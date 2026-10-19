package dev.prototype.demo;

import dev.prototype.core.api.AddonDefinition;
import dev.prototype.core.api.AddonLifecycle;
import dev.prototype.core.api.ClassDefinition;
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
import net.minecraft.world.item.ItemStack;

/** External consumer: no part of this class is packaged in the production Core JAR. */
public final class DemoAddon implements ModInitializer {
    public static final Id ADDON_ID = Id.parse("prototype_demo:demo");
    public static final Id CLASS_ID = Id.parse("prototype_demo:demo");
    private static final System.Logger LOGGER = System.getLogger(DemoAddon.class.getName());
    // Deliberately transient session state; real addon progression belongs to that addon, not Core.
    private static final Set<UUID> ACTIVE_SESSIONS = new HashSet<>();

    @Override public void onInitialize() {
        DemoItems.init();
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
                    }
                });
    }
}
