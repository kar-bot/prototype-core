package dev.prototype.core.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Registration is atomic and deterministic; freeze before starting a server. */
public final class Registry {
    private final Map<Id, AddonDefinition> addons = new TreeMap<>();
    private final Map<Id, ClassDefinition> classes = new TreeMap<>();
    private final Map<Id, Id> classOwners = new TreeMap<>();
    private final Map<Id, AddonLifecycle> lifecycles = new TreeMap<>();
    private boolean frozen;

    public synchronized void register(AddonDefinition definition) { register(definition, AddonLifecycle.NONE); }
    public synchronized void register(AddonDefinition definition, AddonLifecycle lifecycle) {
        if (frozen) throw new IllegalStateException("Prototype registry is frozen");
        if (definition == null || lifecycle == null) throw new IllegalArgumentException("Registration and lifecycle are required");
        if (addons.containsKey(definition.id())) throw new IllegalArgumentException("Duplicate addon identifier: " + definition.id());
        for (var clazz : definition.classes()) {
            if (classes.containsKey(clazz.id())) throw new IllegalArgumentException("Duplicate class identifier: " + clazz.id());
        }
        addons.put(definition.id(), definition);
        lifecycles.put(definition.id(), lifecycle);
        for (var clazz : definition.classes()) {
            classes.put(clazz.id(), clazz);
            classOwners.put(clazz.id(), definition.id());
        }
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
    synchronized AddonLifecycle lifecycle(Id addonId) { return lifecycles.getOrDefault(addonId, AddonLifecycle.NONE); }
}
