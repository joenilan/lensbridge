package com.dreadedzombie.lensbridge.tools;

import com.dreadedzombie.lensbridge.Capture;
import com.dreadedzombie.lensbridge.DirectorCamera;
import com.dreadedzombie.lensbridge.Game;
import com.dreadedzombie.lensbridge.cinema.CameraPath;
import com.dreadedzombie.lensbridge.cinema.CameraProgram;
import com.dreadedzombie.lensbridge.cinema.Shot;
import com.dreadedzombie.lensbridge.cinema.Target;
import com.dreadedzombie.lensbridge.mcp.BridgeServer;
import com.dreadedzombie.lensbridge.mcp.Tool;
import com.dreadedzombie.lensbridge.mcp.Tool.Args;
import com.dreadedzombie.lensbridge.mcp.Tool.Result;
import com.dreadedzombie.lensbridge.mcp.Tool.Schema;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Cinematic camera: target lock, live orbit / pan / rail / follow / dolly zoom, saved paths, frame-exact capture. */
public final class CinemaTools {
    private static final String TARGET_DOC = "Target: {\"entity\": id} | {\"nearest\": \"minecraft:pig\"} | {\"point\": [x,y,z]} "
            + "| {\"player\": true}; optional \"offset\": [dx,dy,dz] for the aim point.";
    private static volatile CameraPath shownPath;
    private static volatile long showUntil;

    private CinemaTools() {
    }

    static Path pathsDir() {
        Path d = FMLPaths.CONFIGDIR.get().resolve("lensbridge").resolve("paths");
        try {
            Files.createDirectories(d);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return d;
    }

    static CameraPath loadPath(String name) {
        Path f = pathsDir().resolve(clean(name) + ".json");
        if (!Files.exists(f)) {
            throw new IllegalArgumentException("No saved path '" + name + "' (path_list shows them)");
        }
        try {
            return CameraPath.parse(JsonParser.parseString(Files.readString(f)).getAsJsonArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void savePath(String name, JsonArray keys) {
        try {
            Files.writeString(pathsDir().resolve(clean(name) + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(keys));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String clean(String name) {
        return name.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    private static Target target(Args a) {
        if (!a.has("target")) {
            throw new IllegalArgumentException("Missing 'target'. " + TARGET_DOC);
        }
        return Target.parse(a.obj("target"));
    }

    private static Schema targetSchema(Schema s) {
        return s.req("target", "object", TARGET_DOC);
    }

    /** Current view (director camera if active, else the player's eyes). */
    private static Shot currentView() {
        return Game.call(() -> {
            if (DirectorCamera.active) {
                return new Shot(DirectorCamera.pos, DirectorCamera.yaw, DirectorCamera.pitch, DirectorCamera.roll, DirectorCamera.fov);
            }
            LocalPlayer p = Game.mc().player;
            return new Shot(p.getEyePosition(), p.getYRot(), p.getXRot(), 0, 0);
        });
    }

    private static Vec3 posArg(Args a, Vec3 fallback) {
        if (!a.has("x")) {
            return fallback;
        }
        Vec3 v = new Vec3(a.num("x"), a.num("y"), a.num("z"));
        return a.bool("relative", false) ? Game.call(() -> Game.mc().player.position()).add(v) : v;
    }

    private static Result started(CameraProgram p) {
        DirectorCamera.play(p);
        Game.waitFrames(2);
        double d = p.duration();
        return Result.text("Playing: " + Game.call(p::describe) + (d > 0 ? String.format(Locale.ROOT, " (%.1fs)", d) : " (endless)")
                + ". Watch with screenshot, film live with record, or render frame-exact with capture_program. camera_release to stop.");
    }

    public static void registerAll(BridgeServer s) {
        s.register(new Tool("find_entities", "Find loaded entities by type and/or name (to lock the camera on them). "
                + "Returns id, type, name, position and distance from the player.",
                new Schema().prop("type", "string", "e.g. mothlight:lumen (substring match)")
                        .prop("name", "string", "Name contains (case-insensitive)").prop("limit", "integer", "Default 20").build(),
                a -> Result.text(Game.call(() -> {
                    LocalPlayer p = Game.mc().player;
                    List<Entity> found = new ArrayList<>();
                    for (Entity e : Game.mc().level.entitiesForRendering()) {
                        String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                        if (a.has("type") && !type.contains(a.str("type"))) {
                            continue;
                        }
                        if (a.has("name") && !e.getDisplayName().getString().toLowerCase(Locale.ROOT).contains(a.str("name").toLowerCase(Locale.ROOT))) {
                            continue;
                        }
                        found.add(e);
                    }
                    found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
                    StringBuilder sb = new StringBuilder(found.size() + " found\n");
                    found.stream().limit(a.i("limit", 20)).forEach(e -> sb.append(String.format(Locale.ROOT,
                            "id %d  %s \"%s\" at %.2f %.2f %.2f  dist %.1f%n", e.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()),
                            e.getDisplayName().getString(), e.getX(), e.getY(), e.getZ(), Math.sqrt(e.distanceToSqr(p)))));
                    return sb.toString();
                }))));

        s.register(new Tool("camera_lock", "Target lock: the camera holds its position (given x/y/z, or the current view) and "
                + "keeps the target framed as it moves. " + TARGET_DOC,
                targetSchema(new Schema()).prop("x", "number", "Camera X (optional)").prop("y", "number", "").prop("z", "number", "")
                        .prop("relative", "boolean", "x/y/z relative to the player").prop("fov", "number", "Degrees")
                        .prop("smoothing", "number", "0 = snap, 0.9 = very smooth (default 0.6)").build(),
                a -> started(new CameraProgram.Lock(posArg(a, currentView().pos()), target(a), (float) a.num("fov", 0),
                        (float) a.num("smoothing", 0.6)))));

        s.register(new Tool("camera_orbit", "Live orbit around a target (follows it if it moves) - 360 turntable views. "
                + TARGET_DOC,
                targetSchema(new Schema()).prop("radius", "number", "Default 4").prop("height", "number", "Above the aim point (default 1)")
                        .prop("speed", "number", "Degrees/second, negative = other way (default 30)")
                        .prop("start_angle", "number", "Degrees (default 0)").prop("sweep", "number", "Stop after N degrees (0 = endless)")
                        .prop("look_height", "number", "Aim offset above the target (default 0)").prop("fov", "number", "Degrees").build(),
                a -> started(new CameraProgram.Orbit(target(a), a.num("radius", 4), a.num("height", 1), a.num("speed", 30),
                        a.num("start_angle", 0), a.num("sweep", 0), (float) a.num("fov", 0), a.num("look_height", 0)))));

        s.register(new Tool("camera_pan", "Live pan: fixed position, the view sweeps horizontally (360 panorama pans).",
                new Schema().prop("x", "number", "Camera X (default: current view)").prop("y", "number", "").prop("z", "number", "")
                        .prop("relative", "boolean", "x/y/z relative to the player")
                        .prop("start_yaw", "number", "Degrees (default: current)").prop("speed", "number", "Degrees/second (default 20)")
                        .prop("sweep", "number", "Degrees then stop (default 360, 0 = endless)").prop("pitch", "number", "Degrees (default: current)")
                        .prop("fov", "number", "Degrees").build(),
                a -> {
                    Shot cur = currentView();
                    return started(new CameraProgram.Pan(posArg(a, cur.pos()), a.num("start_yaw", cur.yaw()), a.num("speed", 20),
                            a.num("sweep", 360), (float) a.num("pitch", cur.pitch()), (float) a.num("fov", 0)));
                }));

        s.register(new Tool("camera_rail", "Rail cam: glide along a saved path or keyframes (smooth spline). Optionally lock onto a "
                + "target while travelling. Modes: once, loop, pingpong.",
                new Schema().prop("path", "string", "Saved path name").prop("keyframes", "array", "Or inline keyframes [{x,y,z,yaw?,pitch?,look_at?,fov?,roll?}]")
                        .prop("seconds", "number", "Time for one pass (default 8)").prop("mode", "string", "once | loop | pingpong (default once)")
                        .prop("easing", "string", "smooth | linear (default smooth)").prop("target", "object", "Optional lock. " + TARGET_DOC).build(),
                a -> {
                    CameraPath path = a.has("path") ? loadPath(a.str("path")) : CameraPath.parse(a.arr("keyframes"));
                    Target lock = a.has("target") ? Target.parse(a.obj("target")) : null;
                    return started(new CameraProgram.Rail(path, a.num("seconds", 8), a.str("mode", "once"),
                            !"linear".equals(a.str("easing", "smooth")), lock));
                }));

        s.register(new Tool("camera_follow", "Chase cam: stays behind/above the target relative to its facing, smoothly. Great for "
                + "riding, flying and walking shots. " + TARGET_DOC,
                targetSchema(new Schema()).prop("distance", "number", "Behind, blocks (default 4)").prop("height", "number", "Above (default 1.5)")
                        .prop("side", "number", "Right (+) / left (-) offset (default 0)").prop("smoothing", "number", "0..0.95 (default 0.85)")
                        .prop("fov", "number", "Degrees").build(),
                a -> started(new CameraProgram.Follow(target(a), a.num("distance", 4), a.num("height", 1.5), a.num("side", 0),
                        (float) a.num("smoothing", 0.85), (float) a.num("fov", 0)))));

        s.register(new Tool("camera_dolly_zoom", "Vertigo / dolly zoom: the camera pushes toward (or pulls away from) the target while "
                + "the FOV changes so the subject stays the same size and the background warps. " + TARGET_DOC,
                targetSchema(new Schema()).prop("x", "number", "Start X (default: current view)").prop("y", "number", "").prop("z", "number", "")
                        .prop("end_distance", "number", "Blocks from the target at the end (default 2)")
                        .prop("seconds", "number", "Default 4").prop("fov", "number", "Starting FOV (default 30)").build(),
                a -> started(new CameraProgram.DollyZoom(posArg(a, currentView().pos()), target(a), a.num("end_distance", 2),
                        a.num("seconds", 4), (float) a.num("fov", 30)))));

        s.register(new Tool("capture_program", "Render the currently playing camera move (orbit/pan/rail/follow/dolly/lock) frame by "
                + "frame at an exact frame rate, then encode to MP4. The world is kept at its true speed (world=sync) so mobs move "
                + "naturally; world=step gives perfectly smooth motion; world=free is a timelapse look.",
                new Schema().prop("seconds", "number", "Length (default: the move's own duration, or 6)").prop("fps", "integer", "Default 30")
                        .prop("world", "string", Rendering.WORLD_DOC)
                        .prop("tick_step", "integer", "Legacy: >0 = world 'step'")
                        .prop("name", "string", "Output name").prop("gif", "boolean", "Also make a GIF").prop("hide_hud", "boolean", "default true").build(),
                a -> {
                    CameraProgram p = DirectorCamera.program;
                    if (p == null) {
                        throw new IllegalStateException("No camera move playing - start one with camera_orbit / camera_rail / ...");
                    }
                    int fps = a.i("fps", 30);
                    double secs = a.num("seconds", p.duration() > 0 ? p.duration() : 6);
                    int frames = Math.max(2, (int) Math.round(secs * fps));
                    String world = a.has("tick_step") && a.i("tick_step", 0) > 0 ? "step" : a.str("world", "sync");
                    Capture.lock();   // one capture at a time, pre-roll included (see Capture.lock)
                    try {
                        // pre-roll: park the move at t=0 and let a few frames render, so programs that restart on a
                        // time jump (drone physics) have reset before frame 0 is captured
                        DirectorCamera.manualTime = 0.0;
                        com.dreadedzombie.lensbridge.Stealth.begin();
                        try {
                            for (int k = 0; k < 3; k++) {
                                Capture.grabImage(4).close();
                            }
                        } finally {
                            com.dreadedzombie.lensbridge.Stealth.end();
                        }
                        return Rendering.render(Capture.safeName(a.str("name", "move_" + Capture.stamp())), fps, frames,
                                i -> DirectorCamera.manualTime = (double) i / fps, world, a.bool("hide_hud", true), a.bool("gif", false));
                    } finally {
                        DirectorCamera.manualTime = null;
                        Capture.unlock();
                    }

                }));

        // ------------------------------------------------------------------ paths
        s.register(new Tool("path_mark", "Build a rail interactively: append the current view (director camera, or where the "
                + "player is looking from) as a keyframe to a named path. Fly/walk, mark, repeat. Optional look_at makes that key aim at a point.",
                new Schema().req("name", "string", "Path name").prop("look_at", "array", "[x,y,z] aim point for this key")
                        .prop("fov", "number", "FOV for this key").prop("reset", "boolean", "Start the path over").build(),
                a -> {
                    Shot v = currentView();
                    JsonArray keys = new JsonArray();
                    Path f = pathsDir().resolve(clean(a.str("name")) + ".json");
                    if (!a.bool("reset", false) && Files.exists(f)) {
                        try {
                            keys = JsonParser.parseString(Files.readString(f)).getAsJsonArray();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    JsonObject k = new JsonObject();
                    k.addProperty("x", v.pos().x);
                    k.addProperty("y", v.pos().y);
                    k.addProperty("z", v.pos().z);
                    if (a.has("look_at")) {
                        k.add("look_at", a.arr("look_at"));
                    } else {
                        k.addProperty("yaw", v.yaw());
                        k.addProperty("pitch", v.pitch());
                    }
                    if (a.has("fov")) {
                        k.addProperty("fov", a.num("fov"));
                    }
                    keys.add(k);
                    savePath(a.str("name"), keys);
                    return Result.text("Path '" + a.str("name") + "' now has " + keys.size() + " keyframes");
                }));

        s.register(new Tool("path_save", "Save keyframes as a named path (for camera_rail / capture_path / path_show).",
                new Schema().req("name", "string", "").req("keyframes", "array", "[{x,y,z,yaw?,pitch?,look_at?,fov?,roll?}, ...]").build(),
                a -> {
                    CameraPath.parse(a.arr("keyframes"));     // validate
                    savePath(a.str("name"), a.arr("keyframes"));
                    return Result.text("Saved path '" + a.str("name") + "' (" + a.arr("keyframes").size() + " keyframes) to " + pathsDir());
                }));

        s.register(new Tool("path_list", "List saved paths with keyframe count and length.", new Schema().build(), a -> {
            try (Stream<Path> files = Files.list(pathsDir())) {
                StringBuilder sb = new StringBuilder("Paths in " + pathsDir() + ":\n");
                files.filter(f -> f.toString().endsWith(".json")).sorted().forEach(f -> {
                    String n = f.getFileName().toString().replace(".json", "");
                    try {
                        CameraPath p = loadPath(n);
                        sb.append(String.format(Locale.ROOT, "  %s: %d keys, %.1f blocks%n", n, p.keys().size(), p.length()));
                    } catch (RuntimeException e) {
                        sb.append("  ").append(n).append(": invalid (").append(e.getMessage()).append(")\n");
                    }
                });
                return Result.text(sb.toString());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));

        s.register(new Tool("path_get", "Return a saved path's keyframes as JSON (to edit and path_save back).",
                new Schema().req("name", "string", "").build(),
                a -> Result.text(new GsonBuilder().setPrettyPrinting().create().toJson(loadPath(a.str("name")).toJson()))));

        s.register(new Tool("path_delete", "Delete a saved path.", new Schema().req("name", "string", "").build(), a -> {
            try {
                Files.deleteIfExists(pathsDir().resolve(clean(a.str("name")) + ".json"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return Result.text("deleted");
        }));

        s.register(new Tool("path_show", "Preview a path in the world: draws the rail with particles (only on this client) for a few "
                + "seconds, with the keyframes marked. Take a screenshot to check the line before rendering.",
                new Schema().req("name", "string", "Saved path name").prop("seconds", "number", "Default 10").build(),
                a -> {
                    shownPath = loadPath(a.str("name"));
                    showUntil = System.currentTimeMillis() + (long) (a.num("seconds", 10) * 1000);
                    return Result.text(String.format(Locale.ROOT, "Showing '%s' (%.1f blocks) for %.0fs", a.str("name"),
                            shownPath.length(), a.num("seconds", 10)));
                }));
    }

    /** Draw the previewed path with client-side particles. */
    public static void onTick(ClientTickEvent.Post event) {
        CameraPath p = shownPath;
        if (p == null || Game.mc().level == null) {
            return;
        }
        if (System.currentTimeMillis() > showUntil) {
            shownPath = null;
            return;
        }
        if (Game.mc().level.getGameTime() % 4 != 0) {
            return;
        }
        for (int i = 0; i <= 80; i++) {
            Vec3 v = p.position(i / 80.0);
            Game.mc().level.addParticle(ParticleTypes.END_ROD, v.x, v.y, v.z, 0, 0, 0);
        }
        for (CameraPath.Key k : p.keys()) {
            Game.mc().level.addParticle(ParticleTypes.HAPPY_VILLAGER, k.pos().x, k.pos().y + 0.2, k.pos().z, 0, 0, 0);
        }
    }
}
