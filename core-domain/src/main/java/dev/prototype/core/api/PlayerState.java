package dev.prototype.core.api;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Core-owned authorization only. Unknown historical identifiers remain valid stored data. */
public record PlayerState(boolean classBanned, boolean hybridEnabled, Set<Id> allowedClasses, Id activeClass) {
    public PlayerState {
        if (allowedClasses == null || allowedClasses.stream().anyMatch(id -> id == null)) {
            throw new IllegalArgumentException("Allowed classes are required");
        }
        var normalized = new TreeSet<>(allowedClasses);
        if (activeClass != null && !normalized.contains(activeClass)) {
            throw new IllegalArgumentException("Active class must be authorized");
        }
        if (!hybridEnabled && normalized.size() > 1) {
            Id retained = activeClass == null ? normalized.first() : activeClass;
            normalized.clear(); normalized.add(retained);
        }
        allowedClasses = Collections.unmodifiableSet(normalized);
    }
    public static PlayerState empty() { return new PlayerState(false, false, Set.of(), null); }
}
