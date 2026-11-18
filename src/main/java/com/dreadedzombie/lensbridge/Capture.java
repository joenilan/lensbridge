package com.dreadedzombie.lensbridge;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import net.minecraft.client.Screenshot;

/**
 * Captures the game's own framebuffer (never the desktop) to PNG files, plus small inline previews.
 * All methods block and must be called OFF the client thread.
 */
public final class Capture {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private Capture() {
    }

    public static Path dir(String sub) {
        Path d = Game.mc().gameDirectory.toPath().resolve(Lensbridge.config().captureDir).resolve(sub);
        try {
            Files.createDirectories(d);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create " + d, e);
        }
        return d;
    }

    public static String stamp() {
        return LocalDateTime.now().format(STAMP);
    }

    // ---------------------------------------------------------------- one capture at a time

    /**
     * Every capture sequence saves and restores the HUD setting and the director camera. Two at once (two thumbnail
     * batches, a thumbnail during a recording) restore each other's half-way state and mix frames between outputs, so
     * one lock is held around each sequence. Re-entrant: a sequence may call another (render -> grab).
     */
    private static final java.util.concurrent.locks.ReentrantLock LOCK = new java.util.concurrent.locks.ReentrantLock();
    private static final int LOCK_WAIT_SECONDS = 30;
    /**
     * What the caller asked for with {@code hide_player} (null: not said, use the config's default). Set by
     * BridgeServer.call for the duration of one tool call, on that call's thread; read when the capture lock is taken.
     */
    public static final ThreadLocal<Boolean> HIDE_PLAYER_REQUEST = new ThreadLocal<>();
    /** While a capture holds the lock: the player's own model (and hand) is left out of every frame. */
    private static volatile boolean hidePlayer;

    /** Set by the player's panic key: the running capture stops at its next frame grab. */
    private static volatile boolean abort;

    /** Take the capture lock (HTTP / worker threads only, never the client thread). Pair with {@link #unlock()}. */
    public static void lock() {
        try {
            if (!LOCK.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                // a clear error instead of hanging forever behind an hour-long recording
                throw new IllegalStateException("Another capture is running (a recording, a render or thumbnails) and didn't "
                        + "finish within " + LOCK_WAIT_SECONDS + " s. Wait for it, or stop it (Alt+R stops a recording; "
                        + "Alt+Backspace in game stops any capture), then try again.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for another capture to finish");
        }
        if (LOCK.getHoldCount() == 1) {
            abort = false;   // a new sequence: an earlier panic press doesn't apply to it
            Boolean asked = HIDE_PLAYER_REQUEST.get();
            hidePlayer = asked != null ? asked : Lensbridge.config().hidePlayerInCaptures;
        }
    }

    public static void unlock() {
        if (LOCK.isHeldByCurrentThread() && LOCK.getHoldCount() == 1) {
            hidePlayer = false;   // the sequence is over (finished, failed or stopped): the player shows again
        }
        LOCK.unlock();
    }

    /**
     * The local player is being kept out of the picture: their model with everything drawn on it (armour, held items,
     * cape, elytra, name tag, shoulder parrots) and its shadow, plus the first-person hand. Render thread.
     */
    public static boolean hidingPlayer() {
        return hidePlayer;
    }

    /** Release the hiding at once (panic key). Any thread. */
    public static void stopHidingPlayer() {
        hidePlayer = false;
    }

    /** A capture sequence (a recording, a render, screenshots, thumbnails) is running. Any thread. */
    public static boolean busy() {
        return LOCK.isLocked();
    }

    /** Panic key: make the capture that is running (if any) stop at its next frame. Any thread. */
    public static void abortRunning() {
        abort = true;
    }

    /** A file name from a tool argument: only [A-Za-z0-9_.-] and no leading dot, so "../" can't leave the captures folder. */
    public static String safeName(String name) {
        String s = name == null ? "" : name.replaceAll("[^A-Za-z0-9_.-]", "_").replaceAll("^\\.+", "");
        return s.isEmpty() ? "capture" : s;
    }

    private static volatile long lastGrab;

    /** After an idle spell the render pipeline may hand back a stale frame; warm it up first. */
    private static int warm(int settleFrames) {
        long now = System.currentTimeMillis();
        int s = now - lastGrab > 1500 ? Math.max(settleFrames, 12) : settleFrames;
        lastGrab = now;
        return s;
    }

    /** Grab the current frame to {@code file} (PNG). Waits {@code settleFrames} frames first. */
    public static Path grab(Path file, int settleFrames) {
        // same path as grabImage, so saved screenshots get its checks too (director frame drawn, terrain built)
        try (NativeImage img = grabImage(settleFrames)) {
            Files.createDirectories(file.getParent());
            img.writeToFile(file);
            return file;
        } catch (Exception e) {
            throw new RuntimeException("Screenshot failed: " + e.getMessage(), e);
        }
    }

    /** Grab the current frame as an image (caller must close it). Off-thread. */
    public static NativeImage grabImage(int settleFrames) {
        if (abort && LOCK.isHeldByCurrentThread()) {
            throw new IllegalStateException("Capture stopped by the player (Alt+Backspace)");
        }
        boolean cold = System.currentTimeMillis() - lastGrab > 1500;   // first capture after a pause (warm() resets it)
        settleFrames = warm(settleFrames);
        // A capture meant to show the director camera must read a frame that was really drawn from it. Right after a
        // relaunch, a shader reload or with other mods hooking the frame, the last finished frame can still be the
        // player's own view (HUD and all), so wait until enough director frames have completed.
        boolean wantDirector = DirectorCamera.shown();
        long start = DirectorCamera.directorFrames;
        if (settleFrames > 0) {
            Game.waitFrames(settleFrames);
        }
        if (wantDirector && cold) {
            // first shot after a pause: give terrain that is still building (a teleport, a fresh camera spot) a moment.
            // Not for every capture: in some places (high in the sky) terrain never reports complete.
            Game.waitTerrain(2000);
        }
        if (wantDirector) {
            long need = start + Math.max(1, Math.min(2, settleFrames));
            int extra = 0;
            while (!(DirectorCamera.lastFrameDirector && DirectorCamera.directorFrames >= need) && extra < 240) {
                Game.waitFrames(1);
                extra++;
            }
            if (extra >= 240) {
                Lensbridge.LOGGER.warn("Capture: no frame was drawn from the director camera; the image may show the player's view");
            }
        }
        CompletableFuture<NativeImage> image = new CompletableFuture<>();
        Game.run(() -> Screenshot.takeScreenshot(Game.mc().getMainRenderTarget(), image::complete));
        try {
            return image.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Frame grab failed: " + e.getMessage(), e);
        }
    }

    /** Start a streamed MP4 sized to the current window. */
    public static VideoStream stream(String name, int fps) {
        int[] size = Game.call(() -> new int[]{Game.mc().getMainRenderTarget().width, Game.mc().getMainRenderTarget().height});
        return new VideoStream(dir("videos").resolve(safeName(name) + ".mp4"), size[0], size[1], fps);
    }

    /** Pull {@code count} evenly spaced stills out of a finished video (for contact sheets). */
    public static List<Path> thumbnails(Path video, int totalFrames, int count) {
        Path dir = dir("frames").resolve(video.getFileName().toString().replace(".mp4", "") + "_thumbs");
        int step = Math.max(1, totalFrames / Math.max(1, count));
        List<String> cmd = List.of(Lensbridge.config().ffmpeg, "-y", "-loglevel", "error", "-i", video.toString(),
                "-vf", "select=not(mod(n\\," + step + ")),scale=640:-1", "-vsync", "vfr", "-frames:v", String.valueOf(count),
                dir.resolve("thumb_%02d.png").toString());
        try {
            Files.createDirectories(dir);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor(2, TimeUnit.MINUTES);
            try (var files = Files.list(dir)) {
                return files.filter(f -> f.getFileName().toString().startsWith("thumb_")).sorted().toList();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /** Grab with the HUD temporarily hidden (restores the previous setting). */
    public static Path grabClean(Path file, boolean hideHud, int settleFrames) {
        lock();
        Stealth.begin();
        try {
            return grabCleanInner(file, hideHud, settleFrames);
        } finally {
            Stealth.end();
            unlock();
        }
    }

    private static Path grabCleanInner(Path file, boolean hideHud, int settleFrames) {
        boolean before = Game.call(() -> Game.mc().options.hideGui);
        if (hideHud != before) {
            Game.run(() -> Game.mc().options.hideGui = hideHud);
        }
        try {
            return grab(file, Math.max(settleFrames, hideHud != before ? 2 : 0));
        } finally {
            if (hideHud != before) {
                Game.run(() -> Game.mc().options.hideGui = before);
            }
        }
    }

    /** Downscaled preview of a PNG for inline return (JPEG, base64). */
    public static String previewBase64(Path png, int maxEdge) {
        try {
            BufferedImage src = ImageIO.read(png.toFile());
            double k = Math.min(1.0D, (double) maxEdge / Math.max(src.getWidth(), src.getHeight()));
            int w = Math.max(1, (int) Math.round(src.getWidth() * k));
            int h = Math.max(1, (int) Math.round(src.getHeight() * k));
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(out, "jpg", bytes);
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + png, e);
        }
    }

    /** Contact sheet: up to {@code count} evenly spaced frames from a sequence, tiled into one preview. */
    public static Path contactSheet(List<Path> frames, int count, Path out) {
        try {
            List<BufferedImage> picks = new ArrayList<>();
            for (int i = 0; i < count && !frames.isEmpty(); i++) {
                int idx = (int) Math.round((double) i * (frames.size() - 1) / Math.max(1, count - 1));
                picks.add(ImageIO.read(frames.get(idx).toFile()));
            }
            int cols = (int) Math.ceil(Math.sqrt(picks.size()));
            int rows = (int) Math.ceil((double) picks.size() / cols);
            int tw = 480;
            int th = (int) Math.round(tw * (double) picks.get(0).getHeight() / picks.get(0).getWidth());
            BufferedImage sheet = new BufferedImage(cols * tw, rows * th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = sheet.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (int i = 0; i < picks.size(); i++) {
                g.drawImage(picks.get(i), (i % cols) * tw, (i / cols) * th, tw, th, null);
            }
            g.dispose();
            ImageIO.write(sheet, "png", out.toFile());
            return out;
        } catch (IOException e) {
            throw new RuntimeException("Contact sheet failed", e);
        }
    }

    /**
     * A sheet of shots in a grid, each with a bar under it: the name in bold and a caption (its home, say) beside it.
     * For mod pages and listings. cols: shots per row.
     */
    public static Path labelledSheet(List<Path> shots, List<String> names, List<String> captions, int cols, Path out) {
        try {
            if (shots.isEmpty()) {
                throw new IllegalArgumentException("No shots for the sheet");
            }
            int c = Math.max(1, Math.min(cols, shots.size()));
            int rows = (int) Math.ceil((double) shots.size() / c);
            int tw = 640;
            BufferedImage first = ImageIO.read(shots.get(0).toFile());
            int th = (int) Math.round(tw * (double) first.getHeight() / first.getWidth());
            int bar = 64;
            int gap = 8;
            int w = c * tw + (c + 1) * gap;
            int h = rows * (th + bar) + (rows + 1) * gap;
            BufferedImage sheet = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = sheet.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new java.awt.Color(0x16, 0x18, 0x1d));
            g.fillRect(0, 0, w, h);
            java.awt.Font nameFont = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 26);
            java.awt.Font capFont = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 19);
            for (int i = 0; i < shots.size(); i++) {
                int x = gap + (i % c) * (tw + gap);
                int y = gap + (i / c) * (th + bar + gap);
                BufferedImage img = i == 0 ? first : ImageIO.read(shots.get(i).toFile());
                g.drawImage(img, x, y, tw, th, null);
                g.setColor(new java.awt.Color(0x24, 0x27, 0x2f));
                g.fillRect(x, y + th, tw, bar);
                String name = i < names.size() ? names.get(i) : "";
                String cap = i < captions.size() ? captions.get(i) : "";
                g.setColor(java.awt.Color.WHITE);
                g.setFont(nameFont);
                g.drawString(name, x + 16, y + th + 28);
                if (!cap.isBlank()) {
                    g.setColor(new java.awt.Color(0xb8, 0xbe, 0xca));
                    g.setFont(capFont);
                    g.drawString(cap, x + 16, y + th + 52);
                }
            }
            g.dispose();
            ImageIO.write(sheet, "png", out.toFile());
            return out;
        } catch (IOException e) {
            throw new RuntimeException("Labelled sheet failed", e);
        }
    }

    /** Encode a numbered PNG sequence (frame_00000.png ...) with ffmpeg. Returns the output file. */
    public static Path encode(Path frameDir, int fps, Path out, boolean gif) {
        List<String> cmd = new ArrayList<>(List.of(Lensbridge.config().ffmpeg, "-y", "-loglevel", "error",
                "-framerate", String.valueOf(fps), "-i", frameDir.resolve("frame_%05d.png").toString()));
        if (gif) {
            cmd.addAll(List.of("-vf", "fps=" + Math.min(fps, 20) + ",scale=640:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse"));
        } else {
            cmd.addAll(List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "16", "-preset", "slow",
                    "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2"));
        }
        cmd.add(out.toString());
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
                throw new RuntimeException("ffmpeg failed: " + log);
            }
            return out;
        } catch (IOException e) {
            throw new RuntimeException("ffmpeg not found (set \"ffmpeg\" in config/lensbridge.json): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while encoding", e);
        }
    }
}
