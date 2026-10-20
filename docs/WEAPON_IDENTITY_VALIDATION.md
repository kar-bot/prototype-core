# Semantic weapon identity validation: 0.2.0-alpha.2, API 2

Baseline: `bea49a7123fc9e7f5b1c8bf1557d51164b358b95`, `0.2.0-alpha.1`, tag `v0.2.0-alpha.1`. Local/GitHub `main` matched before changes. Pale, Warden and Vampire were not modified. Unrelated local release-skill files were preserved.

## Public API and semantics

`ClassWeaponForm.of(Item)`, `.with(DataComponentType<T>, T)` and `.without(DataComponentType<?>)` declare immutable item/persistent-component forms. The five-argument `PrototypeApi.registerAddon` accepts `Map<Id, List<ClassWeaponForm>>`; existing overloads remain intact. The existing `Registry` owns forms and freezes them with class metadata; there is no independent runtime registry or automatic ownership synchronization.

Queries are `PrototypeApi.classWeaponOwner(ItemStack)` returning `Optional<Id>`, `isClassWeapon(ItemStack)` and `isClassWeaponOf(ItemStack, Id)`. Actual item and persistent semantic state establish identity; names/models/lore are not inferred. Overlapping declarations fail registration atomically in either load order, including redundant overlapping forms within one class.

Identity ignores holder, active class, Hybrid, bans and addon-enabled state. It grants no ability use. Convertibility is separate, and ordinary/legacy unclassified items return no owner. Declared conversion targets are revalidated after all transfers; a foreign/unmatched destination leaves the source intact. Older integrations without declarations retain legacy conversion behavior.

The demo registers its whole sword, queries own identity on interaction, and classifies the actual held stack through neutral Ability 1 without sending foreign IDs. Tests demonstrate shared-base marker forms and an alternate non-convertible item representation. No gameplay/progression state moved into Core.

## Executed tests and build

```sh
source scripts/dev-env.sh
./gradlew clean build :integration-tests:runGameTest --warning-mode all
```

| Suite | Tests | Failures/errors/skips |
| --- | ---: | ---: |
| Domain registration, ownership, authorization, lifecycle, persistence and wire security | 154 | 0 |
| Core client/architecture, registration, existing GUI and privacy boundaries | 34 | 0 |
| Demo GUI/server-boundary regression checks | 2 | 0 |
| Dedicated-server commands, conversion, controls, lifecycle and ownership | 78 | 0 |
| Total | 268 | 0 |

Added 34 cases: 16 JUnit and 18 GameTests. Coverage includes declarations, freeze, atomic conflicting registration, both registration orders, defensive copies, null/empty/unknown queries, component state and absence, real stealth/disguise, own/foreign classification, disabled/inactive/unauthorized/banned identity, ordinary-but-convertible items, owned-but-not-convertible forms, persistence/network stack codecs, appearance/owner-string spoofing, snapshot/client privacy, conversion and source preservation after bad identity transfer.

Review found Minecraft's defaulted item registry returns the air key for an unregistered item. Exact reverse lookup now rejects this case, with a startup regression fixture. The complete final clean suite passed after that guard and the final demo changes. No compiler or Gradle deprecation warnings occurred. Headless test logs can contain first-run world configuration/authentication discovery warnings and intentional provider/handler failure fixtures; these did not fail the suite.

Public declarations and descriptors were compared against the published alpha.1 binary across all 31 previously published API classes: none were removed or changed. Manifest API remains 2. Dedicated-server startup/teardown loaded Core, demo and test addons without client-only classloading. Existing controls, authorization, GUI, persistence and conversion regressions still pass.

Final reports: module `build/test-results/test`, `integration-tests/build/gametest-results.xml`; retained cloud log `/tmp/prototype-weapons-clean-build.log`.

## Release artifacts

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `prototype-core-0.2.0-alpha.2.jar` | 168439 | `11b83deed90bb0b96e798f038228e8dce3e2f3bd395a96a8c6b523d88e4619b3` |
| `prototype-demo-addon-0.2.0-alpha.2.jar` | 17558 | `a08dfc3640035bbd0576cbca8996624a7cbf8166fd5a7b2b6a75b554549494f8` |

Production Core contains the public form API and registry/stack bridges, and excludes demo, test and ASM code. The demo remains a separate optional JAR. Sources are separate development artifacts. Both expand version alpha.2; Core retains API 2.

## Migration readiness

Pale and Warden retain their API 2 integration. To adopt identity, raise their minimum dependency to alpha.2 and add the fifth registration argument with whole-item forms for dedicated swords. If stealth changes the physical base item, preserve/register a persistent semantic marker for that representation. Component-only visual disguise is covered automatically by a whole-item declaration. Existing unregistered forms remain unclassified; Core guesses nothing.

Vampire can resume the identity-dependent API integration using owner/own/foreign queries without importing Pale/Warden internals. Actual recognition of those addons' weapons requires their declarations. Hijacking, enchant projection, Blood rendering, reward suppression, abilities and progression remain Vampire-owned. Combined addon gameplay and real client/multiplayer acceptance remain to be tested by the respective addon integrations. See [the public contract](CLASS_WEAPONS.md), the authoritative addon skill and [manual checks](MANUAL_TESTS.md).
