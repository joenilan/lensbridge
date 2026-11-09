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
