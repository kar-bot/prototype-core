package dev.prototype.core.api;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Immutable, persistence-independent snapshot. Missing addon flags default to enabled. */
public record CoreState(Map<UUID, PlayerState> players, Map<Id, Boolean> addonEnabled, long revision) {
    public CoreState {
        if (players == null || addonEnabled == null || revision < 0) throw new IllegalArgumentException("Invalid core state");
        if (players.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null)
                || addonEnabled.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null)) {
            throw new IllegalArgumentException("Null core state entry");
        }
        players = Collections.unmodifiableMap(new TreeMap<>(players));
        addonEnabled = Collections.unmodifiableMap(new TreeMap<>(addonEnabled));
    }
    public static CoreState empty() { return new CoreState(Map.of(), Map.of(), 0); }
}
