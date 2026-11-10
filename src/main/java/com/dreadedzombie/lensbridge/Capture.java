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
        Game.run(() -> Screenshot.takeScreenshot(Game.mc().gameRenderer.mainRenderTarget(), image::complete));
        try {
            return image.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Frame grab failed: " + e.getMessage(), e);
        }
    }

    /** Start a streamed MP4 sized to the current window. */
    public static VideoStream stream(String name, int fps) {
        int[] size = Game.call(() -> new int[]{Game.mc().gameRenderer.mainRenderTarget().width, Game.mc().gameRenderer.mainRenderTarget().height});
        return new VideoStream(dir("videos").resolve(name + ".mp4"), size[0], size[1], fps);
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
        Stealth.begin();
        try {
            return grabCleanInner(file, hideHud, settleFrames);
        } finally {
            Stealth.end();
        }
    }

    private static Path grabCleanInner(Path file, boolean hideHud, int settleFrames) {
        boolean before = Game.call(Game::hudHidden);
        if (hideHud != before) {
            Game.run(() -> Game.setHudHidden(hideHud));
        }
        try {
            return grab(file, Math.max(settleFrames, hideHud != before ? 2 : 0));
        } finally {
            if (hideHud != before) {
                Game.run(() -> Game.setHudHidden(before));
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
