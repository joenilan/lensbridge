package com.dreadedzombie.lensbridge;

import net.neoforged.fml.ModList;

/**
 * Other mods and the frames Lensbridge needs.
 *
 * <p>Dynamic FPS stops drawing most frames while the game window isn't focused (1 a second by default), which is
 * exactly when an AI app is in front. Captures would grab a stale picture, recordings would stutter, and a hidden
 * (stealth) camera frame could stay on screen. So while Lensbridge is working, Dynamic FPS is told to draw normally
 * (see DynamicFpsMixin); the rest of the time it saves power as usual.
 *
 * <p>Sodium replaces the game's chunk renderer and works out what is visible from scratch for every camera, so the
 * vanilla-only chunk bookkeeping fix (LevelRendererMixin) must stay out of its way.
 */
public final class FrameGuard {
    private static volatile long until;
    private static Boolean sodium;

    private FrameGuard() {
    }

    /** Keep real frames coming for a while (a tool is about to capture). */
    public static void hold(long millis) {
        until = Math.max(until, System.currentTimeMillis() + millis);
    }

    /** True while Lensbridge needs every frame really drawn. Any thread. */
    public static boolean needed() {
        // Capture.busy(): every capture sequence holds the capture lock, including real-time recordings, which grab
        // on a clock and never wait for frames
        return Stealth.depth() > 0 || Recorder.running() || Capture.busy() || Game.waiting()
                || Inset.wantsFrame() || System.currentTimeMillis() < until;
    }

    /** Sodium draws the world (render thread, after mods are loaded). */
    public static boolean sodium() {
        if (sodium == null) {
            sodium = ModList.get().isLoaded("sodium");
        }
        return sodium;
    }
}
