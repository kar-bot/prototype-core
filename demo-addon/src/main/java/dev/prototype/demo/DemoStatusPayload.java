package dev.prototype.demo;

import dev.prototype.core.api.minecraft.StealthState;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Addon-owned status schema. Core never reads these fields. */
public record DemoStatusPayload(boolean open, boolean stealthed) implements CustomPacketPayload {
    public static final Type<DemoStatusPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("prototype_demo", "status"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DemoStatusPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> { buffer.writeBoolean(payload.open); buffer.writeBoolean(payload.stealthed); },
            buffer -> new DemoStatusPayload(buffer.readBoolean(), buffer.readBoolean()));

    public static void open(ServerPlayer player) {
        send(player, new DemoStatusPayload(true, StealthState.isStealthed(player.getMainHandItem())));
    }

    public static void close(ServerPlayer player) { send(player, new DemoStatusPayload(false, false)); }

    private static void send(ServerPlayer player, DemoStatusPayload payload) {
        if (ServerPlayNetworking.canSend(player, TYPE)) ServerPlayNetworking.send(player, payload);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
