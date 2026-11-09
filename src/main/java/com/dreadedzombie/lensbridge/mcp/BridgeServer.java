package com.dreadedzombie.lensbridge.mcp;

import com.dreadedzombie.lensbridge.BridgeConfig;
import com.dreadedzombie.lensbridge.Lensbridge;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Local HTTP server speaking MCP "Streamable HTTP" (JSON-RPC 2.0, plain JSON responses) on /mcp, plus a
 * simple REST mirror for scripts: POST /api/&lt;tool&gt; with the arguments as the JSON body.
 * Every request needs {@code Authorization: Bearer <token>} except GET /health.
 */
public final class BridgeServer {
    private static final Gson GSON = new Gson();
    private static final String PROTOCOL = "2025-06-18";
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final BridgeConfig config;
    private HttpServer http;

    public BridgeServer(BridgeConfig config) {
        this.config = config;
    }

    public void register(Tool tool) {
        this.tools.put(tool.name(), tool);
    }

    public Map<String, Tool> tools() {
        return this.tools;
    }

    public void start() throws IOException {
        this.http = HttpServer.create(new InetSocketAddress(this.config.host, this.config.port), 0);
        this.http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "lensbridge-http");
            t.setDaemon(true);
            return t;
        }));
        this.http.createContext("/health", ex -> send(ex, 200, "{\"ok\":true,\"name\":\"lensbridge\"}"));
        this.http.createContext("/mcp", this::handleMcp);
        this.http.createContext("/api/", this::handleRest);
        this.http.createContext("/ui", this::handleUi);
        this.http.createContext("/file", this::handleFile);
        this.http.start();
        Lensbridge.LOGGER.info("Lensbridge listening on http://{}:{}/mcp ({} tools)", this.config.host, this.config.port, this.tools.size());
    }

    public void stop() {
        if (this.http != null) {
            this.http.stop(0);
        }
    }

    private void handleUi(HttpExchange ex) throws IOException {
        try (var in = BridgeServer.class.getResourceAsStream("/assets/lensbridge/ui/index.html")) {
            byte[] page = in == null ? "missing ui".getBytes(StandardCharsets.UTF_8) : in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, page.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(page);
            }
        }
        ex.close();
    }

    /** Serve a file from the capture folder (thumbnails, screenshots, videos). Token via header or ?token=. */
    private void handleFile(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            send(ex, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        String q = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
        String rel = "";
        for (String part : q.split("&")) {
            if (part.startsWith("path=")) {
                rel = java.net.URLDecoder.decode(part.substring(5), StandardCharsets.UTF_8);
            }
        }
        java.nio.file.Path root = com.dreadedzombie.lensbridge.Capture.dir("").toAbsolutePath().normalize();
        java.nio.file.Path file = root.resolve(rel).normalize();
        if (!file.startsWith(root) || !java.nio.file.Files.isRegularFile(file)) {
            send(ex, 404, "{\"error\":\"not found\"}");
            return;
        }
        String name = file.getFileName().toString();
        ex.getResponseHeaders().set("Content-Type", name.endsWith(".png") ? "image/png" : name.endsWith(".mp4") ? "video/mp4"
                : name.endsWith(".gif") ? "image/gif" : "application/octet-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, java.nio.file.Files.size(file));
        try (OutputStream out = ex.getResponseBody()) {
            java.nio.file.Files.copy(file, out);
        }
        ex.close();
    }

    private boolean authorized(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        String q = ex.getRequestURI().getRawQuery();
        if (header == null && q != null) {
            for (String part : q.split("&")) {
                if (part.startsWith("token=")) {
                    header = "Bearer " + part.substring(6);
                }
            }
        }
        String expected = "Bearer " + this.config.token;
        return header != null && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private void handleRest(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            send(ex, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        String name = ex.getRequestURI().getPath().substring("/api/".length());
        if (name.isEmpty()) {
            JsonArray list = new JsonArray();
            this.tools.values().forEach(t -> list.add(describe(t)));
            send(ex, 200, GSON.toJson(list));
            return;
        }
        Tool tool = this.tools.get(name);
        if (tool == null) {
            send(ex, 404, "{\"error\":\"no such tool: " + name + "\"}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject args = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
        send(ex, 200, GSON.toJson(call(tool, args).toJson()));
    }

    private void handleMcp(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            send(ex, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().add("Allow", "POST");
            send(ex, 405, "");
            return;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            send(ex, 400, rpcError(null, -32700, "Parse error"));
            return;
        }
        if (parsed.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement el : parsed.getAsJsonArray()) {
                JsonObject res = rpc(el.getAsJsonObject(), ex);
                if (res != null) {
                    out.add(res);
                }
            }
            if (out.isEmpty()) {
                send(ex, 202, "");
            } else {
                send(ex, 200, GSON.toJson(out));
            }
            return;
        }
        JsonObject res = rpc(parsed.getAsJsonObject(), ex);
        if (res == null) {
            send(ex, 202, "");
        } else {
            send(ex, 200, GSON.toJson(res));
        }
    }

    /** Handle one JSON-RPC message; null for notifications. */
    private JsonObject rpc(JsonObject msg, HttpExchange ex) {
        JsonElement id = msg.get("id");
        String method = msg.has("method") ? msg.get("method").getAsString() : "";
        JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();
        if (id == null) {
            return null;   // notification (e.g. notifications/initialized)
        }
        switch (method) {
            case "initialize" -> {
                JsonObject result = new JsonObject();
                result.addProperty("protocolVersion", params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : PROTOCOL);
                JsonObject caps = new JsonObject();
                caps.add("tools", new JsonObject());
                result.add("capabilities", caps);
                JsonObject info = new JsonObject();
                info.addProperty("name", "lensbridge");
                info.addProperty("version", Lensbridge.VERSION);
                result.add("serverInfo", info);
                result.addProperty("instructions", INSTRUCTIONS);
                ex.getResponseHeaders().add("Mcp-Session-Id", UUID.randomUUID().toString());
                return rpcResult(id, result);
            }
            case "ping" -> {
                return rpcResult(id, new JsonObject());
            }
            case "tools/list" -> {
                JsonArray list = new JsonArray();
                this.tools.values().forEach(t -> list.add(describe(t)));
                JsonObject result = new JsonObject();
                result.add("tools", list);
                return rpcResult(id, result);
            }
            case "tools/call" -> {
                String name = params.has("name") ? params.get("name").getAsString() : "";
                Tool tool = this.tools.get(name);
                if (tool == null) {
                    return GSON.fromJson(rpcError(id, -32602, "Unknown tool: " + name), JsonObject.class);
                }
                JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                        ? params.getAsJsonObject("arguments") : new JsonObject();
                return rpcResult(id, call(tool, args).toJson());
            }
            default -> {
                return GSON.fromJson(rpcError(id, -32601, "Method not found: " + method), JsonObject.class);
            }
        }
    }

    private static Tool.Result call(Tool tool, JsonObject args) {
        try {
            return tool.handler().apply(new Tool.Args(args));
        } catch (RuntimeException e) {
            Lensbridge.LOGGER.debug("Lensbridge tool {} failed", tool.name(), e);
            return Tool.Result.error(tool.name() + " failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private static JsonObject describe(Tool t) {
        JsonObject o = new JsonObject();
        o.addProperty("name", t.name());
        o.addProperty("description", t.description());
        o.add("inputSchema", t.schema());
        return o;
    }

    private static JsonObject rpcResult(JsonElement id, JsonObject result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id);
        o.add("result", result);
        return o;
    }

    private static String rpcError(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id);
        o.add("error", err);
        return GSON.toJson(o);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        if (status == 202 || bytes.length == 0) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        ex.close();
    }

    private static final String INSTRUCTIONS = """
            Lensbridge drives a live Minecraft client. Start with `status`. Use `screenshot` to SEE the game \
            (it captures only the game's own picture). Movement/keys act on the real player; `command` needs op. \
            For promo work: stage with `scene`, frame with `camera_set`/`camera_look_at` (director camera, HUD hidden), \
            then `screenshot`, `capture_orbit` or `capture_path` (video via ffmpeg). Always `camera_release` when done.""";
}
