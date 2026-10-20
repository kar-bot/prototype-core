# Neutral class controls (0.2 API)

Core supplies exactly three rebindable keys under Prototype Core: Class Ability 1 (R), Class Ability 2 (G), and Class Status (K). Defaults are convenience only; normal Minecraft Controls owns rebinding. Keys are consumed during gameplay, not while a screen is open.

The public `dev.prototype.core.api.ClassControlSlot` enum contains `ABILITY_1`, `ABILITY_2`, and `STATUS`. `dev.prototype.core.api.minecraft.ClassControlHandler.handle(ServerPlayer)` runs on the server thread. Add controls in common initialization:

```java
PrototypeApi.registerAddon(definition, providers, lifecycle,
    Map.of(CLASS_ID, Map.of(
        ClassControlSlot.ABILITY_1, player -> abilities.useOne(player),
        ClassControlSlot.ABILITY_2, player -> abilities.useTwo(player),
        ClassControlSlot.STATUS, player -> status.sendPrivateSnapshot(player))));
```

`abilities` and `status` are addon-owned objects. Omit unused slots/classes. Controls must belong to a class in the definition. Null, foreign, conflicting and late bindings are rejected; maps are copied. Existing overloads remain compatible and supply no controls. Registration closes at server startup.

The request is one neutral byte (0, 1, or 2), with no class/addon/actor ID or gameplay state. Core schedules server work, checks the authenticated connection, shares its per-player request rate limit, and resolves the current operational active class. It validates registered/enabled/allowed/active/not-Class-Banned state before invoking only that slot. Unknown/unused/denied requests have no response. Malformed lengths are rejected by the bounded codec; normal Minecraft malformed-packet handling applies.

Hybrid never dispatches to several allowed classes. Switching, bans and runtime disable take effect on the next dispatch without reconnecting. Re-enable/unban restore eligibility without replay. Addons still own item checks, cooldowns, resources, targets, and checks in delayed work. Core isolates nonfatal handler failures in server logs but cannot undo arbitrary gameplay side effects.

## Addon-owned Status GUI

1. The Status key sends the neutral slot.
2. Core resolves the active authorized class and invokes its STATUS handler.
3. The addon gathers private server state and sends its own S2C payload only to this player.
4. Its client receiver schedules creation of its own screen.
5. On deactivation/disable/disconnect, the addon cancels pending work and clears/closes private presentation.

Core never instantiates addon screens or interprets gameplay fields. No full registry or client class cache is needed. The demo implements this using DemoStatusPayload (open/stealth fields), DemoClient and DemoStatusScreen; lifecycle sends a minimal close response. Status is a snapshot. Live refresh, response ordering and authorization-filtered updates belong to the addon. Reauthorize delayed responses before send so stale work does not resurrect a screen after switching.

The demo's Ability 1 sends an action-bar message, Ability 2 is intentionally unused, and Status opens its small addon screen. It registers no extra keys. Dedicated-server tests exercise slots, Hybrid switching, bans, enable/disable, malformed requests, session admission/rate limiting and addon-owned status data. Manual client checks still cover rebinding, rendering and multiplayer timing.
