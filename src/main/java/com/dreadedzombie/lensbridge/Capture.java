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

    /** Grab the current frame to {@code file} (PNG). Waits {@code settleFrames} frames first. */
    public static Path grab(Path file, int settleFrames) {
        if (settleFrames > 0) {
            Game.waitFrames(settleFrames);
        }
        CompletableFuture<NativeImage> image = new CompletableFuture<>();
        Game.run(() -> Screenshot.takeScreenshot(Game.mc().getMainRenderTarget(), image::complete));
        try (NativeImage img = image.get(10, TimeUnit.SECONDS)) {
            Files.createDirectories(file.getParent());
            img.writeToFile(file);
            return file;
        } catch (Exception e) {
            throw new RuntimeException("Screenshot failed: " + e.getMessage(), e);
        }
    }

    /** Grab with the HUD temporarily hidden (restores the previous setting). */
    public static Path grabClean(Path file, boolean hideHud, int settleFrames) {
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
