package com.dreadedzombie.lensbridge;

import com.dreadedzombie.lensbridge.mcp.AgentlinkSupport;
import com.dreadedzombie.lensbridge.server.ServerAgent;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.ArrayDeque;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * {@code /agent <request>}: ask your own AI something from inside the game. Client-side only: the request goes
 * from your game to your AI through Lensbridge and never to the server. The AI picks requests up with the
 * {@code agent_inbox} tool (it waits there while "listening") and answers with {@code agent_say}, which shows in
 * your chat only. Each request carries where you are and what you're looking at, so "what's this?" and "go look at
 * that" make sense to the AI.
 */
public final class AgentInbox {
    private static final int MAX_QUEUED = 20;
    /** A poll that ended this recently still counts as listening (the AI is between two calls). */
    private static final long LISTEN_GRACE_MS = 30_000;

    private static final ArrayDeque<JsonObject> QUEUE = new ArrayDeque<>();
    private static final Object LOCK = new Object();
    private static int nextId;
    private static int waiting;
    private static long lastPollEnd;

    private AgentInbox() {
    }

    static void register(RegisterClientCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("agent")
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.argument("request", StringArgumentType.greedyString())
                        .executes(ctx -> ask(ctx.getSource(), StringArgumentType.getString(ctx, "request")))));
    }

    public static boolean listening() {
        synchronized (LOCK) {
            return waiting > 0 || System.currentTimeMillis() - lastPollEnd < LISTEN_GRACE_MS;
        }
    }

    private static int status(CommandSourceStack s) {
        int queued;
        synchronized (LOCK) {
            queued = QUEUE.size();
        }
        s.sendSuccess(() -> Component.literal(listening()
                ? "Your AI is listening. Ask with /agent <request>." + (queued > 0 ? " (" + queued + " waiting)" : "")
                : "Your AI isn't listening right now. Tell it \"listen for /agent\" (it uses agent_inbox), or connect it "
                        + "first with /lensbridge connect." + (queued > 0 ? " " + queued + " request(s) are waiting for it." : ""))
                .withStyle(ChatFormatting.GRAY), false);
        return queued;
    }

    private static int ask(CommandSourceStack s, String text) {
        JsonObject req = context();
        boolean dropped;
        synchronized (LOCK) {
            req.addProperty("id", ++nextId);
            req.addProperty("request", text);
            req.addProperty("time", System.currentTimeMillis());
            QUEUE.addLast(req);
            dropped = QUEUE.size() > MAX_QUEUED;
            if (dropped) {
                QUEUE.removeFirst();
            }
            LOCK.notifyAll();
        }
        AgentlinkSupport.publish("lensbridge.agent_request", req);
        s.sendSuccess(() -> Component.literal(listening() ? "→ sent to your AI" : "→ queued: your AI isn't listening yet "
                + "(tell it \"listen for /agent\")").withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    /** Where the player is and what they're looking at, for the AI. Render thread (commands run there). */
    private static JsonObject context() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        JsonObject o = new JsonObject();
        if (p == null) {
            return o;
        }
        JsonObject at = new JsonObject();
        at.addProperty("x", Math.round(p.getX() * 10) / 10.0);
        at.addProperty("y", Math.round(p.getY() * 10) / 10.0);
        at.addProperty("z", Math.round(p.getZ() * 10) / 10.0);
        at.addProperty("yaw", Math.round(p.getYRot()));
        at.addProperty("pitch", Math.round(p.getXRot()));
        at.addProperty("dimension", p.level().dimension().identifier().toString());
        o.add("player", at);
        o.addProperty("name", p.getGameProfile().name());
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos bp = b.getBlockPos();
            JsonObject l = new JsonObject();
            l.addProperty("block", BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(bp).getBlock()).toString());
            l.addProperty("x", bp.getX());
            l.addProperty("y", bp.getY());
            l.addProperty("z", bp.getZ());
            o.add("lookingAt", l);
        } else if (hit instanceof EntityHitResult eh) {
            JsonObject l = new JsonObject();
            l.addProperty("entity", BuiltInRegistries.ENTITY_TYPE.getKey(eh.getEntity().getType()).toString());
            l.addProperty("name", eh.getEntity().getName().getString());
            l.addProperty("x", Math.round(eh.getEntity().getX() * 10) / 10.0);
            l.addProperty("y", Math.round(eh.getEntity().getY() * 10) / 10.0);
            l.addProperty("z", Math.round(eh.getEntity().getZ() * 10) / 10.0);
            o.add("lookingAt", l);
        }
        var server = mc.getCurrentServer();
        o.addProperty("where", mc.hasSingleplayerServer() ? "singleplayer" : server != null ? "server " + server.ip : "?");
        var hello = ServerAgent.hello;
        o.addProperty("serverTools", hello != null && !"none".equals(hello.get("access").getAsString())
                ? hello.get("access").getAsString() : "none");
        return o;
    }

    /** Take queued requests, waiting up to {@code waitMs} for the first one. Called from the bridge thread. */
    public static JsonArray take(long waitMs) throws InterruptedException {
        long until = System.currentTimeMillis() + Math.max(0, waitMs);
        synchronized (LOCK) {
            waiting++;
            try {
                while (QUEUE.isEmpty()) {
                    long left = until - System.currentTimeMillis();
                    if (left <= 0) {
                        break;
                    }
                    LOCK.wait(left);
                }
                JsonArray out = new JsonArray();
                while (!QUEUE.isEmpty()) {
                    out.add(QUEUE.removeFirst());
                }
                return out;
            } finally {
                waiting--;
                lastPollEnd = System.currentTimeMillis();
            }
        }
    }

    /** Show the AI's answer in the player's chat (this client only). */
    public static void say(String text) {
        MutableComponent line = Component.literal("[AI] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
        Game.run(() -> Minecraft.getInstance().gui.getChat().addMessage(line));
    }
}
