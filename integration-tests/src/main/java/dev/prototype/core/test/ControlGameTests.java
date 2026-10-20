package dev.prototype.core.test;

import dev.prototype.core.PrototypeCore;
import dev.prototype.core.PrototypeRuntime;
import dev.prototype.core.api.*;
import dev.prototype.core.api.minecraft.PrototypeApi;
import dev.prototype.core.control.ClassControlDispatcher;
import dev.prototype.core.network.ControlPayload;
import dev.prototype.core.network.PrototypeNetworking;
import dev.prototype.demo.DemoAddon;
import dev.prototype.demo.DemoStatusPayload;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

/** Runs real handlers, payload codecs and transport admission on a dedicated Minecraft server. */
public final class ControlGameTests {
    @GameTest public void public_registration_is_atomic_and_conflicts_are_rejected(GameTestHelper helper) {
        helper.assertTrue(PrototypeTestAddon.invalidControlsRejectedAtomically, "Foreign controls leave no addon/provider registration behind");
        helper.assertTrue(PrototypeTestAddon.duplicateControlsRejected, "Duplicate registration cannot replace existing controls");
        helper.succeed();
    }
    private record Fixture(ServerPlayer player, CoreService service) {
        void press(ClassControlSlot slot) { PrototypeCore.controls().dispatch(player, service, slot); }
        List<String> calls() { return PrototypeTestAddon.controls(player.getUUID()); }
    }

    @SuppressWarnings("removal")
    private static Fixture fixture(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        var service = new CoreService(PrototypeApi.registry());
        service.setClass(player.getUUID(), PrototypeTestAddon.CONTROL_A);
        return new Fixture(player, service);
    }

    @GameTest public void each_slot_invokes_only_its_registered_handler(GameTestHelper helper) {
        var f = fixture(helper);
        f.press(ClassControlSlot.ABILITY_1); f.press(ClassControlSlot.ABILITY_2); f.press(ClassControlSlot.STATUS);
        helper.assertValueEqual(f.calls(), List.of("a1", "a2", "private-status"), "Only requested slots execute");
        helper.succeed();
    }

    @GameTest public void hybrid_switch_routes_only_current_class_without_reconnect(GameTestHelper helper) {
        var f = fixture(helper);
        f.service.setHybrid(f.player.getUUID(), true);
        f.service.addClass(f.player.getUUID(), PrototypeTestAddon.CONTROL_B);
        f.press(ClassControlSlot.ABILITY_1);
        f.service.switchClass(f.player.getUUID(), PrototypeTestAddon.CONTROL_B);
        f.press(ClassControlSlot.ABILITY_1); f.press(ClassControlSlot.ABILITY_2); f.press(ClassControlSlot.STATUS);
        helper.assertValueEqual(f.calls(), List.of("a1", "b1"), "Only active class dispatches; unused slots are silent");
        helper.succeed();
    }

    @GameTest public void unauthorized_banned_disabled_and_closed_services_never_dispatch(GameTestHelper helper) {
        var f = fixture(helper);
        f.service.clearClasses(f.player.getUUID()); f.press(ClassControlSlot.ABILITY_1);
        f.service.setClass(f.player.getUUID(), PrototypeTestAddon.CONTROL_A);
        f.service.setClassBanned(f.player.getUUID(), true); f.press(ClassControlSlot.ABILITY_1);
        f.service.setClassBanned(f.player.getUUID(), false);
        f.service.setAddonEnabled(PrototypeTestAddon.CONTROL_ADDON, false); f.press(ClassControlSlot.STATUS);
        f.service.close(); f.press(ClassControlSlot.ABILITY_2);
        helper.assertTrue(f.calls().isEmpty(), "No unauthorized handler executes");
        helper.succeed();
    }

    @GameTest public void disable_reenable_and_ban_unban_resume_cleanly(GameTestHelper helper) {
        var f = fixture(helper);
        f.press(ClassControlSlot.ABILITY_1);
        f.service.setAddonEnabled(PrototypeTestAddon.CONTROL_ADDON, false); f.press(ClassControlSlot.ABILITY_1);
        f.service.setAddonEnabled(PrototypeTestAddon.CONTROL_ADDON, true); f.press(ClassControlSlot.ABILITY_2);
        f.service.setClassBanned(f.player.getUUID(), true); f.press(ClassControlSlot.STATUS);
        f.service.setClassBanned(f.player.getUUID(), false); f.press(ClassControlSlot.STATUS);
        helper.assertValueEqual(f.calls(), List.of("a1", "a2", "private-status"), "Eligibility restored without replaying suppressed controls");
        helper.succeed();
    }

    @GameTest public void unregistered_historical_active_class_is_inert(GameTestHelper helper) {
        var f = fixture(helper);
        var unknown = Id.parse("missing:historical");
        var restored = new CoreService(PrototypeApi.registry(), new CoreState(Map.of(f.player.getUUID(),
                new PlayerState(false, false, Set.of(unknown), unknown)), Map.of(), 1));
        PrototypeCore.controls().dispatch(f.player, restored, ClassControlSlot.STATUS);
        helper.assertTrue(f.calls().isEmpty(), "Unknown active history executes no handler"); helper.succeed();
    }

    @GameTest public void handler_absence_and_failure_are_isolated(GameTestHelper helper) {
        var f = fixture(helper);
        var dispatcher = new ClassControlDispatcher();
        dispatcher.dispatch(f.player, f.service, ClassControlSlot.ABILITY_1);
        dispatcher.register(Set.of(PrototypeTestAddon.CONTROL_A), Map.of(PrototypeTestAddon.CONTROL_A,
                Map.of(ClassControlSlot.ABILITY_1, player -> { throw new IllegalStateException("Expected test failure"); })));
        dispatcher.dispatch(f.player, f.service, ClassControlSlot.ABILITY_1);
        f.press(ClassControlSlot.STATUS);
        helper.assertValueEqual(f.calls(), List.of("private-status"), "Failure does not stop later dispatch"); helper.succeed();
    }

    @GameTest public void controls_registration_cannot_mutate_after_startup(GameTestHelper helper) {
        boolean rejected = false;
        try { PrototypeApi.registerAddon(new AddonDefinition(Id.parse("late:controls"), "Late", "1", null,
                List.of(new ClassDefinition(Id.parse("late:class"), "Late"))), Map.of(), AddonLifecycle.NONE, Map.of()); }
        catch (IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected, "Frozen public registration rejects late control bindings"); helper.succeed();
    }

    private static RegistryFriendlyByteBuf buffer(GameTestHelper helper) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
    }

    @GameTest public void protocol_is_exactly_one_neutral_byte_and_unknown_slots_are_silent(GameTestHelper helper) {
        for (var slot : ClassControlSlot.values()) {
            var buffer = buffer(helper);
            try {
                ControlPayload.CODEC.encode(buffer, new ControlPayload(slot));
                helper.assertValueEqual(buffer.readableBytes(), 1, "No class/addon/gameplay fields on wire");
                helper.assertValueEqual(ControlPayload.CODEC.decode(buffer).slot(), slot, "Slot round trip");
            } finally { buffer.release(); }
        }
        for (int value = 3; value <= 255; value++) helper.assertTrue(new ControlPayload(value).slot() == null, "Invalid slot has no dispatch target");
        helper.succeed();
    }

    @GameTest public void empty_or_appended_class_selector_packets_are_rejected(GameTestHelper helper) {
        for (boolean append : List.of(false, true)) {
            var buffer = buffer(helper);
            try {
                if (append) { buffer.writeByte(0); buffer.writeUtf("secret:arbitrary_class"); }
                boolean rejected = false;
                try { ControlPayload.CODEC.decode(buffer); } catch (RuntimeException expected) { rejected = true; }
                helper.assertTrue(rejected, "Malformed length cannot add an arbitrary class selector");
            } finally { buffer.release(); }
        }
        helper.succeed();
    }

    @GameTest public void status_handler_can_create_addon_owned_schema(GameTestHelper helper) {
        var f = fixture(helper);
        var observed = new AtomicReference<DemoStatusPayload>();
        var dispatcher = new ClassControlDispatcher();
        dispatcher.register(Set.of(PrototypeTestAddon.CONTROL_A), Map.of(PrototypeTestAddon.CONTROL_A,
                Map.of(ClassControlSlot.STATUS, player -> observed.set(new DemoStatusPayload(true, false)))));
        dispatcher.dispatch(f.player, f.service, ClassControlSlot.STATUS);
        helper.assertTrue(observed.get().open(), "Addon status response is independent of Core schema");
        var buffer = buffer(helper);
        try {
            DemoStatusPayload.CODEC.encode(buffer, observed.get());
            helper.assertValueEqual(DemoStatusPayload.CODEC.decode(buffer), observed.get(), "Private addon schema round trip");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest public void demo_registers_ability_one_status_and_unused_ability_two(GameTestHelper helper) {
        var f = fixture(helper);
        f.service.setClass(f.player.getUUID(), DemoAddon.CLASS_ID);
        for (var slot : ClassControlSlot.values()) f.press(slot);
        helper.assertTrue(f.calls().isEmpty(), "Demo owns its handlers; no test/foreign class dispatch"); helper.succeed();
    }

    @GameTest public void off_thread_dispatch_is_rejected(GameTestHelper helper) {
        var f = fixture(helper);
        var rejected = new AtomicBoolean();
        Thread thread = Thread.ofVirtual().start(() -> {
            try { f.press(ClassControlSlot.ABILITY_1); }
            catch (IllegalStateException expected) { rejected.set(true); }
        });
        try { thread.join(1000); } catch (InterruptedException exception) { throw new RuntimeException(exception); }
        helper.assertTrue(rejected.get(), "Dispatch requires server thread"); helper.assertTrue(f.calls().isEmpty(), "No off-thread gameplay"); helper.succeed();
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, ServerPlayer> sessions(GameTestHelper helper) throws Exception {
        var field = PlayerList.class.getDeclaredField("playersByUUID");
        field.setAccessible(true);
        return (Map<UUID, ServerPlayer>) field.get(helper.getLevel().getServer().getPlayerList());
    }

    @GameTest public void authenticated_transport_rechecks_authorization_and_limits_spam(GameTestHelper helper) throws Exception {
        var f = fixture(helper);
        var server = helper.getLevel().getServer();
        var service = PrototypeRuntime.service(server);
        service.setClass(f.player.getUUID(), PrototypeTestAddon.CONTROL_A);
        sessions(helper).put(f.player.getUUID(), f.player);
        try {
            PrototypeNetworking.receiveControl(server, f.player, new ControlPayload(255));
            service.setClassBanned(f.player.getUUID(), true);
            PrototypeNetworking.receiveControl(server, f.player, new ControlPayload(ClassControlSlot.STATUS));
            helper.assertTrue(f.calls().isEmpty(), "Forged/unauthorized requests emit no class response");
            service.setClassBanned(f.player.getUUID(), false);
            for (int i = 0; i < 50; i++) PrototypeNetworking.receiveControl(server, f.player, new ControlPayload(ClassControlSlot.ABILITY_1));
            helper.assertTrue(!f.calls().isEmpty() && f.calls().size() <= 12, "Authenticated transport dispatches with bounded burst");
        } finally {
            sessions(helper).remove(f.player.getUUID()); PrototypeNetworking.disconnect(f.player.getUUID());
        }
        helper.succeed();
    }

    @GameTest public void stale_or_unconnected_session_never_dispatches(GameTestHelper helper) throws Exception {
        var f = fixture(helper);
        PrototypeRuntime.service(helper.getLevel().getServer()).setClass(f.player.getUUID(), PrototypeTestAddon.CONTROL_A);
        var original = sessions(helper).remove(f.player.getUUID());
        try {
            PrototypeNetworking.receiveControl(helper.getLevel().getServer(), f.player, new ControlPayload(ClassControlSlot.ABILITY_1));
            var replacement = fixture(helper).player();
            sessions(helper).put(f.player.getUUID(), replacement);
            PrototypeNetworking.receiveControl(helper.getLevel().getServer(), f.player, new ControlPayload(ClassControlSlot.STATUS));
        } finally {
            if (original != null) sessions(helper).put(f.player.getUUID(), original);
            else sessions(helper).remove(f.player.getUUID());
        }
        helper.assertTrue(f.calls().isEmpty(), "A player absent from current connection map cannot execute queued controls"); helper.succeed();
    }
}
