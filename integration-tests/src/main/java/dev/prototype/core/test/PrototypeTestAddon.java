package dev.prototype.core.test;

import dev.prototype.core.api.AddonDefinition;
import dev.prototype.core.api.AddonLifecycle;
import dev.prototype.core.api.ClassDefinition;
import dev.prototype.core.api.Id;
import dev.prototype.core.api.PlayerClassContext;
import dev.prototype.core.api.minecraft.PrototypeApi;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ToolMaterial;

/** Exists only in the integration test artifact, never in either release JAR. */
public final class PrototypeTestAddon implements ModInitializer {
    public static final Id SECRET_ADDON = Id.parse("prototype_tests:secret");
    public static final Id SECRET_CLASS = Id.parse("prototype_tests:secret");
    record ObservedLifecycle(boolean activated, PlayerClassContext context, Thread thread) {}
    private static final Map<UUID, CopyOnWriteArrayList<ObservedLifecycle>> LIFECYCLE = new ConcurrentHashMap<>();

    static List<ObservedLifecycle> lifecycle(UUID playerId) {
        return List.copyOf(LIFECYCLE.getOrDefault(playerId, new CopyOnWriteArrayList<>()));
    }

    static void clearLifecycle(UUID playerId) { LIFECYCLE.remove(playerId); }

    private static void observe(boolean activated, PlayerClassContext context) {
        LIFECYCLE.computeIfAbsent(context.playerId(), ignored -> new CopyOnWriteArrayList<>())
                .add(new ObservedLifecycle(activated, context, Thread.currentThread()));
    }
    private static final ResourceKey<Item> SWORD_KEY = ResourceKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("prototype_tests", "test_sword"));
    public static final Item TEST_SWORD = Registry.register(BuiltInRegistries.ITEM, SWORD_KEY,
            new Item(new Item.Properties().setId(SWORD_KEY).sword(ToolMaterial.IRON, 3.0F, -2.4F)));

    @Override public void onInitialize() {
        PrototypeApi.registerConvertible(TEST_SWORD);
        PrototypeApi.registerAddon(new AddonDefinition(SECRET_ADDON, "Hidden Integration Addon", "0.1.0", null,
                        List.of(new ClassDefinition(SECRET_CLASS, "Hidden Integration Class"))),
                Map.of(SECRET_CLASS, context -> Optional.of(new ItemStack(TEST_SWORD))),
                new AddonLifecycle() {
                    @Override public void onClassActivated(PlayerClassContext context) { observe(true, context); }
                    @Override public void onClassDeactivated(PlayerClassContext context) { observe(false, context); }
                });
    }
}
