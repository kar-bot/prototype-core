package dev.prototype.core.client;

import com.google.gson.JsonObject;
import dev.prototype.core.network.ActionPayload;
import dev.prototype.core.network.SnapshotPayload;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** This entrypoint and every presentation class live exclusively in the client source set. */
public final class PrototypeClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Prototype Core client");

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(SnapshotPayload.TYPE, (payload, context) ->
            context.client().execute(() -> receive(context.client(), payload.json())));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> close(client));
    }

    private static void receive(Minecraft client, String json) {
        try {
            AdminSnapshot snapshot = AdminSnapshot.parse(json);
            Screen screen = client.gui.screen();
            if (snapshot.mode().equals("CLOSE")) {
                close(client);
            } else if (snapshot.mode().equals("ADMIN")) {
                if (screen instanceof ClassSelectionScreen selector) selector.update(snapshot);
                else if (screen instanceof PrototypeAdminScreen admin) admin.update(snapshot);
                else if (snapshot.open()) client.gui.setScreen(new PrototypeAdminScreen(snapshot));
            }
            // PLAYER snapshots are intentionally not cached. Normal-player commands are Stage 1's UI.
        } catch (RuntimeException invalid) {
            LOGGER.warn("Ignored malformed Prototype Core presentation snapshot", invalid);
            close(client);
        }
    }

    private static void close(Minecraft client) {
        if (client.gui.screen() instanceof PrototypeAdminScreen
            || client.gui.screen() instanceof ClassSelectionScreen) client.gui.setScreen(null);
    }

    static boolean send(String action, long revision, UUID player, String id, Boolean enabled) {
        if (!ClientPlayNetworking.canSend(ActionPayload.TYPE)) return false;
        JsonObject request = new JsonObject();
        request.addProperty("action", action);
        request.addProperty("revision", revision);
        if (player != null) request.addProperty("player", player.toString());
        if (id != null) request.addProperty("id", id);
        if (enabled != null) request.addProperty("enabled", enabled);
        ClientPlayNetworking.send(new ActionPayload(request.toString()));
        return true;
    }
}
