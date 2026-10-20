package dev.prototype.core.network;

import com.google.gson.Gson;
import dev.prototype.core.PrototypeRuntime;
import dev.prototype.core.PrototypeCore;
import dev.prototype.core.api.ActionResult;
import dev.prototype.core.api.AdminActions;
import dev.prototype.core.api.AdminRequest;
import dev.prototype.core.api.CoreService;
import dev.prototype.core.api.Id;
import dev.prototype.core.api.minecraft.PrototypeApi;
import dev.prototype.core.security.WireGuard;
import dev.prototype.core.security.WireRequest;
import dev.prototype.core.security.WireSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Internal server-only dispatcher. Permission, revision, privacy and inventory facts originate here. */
public final class PrototypeNetworking {
    private static final Gson JSON = new Gson();
    private static final Map<MinecraftServer, Map<UUID, WireRequest.Mode>> WATCHERS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, WireGuard> GUARDS = new IdentityHashMap<>();
    private PrototypeNetworking() {}

    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ControlPayload.TYPE, ControlPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ControlPayload.TYPE, (payload, context) ->
                context.server().execute(() -> receiveControl(context.server(), context.player(), payload)));
        PayloadTypeRegistry.clientboundPlay().register(SnapshotPayload.TYPE, SnapshotPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) ->
                context.server().execute(() -> receive(context.server(), context.player(), payload.json())));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> refreshWatchers(server));
        // Removing OP privileges must clear admin metadata even when nobody changes Core state.
        ServerTickEvents.END_SERVER_TICK.register(PrototypeNetworking::revokeUnauthorizedWatchers);
    }

    /** Silent for malformed, unavailable, unauthorized and unused slots; no registry disclosure. */
    public static void receiveControl(MinecraftServer server, ServerPlayer actor, ControlPayload payload) {
        if (!server.isSameThread()) throw new IllegalStateException("Class controls must run on the server thread");
        if (actor.level().getServer() != server || actor.hasDisconnected()
                || server.getPlayerList().getPlayer(actor.getUUID()) != actor) return;
        WireGuard guard = GUARDS.computeIfAbsent(server, ignored -> new WireGuard());
        if (!guard.consume(actor.getUUID(), System.nanoTime()) || payload.slot() == null) return;
        PrototypeCore.controls().dispatch(actor, PrototypeRuntime.service(server), payload.slot());
    }

    public static void openAdmin(ServerPlayer player) {
        if (!PrototypeRuntime.isAdmin(player)) { close(player, "Administrative permission required."); return; }
        watch(player, WireRequest.Mode.ADMIN);
        snapshot(player, WireRequest.Mode.ADMIN, "", true);
    }

    public static void sendPlayerSnapshot(ServerPlayer player) {
        watch(player, WireRequest.Mode.PLAYER);
        snapshot(player, WireRequest.Mode.PLAYER, "", true);
    }

    private static void watch(ServerPlayer player, WireRequest.Mode mode) {
        WATCHERS.computeIfAbsent(player.level().getServer(), ignored -> new HashMap<>()).put(player.getUUID(), mode);
    }

    private static void receive(MinecraftServer server, ServerPlayer actor, String json) {
        // A queued request from a disconnected or superseded player session cannot mutate state.
        if (server.getPlayerList().getPlayer(actor.getUUID()) != actor || actor.hasDisconnected()) return;
        WireGuard guard = GUARDS.computeIfAbsent(server, ignored -> new WireGuard());
        if (!guard.consume(actor.getUUID(), System.nanoTime())) return;
        final WireRequest request;
        try { request = WireRequest.parse(json); }
        catch (IllegalArgumentException exception) { reply(actor, "Invalid Prototype request."); return; }
        if (request.action() == WireRequest.Action.CLOSE) {
            Map<UUID, WireRequest.Mode> watchers = WATCHERS.get(server);
            if (watchers != null) watchers.remove(actor.getUUID());
            return;
        }
        if (request.action() == WireRequest.Action.REFRESH) {
            WireRequest.Mode mode = request.mode() != null ? request.mode() : mode(actor);
            if (mode == WireRequest.Mode.ADMIN && !PrototypeRuntime.isAdmin(actor)) {
                close(actor, "Administrative permission required."); return;
            }
            watch(actor, mode);
            snapshot(actor, mode, "", request.mode() != null);
            return;
        }
        CoreService service = PrototypeRuntime.service(server);
        WireGuard.Decision decision = WireGuard.authorize(request, PrototypeRuntime.isAdmin(actor), service.revision());
        if (decision == WireGuard.Decision.ADMIN_REQUIRED) { close(actor, "Administrative permission required."); return; }
        if (decision == WireGuard.Decision.STALE_REVISION) {
            reply(actor, "State changed. Review the refreshed screen and try again."); return;
        }
        if (request.action() != WireRequest.Action.CONVERT && !PrototypeRuntime.stateWritable(server)) {
            reply(actor, "Prototype state is read-only. Ask the server administrator to inspect the server log."); return;
        }
        try {
            if (request.action() == WireRequest.Action.CONVERT) {
                var result = PrototypeApi.conversions().convert(actor, service);
                reply(actor, result.message());
                return;
            }
            long before = service.revision();
            ActionResult result = request.action() == WireRequest.Action.SWITCH
                    ? service.switchClass(actor.getUUID(), Id.parse(request.id()))
                    : new AdminActions(service).apply(PrototypeRuntime.isAdmin(actor), request.revision(), administrativeIntent(request));
            if (service.revision() != before) PrototypeRuntime.afterMutation(server);
            reply(actor, result.message());
        } catch (RuntimeException exception) {
            PrototypeRuntime.LOGGER.error("Prototype request failed for player {}", actor.getUUID(), exception);
            reply(actor, "Prototype could not complete the request. Ask the server administrator to inspect the server log.");
        }
    }

    private static AdminRequest administrativeIntent(WireRequest request) {
        return switch (request.action()) {
            case SET_CLASS -> new AdminRequest.SetClass(request.player(), Id.parse(request.id()));
            case ADD_CLASS -> new AdminRequest.AddClass(request.player(), Id.parse(request.id()));
            case REMOVE_CLASS -> new AdminRequest.RemoveClass(request.player(), Id.parse(request.id()));
            case CLEAR_CLASSES -> new AdminRequest.ClearClasses(request.player());
            case SET_HYBRID -> new AdminRequest.SetHybrid(request.player(), request.enabled());
            case SET_BAN -> new AdminRequest.SetClassBanned(request.player(), request.enabled());
            case SET_ADDON -> new AdminRequest.SetAddonEnabled(Id.parse(request.id()), request.enabled());
            default -> throw new IllegalStateException("Unexpected Prototype administrative request");
        };
    }

    private static WireRequest.Mode mode(ServerPlayer player) {
        Map<UUID, WireRequest.Mode> watchers = WATCHERS.get(player.level().getServer());
        return watchers == null ? WireRequest.Mode.PLAYER : watchers.getOrDefault(player.getUUID(), WireRequest.Mode.PLAYER);
    }

    private static void reply(ServerPlayer actor, String message) { snapshot(actor, mode(actor), message, false); }

    private static void snapshot(ServerPlayer actor, WireRequest.Mode mode, String message, boolean open) {
        // Recheck at serialization time too: a formerly authorized watcher receives no registry.
        if (mode == WireRequest.Mode.ADMIN && !PrototypeRuntime.isAdmin(actor)) { close(actor, "Administrative permission required."); return; }
        MinecraftServer server = actor.level().getServer();
        if (!ServerPlayNetworking.canSend(actor, SnapshotPayload.TYPE)) {
            Map<UUID, WireRequest.Mode> watchers = WATCHERS.get(server);
            if (watchers != null) watchers.remove(actor.getUUID());
            actor.sendSystemMessage(Component.literal("Prototype Core must be installed on the client to open this screen."));
            return;
        }
        Map<UUID, WireSnapshot.PlayerIdentity> identities = new HashMap<>();
        if (mode == WireRequest.Mode.ADMIN) {
            for (UUID uuid : PrototypeRuntime.service(server).exportState().players().keySet()) {
                server.services().nameToIdCache().get(uuid).ifPresent(identity ->
                        identities.put(uuid, new WireSnapshot.PlayerIdentity(identity.name(), false)));
            }
        }
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            identities.put(online.getUUID(), new WireSnapshot.PlayerIdentity(online.getGameProfile().name(), true));
        }
        WireSnapshot view = WireSnapshot.create(PrototypeRuntime.service(server), actor.getUUID(),
                mode == WireRequest.Mode.ADMIN, identities, message, open);
        String serialized = JSON.toJson(view);
        // Bound the response as well as the request. Commands remain available for unusually large worlds.
        if (serialized.length() > SnapshotPayload.MAX_LENGTH) {
            close(actor, "This Prototype view is too large for this screen. Use the server commands.");
            return;
        }
        ServerPlayNetworking.send(actor, new SnapshotPayload(serialized));
    }

    private static void close(ServerPlayer actor, String message) {
        Map<UUID, WireRequest.Mode> watchers = WATCHERS.get(actor.level().getServer());
        if (watchers != null) watchers.remove(actor.getUUID());
        if (ServerPlayNetworking.canSend(actor, SnapshotPayload.TYPE)) {
            ServerPlayNetworking.send(actor, new SnapshotPayload(JSON.toJson(WireSnapshot.closed(message))));
        }
        actor.sendSystemMessage(Component.literal(message));
    }

    public static void refreshWatchers(MinecraftServer server) {
        Map<UUID, WireRequest.Mode> watchers = WATCHERS.get(server);
        if (watchers == null) return;
        for (var entry : new ArrayList<>(watchers.entrySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) { watchers.remove(entry.getKey()); continue; }
            snapshot(player, entry.getValue(), "", false);
        }
    }

    private static void revokeUnauthorizedWatchers(MinecraftServer server) {
        Map<UUID, WireRequest.Mode> watchers = WATCHERS.get(server);
        if (watchers == null) return;
        for (var entry : new ArrayList<>(watchers.entrySet())) {
            if (entry.getValue() != WireRequest.Mode.ADMIN) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) watchers.remove(entry.getKey());
            else if (!PrototypeRuntime.isAdmin(player)) close(player, "Administrative permission required.");
        }
    }

    public static void disconnect(UUID player) {
        for (var watchers : WATCHERS.values()) watchers.remove(player);
        for (var guard : GUARDS.values()) guard.disconnect(player);
    }

    public static void clear(MinecraftServer server) { WATCHERS.remove(server); GUARDS.remove(server); }
}
