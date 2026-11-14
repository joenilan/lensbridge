package com.dreadedzombie.lensbridge.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The client's end of the server-tools relay: what the connected server offers this player's agent (its "hello"),
 * calls waiting for an answer, and the server's live events. No client classes here: it is fed from the network
 * handler; the tools that use it are in tools/ServerAgentTools.
 */
public final class ServerAgent {
    private static final int MAX_EVENTS = 500;

    /** The server's last hello ({access, tools, ...}), or null: not on a server that relays its tools. */
    public static volatile JsonObject hello;
    private static final Map<Integer, CompletableFuture<JsonObject>> PENDING = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final ArrayDeque<JsonObject> EVENTS = new ArrayDeque<>();
    private static long seq;

    private ServerAgent() {
    }

    static void onDown(AgentDown p) {
        JsonObject body = JsonParser.parseString(p.json()).getAsJsonObject();
        switch (p.kind()) {
            case "hello" -> hello = body;
            case "result" -> {
                CompletableFuture<JsonObject> f = PENDING.remove(p.id());
                if (f != null) {
                    f.complete(body);
                }
            }
            case "event" -> {
                synchronized (EVENTS) {
                    body.addProperty("seq", ++seq);
                    EVENTS.addLast(body);
                    while (EVENTS.size() > MAX_EVENTS) {
                        EVENTS.removeFirst();
                    }
                }
            }
            default -> {
                // a newer server's message kind: ignore
            }
        }
    }

    /** Start a call; the caller sends {@code new AgentUp(id, body)} and waits on the future. */
    public static Map.Entry<Integer, CompletableFuture<JsonObject>> begin() {
        int id = NEXT.incrementAndGet();
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        PENDING.put(id, f);
        return Map.entry(id, f);
    }

    public static void forget(int id) {
        PENDING.remove(id);
    }

    /** Events after {@code since} (a seq), oldest first, whose type starts with one of {@code prefixes} (empty = all). */
    public static JsonObject events(long since, java.util.List<String> prefixes, int max) {
        JsonArray out = new JsonArray();
        long last;
        synchronized (EVENTS) {
            last = seq;
            for (JsonObject e : EVENTS) {
                if (e.get("seq").getAsLong() <= since || out.size() >= max) {
                    continue;
                }
                String type = e.get("type").getAsString();
                if (prefixes.isEmpty() || prefixes.stream().anyMatch(type::startsWith)) {
                    out.add(e);
                }
            }
        }
        JsonObject o = new JsonObject();
        o.add("events", out);
        o.addProperty("next_since", out.isEmpty() ? Math.max(since, last) : out.get(out.size() - 1).getAsJsonObject().get("seq").getAsLong());
        return o;
    }

    /** Left the server: drop its offer, fail waiting calls, forget its events. */
    public static void clear() {
        hello = null;
        PENDING.values().forEach(f -> f.completeExceptionally(new IllegalStateException("Disconnected from the server.")));
        PENDING.clear();
        synchronized (EVENTS) {
            EVENTS.clear();
        }
    }
}
