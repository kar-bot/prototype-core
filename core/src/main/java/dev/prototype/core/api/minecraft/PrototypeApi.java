package dev.prototype.core.api.minecraft;

import dev.prototype.core.PrototypeCore;
import dev.prototype.core.PrototypeRuntime;
import dev.prototype.core.api.AddonDefinition;
import dev.prototype.core.api.AddonLifecycle;
import dev.prototype.core.api.ClassControlSlot;
import dev.prototype.core.api.CoreService;
import dev.prototype.core.api.Id;
import dev.prototype.core.api.Registry;
import dev.prototype.core.conversion.ConversionManager;
import dev.prototype.core.weapon.WeaponIdentity;
import dev.prototype.core.weapon.WeaponStacks;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Stable Fabric-facing API. Register during mod initialization; query on the server thread. */
public final class PrototypeApi {
    private PrototypeApi() {}

    public static void registerAddon(AddonDefinition definition,
                                     Map<Id, SwordConversionProvider> providers,
                                     AddonLifecycle lifecycle) {
        registerAddon(definition, providers, lifecycle, Map.of());
    }

    /**
     * Atomically register metadata, conversion providers, lifecycle, and optional per-class controls.
     * Controls may omit classes/slots. Maps are defensively copied; registration closes at startup.
     */
    public static void registerAddon(AddonDefinition definition,
                                     Map<Id, SwordConversionProvider> providers,
                                     AddonLifecycle lifecycle,
                                     Map<Id, Map<ClassControlSlot, ClassControlHandler>> controls) {
        registerAddon(definition, providers, lifecycle, controls, Map.of());
    }

    /**
     * Add optional semantic weapon forms to the same atomic, startup-frozen addon registration.
     * Each form belongs to a class in this definition; overlapping forms are rejected, even within
     * one class. Existing overloads remain compatible and declare no weapon ownership.
     */
    public static void registerAddon(AddonDefinition definition,
                                     Map<Id, SwordConversionProvider> providers,
                                     AddonLifecycle lifecycle,
                                     Map<Id, Map<ClassControlSlot, ClassControlHandler>> controls,
                                     Map<Id, List<ClassWeaponForm>> weapons) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Registry registry = PrototypeCore.registry();
        if (registry.isFrozen()) throw new IllegalStateException("Prototype registration has closed");
        Set<Id> classIds = definition.classes().stream().map(c -> c.id()).collect(Collectors.toSet());
        if (!classIds.equals(providers.keySet()) || providers.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Each registered class requires exactly one conversion provider");
        }
        // Registry validates every duplicate before mutation; providers cannot overlap existing registered classes.
        conversions().validateProviders(providers);
        var preparedControls = PrototypeCore.controls().prepare(classIds, controls);
        var preparedWeapons = new HashMap<Id, List<WeaponIdentity>>();
        Objects.requireNonNull(weapons, "weapons").forEach((id, forms) ->
                preparedWeapons.put(id, List.copyOf(forms).stream().map(ClassWeaponForm::definition).toList()));
        registry.register(definition, lifecycle, preparedWeapons);
        conversions().registerProviders(providers);
        PrototypeCore.controls().register(classIds, preparedControls);
    }

    public static void registerAddon(AddonDefinition definition, Map<Id, SwordConversionProvider> providers) {
        registerAddon(definition, providers, new AddonLifecycle() {});
    }

    public static void registerConvertible(Item item) { conversions().registerConvertible(item); }
    public static ConversionManager conversions() { return PrototypeCore.conversions(); }
    public static Registry registry() { return PrototypeCore.registry(); }
    public static CoreService service(MinecraftServer server) { return PrototypeRuntime.service(server); }
    public static boolean isAuthorized(ServerPlayer player, Id classId) {
        return service(player.level().getServer()).isAuthorized(player.getUUID(), classId);
    }

    /** Query the authoritative server stack. No registry is synchronized to clients by this API. */
    public static Optional<Id> classWeaponOwner(ItemStack stack) {
        return WeaponStacks.owner(registry(), stack);
    }

    public static boolean isClassWeapon(ItemStack stack) { return classWeaponOwner(stack).isPresent(); }

    public static boolean isClassWeaponOf(ItemStack stack, Id classId) {
        return classId != null && classWeaponOwner(stack).filter(classId::equals).isPresent();
    }
}
