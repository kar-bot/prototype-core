package dev.prototype.core.api.minecraft;

import dev.prototype.core.item.CoreComponents;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;

/** A neutral physical-item flag. Each addon defines its own stealth behavior. */
public final class StealthState {
    /** Public component handle for addon Item.Properties and direct component access. */
    public static final DataComponentType<Boolean> COMPONENT = CoreComponents.STEALTH;
    private StealthState() {}

    public static boolean isStealthed(ItemStack stack) {
        return stack.getOrDefault(CoreComponents.STEALTH, false);
    }

    public static void setStealthed(ItemStack stack, boolean stealthed) {
        stack.set(CoreComponents.STEALTH, stealthed);
    }
}
