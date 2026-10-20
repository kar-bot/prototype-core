package dev.prototype.core.control;

import static org.junit.jupiter.api.Assertions.*;

import dev.prototype.core.api.ClassControlSlot;
import dev.prototype.core.api.Id;
import dev.prototype.core.api.minecraft.ClassControlHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassControlRegistrationTest {
    private static final Id CLASS = Id.parse("test:class");
    private static final ClassControlHandler HANDLER = player -> {};

    @Test void exactlyThreePublicSlots() {
        assertArrayEquals(new ClassControlSlot[]{ClassControlSlot.ABILITY_1, ClassControlSlot.ABILITY_2,
                ClassControlSlot.STATUS}, ClassControlSlot.values());
    }

    @Test void partialAndEmptyBindingsAreValidAndCopied() {
        var dispatcher = new ClassControlDispatcher();
        var slots = new HashMap<ClassControlSlot, ClassControlHandler>();
        slots.put(ClassControlSlot.ABILITY_1, HANDLER);
        var input = new HashMap<Id, Map<ClassControlSlot, ClassControlHandler>>();
        input.put(CLASS, slots);
        var prepared = dispatcher.prepare(Set.of(CLASS), input);
        input.clear(); slots.clear();
        assertEquals(Map.of(ClassControlSlot.ABILITY_1, HANDLER), prepared.get(CLASS));
        assertThrows(UnsupportedOperationException.class, () -> prepared.get(CLASS).clear());
        assertThrows(UnsupportedOperationException.class, prepared::clear);
        assertDoesNotThrow(() -> dispatcher.register(Set.of(CLASS), Map.of()));
    }

    @Test void foreignClassRejectedBeforeMutation() {
        var dispatcher = new ClassControlDispatcher();
        assertThrows(IllegalArgumentException.class, () -> dispatcher.register(Set.of(CLASS),
                Map.of(Id.parse("secret:foreign"), Map.of(ClassControlSlot.STATUS, HANDLER))));
        assertDoesNotThrow(() -> dispatcher.register(Set.of(CLASS), Map.of(CLASS, Map.of(ClassControlSlot.STATUS, HANDLER))));
    }

    @Test void duplicateAndFrozenBindingsCannotBeReplaced() {
        var dispatcher = new ClassControlDispatcher();
        dispatcher.register(Set.of(CLASS), Map.of(CLASS, Map.of(ClassControlSlot.STATUS, HANDLER)));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.register(Set.of(CLASS), Map.of(CLASS, Map.of())));
        dispatcher.freeze();
        assertThrows(IllegalStateException.class, () -> dispatcher.register(Set.of(CLASS), Map.of()));
    }

    @Test void nullSlotsAndHandlersAreRejected() {
        var dispatcher = new ClassControlDispatcher();
        var slots = new HashMap<ClassControlSlot, ClassControlHandler>();
        slots.put(ClassControlSlot.STATUS, null);
        assertThrows(NullPointerException.class, () -> dispatcher.register(Set.of(CLASS), Map.of(CLASS, slots)));
        slots.clear(); slots.put(null, HANDLER);
        assertThrows(NullPointerException.class, () -> dispatcher.register(Set.of(CLASS), Map.of(CLASS, slots)));
    }
}
