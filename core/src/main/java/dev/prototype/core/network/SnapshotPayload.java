package dev.prototype.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Internal S2C view. Ordinary-player registry metadata is filtered before serialization. */
public record SnapshotPayload(String json) implements CustomPacketPayload {
    public static final int MAX_LENGTH = 262144;
    public static final Type<SnapshotPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("prototype", "snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeUtf(payload.json, MAX_LENGTH),
            buffer -> new SnapshotPayload(buffer.readUtf(MAX_LENGTH)));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
