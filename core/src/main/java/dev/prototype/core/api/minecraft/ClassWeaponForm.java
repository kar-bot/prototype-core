package dev.prototype.core.api.minecraft;

import dev.prototype.core.api.Id;
import dev.prototype.core.weapon.WeaponIdentity;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Immutable semantic item form. Whole-item registration covers every state, including disguises.
 * Exact persistent-component constraints can partition a shared item; appearance is never inferred.
 * Values must obey Minecraft's immutable data-component value contract. No gameplay predicates.
 */
public final class ClassWeaponForm {
    private static final Set<DataComponentType<?>> PRESENTATION = Set.of(DataComponents.CUSTOM_NAME,
            DataComponents.ITEM_NAME, DataComponents.LORE, DataComponents.ITEM_MODEL,
            DataComponents.CUSTOM_MODEL_DATA, DataComponents.DYED_COLOR);
    private final WeaponIdentity identity;

    private ClassWeaponForm(WeaponIdentity identity) { this.identity = identity; }

    /** Claim this registered item's entire semantic identity, independent of any visual state. */
    public static ClassWeaponForm of(Item item) {
        Objects.requireNonNull(item, "item");
        var key = BuiltInRegistries.ITEM.getKey(item);
        if (key == null || item == Items.AIR || BuiltInRegistries.ITEM.getValue(key) != item) {
            throw new IllegalArgumentException("A registered non-air item is required");
        }
        return new ClassWeaponForm(new WeaponIdentity(Id.parse(key.toString()), Map.of()));
    }

    /** Match an exact persistent semantic component value; returns a new form. */
    public <T> ClassWeaponForm with(DataComponentType<T> component, T value) {
        return constrain(component, Optional.of(Objects.requireNonNull(value, "value")));
    }

    /** Match absence of a persistent component. Absent and an explicit false are distinct. */
    public ClassWeaponForm without(DataComponentType<?> component) {
        return constrain(component, Optional.empty());
    }

    private ClassWeaponForm constrain(DataComponentType<?> component, Optional<Object> value) {
        Objects.requireNonNull(component, "component");
        var key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component);
        if (key == null || component.isTransient()) throw new IllegalArgumentException("A registered persistent component is required");
        if (PRESENTATION.contains(component)) throw new IllegalArgumentException("Presentation components cannot establish weapon ownership");
        Id id = Id.parse(key.toString());
        var constraints = new HashMap<>(identity.components());
        if (constraints.putIfAbsent(id, value) != null) throw new IllegalArgumentException("Component already constrained");
        return new ClassWeaponForm(new WeaponIdentity(identity.item(), constraints));
    }

    WeaponIdentity definition() { return identity; }
}
