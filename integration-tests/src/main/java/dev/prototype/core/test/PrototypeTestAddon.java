package dev.prototype.core.test;

import dev.prototype.core.api.AddonDefinition;
import dev.prototype.core.api.AddonLifecycle;
import dev.prototype.core.api.ClassDefinition;
import dev.prototype.core.api.ClassControlSlot;
import dev.prototype.core.api.Id;
import dev.prototype.core.api.PlayerClassContext;
import dev.prototype.core.api.minecraft.PrototypeApi;
import dev.prototype.core.api.minecraft.ClassWeaponForm;
import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
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
import net.minecraft.world.item.Items;

/** Exists only in the integration test artifact, never in either release JAR. */
public final class PrototypeTestAddon implements ModInitializer {
    public static final Id SECRET_ADDON = Id.parse("prototype_tests:secret");
    public static final Id SECRET_CLASS = Id.parse("prototype_tests:secret");
    static final Id WEAPON_ADDON = Id.parse("prototype_tests:weapons");
    static final Id WEAPON_A = Id.parse("prototype_tests:weapon_a");
    static final Id WEAPON_B = Id.parse("prototype_tests:weapon_b");
    static boolean conflictingWeaponsRejectedAtomically;
    static boolean unregisteredWeaponRejected;
    static final DataComponentType<String> WEAPON_MARKER = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            Identifier.fromNamespaceAndPath("prototype_tests", "semantic_weapon"),
            DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());
    static final DataComponentType<String> TRANSIENT_MARKER = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            Identifier.fromNamespaceAndPath("prototype_tests", "transient_marker"),
            DataComponentType.<String>builder().networkSynchronized(ByteBufCodecs.STRING_UTF8).build());

    static ItemStack markedWeapon(String marker) {
        var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(WEAPON_MARKER, marker); return stack;
    }
    static final Id CONTROL_ADDON = Id.parse("prototype_tests:controls");
    static final Id CONTROL_A = Id.parse("prototype_tests:control_a");
    static final Id CONTROL_B = Id.parse("prototype_tests:control_b");
    static boolean invalidControlsRejectedAtomically;
    static boolean duplicateControlsRejected;
    private static final Map<UUID, List<String>> CONTROLS = new ConcurrentHashMap<>();
    static List<String> controls(UUID player) { return CONTROLS.getOrDefault(player, List.of()); }
    private static void recordControl(UUID player, String event) {
        CONTROLS.computeIfAbsent(player, ignored -> new CopyOnWriteArrayList<>()).add(event);
    }
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
        // Defaulted ITEM registry returns the air key for unregistered values; the API must reject it.
        var pendingKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("prototype_tests", "pending_weapon"));
        var pending = new Item(new Item.Properties().setId(pendingKey));
        try { ClassWeaponForm.of(pending); }
        catch (IllegalArgumentException expected) { unregisteredWeaponRejected = true; }
        Registry.register(BuiltInRegistries.ITEM, pendingKey, pending);
        registerWeaponExamples();
        var definition = new AddonDefinition(CONTROL_ADDON, "Control tests", "1", null,
                List.of(new ClassDefinition(CONTROL_A, "Control A"), new ClassDefinition(CONTROL_B, "Control B")));
        try {
            PrototypeApi.registerAddon(definition,
                    Map.of(CONTROL_A, context -> Optional.of(new ItemStack(TEST_SWORD)),
                           CONTROL_B, context -> Optional.of(new ItemStack(TEST_SWORD))), AddonLifecycle.NONE,
                    Map.of(SECRET_CLASS, Map.of(ClassControlSlot.STATUS, player -> {})));
            throw new AssertionError("Foreign controls must not be accepted");
        } catch (IllegalArgumentException expected) {
            invalidControlsRejectedAtomically = PrototypeApi.registry().addon(CONTROL_ADDON).isEmpty();
        }
        PrototypeApi.registerAddon(new AddonDefinition(CONTROL_ADDON, "Control tests", "1", null,
                        List.of(new ClassDefinition(CONTROL_A, "Control A"), new ClassDefinition(CONTROL_B, "Control B"))),
                Map.of(CONTROL_A, context -> Optional.of(new ItemStack(TEST_SWORD)),
                       CONTROL_B, context -> Optional.of(new ItemStack(TEST_SWORD))), AddonLifecycle.NONE,
                Map.of(CONTROL_A, Map.of(
                        ClassControlSlot.ABILITY_1, player -> recordControl(player.getUUID(), "a1"),
                        ClassControlSlot.ABILITY_2, player -> recordControl(player.getUUID(), "a2"),
                        ClassControlSlot.STATUS, player -> recordControl(player.getUUID(), "private-status")),
                       CONTROL_B, Map.of(ClassControlSlot.ABILITY_1, player -> recordControl(player.getUUID(), "b1"))));
        try {
            PrototypeApi.registerAddon(definition,
                    Map.of(CONTROL_A, context -> Optional.of(new ItemStack(TEST_SWORD)),
                           CONTROL_B, context -> Optional.of(new ItemStack(TEST_SWORD))), AddonLifecycle.NONE, Map.of());
            throw new AssertionError("Duplicate registration must not be accepted");
        } catch (IllegalArgumentException expected) { duplicateControlsRejected = true; }
        PrototypeApi.registerConvertible(TEST_SWORD);
        PrototypeApi.registerAddon(new AddonDefinition(SECRET_ADDON, "Hidden Integration Addon", "0.1.0", null,
                        List.of(new ClassDefinition(SECRET_CLASS, "Hidden Integration Class"))),
                Map.of(SECRET_CLASS, context -> Optional.of(new ItemStack(TEST_SWORD))),
                new AddonLifecycle() {
                    @Override public void onClassActivated(PlayerClassContext context) { observe(true, context); }
                    @Override public void onClassDeactivated(PlayerClassContext context) { observe(false, context); }
                }, Map.of(), Map.of(SECRET_CLASS, List.of(ClassWeaponForm.of(TEST_SWORD))));
    }

    private static void registerWeaponExamples() {
        var definition = new AddonDefinition(WEAPON_ADDON, "Semantic form tests", "1", null,
                List.of(new ClassDefinition(WEAPON_A, "Form A"), new ClassDefinition(WEAPON_B, "Form B")));
        var forms = Map.of(WEAPON_A, List.of(ClassWeaponForm.of(Items.DIAMOND_SWORD).with(WEAPON_MARKER, "a")),
                           WEAPON_B, List.of(ClassWeaponForm.of(Items.DIAMOND_SWORD).with(WEAPON_MARKER, "b"),
                                            ClassWeaponForm.of(Items.STICK).with(WEAPON_MARKER, "b")));
        try {
            PrototypeApi.registerAddon(definition,
                    Map.of(WEAPON_A, context -> Optional.of(markedWeapon("a")), WEAPON_B, context -> Optional.of(markedWeapon("b"))),
                    AddonLifecycle.NONE, Map.of(),
                    Map.of(WEAPON_A, List.of(ClassWeaponForm.of(Items.DIAMOND_SWORD)),
                           WEAPON_B, List.of(ClassWeaponForm.of(Items.DIAMOND_SWORD).with(WEAPON_MARKER, "b"))));
            throw new AssertionError("Overlapping weapon claims must fail");
        } catch (IllegalArgumentException expected) {
            conflictingWeaponsRejectedAtomically = PrototypeApi.registry().addon(WEAPON_ADDON).isEmpty();
        }
        PrototypeApi.registerConvertible(Items.DIAMOND_SWORD);
        PrototypeApi.registerAddon(definition,
                Map.of(WEAPON_A, context -> Optional.of(markedWeapon("a")), WEAPON_B, context -> Optional.of(markedWeapon("b"))),
                AddonLifecycle.NONE, Map.of(), forms);
    }
}
