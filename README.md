# Prototype Core

A standalone Fabric foundation for independently developed supernatural sword/class addons. Core owns the Prototype Sword, addon registration, UUID-based class authorization, class bans, hybrid authorization, runtime addon state, conversion, lifecycle events, and server-authoritative administration. Addons own their abilities, progression, cooldowns, rendering, and class-specific data.

This repository is independent of Pale. It implements no Pale, Warden, Vampire, Marks, Hunt, Execution, or other class gameplay. The small demo addon is a separate consumer and separate JAR.

## Toolchain and modules

Development prerelease: `0.2.0-alpha.1` (latest stable remains `0.1.1`).

Core now supplies three configurable neutral controls: Class Ability 1 (R), Class Ability 2 (G), and Class Status (K). The server routes each request only to the currently authorized active class. Addons register optional handlers and own their Status data/screens. See [the control API](docs/CLASS_CONTROLS.md) and the separate demo addon.

| Component | Version |
| --- | --- |
| Minecraft Java | `26.3` |
| Fabric Loader | `0.19.5` |
| Fabric API | `0.162.0+26.3` |
| Fabric Loom | `1.18.3` |
| Gradle | `9.7.1` |
| Java | `25` |

Minecraft 26.3 uses unobfuscated Mojang names; the build does not add an obsolete mapping layer.

| Module | Purpose |
| --- | --- |
| `core-domain` | Plain Java public registration, authorization, lifecycle, persistence/security rules and unit tests. Its classes are included in the production Core JAR. |
| `core` | Fabric integration, Prototype Sword, conversion, commands/networking, and native admin GUI. Client implementation has a separate source set. |
| `demo-addon` | External API consumer with its own metadata, thumbnail, class, convertible sword, and intentionally trivial behavior. |
| `integration-tests` | Dedicated-server Fabric GameTest consumer for real commands, conversion, and runtime integration; never install it in a production world. |

## Build and run

Use a JDK 25 installation. From the repository root:

```sh
./gradlew clean test :core:build :demo-addon:build
./gradlew :integration-tests:runGameTest
```

The GameTest XML report is `integration-tests/build/gametest-results.xml`. Gradle's ordinary `test` task runs the JUnit suites; `runGameTest` is a separate required server integration check. Use a disposable generated GameTest world.

In the prepared cloud machine, `source scripts/dev-env.sh` selects the installed JDK 25 and writable Gradle cache. `scripts/setup-cloud.sh` installs the pinned, checksum-verified JDK when missing and configures the platform's Java proxy/CA trust before repeatable JUnit/build validation. Existing tasks use the existing checkout; no Git worktree is required.

The production outputs are `core/build/libs/prototype-core-0.2.0-alpha.1.jar` and `demo-addon/build/libs/prototype-demo-addon-0.2.0-alpha.1.jar`. Install Core together with the pinned Fabric Loader/API on both the server and clients. The demo JAR is optional and must be installed separately for demo validation; it is not inside Core.

For development, use the module's Loom run tasks, such as `./gradlew :core:runClient` and `./gradlew :core:runServer`. A dedicated development server requires accepting Minecraft's EULA in its generated run directory. Add the demo addon JAR to that run's `mods` directory when exercising the external consumer.

The 0.2.0-alpha.1 clean build passed **234 tests**: 174 JUnit and 60 dedicated-server GameTests, with no failures or skips. This includes neutral-control security, switching and lifecycle checks, and the existing admin background/blur regressions plus the new demo Status screen regression. See the [control validation report](docs/CONTROLS_VALIDATION.md) for checksums and remaining manual client checks, and [earlier delivery evidence](docs/VALIDATION.md) for the 0.1.1 history.

## Player and administration workflow

Craft `prototype:prototype_sword`, a wooden-sword-strength, unbreakable weapon with no supernatural behavior. An administrator authorizes a class; an authorized player may use `/prototype switch <class>` and `/prototype convert`. Switching the active class does not convert held items.

Ordinary players have one allowed class by default. Setting a different class replaces it. Hybrid authorization permits several allowed classes, with exactly one active class. **Class Ban** suppresses operational class access without banning the player from the server or deleting historical assignments.

OP level 4 administrators use `/prototype admin` for the Players/Addons screens and the commands below:

```text
/prototype class set <player> <class>
/prototype class add <player> <class>
/prototype class remove <player> <class>
/prototype class clear <player>
/prototype hybrid enable <player>
/prototype hybrid disable <player>
/prototype classban <player>
/prototype classunban <player>
/prototype addon enable <addon>
/prototype addon disable <addon>
```

Runtime disable retains installed items and stored assignments. Compliant addons become inert immediately through Core's authorization API and receive cleanup callbacks; re-enabling restores eligibility through activation callbacks.

The admin GUI has Players/Addons tabs, search, scrolling, online player heads, and an allowed-class selector that identifies the active class separately. Hybrid selection uses checkboxes; non-hybrid selection uses a single choice. Missing historical classes remain removable. Offline players use cached names when known and default skins; otherwise their names fall back to an abbreviated UUID. All changes wait for the server's revision-checked response. A normal-player class GUI is deferred; `/prototype switch` is the player interface.

## Security and development contracts

The server validates commands and GUI mutations. Ordinary player suggestions and snapshots contain only authorized class metadata; guessing an unauthorized or nonexistent class receives the same response. Admin GUI mutations carry revisions so stale screens cannot overwrite newer state.

Conversion delegates item creation to the active class provider, then copies only approved physical state. Core's neutral stealth component survives compatible conversions. Addon-private components do not transfer automatically. Addons must query centralized authorization at every ability entry point and clean transient effects on deactivation/disable. Their UUID-based progression remains their responsibility.

Installed client resource packs/mod JARs physically contain their own assets. Core's privacy contract limits server disclosure; it cannot hide assets or metadata from someone inspecting locally installed addon files.

See [addon API and demo example](docs/ADDON_API.md), [architecture and invariants](docs/ARCHITECTURE.md), [conversion details](docs/CONVERSION.md), [persistence and recovery](docs/PERSISTENCE.md), [validation report](docs/VALIDATION.md), and [manual multiplayer acceptance checks](docs/MANUAL_TESTS.md). The manual checklist explicitly requires in-game validation. Exceptionally large registry/player snapshots exceeding 262,144 characters use the commands instead of the GUI.
