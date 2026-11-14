package com.dreadedzombie.lensbridge;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stealth capture: while active, frames are still rendered (and can be grabbed) but are NOT shown in the window -
 * the player's screen simply holds its last normal frame instead of flashing to the camera's view (see MinecraftMixin).
 * Re-entrant (a counter), so nested captures are fine.
 */
public final class Stealth {
    private static final AtomicInteger DEPTH = new AtomicInteger();

    private Stealth() {
    }

    public static boolean active() {
        return DEPTH.get() > 0 && Lensbridge.config().stealthCapture;
    }

    /** How many captures are in progress (whatever the config says). */
    public static int depth() {
        return DEPTH.get();
    }

    public static void begin() {
        DEPTH.incrementAndGet();
    }

    /** Panic key: nothing is hidden any more, whatever was in progress (later end() calls stop at zero). */
    public static void reset() {
        DEPTH.set(0);
    }

    public static void end() {
        DEPTH.updateAndGet(d -> Math.max(0, d - 1));
    }
}
