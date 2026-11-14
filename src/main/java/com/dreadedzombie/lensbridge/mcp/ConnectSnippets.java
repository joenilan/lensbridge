package com.dreadedzombie.lensbridge.mcp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Ready-to-paste setup for AI apps (same shapes as Agentlink's /agentlink connect). Every snippet contains the token:
 * never log them or show them as visible chat text.
 */
public record ConnectSnippets(String name, String url, String token) {

    public String claudeCode() {
        return "claude mcp add --transport http " + name + " " + url + " --header \"Authorization: Bearer " + token + "\"";
    }

    /** Claude Desktop only starts local programs: it reaches HTTP servers through mcp-remote (needs Node.js). */
    public String claudeDesktop() {
        JsonArray args = new JsonArray();
        args.add("-y");
        args.add("mcp-remote");
        args.add(url);
        args.add("--header");
        args.add("Authorization:${LENSBRIDGE_AUTH}");
        JsonObject env = new JsonObject();
        env.addProperty("LENSBRIDGE_AUTH", "Bearer " + token);
        JsonObject server = new JsonObject();
        server.addProperty("command", "npx");
        server.add("args", args);
        server.add("env", env);
        return wrap(server);
    }

    /** Apps that speak HTTP MCP themselves (Cursor, Windsurf, VS Code...). */
    public String otherApps() {
        JsonObject headers = new JsonObject();
        headers.addProperty("Authorization", "Bearer " + token);
        JsonObject server = new JsonObject();
        server.addProperty("type", "http");
        server.addProperty("url", url);
        server.add("headers", headers);
        return wrap(server);
    }

    private String wrap(JsonObject server) {
        JsonObject servers = new JsonObject();
        servers.add(name, server);
        JsonObject root = new JsonObject();
        root.add("mcpServers", servers);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
    }
}
