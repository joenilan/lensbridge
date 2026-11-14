package com.dreadedzombie.lensbridge.tools;

import com.dreadedzombie.lensbridge.Capture;
import com.dreadedzombie.lensbridge.DirectorCamera;
import com.dreadedzombie.lensbridge.Game;
import com.dreadedzombie.lensbridge.Stealth;
import com.dreadedzombie.lensbridge.UserControls;
import com.dreadedzombie.lensbridge.cinema.Shot;
import com.dreadedzombie.lensbridge.cinema.Tripods;
import com.dreadedzombie.lensbridge.mcp.BridgeServer;
import com.dreadedzombie.lensbridge.mcp.Tool;
import com.dreadedzombie.lensbridge.mcp.Tool.Args;
import com.dreadedzombie.lensbridge.mcp.Tool.Result;
import com.dreadedzombie.lensbridge.mcp.Tool.Schema;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/** Camera manager back end: list / edit / nudge / thumbnail tripods (used by the web panel and by AI). */
public final class ManagerTools {
    private static final Gson GSON = new Gson();

    private ManagerTools() {
    }

    public static Path thumb(String name) {
        return Capture.dir("thumbs").resolve(name.replaceAll("[^A-Za-z0-9_\\-]", "_") + ".png");
    }

    private static Tripods.Tripod get(String name) {
        Tripods.Tripod t = Tripods.get(name);
        if (t == null) {
            throw new IllegalArgumentException("No tripod " + name);
        }
        return t;
    }

    /** Changes whenever the listed cameras, their thumbnails or the edit state change: the page polls this, not the list. */
    private static String signature(List<Tripods.Tripod> list) {
        StringBuilder sb = new StringBuilder(String.valueOf(UserControls.editing()));
        for (Tripods.Tripod t : list) {
            sb.append(t.toJson()).append(thumb(t.name()).toFile().lastModified());
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    /** Capture thumbnails for the given tripods in one stealth batch (the player's screen doesn't change). */
    public static List<String> thumbs(List<Tripods.Tripod> list) {
        List<String> done = new ArrayList<>();
        Capture.lock();   // one capture at a time: two batches at once left the HUD hidden and a hidden camera active
        try {
            boolean wasActive = DirectorCamera.active;
            Stealth.begin();
            try {
                // inside the try: if reading or hiding the HUD fails, stealth and the camera are still put back
                boolean hud = Game.call(Game::hudHidden);
                try {
                    Game.run(() -> Game.setHudHidden(true));
                    for (Tripods.Tripod t : list) {
                        Shot sh = Game.call(() -> Tripods.shot(t, 1.0F));
                        DirectorCamera.set(sh.pos(), sh.yaw(), sh.pitch(), 0, sh.fov());
                        DirectorCamera.source = t.name();
                        Capture.grab(thumb(t.name()), 3);
                        done.add(t.name());
                    }
                } finally {
                    Game.run(() -> Game.setHudHidden(hud));
                }
            } finally {
                Stealth.end();
                if (!wasActive) {
                    DirectorCamera.release();
                }
            }
        } finally {
            Capture.unlock();
        }
        return done;
    }

    public static void registerAll(BridgeServer s) {
        s.register(new Tool("tripod_all", "The tripods of the world and dimension the player is in, as JSON (name, group, x, y, z, "
                + "yaw, pitch, fov, enabled, track, world, dimension, has_thumb), plus a signature that changes when anything "
                + "listed changes. all=true lists the cameras of every world.",
                new Schema().prop("all", "boolean", "Every world and dimension, not only the current one (default false)")
                        .prop("signature_only", "boolean", "Only the signature and edit state (cheap, for polling)").build(), a -> {
                    List<Tripods.Tripod> list = Tripods.list(a.bool("all", false));
                    if (a.bool("signature_only", false)) {
                        JsonObject sig = new JsonObject();
                        sig.addProperty("signature", signature(list));
                        String ed = UserControls.editing();
                        if (ed != null) {
                            sig.addProperty("editing", ed);
                        }
                        return Result.text(GSON.toJson(sig));
                    }
                    JsonArray arr = new JsonArray();
                    for (Tripods.Tripod t : list) {
                        JsonObject o = t.toJson();
                        Path th = thumb(t.name());
                        o.addProperty("has_thumb", Files.exists(th));
                        o.addProperty("thumb_time", Files.exists(th) ? th.toFile().lastModified() : 0);
                        arr.add(o);
                    }
                    JsonObject out = new JsonObject();
                    out.add("tripods", arr);
                    out.addProperty("signature", signature(list));
                    out.addProperty("camera", DirectorCamera.active ? (DirectorCamera.program != null ? DirectorCamera.program.describe() : "fixed") : "off");
                    out.addProperty("preview", DirectorCamera.preview);
                    String ed = UserControls.editing();
                    if (ed != null) {
                        out.addProperty("editing", ed);
                    }
                    return Result.text(GSON.toJson(out));
                }));

        s.register(new Tool("tripod_update", "Edit a tripod: any of x, y, z, yaw, pitch, fov, enabled, rename (new_name), "
                + "look_at [x,y,z] (re-aim), track (target object, or null to stop tracking).",
                new Schema().req("name", "string", "").prop("x", "number", "").prop("y", "number", "").prop("z", "number", "")
                        .prop("yaw", "number", "").prop("pitch", "number", "").prop("fov", "number", "").prop("enabled", "boolean", "")
                        .prop("new_name", "string", "").prop("look_at", "array", "").prop("track", "object", "").build(),
                a -> {
                    Tripods.Tripod t = get(a.str("name"));
                    Vec3 pos = new Vec3(a.num("x", t.pos().x), a.num("y", t.pos().y), a.num("z", t.pos().z));
                    float yaw = (float) a.num("yaw", t.yaw());
                    float pitch = (float) a.num("pitch", t.pitch());
                    if (a.has("look_at")) {
                        JsonArray l = a.arr("look_at");
                        float[] yp = DirectorCamera.lookAt(pos, new Vec3(l.get(0).getAsDouble(), l.get(1).getAsDouble(), l.get(2).getAsDouble()));
                        yaw = yp[0];
                        pitch = yp[1];
                    }
                    JsonObject track = a.json().has("track") ? (a.json().get("track").isJsonNull() ? null : a.obj("track")) : t.track();
                    String name = a.str("new_name", t.name());
                    if (!name.equals(t.name())) {
                        Tripods.remove(t.name());
                    }
                    Tripods.Tripod now = new Tripods.Tripod(name, pos, yaw, pitch, (float) a.num("fov", t.fov()), track,
                            a.bool("enabled", t.enabled()), t.world(), t.dim());
                    Tripods.put(now);
                    return Result.text(GSON.toJson(now.toJson()));
                }));

        s.register(new Tool("tripod_nudge", "Nudge a tripod relative to its own view: forward/right/up in blocks, yaw/pitch/fov in degrees.",
                new Schema().req("name", "string", "").prop("forward", "number", "").prop("right", "number", "").prop("up", "number", "")
                        .prop("yaw", "number", "").prop("pitch", "number", "").prop("fov", "number", "").build(),
                a -> {
                    Tripods.Tripod t = get(a.str("name"));
                    Vec3 fwd = Vec3.directionFromRotation(t.pitch(), t.yaw());
                    Vec3 flat = Vec3.directionFromRotation(0, t.yaw());
                    Vec3 right = new Vec3(-flat.z, 0, flat.x);
                    Vec3 pos = t.pos().add(fwd.scale(a.num("forward", 0))).add(right.scale(a.num("right", 0))).add(0, a.num("up", 0), 0);
                    float fov = t.fov() + (float) a.num("fov", 0);
                    if (a.has("fov") && t.fov() <= 0) {
                        fov = 70 + (float) a.num("fov", 0);
                    }
                    Tripods.Tripod now = new Tripods.Tripod(t.name(), pos, t.yaw() + (float) a.num("yaw", 0),
                            Math.max(-90, Math.min(90, t.pitch() + (float) a.num("pitch", 0))), Math.max(10, Math.min(150, fov)),
                            t.track(), t.enabled(), t.world(), t.dim());
                    Tripods.put(now);
                    return Result.text(GSON.toJson(now.toJson()));
                }));

        s.register(new Tool("tripod_set_from_view", "Store what is on the player's screen (a camera they are previewing or flying, "
                + "else their own eyes) into an EXISTING tripod; a hidden capture camera is not their view. Fails if the tripod "
                + "doesn't exist (use tripod_place to make one). Fly the drone (Alt+D) or look around, then save the angle you like.",
                new Schema().req("name", "string", "").build(), a -> {
                    // update only: the browser's "Save my view here" on a stale card used to bring a deleted camera back
                    Tripods.Tripod old = Tripods.get(a.str("name"));
                    if (old == null) {
                        throw new IllegalArgumentException("There is no camera called " + a.str("name")
                                + " any more (it was removed). Place a new one with Alt+T or tripod_place.");
                    }
                    Shot v = Game.call(() -> {
                        if (DirectorCamera.onScreen()) {   // only a camera the player really looks through is "their view"
                            return new Shot(DirectorCamera.pos, DirectorCamera.yaw, DirectorCamera.pitch, 0, DirectorCamera.fov);
                        }
                        LocalPlayer p = Game.mc().player;
                        if (p == null) {
                            throw new IllegalStateException("Not in a world");
                        }
                        return new Shot(p.getEyePosition(), p.getYRot(), p.getXRot(), 0, 0);
                    });
                    // moved here, so it belongs to the world and dimension the player is in now
                    Tripods.put(new Tripods.Tripod(old.name(), v.pos(), v.yaw(), v.pitch(), v.fov(), null, old.enabled(),
                            Tripods.currentWorld(), Tripods.currentDim()));
                    return Result.text("Saved view to " + a.str("name"));
                }));

        s.register(new Tool("tripod_thumbs", "Refresh tripod thumbnails (stealth - the player's screen doesn't change). Default: all "
                + "tripods of the current world and dimension.",
                new Schema().prop("names", "array", "Tripod names").prop("group", "string", "Only this group").build(), a -> {
                    List<Tripods.Tripod> list = new ArrayList<>();
                    if (a.has("names")) {
                        for (JsonElement el : a.arr("names")) {
                            list.add(get(el.getAsString()));
                        }
                    } else {
                        for (Tripods.Tripod t : Tripods.here()) {
                            if (!a.has("group") || t.group().equals(a.str("group"))) {
                                list.add(t);
                            }
                        }
                    }
                    return Result.text("Refreshed " + thumbs(list).size() + " thumbnails");
                }));

        s.register(new Tool("tripod_edit", "Take control of a tripod in game: the player flies its camera (WASD/Space/Shift, mouse, "
                + "scroll = FOV), Enter saves, Backspace cancels - or finish with tripod_edit_finish.",
                new Schema().req("name", "string", "").build(), a -> {
                    Game.run(() -> UserControls.startEdit(a.str("name")));
                    return Result.text("Editing " + a.str("name") + " in game");
                }));

        s.register(new Tool("tripod_edit_finish", "Finish taking control: save the current view into the tripod, or cancel.",
                new Schema().prop("save", "boolean", "Default true").build(),
                a -> Result.text(Game.call(() -> UserControls.finishEdit(a.bool("save", true))))));

        s.register(new Tool("exit_camera", "Return the player to their own view from any camera mode (edit -> cancel, drone -> land, "
                + "preview -> off) and release the director camera even when it was hidden. Like Esc in game, plus the hidden camera.",
                new Schema().build(),
                a -> Result.text(Game.call(() -> UserControls.exitCameraMode(true)) ? "Back to the player's view" : "Already on the player's view")));

        s.register(new Tool("restore", "Put back the lasting things Lensbridge changed for a shoot: unfreeze the world if scene froze "
                + "it, the advance_time gamerule to its value from before, shaders as they were, and the window's size / "
                + "fullscreen from before window_size. Call it when you are done (after exit_camera). Time of day and weather "
                + "are not put back. Sends commands (needs op for the freeze and the gamerule). In game: /lensbridge restore.",
                new Schema().build(), a -> Result.text(Game.call(com.dreadedzombie.lensbridge.Restore::apply))));

        s.register(new Tool("guide", "Read Lensbridge's bundled docs. Start every session with the agent guide (default): ground "
                + "rules, workflow, lighting, staging, framing and video. Other names: tools (full tool reference), user-guide, readme.",
                new Schema().prop("name", "string", "agent-guide (default) / tools / user-guide / readme").build(),
                a -> {
                    String name = a.str("name", "agent-guide");
                    String md = com.dreadedzombie.lensbridge.mcp.BridgeServer.doc(name);
                    if (md == null) {
                        throw new IllegalArgumentException("No guide '" + name + "'. Try: agent-guide, tools, user-guide, readme");
                    }
                    return Result.text(md);
                }));

        s.register(new Tool("props", "Camera props for behind-the-scenes shots: visible models of the tripods (camera on "
                + "legs), the drone (spinning rotors) and the current rail. Client-only. Normal shots stay clean: props show "
                + "only in the player's view if player_view is on, and in captures from 'crew' cameras (e.g. a drone or a "
                + "tripod named in crew). A camera never sees its own prop.",
                new Schema().prop("enabled", "boolean", "Turn props on/off")
                        .prop("player_view", "boolean", "Show them in the player's own view (setup)")
                        .prop("crew", "array", "Cameras that see the props: 'director', 'drone' or tripod names; [] = none")
                        .prop("staged_drones", "array", "Parked drone props for staged shots: [[x,y,z,yaw], ...]; [] = none")
                        .build(),
                a -> {
                    if (a.has("enabled")) {
                        com.dreadedzombie.lensbridge.cinema.Props.enabled = a.bool("enabled", false);
                    }
                    if (a.has("player_view")) {
                        com.dreadedzombie.lensbridge.cinema.Props.playerView = a.bool("player_view", false);
                    }
                    if (a.has("crew")) {
                        com.dreadedzombie.lensbridge.cinema.Props.crew.clear();
                        a.arr("crew").forEach(e -> com.dreadedzombie.lensbridge.cinema.Props.crew.add(e.getAsString()));
                    }
                    if (a.has("staged_drones")) {
                        com.dreadedzombie.lensbridge.cinema.Props.stagedDrones.clear();
                        for (var el : a.arr("staged_drones")) {
                            var v = el.getAsJsonArray();
                            com.dreadedzombie.lensbridge.cinema.Props.stagedDrones.add(new double[] {v.get(0).getAsDouble(),
                                    v.get(1).getAsDouble(), v.get(2).getAsDouble(), v.size() > 3 ? v.get(3).getAsDouble() : 0});
                        }
                    }
                    Game.waitFrames(2);
                    return Result.text(Game.call(com.dreadedzombie.lensbridge.cinema.Props::describe));
                }));

        s.register(new Tool("tripod_preview", "Show a tripod's view live on the player's screen (or 'off' to return to the player).",
                new Schema().prop("name", "string", "Tripod name, omit to turn preview off").build(), a -> {
                    if (!a.has("name")) {
                        DirectorCamera.release();
                        return Result.text("preview off");
                    }
                    Tripods.Tripod t = get(a.str("name"));
                    Game.run(() -> {
                        UserControls.viewTripod(t);
                        DirectorCamera.preview = true;
                    });
                    return Result.text("Previewing " + t.name() + " on screen");
                }));
    }
}
