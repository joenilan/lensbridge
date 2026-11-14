package com.dreadedzombie.lensbridge;

import java.nio.file.Path;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * The inset (a live picture-in-picture camera on another player) is not in the 26.3 edition yet: 26.3 changed the
 * renderer it hooks into (how chunks are chosen, the light map, the frame's textures), and that part has not been
 * ported. This keeps the commands and tools in place so they answer with a plain explanation, and everything that
 * asks "is an inset frame being drawn?" gets no. The 1.21.11 edition has the full thing.
 */
public final class Inset {
    private static final String NOT_YET = "The inset isn't in the 26.3 edition of Lensbridge yet (it is in the 1.21.11 "
            + "edition). On a server with Agentlink, /agentlink watch <player> shows a player through their eyes.";

    public static volatile boolean on;
    public static volatile boolean full;
    public static volatile String status = "";
    public static volatile int pictures;
    public static boolean thisFrame;
    public static boolean fullFrame;
    public static boolean poseApplied;
    public static boolean dark;
    public static Vec3 camPos = Vec3.ZERO;
    public static float camYaw;
    public static float camPitch;

    private Inset() {
    }

    public static void start(String who, String m, int f, float s, String c) {
        throw new IllegalStateException(NOT_YET);
    }

    public static void stop() {
        on = false;
        full = false;
    }

    public static void setFull(boolean b) {
        full = false;
    }

    public static boolean hides(Entity e) {
        return false;
    }

    public static void normalFrame() {
    }

    public static boolean wantsFrame() {
        return false;
    }

    public static Path snapshot(Path file) throws java.io.IOException {
        throw new IllegalStateException(NOT_YET);
    }

    static void onFramePre(RenderFrameEvent.Pre event) {
    }

    static void onFramePost(RenderFrameEvent.Post event) {
    }

    static void onHud(RenderGuiEvent.Post event) {
    }
}
