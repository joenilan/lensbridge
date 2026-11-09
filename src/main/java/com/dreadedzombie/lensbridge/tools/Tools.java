package com.dreadedzombie.lensbridge.tools;

import com.dreadedzombie.lensbridge.Capture;
import com.dreadedzombie.lensbridge.ChatLog;
import com.dreadedzombie.lensbridge.DirectorCamera;
import com.dreadedzombie.lensbridge.Game;
import com.dreadedzombie.lensbridge.InputDriver;
import com.dreadedzombie.lensbridge.Lensbridge;
import com.dreadedzombie.lensbridge.mcp.BridgeServer;
import com.dreadedzombie.lensbridge.mcp.Tool;
import com.dreadedzombie.lensbridge.mcp.Tool.Args;
import com.dreadedzombie.lensbridge.mcp.Tool.Result;
import com.dreadedzombie.lensbridge.mcp.Tool.Schema;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Every bridge tool. Handlers run on HTTP threads and hop to the client thread via {@link Game}. */
public final class Tools {
    private Tools() {
    }

    private static Minecraft mc() {
        return Game.mc();
    }

    private static LocalPlayer player() {
        LocalPlayer p = mc().player;
        if (p == null) {
            throw new IllegalStateException("Not in a world (join a world or server first)");
        }
        return p;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String vec(Vec3 v) {
        return fmt(v.x) + " " + fmt(v.y) + " " + fmt(v.z);
    }

    private static int previewEdge() {
        return Lensbridge.config().previewMaxEdge;
    }

    public static void registerAll(BridgeServer s) {
        // ================================================================== observe
        s.register(new Tool("status", "Game state: in-world?, open screen, player position/rotation/dimension/health/"
                + "gamemode, time, weather, FPS, window size, server, director camera, capture folder.",
                new Schema().build(), a -> Result.text(Game.call(Tools::statusText))));

        s.register(new Tool("screenshot", "Capture the game view (the game's own framebuffer only - never the desktop). "
                + "Saves a full-size PNG and returns a preview image. Use it to SEE what is happening.",
                new Schema().prop("hide_hud", "boolean", "Hide HUD/hand for a clean shot (default false)")
                        .prop("name", "string", "File name (without .png); default is a timestamp")
                        .prop("settle_frames", "integer", "Frames to wait before capture (default 3)")
                        .prop("preview_size", "integer", "Longest edge of the returned preview (default from config)").build(),
                a -> {
                    Path file = Capture.dir("screenshots").resolve(a.str("name", "shot_" + Capture.stamp()) + ".png");
                    Capture.grabClean(file, a.bool("hide_hud", false), a.i("settle_frames", 3));
                    return Result.text("Saved " + file).addImage(file, a.i("preview_size", previewEdge()));
                }));

        s.register(new Tool("inspect", "What the player is looking at (block or entity), nearby entities with positions "
                + "and names, held items and hotbar.",
                new Schema().prop("radius", "number", "Entity search radius (default 12)")
                        .prop("max_entities", "integer", "Max entities listed (default 30)").build(),
                a -> Result.text(Game.call(() -> inspectText(a.num("radius", 12), a.i("max_entities", 30))))));

        s.register(new Tool("chat_log", "Chat and system messages the client received (command feedback included). "
                + "Pass 'since' = last id you saw to get only new lines.",
                new Schema().prop("since", "integer", "Only lines with id greater than this (default 0)").build(),
                a -> {
                    StringBuilder sb = new StringBuilder();
                    for (ChatLog.Line line : ChatLog.since(a.i("since", 0))) {
                        sb.append('#').append(line.id()).append(line.system() ? " [sys] " : " ").append(line.text()).append('\n');
                    }
                    return Result.text(sb.isEmpty() ? "(no new messages)" : sb.toString());
                }));

        s.register(new Tool("wait", "Wait for game ticks / frames / milliseconds (let things happen before the next screenshot).",
                new Schema().prop("ticks", "integer", "Game ticks (20 per second)")
                        .prop("frames", "integer", "Rendered frames")
                        .prop("millis", "integer", "Milliseconds").build(),
                a -> {
                    if (a.has("frames")) {
                        Game.waitFrames(a.i("frames", 1));
                    }
                    if (a.has("ticks")) {
                        Game.sleep(a.i("ticks", 0) * 50L);
                    }
                    if (a.has("millis")) {
                        Game.sleep(a.i("millis", 0));
                    }
                    return Result.text("waited");
                }));

        // ================================================================== act
        s.register(new Tool("command", "Run a command as the player (no leading slash needed). Needs op for most commands. "
                + "Feedback appears in chat_log.",
                new Schema().req("command", "string", "e.g. 'time set midnight'").build(),
                a -> {
                    String cmd = a.str("command").replaceFirst("^/", "");
                    long before = ChatLog.since(Long.MAX_VALUE - 1).size();
                    Game.run(() -> player().connection.sendCommand(cmd));
                    Game.sleep(250);
                    return Result.text("Sent /" + cmd + " (check chat_log for feedback)");
                }));

        s.register(new Tool("chat", "Send a chat message as the player.",
                new Schema().req("message", "string", "Message text").build(),
                a -> {
                    Game.run(() -> player().connection.sendChat(a.str("message")));
                    return Result.text("sent");
                }));

        s.register(new Tool("look", "Turn the player's head: absolute yaw/pitch, or look at a world position.",
                new Schema().prop("yaw", "number", "Degrees (0 = south/+Z, 90 = west, 180 = north, -90 = east)")
                        .prop("pitch", "number", "Degrees (-90 up .. 90 down)")
                        .prop("x", "number", "Look-at target X").prop("y", "number", "Look-at target Y")
                        .prop("z", "number", "Look-at target Z").build(),
                a -> {
                    String msg = Game.call(() -> {
                        LocalPlayer p = player();
                        float yaw;
                        float pitch;
                        if (a.has("x")) {
                            float[] yp = DirectorCamera.lookAt(p.getEyePosition(), new Vec3(a.num("x"), a.num("y"), a.num("z")));
                            yaw = yp[0];
                            pitch = yp[1];
                        } else {
                            yaw = (float) a.num("yaw", p.getYRot());
                            pitch = (float) a.num("pitch", p.getXRot());
                        }
                        p.setYRot(yaw);
                        p.setXRot(pitch);
                        p.setYHeadRot(yaw);
                        p.yRotO = yaw;
                        p.xRotO = pitch;
                        return "Looking yaw " + fmt(yaw) + " pitch " + fmt(pitch);
                    });
                    return Result.text(msg);
                }));

        s.register(new Tool("hold_keys", "Hold movement/action keys for a number of ticks (20 = 1 s), e.g. "
                + "{\"keys\":{\"forward\":40,\"jump\":5}}. Keys: forward back left right jump sneak sprint use attack drop.",
                new Schema().req("keys", "object", "Map of key -> ticks to hold (0 releases)")
                        .prop("wait", "boolean", "Block until all keys are released (default true)").build(),
                a -> {
                    JsonObject keys = a.obj("keys");
                    Game.run(() -> {
                        for (var e : keys.entrySet()) {
                            InputDriver.hold(e.getKey(), e.getValue().getAsInt());
                        }
                    });
                    if (a.bool("wait", true)) {
                        for (int i = 0; i < 600 && Game.call(InputDriver::busy); i++) {
                            Game.sleep(50);
                        }
                    }
                    return Result.text("Done. Player at " + Game.call(() -> vec(player().position())));
                }));

        s.register(new Tool("press", "Tap a key once (a click): use, attack, drop, inventory, swap_offhand, pick_block, jump.",
                new Schema().req("key", "string", "Key name").build(),
                a -> {
                    Game.run(() -> InputDriver.tap(a.str("key")));
                    Game.waitFrames(2);
                    return Result.text("pressed " + a.str("key"));
                }));

        s.register(new Tool("hotbar", "Select a hotbar slot (1-9) and report what is held.",
                new Schema().req("slot", "integer", "1-9").build(),
                a -> Result.text(Game.call(() -> {
                    int slot = Math.max(1, Math.min(9, a.i("slot", 1)));
                    player().getInventory().setSelectedSlot(slot - 1);
                    return "Holding " + describe(player().getMainHandItem());
                }))));

        s.register(new Tool("close_screen", "Close any open GUI/screen (inventory, menu, chat...).",
                new Schema().build(), a -> {
                    Game.run(() -> mc().setScreen(null));
                    return Result.text("closed");
                }));

        s.register(new Tool("release_inputs", "Release every held key and return the camera to the player.",
                new Schema().build(), a -> {
                    Game.run(() -> {
                        InputDriver.releaseAll();
                        DirectorCamera.release();
                    });
                    return Result.text("released");
                }));

        // ================================================================== director / staging
        s.register(new Tool("camera_set", "Director camera: place a free camera (independent of the player; the player is "
                + "drawn too). Stay within the player's loaded chunks. Use camera_release to return to normal.",
                new Schema().req("x", "number", "").req("y", "number", "").req("z", "number", "")
                        .prop("yaw", "number", "Degrees (0 = south/+Z)").prop("pitch", "number", "Degrees (+ = down)")
                        .prop("roll", "number", "Degrees (Dutch angle)").prop("fov", "number", "Degrees (default: player setting)")
                        .prop("relative", "boolean", "x/y/z are offsets from the player (default false)").build(),
                a -> {
                    Vec3 base = a.bool("relative", false) ? Game.call(() -> player().position()) : Vec3.ZERO;
                    Vec3 p = base.add(a.num("x"), a.num("y"), a.num("z"));
                    DirectorCamera.set(p, (float) a.num("yaw", 0), (float) a.num("pitch", 0), (float) a.num("roll", 0),
                            (float) a.num("fov", 0));
                    Game.waitFrames(2);
                    return Result.text("Camera at " + vec(p));
                }));

        s.register(new Tool("camera_look_at", "Director camera at a position, aimed at a target point (great for framing a subject).",
                new Schema().req("x", "number", "camera X").req("y", "number", "camera Y").req("z", "number", "camera Z")
                        .req("tx", "number", "target X").req("ty", "number", "target Y").req("tz", "number", "target Z")
                        .prop("fov", "number", "Degrees").prop("roll", "number", "Degrees").build(),
                a -> {
                    Vec3 cam = new Vec3(a.num("x"), a.num("y"), a.num("z"));
                    float[] yp = DirectorCamera.lookAt(cam, new Vec3(a.num("tx"), a.num("ty"), a.num("tz")));
                    DirectorCamera.set(cam, yp[0], yp[1], (float) a.num("roll", 0), (float) a.num("fov", 0));
                    Game.waitFrames(2);
                    return Result.text("Camera at " + vec(cam) + " yaw " + fmt(yp[0]) + " pitch " + fmt(yp[1]));
                }));

        s.register(new Tool("camera_release", "Return to the normal player camera.", new Schema().build(), a -> {
            DirectorCamera.release();
            return Result.text("camera released");
        }));

        s.register(new Tool("hud", "Show or hide the HUD (like F1).",
                new Schema().req("visible", "boolean", "true = show").build(), a -> {
                    Game.run(() -> mc().options.hideGui = !a.bool("visible", true));
                    return Result.text("HUD " + (a.bool("visible", true) ? "shown" : "hidden"));
                }));

        s.register(new Tool("window_size", "Resize the game window (e.g. 1920x1080 for promo captures). Captures use the window resolution.",
                new Schema().req("width", "integer", "").req("height", "integer", "").build(), a -> {
                    Game.run(() -> mc().getWindow().setWindowed(a.i("width", 1280), a.i("height", 720)));
                    Game.waitFrames(5);
                    return Result.text("Window " + Game.call(() -> mc().getWindow().getWidth() + "x" + mc().getWindow().getHeight()));
                }));

        s.register(new Tool("scene", "Stage a scene with one call (sends commands; needs op): time, weather, freeze the "
                + "world (/tick freeze) so everything holds still for shots, step frozen ticks, clear the weather/time cycle.",
                new Schema().prop("time", "string", "day | noon | sunset | night | midnight | sunrise | or a tick number")
                        .prop("weather", "string", "clear | rain | thunder")
                        .prop("freeze", "boolean", "true = /tick freeze, false = /tick unfreeze")
                        .prop("step", "integer", "Advance a frozen world by N ticks")
                        .prop("lock_time", "boolean", "Stop the day cycle (gamerule)").build(),
                a -> {
                    List<String> cmds = new ArrayList<>();
                    if (a.has("time")) {
                        cmds.add("time set " + a.str("time"));
                    }
                    if (a.has("weather")) {
                        cmds.add("weather " + a.str("weather"));
                    }
                    if (a.has("lock_time")) {
                        cmds.add("gamerule advance_time " + !a.bool("lock_time", true));
                    }
                    if (a.has("freeze")) {
                        cmds.add(a.bool("freeze", true) ? "tick freeze" : "tick unfreeze");
                    }
                    if (a.has("step")) {
                        cmds.add("tick step " + a.i("step", 1));
                    }
                    for (String c : cmds) {
                        Game.run(() -> player().connection.sendCommand(c));
                        Game.sleep(150);
                    }
                    return Result.text("Sent: " + String.join(" ; ", cmds) + " (see chat_log)");
                }));

        // ================================================================== capture (promo)
        s.register(new Tool("capture_orbit", "Turntable shot: the director camera circles a point and every frame is saved, "
                + "then encoded to MP4 (and optional GIF) with ffmpeg. Returns a contact sheet preview. Tip: scene freeze first.",
                new Schema().req("cx", "number", "Centre X").req("cy", "number", "Centre Y").req("cz", "number", "Centre Z")
                        .prop("radius", "number", "Distance (default 4)").prop("height", "number", "Camera height above centre (default 1.5)")
                        .prop("frames", "integer", "Frame count (default 120)").prop("fps", "integer", "Playback fps (default 30)")
                        .prop("start_angle", "number", "Degrees (default 0)").prop("sweep", "number", "Degrees to travel (default 360)")
                        .prop("fov", "number", "Degrees").prop("name", "string", "Output name")
                        .prop("gif", "boolean", "Also make a GIF").prop("hide_hud", "boolean", "default true").build(),
                a -> {
                    Vec3 c = new Vec3(a.num("cx"), a.num("cy"), a.num("cz"));
                    double r = a.num("radius", 4);
                    double h = a.num("height", 1.5);
                    int frames = a.i("frames", 120);
                    double start = Math.toRadians(a.num("start_angle", 0));
                    double sweep = Math.toRadians(a.num("sweep", 360));
                    float fov = (float) a.num("fov", 0);
                    List<Shot> shots = new ArrayList<>();
                    for (int i = 0; i < frames; i++) {
                        double ang = start + sweep * i / frames;
                        Vec3 cam = c.add(Math.sin(ang) * r, h, Math.cos(ang) * r);
                        float[] yp = DirectorCamera.lookAt(cam, c);
                        shots.add(new Shot(cam, yp[0], yp[1], 0, fov));
                    }
                    return render(shots, a.str("name", "orbit_" + Capture.stamp()), a.i("fps", 30), a.bool("gif", false),
                            a.bool("hide_hud", true));
                }));

        s.register(new Tool("capture_path", "Cinematic camera move through keyframes, saved frame by frame and encoded to MP4. "
                + "Keyframes: [{x,y,z, yaw?,pitch?, look_at?:[x,y,z], fov?, roll?}, ...] (at least 2). Smooth Catmull-Rom "
                + "path; angles from look_at if given.",
                new Schema().req("keyframes", "array", "List of keyframe objects")
                        .prop("seconds", "number", "Duration (default 6)").prop("fps", "integer", "Default 30")
                        .prop("easing", "string", "smooth (ease in/out, default) | linear")
                        .prop("name", "string", "Output name").prop("gif", "boolean", "Also make a GIF")
                        .prop("hide_hud", "boolean", "default true").build(),
                a -> {
                    List<Key> keys = new ArrayList<>();
                    for (JsonElement el : a.arr("keyframes")) {
                        keys.add(Key.parse(el.getAsJsonObject()));
                    }
                    if (keys.size() < 2) {
                        throw new IllegalArgumentException("Need at least 2 keyframes");
                    }
                    int fps = a.i("fps", 30);
                    int frames = Math.max(2, (int) Math.round(a.num("seconds", 6) * fps));
                    boolean smooth = !"linear".equals(a.str("easing", "smooth"));
                    List<Shot> shots = new ArrayList<>();
                    for (int i = 0; i < frames; i++) {
                        double t = (double) i / (frames - 1);
                        if (smooth) {
                            t = t * t * (3 - 2 * t);
                        }
                        shots.add(Key.sample(keys, t));
                    }
                    return render(shots, a.str("name", "path_" + Capture.stamp()), fps, a.bool("gif", false), a.bool("hide_hud", true));
                }));

        s.register(new Tool("record", "Record the live view (player camera or director camera) in real time for N seconds "
                + "and encode to MP4 - for gameplay clips (riding, swarms at a lantern...).",
                new Schema().prop("seconds", "number", "Default 5").prop("fps", "integer", "Default 20")
                        .prop("name", "string", "Output name").prop("hide_hud", "boolean", "default false")
                        .prop("gif", "boolean", "Also make a GIF").build(),
                a -> {
                    int fps = a.i("fps", 20);
                    int frames = (int) Math.round(a.num("seconds", 5) * fps);
                    String name = a.str("name", "clip_" + Capture.stamp());
                    Path dir = Capture.dir("frames").resolve(name);
                    boolean hide = a.bool("hide_hud", false);
                    boolean before = Game.call(() -> mc().options.hideGui);
                    Game.run(() -> mc().options.hideGui = hide);
                    List<Path> files = new ArrayList<>();
                    try {
                        long t0 = System.nanoTime();
                        for (int i = 0; i < frames; i++) {
                            long due = t0 + (long) (i * 1e9 / fps);
                            long now = System.nanoTime();
                            if (due > now) {
                                Game.sleep((due - now) / 1_000_000L);
                            }
                            files.add(Capture.grab(dir.resolve(String.format(Locale.ROOT, "frame_%05d.png", i)), 1));
                        }
                    } finally {
                        Game.run(() -> mc().options.hideGui = before);
                    }
                    return finish(files, dir, name, fps, a.bool("gif", false));
                }));

        s.register(new Tool("list_captures", "List recent captures (screenshots, videos, frame folders) with full paths.",
                new Schema().prop("limit", "integer", "Default 20").build(),
                a -> {
                    Path root = Capture.dir("");
                    try (Stream<Path> files = Files.walk(root, 2)) {
                        List<Path> list = files.filter(p -> !Files.isDirectory(p) && !p.getFileName().toString().startsWith("frame_"))
                                .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                                .limit(a.i("limit", 20)).toList();
                        StringBuilder sb = new StringBuilder("Capture folder: " + root + "\n");
                        list.forEach(p -> sb.append(p).append('\n'));
                        return Result.text(sb.toString());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
    }

    // ------------------------------------------------------------------ capture helpers

    /** One camera placement. */
    record Shot(Vec3 pos, float yaw, float pitch, float roll, float fov) {
    }

    /** A camera-path keyframe. */
    record Key(Vec3 pos, Float yaw, Float pitch, Vec3 lookAt, float fov, float roll) {
        static Key parse(JsonObject o) {
            Vec3 p = new Vec3(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble());
            Vec3 look = null;
            if (o.has("look_at")) {
                JsonArray l = o.getAsJsonArray("look_at");
                look = new Vec3(l.get(0).getAsDouble(), l.get(1).getAsDouble(), l.get(2).getAsDouble());
            }
            return new Key(p, o.has("yaw") ? o.get("yaw").getAsFloat() : null, o.has("pitch") ? o.get("pitch").getAsFloat() : null,
                    look, o.has("fov") ? o.get("fov").getAsFloat() : 0, o.has("roll") ? o.get("roll").getAsFloat() : 0);
        }

        float[] angles() {
            if (this.lookAt != null) {
                return DirectorCamera.lookAt(this.pos, this.lookAt);
            }
            return new float[]{this.yaw == null ? 0 : this.yaw, this.pitch == null ? 0 : this.pitch};
        }

        /** Catmull-Rom position through the keys; angles/fov interpolated between the surrounding keys. */
        static Shot sample(List<Key> keys, double t) {
            int n = keys.size() - 1;
            double f = t * n;
            int i = Math.min(n - 1, (int) Math.floor(f));
            double u = f - i;
            Key k0 = keys.get(Math.max(0, i - 1));
            Key k1 = keys.get(i);
            Key k2 = keys.get(i + 1);
            Key k3 = keys.get(Math.min(n, i + 2));
            Vec3 pos = catmull(k0.pos, k1.pos, k2.pos, k3.pos, u);
            float yaw;
            float pitch;
            if (k1.lookAt != null && k2.lookAt != null) {
                Vec3 look = k1.lookAt.lerp(k2.lookAt, u);
                float[] yp = DirectorCamera.lookAt(pos, look);
                yaw = yp[0];
                pitch = yp[1];
            } else {
                float[] a1 = k1.angles();
                float[] a2 = k2.angles();
                float dy = ((a2[0] - a1[0]) % 360 + 540) % 360 - 180;    // shortest way round
                yaw = (float) (a1[0] + dy * u);
                pitch = (float) (a1[1] + (a2[1] - a1[1]) * u);
            }
            float fov = (float) (k1.fov + (k2.fov - k1.fov) * u);
            float roll = (float) (k1.roll + (k2.roll - k1.roll) * u);
            return new Shot(pos, yaw, pitch, roll, fov);
        }

        static Vec3 catmull(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
            double t2 = t * t;
            double t3 = t2 * t;
            return p1.scale(2).add(p2.subtract(p0).scale(t)).add(p0.scale(2).subtract(p1.scale(5)).add(p2.scale(4)).subtract(p3).scale(t2))
                    .add(p3.subtract(p0).add(p1.scale(3)).subtract(p2.scale(3)).scale(t3)).scale(0.5);
        }
    }

    /** Render a list of director-camera shots to numbered frames, then encode. */
    private static Result render(List<Shot> shots, String name, int fps, boolean gif, boolean hideHud) {
        Path dir = Capture.dir("frames").resolve(name);
        boolean wasActive = DirectorCamera.active;
        boolean hudBefore = Game.call(() -> mc().options.hideGui);
        Game.run(() -> mc().options.hideGui = hideHud);
        List<Path> files = new ArrayList<>();
        try {
            for (int i = 0; i < shots.size(); i++) {
                Shot s = shots.get(i);
                DirectorCamera.set(s.pos(), s.yaw(), s.pitch(), s.roll(), s.fov());
                files.add(Capture.grab(dir.resolve(String.format(Locale.ROOT, "frame_%05d.png", i)), i == 0 ? 6 : 1));
            }
        } finally {
            Game.run(() -> mc().options.hideGui = hudBefore);
            if (!wasActive) {
                DirectorCamera.release();
            }
        }
        return finish(files, dir, name, fps, gif);
    }

    private static Result finish(List<Path> files, Path dir, String name, int fps, boolean gif) {
        Path videos = Capture.dir("videos");
        StringBuilder sb = new StringBuilder(files.size() + " frames in " + dir + "\n");
        try {
            Path mp4 = Capture.encode(dir, fps, videos.resolve(name + ".mp4"), false);
            sb.append("Video: ").append(mp4).append('\n');
            if (gif) {
                sb.append("GIF: ").append(Capture.encode(dir, fps, videos.resolve(name + ".gif"), true)).append('\n');
            }
        } catch (RuntimeException e) {
            sb.append("Encoding skipped: ").append(e.getMessage()).append('\n');
        }
        Path sheet = Capture.contactSheet(files, 9, videos.resolve(name + "_sheet.png"));
        sb.append("Contact sheet: ").append(sheet);
        return Result.text(sb.toString()).addImage(sheet, previewEdge());
    }

    // ------------------------------------------------------------------ text helpers

    private static String describe(ItemStack stack) {
        if (stack.isEmpty()) {
            return "nothing";
        }
        return stack.getCount() + "x " + BuiltInRegistries.ITEM.getKey(stack.getItem()) + " (\"" + stack.getHoverName().getString() + "\")";
    }

    private static String statusText() {
        Minecraft mc = mc();
        StringBuilder sb = new StringBuilder();
        sb.append("Lensbridge ").append(Lensbridge.VERSION).append(" | FPS ").append(mc.getFps())
                .append(" | window ").append(mc.getWindow().getWidth()).append('x').append(mc.getWindow().getHeight()).append('\n');
        sb.append("Screen: ").append(mc.screen == null ? "none (in game)" : mc.screen.getClass().getSimpleName()).append('\n');
        if (mc.player == null || mc.level == null) {
            return sb.append("Not in a world.").toString();
        }
        LocalPlayer p = mc.player;
        sb.append("Server: ").append(mc.getCurrentServer() != null ? mc.getCurrentServer().ip : (mc.hasSingleplayerServer() ? "singleplayer" : "?")).append('\n');
        sb.append("Player: ").append(p.getName().getString()).append(" at ").append(vec(p.position()))
                .append(" yaw ").append(fmt(p.getYRot())).append(" pitch ").append(fmt(p.getXRot()))
                .append(" in ").append(mc.level.dimension().identifier()).append('\n');
        sb.append("Health ").append(fmt(p.getHealth())).append('/').append(fmt(p.getMaxHealth()))
                .append(" food ").append(p.getFoodData().getFoodLevel())
                .append(" mode ").append(mc.gameMode != null ? mc.gameMode.getPlayerMode().getName() : "?")
                .append(" riding ").append(p.getVehicle() == null ? "nothing" : BuiltInRegistries.ENTITY_TYPE.getKey(p.getVehicle().getType()))
                .append('\n');
        long day = mc.level.getDayTime() % 24000L;
        sb.append("Time ").append(day).append(" (").append(day >= 13000 && day < 23000 ? "night" : "day").append(")")
                .append(" weather ").append(mc.level.isThundering() ? "thunder" : mc.level.isRaining() ? "rain" : "clear").append('\n');
        sb.append("Holding ").append(describe(p.getMainHandItem())).append('\n');
        sb.append("Director camera: ").append(DirectorCamera.active ? "ON at " + vec(DirectorCamera.pos) : "off")
                .append(" | HUD ").append(mc.options.hideGui ? "hidden" : "shown").append('\n');
        sb.append("Captures: ").append(mc.gameDirectory.toPath().resolve(Lensbridge.config().captureDir).toAbsolutePath());
        return sb.toString();
    }

    private static String inspectText(double radius, int max) {
        Minecraft mc = mc();
        LocalPlayer p = player();
        StringBuilder sb = new StringBuilder();
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = bh.getBlockPos();
            sb.append("Looking at block ").append(mc.level.getBlockState(pos)).append(" at ").append(pos.toShortString())
                    .append(" face ").append(bh.getDirection()).append('\n');
        } else if (hit instanceof EntityHitResult eh) {
            sb.append("Looking at entity ").append(entityLine(eh.getEntity(), p)).append('\n');
        } else {
            sb.append("Looking at nothing (sky/air)\n");
        }
        sb.append("Main hand: ").append(describe(p.getMainHandItem())).append(" | off hand: ").append(describe(p.getOffhandItem())).append('\n');
        sb.append("Hotbar: ");
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getItem(i);
            sb.append(i + 1).append('=').append(s.isEmpty() ? "-" : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath() + "x" + s.getCount()).append(' ');
        }
        sb.append('\n');
        List<Entity> near = new ArrayList<>(mc.level.getEntities(p, p.getBoundingBox().inflate(radius)));
        near.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        sb.append("Entities within ").append(fmt(radius)).append(" (").append(near.size()).append("):\n");
        near.stream().limit(max).forEach(e -> sb.append("  ").append(entityLine(e, p)).append('\n'));
        return sb.toString();
    }

    private static String entityLine(Entity e, LocalPlayer p) {
        StringBuilder sb = new StringBuilder();
        sb.append(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())).append(" \"").append(e.getDisplayName().getString()).append('"')
                .append(" at ").append(vec(e.position())).append(" dist ").append(fmt(Math.sqrt(e.distanceToSqr(p))));
        if (e instanceof LivingEntity le) {
            sb.append(" hp ").append(fmt(le.getHealth()));
        }
        if (e.getVehicle() != null) {
            sb.append(" riding ").append(BuiltInRegistries.ENTITY_TYPE.getKey(e.getVehicle().getType()));
        }
        sb.append(" id ").append(e.getId());
        return sb.toString();
    }
}
