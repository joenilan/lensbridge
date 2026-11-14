package com.dreadedzombie.lensbridge.tools;

import com.dreadedzombie.lensbridge.Game;
import com.dreadedzombie.lensbridge.mcp.BridgeServer;
import com.dreadedzombie.lensbridge.mcp.Tool;
import com.dreadedzombie.lensbridge.mcp.Tool.Result;
import com.dreadedzombie.lensbridge.mcp.Tool.Schema;
import com.dreadedzombie.lensbridge.server.AgentUp;
import com.dreadedzombie.lensbridge.server.ServerAgent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The server's own tools, reached through the game connection: when the connected server runs Agentlink and
 * Lensbridge and gives this player access, the player's AI can use the server's tools (health, lag, players, the
 * modpack's items and recipes, live events) without connecting to the server itself. The server checks every call
 * against the player's access and writes it to its audit log under the player's name.
 */
public final class ServerAgentTools {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int TIMEOUT_SECONDS = 30;

    private ServerAgentTools() {
    }

    public static void registerAll(BridgeServer s) {
        s.register(new Tool("server_tools", "The connected SERVER's own tools, if it offers them to you (it needs Agentlink "
                + "and Lensbridge installed, and gives you access as staff): server health and lag, who's online and what "
                + "they carry, every installed mod's items, blocks, mobs and recipes. Lists each tool with its arguments "
                + "and your access level. Call them with server_call; live server events with server_events. The server "
                + "logs every call under your name.",
                new Schema().build(),
                a -> {
                    JsonObject hello = requireHello();
                    JsonObject out = new JsonObject();
                    out.addProperty("server", hello.has("server") ? hello.get("server").getAsString() : "?");
                    out.addProperty("access", hello.get("access").getAsString());
                    out.add("tools", hello.get("tools"));
                    out.addProperty("how", "server_call with tool = a name from this list and args = its arguments");
                    return Result.text(PRETTY.toJson(out));
                }));

        s.register(new Tool("server_call", "Run one of the connected server's tools (see server_tools), e.g. "
                + "tool=\"agentlink_server_status\", or tool=\"agentlink_recipes\" with args={\"item\": \"iron_ingot\"}. "
                + "Runs on the server with your staff access; logged there under your name.",
                new Schema().req("tool", "string", "Tool name from server_tools")
                        .prop("args", "object", "The tool's arguments (default none)").build(),
                a -> {
                    JsonObject hello = requireHello();
                    if ("none".equals(hello.get("access").getAsString())) {
                        return Result.error("This server doesn't give you access to its tools.");
                    }
                    JsonObject body = new JsonObject();
                    body.addProperty("tool", a.str("tool"));
                    body.add("args", a.obj("args"));
                    String json = body.toString();
                    // counted in UTF-8 bytes, which is what the 32 KiB packet limit counts (non-English text is 2-4 bytes a letter)
                    if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > AgentUp.MAX_BYTES) {
                        return Result.error("Arguments too long to send to the server.");
                    }
                    Map.Entry<Integer, CompletableFuture<JsonObject>> call = ServerAgent.begin();
                    try {
                        Game.run(() -> ClientPacketDistributor.sendToServer(new AgentUp(call.getKey(), json)));
                        return Result.raw(call.getValue().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    } catch (TimeoutException e) {
                        return Result.error("The server didn't answer within " + TIMEOUT_SECONDS + " s.");
                    } catch (ExecutionException e) {
                        return Result.error(e.getCause() == null ? e.toString() : e.getCause().getMessage());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Result.error("Interrupted.");
                    } finally {
                        ServerAgent.forget(call.getKey());
                    }
                }));

        s.register(new Tool("server_events", "Live events from the connected server, oldest first: agentlink.chat, "
                + "agentlink.player_joined / player_left, agentlink.player_died, agentlink.advancement, agentlink.command "
                + "(private messages never included). Pass the returned next_since back as since for only new ones. "
                + "Collected from when you joined; up to the last 500.",
                new Schema().prop("since", "integer", "Only events after this (default 0 = all kept)")
                        .prop("types", "array", "Type prefixes, e.g. [\"agentlink.chat\"] (default all)")
                        .prop("max", "integer", "At most this many (default 100)").build(),
                a -> {
                    requireHello();
                    List<String> types = new ArrayList<>();
                    for (JsonElement t : a.arr("types")) {
                        types.add(t.getAsString());
                    }
                    return Result.text(PRETTY.toJson(ServerAgent.events((long) a.num("since", 0), types, Math.max(1, a.i("max", 100)))));
                }));
    }

    /** Picture-in-picture: keep an eye on a nearby player while the person keeps their own view. */
    public static void registerInset(BridgeServer s) {
        s.register(new Tool("inset", "Picture-in-picture: a live camera on another player in a corner of the person's "
                + "screen while they keep playing their own view (staff keeping an eye on someone). The player must be "
                + "near (within the person's view range); farther away, use the server's agentlink_watch. Free-camera "
                + "rules apply. full=true makes that camera the person's whole screen instead (Esc returns them to the "
                + "small picture). Dark places are lit as with night vision; in a space too tight for a camera it looks "
                + "through the watched one's eyes. target=\"off\" closes it. In game: /lensbridge inset <player> "
                + "[chase|front|overhead|eyes] [full], /lensbridge inset full | small | off.",
                new Schema().req("target", "string", "Player name, or \"off\"")
                        .prop("mode", "string", "chase (behind, default), front (their face), overhead, or eyes (exactly what they see)")
                        .prop("full", "boolean", "true: their whole screen shows this camera; false: back to the corner picture")
                        .prop("fps", "integer", "Pictures per second (1-60, default 30; each one is a hidden extra frame, so the person's own view shows that many fewer)")
                        .prop("size", "number", "Width as a fraction of the screen (0.15-0.5, default 0.3)")
                        .prop("corner", "string", "top_right (default), top_left, bottom_right, bottom_left").build(),
                a -> {
                    String t = a.str("target");
                    if (t.equalsIgnoreCase("off")) {
                        Game.run(com.dreadedzombie.lensbridge.Inset::stop);   // game state: client thread, not this HTTP thread
                        return Result.text("Inset closed.");
                    }
                    Game.call(() -> {
                        com.dreadedzombie.lensbridge.Inset.start(t, a.str("mode", "chase"), a.i("fps", 30),
                                (float) a.num("size", 0.3), a.str("corner", "top_right"));
                        com.dreadedzombie.lensbridge.Inset.setFull(a.bool("full", false));
                        return null;
                    });
                    Game.sleep(1500);
                    return Result.text("Inset on " + t + ": " + com.dreadedzombie.lensbridge.Inset.status
                            + ". Save its picture with inset_snapshot.");
                }));
        s.register(new Tool("inset_snapshot", "Save the inset's latest picture as a PNG (evidence) and return a preview.",
                new Schema().prop("name", "string", "File name without .png (default a timestamp)").build(),
                a -> {
                    java.nio.file.Path file = com.dreadedzombie.lensbridge.Capture.dir("screenshots")
                            .resolve(com.dreadedzombie.lensbridge.Capture.safeName(
                                    a.str("name", "inset_" + com.dreadedzombie.lensbridge.Capture.stamp())) + ".png");
                    try {
                        com.dreadedzombie.lensbridge.Inset.snapshot(file);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    return Result.text("Saved " + file + " (" + com.dreadedzombie.lensbridge.Inset.status + ")").addImage(file, 800);
                }));
    }

    /** In-game requests: /agent in the player's chat -> agent_inbox; the answer -> agent_say. */
    public static void registerInbox(BridgeServer s) {
        s.register(new Tool("agent_inbox", "Requests the player typed in game with /agent <request>, oldest first, each "
                + "with where they are and what they're looking at. Waits up to wait_seconds for the first one, so call it "
                + "in a loop to LISTEN (the player sees you as listening while you wait). Answer each with agent_say. "
                + "Requests come from the player at the keyboard, the same person you work for. A request stays in the "
                + "inbox until you acknowledge it: pass the returned next_ack as ack on your next call, or it is returned "
                + "again (marked delivery 2, 3).",
                new Schema().prop("wait_seconds", "integer", "How long to wait for a request (default 120, max 600; 0 = just check)")
                        .prop("ack", "integer", "The next_ack from your previous call: those requests reached you and are removed").build(),
                a -> {
                    try {
                        JsonObject out = new JsonObject();
                        var requests = com.dreadedzombie.lensbridge.AgentInbox.take(
                                1000L * Math.max(0, Math.min(600, a.i("wait_seconds", 120))), a.i("ack", 0));
                        out.add("requests", requests);
                        int nextAck = a.i("ack", 0);
                        for (JsonElement r : requests) {
                            nextAck = Math.max(nextAck, r.getAsJsonObject().get("id").getAsInt());
                        }
                        out.addProperty("next_ack", nextAck);
                        out.addProperty("note", requests.isEmpty()
                                ? "No requests yet; call agent_inbox again (ack=" + nextAck + ") to keep listening."
                                : "Answer with agent_say, then call agent_inbox again with ack=" + nextAck + " to keep listening "
                                + "(without ack these requests are returned again).");
                        return Result.text(PRETTY.toJson(out));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Result.error("Interrupted.");
                    }
                }));

        s.register(new Tool("agent_say", "Say something to the player in their game chat, shown as [AI] and only on their "
                + "screen (never sent to the server or other players). Use it to answer /agent requests; keep it short, "
                + "a line or two.",
                new Schema().req("text", "string", "What to say").build(),
                a -> {
                    String text = a.str("text");
                    com.dreadedzombie.lensbridge.AgentInbox.say(text.length() > 1000 ? text.substring(0, 1000) + "..." : text);
                    return Result.text("shown");
                }));
    }

    private static JsonObject requireHello() {
        JsonObject hello = ServerAgent.hello;
        if (hello != null) {
            return hello;
        }
        String where = Game.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return "Not in a world: join the server first.";
            }
            if (mc.hasSingleplayerServer() && !mc.getSingleplayerServer().isPublished()) {
                return "This is singleplayer: there's no separate server. Install Agentlink in this game to reach the "
                        + "world's tools directly (agentlink_server_status and friends).";
            }
            return "This server doesn't offer its tools to you. It needs Agentlink and Lensbridge installed on the server; "
                    + "then its owner decides who gets access (config serverAgent, or the permission lensbridge.server_agent).";
        });
        throw new IllegalStateException(where);
    }
}
