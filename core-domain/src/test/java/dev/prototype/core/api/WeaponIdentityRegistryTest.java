package dev.prototype.core.api;

import static org.junit.jupiter.api.Assertions.*;

import dev.prototype.core.weapon.WeaponIdentity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeaponIdentityRegistryTest {
    private static final Id A = Id.parse("test:a"), B = Id.parse("test:b");
    private static final Id ITEM = Id.parse("test:sword"), MARKER = Id.parse("test:marker"), STEALTH = Id.parse("prototype:stealth");
    private static AddonDefinition addon(Id id) {
        return new AddonDefinition(id, "Test", "1", null, List.of(new ClassDefinition(id, "Test")));
    }
    private static WeaponIdentity form(Object marker) {
        return new WeaponIdentity(ITEM, Map.of(MARKER, Optional.ofNullable(marker)));
    }
    private static void register(Registry r, Id id, WeaponIdentity... forms) {
        r.register(addon(id), AddonLifecycle.NONE, Map.of(id, List.of(forms)));
    }
    private static Optional<Id> owner(Registry r, Map<Id, Object> components) {
        return r.classWeaponOwner(ITEM, components::get);
    }

    @Test void wholeItemRegistrationIgnoresState() {
        var r = new Registry(); register(r, A, new WeaponIdentity(ITEM, Map.of()));
        assertEquals(Optional.of(A), owner(r, Map.of()));
        assertEquals(Optional.of(A), owner(r, Map.of(STEALTH, true)));
    }
    @Test void componentFormsPartitionOneItemWithoutRegistrationOrderDependence() {
        for (boolean reverse : List.of(false, true)) {
            var r = new Registry();
            if (reverse) { register(r, B, form("b")); register(r, A, form("a")); }
            else { register(r, A, form("a")); register(r, B, form("b")); }
            assertEquals(Optional.of(A), owner(r, Map.of(MARKER, "a", STEALTH, true)));
            assertEquals(Optional.of(B), owner(r, Map.of(MARKER, "b", STEALTH, false)));
            assertTrue(owner(r, Map.of()).isEmpty());
        }
    }
    @Test void overlappingBroadAndNarrowFormsFailInEitherOrder() {
        for (boolean reverse : List.of(false, true)) {
            var r = new Registry();
            var broad = new WeaponIdentity(ITEM, Map.of());
            register(r, A, reverse ? form("a") : broad);
            assertThrows(IllegalArgumentException.class, () -> register(r, B, reverse ? broad : form("a")));
            assertTrue(r.addon(B).isEmpty());
        }
    }
    @Test void unrelatedConditionsStillOverlapAndCannotClaimOneStack() {
        var r = new Registry(); register(r, A, form("a"));
        assertThrows(IllegalArgumentException.class, () -> register(r, B,
                new WeaponIdentity(ITEM, Map.of(STEALTH, Optional.of(true)))));
    }
    @Test void duplicateFormsWithinOneRegistrationFailAtomically() {
        var r = new Registry();
        assertThrows(IllegalArgumentException.class, () -> register(r, A, form("a"), form("a")));
        assertTrue(r.addon(A).isEmpty()); assertFalse(r.hasClassWeapons(A));
        register(r, A, form("a"));
    }
    @Test void conflictingBatchCannotPartiallyRegisterAnotherClass() {
        var r = new Registry(); register(r, A, form("a"));
        var c = Id.parse("test:c");
        var definition = new AddonDefinition(B, "B", "1", null,
                List.of(new ClassDefinition(B, "B"), new ClassDefinition(c, "C")));
        assertThrows(IllegalArgumentException.class, () -> r.register(definition, AddonLifecycle.NONE,
                Map.of(B, List.of(form("b")), c, List.of(form("a")))));
        assertTrue(r.addon(B).isEmpty()); assertTrue(r.classDefinition(c).isEmpty());
        assertTrue(owner(r, Map.of(MARKER, "b")).isEmpty());
    }
    @Test void foreignClassRegistrationIsRejected() {
        var r = new Registry();
        assertThrows(IllegalArgumentException.class, () -> r.register(addon(A), AddonLifecycle.NONE, Map.of(B, List.of(form("b")))));
        assertTrue(r.classes().isEmpty());
    }
    @Test void freezeRejectsEvenNewWeaponForms() {
        var r = new Registry(); r.freeze();
        assertThrows(IllegalStateException.class, () -> register(r, A, form("a")));
    }
    @Test void listsMapsAndFormsAreDefensivelyCopied() {
        var constraints = new HashMap<Id, Optional<Object>>(); constraints.put(MARKER, Optional.of("a"));
        var f = new WeaponIdentity(ITEM, constraints); constraints.clear();
        var forms = new ArrayList<>(List.of(f)); var map = new HashMap<Id, List<WeaponIdentity>>(); map.put(A, forms);
        var r = new Registry(); r.register(addon(A), AddonLifecycle.NONE, map); forms.clear(); map.clear();
        assertEquals(Optional.of(A), owner(r, Map.of(MARKER, "a")));
        assertThrows(UnsupportedOperationException.class, () -> f.components().clear());
    }
    @Test void absentAndExplicitFalseAreDifferentConstraints() {
        var r = new Registry(); register(r, A, form(null)); register(r, B, form(false));
        assertEquals(Optional.of(A), owner(r, Map.of()));
        assertEquals(Optional.of(B), owner(r, Map.of(MARKER, false)));
        assertTrue(owner(r, Map.of(MARKER, true)).isEmpty());
    }
    @Test void authorizationAndAddonFlagsDoNotAlterIdentity() {
        var r = new Registry(); register(r, A, form("a"));
        var service = new CoreService(r); var player = UUID.randomUUID();
        assertFalse(service.isAuthorized(player, A));
        service.setClass(player, A); service.setClassBanned(player, true); service.setAddonEnabled(A, false);
        assertEquals(Optional.of(A), owner(r, Map.of(MARKER, "a")));
        service.close(); assertEquals(Optional.of(A), owner(r, Map.of(MARKER, "a")));
    }
    @Test void oldRegistrationOverloadsDeclareNoOwnership() {
        var r = new Registry(); r.register(addon(A));
        assertFalse(r.hasClassWeapons(A)); assertFalse(r.hasClassWeapons(null));
        assertTrue(owner(r, Map.of()).isEmpty()); assertTrue(r.classWeaponOwner(null, null).isEmpty());
    }
    @Test void distinctItemsNeverConflict() {
        var r = new Registry(); register(r, A, form("a"));
        register(r, B, new WeaponIdentity(Id.parse("other:sword"), Map.of()));
        assertEquals(Optional.of(B), r.classWeaponOwner(Id.parse("other:sword"), id -> null));
    }
    @Test void unknownItemsResolveNoOwner() {
        var r = new Registry(); register(r, A, form("a"));
        assertTrue(r.classWeaponOwner(Id.parse("minecraft:netherite_sword"), id -> null).isEmpty());
    }
    @Test void nullConstraintsFormsAndEntriesFailBeforeMutation() {
        assertThrows(NullPointerException.class, () -> new WeaponIdentity(ITEM, null));
        var r = new Registry(); var map = new HashMap<Id, List<WeaponIdentity>>(); map.put(A, null);
        assertThrows(NullPointerException.class, () -> r.register(addon(A), AddonLifecycle.NONE, map));
        assertTrue(r.classes().isEmpty());
    }
}
