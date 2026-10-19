package dev.prototype.core.item;

import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;

/** Persistent and network-synchronized neutral state shared by compatible swords. */
public final class CoreComponents {
    public static final DataComponentType<Boolean> STEALTH = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            Identifier.fromNamespaceAndPath("prototype", "stealth"),
            DataComponentType.<Boolean>builder().persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL).build());

    private CoreComponents() {}
    public static void init() { /* Triggers static registration during common initialization. */ }
}
