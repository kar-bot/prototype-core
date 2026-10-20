package dev.prototype.core.weapon;

import dev.prototype.core.api.Id;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Internal, Minecraft-independent declarative form stored by the existing addon registry. */
public record WeaponIdentity(Id item, Map<Id, Optional<Object>> components) {
    public WeaponIdentity {
        Objects.requireNonNull(item, "item");
        components = Map.copyOf(components);
    }

    /** Exact immutable component values (or absence); no addon predicate can hide an overlap. */
    public boolean overlaps(WeaponIdentity other) {
        if (!item.equals(other.item)) return false;
        for (var entry : components.entrySet()) {
            var constraint = other.components.get(entry.getKey());
            if (constraint != null && !constraint.equals(entry.getValue())) return false;
        }
        return true;
    }

    public boolean matches(Function<Id, Object> componentLookup) {
        return components.entrySet().stream().allMatch(entry ->
                entry.getValue().equals(Optional.ofNullable(componentLookup.apply(entry.getKey()))));
    }
}
