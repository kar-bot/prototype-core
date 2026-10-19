# Sword conversion contract

`/prototype convert` converts the main-hand sword into the player's one operational active class.
It does not select a class. The server checks registration, the class ban, stored authorization,
active class, runtime addon state, held item identity, and a count of exactly one. The provider
must be registered for that class. Source and destination items must both be registered as
convertible. A disabled addon sword can still be converted *out of* into another enabled,
authorized class.

## Implementing a provider

During Fabric common initialization, register the item and then:

```java
PrototypeApi.registerConvertible(DemoItems.DEMO_SWORD);
PrototypeApi.registerAddon(definition, Map.of(DEMO_CLASS, context -> {
    // Return Optional.empty() to decline a particular source.
    return Optional.of(new ItemStack(DemoItems.DEMO_SWORD));
}), lifecycle);
```

`SwordConversionProvider` and `SwordConversionContext` are public APIs in
`dev.prototype.core.api.minecraft`. The context exposes `player()`, `targetClass()`, and
`source()`. Each `source()` call returns a copy, never the live held stack. A provider returns
a **new, clean, single-item stack** with its own item's defaults and any newly initialized
destination state. Never use `transmuteCopy`, copy all source components, or merge source
component patches. Core retains factory-initialized destination components; it does not
translate, inspect, or migrate private progression. An addon remains responsible for the
data it explicitly writes in its own factory.

Providers and transfer copiers run synchronously on the server thread. Treat `player()` as
read-only: do not give/drop items, mutate inventories, open/close menus, change authorization,
or perform gameplay effects. Return empty for expected refusal. Core copies the provider
result, validates it, transfers safe state, rechecks inventory and authorization revision,
and replaces the selected main-hand slot exactly once. It does not delete and `/give`.
Reentrant conversions fail. Callback exceptions and detected inventory/cursor/selection
mutations fail and restore the inventory snapshot; they cannot commit a converted item.

Callbacks belong to trusted installed Java mods, not a security sandbox. Core can restore
the player's inventory but cannot undo arbitrary world changes, external I/O, or mutations
to another mod's state. Such side effects violate this API contract. Data component values
must obey Minecraft's immutable-value contract so `ItemStack.copy()` remains a safe snapshot.

## Default physical state transfer

Core transfers these components, and no blanket source component patch:

* `minecraft:custom_name`, with a copied text component.
* `minecraft:enchantments`, only when the enchantment supports the destination item.
  Levels are limited to the destination enchantment's valid maximum; invalid low levels are
  omitted. Mutually exclusive enchantments are resolved deterministically by registered ID,
  retaining valid factory enchantments before adding source enchantments. Add swords to
  `minecraft:swords` or appropriate enchantable tags so support checks recognize them.
* `prototype:stealth`, a persistent, synchronized Boolean. Missing means false. Explicit
  false and true both survive conversion. `StealthState.isStealthed(stack)` and
  `StealthState.setStealthed(stack, value)` are public helpers. Core assigns no gameplay
  meaning to the flag.

Durability, stats, unbreakable state, lore, custom data/NBT, charge counters, cooldowns,
Marks, and other private components are not implicitly transferred. Destination defaults
decide physical properties. Enchantment transfer uses current server datapack support tags;
addon authors must supply the required tags and account for datapack changes.

## Explicit additional component transfer

For an immutable or defensively copied common component:

```java
Id shared = Id.parse("my_addon:shared_finish");
PrototypeApi.conversions().registerSafeTransfer(shared, MY_FINISH_COMPONENT, value -> value);
PrototypeApi.conversions().permitTransfer(MY_SWORD, shared);
// The other item's owner must explicitly permit the same shared transfer ID too.
```

Register each transfer once before registration closes. The supplied copier must return a
non-null value and must not have side effects. A transfer only executes when **both** the
source item and destination item permit the same transfer ID. One-sided opt-in transfers
nothing. Core-managed name, enchantment, and stealth policies cannot be overridden this way.
Use this extension only for a deliberately agreed common representation, never for automatic
translation of unrelated classes' private progression.

## Prototype Sword

`prototype:prototype_sword` uses exactly the wooden sword's `ToolMaterial.WOOD`, damage
modifier `3.0`, and attack speed modifier `-2.4`; its neutral component makes it unbreakable.
It adds no supernatural mechanics. Its survival recipe uses two wooden tool materials,
one iron nugget, and one stick:

```text
 P
IP
 S
```

The nugget makes this recipe distinct from the ordinary wooden sword recipe. Core supplies
no recipe for addon swords. Models and original pixel textures are under `assets/prototype`.
