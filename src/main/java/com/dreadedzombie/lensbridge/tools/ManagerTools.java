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

    static Path thumb(String name) {
        return Capture.dir("thumbs").resolve(name.replaceAll("[^A-Za-z0-9_\\-]", "_") + ".png");
    }

    private static Tripods.Tripod get(String name) {
        Tripods.Tripod t = Tripods.all().get(name);
        if (t == null) {
            throw new IllegalArgumentException("No tripod " + name);
        }
        return t;
    }

    /** Capture thumbnails for the given tripods in one stealth batch (the player's screen doesn't change). */
    static List<String> thumbs(List<Tripods.Tripod> list) {
        List<String> done = new ArrayList<>();
        boolean wasActive = DirectorCamera.active;
        Stealth.begin();
        boolean hud = Game.call(() -> Game.mc().options.hideGui);
        Game.run(() -> Game.mc().options.hideGui = true);
        try {
            for (Tripods.Tripod t : list) {
                Shot sh = Game.call(() -> Tripods.shot(t, 1.0F));
                DirectorCamera.set(sh.pos(), sh.yaw(), sh.pitch(), 0, sh.fov());
                Capture.grab(thumb(t.name()), 3);
                done.add(t.name());
            }
        } finally {
            Game.run(() -> Game.mc().options.hideGui = hud);
            Stealth.end();
            if (!wasActive) {
                DirectorCamera.release();
            }
        }
        return done;
    }

    public static void registerAll(BridgeServer s) {
        s.register(new Tool("tripod_all", "All tripods as JSON (name, group, x, y, z, yaw, pitch, fov, enabled, track, has_thumb).",
                new Schema().build(), a -> {
                    JsonArray arr = new JsonArray();
                    for (Tripods.Tripod t : Tripods.all().values()) {
                        JsonObject o = t.toJson();
                        Path th = thumb(t.name());
                        o.addProperty("has_thumb", Files.exists(th));
                        o.addProperty("thumb_time", Files.exists(th) ? th.toFile().lastModified() : 0);
                        arr.add(o);
                    }
                    JsonObject out = new JsonObject();
                    out.add("tripods", arr);
                    out.addProperty("camera", DirectorCamera.active ? (DirectorCamera.program != null ? DirectorCamera.program.describe() : "fixed") : "off");
                    out.addProperty("preview", DirectorCamera.preview);
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
                    Tripods.put(new Tripods.Tripod(name, pos, yaw, pitch, (float) a.num("fov", t.fov()), track, a.bool("enabled", t.enabled())));
                    return Result.text(GSON.toJson(Tripods.all().get(name).toJson()));
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
                    Tripods.put(new Tripods.Tripod(t.name(), pos, t.yaw() + (float) a.num("yaw", 0),
                            Math.max(-90, Math.min(90, t.pitch() + (float) a.num("pitch", 0))), Math.max(10, Math.min(150, fov)), t.track(), t.enabled()));
                    return Result.text(GSON.toJson(Tripods.all().get(t.name()).toJson()));
                }));

        s.register(new Tool("tripod_set_from_view", "Store the current view (director camera if active, else the player's eyes) into a tripod "
                + "(creates it if missing). Fly the drone (K) or look around, then save the angle you like.",
                new Schema().req("name", "string", "").build(), a -> {
                    Shot v = Game.call(() -> {
                        if (DirectorCamera.active) {
                            return new Shot(DirectorCamera.pos, DirectorCamera.yaw, DirectorCamera.pitch, 0, DirectorCamera.fov);
                        }
                        LocalPlayer p = Game.mc().player;
                        return new Shot(p.getEyePosition(), p.getYRot(), p.getXRot(), 0, 0);
                    });
                    Tripods.Tripod old = Tripods.all().get(a.str("name"));
                    Tripods.put(new Tripods.Tripod(a.str("name"), v.pos(), v.yaw(), v.pitch(), v.fov(), null, old == null || old.enabled()));
                    return Result.text("Saved view to " + a.str("name"));
                }));

        s.register(new Tool("tripod_thumbs", "Refresh tripod thumbnails (stealth - the player's screen doesn't change). Default: all.",
                new Schema().prop("names", "array", "Tripod names").prop("group", "string", "Only this group").build(), a -> {
                    List<Tripods.Tripod> list = new ArrayList<>();
                    if (a.has("names")) {
                        for (JsonElement el : a.arr("names")) {
                            list.add(get(el.getAsString()));
                        }
                    } else {
                        for (Tripods.Tripod t : Tripods.all().values()) {
                            if (!a.has("group") || t.group().equals(a.str("group"))) {
                                list.add(t);
                            }
                        }
                    }
                    return Result.text("Refreshed " + thumbs(list).size() + " thumbnails");
                }));

        s.register(new Tool("tripod_preview", "Show a tripod's view live on the player's screen (or 'off' to return to the player).",
                new Schema().prop("name", "string", "Tripod name, omit to turn preview off").build(), a -> {
                    if (!a.has("name")) {
                        DirectorCamera.release();
                        return Result.text("preview off");
                    }
                    Tripods.Tripod t = get(a.str("name"));
                    Game.run(() -> UserControls.viewTripod(t));
                    DirectorCamera.preview = true;
                    return Result.text("Previewing " + t.name() + " on screen");
                }));
    }
}
