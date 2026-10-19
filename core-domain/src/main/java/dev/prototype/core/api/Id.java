package dev.prototype.core.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable Minecraft identifier. Parsing requires an explicit namespace. */
public record Id(String namespace, String path) implements Comparable<Id> {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9/._-]+");

    public Id {
        if (namespace == null || path == null || !NAMESPACE.matcher(namespace).matches()
                || !PATH.matcher(path).matches() || namespace.length() + path.length() > 255) {
            throw new IllegalArgumentException("Invalid namespaced identifier");
        }
    }

    public static Id parse(String value) {
        if (value == null) throw new IllegalArgumentException("Invalid namespaced identifier");
        int colon = value.indexOf(':');
        if (colon < 1 || colon != value.lastIndexOf(':')) {
            throw new IllegalArgumentException("Expected namespace:path");
        }
        return new Id(value.substring(0, colon), value.substring(colon + 1));
    }

    @Override public String toString() { return namespace + ":" + path; }
    @Override public int compareTo(Id other) { return toString().compareTo(Objects.requireNonNull(other).toString()); }
}
