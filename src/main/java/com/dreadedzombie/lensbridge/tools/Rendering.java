package com.dreadedzombie.lensbridge.tools;

import com.dreadedzombie.lensbridge.Capture;
import com.dreadedzombie.lensbridge.Game;
import com.dreadedzombie.lensbridge.Lensbridge;
import com.dreadedzombie.lensbridge.VideoStream;
import com.dreadedzombie.lensbridge.mcp.Tool.Result;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * Frame-exact rendering with the WORLD KEPT AT THE RIGHT SPEED. A capture runs slower than real time, so by default
 * ("sync") the server tick rate is scaled down to match the capture speed - mobs move at their true speed in the
 * video. Other modes: "frozen" (world held still), "step" (frozen, stepped exactly 20/fps ticks per frame) and
 * "free" (world runs at full speed while the camera is rendered slowly = a world timelapse; only when asked).
 */
public final class Rendering {
    private Rendering() {
    }

    public static final String WORLD_DOC = "World speed during the render: sync (default - mobs move at true speed), frozen "
            + "(world held still), step (frozen + world advanced exactly 20/fps ticks per frame, interpolated - perfectly smooth at any fps, e.g. 60), free (world runs at "
            + "full speed = timelapse look). sync/frozen/step need op.";

    /** Partial tick forced on the renderer during a step render (-1 = off). See DeltaTrackerTimerMixin. */
    public static volatile float forcedPartial = -1f;

    /** Whether the client's world is currently frozen (/tick freeze). */
    public static boolean worldFrozen() {
        return Game.call(() -> Game.mc().level != null && Game.mc().level.tickRateManager().isFrozen());
    }

    public static void cmd(String c) {
        Game.run(() -> Game.mc().player.connection.sendCommand(c));
    }

    /** Render {@code frames} frames; {@code setup} positions the camera / clock for frame i (called off-thread). */
    public static Result render(String name, int fps, int frames, IntConsumer setup, String world, boolean hideHud, boolean gif) {
        com.dreadedzombie.lensbridge.Stealth.begin();
        try {
            return renderInner(name, fps, frames, setup, world, hideHud, gif);
        } finally {
            com.dreadedzombie.lensbridge.Stealth.end();
        }
    }

    private static Result renderInner(String name, int fps, int frames, IntConsumer setup, String world, boolean hideHud, boolean gif) {
        boolean hudBefore = Game.call(() -> Game.mc().options.hideGui);
        Game.run(() -> Game.mc().options.hideGui = hideHud);
        String mode = world == null ? "sync" : world.toLowerCase(Locale.ROOT);
        double ticksPerFrame = 20.0 / fps;
        long stepped = 0;
        long started = System.nanoTime();
        boolean wasFrozen = worldFrozen();
        if ((mode.equals("frozen") || mode.equals("step")) && !wasFrozen) {
            cmd("tick freeze");
            Game.sleep(150);
        }
        VideoStream video = Capture.stream(name, fps);
        double avgFrameSeconds = -1;
        float lastRate = 20;
        try {
            for (int i = 0; i < frames; i++) {
                long t0 = System.nanoTime();
                setup.accept(i);
                if (mode.equals("step")) {
                    // world time for this frame, in ticks: whole ticks are stepped, the fraction is the partial tick
                    double t = i * ticksPerFrame;
                    long need = (long) Math.floor(t) + 1;
                    if (need > stepped) {
                        int whole = (int) (need - stepped);
                        stepped = need;
                        cmd("tick step " + whole);
                        Game.sleep(55L * whole);
                    }
                    forcedPartial = (float) (t - Math.floor(t));
                }
                if (mode.equals("sync")) {
                    // capture faster than real time: wait, so the world never gets ahead of the video (no slow motion)
                    long due = started + (long) (i * 1e9 / fps * (20.0 / lastRate));
                    long now = System.nanoTime();
                    if (due > now) {
                        Game.sleep((due - now) / 1_000_000L);
                    }
                }
                try (NativeImage img = Capture.grabImage(i == 0 ? 6 : 1)) {
                    video.write(img);
                }
                double dt = (System.nanoTime() - t0) / 1e9;
                if (i > 0) {
                    avgFrameSeconds = avgFrameSeconds < 0 ? dt : avgFrameSeconds * 0.8 + dt * 0.2;
                }
                // sync: game should advance 1/fps seconds of game time per captured frame
                if (mode.equals("sync") && avgFrameSeconds > 0 && (i == 3 || i % 10 == 0)) {
                    float rate = (float) Math.max(1.0, Math.min(20.0, ticksPerFrame / avgFrameSeconds));
                    if (Math.abs(rate - lastRate) > 0.4) {
                        lastRate = rate;
                        cmd(String.format(Locale.ROOT, "tick rate %.2f", rate));
                    }
                }
            }
        } finally {
            forcedPartial = -1f;
            try {
                video.close();
            } finally {
                if (mode.equals("sync") && lastRate != 20) {
                    cmd("tick rate 20");
                }
                if ((mode.equals("frozen") || mode.equals("step")) && !wasFrozen) {
                    cmd("tick unfreeze"); // leave the world as we found it
                }
                Game.run(() -> Game.mc().options.hideGui = hudBefore);
            }
        }
        double took = (System.nanoTime() - started) / 1e9;
        String note = switch (mode) {
            case "sync" -> String.format(Locale.ROOT, "World kept in sync (tick rate scaled to %.1f during capture).", lastRate);
            case "free" -> "World ran at full speed during a slower capture: motion is sped up (timelapse look).";
            default -> "World " + mode + ".";
        };
        return finish(video.file(), frames, fps, gif, String.format(Locale.ROOT, "%d frames @ %d fps = %.1fs of video (rendered in %.1fs). %s",
                frames, fps, frames / (double) fps, took, note));
    }

    /** Thumbnails + contact sheet (+ optional GIF) for a finished video. */
    public static Result finish(Path video, int frames, int fps, boolean gif, String summary) {
        StringBuilder sb = new StringBuilder(summary).append("\nVideo: ").append(video).append('\n');
        if (gif) {
            Path g = video.resolveSibling(video.getFileName().toString().replace(".mp4", ".gif"));
            try {
                Process p = new ProcessBuilder(Lensbridge.config().ffmpeg, "-y", "-loglevel", "error", "-i", video.toString(), "-vf",
                        "fps=" + (fps > 33 ? "100/3" : String.valueOf(fps)) + ",scale=640:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse", g.toString())
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                p.waitFor(5, TimeUnit.MINUTES);
                sb.append("GIF: ").append(g).append('\n');
            } catch (Exception e) {
                sb.append("GIF failed: ").append(e.getMessage()).append('\n');
            }
        }
        List<Path> thumbs = Capture.thumbnails(video, frames, 9);
        if (thumbs.isEmpty()) {
            return Result.text(sb.toString());
        }
        Path sheet = Capture.contactSheet(new ArrayList<>(thumbs), thumbs.size(),
                video.resolveSibling(video.getFileName().toString().replace(".mp4", "_sheet.png")));
        sb.append("Contact sheet: ").append(sheet);
        return Result.text(sb.toString()).addImage(sheet, Lensbridge.config().previewMaxEdge);
    }

    /**
     * Real-time recording: the clip always covers exactly as much game time as it lasts. If a grab runs late the
     * previous frame is repeated, so it never turns into an accidental timelapse. Timelapse only when
     * {@code timelapseInterval} > 0 (one frame every N seconds, played back at fps).
     */
    public static Result record(String name, int fps, double seconds, double timelapseInterval, boolean hideHud, boolean gif,
                                java.util.function.BooleanSupplier keepGoing) {
        // recording a director/drone camera the player isn't previewing: keep it off their screen
        boolean stealth = com.dreadedzombie.lensbridge.DirectorCamera.active && !com.dreadedzombie.lensbridge.DirectorCamera.preview;
        if (stealth) {
            com.dreadedzombie.lensbridge.Stealth.begin();
        }
        try {
            return recordInner(name, fps, seconds, timelapseInterval, hideHud, gif, keepGoing);
        } finally {
            if (stealth) {
                com.dreadedzombie.lensbridge.Stealth.end();
            }
        }
    }

    private static Result recordInner(String name, int fps, double seconds, double timelapseInterval, boolean hideHud, boolean gif,
                                      java.util.function.BooleanSupplier keepGoing) {
        boolean hudBefore = Game.call(() -> Game.mc().options.hideGui);
        Game.run(() -> Game.mc().options.hideGui = hideHud);
        VideoStream video = Capture.stream(name, fps);
        long t0 = System.nanoTime();
        int written = 0;
        int grabbed = 0;
        try {
            if (timelapseInterval > 0) {
                int total = (int) Math.round(seconds / timelapseInterval);
                for (int i = 0; i < total && keepGoing.getAsBoolean(); i++) {
                    long due = t0 + (long) (i * timelapseInterval * 1e9);
                    long now = System.nanoTime();
                    if (due > now) {
                        Game.sleep((due - now) / 1_000_000L);
                    }
                    try (NativeImage img = Capture.grabImage(i == 0 ? 4 : 1)) {
                        video.write(img);
                    }
                    written++;
                    grabbed++;
                }
            } else {
                int total = (int) Math.round(seconds * fps);
                while (written < total && keepGoing.getAsBoolean()) {
                    long slot = t0 + (long) (written * 1e9 / fps);
                    long now = System.nanoTime();
                    if (slot > now) {
                        Game.sleep((slot - now) / 1_000_000L);
                    }
                    try (NativeImage img = Capture.grabImage(grabbed == 0 ? 4 : 1)) {
                        video.write(img);
                    }
                    grabbed++;
                    written++;
                    // fill any slots that passed while grabbing with the same frame -> real-time duration
                    int due = (int) Math.min(total, Math.floor((System.nanoTime() - t0) / 1e9 * fps));
                    while (written < due) {
                        video.repeat();
                        written++;
                    }
                }
            }
        } finally {
            video.close();
            Game.run(() -> Game.mc().options.hideGui = hudBefore);
        }
        double real = (System.nanoTime() - t0) / 1e9;
        String summary = timelapseInterval > 0
                ? String.format(Locale.ROOT, "TIMELAPSE: %d frames, one every %.1fs over %.1fs of game time -> %.1fs of video @ %d fps.",
                written, timelapseInterval, real, written / (double) fps, fps)
                : String.format(Locale.ROOT, "Real-time: %.1fs of game time -> %.1fs of video @ %d fps (%d unique frames, %d repeated to keep real time).",
                real, written / (double) fps, fps, grabbed, written - grabbed);
        return finish(video.file(), written, fps, gif, summary);
    }
}
