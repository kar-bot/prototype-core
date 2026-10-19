package dev.prototype.core.api;

import java.util.Objects;

public record PlayerClassContext(java.util.UUID playerId, Id classId, LifecycleReason reason) {
    public PlayerClassContext {
        Objects.requireNonNull(playerId); Objects.requireNonNull(classId); Objects.requireNonNull(reason);
    }
}
