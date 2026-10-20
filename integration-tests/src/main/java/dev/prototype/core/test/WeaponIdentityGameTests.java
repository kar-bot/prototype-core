package dev.prototype.core.test;

import com.google.gson.Gson;
import com.mojang.serialization.JsonOps;
import dev.prototype.core.api.*;
import dev.prototype.core.api.minecraft.ClassWeaponForm;
import dev.prototype.core.api.minecraft.PrototypeApi;
import dev.prototype.core.api.minecraft.StealthState;
import dev.prototype.core.conversion.ConversionManager;
import dev.prototype.core.item.PrototypeItems;
import dev.prototype.core.security.WireSnapshot;
import dev.prototype.demo.DemoAddon;
import dev.prototype.demo.DemoItems;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;

public final class WeaponIdentityGameTests {
    private static void owner(GameTestHelper helper, ItemStack stack, Id expected) {
        helper.assertValueEqual(PrototypeApi.classWeaponOwner(stack), Optional.of(expected), "Semantic owner");
        helper.assertTrue(PrototypeApi.isClassWeapon(stack), "Class weapon predicate");
        helper.assertTrue(PrototypeApi.isClassWeaponOf(stack, expected), "Own-class predicate");
    }

    @GameTest public void demo_revealed_and_stealthed_forms_keep_owner(GameTestHelper helper) {
        var stack = new ItemStack(DemoItems.DEMO_SWORD);
        owner(helper, stack, DemoAddon.CLASS_ID);
        StealthState.setStealthed(stack, true); owner(helper, stack, DemoAddon.CLASS_ID);
        stack.remove(StealthState.COMPONENT); owner(helper, stack, DemoAddon.CLASS_ID);
        helper.succeed();
    }

    @GameTest public void visual_disguise_name_and_lore_do_not_change_identity(GameTestHelper helper) {
        var stack = new ItemStack(DemoItems.DEMO_SWORD);
        stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("minecraft", "netherite_sword"));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Netherite Sword"));
        StealthState.setStealthed(stack, true); owner(helper, stack, DemoAddon.CLASS_ID);
        var ordinary = new ItemStack(Items.NETHERITE_SWORD);
        ordinary.set(DataComponents.CUSTOM_NAME, Component.literal("Prototype Demo Sword"));
        helper.assertTrue(PrototypeApi.classWeaponOwner(ordinary).isEmpty(), "Appearance cannot forge class identity");
        helper.succeed();
    }

    @GameTest public void ordinary_and_empty_stacks_have_no_owner(GameTestHelper helper) {
        for (var item : List.of(Items.DIAMOND_SWORD, Items.NETHERITE_SWORD, Items.WOODEN_SWORD, Items.STICK)) {
            helper.assertFalse(PrototypeApi.isClassWeapon(new ItemStack(item)), "Ordinary item is unowned");
        }
        helper.assertTrue(PrototypeApi.classWeaponOwner(null).isEmpty(), "Null-safe query");
        helper.assertTrue(PrototypeApi.classWeaponOwner(ItemStack.EMPTY).isEmpty(), "Empty is unowned");
        helper.assertFalse(PrototypeApi.isClassWeaponOf(new ItemStack(DemoItems.DEMO_SWORD), null), "Null class is not an owner");
        helper.succeed();
    }

    @GameTest public void convertibility_alone_never_establishes_ownership(GameTestHelper helper) {
        for (var item : List.of(PrototypeItems.PROTOTYPE_SWORD, Items.DIAMOND_SWORD)) {
            helper.assertTrue(PrototypeApi.conversions().isConvertible(item), "Physical source is compatible");
            helper.assertFalse(PrototypeApi.isClassWeapon(new ItemStack(item)), "No declaration matches this ordinary stack");
        }
        helper.succeed();
    }

    @GameTest public void alternate_item_form_can_be_owned_without_being_convertible(GameTestHelper helper) {
        var stack = new ItemStack(Items.STICK); stack.set(PrototypeTestAddon.WEAPON_MARKER, "b");
        owner(helper, stack, PrototypeTestAddon.WEAPON_B);
        helper.assertFalse(PrototypeApi.conversions().isConvertible(stack.getItem()), "Identity does not opt into conversion");
        helper.succeed();
    }

    @GameTest public void public_forms_reject_invalid_inputs_and_do_not_mutate_builder(GameTestHelper helper) {
        helper.assertTrue(PrototypeTestAddon.unregisteredWeaponRejected, "Default registry fallback cannot claim an unregistered item");
        int rejected = 0;
        var base = ClassWeaponForm.of(DemoItems.DEMO_SWORD);
        try { ClassWeaponForm.of(Items.AIR); } catch (IllegalArgumentException expected) { rejected++; }
        try { base.with(PrototypeTestAddon.TRANSIENT_MARKER, "a"); } catch (IllegalArgumentException expected) { rejected++; }
        try { base.with(PrototypeTestAddon.WEAPON_MARKER, "a").with(PrototypeTestAddon.WEAPON_MARKER, "b"); }
        catch (IllegalArgumentException expected) { rejected++; }
        try { base.without(PrototypeTestAddon.WEAPON_MARKER).with(PrototypeTestAddon.WEAPON_MARKER, "a"); }
        catch (IllegalArgumentException expected) { rejected++; }
        // The original can still be constrained independently after creating another form.
        base.with(PrototypeTestAddon.WEAPON_MARKER, "a"); base.without(PrototypeTestAddon.WEAPON_MARKER);
        helper.assertValueEqual(rejected, 4, "Invalid forms rejected, original form remains immutable"); helper.succeed();
    }

    @GameTest public void component_markers_partition_same_raw_item_and_ignore_stealth(GameTestHelper helper) {
        var a = PrototypeTestAddon.markedWeapon("a"); var b = PrototypeTestAddon.markedWeapon("b");
        helper.assertTrue(a.getItem() == b.getItem(), "Same physical item supports different semantic forms");
        owner(helper, a, PrototypeTestAddon.WEAPON_A); owner(helper, b, PrototypeTestAddon.WEAPON_B);
        StealthState.setStealthed(a, true); StealthState.setStealthed(b, true);
        owner(helper, a, PrototypeTestAddon.WEAPON_A); owner(helper, b, PrototypeTestAddon.WEAPON_B);
        helper.assertFalse(PrototypeApi.isClassWeapon(PrototypeTestAddon.markedWeapon("unregistered")), "No wildcard marker claims");
        helper.succeed();
    }

    @GameTest public void stolen_inactive_and_banned_weapons_still_resolve(GameTestHelper helper) {
        var service = new CoreService(PrototypeApi.registry()); var player = UUID.randomUUID();
        var foreign = new ItemStack(PrototypeTestAddon.TEST_SWORD);
        helper.assertFalse(service.isAuthorized(player, PrototypeTestAddon.SECRET_CLASS), "Holder is unauthorized");
        owner(helper, foreign, PrototypeTestAddon.SECRET_CLASS);
        service.setHybrid(player, true); service.setClass(player, PrototypeTestAddon.SECRET_CLASS); service.setClass(player, DemoAddon.CLASS_ID);
        helper.assertFalse(service.isAuthorized(player, PrototypeTestAddon.SECRET_CLASS), "Allowed class is inactive");
        service.setClassBanned(player, true); owner(helper, foreign, PrototypeTestAddon.SECRET_CLASS);
        helper.assertFalse(PrototypeApi.isClassWeaponOf(foreign, DemoAddon.CLASS_ID), "Vampire-like caller distinguishes foreign ownership");
        helper.succeed();
    }

    @GameTest public void disabled_addon_identity_does_not_become_ordinary(GameTestHelper helper) {
        var service = new CoreService(PrototypeApi.registry()); var player = UUID.randomUUID();
        service.setClass(player, DemoAddon.CLASS_ID); service.setAddonEnabled(DemoAddon.ADDON_ID, false);
        helper.assertFalse(service.isAuthorized(player, DemoAddon.CLASS_ID), "Gameplay disabled");
        owner(helper, new ItemStack(DemoItems.DEMO_SWORD), DemoAddon.CLASS_ID); helper.succeed();
    }

    @GameTest public void component_identity_survives_disk_and_network_codecs(GameTestHelper helper) {
        var stack = PrototypeTestAddon.markedWeapon("b"); StealthState.setStealthed(stack, true);
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        var restored = ItemStack.CODEC.parse(ops, ItemStack.CODEC.encodeStart(ops, stack).getOrThrow()).getOrThrow();
        owner(helper, restored, PrototypeTestAddon.WEAPON_B);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            ItemStack.STREAM_CODEC.encode(buffer, stack);
            owner(helper, ItemStack.STREAM_CODEC.decode(buffer), PrototypeTestAddon.WEAPON_B);
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest public void identity_registration_conflicts_are_atomic_and_frozen(GameTestHelper helper) {
        helper.assertTrue(PrototypeTestAddon.conflictingWeaponsRejectedAtomically, "Bad forms do not install metadata/providers/controls");
        boolean rejected = false;
        var id = Id.parse("late:weapon");
        try {
            PrototypeApi.registerAddon(new AddonDefinition(id, "Late", "1", null, List.of(new ClassDefinition(id, "Late"))),
                    Map.of(id, context -> Optional.of(new ItemStack(Items.STICK))), AddonLifecycle.NONE, Map.of(),
                    Map.of(id, List.of(ClassWeaponForm.of(Items.STICK))));
        } catch (IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected, "Public five-argument registration freezes with existing registry"); helper.succeed();
    }

    @GameTest public void appearance_components_cannot_be_registered_as_identity(GameTestHelper helper) {
        boolean rejected = false;
        try { ClassWeaponForm.of(DemoItems.DEMO_SWORD).with(DataComponents.CUSTOM_NAME, Component.literal("Pale Sword")); }
        catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "Display names cannot establish ownership");
        rejected = false;
        try { ClassWeaponForm.of(DemoItems.DEMO_SWORD).with(DataComponents.ITEM_MODEL, Identifier.parse("minecraft:diamond_sword")); }
        catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "Model cannot establish ownership"); helper.succeed();
    }

    @GameTest public void anonymous_snapshot_does_not_enumerate_ownership_or_secret_metadata(GameTestHelper helper) {
        var service = new CoreService(PrototypeApi.registry());
        var snapshot = WireSnapshot.create(service, UUID.randomUUID(), false, Map.of(), "", false);
        helper.assertTrue(snapshot.addons().isEmpty(), "No unauthorized registry metadata");
        String json = new Gson().toJson(snapshot);
        for (String forbidden : List.of("semantic_weapon", "classWeapons", "weapon_a", "prototype_tests:secret", "demo_sword")) {
            helper.assertFalse(json.contains(forbidden), "No weapon forms/secret identities in ordinary packets");
        }
        helper.succeed();
    }

    @GameTest public void stack_claimed_custom_data_has_no_authority_without_registered_form(GameTestHelper helper) {
        var stack = new ItemStack(Items.NETHERITE_SWORD);
        var data = new CompoundTag(); data.putString("prototype:owner", "prototype_tests:secret");
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        helper.assertTrue(PrototypeApi.classWeaponOwner(stack).isEmpty(), "Unregistered client-style ownership claims are ignored");
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest public void core_conversion_establishes_destination_owner_and_keeps_stealth(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        var service = new CoreService(PrototypeApi.registry()); service.setClass(player.getUUID(), DemoAddon.CLASS_ID);
        var source = new ItemStack(PrototypeItems.PROTOTYPE_SWORD); StealthState.setStealthed(source, true);
        player.setItemInHand(InteractionHand.MAIN_HAND, source);
        helper.assertTrue(PrototypeApi.conversions().convert(player, service).success(), "Registered demo destination converts");
        owner(helper, player.getMainHandItem(), DemoAddon.CLASS_ID);
        helper.assertTrue(StealthState.isStealthed(player.getMainHandItem()), "Physical stealth transferred");
        service.setClass(player.getUUID(), PrototypeTestAddon.SECRET_CLASS);
        helper.assertTrue(PrototypeApi.conversions().convert(player, service).success(), "Cross-addon conversion succeeds");
        owner(helper, player.getMainHandItem(), PrototypeTestAddon.SECRET_CLASS);
        helper.assertTrue(StealthState.isStealthed(player.getMainHandItem()), "Stealth does not corrupt destination ownership");
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest public void conversion_does_not_copy_source_identity_marker_or_private_state(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel(); var service = new CoreService(PrototypeApi.registry());
        service.setClass(player.getUUID(), PrototypeTestAddon.WEAPON_B);
        var source = PrototypeTestAddon.markedWeapon("a"); StealthState.setStealthed(source, true);
        player.setItemInHand(InteractionHand.MAIN_HAND, source);
        helper.assertTrue(PrototypeApi.conversions().convert(player, service).success(), "Shared-base conversion retains destination semantic marker");
        owner(helper, player.getMainHandItem(), PrototypeTestAddon.WEAPON_B);
        helper.assertValueEqual(player.getMainHandItem().get(PrototypeTestAddon.WEAPON_MARKER), "b", "Source class marker not copied");
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest public void wrong_destination_identity_rejects_without_replacing_source(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel(); var service = new CoreService(PrototypeApi.registry());
        service.setClass(player.getUUID(), DemoAddon.CLASS_ID);
        var source = new ItemStack(PrototypeItems.PROTOTYPE_SWORD); player.setItemInHand(InteractionHand.MAIN_HAND, source);
        for (var item : List.of(PrototypeTestAddon.TEST_SWORD, Items.DIAMOND_SWORD)) {
            var manager = new ConversionManager(); manager.registerConvertible(PrototypeItems.PROTOTYPE_SWORD);
            manager.registerConvertible(item);
            manager.registerProvider(DemoAddon.CLASS_ID, context -> Optional.of(new ItemStack(item)));
            helper.assertFalse(manager.convert(player, service).success(), "Opted-in destination may not produce foreign/unowned identity");
            helper.assertTrue(player.getMainHandItem() == source, "Original source not replaced");
        }
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest public void unsafe_transfer_cannot_overwrite_destination_identity(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel(); var service = new CoreService(PrototypeApi.registry());
        service.setClass(player.getUUID(), PrototypeTestAddon.WEAPON_B);
        var source = PrototypeTestAddon.markedWeapon("a"); player.setItemInHand(InteractionHand.MAIN_HAND, source);
        var manager = new ConversionManager(); manager.registerConvertible(Items.DIAMOND_SWORD);
        manager.registerProvider(PrototypeTestAddon.WEAPON_B, context -> Optional.of(PrototypeTestAddon.markedWeapon("b")));
        var transfer = Id.parse("prototype_tests:bad_marker_transfer");
        manager.registerSafeTransfer(transfer, PrototypeTestAddon.WEAPON_MARKER, value -> value);
        manager.permitTransfer(Items.DIAMOND_SWORD, transfer);
        helper.assertFalse(manager.convert(player, service).success(), "Post-transfer identity mismatch rejects conversion");
        owner(helper, player.getMainHandItem(), PrototypeTestAddon.WEAPON_A);
        helper.assertTrue(player.getMainHandItem() == source, "Original preserved"); helper.succeed();
    }
}
