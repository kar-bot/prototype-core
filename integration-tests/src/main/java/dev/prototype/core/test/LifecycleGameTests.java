package dev.prototype.core.test;

import dev.prototype.core.PrototypeRuntime;
import dev.prototype.core.api.CoreService;
import dev.prototype.core.api.LifecycleReason;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Exercises the real runtime boundary used by Fabric's potentially off-thread disconnect event. */
public final class LifecycleGameTests {
    private static CoreService service(GameTestHelper helper) {
        return PrototypeRuntime.service(helper.getLevel().getServer());
    }

    private static UUID attachedPlayer(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        var service = service(helper);
        service.setAddonEnabled(PrototypeTestAddon.SECRET_ADDON, true);
        service.setClass(playerId, PrototypeTestAddon.SECRET_CLASS);
        PrototypeTestAddon.clearLifecycle(playerId);
        service.attachPlayer(playerId);
        return playerId;
    }

    private static void assertOneServerThreadDeactivation(GameTestHelper helper, UUID playerId, Thread serverThread) {
        var events = PrototypeTestAddon.lifecycle(playerId);
        helper.assertValueEqual(events.size(), 2, "One activation and exactly one deactivation are dispatched");
        helper.assertTrue(events.getFirst().activated(), "First hook activates the attached class");
        helper.assertFalse(events.getLast().activated(), "Disconnect deactivates the class");
        helper.assertTrue(events.stream().allMatch(event -> event.thread() == serverThread),
                "Every lifecycle hook runs on the authoritative server thread");
        helper.assertValueEqual(events.getLast().context().reason(), LifecycleReason.PLAYER_LEAVE,
                "Disconnect supplies the normal leave cleanup reason");
        helper.assertFalse(service(helper).onlinePlayers().contains(playerId), "Disconnect removes the online session");
        helper.assertTrue(service(helper).allowedClasses(playerId).contains(PrototypeTestAddon.SECRET_CLASS),
                "Disconnect retains persistent class authorization");
    }

    @GameTest public void background_disconnect_dispatches_lifecycle_on_server_thread(GameTestHelper helper) {
        UUID playerId = attachedPlayer(helper);
        Thread serverThread = Thread.currentThread();
        var server = helper.getLevel().getServer();
        AtomicBoolean returned = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.ofVirtual().name("prototype-test-network-disconnect").start(() -> {
            try { PrototypeRuntime.disconnectPlayer(server, playerId); }
            catch (Throwable exception) { failure.set(exception); }
            finally { returned.set(true); }
        });
        helper.runAfterDelay(2, () -> helper.succeedWhen(() -> {
            helper.assertTrue(returned.get(), "Background network callback returns without blocking the server");
            helper.assertTrue(failure.get() == null, "Background disconnect schedules safely: " + failure.get());
            assertOneServerThreadDeactivation(helper, playerId, serverThread);
            PrototypeTestAddon.clearLifecycle(playerId);
        }));
    }

    @GameTest public void repeated_disconnect_deactivates_once_and_preserves_authorization(GameTestHelper helper) {
        UUID playerId = attachedPlayer(helper);
        var server = helper.getLevel().getServer();
        PrototypeRuntime.disconnectPlayer(server, playerId);
        PrototypeRuntime.disconnectPlayer(server, playerId);
        assertOneServerThreadDeactivation(helper, playerId, Thread.currentThread());
        PrototypeTestAddon.clearLifecycle(playerId);
        helper.succeed();
    }

    @GameTest public void unattached_disconnect_is_a_safe_noop(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        var server = helper.getLevel().getServer();
        var before = service(helper).exportState();
        PrototypeRuntime.disconnectPlayer(server, playerId);
        PrototypeRuntime.disconnectPlayer(server, playerId);
        helper.assertTrue(PrototypeTestAddon.lifecycle(playerId).isEmpty(), "Unknown session dispatches no lifecycle hooks");
        helper.assertValueEqual(service(helper).exportState(), before, "Unknown session leaves persisted state and revision untouched");
        helper.succeed();
    }
}
