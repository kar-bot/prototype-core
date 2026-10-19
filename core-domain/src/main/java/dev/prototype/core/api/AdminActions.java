package dev.prototype.core.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Atomic permission and revision guard for server-side integrations. The permission argument
 * must be computed from the authenticated command source, never from client request contents.
 */
public final class AdminActions {
    private final CoreService service;
    public AdminActions(CoreService service) { this.service = Objects.requireNonNull(service); }

    public ActionResult apply(boolean serverValidatedAdmin, long expectedRevision, AdminRequest request) {
        synchronized (service) {
            if (!serverValidatedAdmin) return ActionResult.FORBIDDEN;
            if (expectedRevision != service.revision()) return ActionResult.CONFLICT;
            if (request == null) return ActionResult.invalidRequest("Invalid administrative request.");
            UUID player = switch (request) {
                case AdminRequest.SetClass value -> value.playerId();
                case AdminRequest.AddClass value -> value.playerId();
                case AdminRequest.RemoveClass value -> value.playerId();
                case AdminRequest.ClearClasses value -> value.playerId();
                case AdminRequest.SetHybrid value -> value.playerId();
                case AdminRequest.SetClassBanned value -> value.playerId();
                case AdminRequest.SetAddonEnabled ignored -> null;
            };
            if (player == null && !(request instanceof AdminRequest.SetAddonEnabled)) {
                return ActionResult.invalidRequest("Invalid player identifier.");
            }
            return switch (request) {
                case AdminRequest.SetClass value -> service.setClass(value.playerId(), value.classId());
                case AdminRequest.AddClass value -> service.addClass(value.playerId(), value.classId());
                case AdminRequest.RemoveClass value -> service.removeClass(value.playerId(), value.classId());
                case AdminRequest.ClearClasses value -> service.clearClasses(value.playerId());
                case AdminRequest.SetHybrid value -> service.setHybrid(value.playerId(), value.enabled());
                case AdminRequest.SetClassBanned value -> service.setClassBanned(value.playerId(), value.banned());
                case AdminRequest.SetAddonEnabled value -> service.setAddonEnabled(value.addonId(), value.enabled());
            };
        }
    }
}
