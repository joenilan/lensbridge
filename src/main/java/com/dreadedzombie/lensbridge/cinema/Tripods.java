package com.dreadedzombie.lensbridge.cinema;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import org.jspecify.annotations.Nullable;

/** Named fixed cameras ("tripods") for multi-angle stills and synced multicam capture. Saved to config/lensbridge/tripods.json. */
public final class Tripods {
    /**
     * A tripod: fixed position; either fixed angles or a tracking target (stored as its JSON spec). {@code world} and
     * {@code dim} say where it was placed (see {@link #currentWorld()}); null = saved before cameras knew their world,
     * and treated as belonging everywhere so nothing disappears.
     */
    public record Tripod(String name, Vec3 pos, float yaw, float pitch, float fov, @Nullable JsonObject track, boolean enabled,
                         @Nullable String world, @Nullable String dim) {
        /** A new camera, placed in the world and dimension the player is in now. */
        public Tripod(String name, Vec3 pos, float yaw, float pitch, float fov, @Nullable JsonObject track) {
            this(name, pos, yaw, pitch, fov, track, true, currentWorld(), currentDim());
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
            if (world != null) {
                o.addProperty("world", world);
            }
            if (dim != null) {
                o.addProperty("dimension", dim);
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

    /** The live map (callers hold the class lock). Loads the file on first use. */
    private static Map<String, Tripod> map() {
        if (!loaded) {
            loaded = true;
            load();
        }
        return TRIPODS;
    }

    /**
     * Read tripods.json. A bad entry is skipped, not the rest of the file; and because the next save rewrites the file
     * from what was read, anything unreadable is first copied to tripods.json.broken-<time> so no camera is lost.
     */
    private static void load() {
        Path file = file();
        if (!Files.exists(file)) {
            return;
        }
        boolean bad = false;
        try {
            for (var el : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
                try {
                    JsonObject o = el.getAsJsonObject();
                    TRIPODS.put(o.get("name").getAsString(), new Tripod(o.get("name").getAsString(),
                            new Vec3(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble()),
                            o.get("yaw").getAsFloat(), o.get("pitch").getAsFloat(), o.get("fov").getAsFloat(),
                            o.has("track") && o.get("track").isJsonObject() ? o.getAsJsonObject("track") : null,
                            !o.has("enabled") || o.get("enabled").getAsBoolean(),
                            o.has("world") ? o.get("world").getAsString() : null,
                            o.has("dimension") ? o.get("dimension").getAsString() : null));
                } catch (RuntimeException e) {
                    bad = true;
                    com.dreadedzombie.lensbridge.Lensbridge.LOGGER.warn("Lensbridge: skipping an unreadable camera in tripods.json: {}", el, e);
                }
            }
        } catch (IOException | RuntimeException e) {
            bad = true;
            com.dreadedzombie.lensbridge.Lensbridge.LOGGER.warn("Lensbridge: tripods.json could not be read", e);
        }
        if (bad) {
            Path keep = file.resolveSibling("tripods.json.broken-" + System.currentTimeMillis());
            try {
                Files.copy(file, keep, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                com.dreadedzombie.lensbridge.Lensbridge.LOGGER.warn("Lensbridge: kept the damaged camera list as {} ({} cameras were readable)",
                        keep, TRIPODS.size());
            } catch (IOException e) {
                com.dreadedzombie.lensbridge.Lensbridge.LOGGER.warn("Lensbridge: could not keep a copy of the damaged tripods.json", e);
            }
        }
    }

    /** Every camera, in every world: a copy, safe to keep and iterate on any thread (HTTP threads edit the real one). */
    public static synchronized Map<String, Tripod> all() {
        return new LinkedHashMap<>(map());
    }

    public static synchronized @Nullable Tripod get(String name) {
        return map().get(name);
    }

    // ---------------------------------------------------------------- which world a camera belongs to

    private static volatile Object worldOf;
    private static volatile String worldName;

    /** "mp:<server address>" or "sp:<world folder>"; null when not in a world (or it can't be told). Any thread. */
    public static @Nullable String currentWorld() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            var level = mc.level;
            if (level == null) {
                return null;
            }
            if (level == worldOf) {
                return worldName;   // asked every frame while props are shown: work it out once per level
            }
            var local = mc.getSingleplayerServer();
            var server = mc.getCurrentServer();
            String name = local != null ? "sp:" + local.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .toAbsolutePath().normalize().getFileName()
                    : server == null ? null : "mp:" + server.ip.toLowerCase(java.util.Locale.ROOT);
            worldName = name;
            worldOf = level;
            return name;
        } catch (RuntimeException e) {
            return null;   // between worlds: treat as "can't tell"
        }
    }

    /** The dimension the player is in ("minecraft:overworld"), or null when not in a world. Any thread. */
    public static @Nullable String currentDim() {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        return level == null ? null : level.dimension().identifier().toString();
    }

    /** A camera with no world/dimension (saved by an older version) counts as being everywhere. */
    private static boolean isHere(Tripod t, @Nullable String world, @Nullable String dim) {
        return (t.world() == null || world == null || t.world().equals(world))
                && (t.dim() == null || dim == null || t.dim().equals(dim));
    }

    /** The cameras of the world and dimension the player is in now (a copy). Cameras are global on disk, not in use. */
    public static List<Tripod> here() {
        String world = currentWorld();
        String dim = currentDim();
        List<Tripod> out = new ArrayList<>();
        synchronized (Tripods.class) {
            for (Tripod t : map().values()) {
                if (isHere(t, world, dim)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** {@link #here()}, or every camera everywhere when {@code everywhere}. */
    public static List<Tripod> list(boolean everywhere) {
        return everywhere ? new ArrayList<>(all().values()) : here();
    }

    public static synchronized void put(Tripod t) {
        map().put(t.name(), t);
        save();
    }

    public static synchronized boolean remove(String name) {
        boolean r = map().remove(name) != null;
        if (r) {
            save();
            deleteThumb(name);
        }
        return r;
    }

    public static synchronized void clear() {
        List<String> names = new ArrayList<>(map().keySet());
        map().clear();
        save();
        names.forEach(Tripods::deleteThumb);
    }

    /** Names are reused (tripod_1 again after a removal): without this a new camera would show a removed one's picture. */
    private static void deleteThumb(String name) {
        try {
            Files.deleteIfExists(com.dreadedzombie.lensbridge.tools.ManagerTools.thumb(name));
        } catch (IOException | RuntimeException e) {
            com.dreadedzombie.lensbridge.Lensbridge.LOGGER.debug("Lensbridge: thumbnail of {} not deleted", name, e);
        }
    }

    public static synchronized String nextName() {
        int i = 1;
        while (map().containsKey("tripod_" + i)) {
            i++;
        }
        return "tripod_" + i;
    }

    /**
     * Written to a temp file and moved into place, so a crash mid-write can't leave half a file. A failure is logged,
     * not thrown: this runs on the client thread for Alt+T, where an exception would crash the game.
     */
    private static void save() {
        JsonArray arr = new JsonArray();
        for (Tripod t : TRIPODS.values()) {
            arr.add(t.toJson());
        }
        Path file = file();
        Path tmp = file.resolveSibling("tripods.json.tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, GSON.toJson(arr));
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            com.dreadedzombie.lensbridge.Lensbridge.LOGGER.error("Lensbridge: could not save the cameras to {} (they are kept "
                    + "in memory until the game closes)", file, e);
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
