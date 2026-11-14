package com.dreadedzombie.lensbridge.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.function.Function;

/** One bridge tool: name, description, JSON schema for its arguments, and the handler. */
public record Tool(String name, String description, JsonObject schema, Function<Args, Result> handler) {

    /** Typed access to the JSON arguments. */
    public record Args(JsonObject json) {
        public boolean has(String k) {
            return json.has(k) && !json.get(k).isJsonNull();
        }

        public String str(String k, String def) {
            return has(k) ? json.get(k).getAsString() : def;
        }

        public String str(String k) {
            if (!has(k)) {
                throw new IllegalArgumentException("Missing argument '" + k + "'");
            }
            return json.get(k).getAsString();
        }

        public double num(String k, double def) {
            return has(k) ? json.get(k).getAsDouble() : def;
        }

        public double num(String k) {
            if (!has(k)) {
                throw new IllegalArgumentException("Missing argument '" + k + "'");
            }
            return json.get(k).getAsDouble();
        }

        public int i(String k, int def) {
            return has(k) ? json.get(k).getAsInt() : def;
        }

        public boolean bool(String k, boolean def) {
            return has(k) ? json.get(k).getAsBoolean() : def;
        }

        public JsonArray arr(String k) {
            return has(k) ? json.getAsJsonArray(k) : new JsonArray();
        }

        public JsonObject obj(String k) {
            return has(k) ? json.getAsJsonObject(k) : new JsonObject();
        }
    }

    /** MCP tool result: a list of text/image content blocks. */
    public static final class Result {
        final JsonArray content = new JsonArray();
        boolean error;

        public static Result text(String text) {
            return new Result().addText(text);
        }

        /** An MCP CallToolResult built elsewhere (e.g. relayed from the server). */
        public static Result raw(JsonObject mcpResult) {
            Result r = new Result();
            if (mcpResult.has("content") && mcpResult.get("content").isJsonArray()) {
                r.content.addAll(mcpResult.getAsJsonArray("content"));
            }
            r.error = mcpResult.has("isError") && mcpResult.get("isError").getAsBoolean();
            return r;
        }

        public static Result error(String text) {
            Result r = text(text);
            r.error = true;
            return r;
        }

        public Result addText(String text) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "text");
            o.addProperty("text", text);
            this.content.add(o);
            return this;
        }

        /** Put a line of text before everything else (e.g. a note from a pre-pass). */
        public Result prependText(String text) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "text");
            o.addProperty("text", text);
            JsonArray rest = this.content.deepCopy();
            while (!this.content.isEmpty()) {
                this.content.remove(0);
            }
            this.content.add(o);
            this.content.addAll(rest);
            return this;
        }

        public Result addImage(Path png, int maxEdge) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "image");
            o.addProperty("mimeType", "image/jpeg");
            o.addProperty("data", com.dreadedzombie.lensbridge.Capture.previewBase64(png, maxEdge));
            this.content.add(o);
            return this;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.add("content", this.content);
            o.addProperty("isError", this.error);
            return o;
        }
    }

    /** Small fluent JSON-schema builder. */
    public static final class Schema {
        private final JsonObject root = new JsonObject();
        private final JsonObject props = new JsonObject();
        private final JsonArray required = new JsonArray();

        public Schema() {
            root.addProperty("type", "object");
            root.add("properties", props);
        }

        public Schema prop(String name, String type, String description) {
            JsonObject p = new JsonObject();
            p.addProperty("type", type);
            p.addProperty("description", description);
            props.add(name, p);
            return this;
        }

        public Schema req(String name, String type, String description) {
            prop(name, type, description);
            required.add(name);
            return this;
        }

        public JsonObject build() {
            if (!required.isEmpty()) {
                root.add("required", required);
            }
            return root;
        }
    }
}
