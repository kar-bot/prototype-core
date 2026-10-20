# Architecture and invariants

Prototype Core separates rules from Minecraft integration. `core-domain` contains plain Java registration, class authorization, lifecycle, persistence, and request-validation code. `core` embeds those classes and supplies Fabric item/conversion integration, server commands, protocol handling, and client administration. `demo-addon` is an independently initialized API consumer; its classes and assets are absent from the production Core JAR.

The intended addon-facing API lives under `dev.prototype.core.api`. Fabric-specific conversion and stealth helpers live under `dev.prototype.core.api.minecraft`. Client GUI, commands, protocol serialization, security guards, and persistence implementation are internal. See [ADDON_API.md](ADDON_API.md) for the public contract and a minimal consumer example.

## Authorization model

Core's three neutral controls resolve the authenticated player's current operational active class on the server for every request. Startup-frozen optional class handlers implement only addon behavior. Status callbacks send addon-owned private payloads to addon client screens; Core never interprets gameplay state or loads those screens. See [CLASS_CONTROLS.md](CLASS_CONTROLS.md).

State is keyed by player UUID, not mutable player name.

| Field | Meaning |
| --- | --- |
| `classBanned` | Suppress operational class use without banning the player or erasing stored authorization. |
| `hybridEnabled` | Allow multiple authorized classes; never permit multiple simultaneously active classes. |
| `allowedClasses` | Stable namespaced historical class IDs authorized for this player. |
| `activeClass` | Selected class, retained as history when a class ban or runtime disable suppresses operational access. |

A class is operational only when it is registered, its addon is enabled, the player is not class-banned, the class is allowed, and the class is the single selected active class. Registration alone grants no player access. A missing historical addon/class can remain in stored state while yielding no operational authorization.

For a non-hybrid player, assigning another class replaces the allowed set and activates the new class. For a hybrid player, `set` retains other allowed classes and activates the assigned class. Adding a second class requires hybrid authorization. Turning hybrid off retains the active class when possible and reduces the allowed set to one. Switching validates ownership and addon state and does not modify a sword. Class Ban and addon disable retain historical assignment; removing them restores eligibility through normal lifecycle transitions.

## Server authority and secrecy

The server derives actor identity and current permission from the connection. GUI requests do not grant authority by asserting an admin mode, revision, UUID, class ID, or flag. Admin mutations require current OP level 4 and the current Core revision; stale requests fail before mutation.

The C2S action protocol is bounded to 4,096 characters and rejects unknown/duplicate fields, wrong scalar types, malformed canonical UUIDs, invalid namespaced IDs, and missing or inappropriate action arguments. The request guard rate-limits by actor with a 12-request burst and 8 requests per second refill. Server dispatch still revalidates every operation against current state; syntactic validity is not authorization.

Ordinary switch suggestions and metadata are filtered before serialization. Unauthorized real IDs and nonexistent IDs share the same ordinary-player error. A class-banned player has no switch suggestions. Admin views may expose the complete registered registry because they require management access. Already owned disabled classes may remain visible as disabled.

Admin watchers are rechecked after permission changes and before every snapshot; losing permission closes the screen instead of sending new admin data. Snapshots are capped at 262,144 characters. An unusually large world/registry that exceeds this limit uses the command interface instead of the GUI.

This protects disclosure through Core's server protocol and commands. Locally installed addon JARs and assets remain inspectable on the player's machine; Core cannot make shipped resources secret.

## Conversion ownership

The existing addon/class `Registry` now also owns optional declarative semantic weapon forms. Identity queries inspect item identity and persistent component constraints, never presentation. Ownership survives Class Ban, runtime disable and holder/class changes; it grants no ability authorization. Overlaps reject startup registration. No ownership registry is automatically serialized to clients. See [CLASS_WEAPONS.md](CLASS_WEAPONS.md).

Conversion uses the server's actual held stack and current active class. Core checks class authorization, runtime addon state, and registered source-item compatibility before invoking the destination provider. Sword switching and class switching are independent operations.

An addon creates the destination sword. Transfer starts with that destination's own components and applies only approved physical source state. Custom name, compatible enchantments, and Core's neutral stealth state are handled explicitly; addon-private components require explicit transfer registration. Copying the entire source component map would cross addon ownership boundaries and is forbidden by the contract.

Core owns the neutral stealth state so it can survive Prototype-family conversion. Core does not decide whether stealth changes damage, rendering, abilities, or other gameplay. Those interpretations belong to each addon.

## Runtime lifecycle and addon data

Enabling/disabling changes Core's authoritative runtime activation state; it does not unload Fabric mods. Registered items/resources remain available, and player assignments remain stored. Addons must consult Core authorization at every ability entry point, including attacks, scheduled work, network requests, and ongoing effects. An addon that bypasses this contract cannot be made inert by Core alone.

Old operational classes receive deactivation, new operational classes receive activation, and addons receive enable/disable callbacks. Deactivation and runtime disable are cleanup opportunities for transient effects, targeting, pending actions, modifiers, and temporary client state. Re-enable must not recreate stale transient effects.

Addon persistent data is namespaced, independently owned, and keyed by player UUID. Changing class must not translate, reset, or delete another class's progression. A player returning to a previous class can restore that addon's prior progression even if hybrid was never enabled.

## Persistence

Each server session loads its own `<world>/data/prototype/core-state.json`. Schema version `1` contains a monotonic `revision`, a `players` object keyed by UUID, and an `addonEnabled` object keyed by stable addon ID. Player records contain only Core's Class Ban/hybrid/allowed/active fields; no addon progression is stored here.

```json
{
  "schemaVersion": 1,
  "revision": 4,
  "players": {
    "12345678-1234-1234-1234-123456789abc": {
      "classBanned": false,
      "hybridEnabled": false,
      "allowedClasses": ["prototype_demo:demo"],
      "activeClass": "prototype_demo:demo"
    }
  },
  "addonEnabled": {"prototype_demo:demo": true}
}
```

Unknown syntactically valid historical class/addon IDs survive restart and temporary addon removal. Invalid records are diagnosed and recovered conservatively; malformed bans default to banned, hybrid flags to false, and addon flags to disabled. Structural corruption cannot silently turn all addons on. Writes use forced temporary files, atomic replacement, directory synchronization, and an exact previous-primary `.bak`. Externally changed files are protected from overwrite.

The server persists a proposed state before committing live authorization or invoking callbacks. A save failure leaves the prior live state and revision intact and blocks further writes until repair/reload. A final filesystem synchronization failure can occur after a rename, so operators must review primary/backup files before restarting after storage errors.

Corrupt primary state may recover a valid backup read-only; unsupported schema versions refuse startup. Read-only recovery does not overwrite historical files at shutdown, and administrative mutations remain unavailable until the operator restores a reviewed state and restarts. See [PERSISTENCE.md](PERSISTENCE.md) for limits and recovery instructions. Core never reads or deletes another addon's data.

## Dedicated server boundary

Common/server code and the external addon registration entrypoint must not refer to client GUI, renderer, texture manager, or other client-only classes. The native admin screen and snapshot presentation model belong to Core's `client` source set and client entrypoint. Plain API metadata uses resource identifiers as data, not loaded client resource objects.

The manual dedicated-server and multiplayer checks in [MANUAL_TESTS.md](MANUAL_TESTS.md) remain necessary after compilation and unit tests.

## Later Pale migration

Pale should depend on the production Core JAR and register its stable addon/class IDs through the public API. Register its sword as convertible and create its destination item through a conversion provider. Put its thumbnail and other class resources in Pale's own namespace.

Replace local authorization assumptions with Core's centralized operational authorization at every ability entry point. Keep Pale progression, Marks, charges, cooldowns, and other schemas in Pale's own UUID-based persistence. Interpret Core stealth according to Pale's semantics without moving those semantics into Core. On class deactivation/addon disable, clean active Hunt, outline, modifiers, pending Execution, temporary rendering, and other transient work; preserve persistent progression. Send Pale-specific network state only to recipients authorized to see it, and clear temporary client state when eligibility ends.

Preserve stable historical IDs across future migrations. Changing display names must not alter identity. Test switching away and returning after a long interval, runtime disable/re-enable, temporary addon removal, restart, and cross-addon conversion before removing Pale's previous integration path.
