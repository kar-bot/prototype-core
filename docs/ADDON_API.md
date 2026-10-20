# Addon development contract

> Codex contributors: [`.agents/skills/prototype-addon-development/SKILL.md`](../.agents/skills/prototype-addon-development/SKILL.md) is the authoritative local skill for developing or migrating Prototype addons. Consult it when integrating an addon or changing this API.

Prototype Core `0.2.0-alpha.2` (API 2) targets Minecraft `26.3`, Fabric Loader `0.19.5`, Fabric API `0.162.0+26.3`, and Java `25`. Use Loom `1.18.3` and Gradle `9.7.1` consistently with this repository. Minecraft 26.3 uses unobfuscated Mojang names. See [neutral class controls](CLASS_CONTROLS.md) and [semantic class weapons](CLASS_WEAPONS.md).

## Depend on production Core

For a standalone addon, place the built **production** `prototype-core-0.2.0-alpha.2.jar` in your addon's `libs` directory and add it as a local development dependency:

```groovy
dependencies {
    implementation files('libs/prototype-core-0.2.0-alpha.2.jar')
}
```

Declare the runtime dependency in your addon's `fabric.mod.json`:

```json
"depends": {
  "prototype": ">=0.2.0-alpha.2 <0.3.0",
  "fabricloader": ">=0.19.5",
  "minecraft": "26.3",
  "java": ">=25",
  "fabric-api": ">=0.162.0+26.3"
}
```

This snippet belongs inside the full mod metadata object. Ship the addon as a separate JAR and require Core to be installed separately; do not bundle duplicate Core classes or the demo addon. Within this repository, `demo-addon` uses `implementation project(':core')` instead of a local JAR. There is no claimed remote artifact repository for this release.

## Public and internal APIs

The supported addon contracts are `dev.prototype.core.api` (identifiers, metadata, immutable state views, authorization service, and lifecycle types) and `dev.prototype.core.api.minecraft` (Fabric registration, conversion provider/context, and Minecraft helpers). The conversion-manager registration methods documented below are supported through `PrototypeApi.conversions()`. `CoreService` mutations are trusted **server** operations; ordinary addons normally query them and leave authorization administration to Core's guarded commands/GUI.

`ConversionManager.registerSafeTransfer`, `permitTransfer`, and `isConvertible` are documented addon APIs despite the manager's `conversion` package. Its `convert(ServerPlayer, CoreService)` and `ConversionResult` are trusted server-integration APIs; they validate current authority and the main-hand stack rather than accepting client item data. Core owns registration closure: addons must not call manager/registry `freeze()` or replace runtime service lifecycle wiring.

`dev.prototype.core.client`, `command`, `network`, `security`, and `persistence`, along with Core initializer/runtime wiring, are implementation details. Packet action strings, JSON layouts, GUI classes, and storage classes are not addon extension points. Do not construct packets to administer Core, depend on private UI classes, or write Core's persistence file directly.

The 0.2 API retains earlier registration overloads and adds optional class controls. Declare a bounded compatibility range and recompile/retest when Core or Minecraft/Fabric changes. Prerelease APIs require integration validation; display-name changes do not change stable IDs.

## Register an addon and its classes

Register during your `ModInitializer.onInitialize`, before Core freezes registration at server startup. Use explicit, lowercase `namespace:path` IDs. Class IDs are globally unique; one addon may register several classes. Duplicate addon/class IDs and invalid metadata fail deterministically. Every class needs exactly one destination conversion provider.

The following is the same minimal pattern used by the external demo; register the `DEMO_SWORD` item using the normal Minecraft item registration path before invoking it:

```java
import dev.prototype.core.api.AddonDefinition;
import dev.prototype.core.api.AddonLifecycle;
import dev.prototype.core.api.ClassDefinition;
import dev.prototype.core.api.Id;
import dev.prototype.core.api.PlayerClassContext;
import dev.prototype.core.api.minecraft.PrototypeApi;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

private static final Id ADDON = Id.parse("prototype_demo:demo");
private static final Id CLASS = Id.parse("prototype_demo:demo");

void registerWithCore() {
    PrototypeApi.registerConvertible(DEMO_SWORD);
    PrototypeApi.registerAddon(
        new AddonDefinition(
            ADDON, "Prototype Demo", "0.2.0-alpha.2",
            "prototype_demo:textures/gui/demo_thumbnail.png",
            List.of(new ClassDefinition(CLASS, "Demo"))),
        Map.of(CLASS, context -> Optional.of(new ItemStack(DEMO_SWORD))),
        new AddonLifecycle() {
            @Override public void onClassActivated(PlayerClassContext context) {
                // Initialize transient state for context.playerId().
            }
            @Override public void onClassDeactivated(PlayerClassContext context) {
                // Remove transient state; retain UUID-owned progression.
            }
            @Override public void onAddonEnabled(Id addonId) {
                // Prepare clean runtime state.
            }
            @Override public void onAddonDisabled(Id addonId) {
                // Stop transient work; preserve persistent progression.
            }
        });
}
```

Registration says the addon is installed. It does not assign players or grant class use, visibility, or conversion access. Runtime enable/disable changes state; it never removes the installed item registration or unloads a Fabric mod.

Use the addon's actual Fabric metadata version in production registration, as the demo does; keep presentation version information consistent with the installed JAR.

The two-/three-/four-argument `registerAddon` methods remain binary compatible with alpha.1. A fifth `Map<Id, List<ClassWeaponForm>>` argument declares optional semantic weapon forms in the same registration/freeze transaction. Use `ClassWeaponForm.of(item)` for a dedicated item or `.with(persistentSemanticComponent, value)` / `.without(component)` for exact component-aware forms. `PrototypeApi.classWeaponOwner(ItemStack)`, `isClassWeapon(ItemStack)` and `isClassWeaponOf(ItemStack, Id)` query actual stack identity independently of holder authorization, runtime disable or conversion compatibility. Overlapping claims reject registration. No registry synchronization is added. See [the complete contract and migration steps](CLASS_WEAPONS.md); `dev.prototype.core.weapon` is internal bridge/registry implementation, not an addon extension package.

## Thumbnail resources

Supply a **64 × 64 PNG**, maximum 1 MiB encoded size, with an identifier such as `youraddon:textures/gui/thumbnail.png`. Its file belongs at `assets/youraddon/textures/gui/thumbnail.png` inside the addon JAR. Transparent PNGs are supported. Use an image that remains legible when drawn as a compact Minecraft GUI thumbnail.

Omitted/blank thumbnails select `prototype:textures/gui/addon_fallback.png`. Missing, unreadable, corrupt, non-PNG, wrong-size, or oversized resources use the Core fallback at rendering time. A malformed resource identifier is rejected as invalid registration metadata. Core includes its fallback only; each addon supplies its own artwork. Ordinary server snapshots send thumbnail identifiers only for addons/classes the player is allowed to know about.

## Centralized authorization

From the authoritative server thread, obtain `PrototypeApi.service(server)` or call `PrototypeApi.isAuthorized(player, CLASS)`.

```java
if (!PrototypeApi.isAuthorized(player, CLASS)) {
    return; // Do not perform the ability or advance an ongoing effect.
}
```

Use this at **every** ability entry point: item interactions, attacks, tick callbacks, delayed tasks, and addon network handlers. Checking only when a player activates a class is insufficient because the addon may later be disabled or the player banned/switched.

`CoreService` supplies:

| Method | Meaning |
| --- | --- |
| `isAddonEnabled(addonId)` | Registered addon currently enabled. |
| `isClassBanned(playerUuid)` | Core Class Ban flag. |
| `allowedClasses(playerUuid)` | Stored authorized IDs, including unknown historical IDs. |
| `activeClass(playerUuid)` | Selected historical class, even while suppressed. |
| `operationalActive(playerUuid)` | Active class only if it passes all authorization rules. |
| `isAuthorized(playerUuid, classId)` | Registered + addon enabled + not class-banned + allowed + active. |
| `playerSnapshot(playerUuid)` | Filtered metadata for that player's own registered authorized classes. |

Do not infer operational access from `activeClass` or `allowedClasses` alone. Do not use the complete registry to build an ordinary player's UI or autocomplete. A client cache is presentation data and cannot authorize gameplay.

Trusted server integrations that administer authorization can use `new AdminActions(service).apply(serverValidatedAdmin, expectedRevision, request)`. `AdminRequest` is a sealed family of `SetClass`, `AddClass`, `RemoveClass`, `ClearClasses`, `SetHybrid`, `SetClassBanned`, and `SetAddonEnabled` records. The integration must compute permission from the authenticated server connection/command source. Passing a client-provided Boolean to this helper would violate the trust boundary. The helper validates permission and revision together before dispatching the typed mutation; it does not grant clients an administration API.

## Conversion

`SwordConversionProvider` is a functional interface with `Optional<ItemStack> convert(SwordConversionContext context)`. The context exposes `player()`, `targetClass()`, and `source()`. The source is an isolated copy; each access yields a copy. Return a new single destination weapon stack, or `Optional.empty()` to reject.

Core validates the actual server-held item, class ban, active/allowed class, addon state, source-item registration, and destination provider before replacing the hand. Call `PrototypeApi.registerConvertible(item)` for each sword that may later be converted to another class. This registration does not authorize use of that sword's class.

Providers must be synchronous and side-effect-free: construct a result without mutating the player's inventory, applying effects, consuming currency, or editing addon progression. An invalid result, rejection, or exception must not require undoing external effects. Use `context.source()` to inspect supported physical source state; create destination defaults yourself instead of returning a component-map copy of the source.

Core transfers custom name and supported destination-compatible enchantments explicitly. Enchantments must support the destination item, are clamped to their valid levels, and must be mutually compatible; destination default enchantments are considered before source enchantments in deterministic ID order. Unsupported or conflicting enchantments are omitted. Core does not blindly copy damage, private components, private progression, or another addon's special item data. The source remains unchanged on rejected conversion.

The server checks that the factory leaves the player's inventory, selected slot, container identity, and carried stack unchanged. It restores the captured inventory if a callback violates that rule. Authorization and Core revision are rechecked before commit. This does not roll back world edits, spawned entities, progression, or arbitrary callback effects, which is why providers must remain side-effect-free.

For additional shared components, the conversion manager supports:

```java
PrototypeApi.conversions().registerSafeTransfer(key, componentType, copier);
PrototypeApi.conversions().permitTransfer(sourceSword, key);
PrototypeApi.conversions().permitTransfer(destinationSword, key);
```

`key` is an `Id`, `componentType` is a `DataComponentType<T>`, and `copier` is a `UnaryOperator<T>` that creates a safe independent value. Both endpoint items must opt in to the same key. Register these during initialization before conversion registration freezes. Never opt private progression or another addon's private data into generic transfer without an explicit cross-addon contract.

## Neutral stealth state

Use the public Minecraft helper rather than copying component maps:

```java
import dev.prototype.core.api.minecraft.StealthState;

boolean hidden = StealthState.isStealthed(stack);
StealthState.setStealthed(stack, true);
```

The underlying `prototype:stealth` Boolean component is persisted with the item and network-synchronized through Minecraft's component system. Missing state means `false`. Core explicitly preserves either `true` or `false` across compatible conversion; a destination factory's default cannot override the source state.

`StealthState.COMPONENT` exposes the public `DataComponentType<Boolean>` handle when an addon needs to set an item default, such as `.component(StealthState.COMPONENT, false)` in its `Item.Properties`.

Stealth is physical item state, not authorization or progression. Each addon decides what it changes. Core neither disables abilities nor chooses rendering based on the flag. Setting the flag from an addon still needs that addon's own server validation; clients do not gain permission by claiming a stealth value.

## Lifecycle and data ownership

Hooks run synchronously on the server thread **after** Core commits the new authoritative state. For a class transition, old deactivation precedes new activation. Addon disable suppresses authorization first, deactivates affected online players, then invokes addon cleanup. Enable prepares the addon before activating eligible online players. Offline assignments receive activation on join.

Hooks may query Core but must not mutate Core state reentrantly. Nonfatal callback failures are logged and isolated so later callbacks still run; addons remain responsible for complete cleanup. `PlayerClassContext` contains `playerId()`, `classId()`, and `reason()`. `LifecycleReason` distinguishes joining/leaving, class assignment/switch, authorization removal, hybrid disable, Class Ban/unban, addon disable/enable, and server stop.

The running Fabric integration durably saves a proposed authorization state before committing it or dispatching lifecycle events. A failed save rejects the mutation and leaves live authorization/revision unchanged. On server stop, authorization is suppressed before cleanup hooks; exported enabled flags and stored assignments remain intact for restart.

An addon must arrange its own server/player lookup for UUID contexts using normal Fabric server lifecycle/events; the plain Java context does not contain a Minecraft client or player instance. Handle disconnect, respawn, and server shutdown cleanly. Core dispatches Fabric network-thread disconnects onto the server thread, ignores stale disconnects from replaced player connections, and treats late/duplicate shutdown cleanup safely. Track addon persistent progression separately by UUID and namespace. Cleanup must remove only transient work/effects; switching away and back must restore the same persistent progression even for non-hybrid players.

## Privacy and future migration

Do not broadcast installed class registries, thumbnails, ability state, or secret class metadata to ordinary clients. Core's own protocol filters server-side, but an addon's own packets need the same recipient discipline. Unauthorized and nonexistent IDs should share ordinary-user failures. Locally installed addon resources/JARs remain inspectable; server privacy does not hide physical client files.

For Pale or another future addon, register its own stable IDs/sword/resources, use Core authorization and neutral stealth, preserve its private UUID-based data, and remove transient mechanics on every deactivation/disable path. See [architecture](ARCHITECTURE.md) and the [manual checklist](MANUAL_TESTS.md).

## Demo behavior

The separate demo registers addon/class `prototype_demo:demo`, sword `prototype_demo:demo_sword`, and its own `prototype_demo:textures/gui/demo_thumbnail.png`. An authorized active player right-clicking its sword receives a short action-bar status message; shift-right-click toggles the Core stealth flag. An unauthorized, class-banned, switched-away, or runtime-disabled player receives no demo ability behavior. The demo tracks transient activated UUIDs and clears them on deactivation/disable; it has no progression schema and no supernatural gameplay.

To validate it, install both JARs, assign the demo class as OP level 4, hold the Prototype Sword, run `/prototype convert`, toggle stealth with shift-right-click, convert again, and disable/re-enable the addon. The external consumer source is the reference example; none of its code/resources is embedded in the production Core JAR.
