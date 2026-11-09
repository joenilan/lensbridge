package com.dreadedzombie.lensbridge;

import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * A free "director" camera, independent of the player: position, yaw/pitch/roll and FOV. While active it
 * replaces the game camera every frame (see CameraMixin) and the player model is drawn like in third person.
 * Chunks only render where the player has them loaded, so stay within render distance of the player
 * (or move the player too, e.g. spectator + /tp, for far shots).
 */
public final class DirectorCamera {
    public static volatile boolean active;
    public static volatile Vec3 pos = Vec3.ZERO;
    public static volatile float yaw;
    public static volatile float pitch;
    public static volatile float roll;
    /** Degrees; <= 0 = use the player's FOV setting. */
    public static volatile float fov;

    private DirectorCamera() {
    }

    public static void set(Vec3 p, float y, float pi, float r, float f) {
        program = null;
        pos = p;
        yaw = y;
        pitch = pi;
        roll = r;
        fov = f;
        active = true;
    }

    public static void release() {
        active = false;
        program = null;
        manualTime = null;
        last = null;
    }

    // ------------------------------------------------------------------ live programs (orbit, pan, rail, follow...)

    public static volatile com.dreadedzombie.lensbridge.cinema.@org.jspecify.annotations.Nullable CameraProgram program;
    /** When set (capture_program), the program is evaluated at this time instead of real time. */
    public static volatile @org.jspecify.annotations.Nullable Double manualTime;
    private static volatile long programStart;
    private static volatile com.dreadedzombie.lensbridge.cinema.@org.jspecify.annotations.Nullable Shot last;
    public static volatile boolean programFinished;

    public static void play(com.dreadedzombie.lensbridge.cinema.CameraProgram p) {
        last = null;
        programFinished = false;
        programStart = System.nanoTime();
        manualTime = null;
        program = p;
        active = true;
    }

    public static double elapsed() {
        Double m = manualTime;
        return m != null ? m : (System.nanoTime() - programStart) / 1e9;
    }

    /** Called every frame from CameraMixin before the camera is applied. Render thread. */
    public static void frame(float partialTick) {
        com.dreadedzombie.lensbridge.cinema.CameraProgram p = program;
        if (p == null || !active) {
            return;
        }
        com.dreadedzombie.lensbridge.cinema.Shot s = p.eval(elapsed(), partialTick, last);
        if (s == null) {
            programFinished = true;      // hold the last frame
            return;
        }
        last = s;
        pos = s.pos();
        yaw = s.yaw();
        pitch = s.pitch();
        roll = s.roll();
        fov = s.fov();
    }

    /** yaw/pitch that look from {@code from} at {@code to} (Minecraft convention: yaw 0 = +Z, pitch + = down). */
    public static float[] lookAt(Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float y = (float) (Math.toDegrees(Math.atan2(-d.x, d.z)));
        float p = (float) (-Math.toDegrees(Math.atan2(d.y, h)));
        return new float[]{y, p};
    }

    static void onAngles(ViewportEvent.ComputeCameraAngles event) {
        if (active) {
            event.setYaw(yaw);
            event.setPitch(pitch);
            event.setRoll(roll);
        }
    }

    static void onFov(ViewportEvent.ComputeFov event) {
        if (active && fov > 0) {
            event.setFOV(fov);
        }
    }
}
