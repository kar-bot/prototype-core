package dev.prototype.core.api;

/** Called on the authoritative server thread after the new state has committed. */
public interface AddonLifecycle {
    AddonLifecycle NONE = new AddonLifecycle() {};
    default void onAddonEnabled(Id addonId) {}
    default void onAddonDisabled(Id addonId) {}
    default void onClassActivated(PlayerClassContext context) {}
    default void onClassDeactivated(PlayerClassContext context) {}
}
