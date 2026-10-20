package dev.prototype.core.api;

import dev.prototype.core.weapon.WeaponIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Registration is atomic and deterministic; freeze before starting a server. */
public final class Registry {
    private final Map<Id, AddonDefinition> addons = new TreeMap<>();
    private final Map<Id, ClassDefinition> classes = new TreeMap<>();
    private final Map<Id, Id> classOwners = new TreeMap<>();
    private final Map<Id, AddonLifecycle> lifecycles = new TreeMap<>();
    private final Map<Id, List<WeaponIdentity>> classWeapons = new TreeMap<>();
    private boolean frozen;

    public synchronized void register(AddonDefinition definition) { register(definition, AddonLifecycle.NONE); }
    public synchronized void register(AddonDefinition definition, AddonLifecycle lifecycle) {
        register(definition, lifecycle, Map.of());
    }

    /** Internal Minecraft bridge; external addons register typed forms through PrototypeApi. */
    public synchronized void register(AddonDefinition definition, AddonLifecycle lifecycle,
                                      Map<Id, List<WeaponIdentity>> weapons) {
        if (frozen) throw new IllegalStateException("Prototype registry is frozen");
        if (definition == null || lifecycle == null) throw new IllegalArgumentException("Registration and lifecycle are required");
        if (addons.containsKey(definition.id())) throw new IllegalArgumentException("Duplicate addon identifier: " + definition.id());
        for (var clazz : definition.classes()) {
            if (classes.containsKey(clazz.id())) throw new IllegalArgumentException("Duplicate class identifier: " + clazz.id());
        }
        var ids = definition.classes().stream().map(ClassDefinition::id).collect(Collectors.toSet());
        var prepared = new TreeMap<Id, List<WeaponIdentity>>();
        var claimed = new ArrayList<WeaponIdentity>();
        classWeapons.values().forEach(claimed::addAll);
        Objects.requireNonNull(weapons, "weapons").forEach((id, forms) -> {
            if (id == null || !ids.contains(id)) throw new IllegalArgumentException("Weapon class must belong to this addon");
            var copy = List.copyOf(forms);
            for (var form : copy) {
                if (claimed.stream().anyMatch(form::overlaps)) {
                    throw new IllegalArgumentException("Overlapping class weapon forms are not allowed");
                }
                claimed.add(form);
            }
            prepared.put(id, copy);
        });
        addons.put(definition.id(), definition);
        lifecycles.put(definition.id(), lifecycle);
        for (var clazz : definition.classes()) {
            classes.put(clazz.id(), clazz);
            classOwners.put(clazz.id(), definition.id());
        }
        classWeapons.putAll(prepared);
    }

    public synchronized void freeze() { frozen = true; }
    public synchronized boolean isFrozen() { return frozen; }
    public synchronized Optional<AddonDefinition> addon(Id id) { return id == null ? Optional.empty() : Optional.ofNullable(addons.get(id)); }
    public synchronized Optional<ClassDefinition> classDefinition(Id id) { return id == null ? Optional.empty() : Optional.ofNullable(classes.get(id)); }
    public synchronized Optional<AddonDefinition> addonForClass(Id id) {
        Id owner = id == null ? null : classOwners.get(id);
        return owner == null ? Optional.empty() : Optional.ofNullable(addons.get(owner));
    }
    public synchronized List<AddonDefinition> addons() { return List.copyOf(addons.values()); }
    public synchronized List<ClassDefinition> classes() { return List.copyOf(classes.values()); }
    /** Identity ignores holder authorization and runtime enable flags. No ownership enumeration API. */
    public synchronized Optional<Id> classWeaponOwner(Id item, Function<Id, Object> componentLookup) {
        if (item == null) return Optional.empty();
        for (var entry : classWeapons.entrySet()) {
            for (var form : entry.getValue()) {
                if (form.item().equals(item) && form.matches(componentLookup)) return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }
    /** Internal conversion validation; legacy classes without forms retain their conversion contract. */
    public synchronized boolean hasClassWeapons(Id classId) {
        return classId != null && !classWeapons.getOrDefault(classId, List.of()).isEmpty();
    }
    synchronized AddonLifecycle lifecycle(Id addonId) { return lifecycles.getOrDefault(addonId, AddonLifecycle.NONE); }
}
