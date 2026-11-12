package com.dreadedzombie.lensbridge.server;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> server, the server-tools relay: call {@code id}, body {@code {"tool": ..., "args": {...}}}. */
public record AgentUp(int id, String json) implements CustomPacketPayload {
    /** Vanilla caps a client -> server custom payload at 32 KiB. */
    public static final int MAX_CHARS = 30_000;
    public static final Type<AgentUp> TYPE = new Type<>(Identifier.fromNamespaceAndPath("lensbridge", "agent_up"));
    public static final StreamCodec<ByteBuf, AgentUp> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AgentUp::id, ByteBufCodecs.stringUtf8(MAX_CHARS), AgentUp::json, AgentUp::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
