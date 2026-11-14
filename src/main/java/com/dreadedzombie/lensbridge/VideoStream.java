package com.dreadedzombie.lensbridge;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Streams raw frames straight into ffmpeg (no PNG per frame) - fast enough for real-time recording.
 * Frames are RGBA (NativeImage ABGR ints written little-endian).
 */
public final class VideoStream implements AutoCloseable {
    private final Process process;
    private final OutputStream out;
    private final Path file;
    private final int width;
    private final int height;
    private ByteBuffer buffer;
    private byte[] last;
    private int frames;
    private final int fps;

    public VideoStream(Path file, int width, int height, int fps) {
        this.file = file;
        this.width = width;
        this.height = height;
        this.fps = fps;
        List<String> cmd = List.of(Lensbridge.config().ffmpeg, "-y", "-loglevel", "error",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-r", String.valueOf(fps), "-i", "-",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "17", "-preset", "veryfast",
                "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2", "-movflags", "+faststart", file.toString());
        try {
            this.process = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            throw new RuntimeException("ffmpeg not found (set \"ffmpeg\" in config/lensbridge.json): " + e.getMessage(), e);
        }
        this.out = new BufferedOutputStream(this.process.getOutputStream(), 1 << 20);
    }

    /** Write one frame (must match the stream size; a resized window ends the clip). Does not close the image. */
    public void write(NativeImage img) {
        if (img.getWidth() != this.width || img.getHeight() != this.height) {
            throw new IllegalStateException("Window size changed during capture");
        }
        int[] px = img.getPixelsABGR();
        if (this.buffer == null) {
            this.buffer = ByteBuffer.allocate(px.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        }
        this.buffer.clear();
        for (int p : px) {
            this.buffer.putInt(p | 0xFF000000);
        }
        this.last = this.buffer.array();
        writeLast();
    }

    /** Repeat the previous frame (keeps real-time pacing when a capture runs late). */
    public void repeat() {
        if (this.last != null) {
            writeLast();
        }
    }

    private void writeLast() {
        try {
            this.out.write(this.last, 0, this.width * this.height * 4);
            this.frames++;
        } catch (IOException e) {
            throw new RuntimeException("ffmpeg stopped accepting frames", e);
        }
    }

    public int frames() {
        return this.frames;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    @Override
    public void close() {
        try {
            this.out.close();
            if (!this.process.waitFor(5, TimeUnit.MINUTES) || this.process.exitValue() != 0) {
                throw new RuntimeException("ffmpeg failed while encoding " + this.file);
            }
            com.google.gson.JsonObject e = new com.google.gson.JsonObject();
            e.addProperty("file", this.file.toString());
            e.addProperty("name", this.file.getFileName().toString());
            e.addProperty("frames", this.frames);
            e.addProperty("fps", this.fps);
            e.addProperty("seconds", this.fps > 0 ? Math.round(this.frames * 100.0 / this.fps) / 100.0 : 0);
            e.addProperty("width", this.width);
            e.addProperty("height", this.height);
            com.dreadedzombie.lensbridge.mcp.AgentlinkSupport.publish("lensbridge.video_saved", e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Path file() {
        return this.file;
    }
}
