# Prototype Core 0.2.0-alpha.1 validation

Baseline inspected: `de3b46f19e6450b02605e4671e65c43990bb256b` on `main`, matching GitHub and the Pale readiness audit. No Pale, Warden or Vampire repository was modified. This prerelease adds neutral controls while preserving existing registration overloads, authorization, lifecycle, conversion, persistence and admin screens.

## Implemented contract

- Exactly three configurable Core keys: Class Ability 1 (R), Class Ability 2 (G), Class Status (K).
- Public `ClassControlSlot` and `ClassControlHandler.handle(ServerPlayer)`; optional per-class handlers in the four-argument `PrototypeApi.registerAddon` overload. Registration is validated before mutation, copied and frozen at startup.
- A one-byte neutral request contains no class/addon/ability/player identity. Server admission checks the live authenticated session, rate limit and server thread; dispatch derives the operational active class and rechecks authorization.
- Only the requested slot of the one active class runs. Bans, missing history, disabled addons, unused slots and unassigned players execute no handler or disclosure response. Re-enable/unban restore eligibility without replay.
- STATUS invokes an addon server callback, which owns private S2C data and its client screen. Demo Ability 1 sends a message; Ability 2 is unused; demo Status opens its own snapshot screen and lifecycle clears it on deactivation. No addon key mappings or gameplay schemas were added to Core.
- The authoritative addon skill documents exact signatures, migration, privacy, status ownership and remaining limits. `AGENTS.md` requires synchronized API/skill changes.

## Executed validation

```sh
source scripts/dev-env.sh
./gradlew clean build :integration-tests:runGameTest --warning-mode all
```

| Suite | Tests | Failures/errors/skips |
| --- | ---: | ---: |
| Domain authorization/registration/lifecycle/persistence/security | 139 | 0 |
| Core client/protocol architecture and registration, including existing GUI regressions | 33 | 0 |
| Demo client/server boundary and GUI background regression | 2 | 0 |
| Dedicated-server commands, conversion, lifecycle and control integration | 60 | 0 |
| Total | 234 | 0 |

Added 24 cases (9 JUnit, 15 GameTests) covering registration/immutability/conflicts/freeze, all slots, Hybrid switching, authorization, ban/unban, disable/enable, unknown historical class, handler failure isolation, malformed/forged payloads, session revocation/replacement, rate limiting, status response schema, server threading, exactly three compiled mappings, and absence of client references in common classes. The demo screen has a compiled regression check preserving vanilla background ownership and native widgets.

The first run exposed a test-fixture mistake: Fabric's mock helper creates a connected player. The stale-session fixture now explicitly removes/replaces its session and restores it afterward. Final complete clean validation passed. Logs include intentionally failing provider/handler fixtures and unavailable Minecraft authentication-service discovery in the headless test environment; these did not fail tests. No compiler or Gradle deprecation warnings occurred in the final clean run.

Current report locations: module `build/test-results/test`, `integration-tests/build/gametest-results.xml`, and the retained cloud log `/tmp/prototype-controls-clean-build.log`.

## Artifacts inspected

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `prototype-core-0.2.0-alpha.1.jar` | 160120 | `f4072339ce7dad411d782565d2e7037d6326915a1b1cef237274b0b4f269bdc7` |
| `prototype-demo-addon-0.2.0-alpha.1.jar` | 17096 | `d98fa34e157ef662d69ae477e39e032609db6cb5f88ce3dd723da94a865acb35` |

Core includes the domain/public API and client controls, and excludes demo, test-consumer and ASM classes/assets. The demo includes its own client entrypoint/status screen and does not embed Core. Both manifests expand the intended prerelease version. Dedicated-server execution loaded Core, demo and the independent test addon without client-only classloading.

## Remaining acceptance and API limits

Run [manual client/multiplayer checks](MANUAL_TESTS.md) for actual Controls rebinding, rendered GUI/blur, two-client privacy, response ordering and third-party cleanup. The headless suite does not establish graphical acceptance or Pale compatibility. Pale's missing neutral-control blocker is resolved, but its gameplay migration and combined-mod testing have not been performed.

Addons still own sword identity checks, cooldowns, private persistence, status payloads/screens, live subscriptions and cleanup of delayed work. Core has no public item-to-class mapping or general player-metadata subscription. It intentionally does not interpret Pale/Warden/Vampire data.
