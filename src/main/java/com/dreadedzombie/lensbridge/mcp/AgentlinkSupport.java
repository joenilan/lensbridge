package com.dreadedzombie.lensbridge.mcp;

import com.dreadedzombie.lensbridge.Lensbridge;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.neoforged.fml.InterModComms;
import net.neoforged.fml.ModList;

/**
 * Lensbridge on top of Agentlink, the shared bridge mod. When Agentlink is installed, Lensbridge does not start its own
 * server: it hands its tools, web pages and agent instructions to Agentlink over InterModComms (no compile dependency,
 * so Lensbridge builds and runs without Agentlink). When Agentlink is missing, {@link BridgeServer} runs as before.
 * <p>Every tool keeps its bare name as an alias ({@code status}, {@code capture_orbit}...), so MCP clients and scripts set
 * up for Lensbridge see the same tool names; its id on the bridge is {@code lensbridge.<name>}.
 */
public final class AgentlinkSupport {
    public static final String AGENTLINK = "agentlink";
    /** Events Lensbridge publishes on the bridge (documentation for list_capabilities). */
    public static final List<String> EVENTS = List.of(
            "lensbridge.video_saved",          // any MP4 finished: capture_orbit, capture_path, capture_program, record, the Alt+R recorder
            "lensbridge.recording_started",    // Alt+R recorder started
            "lensbridge.recording_finished",   // Alt+R recorder stopped and encoded (ok/file or error)
            "lensbridge.tripod_placed",        // the player placed a tripod with Alt+T
            "lensbridge.agent_request");       // the player typed /agent <request> (also in agent_inbox)
    private static volatile Function<String, String> pageUrl;
    private static volatile Supplier<String> baseUrl;
    private static volatile BiConsumer<String, JsonObject> publish;

    private AgentlinkSupport() {
    }

    /** Agentlink is installed: let it carry Lensbridge's tools instead of starting the embedded server. */
    public static boolean available() {
        return ModList.get().isLoaded(AGENTLINK);
    }

    /** Linked and listening. */
    public static boolean linked() {
        Supplier<String> b = baseUrl;
        return b != null && b.get() != null;
    }

    /** Send everything to Agentlink. Call from InterModEnqueueEvent. */
    public static void enqueue(BridgeServer server) {
        String version = ModList.get().getModContainerById(Lensbridge.MODID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse(Lensbridge.VERSION);
        InterModComms.sendTo(Lensbridge.MODID, AGENTLINK, "register_provider", () -> provider(server, version));
    }

    /** The register_provider payload: JDK and Gson types only (see Agentlink's docs/INTEGRATING.md). */
    static Map<String, Object> provider(BridgeServer server, String version) {
        Map<String, Object> p = new HashMap<>();
        p.put("namespace", Lensbridge.MODID);
        p.put("displayName", "Lensbridge");
        p.put("version", version);
        p.put("description", "Film studio for the client: game-only screenshots, input, inspection, director camera, "
                + "tripods, drone, camera paths, Showcase Stages and video capture.");
        p.put("instructions", BridgeServer.INSTRUCTIONS);
        p.put("events", EVENTS);
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Tool t : server.tools().values()) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("inputSchema", t.schema());
            m.put("aliases", List.of(t.name()));
            m.put("handler", (Function<JsonObject, JsonObject>) args -> BridgeServer.call(t, args).toJson());
            tools.add(m);
        }
        p.put("tools", tools);
        p.put("routes", List.of(
                route("/ui", false, BridgeServer::handleUi),
                route("/docs", false, BridgeServer::handleDocs),
                route("/file", true, BridgeServer::serveFile)));
        p.put("onLinked", (Consumer<Map<String, Object>>) AgentlinkSupport::onLinked);
        return p;
    }

    private static Map<String, Object> route(String path, boolean token, com.sun.net.httpserver.HttpHandler handler) {
        Map<String, Object> r = new HashMap<>();
        r.put("path", path);
        r.put("requiresToken", token);
        r.put("handler", handler);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static void onLinked(Map<String, Object> info) {
        pageUrl = (Function<String, String>) info.get("pageUrl");
        baseUrl = (Supplier<String>) info.get("baseUrl");
        publish = (BiConsumer<String, JsonObject>) info.get("publish");
        // singleplayer / LAN host: the integrated server relays its tools to LAN staff through this same link
        com.dreadedzombie.lensbridge.server.AgentRelay.link(info);
        String url = baseUrl == null ? null : baseUrl.get();
        if (url == null) {
            Lensbridge.LOGGER.warn("Lensbridge: Agentlink is installed but not listening (see the Agentlink lines in the log); "
                    + "Lensbridge's tools are unreachable until it is");
        } else {
            Lensbridge.LOGGER.info("Lensbridge: tools served by Agentlink {} at {}/mcp", info.get("version"), url);
        }
    }

    /** A local page's URL with the token in the fragment, or null when not linked. */
    public static String pageUrl(String path) {
        Function<String, String> f = pageUrl;
        return f == null ? null : f.apply(path);
    }

    /** Publish a bridge event (no-op without Agentlink). Type must start with "lensbridge.". */
    public static void publish(String type, JsonObject data) {
        BiConsumer<String, JsonObject> p = publish;
        if (p != null) {
            try {
                p.accept(type, data);
            } catch (RuntimeException e) {
                Lensbridge.LOGGER.debug("Lensbridge: event {} not published", type, e);
            }
        }
    }
}
