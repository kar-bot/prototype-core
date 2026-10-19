package dev.prototype.core;

import dev.prototype.core.api.Registry;
import dev.prototype.core.command.PrototypeCommands;
import dev.prototype.core.conversion.ConversionManager;
import dev.prototype.core.item.CoreComponents;
import dev.prototype.core.item.PrototypeItems;
import dev.prototype.core.network.PrototypeNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/** Production initializer deliberately contains no addon gameplay or client references. */
public final class PrototypeCore implements ModInitializer {
    public static final String MOD_ID = "prototype";
    private static final Registry REGISTRY = new Registry();
    private static final ConversionManager CONVERSIONS = new ConversionManager();
    public static Registry registry() { return REGISTRY; }
    public static ConversionManager conversions() { return CONVERSIONS; }

    @Override public void onInitialize() {
        CoreComponents.init();
        PrototypeItems.init();
        PrototypeNetworking.init();
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> PrototypeCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            CONVERSIONS.freeze();
            PrototypeRuntime.start(server, REGISTRY);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(PrototypeRuntime::stop);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            PrototypeRuntime.service(server).attachPlayer(handler.player.getUUID());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            PrototypeRuntime.disconnectPlayer(server, handler.player);
        });
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            var service = PrototypeRuntime.service(newPlayer.level().getServer());
            service.detachPlayer(oldPlayer.getUUID());
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            PrototypeRuntime.service(newPlayer.level().getServer()).attachPlayer(newPlayer.getUUID());
        });
    }
}
