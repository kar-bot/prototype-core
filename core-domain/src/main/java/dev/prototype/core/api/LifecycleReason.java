package dev.prototype.core.api;

/** Hooks concern transient cleanup; they never require deleting addon progression. */
public enum LifecycleReason {
    PLAYER_JOIN, PLAYER_LEAVE, CLASS_ASSIGNED, CLASS_SWITCHED, AUTHORIZATION_REMOVED,
    HYBRID_DISABLED, CLASS_BANNED, CLASS_UNBANNED, ADDON_DISABLED, ADDON_ENABLED, SERVER_STOP
}
