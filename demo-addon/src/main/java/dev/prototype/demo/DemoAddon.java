package dev.prototype.demo;

import dev.prototype.core.api.AddonDefinition;
import dev.prototype.core.api.AddonLifecycle;
import dev.prototype.core.api.ClassDefinition;
import dev.prototype.core.api.ClassControlSlot;
import dev.prototype.core.api.Id;
import dev.prototype.core.api.PlayerClassContext;
import dev.prototype.core.api.minecraft.PrototypeApi;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** External consumer: no part of this class is packaged in the production Core JAR. */
public final class DemoAddon implements ModInitializer {
    public static final Id ADDON_ID = Id.parse("prototype_demo:demo");
    public static final Id CLASS_ID = Id.parse("prototype_demo:demo");
    private static final System.Logger LOGGER = System.getLogger(DemoAddon.class.getName());
    // Deliberately transient session state; real addon progression belongs to that addon, not Core.
    private static final Set<UUID> ACTIVE_SESSIONS = new HashSet<>();
    private static MinecraftServer currentServer;

    @Override public void onInitialize() {
        DemoItems.init();
        PayloadTypeRegistry.clientboundPlay().register(DemoStatusPayload.TYPE, DemoStatusPayload.CODEC);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> currentServer = server);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> currentServer = null);
        PrototypeApi.registerConvertible(DemoItems.DEMO_SWORD);
        String version = FabricLoader.getInstance().getModContainer("prototype_demo").orElseThrow()
                .getMetadata().getVersion().getFriendlyString();
        PrototypeApi.registerAddon(new AddonDefinition(ADDON_ID, "Prototype Demo", version,
                        "prototype_demo:textures/gui/demo_thumbnail.png",
                        List.of(new ClassDefinition(CLASS_ID, "Demo"))),
                Map.of(CLASS_ID, context -> Optional.of(new ItemStack(DemoItems.DEMO_SWORD))),
                new AddonLifecycle() {
                    @Override public void onAddonEnabled(Id addon) {
                        LOGGER.log(System.Logger.Level.INFO, "Demo enabled; Core authorization controls every interaction");
                    }
                    @Override public void onAddonDisabled(Id addon) {
                        ACTIVE_SESSIONS.clear();
                        LOGGER.log(System.Logger.Level.INFO, "Demo disabled; transient sessions cleared");
                    }
                    @Override public void onClassActivated(PlayerClassContext context) {
                        ACTIVE_SESSIONS.add(context.playerId());
                    }
                    @Override public void onClassDeactivated(PlayerClassContext context) {
                        ACTIVE_SESSIONS.remove(context.playerId());
                        if (currentServer != null) {
                            var player = currentServer.getPlayerList().getPlayer(context.playerId());
                            if (player != null) DemoStatusPayload.close(player);
                        }
                    }
                }, Map.of(CLASS_ID, Map.of(
                        ClassControlSlot.ABILITY_1, player -> player.sendSystemMessage(
                                Component.literal("Demo Class Ability 1"), true),
                        // Ability 2 is intentionally unused. STATUS sends only to this player.
                        ClassControlSlot.STATUS, DemoStatusPayload::open)));
    }
}
