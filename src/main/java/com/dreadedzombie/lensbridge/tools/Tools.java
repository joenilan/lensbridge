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
import com.dreadedzombie.lensbridge.cinema.CameraPath;
import com.dreadedzombie.lensbridge.cinema.Shot;
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
                + "{\"keys\":{\"forward\":40,\"jump\":5}}. Keys: forward back left right jump sneak sprint use attack drop, or any key mapping id (e.g. key.mothlight.glide_drop).",
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

        s.register(new Tool("press", "Tap a key once (a click): use, attack, drop, inventory, swap_offhand, pick_block, jump, or any key mapping id (e.g. key.lensbridge.drone).",
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

        s.register(new Tool("screen_info", "The open GUI screen (if any): its class, the GUI scale and the window size, for screen_click.",
                new Schema().build(),
                a -> Result.text(Game.call(() -> {
                    var mc = Game.mc();
                    var w = mc.getWindow();
                    return (mc.screen == null ? "no screen open" : "screen " + mc.screen.getClass().getName())
                            + " | window " + w.getWidth() + "x" + w.getHeight() + " | gui scale " + w.getGuiScale();
                }))));

        s.register(new Tool("screen_click", "Click inside the open GUI screen (inventory, a mod's book or menu...) at window "
                + "pixel x,y, the same coordinates as in a screenshot of the game. For testing mod screens.",
                new Schema().req("x", "number", "Window pixels from the left").req("y", "number", "Window pixels from the top")
                        .prop("button", "integer", "0 left (default), 1 right").build(),
                a -> Result.text(Game.call(() -> {
                    var mc = Game.mc();
                    if (mc.screen == null) {
                        throw new IllegalStateException("No screen open");
                    }
                    double scale = mc.getWindow().getGuiScale();
                    double gx = a.num("x") / scale;
                    double gy = a.num("y") / scale;
                    var ev = new net.minecraft.client.input.MouseButtonEvent(gx, gy,
                            new net.minecraft.client.input.MouseButtonInfo(a.i("button", 0), 0));
                    var screen = mc.screen;
                    boolean used = screen.mouseClicked(ev, false);
                    if (mc.screen == screen) {   // the click may have closed or replaced the screen
                        screen.mouseReleased(ev);
                    }
                    return String.format(java.util.Locale.ROOT, "clicked %.0f,%.0f (gui %.1f,%.1f) on %s: %s", a.num("x"), a.num("y"),
                            gx, gy, mc.screen == null ? "(closed)" : mc.screen.getClass().getSimpleName(), used ? "handled" : "nothing there");
                }))));

        s.register(new Tool("screen_scroll", "Scroll the mouse wheel over the open GUI screen at window pixel x,y.",
                new Schema().req("x", "number", "").req("y", "number", "").prop("amount", "number", "Positive = up (default -1, down)").build(),
                a -> Result.text(Game.call(() -> {
                    var mc = Game.mc();
                    if (mc.screen == null) {
                        throw new IllegalStateException("No screen open");
                    }
                    double scale = mc.getWindow().getGuiScale();
                    boolean used = mc.screen.mouseScrolled(a.num("x") / scale, a.num("y") / scale, 0, a.num("amount", -1));
                    return used ? "scrolled" : "nothing scrolled";
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

        s.register(new Tool("camera_preview", "Show the director camera live on the player's screen (on), or keep it hidden so "
                + "captures never disturb the player's view (off, default).",
                new Schema().req("on", "boolean", "").build(), a -> {
                    DirectorCamera.preview = a.bool("on", false);
                    return Result.text("Camera preview " + (DirectorCamera.preview ? "ON - the player sees the camera" : "OFF - captures are invisible"));
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
                new Schema().prop("look", "string", "Lighting preset: qa (shaders off, morning light, clear - honest neutral look), "
                        + "noon, golden (low warm sun), sunset, blue_hour, night, dawn, overcast")
                        .prop("shaders", "boolean", "Iris shaders on/off (if Iris is installed)")
                        .prop("time", "string", "day | noon | sunset | night | midnight | sunrise | or a tick number")
                        .prop("weather", "string", "clear | rain | thunder")
                        .prop("freeze", "boolean", "true = /tick freeze, false = /tick unfreeze")
                        .prop("step", "integer", "Advance a frozen world by N ticks")
                        .prop("lock_time", "boolean", "Stop the day cycle (gamerule)").build(),
                a -> {
                    List<String> cmds = new ArrayList<>();
                    StringBuilder extra = new StringBuilder();
                    if (a.has("look")) {
                        String[] preset = switch (a.str("look")) {
                            case "qa" -> new String[]{"2500", "clear", "off"};
                            case "noon" -> new String[]{"6000", "clear", null};
                            case "golden" -> new String[]{"11800", "clear", null};
                            case "sunset" -> new String[]{"12600", "clear", null};
                            case "blue_hour" -> new String[]{"13300", "clear", null};
                            case "night" -> new String[]{"18000", "clear", null};
                            case "dawn" -> new String[]{"23300", "clear", null};
                            case "overcast" -> new String[]{"4000", "rain", null};
                            default -> throw new IllegalArgumentException("Unknown look " + a.str("look"));
                        };
                        cmds.add("time set " + preset[0]);
                        cmds.add("weather " + preset[1]);
                        cmds.add("gamerule advance_time false");
                        if (preset[2] != null && !a.has("shaders")) {
                            extra.append(Game.call(() -> com.dreadedzombie.lensbridge.Shaders.set(false))).append("; ");
                        }
                    }
                    if (a.has("shaders")) {
                        extra.append(Game.call(() -> com.dreadedzombie.lensbridge.Shaders.set(a.bool("shaders", true)))).append("; ");
                    }
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
                    if (extra.indexOf("shaders O") >= 0) {
                        // switching shaders rebuilds all terrain; shots taken before it finishes are foggy and half-empty
                        Game.waitFrames(5);
                        extra.append(Game.waitTerrain(1500, 15000)).append("; ");
                    }
                    return Result.text(extra + "Sent: " + String.join(" ; ", cmds) + " (see chat_log)");
                }));

        // ================================================================== capture (promo)
        s.register(new Tool("capture_orbit", "Turntable shot: the director camera circles a point (or a target entity's centre) and "
                + "every frame is saved, then encoded to MP4 (and optional GIF) with ffmpeg. Returns a contact sheet preview. "
                + "Tip: scene freeze first; world=step + fps 60 for the smoothest turntables.",
                new Schema().prop("cx", "number", "Centre X (or use target)").prop("cy", "number", "Centre Y").prop("cz", "number", "Centre Z")
                        .prop("target", "object", "Centre on a target instead: {entity:id} / {nearest:type}; centre = middle of its "
                                + "hitbox, plus optional offset [dx,dy,dz]")
                        .prop("auto_center", "boolean", "With an entity target (default true): measure where the MODEL really is "
                                + "(two probe views, with/without the entity) and centre on it; radius defaults to fit the model")
                        .prop("radius", "number", "Distance (default 4)").prop("height", "number", "Camera height above centre (default 1.5)")
                        .prop("frames", "integer", "Frame count (default 120)").prop("fps", "integer", "Playback fps (default 30)")
                        .prop("start_angle", "number", "Degrees (default 0)").prop("sweep", "number", "Degrees to travel (default 360)")
                        .prop("fov", "number", "Degrees").prop("name", "string", "Output name")
                        .prop("gif", "boolean", "Also make a GIF").prop("hide_hud", "boolean", "default true").prop("world", "string", Rendering.WORLD_DOC).build(),
                a -> {
                    Vec3 c;
                    double r = a.num("radius", 4);
                    double h = a.num("height", 1.5);
                    String autoNote = "";
                    if (a.has("target")) {
                        com.dreadedzombie.lensbridge.cinema.Target tg = com.dreadedzombie.lensbridge.cinema.Target.parse(a.obj("target"));
                        c = Game.call(() -> tg.centre(1f));
                        if (c == null) {
                            throw new IllegalArgumentException("Target not found");
                        }
                        if (tg.entityId() >= 0 && a.bool("auto_center", true)) {
                            boolean wasActive = DirectorCamera.active;
                            com.dreadedzombie.lensbridge.cinema.AutoFrame.Result m;
                            com.dreadedzombie.lensbridge.Stealth.begin();
                            try {
                                m = com.dreadedzombie.lensbridge.cinema.AutoFrame.measure(tg.entityId(), c, 6);
                            } finally {
                                com.dreadedzombie.lensbridge.Stealth.end();
                                if (!wasActive) {
                                    DirectorCamera.release();
                                }
                            }
                            c = m.centre();
                            autoNote = m.note();
                            if (!a.has("radius")) {
                                // model fills ~70% of the frame height (wings flap beyond the measured pose) at the orbit distance, seen from ~25 degrees up
                                double fovUsed = a.num("fov", 50);
                                double dist = Math.max(1.2, m.halfSize() / (0.7 * Math.tan(Math.toRadians(fovUsed / 2))));
                                double elev = Math.toRadians(25);
                                r = dist * Math.cos(elev);
                                if (!a.has("height")) {
                                    h = dist * Math.sin(elev);
                                }
                                autoNote += String.format(java.util.Locale.ROOT, "Radius %.2f, height %.2f. ", r, h);
                            }
                        }
                    } else {
                        c = new Vec3(a.num("cx"), a.num("cy"), a.num("cz"));
                    }
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
                    Result res = render(shots, a.str("name", "orbit_" + Capture.stamp()), a.i("fps", 30), a.bool("gif", false),
                            a.bool("hide_hud", true), a.str("world", "sync"));
                    return autoNote.isEmpty() ? res : res.prependText(autoNote);
                }));

        s.register(new Tool("capture_path", "Cinematic camera move through keyframes, saved frame by frame and encoded to MP4. "
                + "Keyframes: [{x,y,z, yaw?,pitch?, look_at?:[x,y,z], fov?, roll?}, ...] (at least 2). Smooth Catmull-Rom "
                + "path; angles from look_at if given.",
                new Schema().prop("keyframes", "array", "List of keyframe objects").prop("path", "string", "Or: name of a saved path")
                        .prop("seconds", "number", "Duration (default 6)").prop("fps", "integer", "Default 30")
                        .prop("easing", "string", "smooth (ease in/out, default) | linear")
                        .prop("name", "string", "Output name").prop("gif", "boolean", "Also make a GIF")
                        .prop("hide_hud", "boolean", "default true").prop("world", "string", Rendering.WORLD_DOC).build(),
                a -> {
                    CameraPath path = a.has("path") ? CinemaTools.loadPath(a.str("path")) : CameraPath.parse(a.arr("keyframes"));
                    int fps = a.i("fps", 30);
                    int frames = Math.max(2, (int) Math.round(a.num("seconds", 6) * fps));
                    boolean smooth = !"linear".equals(a.str("easing", "smooth"));
                    List<Shot> shots = new ArrayList<>();
                    for (int i = 0; i < frames; i++) {
                        double t = (double) i / (frames - 1);
                        if (smooth) {
                            t = t * t * (3 - 2 * t);
                        }
                        shots.add(path.sample(t));
                    }
                    return render(shots, a.str("name", "path_" + Capture.stamp()), fps, a.bool("gif", false), a.bool("hide_hud", true),
                            a.str("world", "sync"));
                }));

        s.register(new Tool("record", "Record the live view (player camera, director camera, drone...) in REAL TIME - the clip "
                + "lasts exactly as long as the action (late frames are repeated, never sped up). Set timelapse_interval for a timelapse.",
                new Schema().prop("seconds", "number", "Default 5").prop("fps", "integer", "Default 30")
                        .prop("timelapse_interval", "number", "Only for timelapses: seconds of game time between frames (e.g. 2)")
                        .prop("name", "string", "Output name").prop("hide_hud", "boolean", "default false")
                        .prop("gif", "boolean", "Also make a GIF").build(),
                a -> Rendering.record(a.str("name", (a.num("timelapse_interval", 0) > 0 ? "timelapse_" : "clip_") + Capture.stamp()),
                        a.i("fps", 30), a.num("seconds", 5), a.num("timelapse_interval", 0), a.bool("hide_hud", false),
                        a.bool("gif", false), () -> true)));

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

    /** Render director-camera shots frame by frame (world kept in sync by default). */
    public static Result render(List<Shot> shots, String name, int fps, boolean gif, boolean hideHud, String world) {
        boolean wasActive = DirectorCamera.active;
        try {
            return Rendering.render(name, fps, shots.size(), i -> {
                Shot sh = shots.get(i);
                DirectorCamera.set(sh.pos(), sh.yaw(), sh.pitch(), sh.roll(), sh.fov());
            }, world, hideHud, gif);
        } finally {
            if (!wasActive) {
                DirectorCamera.release();
            }
        }
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
        sb.append("Director camera: ").append(DirectorCamera.active ? "ON at " + vec(DirectorCamera.pos)
                        + (DirectorCamera.preview ? " (previewed on screen)" : " (hidden; shown only in captures)") : "off")
                .append(" | shaders ").append(com.dreadedzombie.lensbridge.Shaders.state())
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
