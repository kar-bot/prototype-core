package dev.prototype.core.network;

import dev.prototype.core.security.WireRequest;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Internal bounded C2S request. Its fields are untrusted until validated on the server. */
public record ActionPayload(String json) implements CustomPacketPayload {
    public static final Type<ActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("prototype", "action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeUtf(payload.json, WireRequest.MAX_LENGTH),
            buffer -> new ActionPayload(buffer.readUtf(WireRequest.MAX_LENGTH)));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
