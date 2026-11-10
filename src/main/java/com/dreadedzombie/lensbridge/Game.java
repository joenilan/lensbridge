package com.dreadedzombie.lensbridge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Thread helpers: everything that touches the game runs on the client thread; HTTP threads wait for it. */
public final class Game {
    private static final List<FrameWait> FRAME_WAITS = new ArrayList<>();
    private static long frames;

    private record FrameWait(long target, CompletableFuture<Void> future) {
    }

    private Game() {
    }

    public static Minecraft mc() {
        return Minecraft.getInstance();
    }

    /** Whether the HUD is hidden (F1). Client thread. */
    public static boolean hudHidden() {
        return mc().gui.hud.isHidden();
    }

    /** Hide or show the HUD (F1). 26.x only offers a toggle (the old {@code options.hideGui} field is gone). Client thread. */
    public static void setHudHidden(boolean hidden) {
        if (mc().gui.hud.isHidden() != hidden) {
            mc().gui.hud.toggle();
        }
    }

    /** Run on the client thread and wait for the result (throws on timeout / error). */
    public static <T> T call(Supplier<T> task) {
        if (mc().isSameThread()) {
            return task.get();
        }
        try {
            return mc().submit(task).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException(cause.getMessage() == null ? cause.toString() : cause.getMessage(), cause);
        }
    }

    public static void run(Runnable task) {
        call(() -> {
            task.run();
            return null;
        });
    }

    /** Block (off-thread) until {@code n} more frames have been rendered. */
    /** Wait (off-thread) until the terrain around the camera has been rebuilt, up to {@code maxMs}. Returns a note. */
    public static String waitTerrain(long maxMs) {
        return waitTerrain(0, maxMs);
    }

    /**
     * Wait (off-thread) until the terrain is fully built: at least {@code minMs}, then three complete checks in a row,
     * up to {@code maxMs}. Uses Sodium's own state when Sodium is installed (it replaces vanilla's chunk renderer).
     */
    public static String waitTerrain(long minMs, long maxMs) {
        long t0 = System.currentTimeMillis();
        int ok = 0;
        while (System.currentTimeMillis() - t0 < maxMs) {
            boolean done = Boolean.TRUE.equals(call(Game::terrainComplete));
            ok = done ? ok + 1 : 0;
            if (ok >= 3 && System.currentTimeMillis() - t0 >= minMs) {
                return String.format(java.util.Locale.ROOT, "terrain ready (%.1fs)", (System.currentTimeMillis() - t0) / 1000.0);
            }
            waitFrames(2);
        }
        return "terrain still building after " + maxMs / 1000 + "s";
    }

    private static java.lang.reflect.Method sodiumComplete;
    private static Object sodiumLookup;

    /** Render thread. */
    private static boolean terrainComplete() {
        if (mc().level == null) {
            return false;
        }
        try {
            if (sodiumLookup == null) {
                Class<?> c = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
                sodiumLookup = c.getMethod("instanceNullable");
                sodiumComplete = c.getMethod("isTerrainRenderComplete");
            }
            if (sodiumLookup instanceof java.lang.reflect.Method m) {
                Object r = m.invoke(null);
                if (r != null) {
                    return (boolean) sodiumComplete.invoke(r);
                }
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            sodiumLookup = Boolean.FALSE;   // no Sodium: use vanilla
        }
        return mc().levelRenderer.hasRenderedAllSections();
    }

    public static void waitFrames(int n) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        synchronized (FRAME_WAITS) {
            FRAME_WAITS.add(new FrameWait(frames + Math.max(1, n), future));
        }
        try {
            future.get(Math.max(5, n) * 2L, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Timed out waiting for frames (is the game window minimised or paused?)");
        }
    }

    /** Block (off-thread) for real time. */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void onFrame(RenderFrameEvent.Post event) {
        DirectorCamera.frameDone();
        synchronized (FRAME_WAITS) {
            frames++;
            for (Iterator<FrameWait> it = FRAME_WAITS.iterator(); it.hasNext(); ) {
                FrameWait wait = it.next();
                if (frames >= wait.target) {
                    wait.future.complete(null);
                    it.remove();
                }
            }
        }
    }

    public static long frameCount() {
        return frames;
    }
}
