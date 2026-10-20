package dev.prototype.core.api.minecraft;

import net.minecraft.server.level.ServerPlayer;

/**
 * Invoked synchronously on the server thread for the authenticated, currently authorized player.
 * Registered for one class/slot during addon initialization. Validate addon-specific item, cooldown,
 * and gameplay conditions here; recheck Prototype authorization in delayed work. A STATUS handler
 * may send the addon's own private payload to this player. Never reference client screens here.
 */
@FunctionalInterface
public interface ClassControlHandler {
    void handle(ServerPlayer player);
}
