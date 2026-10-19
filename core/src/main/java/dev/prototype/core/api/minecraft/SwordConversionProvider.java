package dev.prototype.core.api.minecraft;

import java.util.Optional;
import net.minecraft.world.item.ItemStack;

/**
 * A server-thread factory for a clean destination sword. Return empty to decline.
 * Never mutate the player, inventory, or authorization from this callback. The source
 * accessor supplies a copy; arbitrary source component patches must not be copied.
 */
@FunctionalInterface
public interface SwordConversionProvider {
    Optional<ItemStack> convert(SwordConversionContext context);
}
