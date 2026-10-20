# Prototype Core validation

Current 0.2.0-alpha.2 evidence: [semantic weapon identity validation](WEAPON_IDENTITY_VALIDATION.md). Earlier [neutral control validation](CONTROLS_VALIDATION.md) records alpha.1. The following report records the historical 0.1.1 delivery and its artifacts/results.

## Prototype Core 0.1.1 delivery and validation

Prototype Core is a new standalone project targeting Minecraft Java 26.3, Fabric Loader 0.19.5, Fabric API 0.162.0+26.3, Loom 1.18.3, Gradle 9.7.1, and Java 25. The existing Pale repository is outside this project's scope. Core contains no supernatural class gameplay; the demo is a separate JAR.

## Architecture and delivered behavior

| Requested report item | Implementation |
| --- | --- |
| 1. Architecture/packages | `core-domain`: plain Java `dev.prototype.core.api`, `security`, and `persistence`; `core`: Fabric/Minecraft integration, `api.minecraft`, conversion/items/commands/networking, separate `client` source set; `demo-addon`: external consumer; `integration-tests`: test-only server consumer. |
| 2. Public API | Stable namespaced `Id`, addon/class definitions, `Registry`, `CoreService` authorization/state queries, typed `AdminActions`/`AdminRequest`, lifecycle context/reasons; Minecraft `PrototypeApi`, sword provider/context, stealth helper/component handle, manager safe-transfer/consent/query methods, and trusted server conversion/result APIs. See [ADDON_API.md](ADDON_API.md). |
| 3. Persistence | `<world>/data/prototype/core-state.json`, schema 1, UUID-keyed player records, addon enable flags, revision, atomic saves and previous-primary `.bak`. Unknown historical IDs survive; corruption uses conservative recovery/read-only protection. See [PERSISTENCE.md](PERSISTENCE.md). |
| 4. Authorization | Registered class + enabled owning addon + allowed class + single selected active class + no Class Ban. Non-hybrid assignment replaces; hybrid authorization permits several allowed classes with one active. Stored selection remains during bans/disable. |
| 5. Secret-class privacy | Ordinary suggestions and snapshots filter on the server; guesses for unauthorized and nonexistent classes share a generic failure. Only authorized metadata/thumbnails are serialized. OP4 admin views see the complete registry; revocation closes them. |
| 6. Conversion | Server-held single compatible sword, current operational active class, clean destination provider, explicit common-state transfer, bilateral component-transfer consent, inventory/revision revalidation, one main-hand replacement. Provider refusal/failure leaves source intact. See [CONVERSION.md](CONVERSION.md). |
| 7. Stealth | Core-owned persistent/network-synchronized `prototype:stealth` Boolean; `StealthState` helper and component handle; false and true survive conversion. Addons decide gameplay semantics. |
| 8. Runtime enable/disable | Persisted authoritative addon flags, immediate authorization suppression, conversion-into rejection, installed items/resources retained, no JVM unloading. Re-enable restores eligibility normally. |
| 9. Lifecycle | Server-thread callbacks after durable state commit; old deactivation before new activation; addon disable cleanup and clean enable activation; join/leave/respawn/shutdown handling; callback isolation and blocked reentrant mutations. Addon progression remains untouched. |
| 10. Admin commands | OP4 `/prototype class set/add/remove/clear`, `hybrid enable/disable`, `classban`, `classunban`, `addon enable/disable`, and `/prototype admin`; player `/prototype switch <class>` and `/prototype convert`. Namespaced IDs are accepted unquoted. |
| 11. GUI | Native Players/Addons tabs, search and scrolling, online/default player heads, allowed-versus-active class selection, hybrid checkboxes, Class Ban, addon thumbnail/fallback/version/state controls. Every mutation is revision-checked and waits for a server response. Player GUI is deferred. |
| 12. Demo | `prototype_demo:demo`, own thumbnail and convertible `prototype_demo:demo_sword`; authorized right-click status and shift-right-click stealth toggle; centralized kill switch and transient lifecycle cleanup. No progression or production embedding. |
| 13. Created modules/files | Four Gradle modules; wrapper and pinned properties; source/resources/tests; production sword recipe/model/texture/fallback; demo assets; cloud setup helpers; README and developer/architecture/conversion/persistence/manual/validation docs. |
| 14. Automated tests | JUnit registration/authorization/lifecycle/persistence/wire/privacy/admin/client-thumbnail tests plus dedicated-server GameTests for actual commands and conversion. Final executed totals/results appear below. |
| 15. Production Core JAR | `core/build/libs/prototype-core-0.1.1.jar`; includes domain classes and Core resources, excludes demo/test consumer classes. |
| 16. Demo JAR | `demo-addon/build/libs/prototype-demo-addon-0.1.0.jar`; install separately for API validation. |
| 17. Clean builds | `./gradlew clean build :integration-tests:runGameTest`; final results appear below. |
| 18. Warnings/limitations | Manual visual/multiplayer acceptance is still required. No normal-player GUI. Runtime disable requires addon compliance; client-shipped files remain inspectable. Large snapshots fall back to commands; offline identities/skins use cache/defaults. Filesystem durability limitations are documented. |
| 19. Manual multiplayer tests | Two clients with OP4/ordinary actors: survival recipe, GUI scales/scrolling, assignment/hybrid/ban, secrecy and forged requests, stale revisions, conversion/name/enchantments/stealth, disable/re-enable, restart/missing addon, progression/transient cleanup. See [MANUAL_TESTS.md](MANUAL_TESTS.md). |
| 20. Later Pale migration | Depend on public Core API, preserve stable IDs and UUID-owned Pale progression, register sword/provider/resources, check centralized authorization everywhere, interpret neutral stealth in Pale, and clean Hunt/outline/modifiers/pending Execution/rendering on deactivation/disable. See [ARCHITECTURE.md](ARCHITECTURE.md). |

## Admin GUI blur regression fixed in 0.1.1

Minecraft 26.3's final `Screen.extractRenderStateWithTooltipAndSubtitles()` already extracts the background before invoking screen content. `AdminPanelScreen.extractRenderState()` called `extractBackground()` again, attempting the same blur twice and crashing even with an empty addon registry. Removed that one redundant call. Normal background remains vanilla-owned; panel drawing, rows, native widgets, thumbnails, player heads, search, scrolling and deferred tooltips stay intact. `PrototypeAdminScreen` (both Players/Addons tabs) and `ClassSelectionScreen` inherit the corrected path. No other custom screen calls background/blur extraction.

Nine headless compiled render-path regression cases inspect actual Minecraft ownership/order, both concrete screen inheritance chains, both admin tab renderers, normal panel/native-widget extraction, automatic discovery of future custom screens, and duplicate calls hidden in helpers/method references. Before the fix, five affected-screen cases failed, each naming the original duplicate call; after the fix, all nine pass. The full clean suite passes **210 tests**: **165 JUnit** and **45 dedicated-server GameTests**, zero failures/errors/skips.

The updated production JAR differs from 0.1.0 only in `AdminPanelScreen.class`, `fabric.mod.json` and manifest version metadata. All **58 common/server/API classes are byte-for-byte unchanged**, and production resources, other GUI classes and JAR membership are preserved. Test-only ASM is excluded from production. Core standalone startup is also checked without the demo. The demo is optional; no Pale code was changed. A live Pale-equipped graphical client was not available in this cloud machine, so these regressions validate the compiled extraction contract, not a rendered multiplayer frame.

Regression red/green logs are retained in `build/validation/blur-regression-before.log` and `build/validation/blur-regression-after.log`. The updated clean build/full-suite log is `build/validation/clean-build-and-gametest.log`.

## Final execution evidence

Final current-source validation succeeded with the pinned toolchain:

```sh
source scripts/dev-env.sh
./gradlew clean build :integration-tests:runGameTest --warning-mode all
```

All production, demo, and test-consumer compilation/build tasks completed successfully. Both release JARs were also rebuilt from clean module directories. There were no compiler or Gradle deprecation warnings in the final run.

| Executed suite | Tests | Failed/errors | Skipped |
| --- | ---: | ---: | ---: |
| Domain registration, authorization, lifecycle, administrative guard, persistence, wire privacy/security | 139 | 0 | 0 |
| Client snapshot, PNG thumbnail fallback and GUI background regression validation | 26 | 0 | 0 |
| Dedicated-server conversion, crafting, item component codecs, real Brigadier commands, demo behavior and disconnect lifecycle | 45 | 0 | 0 |
| **Total** | **210** | **0** | **0** |

JUnit counts are from the current XML reports. The GameTest runner reported **all 45 required tests passed**, and its current XML contains 45 cases with no failures, errors, or skips. The separate demo is exercised through the server GameTests; it does not have a separate JUnit suite. The disconnect regressions verify that background callbacks dispatch lifecycle cleanup on the server thread, duplicate disconnects clean up once, and unattached UUIDs leave authorization/revision unchanged.

Production Core 0.1.1 alone also started a dedicated Minecraft 26.3 server without the demo, registered and exposed the command tree, saved the world, and shut down cleanly. Combined Core/demo/test startup and shutdown passed in the GameTest server. No common/server/demo source imports client-only Minecraft or rendering APIs.

Artifact contents were inspected: production Core includes the domain/public API, sword/resources and client entrypoint, with **no demo or integration-test code/assets**. The demo contains its own classes/resources, with no embedded Core classes. Production metadata expands to Core `0.1.1`; the unchanged optional demo remains `0.1.0`. The Gradle wrapper matches the official wrapper SHA-256, and its distribution checksum is pinned.

| Release artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `core/build/libs/prototype-core-0.1.1.jar` | 149396 | `cf40bdbb31424b19816ba160f7316d24c2ed7d77e969bf28bb6ccf676511a76b` |
| `demo-addon/build/libs/prototype-demo-addon-0.1.0.jar` | 10571 | `e91981aa4fef7bb8a2678328562ba5429c8a16ad6914bfc006848c4156e2356b` |

`scripts/setup-cloud.sh` was executed successfully and repeated after the final code changes; it configures the retained JDK/Gradle cache and runs JUnit/build checks without modifying application sources. Reusable `install_script` and `start_skill` were saved to the environment draft. Saving the draft does not publish an environment or validate restoration in a new task; review/save the environment settings and publish to retain the prepared snapshot.

The final build/GameTest log is retained at `build/validation/clean-build-and-gametest.log`; the production-only startup log is `build/validation/production-server.log`. Their warning/error paths include intentional failing-provider fixtures. Headless Minecraft emitted authentication-services DNS warnings and an initial world-generation timing warning; authenticated online login and GUI visual rendering were not validated. The offline smoke fixture is restricted to loopback. The final Gradle resource-expansion deprecations found during earlier builds were corrected and are absent from the final run.


JUnit XML/HTML reports live under each tested module's `build/test-results/test` and `build/reports/tests/test`. Dedicated-server GameTest results are written to `integration-tests/build/gametest-results.xml`.

## Practical limits

Compilation, unit tests, and headless dedicated-server tests do not establish that a native GUI looks correct on a real client or that third-party gameplay cleans itself up under multiplayer timing. Run the manual checklist on matching server/client installations before distributing a release. The test-only integration addon must never be included in production deployments.

Core's privacy contract covers its server responses, suggestions, and snapshots. A player who physically installs an addon can inspect its files. Addon providers and lifecycle hooks are trusted Java mod code; inventory rollback is not a sandbox for arbitrary world edits or external side effects. Every compliant ability must query Core and every transient task must clean up on deactivation/disable.
