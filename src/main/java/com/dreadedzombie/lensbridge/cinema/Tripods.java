package com.dreadedzombie.lensbridge.cinema;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import org.jspecify.annotations.Nullable;

/** Named fixed cameras ("tripods") for multi-angle stills and synced multicam capture. Saved to config/lensbridge/tripods.json. */
public final class Tripods {
    /** A tripod: fixed position; either fixed angles or a tracking target (stored as its JSON spec). */
    public record Tripod(String name, Vec3 pos, float yaw, float pitch, float fov, @Nullable JsonObject track, boolean enabled) {
        public Tripod(String name, Vec3 pos, float yaw, float pitch, float fov, @Nullable JsonObject track) {
            this(name, pos, yaw, pitch, fov, track, true);
        }

        /** Group = first segment of the name ("wm_03_luna" -> "wm"), used to organise the camera manager. */
        public String group() {
            int i = name.indexOf('_');
            return i > 0 ? name.substring(0, i) : "misc";
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("group", group());
            o.addProperty("x", pos.x);
            o.addProperty("y", pos.y);
            o.addProperty("z", pos.z);
            o.addProperty("yaw", yaw);
            o.addProperty("pitch", pitch);
            o.addProperty("fov", fov);
            o.addProperty("enabled", enabled);
            if (track != null) {
                o.add("track", track);
            }
            return o;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Tripod> TRIPODS = new LinkedHashMap<>();
    private static boolean loaded;

    private Tripods() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("lensbridge").resolve("tripods.json");
    }

    public static synchronized Map<String, Tripod> all() {
        if (!loaded) {
            loaded = true;
            try {
                if (Files.exists(file())) {
                    for (var el : JsonParser.parseString(Files.readString(file())).getAsJsonArray()) {
                        JsonObject o = el.getAsJsonObject();
                        TRIPODS.put(o.get("name").getAsString(), new Tripod(o.get("name").getAsString(),
                                new Vec3(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble()),
                                o.get("yaw").getAsFloat(), o.get("pitch").getAsFloat(), o.get("fov").getAsFloat(),
                                o.has("track") ? o.getAsJsonObject("track") : null,
                                !o.has("enabled") || o.get("enabled").getAsBoolean()));
                    }
                }
            } catch (IOException | RuntimeException e) {
                com.dreadedzombie.lensbridge.Lensbridge.LOGGER.warn("Lensbridge: bad tripods.json", e);
            }
        }
        return TRIPODS;
    }

    public static synchronized void put(Tripod t) {
        all().put(t.name(), t);
        save();
    }

    public static synchronized boolean remove(String name) {
        boolean r = all().remove(name) != null;
        save();
        return r;
    }

    public static synchronized void clear() {
        all().clear();
        save();
    }

    public static synchronized String nextName() {
        int i = 1;
        while (all().containsKey("tripod_" + i)) {
            i++;
        }
        return "tripod_" + i;
    }

    private static void save() {
        JsonArray arr = new JsonArray();
        for (Tripod t : TRIPODS.values()) {
            arr.add(t.toJson());
        }
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(arr));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** The shot this tripod sees right now (tracking tripods aim at their target). Client thread. */
    public static Shot shot(Tripod t, float partialTick) {
        if (t.track() != null) {
            try {
                Vec3 aim = Target.parse(t.track()).aim(partialTick);
                if (aim != null) {
                    float[] yp = com.dreadedzombie.lensbridge.DirectorCamera.lookAt(t.pos(), aim);
                    return new Shot(t.pos(), yp[0], yp[1], 0, t.fov());
                }
            } catch (RuntimeException ignored) {
                // target gone: fall back to the stored angles
            }
        }
        return new Shot(t.pos(), t.yaw(), t.pitch(), 0, t.fov());
    }
}
