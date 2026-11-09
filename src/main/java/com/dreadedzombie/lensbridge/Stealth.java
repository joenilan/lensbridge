package com.dreadedzombie.lensbridge;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stealth capture: while active, frames are still rendered (and can be grabbed) but are NOT presented to the
 * window - the player's screen simply holds its last normal frame instead of flashing to the camera's view.
 * Re-entrant (a counter), so nested captures are fine.
 */
public final class Stealth {
    private static final AtomicInteger DEPTH = new AtomicInteger();

    private Stealth() {
    }

    public static boolean active() {
        return DEPTH.get() > 0 && Lensbridge.config().stealthCapture;
    }

    public static void begin() {
        DEPTH.incrementAndGet();
    }

    public static void end() {
        DEPTH.updateAndGet(d -> Math.max(0, d - 1));
    }
}
