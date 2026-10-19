package dev.prototype.demo;

import dev.prototype.core.api.minecraft.PrototypeApi;
import dev.prototype.core.api.minecraft.StealthState;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.level.Level;

public final class DemoItems {
    private static final ResourceKey<Item> SWORD_KEY = ResourceKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("prototype_demo", "demo_sword"));
    public static final Item DEMO_SWORD = Registry.register(BuiltInRegistries.ITEM, SWORD_KEY,
            new Item(new Item.Properties().setId(SWORD_KEY).sword(ToolMaterial.WOOD, 3.0F, -2.4F)
                    .component(DataComponents.UNBREAKABLE, Unit.INSTANCE)
                    .component(StealthState.COMPONENT, false)) {
                @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
                    if (!(player instanceof ServerPlayer serverPlayer)
                            || !PrototypeApi.isAuthorized(serverPlayer, DemoAddon.CLASS_ID)) return InteractionResult.PASS;
                    ItemStack stack = player.getItemInHand(hand);
                    if (player.isShiftKeyDown()) {
                        StealthState.setStealthed(stack, !StealthState.isStealthed(stack));
                        player.getInventory().setChanged();
                    }
                    serverPlayer.sendSystemMessage(Component.literal("Demo active · Core stealth flag: "
                            + (StealthState.isStealthed(stack) ? "on" : "off")), true);
                    return InteractionResult.SUCCESS;
                }
            });

    private DemoItems() {}
    public static void init() { /* Triggers item registration. */ }
}
