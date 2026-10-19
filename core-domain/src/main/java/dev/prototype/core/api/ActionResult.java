package dev.prototype.core.api;

/** Ordinary access failures deliberately carry one indistinguishable message. */
public record ActionResult(Status status, String message) {
    public enum Status { APPLIED, NO_CHANGE, ACCESS_DENIED, INVALID_REQUEST, INVALID_STATE, FORBIDDEN, CONFLICT }
    public static final ActionResult APPLIED = new ActionResult(Status.APPLIED, "Updated.");
    public static final ActionResult NO_CHANGE = new ActionResult(Status.NO_CHANGE, "Already up to date.");
    public static final ActionResult ACCESS_DENIED = new ActionResult(Status.ACCESS_DENIED, "You do not have access to that class.");
    public static final ActionResult FORBIDDEN = new ActionResult(Status.FORBIDDEN, "Administrator permission is required.");
    public static final ActionResult CONFLICT = new ActionResult(Status.CONFLICT, "State changed. Refresh and try again.");
    public static ActionResult invalidRequest(String message) { return new ActionResult(Status.INVALID_REQUEST, message); }
    public boolean success() { return status == Status.APPLIED || status == Status.NO_CHANGE; }
}
