package com.dreadedzombie.lensbridge.server;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client: may this player use free cameras / let an AI act for them here. Optional channel. */
public record PolicyPayload(boolean cameras, boolean control) implements CustomPacketPayload {
    public static final Type<PolicyPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("lensbridge", "policy"));
    public static final StreamCodec<ByteBuf, PolicyPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, PolicyPayload::cameras, ByteBufCodecs.BOOL, PolicyPayload::control, PolicyPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
