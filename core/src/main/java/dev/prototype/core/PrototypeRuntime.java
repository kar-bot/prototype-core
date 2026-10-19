package dev.prototype.core;

import dev.prototype.core.api.CoreService;
import dev.prototype.core.api.CoreState;
import dev.prototype.core.api.Registry;
import dev.prototype.core.network.PrototypeNetworking;
import dev.prototype.core.persistence.StateStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-thread-owned runtime. No client classes or static cross-world player state. */
public final class PrototypeRuntime {
    public static final Logger LOGGER = LoggerFactory.getLogger("Prototype Core");
    private static final Map<MinecraftServer, Session> SESSIONS = new IdentityHashMap<>();
    private static final class Session {
        private final CoreService service;
        private final StateStore store;
        private boolean stopping;
        private Session(CoreService service, StateStore store) { this.service = service; this.store = store; }
        private CoreService service() { return service; }
        private StateStore store() { return store; }
    }
    private PrototypeRuntime() {}

    public static void start(MinecraftServer server, Registry registry) {
        registry.freeze();
        Path directory = server.getWorldPath(LevelResource.ROOT).resolve("data/prototype");
        StateStore store = new StateStore(directory);
        try {
            CoreState state = store.load();
            CoreService service = new CoreService(registry, state,
                    (operation, error) -> LOGGER.error("Prototype addon lifecycle failed: " + operation, error),
                    replacement -> {
                        try { store.save(replacement); }
                        catch (IOException failure) {
                            LOGGER.error("Prototype state could not be persisted; mutation was rejected", failure);
                            throw new IllegalStateException("Prototype state could not be saved; no authorization changed. Inspect the server log.", failure);
                        }
                    });
            SESSIONS.put(server, new Session(service, store));
            service.start();
            for (String warning : store.diagnostics()) LOGGER.warn("Prototype persistence: {}", warning);
            if (store.isReadOnly()) LOGGER.error("Prototype state is read-only; administrative changes are unavailable. Protect and repair {} before restarting.", directory);
            LOGGER.info("Prototype Core loaded {} addon(s), world state at {}", registry.addons().size(), directory);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot safely load Prototype Core state from " + directory, exception);
        }
    }

    public static CoreService service(MinecraftServer server) {
        Session session = SESSIONS.get(server);
        if (session == null) throw new IllegalStateException("Prototype Core server session is not running");
        return session.service();
    }

    public static boolean isAdmin(ServerPlayer player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    public static boolean stateWritable(MinecraftServer server) {
        Session session = SESSIONS.get(server);
        return session != null && !session.stopping && !session.store().isReadOnly();
    }

    /** Persistence already succeeded before CoreService committed the mutation or invoked hooks. */
    public static void afterMutation(MinecraftServer server) {
        if (SESSIONS.containsKey(server)) PrototypeNetworking.refreshWatchers(server);
    }

    /** Fabric disconnect may originate on Netty or arrive after server shutdown cleanup. */
    public static void disconnectPlayer(MinecraftServer server, UUID playerId) {
        disconnectPlayer(server, playerId, null);
    }

    public static void disconnectPlayer(MinecraftServer server, ServerPlayer player) {
        disconnectPlayer(server, player.getUUID(), player);
    }

    private static void disconnectPlayer(MinecraftServer server, UUID playerId, ServerPlayer departed) {
        if (!server.isSameThread()) {
            if (!server.isStopped()) {
                try { server.execute(() -> disconnectPlayer(server, playerId, departed)); }
                catch (RejectedExecutionException stopped) {
                    // Shutdown already deactivated players and cleared all server-owned network state.
                }
            }
            return;
        }
        ServerPlayer current = server.getPlayerList().getPlayer(playerId);
        // A delayed event from a replaced connection cannot deactivate a newer session for this UUID.
        if (departed != null && current != null && current != departed) return;
        Session session = SESSIONS.get(server);
        if (session != null && !session.stopping) session.service().detachPlayer(playerId);
        PrototypeNetworking.disconnect(playerId);
    }

    public static void stop(MinecraftServer server) {
        Session session = SESSIONS.get(server);
        if (session == null || session.stopping) return;
        session.stopping = true;
        session.service().close();
        try {
            if (!session.store().isReadOnly()) session.store().save(session.service().exportState());
        } catch (IOException exception) {
            LOGGER.error("Unable to save Prototype Core state during shutdown", exception);
        } finally {
            PrototypeNetworking.clear(server);
            SESSIONS.remove(server);
        }
    }
}
