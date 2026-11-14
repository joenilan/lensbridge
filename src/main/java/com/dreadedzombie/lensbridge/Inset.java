package com.dreadedzombie.lensbridge;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * A live picture-in-picture camera on another player while you keep playing your own view: a few times a second one
 * frame is drawn from the inset camera (stealth: never shown on screen), read back, and drawn small in a corner. For
 * staff keeping an eye on someone nearby (a player by name; or an entity by name tag or type). The client only has players and terrain near you (entity tracking and
 * render distance), so the target must be within range; farther away, the server's watch puts you there instead.
 * Pauses while Lensbridge records or captures, so it never ends up in a video. Free-camera rules apply.
 */
public final class Inset {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("lensbridge", "inset");

    public static volatile boolean on;
    public static volatile String target;
    /** chase (behind and above), front (facing them), overhead, or eyes (exactly what they see). */
    public static volatile String mode = "chase";
    public static volatile int fps = 30;
    /** Width as a fraction of the screen. */
    public static volatile float size = 0.3f;
    /** top_right, top_left, bottom_right, bottom_left. */
    public static volatile String corner = "top_right";
    /** What the label says: "Steve · 23 m", or why there's no picture. */
    public static volatile String status = "";

    /**
     * Full view: the watching camera is the person's whole screen (every frame, shown) instead of a small picture.
     * Esc or "/lensbridge inset small" goes back to the corner picture.
     */
    public static volatile boolean full;
    /** Set at the start of a full-view frame; read by CameraMixin. */
    public static boolean fullFrame;
    /** This frame looks through the watched one's own eyes (their model is left out of it). */
    private static boolean eyesNow;
    /** The watched one is somewhere dark: this camera's frames are drawn as with night vision. */
    public static boolean dark;
    private static Entity watched;
    private static int chaseAngle;
    private static long lastPose;
    /** Where the chase camera goes when straight behind is blocked: degrees around the watched one. */
    private static final float[] ANGLES = {0, 40, -40, 80, -80, 125, -125, 180};

    /** Set at the start of an inset frame (render thread); read by CameraMixin. */
    public static boolean thisFrame;
    public static Vec3 camPos = Vec3.ZERO;
    public static float camYaw;
    public static float camPitch;

    /** The inset camera was really used this frame (CameraMixin): false if something skipped drawing the frame. */
    public static boolean poseApplied;
    /** After an inset frame: stay hidden until a normal frame has really been drawn. */
    private static volatile boolean needNormalFrame;
    /** Counts pictures, so a snapshot can wait for a fresh one. */
    public static volatile int pictures;
    private static boolean cullChanged;
    private static boolean savedHideGui;
    private static volatile long lastShot;
    private static Picture texture;
    /** Someone asked for the picture as a file: the next inset frame is also read back. */
    private static volatile java.util.concurrent.CompletableFuture<NativeImage> wanted;
    private static int texW;
    private static int texH;
    private static Vec3 smoothed;
    private static boolean savedSmartCull = true;

    private Inset() {
    }

    public static void start(String who, String m, int f, float s, String c) {
        Policy.requireCameras();
        if (!Lensbridge.config().stealthCapture) {
            throw new IllegalStateException("The inset needs \"stealthCapture\": true in config/lensbridge.json "
                    + "(otherwise its frames would flash on your screen).");
        }
        target = who;
        mode = m == null ? "chase" : m.toLowerCase(Locale.ROOT);
        fps = Mth.clamp(f, 1, 60);
        size = Mth.clamp(s, 0.15f, 0.5f);
        corner = c == null ? "top_right" : c.toLowerCase(Locale.ROOT);
        smoothed = null;
        status = "looking for " + who + "...";
        Minecraft mc = Minecraft.getInstance();
        if (!FrameGuard.sodium()) {
            // vanilla renderer: the inset draws from the player's chunk list (see LevelRendererMixin), so don't
            // drop chunks the player can't see. Sodium works out each camera's own list and needs none of this.
            if (!cullChanged) {
                savedSmartCull = mc.smartCull;
                cullChanged = true;
            }
            mc.smartCull = false;
        }
        lastShot = 0;
        on = true;
    }

    public static void stop() {
        if (cullChanged) {
            Minecraft.getInstance().smartCull = savedSmartCull;
            cullChanged = false;
        }
        on = false;
        full = false;
        target = null;
        status = "";
    }

    /** A player by name; else the nearest entity with that name tag, or of that type (e.g. "villager"). */
    private static Entity find(Minecraft mc) {
        String who = target;
        if (mc.level == null || who == null || mc.player == null) {
            return null;
        }
        for (Player p : mc.level.players()) {
            if (p.getGameProfile().name().equalsIgnoreCase(who) && p != mc.player) {
                return p;
            }
        }
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) {
                continue;
            }
            boolean named = e.hasCustomName() && e.getCustomName().getString().equalsIgnoreCase(who);
            var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            boolean typed = type.toString().equalsIgnoreCase(who) || type.getPath().equalsIgnoreCase(who);
            double d = e.distanceToSqr(mc.player);
            if ((named || typed) && d < bestD) {
                best = e;
                bestD = d;
            }
        }
        return best;
    }

    private static String nameOf(Entity e) {
        return e instanceof Player p ? p.getGameProfile().name() : e.getName().getString();
    }

    /** Start of every frame: finish the last inset frame's stealth, maybe begin a new inset frame. */
    static void onFramePre(RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (needNormalFrame && mc.level == null) {
            normalFrame();   // left the world: nothing left to hide
        }
        if (!on) {
            return;
        }
        if (!Policy.camerasAllowed()) {
            stop();
            if (mc.player != null) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "[Lensbridge] Free cameras aren't allowed for you here - inset closed"), true);
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (mc.level == null || needNormalFrame) {
            return;
        }
        if (Stealth.active()) {
            // a hidden capture is running: its frames belong to the director camera. A full-view inset frame here would
            // make every grab wait for a director frame that never comes, then save the watched player's view instead.
            return;
        }
        if (!full && (now - lastShot < 1000 / Math.max(1, fps) || Recorder.running() || DirectorCamera.preview)) {
            return;
        }
        Entity p = find(mc);
        if (p == null) {
            status = target + " isn't near you (out of view range): use the server's watch instead";
            lastShot = now;   // look again at the usual pace
            watched = null;
            return;
        }
        pose(mc, p, event.getPartialTick().getGameTimeDeltaPartialTick(false), now);
        status = nameOf(p) + " · " + Math.round(mc.player == null ? 0 : mc.player.distanceTo(p)) + " m" + (eyesNow ? " · their eyes" : "");
        if (full) {
            fullFrame = true;   // a normal, shown frame through the watching camera
            return;
        }
        lastShot = now;
        thisFrame = true;
        poseApplied = false;
        Stealth.begin();
        savedHideGui = mc.options.hideGui;
        mc.options.hideGui = true;
    }

    /** Where the watching camera is this frame, and where it looks. */
    private static void pose(Minecraft mc, Entity p, float pt, long now) {
        Vec3 eye = p.getEyePosition(pt);
        float yaw = p.getViewYRot(pt);
        watched = p;
        dark = mc.level.getMaxLocalRawBrightness(net.minecraft.core.BlockPos.containing(eye)) < 5;
        eyesNow = false;
        float dt = Math.min(0.25f, (now - lastPose) / 1000f);
        lastPose = now;
        if ("eyes".equals(mode)) {
            eyes(p, eye, yaw, pt);
            return;
        }
        Vec3 want;
        if ("front".equals(mode)) {
            want = eye.add(Vec3.directionFromRotation(0, yaw).scale(4)).add(0, 0.6, 0);
        } else if ("overhead".equals(mode)) {
            want = eye.add(0, 14, 0.01);
        } else {
            // behind them; if that's a wall, the nearest side with room (and stay there while it's good enough)
            double best = -1;
            int bestI = 0;
            for (int n = 0; n <= ANGLES.length; n++) {
                int i = n == 0 ? chaseAngle : n - 1;
                double r = room(mc, p, eye, behind(eye, yaw + ANGLES[i]));
                if (n == 0 && r >= 3 || r >= 4.5) {
                    best = r;
                    bestI = i;
                    break;
                }
                if (r > best + 0.01) {
                    best = r;
                    bestI = i;
                }
            }
            chaseAngle = bestI;
            want = behind(eye, yaw + ANGLES[bestI]);
        }
        if (room(mc, p, eye, want) < 1.2) {
            eyes(p, eye, yaw, pt);   // a one-block tunnel, a tiny room: nowhere to stand, so look through their eyes
            return;
        }
        Vec3 goal = within(mc, p, eye, want);
        double k = 1 - Math.exp(-dt * 10);
        smoothed = smoothed == null || smoothed.distanceTo(goal) > 20 ? goal : smoothed.lerp(goal, k);
        // like the game's own third-person view: never inside a wall, a roof or leaves - move in front of what's in the way
        Vec3 cam = within(mc, p, eye, smoothed);
        Vec3 d = eye.subtract(cam);
        camPos = cam;
        camYaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
        camPitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
    }

    private static void eyes(Entity p, Vec3 eye, float yaw, float pt) {
        eyesNow = true;
        smoothed = null;
        camPos = eye;
        camYaw = yaw;
        camPitch = p.getViewXRot(pt);
    }

    private static Vec3 behind(Vec3 eye, float yaw) {
        return eye.subtract(Vec3.directionFromRotation(0, yaw).scale(5)).add(0, 2.2, 0);
    }

    /** How far the camera can go from the eye toward a spot before something is in the way. */
    private static double room(Minecraft mc, Entity p, Vec3 eye, Vec3 to) {
        net.minecraft.world.phys.BlockHitResult hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, to,
                net.minecraft.world.level.ClipContext.Block.VISUAL, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? eye.distanceTo(to)
                : Math.max(0.2, hit.getLocation().distanceTo(eye) - 0.35);
    }

    private static Vec3 within(Minecraft mc, Entity p, Vec3 eye, Vec3 to) {
        double len = eye.distanceTo(to);
        double r = room(mc, p, eye, to);
        return r >= len || len < 1e-6 ? to : eye.add(to.subtract(eye).scale(r / len));
    }

    /** The watched one's own model is left out of a through-their-eyes frame. Render thread (EntityRenderDispatcherMixin). */
    public static boolean hides(Entity e) {
        return eyesNow && e == watched && (thisFrame || fullFrame);
    }

    /** Full view on or off. Any thread. */
    public static void setFull(boolean b) {
        full = b && on;
        smoothed = null;
    }

    /** End of every frame: if it was an inset frame, read it back (async) and restore the HUD setting. */
    static void onFramePost(RenderFrameEvent.Post event) {
        fullFrame = false;
        if (!thisFrame) {
            return;
        }
        thisFrame = false;
        Minecraft mc = Minecraft.getInstance();
        mc.options.hideGui = savedHideGui;
        // Put the game's camera back on the player straight away. Between frames the game and other mods read it:
        // sound follows it, and Jade/WAILA works out "what am I looking at" from it, so left on the inset it would
        // show the watched player's target and play their surroundings.
        if (poseApplied) {
            DirectorCamera.playerView(event.getPartialTick().getGameTimeDeltaPartialTick(true));
        }
        // the buffer swap comes after this event: stay hidden until a normal frame has really been drawn
        needNormalFrame = true;
        if (!poseApplied) {
            return;   // another mod skipped drawing this frame (e.g. window in the background): no picture to read
        }
        // keep the picture on the graphics card: one texture-to-texture copy, cheap enough for a smooth inset
        com.mojang.blaze3d.textures.GpuTexture frame = mc.getMainRenderTarget().getColorTexture();
        if (frame == null || !on) {
            return;
        }
        if (texture == null) {
            texture = new Picture();
            mc.getTextureManager().register(ID, texture);
        }
        texture.take(frame);
        texW = frame.getWidth(0);
        texH = frame.getHeight(0);
        pictures++;
        java.util.concurrent.CompletableFuture<NativeImage> w = wanted;
        if (w != null) {
            wanted = null;
            Screenshot.takeScreenshot(mc.getMainRenderTarget(), w::complete);
        }
    }

    /** The inset's own texture: a copy of the hidden frame, same size as the window. */
    private static final class Picture extends AbstractTexture {
        private int w;
        private int h;

        void take(com.mojang.blaze3d.textures.GpuTexture frame) {
            int fw = frame.getWidth(0);
            int fh = frame.getHeight(0);
            com.mojang.blaze3d.systems.GpuDevice device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
            if (this.texture == null || fw != w || fh != h) {
                close();
                w = fw;
                h = fh;
                this.texture = device.createTexture(() -> "lensbridge inset",
                        com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST | com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING,
                        frame.getFormat(), w, h, 1, 1);
                this.textureView = device.createTextureView(this.texture);
                this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
                        .getClampToEdge(com.mojang.blaze3d.textures.FilterMode.LINEAR);
            }
            device.createCommandEncoder().copyTextureToTexture(frame, this.texture, 0, 0, 0, 0, 0, w, h);
        }
    }

    /** A normal (non-inset) world frame is being drawn: it may be shown again. Render thread (CameraMixin). */
    public static void normalFrame() {
        if (needNormalFrame) {
            needNormalFrame = false;
            Stealth.end();
        }
    }

    /** An inset is open (or its last hidden frame still needs a normal one after it). Any thread. */
    public static boolean wantsFrame() {
        // the whole time an inset is open: the person is watching their screen, so their own view must stay smooth
        return needNormalFrame || on;
    }

    /** HUD: the inset in its corner with a label. */
    static void onHud(RenderGuiEvent.Post event) {
        // never inside an inset frame (NeoForge fires this even with the HUD hidden): no picture in the picture
        if (!on || thisFrame || Minecraft.getInstance().options.hideGui) {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        Minecraft mc = Minecraft.getInstance();
        if (full) {
            String text = "● Watching " + (status.length() > 70 ? status.substring(0, 70) + "..." : status) + "  -  Esc: your own view";
            int tw = mc.font.width(text);
            int tx = (g.guiWidth() - tw) / 2;
            int ty = g.guiHeight() - 62;   // above the hotbar: the top of the screen belongs to Jade/WAILA and boss bars
            g.fill(tx - 4, ty - 3, tx + tw + 4, ty + 10, 0xA0000000);
            g.drawString(mc.font, text, tx, ty, 0xFFFF6060, false);
            return;
        }
        int sw = g.guiWidth();
        int sh = g.guiHeight();
        int w = Math.round(sw * size);
        int h = texture == null ? Math.round(w * 9 / 16f) : Math.round(w * (float) texH / texW);
        int x = corner.endsWith("left") ? 6 : sw - w - 6;
        int y = corner.startsWith("bottom") ? sh - h - 40 : 6;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFFB050FF);
        if (texture == null || status.contains("isn't near")) {
            g.fill(x, y, x + w, y + h, 0xC0000000);
        } else {
            // the frame is stored bottom row first: draw it flipped
            g.blit(RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND, ID, x, y, 0f, (float) texH, w, h, texW, -texH, texW, texH);
        }
        g.fill(x, y + h - 11, x + w, y + h, 0xA0000000);
        String label = mc.font.plainSubstrByWidth("● " + status.replace(" · their eyes", " · eyes"), w - 6);
        g.drawString(mc.font, label, x + 3, y + h - 10, 0xFFFF6060, false);
    }

    /** Save the next inset picture as a PNG (evidence). Off the render thread: waits up to 3 s for that picture. */
    public static Path snapshot(Path file) throws java.io.IOException {
        if (!on) {
            throw new IllegalStateException("No inset is open.");
        }
        java.util.concurrent.CompletableFuture<NativeImage> f = new java.util.concurrent.CompletableFuture<>();
        wanted = f;
        try (NativeImage img = f.get(3, java.util.concurrent.TimeUnit.SECONDS)) {
            Files.createDirectories(file.getParent());
            img.writeToFile(file);
            return file;
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            wanted = null;
            throw new IllegalStateException("No inset picture right now (" + status + ").");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted.");
        }
    }
}
