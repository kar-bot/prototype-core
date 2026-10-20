package dev.prototype.demo.client;

import dev.prototype.demo.DemoStatusPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class DemoClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(DemoStatusPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    if (payload.open()) context.client().gui.setScreen(new DemoStatusScreen(payload.stealthed()));
                    else if (context.client().gui.screen() instanceof DemoStatusScreen) context.client().gui.setScreen(null);
                }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (client.gui.screen() instanceof DemoStatusScreen) client.gui.setScreen(null);
        });
    }
}
