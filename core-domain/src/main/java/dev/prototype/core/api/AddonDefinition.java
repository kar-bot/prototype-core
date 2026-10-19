package dev.prototype.core.api;

import java.util.List;
import java.util.TreeSet;

/** Immutable registration metadata. Thumbnail resources should be 64 x 64 PNG images. */
public record AddonDefinition(Id id, String displayName, String version, String thumbnail,
                              List<ClassDefinition> classes) {
    public static final String FALLBACK_THUMBNAIL = "prototype:textures/gui/addon_fallback.png";

    public AddonDefinition {
        if (id == null) throw new IllegalArgumentException("Addon identifier is required");
        displayName = requireText(displayName, "Addon display name", 128);
        version = requireText(version, "Addon version", 64);
        if (thumbnail == null || thumbnail.isBlank()) thumbnail = FALLBACK_THUMBNAIL;
        Id.parse(thumbnail);
        if (classes == null || classes.isEmpty() || classes.size() > 256 || classes.stream().anyMatch(c -> c == null)) {
            throw new IllegalArgumentException("Addon must register between 1 and 256 classes");
        }
        classes = classes.stream().sorted(java.util.Comparator.comparing(ClassDefinition::id)).toList();
        var seen = new TreeSet<Id>();
        for (var definition : classes) {
            if (!seen.add(definition.id())) throw new IllegalArgumentException("Duplicate class identifier: " + definition.id());
        }
    }

    static String requireText(String text, String field, int maximum) {
        if (text == null || text.isBlank() || text.length() > maximum || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is missing or invalid");
        }
        return text;
    }
}
