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

    /**
     * Which camera is being looked through: "director" (free AI camera), "drone", a tripod name, or "player" when the
     * director camera isn't shown. Camera props use it to hide a camera's own prop and to decide who sees props.
     */
    public static volatile String source = "director";

    /** Set by CameraMixin when this frame's camera came from the director (render thread). */
    public static volatile boolean appliedThisFrame;
    /** Whether the most recently completed frame was drawn from the director camera, and how many such frames so far. */
    public static volatile boolean lastFrameDirector;
    public static volatile long directorFrames;

    /** End of every frame (from Game.onFrame): record whether it was a director frame. */
    static void frameDone(float partialTick) {
        boolean d = appliedThisFrame;
        appliedThisFrame = false;
        lastFrameDirector = d;
        if (d) {
            directorFrames++;
            if (!preview) {
                playerView(partialTick);   // a hidden capture frame: the player isn't looking through this camera
            }
        }
    }

    /** True while {@link #playerView} runs: CameraMixin then leaves the camera alone. */
    public static boolean restoringPlayerView;

    /**
     * Put the game's camera back on the player after a hidden camera frame (a stealth capture, an inset picture).
     * Between frames the game and other mods read the camera: sound follows it, and Jade/WAILA works out "what am I
     * looking at" from it. Left on the hidden camera, the player would see tooltips for blocks they aren't looking
     * at and hear another place. Render thread.
     */
    public static void playerView(float partialTick) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        restoringPlayerView = true;
        try {
            // 26.x: update() places the camera on the player again (CameraMixin leaves it alone while this runs)
            mc.gameRenderer.mainCamera().update(mc.getDeltaTracker());
        } finally {
            restoringPlayerView = false;
        }
    }

    public static void set(Vec3 p, float y, float pi, float r, float f) {
        Policy.requireCameras();
        source = "director";
        program = null;
        pos = p;
        yaw = y;
        pitch = pi;
        roll = r;
        fov = f;
        active = true;
    }

    /** When true the player watches the director camera live (hotkey drone/tripods, camera_preview). */
    public static volatile boolean preview;

    /**
     * The player is really looking through the director camera (active AND previewed). A hidden one (an AI's capture
     * camera) is not the player's view: "save my view" style actions must use the player's eyes then.
     */
    public static boolean onScreen() {
        return active && preview;
    }

    /** Whether this frame should be seen through the director camera. */
    public static boolean shown() {
        return active && (preview || Stealth.active() || !Lensbridge.config().stealthCapture);
    }

    /**
     * Advance live programs every frame (even while not shown, so drones keep flying). Called from MinecraftMixin just
     * before the camera is placed: since 26.x RenderFrameEvent.Pre fires only after the camera and entities have been
     * extracted for the frame, so using it would show every shot (and prop) one frame late.
     */
    public static void beforeCamera(float pt) {
        if (active && !Policy.camerasAllowed()) {
            UserControls.stopPilot();
            release();
            net.minecraft.client.player.LocalPlayer pl = net.minecraft.client.Minecraft.getInstance().player;
            if (pl != null) {
                pl.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
                        "[Lensbridge] Free cameras aren't allowed for you here - back to your view"));
            }
        }
        if (active) {
            frame(pt);
        }
        com.dreadedzombie.lensbridge.cinema.Props.frame(pt);
    }

    public static void release() {
        active = false;
        preview = false;
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
        Policy.requireCameras();
        source = p instanceof com.dreadedzombie.lensbridge.cinema.Drone ? "drone" : "director";
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

    /** Called every frame from beforeCamera, before the camera is applied. Render thread. */
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
        if (shown()) {
            event.setYaw(yaw);
            event.setPitch(pitch);
            event.setRoll(roll);
        }
    }

    static void onFov(ViewportEvent.ComputeFov event) {
        if (shown() && fov > 0) {
            event.setFOV(fov);
        }
    }
}
