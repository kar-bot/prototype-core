# Multiplayer validation

Automated tests cover the Core rules and protocol validation. Run this checklist on an actual dedicated server with two clients before distributing a release; a passing build does not establish that rendering, multiplayer timing, or third-party lifecycle cleanup works in game.

Use a new test world. Install the production Core JAR on server and clients. Install the **separate** demo addon JAR when testing conversion and lifecycle behavior. Use one OP level 4 administrator and one ordinary player; keep their UUIDs stable across restarts. A second independent addon/class is useful for cross-addon and secret-class cases.

## Installation and native presentation

- Start a dedicated server with Core alone, then with Core and the demo addon. Neither startup may load client-only Minecraft classes on the server.
- Join with matching client mods. Craft `prototype:prototype_sword` using its recipe. Compare attack damage/speed with a wooden sword; confirm normal survival use does not damage it and it has no class abilities.
- Open `/prototype admin` as OP level 4 with menu background blur enabled. Test Players, Addons, and class selection; repeat with no compatible addon registered and with an addon installed. Confirm every screen opens without "Can only blur once per frame", and background, panel, native controls, heads, thumbnails, tooltips, search and scrolling remain visible at small and large GUI scales. The demo is optional.
- Verify addon thumbnail rendering; temporarily remove an addon thumbnail in a **test copy** and confirm Core renders its fallback without crashing. Test an invalid resource as well.
- Revoke OP while the screen is open. Every subsequent mutation must fail and must not update state.

## Class authorization and preservation

- Assign the ordinary player one class. Assign a different class while hybrid is off: the previous authorization must be replaced and the new class must be active.
- Attempt to add a second class with hybrid off: reject safely. Enable hybrid, add a second class, and verify the GUI marks allowed classes separately from the single active class.
- Run `/prototype switch <class>` between allowed classes. The previous class receives deactivation and the new class receives activation; held items do not change.
- Disable hybrid while two classes are allowed. The active class is retained when possible and the allowed set shrinks to one.
- Apply **Class Ban**. The player can still join and play; switching, conversion, and addon authorization stop. Allowed/history state remains. Unban and verify eligibility returns.
- Create addon progression or a namespaced test value, switch away, then return after restart. The original addon-owned persistent value must remain unchanged.

## Privacy and hostile requests

- Ordinary `/prototype switch` suggestions include only the player's allowed, registered classes. A class-banned player receives no suggestions.
- Enter a registered secret class ID without authorization, then a nonexistent ID: the player must receive the same generic failure.
- Inspect ordinary-player snapshots/packets with a development client. Unauthorized addon names, class IDs, versions, and thumbnails must be absent. An admin snapshot may contain the complete registry.
- An owned class whose addon is disabled may remain visible as disabled. It must not become operational.
- Send forged admin actions as an ordinary player, including set/add/remove/clear, hybrid, class-ban, and addon enable. Reject every request without a state change.
- Keep two admin screens open. Mutate from one, then submit an action based on the stale revision from the other. The stale mutation must fail and the screen must refresh.
- Test invalid identifiers, invalid UUIDs/action values, oversized strings or payloads, and fabricated revisions. Reject safely without disclosing hidden registry contents or disconnecting unrelated players.

## Conversion and stealth

- With an authorized active demo class, convert a Prototype Sword using `/prototype convert`; then convert a registered compatible addon sword. Only the held weapon changes.
- Try an incompatible held item, an empty hand, no active class, an unauthorized class, Class Ban, and a disabled addon. Every rejected conversion must leave the original stack untouched.
- Apply a custom name and valid enchantments before conversion. Verify the supported transferable state is retained and invalid enchantments do not transfer.
- Shift-right-click the authorized demo sword to toggle Core's neutral stealth state, convert, and verify it persists. Ordinary right-click shows its action-bar status. Confirm unrelated addon-private components do not transfer automatically.
- Exercise any explicitly registered transferable component with the destination addon. Verify a rejected provider/transfer leaves the source item intact.
- Attempt to force conversion through a fabricated client request or stale item state. Server authorization and the actual held item remain decisive.

## Runtime disable, lifecycle, and restart

- Enable a class, trigger the demo's trivial authorized behavior, then disable its addon. Authorization becomes false immediately; conversion into it fails; activation/deactivation and addon callbacks occur as documented.
- Verify existing addon swords remain installed and visible, but compliant class behavior is inert. Stored allowed classes and addon progression remain.
- Re-enable the addon. Eligibility returns through normal activation, with no stale transient effect restored.
- Restart the server. UUID assignments, hybrid state, Class Ban, active class, and addon enable/disable state must round-trip.
- Remove an addon temporarily, start and stop the server, reinstall it, and verify historical IDs/authorization survived without destroying unrelated players' data.
- In a disposable world, test partially malformed persistence data. Valid unrelated state should survive; inspect logs and backup/recovery behavior rather than silently accepting corruption.
- Disconnect/reconnect, die/respawn, switch worlds, and stop the server while a class has transient effects. Confirm lifecycle cleanup and later activation do not leak another player's state.

Record the Minecraft/Fabric/Core/addon versions, server log, client log, and failed steps. These are manual acceptance checks, not claims that a test was executed.

## Neutral controls and addon Status (0.2 API)

- Confirm Controls lists exactly Class Ability 1, Class Ability 2 and Class Status under Prototype Core. Rebind all three and verify the new keys work. Typing in screens must not trigger class controls.
- With demo assigned, Ability 1 shows its action-bar message, Ability 2 is silent, and Status opens its addon screen. Verify native background blur is extracted once and Done/Escape work.
- Switch to a second control-enabled addon with Hybrid on. Each key routes only to the current class without reconnecting. Hold a foreign sword and verify addon-specific item checks still apply.
- Ban/unban and disable/re-enable while testing controls. Disabled/banned handlers must never execute; status closes on deactivation, and no stale transient work reappears.
- Use two clients, including an ordinary player without secret-class authorization. Status data goes only to its requester. Invalid slots and unavailable handlers disclose no class metadata. Verify status closes on disconnect/respawn and never reopens from a stale delayed response.
