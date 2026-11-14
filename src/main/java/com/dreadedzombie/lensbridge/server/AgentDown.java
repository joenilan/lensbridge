package com.dreadedzombie.lensbridge.server;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client, the server-tools relay (see AgentRelay). {@code kind}: "hello" (what this player's agent may use
 * here, sent on join and whenever it changes), "result" (the answer to call {@code id}), "event" (a live server event).
 * The body is JSON.
 */
public record AgentDown(String kind, int id, String json) implements CustomPacketPayload {
    /** Vanilla caps a server -> client custom payload at 1 MiB; leave room for the rest of the packet. */
    public static final int MAX_BYTES = 1_000_000;
    public static final Type<AgentDown> TYPE = new Type<>(Identifier.fromNamespaceAndPath("lensbridge", "agent_down"));
    public static final StreamCodec<ByteBuf, AgentDown> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(16), AgentDown::kind, ByteBufCodecs.VAR_INT, AgentDown::id,
            ByteBufCodecs.stringUtf8(MAX_BYTES), AgentDown::json, AgentDown::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
