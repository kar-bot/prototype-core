package dev.prototype.core.api;

/** Public presentation metadata; addon gameplay and progression remain addon owned. */
public record ClassDefinition(Id id, String displayName) {
    public ClassDefinition {
        if (id == null) throw new IllegalArgumentException("Class identifier is required");
        displayName = AddonDefinition.requireText(displayName, "Class display name", 128);
    }
}
