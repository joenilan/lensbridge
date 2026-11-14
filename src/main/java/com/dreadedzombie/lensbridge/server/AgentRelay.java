package com.dreadedzombie.lensbridge.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server's end of the server-tools relay. A staff member's own AI talks to their own Lensbridge (on their PC);
 * Lensbridge passes server tool calls over the game connection to here; this checks what the server lets that player
 * do (permission {@code lensbridge.server_agent}: none / read / moderate / admin) and has Agentlink run the call as
 * them, so Agentlink's tier checks and audit log see the staff member's name. The server needs Agentlink and
 * Lensbridge; the staff member needs only Lensbridge; nobody opens a port or shares a token.
 * <p>Only common classes: this runs on dedicated servers and on the integrated server of a LAN host.
 */
public final class AgentRelay {
    private static final Logger LOG = LoggerFactory.getLogger("Lensbridge");
    private static final int MAX_IN_FLIGHT = 4;
    private static final String NONE = "none";

    private static volatile Function<String, JsonArray> relayTools;
    private static volatile Function<Map<String, Object>, JsonObject> relayCall;
    /** What each online player was last told (their access), so changes are pushed. */
    private static final Map<UUID, String> SENT = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicInteger> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "Lensbridge agent relay");
        t.setDaemon(true);
        return t;
    });
    /** A relayed call that takes longer is given up on (a little under the client's own 30 s wait, so the reason arrives). */
    private static final int CALL_TIMEOUT_SECONDS = 25;
    private static final java.util.concurrent.ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Lensbridge agent relay timer");
        t.setDaemon(true);
        return t;
    });
    /**
     * Events only staff may be sent (moderate or admin), if this Agentlink doesn't say itself (see {@link #eventTier}).
     * Before, every agentlink.* event went to everyone with any access, "read" included.
     */
    private static final java.util.Set<String> STAFF_EVENTS = java.util.Set.of(
            "agentlink.staff_chat", "agentlink.report", "agentlink.moderation", "agentlink.command");
    /** Agentlink's own answer to "what access does this event type need" ("read" / "moderate" / "admin"), when it offers one. */
    private static volatile Function<String, String> eventTier;

    private AgentRelay() {
    }

    /** Agentlink's onLinked info (from the server's own registration, or the client's in singleplayer/LAN). */
    @SuppressWarnings("unchecked")
    public static void link(Map<String, Object> info) {
        if (!(info.get("relayTools") instanceof Function<?, ?> tools) || !(info.get("relayCall") instanceof Function<?, ?> call)) {
            LOG.info("Lensbridge: this Agentlink can't relay server tools (too old); staff tools over the game connection are off");
            return;
        }
        relayTools = (Function<String, JsonArray>) tools;
        relayCall = (Function<Map<String, Object>, JsonObject>) call;
        if (info.get("eventTier") instanceof Function<?, ?> tier) {
            eventTier = (Function<String, String>) tier;
        }
        if (info.get("subscribe") instanceof Function<?, ?> sub) {
            ((Function<BiConsumer<String, JsonObject>, AutoCloseable>) sub).apply(AgentRelay::onEvent);
        }
        LOG.info("Lensbridge: relaying this server's Agentlink tools to staff who run Lensbridge (permission lensbridge.server_agent)");
    }

    public static boolean linked() {
        return relayCall != null;
    }

    /** For a dedicated server: the register_provider payload that gets the link (no tools of its own). */
    public static Map<String, Object> serverProvider(String version) {
        Map<String, Object> p = new HashMap<>();
        p.put("namespace", "lensbridge");
        p.put("displayName", "Lensbridge (server)");
        p.put("version", version);
        p.put("description", "Relays this server's tools to staff members' own AI, over their game connection "
                + "(they run Lensbridge; permission lensbridge.server_agent sets what each may do).");
        p.put("tools", List.of());
        p.put("onLinked", (Consumer<Map<String, Object>>) AgentRelay::link);
        return p;
    }

    static String access(ServerPlayer p) {
        String a = PermissionAPI.getPermission(p, LensbridgeServer.AGENT_NODE);
        return a == null ? NONE : switch (a.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "read", "moderate", "admin" -> a.trim().toLowerCase(java.util.Locale.ROOT);
            default -> NONE;
        };
    }

    /** Called every couple of seconds per player: tell them (again) what they may use, when it changed. */
    static void sync(ServerPlayer p) {
        if (!linked() || !p.connection.hasChannel(AgentDown.TYPE)) {
            return;
        }
        String access = access(p);
        if (access.equals(SENT.put(p.getUUID(), access))) {
            return;
        }
        JsonObject hello = new JsonObject();
        hello.addProperty("access", access);
        hello.add("tools", NONE.equals(access) ? new JsonArray() : relayTools.apply(access));
        MinecraftServer server = p.level().getServer();
        hello.addProperty("server", server.getMotd());
        send(p, new AgentDown("hello", 0, hello.toString()));
    }

    static void forget(UUID player) {
        SENT.remove(player);
        IN_FLIGHT.remove(player);
    }

    /** The tool list changed (a mod registered or removed tools): send everyone a fresh hello. */
    static void resendAll() {
        SENT.clear();
    }

    /** A call from a player's Lensbridge. Runs off the server thread (tools wait on it for world reads). */
    static void onCall(AgentUp up, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer p)) {
            return;
        }
        String access = access(p);
        if (!linked() || NONE.equals(access)) {
            reply(p, up.id(), error(linked() ? "This server doesn't give you access to its tools (permission lensbridge.server_agent)."
                    : "This server doesn't run Agentlink, so it has no tools to offer."));
            return;
        }
        AtomicInteger busy = IN_FLIGHT.computeIfAbsent(p.getUUID(), u -> new AtomicInteger());
        if (busy.incrementAndGet() > MAX_IN_FLIGHT) {
            busy.decrementAndGet();
            reply(p, up.id(), error("Too many calls at once; wait for the previous ones."));
            return;
        }
        String name = p.getGameProfile().name();
        String uuid = p.getUUID().toString();
        // exactly one answer per call, from whichever comes first: the result or the timeout
        java.util.concurrent.atomic.AtomicBoolean answered = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.Future<?> running = POOL.submit(() -> {
            JsonObject result;
            try {
                JsonObject body = JsonParser.parseString(up.json()).getAsJsonObject();
                Map<String, Object> call = new HashMap<>();
                call.put("name", name);
                call.put("uuid", uuid);
                call.put("access", access);
                call.put("tool", body.has("tool") ? body.get("tool").getAsString() : "");
                call.put("args", body.has("args") && body.get("args").isJsonObject() ? body.getAsJsonObject("args") : new JsonObject());
                result = relayCall.apply(call);
            } catch (RuntimeException e) {
                result = error("The server could not run that: " + e.getMessage());
            }
            if (answered.compareAndSet(false, true)) {
                busy.decrementAndGet();
                reply(p, up.id(), result);
            }
        });
        // a call that hangs used to hold its slot for good (two of them stopped the relay for everyone)
        TIMER.schedule(() -> {
            if (answered.compareAndSet(false, true)) {
                running.cancel(true);   // interrupts the worker if it has started, drops it from the queue if not
                busy.decrementAndGet();
                reply(p, up.id(), error("The server took longer than " + CALL_TIMEOUT_SECONDS + " s to run that and gave up. "
                        + "Try again, or ask for less."));
            }
        }, CALL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static final List<String> TIERS = List.of(NONE, "read", "moderate", "admin");

    /** The access a player needs to be sent this event: Agentlink's answer when it gives one, else the local staff list. */
    private static String eventTier(String type) {
        Function<String, String> ask = eventTier;
        if (ask != null) {
            try {
                String tier = ask.apply(type);
                if (tier != null && TIERS.indexOf(tier) > 0) {
                    // never lower than the local list: an older Agentlink may not have marked every staff event
                    return STAFF_EVENTS.contains(type) && TIERS.indexOf(tier) < 2 ? "moderate" : tier;
                }
            } catch (RuntimeException e) {
                // fall through to the local list
            }
        }
        return STAFF_EVENTS.contains(type) ? "moderate" : "read";
    }

    private static void reply(ServerPlayer p, int id, JsonObject result) {
        String json = result.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > AgentDown.MAX_BYTES) {
            json = error("The answer is too big to send over the game connection; ask for less (a limit, one mod, "
                    + "one player).").toString();
        }
        send(p, new AgentDown("result", id, json));
    }

    private static void onEvent(String type, JsonObject data) {
        if (!type.startsWith("agentlink.") || type.equals("agentlink.tools_changed")) {
            if (type.equals("agentlink.tools_changed")) {
                resendAll();
            }
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        JsonObject e = new JsonObject();
        e.addProperty("type", type);
        e.addProperty("time", System.currentTimeMillis());
        e.add("data", data);
        AgentDown msg = new AgentDown("event", 0, e.toString());
        int needed = TIERS.indexOf(eventTier(type));
        server.execute(() -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                String access = SENT.get(p.getUUID());
                // staff chat, reports, moderation and player commands only to moderate / admin
                if (access != null && TIERS.indexOf(access) >= Math.max(1, needed)) {
                    PacketDistributor.sendToPlayer(p, msg);
                }
            }
        });
    }

    private static void send(ServerPlayer p, AgentDown msg) {
        MinecraftServer server = p.level().getServer();
        server.execute(() -> {
            if (!p.hasDisconnected()) {
                PacketDistributor.sendToPlayer(p, msg);
            }
        });
    }

    private static JsonObject error(String text) {
        JsonObject t = new JsonObject();
        t.addProperty("type", "text");
        t.addProperty("text", text);
        JsonArray content = new JsonArray();
        content.add(t);
        JsonObject o = new JsonObject();
        o.add("content", content);
        o.addProperty("isError", true);
        return o;
    }
}
