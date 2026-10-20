package dev.prototype.core.network;

import dev.prototype.core.api.ClassControlSlot;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Exactly one neutral slot byte. No class, addon, ability name, UUID, or gameplay fields. */
public record ControlPayload(int slotId) implements CustomPacketPayload {
    public static final Type<ControlPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("prototype", "control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ControlPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeByte(payload.slotId), buffer -> {
                int value = buffer.readUnsignedByte();
                if (buffer.isReadable()) throw new DecoderException("Invalid Prototype control request");
                return new ControlPayload(value);
            });

    public ControlPayload(ClassControlSlot slot) {
        this(switch (slot) { case ABILITY_1 -> 0; case ABILITY_2 -> 1; case STATUS -> 2; });
    }

    public ClassControlSlot slot() {
        return switch (slotId) { case 0 -> ClassControlSlot.ABILITY_1; case 1 -> ClassControlSlot.ABILITY_2;
            case 2 -> ClassControlSlot.STATUS; default -> null; };
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
