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

Keep the addon as a separate JAR with a common `ModInitializer`. Its `fabric.mod.json` must depend on mod ID `prototype`. The current demo uses Minecraft `26.3`, Java `>=25`, Loader `>=0.19.5`, Fabric API `>=0.162.0+26.3`, and `prototype: >=0.2.0-alpha.2 <0.3.0`; match the actual Core artifact in the target project. API version remains 2; alpha.1 registration overloads are retained.

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
            }, Map.of(CLASS_ID, Map.of(
                ClassControlSlot.ABILITY_1, player -> ExampleAbilities.abilityOne(player),
                ClassControlSlot.STATUS, player -> ExampleStatus.sendPrivateSnapshot(player))),
            Map.of(CLASS_ID, List.of(ClassWeaponForm.of(ExampleItems.CLASS_SWORD))));
    }
}
```

Imports are `AddonDefinition`, `AddonLifecycle`, `ClassDefinition`, `ClassControlSlot`, `Id`, and `PlayerClassContext` from `dev.prototype.core.api`; `PrototypeApi` and `ClassWeaponForm` from `dev.prototype.core.api.minecraft`; plus Java `List`, `Map`, `Optional`, Fabric Loader/API, and Minecraft `ItemStack`. ExampleItems, ExampleSessions, ExampleAbilities and ExampleStatus are addon-owned implementations, not Core APIs.

`PrototypeApi.registerAddon(AddonDefinition, Map<Id, SwordConversionProvider>, AddonLifecycle, Map<Id, Map<ClassControlSlot, ClassControlHandler>>)` registers optional controls atomically with the addon. Provider keys must equal registered class IDs exactly. Control maps may omit classes or slots; foreign class keys, null bindings, duplicate registration and late registration are rejected. Maps are copied. The existing two-/three-argument overloads remain compatible and register no controls. Inspect the demo as supporting evidence; current Core source is authoritative.

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

Core 0.2.0-alpha.2 adds semantic class-weapon identity in the existing addon registry. Use the five-argument overload:

```java
PrototypeApi.registerAddon(definition, providers, lifecycle, controls,
    Map.of(CLASS_ID, List.of(ClassWeaponForm.of(MY_SWORD))));
```

`ClassWeaponForm.of(Item)` claims every state of a dedicated registered item, including revealed/stealth/disguise states. For a shared raw item, use persistent semantic component constraints: `ClassWeaponForm.of(SHARED_ITEM).with(MY_IDENTITY_COMPONENT, markerValue)`. Chain `.with(...)` to require several exact component values, or `.without(component)` to require absence. Every call returns a new immutable form. Expected values must follow Minecraft's immutable component-value contract and implement meaningful equality. Missing and explicit false are distinct constraints, unlike `StealthState.isStealthed`'s false default. A whole-item form covers both automatically. Register every legitimate item/component form, including a separate Blood representation if the addon actually uses one.

Forms are declarative, not arbitrary predicates: Core rejects any overlap before addon registration commits, including duplicate/redundant forms within one class and conflicts across addons. Same-item forms are provably disjoint only if a shared component constraint differs (including present versus absent). Otherwise registration fails rather than selecting by load order. Unknown class keys, unregistered/air items, unregistered/transient components and common presentation constraints (name, lore, model, custom model data, dye color) are rejected. Use semantic markers, never appearance or progression, to establish ownership. `weapon` implementation packages are internal; addons use the typed facade.

Query the authoritative server-side stack:

```java
Optional<Id> owner = PrototypeApi.classWeaponOwner(serverPlayer.getMainHandItem());
boolean anyClass = PrototypeApi.isClassWeapon(stack);
boolean ownClass = PrototypeApi.isClassWeaponOf(stack, CLASS_ID);
boolean foreignClass = owner.isPresent() && !owner.get().equals(CLASS_ID);
```

No match (including null/empty stack or an unregistered legacy addon item) returns empty/false. Identity ignores holder authorization, active class, Hybrid, Class Ban and runtime addon disable; a stolen/disabled foreign weapon remains foreign. Identity never grants use: abilities must still pass `PrototypeApi.isAuthorized`.

Convertibility is independent: the neutral Prototype Sword and a convertible ordinary item have no class owner unless a registered form matches. A class weapon need not be convertible. For classes declaring forms, Core revalidates the final converted stack's owner AFTER all transfers and rejects a wrong/unmatched destination without replacing the source. Factory defaults must create the destination's semantic marker. Do not transfer source markers or private gameplay state; register all supported stealth values so legitimate neutral-stealth transfer remains valid. Classes using older overloads retain their earlier conversion behavior.

No ownership registry or query packet is synchronized to clients. Never accept a client-asserted owner ID; query the server's real inventory stack. Do not enumerate secret forms/IDs in addon packets or ordinary UI. Core still cannot hide resources or component values already present in a locally installed/observed item.

Existing Pale/Warden integrations need a small adoption update: depend on alpha.2 or newer, add their dedicated item(s) via the fifth registration argument, and declare any shared-base stealth/Blood representation with a persistent semantic marker. Until they do, consumers must treat the missing registration as unknown/unclassified; do not guess based on visuals or convertibility. Alpha.1 overloads and API 2 remain compatible.

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

Core 0.2.0-alpha.1 supplies exactly three configurable keys under Prototype Core: Class Ability 1 (default R), Class Ability 2 (G), and Class Status (K). Rebinding uses normal Minecraft Controls. Addons must not duplicate these mappings.

`dev.prototype.core.api.ClassControlSlot` contains exactly `ABILITY_1`, `ABILITY_2`, and `STATUS`. `dev.prototype.core.api.minecraft.ClassControlHandler` has `void handle(ServerPlayer player)`. Register per-class callbacks in the four-argument overload above; omit unused slots.

Handlers execute synchronously on the server thread for the authenticated player. For every request Core resolves the CURRENT operational active class and verifies registered/enabled/allowed/active/not-banned authorization. Hybrid dispatches only to one active class. Switching and disable/enable require no client handler cache or reconnect. Unavailable/unused/denied controls are silent. The client sends one slot byte, with no class, addon, UUID, ability name or gameplay data. The server checks current connection identity and rate-limits requests.

Addons still validate sword identity, cooldowns, resources and targets, and recheck `PrototypeApi.isAuthorized` in delayed work. Lifecycle hooks clear transient state.

STATUS should call an addon server method that gathers private state and sends an addon-defined Fabric S2C payload only to that player. Register its codec in common initialization and receiver in the client entrypoint. The receiver schedules client work and opens the addon-owned screen. Never instantiate/import client screens on the server. On deactivation/disable/disconnect, cancel pending status work and clear/close presentation. Reauthorize delayed responses before send. Core understands none of the payload's gameplay fields. See DemoStatusPayload, DemoClient and DemoStatusScreen for the actual example. Status is a snapshot; live refresh is addon-owned.

## Migrating a standalone class mod

1. Inventory stable IDs, swords, abilities, packets, screens, persistence, temporary effects, and standalone administration.
2. Add the matching Prototype Core dependency while keeping a separate addon JAR.
3. Register stable addon/class metadata and the real Fabric metadata version.
4. Register every compatible class sword and one side-effect-free provider per class. Declare semantic weapon forms with the fifth registration argument; include every legitimate stealth/shared-base form and initialize destination identity in conversion factories.
5. Replace all authorization assumptions with `PrototypeApi.isAuthorized`; retain physical sword checks where required.
6. Route conversion through Core, removing delete-and-give or blanket component copying only after validation.
7. Add lifecycle activation/deactivation and runtime enable/disable cleanup while preserving progression.
8. Filter addon packets and client status by recipient authorization.
9. Bind ability/status handlers to Core's three slots and remove duplicate standalone mappings once verified. Keep status payloads, terminology, screens and rendering in the addon.
10. Remove standalone assignment, switching, Hybrid, Class Ban, enable/disable, and admin infrastructure only after Core equivalents work.
11. Test with multiple simultaneously registered addons before deleting compatibility code.

Preserve gameplay implementation; migrate shared infrastructure incrementally.

## Current Prototype Core API Gaps

Do not implement these in an addon or invent workarounds presented as Core APIs:

1. **No player-facing metadata subscription API exists.** A filtered server PlayerSnapshot exists, but Core's client does not cache it or expose an addon client API.
2. **No addon persistence extension exists.** Addons must own storage, preserving the ownership boundary.
3. **No general addon networking/private-status abstraction exists.** STATUS initiates an authorized addon response, but addons own payloads, recipient filtering and revocation cleanup.

The shared-control/status-dispatch and semantic weapon identity gaps are resolved. Addons must adopt the new weapon declarations before consumers can recognize their forms. Core deliberately leaves gameplay-specific schemas and mechanics in addons.

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
- Rebind all three controls; verify only the requested slot of the current class executes across Hybrid switches, bans and disable/enable. Unused slots are silent. Status opens only the requesting player's GUI and closes on deactivation/disable/disconnect; screen rendering must not duplicate vanilla background extraction.
- Confirm no class-specific Pale/Warden/Vampire state enters Core persistence and no common code loads client classes on the dedicated server.
- Check semantic ownership of revealed/disguised/stealth forms, ordinary lookalikes, owned/foreign weapons, disabled addons and unauthorized/banned holders. Verify overlapping declarations reject startup, identity survives legitimate conversion/disk codecs, source identity markers never overwrite a destination, and no secret form registry appears in ordinary packets.

## Migration readiness

Pale can now use registration, conversion, authorization, lifecycle, runtime disable, Hybrid/Class Ban, stealth, private persistence and the three shared controls including Status dispatch. Keep Pale gameplay, status schema, screen and cleanup in Pale. The missing-control blocker is resolved; real migration still requires inspecting Pale and combined multiplayer tests.

Warden can use the same controls and infrastructure while retaining charges, sonic boom and counters. It can now declare semantic sword forms; live Core-provided client metadata remains absent.

Vampire can now classify ordinary, own and foreign registered class weapons without importing Pale/Warden internals. Actual foreign recognition requires each owner to adopt weapon declarations. Keep hijacking, projection, Blood rendering, reward decisions, Marks, progression, Hunt and Blood Moon in Vampire; identity does not authorize foreign abilities.

Whenever Core's public addon API changes, update this skill in the same task. This repository is authoritative; addon repositories should consume its current skill rather than maintain independent stale copies.
