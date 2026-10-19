package dev.prototype.core.api;

import java.util.UUID;

/** Typed administrative intents; construction never grants permission. */
public sealed interface AdminRequest {
    record SetClass(UUID playerId, Id classId) implements AdminRequest {}
    record AddClass(UUID playerId, Id classId) implements AdminRequest {}
    record RemoveClass(UUID playerId, Id classId) implements AdminRequest {}
    record ClearClasses(UUID playerId) implements AdminRequest {}
    record SetHybrid(UUID playerId, boolean enabled) implements AdminRequest {}
    record SetClassBanned(UUID playerId, boolean banned) implements AdminRequest {}
    record SetAddonEnabled(Id addonId, boolean enabled) implements AdminRequest {}
}
