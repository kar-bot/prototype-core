package dev.prototype.core.client;

import dev.prototype.core.api.ClassControlSlot;
import dev.prototype.core.network.ControlPayload;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;

/** Three permanent, rebindable neutral keys; never caches any addon/class/handler identity. */
final class ClassControlKeys {
    private record Binding(KeyMapping key, ClassControlSlot slot) {}

    static void init() {
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("prototype", "class_controls"));
        var bindings = List.of(
            new Binding(KeyMappingHelper.registerKeyMapping(new KeyMapping("key.prototype.ability_1", InputConstants.KEY_R, category)), ClassControlSlot.ABILITY_1),
            new Binding(KeyMappingHelper.registerKeyMapping(new KeyMapping("key.prototype.ability_2", InputConstants.KEY_G, category)), ClassControlSlot.ABILITY_2),
            new Binding(KeyMappingHelper.registerKeyMapping(new KeyMapping("key.prototype.status", InputConstants.KEY_K, category)), ClassControlSlot.STATUS));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            for (var binding : bindings) {
                while (binding.key().consumeClick()) {
                    if (client.player != null && client.gui.screen() == null
                            && ClientPlayNetworking.canSend(ControlPayload.TYPE)) {
                        ClientPlayNetworking.send(new ControlPayload(binding.slot()));
                    }
                }
            }
        });
    }
}
