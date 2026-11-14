package com.dreadedzombie.lensbridge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Background real-time recorder (for the in-game record hotkey and the `record` tool). */
public final class Recorder {
    /**
     * The recording in progress: true while frames are being taken, false once stopped and still encoding, null when
     * idle. One token per recording, so an old recording finishing can't switch off a newer one.
     */
    private static final java.util.concurrent.atomic.AtomicReference<AtomicBoolean> CURRENT = new java.util.concurrent.atomic.AtomicReference<>();
    private static volatile long startedAt;
    private static volatile int frameCount;

    private Recorder() {
    }

    public static boolean running() {
        AtomicBoolean c = CURRENT.get();
        return c != null && c.get();
    }

    /** Stopped, but the previous clip is still being saved: a new recording can't start yet. */
    public static boolean encoding() {
        AtomicBoolean c = CURRENT.get();
        return c != null && !c.get();
    }

    public static double seconds() {
        return running() ? (System.currentTimeMillis() - startedAt) / 1000.0 : 0;
    }

    public static int frames() {
        return frameCount;
    }

    /**
     * Start recording on a background thread; {@code done} gets (true, the video file) or (false, what went wrong).
     * Returns false if a recording is running or the last one is still encoding.
     */
    public static boolean start(String name, int fps, boolean hideHud, double maxSeconds, java.util.function.BiConsumer<Boolean, String> done) {
        AtomicBoolean mine = new AtomicBoolean(true);
        if (!CURRENT.compareAndSet(null, mine)) {
            return false;
        }
        startedAt = System.currentTimeMillis();
        frameCount = 0;
        com.google.gson.JsonObject started = new com.google.gson.JsonObject();
        started.addProperty("name", name);
        started.addProperty("fps", fps);
        com.dreadedzombie.lensbridge.mcp.AgentlinkSupport.publish("lensbridge.recording_started", started);
        Thread t = new Thread(() -> {
            com.google.gson.JsonObject finished = new com.google.gson.JsonObject();
            finished.addProperty("name", name);
            try {
                var result = com.dreadedzombie.lensbridge.tools.Rendering.record(name, fps, maxSeconds > 0 ? maxSeconds : 3600,
                        0, hideHud, false, mine::get);
                String file = Capture.dir("videos").resolve(name + ".mp4").toString();
                finished.addProperty("ok", true);
                finished.addProperty("file", file);
                done.accept(true, file);
            } catch (RuntimeException e) {
                finished.addProperty("ok", false);
                finished.addProperty("error", String.valueOf(e.getMessage()));
                done.accept(false, String.valueOf(e.getMessage()));
            } finally {
                CURRENT.compareAndSet(mine, null);
                finished.addProperty("seconds", Math.round((System.currentTimeMillis() - startedAt) / 100.0) / 10.0);
                com.dreadedzombie.lensbridge.mcp.AgentlinkSupport.publish("lensbridge.recording_finished", finished);
            }
        }, "lensbridge-recorder");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public static void stop() {
        AtomicBoolean c = CURRENT.get();
        if (c != null) {
            c.set(false);
        }
    }
}
