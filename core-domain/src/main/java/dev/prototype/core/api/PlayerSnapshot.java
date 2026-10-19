package dev.prototype.core.api;

import java.util.List;
import java.util.UUID;

/** Server-filtered client view, containing only the caller's registered authorized classes. */
public record PlayerSnapshot(UUID playerId, long revision, boolean classBanned, boolean hybridEnabled,
                             Id activeClass, List<KnownClass> classes) {
    public PlayerSnapshot { classes = List.copyOf(classes); }
    public record KnownClass(Id id, String displayName, Id addonId, String addonDisplayName,
                             String thumbnail, boolean enabled, boolean active) {}
}
