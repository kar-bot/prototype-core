package dev.prototype.core.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegistryTest {
    static final Id A = Id.parse("demo:a");
    static final Id B = Id.parse("demo:b");
    static AddonDefinition addon(String id, Id... classes) {
        return new AddonDefinition(Id.parse(id), "Display", "1.0", "demo:textures/gui/icon.png",
                java.util.Arrays.stream(classes).map(c -> new ClassDefinition(c, "Class " + c.path())).toList());
    }

    @Test void identifiersHaveStableRoundTripAndOrdering() {
        assertEquals(new Id("demo", "a/b.c-d_e"), Id.parse("demo:a/b.c-d_e"));
        assertEquals("demo:a", A.toString());
        assertTrue(A.compareTo(B) < 0);
    }
    @ParameterizedTest @NullSource @ValueSource(strings = {"", "demo", ":a", "demo:", "demo:a:b", "Demo:a", "demo:A", "demo:a b", "demo:a\n"})
    void invalidIdentifiersFail(String id) { assertThrows(IllegalArgumentException.class, () -> Id.parse(id)); }
    @Test void addonOwnsMultipleClasses() {
        var registry = new Registry(); var addon = addon("demo:consumer", B, A); registry.register(addon);
        assertEquals(List.of(A, B), registry.classes().stream().map(ClassDefinition::id).toList());
        assertEquals(addon, registry.addonForClass(A).orElseThrow());
        assertEquals(addon, registry.addonForClass(B).orElseThrow());
    }
    @Test void duplicateAddonIsClearAndRegistrationIsAtomic() {
        var registry = new Registry(); registry.register(addon("demo:consumer", A));
        var error = assertThrows(IllegalArgumentException.class, () -> registry.register(addon("demo:consumer", B)));
        assertEquals("Duplicate addon identifier: demo:consumer", error.getMessage());
        assertTrue(registry.classDefinition(B).isEmpty());
    }
    @Test void duplicateClassIsClearAndRegistrationIsAtomic() {
        var registry = new Registry(); registry.register(addon("demo:first", B));
        var error = assertThrows(IllegalArgumentException.class, () -> registry.register(addon("demo:second", A, B)));
        assertEquals("Duplicate class identifier: demo:b", error.getMessage());
        assertTrue(registry.addon(Id.parse("demo:second")).isEmpty());
        assertTrue(registry.classDefinition(A).isEmpty());
    }
    @Test void duplicateClassWithinAddonFailsBeforeRegistryMutation() {
        assertThrows(IllegalArgumentException.class, () -> addon("demo:consumer", A, A));
    }
    @Test void registrationListsCannotBeChangedByCaller() {
        var classes = new ArrayList<>(List.of(new ClassDefinition(A, "A")));
        var definition = new AddonDefinition(Id.parse("demo:consumer"), "Consumer", "1", null, classes);
        classes.clear();
        assertEquals(1, definition.classes().size());
        assertThrows(UnsupportedOperationException.class, () -> definition.classes().clear());
    }
    @Test void missingThumbnailUsesCoreFallback() {
        assertEquals(AddonDefinition.FALLBACK_THUMBNAIL,
                new AddonDefinition(Id.parse("demo:consumer"), "Consumer", "1", " ", List.of(new ClassDefinition(A, "A"))).thumbnail());
    }
    @Test void missingAndInvalidMetadataFails() {
        assertThrows(IllegalArgumentException.class, () -> new ClassDefinition(A, ""));
        assertThrows(IllegalArgumentException.class, () -> new ClassDefinition(null, "A"));
        assertThrows(IllegalArgumentException.class, () -> new AddonDefinition(null, "A", "1", null, List.of(new ClassDefinition(A, "A"))));
        assertThrows(IllegalArgumentException.class, () -> new AddonDefinition(A, "A", "", null, List.of(new ClassDefinition(A, "A"))));
        assertThrows(IllegalArgumentException.class, () -> new AddonDefinition(A, "A", "1", "invalid", List.of(new ClassDefinition(A, "A"))));
        assertThrows(IllegalArgumentException.class, () -> new AddonDefinition(A, "A", "1", null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ClassDefinition(A, "line\nbreak"));
    }
    @Test void unknownAndNullLookupsRemainSafe() {
        var registry = new Registry();
        assertTrue(registry.addon(null).isEmpty());
        assertTrue(registry.classDefinition(null).isEmpty());
        assertTrue(registry.addonForClass(null).isEmpty());
        assertTrue(registry.addonForClass(A).isEmpty());
    }
    @Test void freezePreventsLateRegistrations() {
        var registry = new Registry(); registry.freeze();
        assertTrue(registry.isFrozen());
        assertThrows(IllegalStateException.class, () -> registry.register(addon("demo:consumer", A)));
    }
}
