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
        Thread t = new Thread(() -> {
            Path dir = Capture.dir("frames").resolve(name);
            boolean before = Game.call(() -> Game.mc().options.hideGui);
            if (hideHud) {
                Game.run(() -> Game.mc().options.hideGui = true);
            }
            List<Path> files = new ArrayList<>();
            try {
                long t0 = System.nanoTime();
                for (int i = 0; RUNNING.get() && (maxSeconds <= 0 || i < maxSeconds * fps); i++) {
                    long due = t0 + (long) (i * 1e9 / fps);
                    long now = System.nanoTime();
                    if (due > now) {
                        Game.sleep((due - now) / 1_000_000L);
                    }
                    files.add(Capture.grab(dir.resolve(String.format(Locale.ROOT, "frame_%05d.png", i)), i == 0 ? 4 : 1));
                    frameCount = files.size();
                }
                Path video = Capture.encode(dir, fps, Capture.dir("videos").resolve(name + ".mp4"), false);
                done.accept(video.toString());
            } catch (RuntimeException e) {
                done.accept("Recording failed: " + e.getMessage());
            } finally {
                if (hideHud) {
                    Game.run(() -> Game.mc().options.hideGui = before);
                }
                RUNNING.set(false);
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
