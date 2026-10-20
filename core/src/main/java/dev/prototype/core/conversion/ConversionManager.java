package dev.prototype.core.conversion;

import dev.prototype.core.api.CoreService;
import dev.prototype.core.api.Id;
import dev.prototype.core.api.minecraft.SwordConversionContext;
import dev.prototype.core.api.minecraft.SwordConversionProvider;
import dev.prototype.core.weapon.WeaponStacks;
import dev.prototype.core.item.CoreComponents;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/** Server-thread, in-place transactional conversion with a deliberately narrow state allowlist. */
public final class ConversionManager {
    private static final System.Logger LOGGER = System.getLogger(ConversionManager.class.getName());
    private static final String ACCESS_DENIED = "You do not have access to that class.";
    private static final String FAILED = "Sword conversion could not be completed.";
    private final Map<Id, SwordConversionProvider> providers = new HashMap<>();
    private final Set<Item> convertible = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Id, Transfer<?>> transfers = new LinkedHashMap<>();
    private final Map<Item, Set<Id>> transferConsent = new IdentityHashMap<>();
    private final Set<java.util.UUID> converting = new HashSet<>();
    private boolean frozen;

    public void validateProviders(Map<Id, SwordConversionProvider> additions) {
        ensureOpen();
        Objects.requireNonNull(additions, "providers");
        additions.forEach((id, provider) -> {
            Objects.requireNonNull(id, "classId"); Objects.requireNonNull(provider, "provider");
            if (providers.containsKey(id)) throw new IllegalArgumentException("Duplicate conversion provider: " + id);
        });
    }

    public void registerProviders(Map<Id, SwordConversionProvider> additions) {
        validateProviders(additions);
        providers.putAll(additions);
    }

    public void registerProvider(Id classId, SwordConversionProvider provider) {
        registerProviders(Map.of(classId, provider));
    }

    public void registerConvertible(Item item) {
        ensureOpen();
        if (!convertible.add(Objects.requireNonNull(item, "item"))) {
            throw new IllegalArgumentException("Item already registered as convertible: " + item);
        }
    }

    public boolean isConvertible(Item item) { return convertible.contains(item); }

    /** Register one explicitly safe component and a defensive copier/transformer. */
    public <T> void registerSafeTransfer(Id transferId, DataComponentType<T> component, UnaryOperator<T> copier) {
        ensureOpen();
        Objects.requireNonNull(transferId); Objects.requireNonNull(component); Objects.requireNonNull(copier);
        if (component == DataComponents.CUSTOM_NAME || component == DataComponents.ENCHANTMENTS
                || component == CoreComponents.STEALTH) {
            throw new IllegalArgumentException("This component already has a Core transfer policy");
        }
        if (transfers.containsKey(transferId)
                || transfers.values().stream().anyMatch(transfer -> transfer.component == component)) {
            throw new IllegalArgumentException("Duplicate safe component transfer: " + transferId);
        }
        transfers.put(transferId, new Transfer<>(component, copier));
    }

    /** Both the source item and the destination item must explicitly permit this transfer ID. */
    public void permitTransfer(Item item, Id transferId) {
        ensureOpen(); Objects.requireNonNull(item); Objects.requireNonNull(transferId);
        if (!convertible.contains(item)) throw new IllegalArgumentException("Transfer endpoint must be convertible");
        if (!transfers.containsKey(transferId)) throw new IllegalArgumentException("Unknown component transfer: " + transferId);
        transferConsent.computeIfAbsent(item, ignored -> new HashSet<>()).add(transferId);
    }

    public void freeze() { frozen = true; }

    @SuppressWarnings("removal") // ThreadDeath remains fatal if a third-party callback throws it.
    public ConversionResult convert(ServerPlayer player, CoreService service) {
        Objects.requireNonNull(player); Objects.requireNonNull(service);
        if (!player.level().getServer().isSameThread()) {
            throw new IllegalStateException("Sword conversion must run on the server thread");
        }
        Optional<Id> active = service.operationalActive(player.getUUID());
        if (active.isEmpty() || !service.isAuthorized(player.getUUID(), active.get())) {
            return ConversionResult.rejected(ACCESS_DENIED);
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || held.getCount() != 1 || !isConvertible(held.getItem())) {
            return ConversionResult.rejected("Hold one Prototype-compatible sword to convert.");
        }
        SwordConversionProvider provider = providers.get(active.get());
        if (provider == null) return ConversionResult.rejected(FAILED);
        // Reentrant addon calls cannot recurse into a second inventory transaction.
        if (!converting.add(player.getUUID())) return ConversionResult.rejected(FAILED);
        InventorySnapshot snapshot = new InventorySnapshot(player);
        long revision = service.revision();
        try {
            ItemStack source = held.copy();
            SwordConversionContext context = new SwordConversionContext(player, active.get(), source);
            Optional<ItemStack> produced = Objects.requireNonNull(provider.convert(context), "provider result");
            if (!snapshot.unchanged(player)) return rejectAndRestore(player, snapshot);
            if (produced.isEmpty()) return ConversionResult.rejected("This sword cannot be converted into your active class.");
            ItemStack result = produced.get().copy();
            if (result.isEmpty() || result.getCount() != 1 || !isConvertible(result.getItem())) {
                return ConversionResult.rejected(FAILED);
            }
            transferCommonState(source, result);
            transferOptedInState(source, result);
            // Opted-in identities must still describe the destination after all physical transfers.
            if (service.registry().hasClassWeapons(active.get())
                    && !WeaponStacks.owner(service.registry(), result).filter(active.get()::equals).isPresent()) {
                return ConversionResult.rejected(FAILED);
            }
            // A provider/transfer hook may execute other mod code; validate again immediately before commit.
            if (!snapshot.unchanged(player)) return rejectAndRestore(player, snapshot);
            if (service.revision() != revision || !service.isAuthorized(player.getUUID(), active.get())) {
                return ConversionResult.rejected(ACCESS_DENIED);
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, result);
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            return ConversionResult.converted();
        } catch (Throwable exception) {
            if (exception instanceof VirtualMachineError fatal) throw fatal;
            if (exception instanceof ThreadDeath fatal) throw fatal;
            snapshot.restore(player);
            LOGGER.log(System.Logger.Level.WARNING, "Conversion callback failed for class " + active.get(), exception);
            return ConversionResult.rejected(FAILED);
        } finally {
            converting.remove(player.getUUID());
        }
    }

    private static ConversionResult rejectAndRestore(ServerPlayer player, InventorySnapshot snapshot) {
        snapshot.restore(player);
        LOGGER.log(System.Logger.Level.WARNING, "Conversion callback attempted to change a player inventory before commit");
        return ConversionResult.rejected(FAILED);
    }

    private static void transferCommonState(ItemStack source, ItemStack result) {
        var name = source.get(DataComponents.CUSTOM_NAME);
        if (name != null) result.set(DataComponents.CUSTOM_NAME, name.copy());
        // Preserve explicit false as well as true; a factory cannot stealth a visible source by default.
        result.set(CoreComponents.STEALTH, source.getOrDefault(CoreComponents.STEALTH, false));
        ItemEnchantments.Mutable accepted = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        mergeSupportedEnchantments(result.getEnchantments(), result, accepted);
        mergeSupportedEnchantments(source.getEnchantments(), result, accepted);
        result.set(DataComponents.ENCHANTMENTS, accepted.toImmutable());
    }

    private static void mergeSupportedEnchantments(ItemEnchantments original, ItemStack destination,
                                                   ItemEnchantments.Mutable accepted) {
        List<Holder<Enchantment>> sorted = original.keySet().stream()
                .sorted(Comparator.comparing(Holder::getRegisteredName)).toList();
        for (Holder<Enchantment> enchantment : sorted) {
            Enchantment definition = enchantment.value();
            if (!definition.isSupportedItem(destination)) continue;
            int level = Math.min(original.getLevel(enchantment), definition.getMaxLevel());
            if (level < definition.getMinLevel()) continue;
            boolean compatible = accepted.keySet().stream().allMatch(existing -> existing.equals(enchantment)
                    || Enchantment.areCompatible(existing, enchantment));
            if (compatible) accepted.upgrade(enchantment, level);
        }
    }

    private void transferOptedInState(ItemStack source, ItemStack destination) {
        Set<Id> sender = transferConsent.getOrDefault(source.getItem(), Set.of());
        Set<Id> receiver = transferConsent.getOrDefault(destination.getItem(), Set.of());
        transfers.forEach((id, transfer) -> {
            if (sender.contains(id) && receiver.contains(id)) transfer.apply(source, destination);
        });
    }

    private void ensureOpen() {
        if (frozen) throw new IllegalStateException("Prototype conversion registration has closed");
    }

    private record Transfer<T>(DataComponentType<T> component, UnaryOperator<T> copier) {
        void apply(ItemStack source, ItemStack destination) {
            T value = source.get(component);
            if (value != null) destination.set(component, Objects.requireNonNull(copier.apply(value), "transferred value"));
        }
    }

    private static final class InventorySnapshot {
        private final List<ItemStack> items = new ArrayList<>();
        private final int selected;
        private final AbstractContainerMenu menu;
        private final ItemStack carried;

        InventorySnapshot(ServerPlayer player) {
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) items.add(inventory.getItem(slot).copy());
            selected = inventory.getSelectedSlot();
            menu = player.containerMenu;
            carried = menu.getCarried().copy();
        }

        boolean unchanged(ServerPlayer player) {
            Inventory inventory = player.getInventory();
            if (inventory.getSelectedSlot() != selected || inventory.getContainerSize() != items.size()
                    || player.containerMenu != menu || !ItemStack.matches(carried, menu.getCarried())) return false;
            for (int slot = 0; slot < items.size(); slot++) {
                if (!ItemStack.matches(items.get(slot), inventory.getItem(slot))) return false;
            }
            return true;
        }

        void restore(ServerPlayer player) {
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < items.size(); slot++) inventory.setItem(slot, items.get(slot).copy());
            inventory.setSelectedSlot(selected);
            menu.setCarried(carried.copy());
            inventory.setChanged();
            player.containerMenu.broadcastChanges();
        }
    }
}
