package com.dreadedzombie.lensbridge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Background real-time recorder (for the in-game record hotkey and the `record` tool). */
public final class Recorder {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile long startedAt;
    private static volatile int frameCount;

    private Recorder() {
    }

    public static boolean running() {
        return RUNNING.get();
    }

    public static double seconds() {
        return running() ? (System.currentTimeMillis() - startedAt) / 1000.0 : 0;
    }

    public static int frames() {
        return frameCount;
    }

    /** Start recording on a background thread; {@code done} gets the encoded video (or an error text). */
    public static boolean start(String name, int fps, boolean hideHud, double maxSeconds, Consumer<String> done) {
        if (!RUNNING.compareAndSet(false, true)) {
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
                        0, hideHud, false, RUNNING::get);
                String file = Capture.dir("videos").resolve(name + ".mp4").toString();
                finished.addProperty("ok", true);
                finished.addProperty("file", file);
                done.accept(file);
            } catch (RuntimeException e) {
                finished.addProperty("ok", false);
                finished.addProperty("error", String.valueOf(e.getMessage()));
                done.accept("Recording failed: " + e.getMessage());
            } finally {
                RUNNING.set(false);
                finished.addProperty("seconds", Math.round((System.currentTimeMillis() - startedAt) / 100.0) / 10.0);
                com.dreadedzombie.lensbridge.mcp.AgentlinkSupport.publish("lensbridge.recording_finished", finished);
            }
        }, "lensbridge-recorder");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public static void stop() {
        RUNNING.set(false);
    }
}
