package com.dreadedzombie.lensbridge.tools;

import com.dreadedzombie.lensbridge.Capture;
import com.dreadedzombie.lensbridge.DirectorCamera;
import com.dreadedzombie.lensbridge.Game;
import com.dreadedzombie.lensbridge.UserControls;
import com.dreadedzombie.lensbridge.cinema.Drone;
import com.dreadedzombie.lensbridge.cinema.Shot;
import com.dreadedzombie.lensbridge.cinema.Target;
import com.dreadedzombie.lensbridge.cinema.Tripods;
import com.dreadedzombie.lensbridge.mcp.BridgeServer;
import com.dreadedzombie.lensbridge.mcp.Tool;
import com.dreadedzombie.lensbridge.mcp.Tool.Args;
import com.dreadedzombie.lensbridge.mcp.Tool.Result;
import com.dreadedzombie.lensbridge.mcp.Tool.Schema;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/** Drone camera + tripods (multi-angle stills, synced multicam). */
public final class DroneTools {
    private DroneTools() {
    }

    private static Shot currentView() {
        return Game.call(() -> {
            if (DirectorCamera.active) {
                return new Shot(DirectorCamera.pos, DirectorCamera.yaw, DirectorCamera.pitch, DirectorCamera.roll, DirectorCamera.fov);
            }
            LocalPlayer p = Game.mc().player;
            return new Shot(p.getEyePosition(), p.getYRot(), p.getXRot(), 0, 0);
        });
    }

    public static void registerAll(BridgeServer s) {
        s.register(new Tool("drone_fly", "Fly a PHYSICS DRONE camera (inertia, speed/accel limits, banking, stabilised gimbal, hover wobble, "
                + "ground clearance) - real drone footage. Autonomous modes: hover (hold, gimbal tracks target), mission (fly waypoints, "
                + "optional target to keep framed), orbit (physics orbit, natural lag+bank), follow (auto follow-me aerial: trails, banks, "
                + "frames the subject), dronie (pull back + up away from subject), reveal (rise up, gimbal tilts down), topdown (god view, "
                + "slow spin), fpv (racing-drone lines: hard banks, no gimbal), pilot (sticks via drone_control). Starts from the "
                + "current view unless x/y/z given. Then screenshot / record (live) / capture_program (frame-exact).",
                new Schema().req("mode", "string", "hover | mission | orbit | follow | dronie | reveal | topdown | fpv | pilot")
                        .prop("target", "object", "Subject: {entity:id} | {nearest:type} | {point:[x,y,z]} | {player:true}")
                        .prop("waypoints", "array", "mission/fpv: [{x,y,z,hold?}] (hold = seconds to hover there)")
                        .prop("loop", "boolean", "mission/fpv: loop the waypoints")
                        .prop("x", "number", "Start X (default: current view)").prop("y", "number", "").prop("z", "number", "")
                        .prop("max_speed", "number", "blocks/s (default 6; fpv 14)").prop("accel", "number", "blocks/s^2 (default 4; fpv 12)")
                        .prop("yaw_rate", "number", "deg/s (default 60; fpv 180)").prop("bank", "number", "Banking amount (default 3)")
                        .prop("wobble", "number", "Hover wobble 0..1 (default 0.5)").prop("clearance", "number", "Min blocks above ground (default 1)")
                        .prop("distance", "number", "follow: behind (default 6); dronie: pull-back blocks (default 18); reveal: forward push (default 2)")
                        .prop("height", "number", "follow: above (default 4); dronie: climb (default 10); reveal: rise (default 8); topdown: altitude (default 20); orbit: above (default 3)")
                        .prop("radius", "number", "orbit radius (default 8)").prop("speed", "number", "orbit / topdown spin deg/s (default 20 / 6)")
                        .prop("seconds", "number", "dronie / reveal length (default 8)").prop("fov", "number", "Degrees").build(),
                a -> {
                    Drone.Mode mode = Drone.Mode.valueOf(a.str("mode").toUpperCase(Locale.ROOT));
                    boolean fpv = mode == Drone.Mode.FPV;
                    Shot view = currentView();
                    if (a.has("x")) {
                        view = new Shot(new Vec3(a.num("x"), a.num("y"), a.num("z")), view.yaw(), view.pitch(), 0, view.fov());
                    }
                    Target target = a.has("target") ? Target.parse(a.obj("target")) : null;
                    if (target == null && (mode == Drone.Mode.ORBIT || mode == Drone.Mode.FOLLOW || mode == Drone.Mode.DRONIE)) {
                        throw new IllegalArgumentException(mode + " needs a target");
                    }
                    List<Drone.Waypoint> wps = new ArrayList<>();
                    for (JsonElement el : a.arr("waypoints")) {
                        JsonObject o = el.getAsJsonObject();
                        wps.add(new Drone.Waypoint(new Vec3(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble()),
                                o.has("hold") ? o.get("hold").getAsDouble() : 0));
                    }
                    double defDist = switch (mode) { case DRONIE -> 18; case REVEAL -> 2; default -> 6; };
                    double defHeight = switch (mode) { case DRONIE -> 10; case REVEAL -> 8; case TOPDOWN -> 20; case ORBIT -> 3; default -> 4; };
                    Drone d = new Drone(mode, view, target, wps, a.bool("loop", false), a.num("max_speed", fpv ? 14 : 6),
                            a.num("accel", fpv ? 12 : 4), a.num("yaw_rate", fpv ? 180 : 60), a.num("bank", 3), a.num("wobble", fpv ? 0.2 : 0.5),
                            a.num("clearance", 1), a.num("distance", defDist), a.num("height", defHeight), a.num("radius", 8),
                            a.num("speed", mode == Drone.Mode.TOPDOWN ? 6 : 20), a.num("seconds", 8), (float) a.num("fov", 0));
                    DirectorCamera.play(d);
                    Game.waitFrames(2);
                    double dur = d.duration();
                    return Result.text("Drone flying: " + Game.call(d::describe) + (dur > 0 ? String.format(Locale.ROOT, " (%.1fs)", dur) : "")
                            + ". drone_status for telemetry; capture_program for a frame-exact render; camera_release to land.");
                }));

        s.register(new Tool("drone_control", "Pilot the drone with sticks (starts a pilot-mode drone at the current view if needed). "
                + "Values -1..1, held for 'seconds' then centred.",
                new Schema().prop("forward", "number", "").prop("right", "number", "").prop("up", "number", "")
                        .prop("yaw", "number", "Turn (+ = right)").prop("gimbal", "number", "Tilt (+ = down)")
                        .prop("seconds", "number", "Default 1").prop("max_speed", "number", "blocks/s (default 6)").build(),
                a -> {
                    if (!(DirectorCamera.program instanceof Drone d) || d.mode() != Drone.Mode.PILOT) {
                        Shot v = currentView();
                        Game.run(() -> UserControls.startPilot(v, a.num("max_speed", 6)));
                    }
                    Drone.aiSticksUntil = System.currentTimeMillis() + (long) (a.num("seconds", 1) * 1000) + 100;
                    Drone.stickForward = (float) a.num("forward", 0);
                    Drone.stickRight = (float) a.num("right", 0);
                    Drone.stickUp = (float) a.num("up", 0);
                    Drone.stickYaw = (float) a.num("yaw", 0);
                    Drone.stickGimbal = (float) a.num("gimbal", 0);
                    Game.sleep((long) (a.num("seconds", 1) * 1000));
                    Drone.stickForward = Drone.stickRight = Drone.stickUp = Drone.stickYaw = Drone.stickGimbal = 0;
                    return Result.text(DirectorCamera.program instanceof Drone dd ? dd.telemetry() : "no drone");
                }));

        s.register(new Tool("drone_status", "Drone telemetry: mode, altitude above ground, speed, yaw, gimbal.", new Schema().build(),
                a -> Result.text(DirectorCamera.program instanceof Drone d ? Game.call(d::telemetry) : "No drone flying")));

        // ------------------------------------------------------------------ tripods
        s.register(new Tool("tripod_place", "Place a named fixed camera (tripod) - at the current view, or at x/y/z aimed at look_at "
                + "or tracking a target. Use several around a subject for multiple angles.",
                new Schema().prop("name", "string", "Default tripod_N").prop("x", "number", "").prop("y", "number", "").prop("z", "number", "")
                        .prop("look_at", "array", "[x,y,z] fixed aim point").prop("target", "object", "Track a subject instead (aims every frame)")
                        .prop("fov", "number", "Degrees").build(),
                a -> {
                    Shot v = currentView();
                    Vec3 pos = a.has("x") ? new Vec3(a.num("x"), a.num("y"), a.num("z")) : v.pos();
                    float yaw = v.yaw();
                    float pitch = v.pitch();
                    if (a.has("look_at")) {
                        var l = a.arr("look_at");
                        float[] yp = DirectorCamera.lookAt(pos, new Vec3(l.get(0).getAsDouble(), l.get(1).getAsDouble(), l.get(2).getAsDouble()));
                        yaw = yp[0];
                        pitch = yp[1];
                    }
                    String name = a.str("name", Tripods.nextName());
                    Tripods.put(new Tripods.Tripod(name, pos, yaw, pitch, (float) a.num("fov", v.fov()), a.has("target") ? a.obj("target") : null));
                    return Result.text("Tripod '" + name + "' placed (" + Tripods.all().size() + " total)");
                }));

        s.register(new Tool("tripod_ring", "Place a ring of tripods around a subject in one go (e.g. 4 or 6 angles), all aimed at it.",
                new Schema().req("cx", "number", "").req("cy", "number", "").req("cz", "number", "")
                        .prop("count", "integer", "Default 4").prop("radius", "number", "Default 5").prop("height", "number", "Above the subject (default 1.5)")
                        .prop("start_angle", "number", "Degrees (default 45)").prop("fov", "number", "").prop("prefix", "string", "Name prefix (default 'ring')").build(),
                a -> {
                    Vec3 c = new Vec3(a.num("cx"), a.num("cy"), a.num("cz"));
                    int n = a.i("count", 4);
                    for (int i = 0; i < n; i++) {
                        double ang = Math.toRadians(a.num("start_angle", 45) + 360.0 * i / n);
                        Vec3 p = c.add(Math.sin(ang) * a.num("radius", 5), a.num("height", 1.5), Math.cos(ang) * a.num("radius", 5));
                        float[] yp = DirectorCamera.lookAt(p, c);
                        Tripods.put(new Tripods.Tripod(a.str("prefix", "ring") + "_" + (i + 1), p, yp[0], yp[1], (float) a.num("fov", 0), null));
                    }
                    return Result.text(n + " tripods placed around " + c);
                }));

        s.register(new Tool("tripod_list", "List tripods.", new Schema().build(), a -> {
            StringBuilder sb = new StringBuilder();
            Tripods.all().values().forEach(t -> sb.append(String.format(Locale.ROOT, "%s at %.1f %.1f %.1f yaw %.0f pitch %.0f%s%n",
                    t.name(), t.pos().x, t.pos().y, t.pos().z, t.yaw(), t.pitch(), t.track() != null ? " tracking " + t.track() : "")));
            return Result.text(sb.isEmpty() ? "No tripods" : sb.toString());
        }));

        s.register(new Tool("tripod_remove", "Remove a tripod, a whole group (name prefix before the first _ ), or all.",
                new Schema().prop("name", "string", "").prop("group", "string", "e.g. 'sheet' removes sheet_wide, sheet_close...")
                        .prop("all", "boolean", "").build(), a -> {
                    if (a.bool("all", false)) {
                        Tripods.clear();
                        return Result.text("All tripods removed");
                    }
                    if (a.has("group")) {
                        String g = a.str("group");
                        int n = 0;
                        for (Tripods.Tripod t : new ArrayList<>(Tripods.all().values())) {
                            if (t.group().equals(g) && Tripods.remove(t.name())) {
                                n++;
                            }
                        }
                        return Result.text("Removed " + n + " tripod(s) in group " + g);
                    }
                    return Result.text(Tripods.remove(a.str("name")) ? "removed" : "no such tripod");
                }));

        s.register(new Tool("tripod_view", "Look through a tripod (live).", new Schema().req("name", "string", "").build(), a -> {
            Tripods.Tripod t = Tripods.all().get(a.str("name"));
            if (t == null) {
                throw new IllegalArgumentException("No tripod " + a.str("name"));
            }
            Game.run(() -> UserControls.viewTripod(t));
            Game.waitFrames(2);
            return Result.text("Viewing " + t.name());
        }));

        s.register(new Tool("tripod_shots", "Multi-angle stills: a clean screenshot from every tripod (or the named ones), plus one "
                + "contact sheet of all angles.",
                new Schema().prop("names", "array", "Tripod names (default all)").prop("name", "string", "Output prefix").build(),
                a -> {
                    List<Tripods.Tripod> list = pick(a);
                    String prefix = a.str("name", "angles_" + Capture.stamp());
                    com.dreadedzombie.lensbridge.Stealth.begin();
                    try {
                    List<Path> files = new ArrayList<>();
                    boolean hud = Game.call(Game::hudHidden);
                    Game.run(() -> Game.setHudHidden(true));
                    try {
                        for (Tripods.Tripod t : list) {
                            Shot sh = Game.call(() -> Tripods.shot(t, 1.0F));
                            DirectorCamera.set(sh.pos(), sh.yaw(), sh.pitch(), 0, sh.fov());
                                DirectorCamera.source = t.name();
                            files.add(Capture.grab(Capture.dir("screenshots").resolve(prefix + "_" + t.name() + ".png"), 3));
                        }
                    } finally {
                        Game.run(() -> Game.setHudHidden(hud));
                        DirectorCamera.release();
                    }
                    Path sheet = Capture.contactSheet(files, files.size(), Capture.dir("screenshots").resolve(prefix + "_sheet.png"));
                    StringBuilder sb = new StringBuilder();
                    files.forEach(f -> sb.append(f).append('\n'));
                    return Result.text(sb + "Sheet: " + sheet).addImage(sheet, com.dreadedzombie.lensbridge.Lensbridge.config().previewMaxEdge);
                    } finally {
                        com.dreadedzombie.lensbridge.Stealth.end();
                    }
                }));

        s.register(new Tool("multicam", "Synced multi-angle VIDEO at true world speed: freezes the world and, tick by tick, renders every tripod's view, "
                + "so you get one MP4 per angle with identical action (perfect for editing between angles). Needs op.",
                new Schema().prop("seconds", "number", "Default 5").prop("fps", "integer", "Default 20 (1 world tick per frame)")
                        .prop("names", "array", "Tripod names (default all)").prop("name", "string", "Output prefix").build(),
                a -> {
                    List<Tripods.Tripod> list = pick(a);
                    int fps = a.i("fps", 20);
                    int frames = (int) Math.round(a.num("seconds", 5) * fps);
                    String prefix = a.str("name", "multicam_" + Capture.stamp());
                    com.dreadedzombie.lensbridge.Stealth.begin();
                    try {
                    Map<String, com.dreadedzombie.lensbridge.VideoStream> out = new LinkedHashMap<>();
                    boolean hud = Game.call(Game::hudHidden);
                    boolean wasFrozen = Rendering.worldFrozen();
                    Game.run(() -> {
                        Game.setHudHidden(true);
                        if (!wasFrozen) {
                            Game.mc().player.connection.sendCommand("tick freeze");
                        }
                    });
                    Game.sleep(300);
                    double ticksPerFrame = 20.0 / fps;
                    long stepped = 0;
                    try {
                        for (Tripods.Tripod t : list) {
                            out.put(t.name(), Capture.stream(prefix + "_" + t.name(), fps));
                        }
                        for (int i = 0; i < frames; i++) {
                            // world time for this frame: whole ticks stepped, fraction rendered as the partial tick
                            double tt = i * ticksPerFrame;
                            long need = (long) Math.floor(tt) + 1;
                            if (need > stepped) {
                                int whole = (int) (need - stepped);
                                stepped = need;
                                Game.run(() -> Game.mc().player.connection.sendCommand("tick step " + whole));
                                Game.sleep(55L * whole);
                            }
                            Rendering.forcedPartial = (float) (tt - Math.floor(tt));
                            for (Tripods.Tripod t : list) {
                                Shot sh = Game.call(() -> Tripods.shot(t, 1.0F));
                                DirectorCamera.set(sh.pos(), sh.yaw(), sh.pitch(), 0, sh.fov());
                                DirectorCamera.source = t.name();
                                try (var img = Capture.grabImage(i == 0 ? 4 : 2)) {
                                    out.get(t.name()).write(img);
                                }
                            }
                        }
                    } finally {
                        Rendering.forcedPartial = -1f;
                        out.values().forEach(com.dreadedzombie.lensbridge.VideoStream::close);
                        Game.run(() -> {
                            Game.setHudHidden(hud);
                            if (!wasFrozen) {
                                Game.mc().player.connection.sendCommand("tick unfreeze");
                            }
                        });
                        DirectorCamera.release();
                    }
                    StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                            "%d frames x %d angles = %.1fs per angle at true world speed%n", frames, list.size(), frames / (double) fps));
                    List<Path> firsts = new ArrayList<>();
                    for (var e : out.entrySet()) {
                        sb.append(e.getKey()).append(": ").append(e.getValue().file()).append('\n');
                        List<Path> th = Capture.thumbnails(e.getValue().file(), frames, 3);
                        if (!th.isEmpty()) {
                            firsts.add(th.get(th.size() / 2));
                        }
                    }
                    Path sheet = Capture.contactSheet(firsts, firsts.size(), Capture.dir("videos").resolve(prefix + "_angles.png"));
                    return Result.text(sb + "Angles sheet: " + sheet).addImage(sheet, com.dreadedzombie.lensbridge.Lensbridge.config().previewMaxEdge);
                    } finally {
                        com.dreadedzombie.lensbridge.Stealth.end();
                    }
                }));
    }

    private static List<Tripods.Tripod> pick(Args a) {
        List<Tripods.Tripod> list = new ArrayList<>();
        if (a.has("names")) {
            for (JsonElement el : a.arr("names")) {
                Tripods.Tripod t = Tripods.all().get(el.getAsString());
                if (t == null) {
                    throw new IllegalArgumentException("No tripod " + el.getAsString());
                }
                list.add(t);
            }
        } else {
            Tripods.all().values().stream().filter(Tripods.Tripod::enabled).forEach(list::add);
        }
        if (list.isEmpty()) {
            throw new IllegalStateException("No tripods - place some with tripod_place / tripod_ring (or press Y in game)");
        }
        return list;
    }
}
