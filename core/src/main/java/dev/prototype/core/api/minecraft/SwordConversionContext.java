package dev.prototype.core.api.minecraft;

import dev.prototype.core.api.Id;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Read-only conversion inputs. The player reference is for queries, never side effects. */
public final class SwordConversionContext {
    private final ServerPlayer player;
    private final Id targetClass;
    private final ItemStack source;

    public SwordConversionContext(ServerPlayer player, Id targetClass, ItemStack source) {
        this.player = Objects.requireNonNull(player, "player");
        this.targetClass = Objects.requireNonNull(targetClass, "targetClass");
        this.source = Objects.requireNonNull(source, "source").copy();
    }

    public ServerPlayer player() { return player; }
    public Id targetClass() { return targetClass; }
    /** Each invocation returns a new stack, never the live held sword. */
    public ItemStack source() { return source.copy(); }
}
