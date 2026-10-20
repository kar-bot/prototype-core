---
name: prototype-addon-development
description: Create or migrate an external Fabric mod into a Prototype Core addon using the current public registration, authorization, lifecycle, conversion, stealth, privacy, and persistence contracts. Use when integrating an addon, reviewing an integration, or changing Core's addon API.
---

# Prototype addon development

Use this skill to create or migrate an external Fabric mod into a Prototype Core addon/class. Preserve the addon's gameplay and private data. Replace standalone infrastructure only where the current Core API has a real equivalent. Inspect the exact Core dependency version before editing an addon; current source is authoritative.

Use intended public types under `dev.prototype.core.api` and `dev.prototype.core.api.minecraft`. Do not import Core client screens, commands, protocol/persistence implementation, `PrototypeRuntime`, `PrototypeCore`, or `CoreComponents`.

## Ownership boundary

Core owns the neutral Prototype Sword, addon/class registry, allowed and active class state, Hybrid authorization, Class Ban, runtime addon enable/disable, authorization/lifecycle, conversion transaction, neutral stealth state, filtered Core metadata, and exactly the infrastructure its public API actually exposes.

Addons own their sword items, abilities, damage, targeting, cooldowns, effects, rendering, sounds, status UI, packets, UUID-keyed progression, private gameplay components, and transient cleanup. Never move Pale Marks/Hunt/Execution/progression; Warden charges/sonic boom/counters; or Vampire Marks/progression/Blood/Hunt/Blood Moon into Core.

## Fabric dependency and server boundary

Keep the addon as a separate JAR with a common `ModInitializer`. Its `fabric.mod.json` must depend on mod ID `prototype`. The current demo uses Minecraft `26.3`, Java `>=25`, Loader `>=0.19.5`, Fabric API `>=0.162.0+26.3`, and `prototype: >=0.1.0 <0.2.0`; match the actual Core artifact in the target project.

Common registration and server behavior must not reference `net.minecraft.client.*`. Put screens, renderers, client receivers, and key polling in a client entrypoint/source set. Validate on a true dedicated server.

## IDs and metadata

Create stable explicit IDs with `Id.parse("namespace:path")`: one addon ID and one globally unique class ID per class. Changing a display name must not change identity.

`AddonDefinition` contains addon ID, display name, installed version, thumbnail resource identifier, and 1-256 `ClassDefinition` values. Each `ClassDefinition` contains class ID and display name. Addon and class display names are nonblank, control-free, and at most 128 characters; version is at most 64.

Read the presentation version from Fabric metadata so it matches the JAR:

```java
String version = FabricLoader.getInstance().getModContainer("example").orElseThrow()
        .getMetadata().getVersion().getFriendlyString();
```

Supply a 64 x 64 PNG, at most 1 MiB, such as `assets/example/textures/gui/thumbnail.png`, registered as `example:textures/gui/thumbnail.png`. Blank uses `AddonDefinition.FALLBACK_THUMBNAIL`; unusable images render with Core's fallback.

## Minimal registration

Register during common initialization, before server startup freezes registration:

```java
public final class ExampleAddon implements ModInitializer {
    public static final Id ADDON_ID = Id.parse("example:classes");
    public static final Id CLASS_ID = Id.parse("example:example");

    @Override public void onInitialize() {
        ExampleItems.init();
        PrototypeApi.registerConvertible(ExampleItems.CLASS_SWORD);
        String version = FabricLoader.getInstance().getModContainer("example").orElseThrow()
                .getMetadata().getVersion().getFriendlyString();

        PrototypeApi.registerAddon(
            new AddonDefinition(ADDON_ID, "Example Addon", version,
                "example:textures/gui/thumbnail.png",
                List.of(new ClassDefinition(CLASS_ID, "Example Class"))),
            Map.of(CLASS_ID, context ->
                Optional.of(new ItemStack(ExampleItems.CLASS_SWORD))),
            new AddonLifecycle() {
                @Override public void onAddonEnabled(Id id) {
                    ExampleSessions.prepare();
                }
                @Override public void onAddonDisabled(Id id) {
                    ExampleSessions.clearAllTransientState();
                }
                @Override public void onClassActivated(PlayerClassContext context) {
                    ExampleSessions.activate(context.playerId());
                }
                @Override public void onClassDeactivated(PlayerClassContext context) {
                    ExampleSessions.deactivate(context.playerId());
                }
            });
    }
}
```

Imports are `AddonDefinition`, `AddonLifecycle`, `ClassDefinition`, `Id`, and `PlayerClassContext` from `dev.prototype.core.api`; `PrototypeApi` from `dev.prototype.core.api.minecraft`; plus Java `List`, `Map`, `Optional`, Fabric Loader/API, and Minecraft `ItemStack`.

`PrototypeApi.registerAddon(AddonDefinition, Map<Id, SwordConversionProvider>, AddonLifecycle)` requires provider keys to equal the definition's class IDs exactly. Every class has one non-null provider, with no extra provider. IDs are globally unique and registration is atomic. The lifecycle-free overload is suitable only when there is truly no transient state. Inspect `demo-addon` as supporting evidence, but follow current Core source if it differs.

## Sword identity and conversion

The addon registers its sword as an ordinary Minecraft `Item`; that registry item is its physical identity. Call `PrototypeApi.registerConvertible(item)` once for every sword that may be a conversion source or destination. This marks physical compatibility; it neither assigns a class nor authorizes abilities.

The provider map associates a class with destination construction. `/prototype convert` selects the one operational active class and invokes its provider. `SwordConversionContext` exposes read-only `player()`, `targetClass()`, and `source()`; every `source()` call returns a copy. Return a new clean count-one `ItemStack`, or `Optional.empty()` to decline. Providers must be synchronous and side-effect-free: do not change inventory, authorization, progression, player, or world.

Core validates the live main-hand item, snapshots inventory, rechecks authorization/revision, and replaces it once. It transfers custom name, supported compatible enchantments, and neutral stealth. It does not transfer durability, Marks, charges, cooldowns, progression, private components, or arbitrary component maps.

For an intentionally shared component only:

```java
Id transfer = Id.parse("example:shared_finish");
PrototypeApi.conversions().registerSafeTransfer(transfer, SHARED_COMPONENT, value -> value);
PrototypeApi.conversions().permitTransfer(ExampleItems.CLASS_SWORD, transfer);
```

Both physical endpoints must permit the same ID. Never use this for unrelated private progression.

Core has no public item-to-class identity registry or `classForItem` query. Each addon recognizes its own sword. A wrong-class sword retains its physical identity, but its abilities must fail authorization. Record ecosystem-wide sword ownership queries as an API gap.

## Authorization at every entry point

On the server thread, gate every ability and continuation:

```java
if (!PrototypeApi.isAuthorized(serverPlayer, CLASS_ID)) {
    stopTransientWork(serverPlayer.getUUID());
    return;
}
```

This requires a registered class, enabled owning addon, allowed class, selected active class, and no Class Ban. Check item use, attacks, damage/effect callbacks, server ticks, scheduled work, addon packet receivers, and ongoing effects. Lifecycle is cleanup, not a replacement for checks at action sites.

`PrototypeApi.service(server)` exposes `CoreService`. Use `isAuthorized` as the kill switch. `operationalActive` returns only an authorized active class. `activeClass` may be suppressed history; `allowedClasses` may include unknown history. `isClassBanned`, `isAddonEnabled`, `switchSuggestions`, and `playerSnapshot` are available server queries.

Never authorize from active/allowed state alone, an item, registry membership, client cache, or client claims. Addons normally must not call trusted state mutation methods. For addon packets, derive the actor from the authenticated connection, schedule on the server thread, recheck authorization and server-owned item/state/cooldown, and rate-limit expensive requests.

## Assignment, Hybrid, Class Ban, runtime disable

Core separates allowed classes from one active class. Without Hybrid, assigning another class replaces the allowed set. Hybrid permits several allowed classes but still one active class. Switching never converts a sword. Disabling Hybrid reduces authorization to one class while retaining the active class where possible. Class Ban and runtime addon disable suppress access while retaining history and addon-owned progression/items.

## Lifecycle and cleanup

`AddonLifecycle` runs synchronously on the server thread after Core commits state. Hooks may query Core but cannot mutate Core reentrantly. `PlayerClassContext` provides `playerId()`, `classId()`, and `reason()`.

`LifecycleReason` values are `PLAYER_JOIN`, `PLAYER_LEAVE`, `CLASS_ASSIGNED`, `CLASS_SWITCHED`, `AUTHORIZATION_REMOVED`, `HYBRID_DISABLED`, `CLASS_BANNED`, `CLASS_UNBANNED`, `ADDON_DISABLED`, `ADDON_ENABLED`, and `SERVER_STOP`.

Core deactivates the old operational class before activating the new. On disable, authorization is suppressed, affected online players deactivate, then `onAddonDisabled` runs. On enable, `onAddonEnabled` runs before eligible players activate. Failures are isolated, so cleanup must be idempotent.

Cancel only addon-owned pending tasks, targeting, temporary modifiers/effects, active modes, tracked entities, and transient client state. Preserve persistent progression. Recheck authorization in delayed work even after registering cleanup hooks.

## Neutral stealth

Core implements one shared physical item flag:

```java
boolean hidden = StealthState.isStealthed(stack);
StealthState.setStealthed(stack, true);
```

`StealthState.COMPONENT` can define an item default. Missing means false; true and false survive conversion. Core assigns no gameplay or rendering meaning. The addon interprets the flag and still validates server authority.

## Persistent state and networking

Core persists only Class Ban, Hybrid, allowed classes, active class, addon-enabled flags, and revision. It has no addon persistence extension. Store gameplay/progression under the addon's namespace by UUID. Switching, bans, disable, restart, or temporary removal must not reset it.

Core has no generic addon packet bus. Register addon-specific Fabric payloads. Send private status only to its authorized player; stop updates and send a minimal clear/close signal when authorization ends. Do not broadcast secret IDs, ability state, or progression.

## Secret/restricted class safety

Registration is explicit Java initialization; Core does not scan arbitrary mods. The server registry knows every registered class, and a currently authorized OP-level admin may receive the complete registry.

Ordinary visibility is filtered:

- `/prototype switch` autocomplete uses `CoreService.switchSuggestions`, exposing only allowed, registered, enabled classes and none while Class Banned;
- `CoreService.playerSnapshot` contains only that player's registered allowed classes;
- internal `WireSnapshot` filters ordinary addon/class metadata to allowed classes;
- unauthorized real IDs and nonexistent IDs share the same ordinary switch failure;
- an already allowed class may remain visible as disabled;
- the admin GUI intentionally shows the complete registry after server-side permission checks.

Core's client does not cache ordinary `PLAYER` snapshots; `/prototype switch` is the ordinary interface. Never use `PrototypeApi.registry().addons()` to populate ordinary UI/autocomplete because it is the full registry. An addon's GUI, autocomplete, discovery, networking, and status packets must independently filter recipients. Locally installed client JARs/assets remain inspectable; server privacy cannot hide shipped files.

## Three Core controls and Class Status

The current source does **not** implement the intended neutral controls. There are no public APIs for:

- Class Ability 1;
- Class Ability 2;
- Class Status;
- registering handlers for these slots;
- dispatching an addon-specific Class Status GUI.

Do not invent names or claim registration. A standalone addon may retain its own keybind/status screen temporarily with server checks, but that does not satisfy the intended Core contract. Core needs an implementation pass before these can migrate cleanly.

## Migrating a standalone class mod

1. Inventory stable IDs, swords, abilities, packets, screens, persistence, temporary effects, and standalone administration.
2. Add the matching Prototype Core dependency while keeping a separate addon JAR.
3. Register stable addon/class metadata and the real Fabric metadata version.
4. Register every compatible class sword and exactly one side-effect-free provider per class.
5. Replace all authorization assumptions with `PrototypeApi.isAuthorized`; retain physical sword checks where required.
6. Route conversion through Core, removing delete-and-give or blanket component copying only after validation.
7. Add lifecycle activation/deactivation and runtime enable/disable cleanup while preserving progression.
8. Filter addon packets and client status by recipient authorization.
9. Keep existing keybinds/status only as a documented temporary path because Core lacks shared dispatch.
10. Remove standalone assignment, switching, Hybrid, Class Ban, enable/disable, and admin infrastructure only after Core equivalents work.
11. Test with multiple simultaneously registered addons before deleting compatibility code.

Preserve gameplay implementation; migrate shared infrastructure incrementally.

## Current Prototype Core API Gaps

Do not implement these in an addon or invent workarounds presented as Core APIs:

1. **Three neutral keybind slots are absent.** No Class Ability 1, Class Ability 2, or Class Status mappings, payloads, handler interfaces, registration, rebinding, or dispatch exist.
2. **Class Status GUI dispatch is absent.** An addon cannot register/open a status screen through Core.
3. **No player-facing metadata subscription API exists.** A filtered server `PlayerSnapshot` exists, but Core's client intentionally does not cache it or expose an addon client API.
4. **No class-sword identity mapping exists.** Core tracks convertible items and class providers, not ownership of an item by a class.
5. **No addon persistence extension exists.** Addons must own storage, which preserves the ownership boundary but requires their own implementation.
6. **No general addon networking/private-status abstraction exists.** Addons must implement packets, recipient filtering, and revocation cleanup.

These gaps block the intended shared controls/status experience. They do not block registration, assignment, authorization, conversion, lifecycle, runtime disable, Hybrid/Class Ban enforcement, stealth, or private persistence.

## Multi-addon validation

Run project tests, then use a true dedicated server and matching clients:

- Start Core alone, each addon, then Core + Pale + Warden + Vampire; verify expected registrations and duplicate-ID rejection.
- Confirm the OP admin GUI lists correct addon names, versions, thumbnails, enable flags, and classes; ordinary clients receive no unauthorized metadata.
- Test assignment with Hybrid off, multiple allowed classes with Hybrid on, and exactly one active class.
- Switch across addons; old deactivation precedes new activation, held sword stays unchanged, transient state clears, and progression survives switching/restart.
- Test Class Ban across abilities, delayed work, conversion, suggestions, and private packets without deleting history.
- Convert Prototype and compatible ecosystem swords into every active class. Verify approved physical state transfers and private state does not cross addons.
- Hold wrong-class swords: physical identity remains, foreign abilities do not run without that class being authorized and active.
- Disable/re-enable each addon. Authorization and conversion stop immediately; transient state clears; items, history, progression, and other addons remain sound.
- Compare unauthorized secret and nonexistent IDs in autocomplete, GUIs, packets, and switching; neither path may reveal the secret to ordinary users.
- Temporarily remove/reinstall an addon in a disposable world and verify Core history plus addon storage survive.
- Forge addon packets and confirm authenticated player, server state, item, cooldown, and `PrototypeApi.isAuthorized` are decisive.
- Confirm no class-specific Pale/Warden/Vampire state enters Core persistence and no common code loads client classes on the dedicated server.

## Migration readiness

Pale can use current registration, conversion, authorization, lifecycle, runtime disable, Hybrid/Class Ban, stealth, and private persistence. It cannot cleanly complete the intended shared three controls and Class Status routing until Core adds those APIs.

Warden can use the same current infrastructure while retaining charges, sonic boom, and counters. It also cannot cleanly complete shared controls/status dispatch. If it needs ecosystem-wide sword ownership or live Core-provided authorized client metadata, those are current gaps.

Vampire has the same limitation: keep Marks, Blood, progression, Hunt, and Blood Moon private, integrate available infrastructure, and defer shared controls/status replacement until Core implements it.
