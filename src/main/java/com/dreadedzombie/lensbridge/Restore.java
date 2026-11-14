package com.dreadedzombie.lensbridge;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Remembers the lasting things Lensbridge changed for a shoot (the `scene` and `window_size` tools) so one call puts
 * them back: the world freeze, the advance_time gamerule, shaders, and the window's size / fullscreen. Captures restore
 * their own short-lived changes (HUD, camera, tick rate); this is for what is deliberately left in place between shots
 * and used to stay that way after the AI was done.
 * <p>Only the FIRST change of each thing is remembered, so the value put back is the one from before Lensbridge touched it.
 */
public final class Restore {
    private static boolean froze;
    private static Boolean advanceTimeBefore;
    private static Boolean shadersBefore;
    private static int[] windowBefore;      // width, height, fullscreen (1/0)

    private Restore() {
    }

    /** `scene` is about to send "tick freeze" / "tick unfreeze". */
    public static synchronized void noteFreeze(boolean freeze, boolean wasFrozen) {
        if (!freeze) {
            froze = false;          // unfrozen on request: nothing left to undo
        } else if (!wasFrozen) {
            froze = true;
        }
    }

    public static synchronized boolean advanceTimeKnown() {
        return advanceTimeBefore != null;
    }

    /** The gamerule's value before the first change (read from the game's answer to "gamerule advance_time"). */
    public static synchronized void noteAdvanceTime(boolean before) {
        if (advanceTimeBefore == null) {
            advanceTimeBefore = before;
        }
    }

    /** Shaders are about to be switched. Client thread. */
    public static synchronized void noteShaders() {
        String state = Shaders.state();
        if (shadersBefore == null && !state.equals("n/a")) {
            shadersBefore = state.equals("on");
        }
    }

    /** The window is about to be resized. Client thread. */
    public static synchronized void noteWindow() {
        if (windowBefore == null) {
            var w = Minecraft.getInstance().getWindow();
            windowBefore = new int[]{w.getWidth(), w.getHeight(), w.isFullscreen() ? 1 : 0};
        }
    }

    public static synchronized boolean pending() {
        return froze || advanceTimeBefore != null || shadersBefore != null || windowBefore != null;
    }

    /** Put everything back and forget it. Client thread (the tool and /lensbridge restore). Returns what was done. */
    public static synchronized String apply() {
        Minecraft mc = Minecraft.getInstance();
        List<String> done = new ArrayList<>();
        if (mc.player != null) {
            if (froze) {
                mc.player.connection.sendCommand("tick unfreeze");
                done.add("world unfrozen");
            }
            if (advanceTimeBefore != null) {
                mc.player.connection.sendCommand("gamerule advance_time " + advanceTimeBefore);
                done.add("advance_time back to " + advanceTimeBefore);
            }
            froze = false;
            advanceTimeBefore = null;
        } else if (froze || advanceTimeBefore != null) {
            done.add("not in a world, so the freeze / advance_time gamerule were left alone (run this again in the world)");
        }
        if (shadersBefore != null) {
            if (!Shaders.state().equals(shadersBefore ? "on" : "off")) {
                done.add(Shaders.set(shadersBefore));
            }
            shadersBefore = null;
        }
        if (windowBefore != null) {
            var w = mc.getWindow();
            if (windowBefore[2] == 1) {
                if (!w.isFullscreen()) {
                    w.toggleFullScreen();
                    mc.options.fullscreen().set(true);
                }
                done.add("window back to fullscreen");
            } else {
                w.setWindowed(windowBefore[0], windowBefore[1]);
                done.add("window back to " + windowBefore[0] + "x" + windowBefore[1]);
            }
            windowBefore = null;
        }
        return done.isEmpty() ? "Nothing to restore: Lensbridge has no freeze, gamerule, shader or window change on record."
                : "Restored: " + String.join("; ", done) + ". (Time of day and weather are not put back.)";
    }
}
