# Semantic class weapons (0.2.0-alpha.2, API 2)

Class-weapon identity answers what a stack IS. Authorization answers what a player may USE. Convertibility answers whether Core may replace a physical item. These are separate contracts.

## Register forms during addon initialization

Use public `dev.prototype.core.api.minecraft.ClassWeaponForm` and the new compatible overload:

```java
PrototypeApi.registerAddon(definition, providers, lifecycle, controls,
    Map.of(CLASS_ID, List.of(ClassWeaponForm.of(MY_SWORD))));
```

`ClassWeaponForm.of(Item)` covers all states of that registered non-air item. A visual disguise, lore/name change, enchantment, or neutral stealth toggle does not alter ownership. Multiple distinct item forms can belong to one class.

When a shared raw item genuinely represents several things, declare persistent semantic state explicitly:

```java
ClassWeaponForm.of(SHARED_BASE_ITEM).with(MY_SEMANTIC_COMPONENT, marker)
```

`with(DataComponentType<T>, T)` requires an exact present value. `without(DataComponentType<?>)` requires absence. Chain constraints; builder calls return new immutable forms. Component values follow Minecraft's immutable-value/equality contract. Transient/unregistered components, duplicate component constraints and common presentation fields (names, lore, model, custom model data, dye color) are rejected. Addon-defined constraints must also be semantic, not cosmetic or progression-derived.

Absent and explicit false differ. `ClassWeaponForm.of(MY_SWORD)` naturally covers both plus true; state-specific forms must declare every legitimate representation. Core infers no identity from the neutral stealth flag alone. If an addon replaces its sword with a vanilla-base stealth form or Blood item, it must preserve an authoritative persistent semantic marker and register that form. Ordinary vanilla lookalikes without the marker remain unowned.

The existing addon/class `Registry` owns these declarations and freezes them at server startup. Registration validates all forms before committing metadata, conversion providers or control handlers. Every class key must belong to the same definition. Omitted/empty form lists mean no declaration. Two-/three-/four-argument API 2 overloads remain intact and declare no ownership.

## Query the real server stack

```java
Optional<Id> owner = PrototypeApi.classWeaponOwner(player.getMainHandItem());
if (owner.isEmpty()) {
    // No registered class form matches. Ordinary or not yet adopted by its addon.
} else if (owner.get().equals(MY_CLASS)) {
    // Own class weapon.
} else {
    // Foreign registered class weapon. No other addon imports are needed.
}
boolean classWeapon = PrototypeApi.isClassWeapon(stack);
boolean ownWeapon = PrototypeApi.isClassWeaponOf(stack, MY_CLASS);
```

Methods are common/server-safe and return empty/false for null, empty or unmatched stacks. Query only authoritative inventory state for gameplay decisions. A client-supplied owner string is never an authority, and Core adds no ownership query/registry-sync protocol.

Queries do not depend on who holds the item, allowed/active classes, Hybrid, Class Ban or runtime addon enable flags. Disabled addon weapons remain semantically foreign to another class. A holder must still pass `PrototypeApi.isAuthorized` for abilities. No class gameplay or progression is stored in the identity registry.

## Deterministic conflicts

Arbitrary predicates cannot reliably prove that two registrations are disjoint, so the API accepts declarative forms only. Different items are disjoint. Same-item forms are disjoint only when a shared component constraint differs, including absence versus presence. Otherwise registration throws `IllegalArgumentException` before committing. This includes duplicates/overlapping forms within one class. Load order can never silently select an owner: a conflicting installation fails initialization in either order. Use disjoint marker values or one whole-item declaration instead of redundant forms.

## Conversion and stealth

`registerConvertible` does not establish ownership. Core's neutral Prototype Sword is convertible and unowned. Registering identity does not require convertibility.

Conversion factories initialize their destination item and semantic components. Core transfers its existing physical allowlist only, not source class markers or private progression. For a destination class with declared forms, Core verifies the final owner after ALL transfers; mismatch/no match rejects without replacing the source. Register all legitimate stealth representations. Classes without declarations retain legacy conversion behavior for API 2 compatibility.

## Migration and privacy

Pale and Warden can retain their API 2 integration, update their dependency minimum when adopting this API, and add the fifth argument declaring their sword(s). A dedicated item declaration covers component-only disguises automatically; shared vanilla-base forms require an addon-owned persistent marker. No Pale/Warden/Vampire code is changed here. Until declaration, consumers cannot identify an addon weapon semantically and must not guess.

Vampire can resume its identity-dependent integration using these queries, while keeping Blood, hijacking, projection, rendering, rewards and progression in Vampire. Combined behavior still requires Pale/Warden adoption and multi-addon testing.

Do not broadcast hidden class IDs, form lists or query results to ordinary clients. Core snapshots/autocomplete remain filtered and include no weapon registry. Installed client resources and actual item components remain inspectable; the API does not promise to hide locally available files/data.

The demo declares its whole sword and displays `isClassWeaponOf` on authorized interaction. Its neutral Ability 1 classifies the actual held stack as ordinary/unclassified, own or foreign without sending owner IDs. Hold the convertible neutral Prototype Sword to see that compatibility does not imply ownership. Tests additionally use persistent markers on one shared base item to prove disjoint multi-class semantics, ordinary/convertible lookalikes, stealth, disk/network codecs, disable/ban independence, conflicts and conversion safety.
