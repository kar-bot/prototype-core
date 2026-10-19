package dev.prototype.core.item;

import dev.prototype.core.api.minecraft.PrototypeApi;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Unit;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ToolMaterial;

public final class PrototypeItems {
    private static final ResourceKey<Item> SWORD_KEY = ResourceKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("prototype", "prototype_sword"));

    /** Vanilla wooden sword damage, attack speed, tool and enchantability properties. */
    public static final Item PROTOTYPE_SWORD = Registry.register(BuiltInRegistries.ITEM, SWORD_KEY,
            new Item(new Item.Properties().setId(SWORD_KEY)
                    .sword(ToolMaterial.WOOD, 3.0F, -2.4F)
                    .component(DataComponents.UNBREAKABLE, Unit.INSTANCE)
                    .component(CoreComponents.STEALTH, false)));

    private PrototypeItems() {}
    public static void init() { PrototypeApi.registerConvertible(PROTOTYPE_SWORD); }
}
