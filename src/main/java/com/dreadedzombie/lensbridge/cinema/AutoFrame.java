package com.dreadedzombie.lensbridge.cinema;

import com.dreadedzombie.lensbridge.Capture;
import com.dreadedzombie.lensbridge.DirectorCamera;
import com.dreadedzombie.lensbridge.Game;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Finds where an entity's MODEL really is, which can differ a lot from its position and hitbox (flying mobs, custom
 * renderers, scaled models). Two probe views (from the south and from the east) are each shot twice, with and without
 * the entity drawn; the pixels that change are the model. Their centroid gives the visual centre in 3D and their
 * extent gives the model's size, so a turntable can be centred and sized to fit.
 */
public final class AutoFrame {
    public record Result(Vec3 centre, double halfSize, String note) {
    }

    private static final float FOV = 50f;
    private static final int THRESHOLD = 12;   // low: dark wings on a dark backdrop change pixels only slightly

    private AutoFrame() {
    }

    /** Off-thread. Leaves the director camera wherever the last probe put it. */
    public static Result measure(int entityId, Vec3 start, double probeDistance) {
        // nothing may move between the shown/hidden probe shots, so hold the world still while measuring
        // the entity's shadow vanishes with it, so it would count as part of the model: no shadows while measuring
        boolean shadows = Game.call(() -> Game.mc().options.entityShadows().get());
        Game.run(() -> Game.mc().options.entityShadows().set(false));
        try {
            return measureFrozen(entityId, start, probeDistance);
        } finally {
            Game.run(() -> Game.mc().options.entityShadows().set(shadows));
        }
    }

    private static Result measureFrozen(int entityId, Vec3 start, double probeDistance) {
        boolean wasFrozen = com.dreadedzombie.lensbridge.tools.Rendering.worldFrozen();
        if (!wasFrozen) {
            com.dreadedzombie.lensbridge.tools.Rendering.cmd("tick freeze");
            Game.sleep(200);
        }
        try {
            return measureInner(entityId, start, probeDistance);
        } finally {
            if (!wasFrozen) {
                com.dreadedzombie.lensbridge.tools.Rendering.cmd("tick unfreeze");
            }
        }
    }

    private static Result measureInner(int entityId, Vec3 start, double probeDistance) {
        Vec3 c = start;
        double half = 0.5;
        StringBuilder note = new StringBuilder();
        // second view: from the side (+x) normally; from straight above when asked (on a Showcase Stage the next exhibit
        // in the row would block a side view, while nothing is ever above an exhibit)
        Vec3 second = topView ? new Vec3(0.001, 1, 0.001).normalize() : new Vec3(1, 0, 0);
        // two passes: the second probe is aimed at the first estimate, so the model is near the middle of the frame
        for (int pass = 0; pass < 2; pass++) {
            double[] front = probe(entityId, c, new Vec3(0, 0, 1), probeDistance);
            double[] other = probe(entityId, c, second, probeDistance);
            if (front == null || other == null) {
                note.append("auto-centre: model not found in the probe views; using the hitbox centre. ");
                return new Result(start, half, note.toString());
            }
            // front gives x and y; the side view gives z and y; the top view gives x and z
            double dx = topView ? (front[4] + other[4]) / 2 : front[4];
            double dy = topView ? front[5] : (front[5] + other[5]) / 2;
            double dz = other[6];
            c = c.add(dx, dy, dz);
            half = Math.max(Math.max(front[2], other[2]), Math.max(front[3], other[3]));
        }
        note.append(String.format(java.util.Locale.ROOT, "auto-centre: model centre %.2f %.2f %.2f, size ~%.2f blocks. ",
                c.x, c.y, c.z, half * 2));
        return new Result(c, half, note.toString());
    }

    /** Set by callers that want the top-down second probe (see measureInner). */
    public static volatile boolean topView;

    /**
     * One probe view. Returns {right offset, up offset, half width, half height} in blocks at the target's distance,
     * or null if no model pixels were found.
     */
    private static double[] probe(int entityId, Vec3 aim, Vec3 dir, double d) {
        Vec3 cam = aim.add(dir.scale(d));
        float[] yp = DirectorCamera.lookAt(cam, aim);
        DirectorCamera.set(cam, yp[0], yp[1], 0, FOV);
        int[] size = new int[2];
        boolean gui = Game.call(Game::hudHidden);
        Game.run(() -> Game.setHudHidden(true));
        int[] shown;
        try {
            shown = grabStable(size);
        } finally {
            Game.run(() -> Game.setHudHidden(gui));
        }
        boolean was = setInvisible(entityId, true);
        int[] hidden;
        Game.run(() -> Game.setHudHidden(true));
        try {
            hidden = grabStable(size);
        } finally {
            setInvisible(entityId, was);
            Game.run(() -> Game.setHudHidden(gui));
        }
        int w = size[0];
        int h = size[1];
        debugDump(shown, hidden, w, h, dir.x != 0 ? "side" : "front");
        // changed-pixel mask on a coarse grid (4x4 cells); then keep only the biggest connected blob, so twinkling
        // stars, shader shimmer or drifting particles elsewhere in the frame can't stretch the box
        final int cell = 4;
        int gw = (w + cell - 1) / cell;
        int gh = (h + cell - 1) / cell;
        int[] cnt = new int[gw * gh];
        boolean[] mask = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = shown[y * w + x];
                int q = hidden[y * w + x];
                int diff = Math.abs((p & 0xFF) - (q & 0xFF)) + Math.abs((p >> 8 & 0xFF) - (q >> 8 & 0xFF))
                        + Math.abs((p >> 16 & 0xFF) - (q >> 16 & 0xFF));
                if (diff > THRESHOLD) {
                    mask[y * w + x] = true;
                    cnt[(y / cell) * gw + x / cell]++;
                }
            }
        }
        int[] label = new int[gw * gh];
        int best = 0;
        long bestSize = 0;
        int next = 0;
        java.util.Map<Integer, Long> sizes = new java.util.HashMap<>();
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int i = 0; i < cnt.length; i++) {
            if (cnt[i] < 2 || label[i] != 0) {
                continue;
            }
            int id = ++next;
            long blob = 0;
            label[i] = id;
            queue.add(i);
            while (!queue.isEmpty()) {
                int c = queue.poll();
                blob += cnt[c];
                int cx = c % gw;
                int cy = c / gw;
                // 8-neighbours, plus a one-cell gap so thin antennae/legs stay attached to the body
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int nx = cx + dx;
                        int ny = cy + dy;
                        if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) {
                            continue;
                        }
                        int n2 = ny * gw + nx;
                        if (cnt[n2] >= 2 && label[n2] == 0) {
                            label[n2] = id;
                            queue.add(n2);
                        }
                    }
                }
            }
            sizes.put(id, blob);
            if (blob > bestSize) {
                bestSize = blob;
                best = id;
            }
        }
        if (best == 0 || bestSize < 20) {
            return null;
        }
        int[] cols = new int[w];
        int[] rows = new int[h];
        long n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int lb = label[(y / cell) * gw + x / cell];
                // every substantial piece of the model (a big moth's wings can separate from its body at low-contrast
                // joins); specks under 5% of the largest piece (stars, particles) still drop out
                if (mask[y * w + x] && lb != 0 && sizes.getOrDefault(lb, 0L) >= bestSize * 0.05) {
                    cols[x]++;
                    rows[y]++;
                    n++;
                }
            }
        }
        int minX = pct(cols, n, 0.002);
        int maxX = pct(cols, n, 0.998);
        int minY = pct(rows, n, 0.002);
        int maxY = pct(rows, n, 0.998);
        // pinhole camera: vertical FOV, square pixels. Use the box centre (not the pixel centroid) so long wings or
        // tails on one side don't drag the subject off-centre.
        double f = (h / 2.0) / Math.tan(Math.toRadians(FOV / 2));
        double u = (minX + maxX) / 2.0;
        double v = (minY + maxY) / 2.0;
        double r = (u - w / 2.0) / f * d;
        double up = (h / 2.0 - v) / f * d;
        // screen offsets -> world offset, from the probe camera's own axes (works for any probe direction)
        double yaw = Math.toRadians(yp[0]);
        double pitch = Math.toRadians(yp[1]);
        Vec3 fwd = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 upv = right.cross(fwd);
        Vec3 world = right.scale(r).add(upv.scale(up));
        return new double[] {r, up, (maxX - minX) / 2.0 / f * d, (maxY - minY) / 2.0 / f * d, world.x, world.y, world.z};
    }

    /**
     * Grab until two consecutive frames agree (under 0.3% of pixels changed), so a frame rendered before the camera
     * moved or the entity was hidden can never be measured. Glow particles keep a few pixels changing; that's fine.
     */
    private static int[] grabStable(int[] size) {
        int[] prev = null;
        for (int tries = 0; tries < 8; tries++) {
            int[] cur;
            try (NativeImage img = Capture.grabImage(tries == 0 ? 4 : 2)) {
                size[0] = img.getWidth();
                size[1] = img.getHeight();
                cur = pixels(img);
            }
            if (prev != null && prev.length == cur.length) {
                int changed = 0;
                for (int i = 0; i < cur.length; i++) {
                    if (cur[i] != prev[i]) {
                        changed++;
                    }
                }
                if (changed < cur.length * 0.003) {
                    return cur;
                }
            }
            prev = cur;
        }
        return prev;
    }

    /** Saves the last probe (shown | hidden | changed-pixel mask) to captures/frames/autoframe_<view>.png. */
    private static void debugDump(int[] shown, int[] hidden, int w, int h, String view) {
        try {
            int sw = w / 4;
            int sh = h / 4;
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(sw * 3, sh, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < sh; y++) {
                for (int x = 0; x < sw; x++) {
                    int p = shown[(y * 4) * w + x * 4];
                    int q = hidden[(y * 4) * w + x * 4];
                    out.setRGB(x, y, abgrToRgb(p));
                    out.setRGB(sw + x, y, abgrToRgb(q));
                    int diff = Math.abs((p & 0xFF) - (q & 0xFF)) + Math.abs((p >> 8 & 0xFF) - (q >> 8 & 0xFF))
                            + Math.abs((p >> 16 & 0xFF) - (q >> 16 & 0xFF));
                    out.setRGB(2 * sw + x, y, diff > THRESHOLD ? 0xFFFFFF : 0);
                    // (mask before blob filtering)
                }
            }
            java.nio.file.Path f = Capture.dir("frames").resolve("autoframe_" + view + ".png");
            javax.imageio.ImageIO.write(out, "png", f.toFile());
        } catch (Exception ignored) {
            // debugging aid only
        }
    }

    private static int abgrToRgb(int p) {
        return p & 0xFFFFFF; // NativeImage.getPixel is ARGB
    }

    private static int pct(int[] hist, long total, double q) {
        long want = (long) Math.ceil(total * q);
        long acc = 0;
        for (int i = 0; i < hist.length; i++) {
            acc += hist[i];
            if (acc >= want) {
                return i;
            }
        }
        return hist.length - 1;
    }

    private static int[] pixels(NativeImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[y * w + x] = img.getPixel(x, y);
            }
        }
        return out;
    }

    /** Client-side only: the server never hears about it, and a frozen world won't resend the flag. */
    private static boolean setInvisible(int entityId, boolean value) {
        return Game.call(() -> {
            Entity e = Game.mc().level == null ? null : Game.mc().level.getEntity(entityId);
            if (e == null) {
                return false;
            }
            boolean was = e.isInvisible();
            e.setInvisible(value);
            return was;
        });
    }
}
