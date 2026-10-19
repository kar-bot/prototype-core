package dev.prototype.core.test;

import com.mojang.serialization.JsonOps;
import dev.prototype.core.api.AddonDefinition;
import dev.prototype.core.api.ClassDefinition;
import dev.prototype.core.api.CoreService;
import dev.prototype.core.api.Id;
import dev.prototype.core.api.Registry;
import dev.prototype.core.api.minecraft.StealthState;
import dev.prototype.core.api.minecraft.SwordConversionProvider;
import dev.prototype.core.conversion.ConversionManager;
import dev.prototype.core.conversion.ConversionResult;
import dev.prototype.core.item.PrototypeItems;
import dev.prototype.demo.DemoItems;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

/** Real registered components, items, datapack tags and server player inventories. */
public final class PrototypeGameTests {
    private static final Id ADDON = Id.parse("conversion_test:addon");
    private static final Id CLASS = Id.parse("conversion_test:class");
    private static final Id TRANSFER = Id.parse("conversion_test:shared_state");

    private record Fixture(ServerPlayer player, CoreService service, ConversionManager conversions) {
        ItemStack held() { return player.getMainHandItem(); }
        ConversionResult convert() { return conversions.convert(player, service); }
    }

    private static Fixture fixture(GameTestHelper helper) {
        return fixture(helper, context -> Optional.of(new ItemStack(DemoItems.DEMO_SWORD)));
    }
    @SuppressWarnings("removal") // Fabric/Minecraft's mock provides the server packet connection required by inventories.
    private static Fixture fixture(GameTestHelper helper, SwordConversionProvider provider) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Registry registry = new Registry();
        registry.register(new AddonDefinition(ADDON, "Conversion test", "0.1.0", null,
                List.of(new ClassDefinition(CLASS, "Conversion test class"))));
        CoreService service = new CoreService(registry);
        service.start();
        service.setClass(player.getUUID(), CLASS);
        ConversionManager manager = new ConversionManager();
        manager.registerConvertible(PrototypeItems.PROTOTYPE_SWORD);
        manager.registerConvertible(DemoItems.DEMO_SWORD);
        manager.registerConvertible(PrototypeTestAddon.TEST_SWORD);
        manager.registerProvider(CLASS, provider);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PrototypeItems.PROTOTYPE_SWORD));
        return new Fixture(player, service, manager);
    }

    private static Holder<Enchantment> enchantment(GameTestHelper helper, ResourceKey<Enchantment> key) {
        return helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }
    private static CustomData privateData() {
        CompoundTag data = new CompoundTag(); data.putString("unrelated_addon:progression", "private");
        return CustomData.of(data);
    }
    private static void unchangedFailure(GameTestHelper helper, Fixture fixture) {
        ItemStack before = fixture.held().copy();
        helper.assertFalse(fixture.convert().success(), "Conversion is rejected");
        helper.assertTrue(ItemStack.matches(before, fixture.held()), "Rejected conversion preserves original sword state");
    }

    @GameTest public void prototype_converts_into_external_demo(GameTestHelper helper) {
        Fixture f = fixture(helper);
        f.player().getInventory().setItem(8, new ItemStack(Items.DIAMOND, 17));
        helper.assertTrue(f.convert().success(), "Prototype conversion succeeds");
        helper.assertTrue(f.held().is(DemoItems.DEMO_SWORD), "Destination is the external demo sword");
        helper.assertValueEqual(f.player().getInventory().getItem(8).getCount(), 17, "Unrelated inventory stack retained");
        helper.assertTrue(f.service().isAuthorized(f.player().getUUID(), CLASS), "Conversion does not switch authorization");
        helper.succeed();
    }

    @GameTest public void external_sword_can_convert_into_another_family_sword(GameTestHelper helper) {
        Fixture f = fixture(helper, context -> Optional.of(new ItemStack(PrototypeTestAddon.TEST_SWORD)));
        f.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(DemoItems.DEMO_SWORD));
        helper.assertTrue(f.convert().success(), "Cross-addon sword conversion succeeds");
        helper.assertTrue(f.held().is(PrototypeTestAddon.TEST_SWORD), "Target factory selects the new sword");
        helper.succeed();
    }

    @GameTest public void incompatible_item_is_rejected(GameTestHelper helper) {
        Fixture f = fixture(helper);
        f.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        unchangedFailure(helper, f); helper.succeed();
    }

    @GameTest public void class_ban_rejects_conversion(GameTestHelper helper) {
        Fixture f = fixture(helper); f.service().setClassBanned(f.player().getUUID(), true);
        unchangedFailure(helper, f);
        helper.assertTrue(f.service().allowedClasses(f.player().getUUID()).contains(CLASS), "Ban retains assignment");
        helper.succeed();
    }

    @GameTest public void unauthorized_player_is_rejected_without_ids(GameTestHelper helper) {
        Fixture f = fixture(helper); f.service().clearClasses(f.player().getUUID());
        ConversionResult result = f.convert();
        helper.assertFalse(result.success(), "No assignment cannot convert");
        helper.assertValueEqual(result.message(), "You do not have access to that class.", "Generic private denial");
        helper.assertTrue(f.held().is(PrototypeItems.PROTOTYPE_SWORD), "Source retained"); helper.succeed();
    }

    @GameTest public void disabled_addon_rejects_then_reenable_resumes(GameTestHelper helper) {
        Fixture f = fixture(helper); f.service().setAddonEnabled(ADDON, false);
        unchangedFailure(helper, f);
        helper.assertTrue(f.service().allowedClasses(f.player().getUUID()).contains(CLASS), "Disable preserves assignment");
        f.service().setAddonEnabled(ADDON, true);
        helper.assertTrue(f.convert().success(), "Reenabled addon resumes eligibility"); helper.succeed();
    }

    @GameTest public void provider_can_decline_without_deleting_source(GameTestHelper helper) {
        Fixture f = fixture(helper, context -> Optional.empty());
        unchangedFailure(helper, f); helper.succeed();
    }

    @GameTest public void throwing_provider_preserves_inventory(GameTestHelper helper) {
        Fixture f = fixture(helper, context -> { throw new IllegalStateException("Expected provider failure"); });
        unchangedFailure(helper, f); helper.succeed();
    }

    @GameTest public void nonfatal_provider_error_preserves_inventory(GameTestHelper helper) {
        Fixture f = fixture(helper, context -> { throw new AssertionError("Expected provider failure"); });
        unchangedFailure(helper, f); helper.succeed();
    }

    @GameTest public void nonconvertible_factory_output_is_rejected(GameTestHelper helper) {
        Fixture f = fixture(helper, context -> Optional.of(new ItemStack(Items.DIAMOND_SWORD)));
        unchangedFailure(helper, f); helper.succeed();
    }

    @GameTest public void multiple_destination_items_are_rejected(GameTestHelper helper) {
        Fixture f = fixture(helper, context -> Optional.of(new ItemStack(DemoItems.DEMO_SWORD, 2)));
        unchangedFailure(helper, f); helper.succeed();
    }

    @GameTest public void stacked_source_swords_are_rejected(GameTestHelper helper) {
        Fixture f = fixture(helper); f.held().setCount(2);
        unchangedFailure(helper, f); helper.succeed();
    }

    @GameTest public void core_stealth_true_survives_conversion(GameTestHelper helper) {
        Fixture f = fixture(helper); StealthState.setStealthed(f.held(), true);
        helper.assertTrue(f.convert().success(), "Conversion succeeds");
        helper.assertTrue(StealthState.isStealthed(f.held()), "Neutral stealth state survives"); helper.succeed();
    }

    @GameTest public void visible_source_overrides_factory_stealth(GameTestHelper helper) {
        Fixture f = fixture(helper, context -> {
            ItemStack result = new ItemStack(DemoItems.DEMO_SWORD); StealthState.setStealthed(result, true);
            return Optional.of(result);
        });
        StealthState.setStealthed(f.held(), false);
        helper.assertTrue(f.convert().success(), "Conversion succeeds");
        helper.assertFalse(StealthState.isStealthed(f.held()), "Explicit false survives too"); helper.succeed();
    }

    @GameTest public void custom_name_survives_conversion(GameTestHelper helper) {
        Fixture f = fixture(helper); f.held().set(DataComponents.CUSTOM_NAME, Component.literal("Founder's blade"));
        helper.assertTrue(f.convert().success(), "Conversion succeeds");
        helper.assertValueEqual(f.held().getCustomName().getString(), "Founder's blade", "Custom name retained");
        helper.succeed();
    }

    @GameTest public void unrelated_private_data_does_not_leak(GameTestHelper helper) {
        Fixture f = fixture(helper); f.held().set(DataComponents.CUSTOM_DATA, privateData());
        helper.assertTrue(f.convert().success(), "Conversion succeeds");
        helper.assertTrue(f.held().get(DataComponents.CUSTOM_DATA) == null, "No blanket private component copying");
        helper.succeed();
    }

    @GameTest public void safe_transfer_requires_bilateral_consent(GameTestHelper helper) {
        Fixture f = fixture(helper); CustomData data = privateData();
        f.held().set(DataComponents.CUSTOM_DATA, data);
        f.conversions().registerSafeTransfer(TRANSFER, DataComponents.CUSTOM_DATA, value -> CustomData.of(value.copyTag()));
        f.conversions().permitTransfer(PrototypeItems.PROTOTYPE_SWORD, TRANSFER);
        f.conversions().permitTransfer(DemoItems.DEMO_SWORD, TRANSFER);
        helper.assertTrue(f.convert().success(), "Agreed transfer succeeds");
        helper.assertValueEqual(f.held().get(DataComponents.CUSTOM_DATA), data, "Explicit agreed component preserved");
        helper.succeed();
    }

    @GameTest public void one_sided_transfer_does_not_copy_private_state(GameTestHelper helper) {
        Fixture f = fixture(helper); f.held().set(DataComponents.CUSTOM_DATA, privateData());
        f.conversions().registerSafeTransfer(TRANSFER, DataComponents.CUSTOM_DATA, value -> CustomData.of(value.copyTag()));
        f.conversions().permitTransfer(PrototypeItems.PROTOTYPE_SWORD, TRANSFER);
        helper.assertTrue(f.convert().success(), "Conversion otherwise succeeds");
        helper.assertTrue(f.held().get(DataComponents.CUSTOM_DATA) == null, "Destination did not consent");
        helper.succeed();
    }

    @GameTest public void unsupported_enchantments_are_removed(GameTestHelper helper) {
        Fixture f = fixture(helper);
        var sharpness = enchantment(helper, Enchantments.SHARPNESS);
        var power = enchantment(helper, Enchantments.POWER);
        f.held().enchant(sharpness, 3); f.held().enchant(power, 2);
        helper.assertTrue(f.convert().success(), "Conversion succeeds");
        helper.assertValueEqual(f.held().getEnchantments().getLevel(sharpness), 3, "Supported sword enchantment retained");
        helper.assertValueEqual(f.held().getEnchantments().getLevel(power), 0, "Bow enchantment not valid on target sword");
        helper.succeed();
    }

    @GameTest public void enchantment_levels_and_exclusivity_are_validated(GameTestHelper helper) {
        Fixture f = fixture(helper);
        var sharpness = enchantment(helper, Enchantments.SHARPNESS);
        var smite = enchantment(helper, Enchantments.SMITE);
        f.held().enchant(sharpness, 100); f.held().enchant(smite, 3);
        helper.assertTrue(f.convert().success(), "Conversion succeeds");
        helper.assertValueEqual(f.held().getEnchantments().getLevel(sharpness), sharpness.value().getMaxLevel(), "Level capped");
        helper.assertValueEqual(f.held().getEnchantments().getLevel(smite), 0, "Conflicting enchantment omitted deterministically");
        helper.succeed();
    }

    @GameTest public void provider_source_accessor_is_a_copy(GameTestHelper helper) {
        AtomicBoolean copied = new AtomicBoolean();
        Fixture f = fixture(helper, context -> {
            ItemStack source = context.source(); source.setCount(0);
            copied.set(context.source().getCount() == 1);
            return Optional.empty();
        });
        unchangedFailure(helper, f); helper.assertTrue(copied.get(), "Each source accessor is independent"); helper.succeed();
    }

    @GameTest public void callback_inventory_mutation_is_rolled_back(GameTestHelper helper) {
        Fixture f = fixture(helper, context -> {
            context.player().getInventory().setItem(7, new ItemStack(Items.DIAMOND, 64));
            context.player().getMainHandItem().setCount(0);
            return Optional.of(new ItemStack(DemoItems.DEMO_SWORD));
        });
        f.player().getInventory().setItem(7, new ItemStack(Items.APPLE, 3));
        unchangedFailure(helper, f);
        helper.assertTrue(f.player().getInventory().getItem(7).is(Items.APPLE), "Other slot identity restored");
        helper.assertValueEqual(f.player().getInventory().getItem(7).getCount(), 3, "Other slot count restored"); helper.succeed();
    }

    @GameTest public void selected_slot_mutation_is_rolled_back(GameTestHelper helper) {
        Fixture f = fixture(helper, context -> {
            context.player().getInventory().setSelectedSlot(5);
            return Optional.of(new ItemStack(DemoItems.DEMO_SWORD));
        });
        int selected = f.player().getInventory().getSelectedSlot();
        unchangedFailure(helper, f);
        helper.assertValueEqual(f.player().getInventory().getSelectedSlot(), selected, "Selected hotbar slot restored"); helper.succeed();
    }

    @GameTest public void cursor_mutation_is_rolled_back(GameTestHelper helper) {
        Fixture f = fixture(helper, context -> {
            context.player().containerMenu.setCarried(new ItemStack(Items.DIAMOND));
            return Optional.of(new ItemStack(DemoItems.DEMO_SWORD));
        });
        unchangedFailure(helper, f); helper.assertTrue(f.player().containerMenu.getCarried().isEmpty(), "Cursor restored"); helper.succeed();
    }

    @GameTest public void prototype_matches_wooden_combat_and_is_unbreakable(GameTestHelper helper) {
        ItemStack prototype = new ItemStack(PrototypeItems.PROTOTYPE_SWORD);
        ItemStack wooden = new ItemStack(Items.WOODEN_SWORD);
        helper.assertValueEqual(prototype.get(DataComponents.ATTRIBUTE_MODIFIERS), wooden.get(DataComponents.ATTRIBUTE_MODIFIERS),
                "Combat attributes exactly equal vanilla wooden sword");
        helper.assertTrue(prototype.get(DataComponents.UNBREAKABLE) != null, "Unbreakable component present");
        helper.assertFalse(prototype.isDamageableItem(), "Normal durability damage is prevented"); helper.succeed();
    }

    @GameTest public void core_stealth_persists_in_real_itemstack_codec(GameTestHelper helper) {
        ItemStack source = new ItemStack(PrototypeItems.PROTOTYPE_SWORD); StealthState.setStealthed(source, true);
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        var encoded = ItemStack.CODEC.encodeStart(ops, source).getOrThrow();
        ItemStack decoded = ItemStack.CODEC.parse(ops, encoded).getOrThrow();
        helper.assertTrue(ItemStack.matches(source, decoded), "Sword and components survive persistence codec");
        helper.assertTrue(StealthState.isStealthed(decoded), "Persisted flag remains true"); helper.succeed();
    }

    @GameTest public void core_stealth_survives_real_itemstack_network_codec(GameTestHelper helper) {
        ItemStack source = new ItemStack(DemoItems.DEMO_SWORD); StealthState.setStealthed(source, true);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            ItemStack.STREAM_CODEC.encode(buffer, source);
            ItemStack decoded = ItemStack.STREAM_CODEC.decode(buffer);
            helper.assertTrue(ItemStack.matches(source, decoded), "Sword and components survive network codec");
            helper.assertTrue(StealthState.isStealthed(decoded), "Network synchronized flag remains true");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest public void duplicate_and_late_registration_are_rejected(GameTestHelper helper) {
        Fixture f = fixture(helper); boolean duplicate = false, late = false;
        try { f.conversions().registerProvider(CLASS, context -> Optional.empty()); }
        catch (IllegalArgumentException expected) { duplicate = true; }
        f.conversions().freeze();
        try { f.conversions().registerConvertible(Items.STICK); }
        catch (IllegalStateException expected) { late = true; }
        helper.assertTrue(duplicate && late, "Duplicate and late registrations fail clearly"); helper.succeed();
    }

    @GameTest public void provider_authorization_change_prevents_stale_commit(GameTestHelper helper) {
        AtomicReference<Fixture> reference = new AtomicReference<>();
        Fixture f = fixture(helper, context -> {
            reference.get().service().setAddonEnabled(ADDON, false);
            return Optional.of(new ItemStack(DemoItems.DEMO_SWORD));
        });
        reference.set(f);
        unchangedFailure(helper, f);
        helper.assertFalse(f.service().isAuthorized(f.player().getUUID(), CLASS), "Final authorization recheck reflects disable");
        helper.succeed();
    }

    @GameTest public void reentrant_conversion_cannot_commit_twice(GameTestHelper helper) {
        AtomicReference<Fixture> reference = new AtomicReference<>(); AtomicBoolean nestedRejected = new AtomicBoolean();
        Fixture f = fixture(helper, context -> {
            nestedRejected.set(!reference.get().convert().success());
            return Optional.of(new ItemStack(DemoItems.DEMO_SWORD));
        });
        reference.set(f);
        helper.assertTrue(f.convert().success(), "Outer conversion commits once");
        helper.assertTrue(nestedRejected.get(), "Nested conversion is rejected");
        helper.assertValueEqual(f.held().getCount(), 1, "One destination item"); helper.succeed();
    }

    @GameTest public void failing_transfer_does_not_commit_or_lose_source(GameTestHelper helper) {
        Fixture f = fixture(helper); f.held().set(DataComponents.CUSTOM_DATA, privateData());
        f.conversions().registerSafeTransfer(TRANSFER, DataComponents.CUSTOM_DATA,
                value -> { throw new IllegalStateException("Expected transfer failure"); });
        f.conversions().permitTransfer(PrototypeItems.PROTOTYPE_SWORD, TRANSFER);
        f.conversions().permitTransfer(DemoItems.DEMO_SWORD, TRANSFER);
        unchangedFailure(helper, f); helper.succeed();
    }

    @GameTest public void factory_defaults_do_not_inherit_source_combat_properties(GameTestHelper helper) {
        Fixture f = fixture(helper, context -> Optional.of(new ItemStack(PrototypeTestAddon.TEST_SWORD)));
        helper.assertTrue(f.convert().success(), "Conversion succeeds");
        ItemStack expected = new ItemStack(PrototypeTestAddon.TEST_SWORD);
        helper.assertValueEqual(f.held().get(DataComponents.ATTRIBUTE_MODIFIERS), expected.get(DataComponents.ATTRIBUTE_MODIFIERS),
                "Iron target keeps its own combat stats");
        helper.assertTrue(f.held().get(DataComponents.UNBREAKABLE) == null, "Prototype unbreakable state does not leak");
        helper.succeed();
    }

    @GameTest public void survival_recipe_assembles_unbreakable_prototype(GameTestHelper helper) {
        var input = CraftingInput.of(2, 3, List.of(ItemStack.EMPTY, new ItemStack(Items.OAK_PLANKS),
                new ItemStack(Items.IRON_NUGGET), new ItemStack(Items.OAK_PLANKS),
                ItemStack.EMPTY, new ItemStack(Items.STICK)));
        var recipe = helper.getLevel().getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel())
                .orElseThrow(() -> helper.assertionException("Prototype survival recipe is available"));
        ItemStack crafted = recipe.value().assemble(input);
        helper.assertTrue(crafted.is(PrototypeItems.PROTOTYPE_SWORD), "Real crafting input produces Prototype Sword");
        helper.assertValueEqual(crafted.getCount(), 1, "Recipe produces one sword");
        helper.assertTrue(crafted.get(DataComponents.UNBREAKABLE) != null, "Crafted sword receives default unbreakable state");
        helper.succeed();
    }
}
