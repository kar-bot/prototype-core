package dev.prototype.core.weapon;

import dev.prototype.core.api.Id;
import dev.prototype.core.api.Registry;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Internal ItemStack adapter; identity rules live in the existing addon registry. */
public final class WeaponStacks {
    private WeaponStacks() {}

    public static Optional<Id> owner(Registry registry, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        var item = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (item == null || BuiltInRegistries.ITEM.getValue(item) != stack.getItem()) return Optional.empty();
        return registry.classWeaponOwner(Id.parse(item.toString()), componentId -> {
            var component = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(Identifier.parse(componentId.toString()));
            return component == null ? null : stack.get(component);
        });
    }
}
