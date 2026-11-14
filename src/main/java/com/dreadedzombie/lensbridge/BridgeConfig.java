package com.dreadedzombie.lensbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * config/lensbridge.json. The bridge only listens on localhost and every request must carry the token
 * ({@code Authorization: Bearer <token>}). Delete the file to regenerate a new token.
 */
public final class BridgeConfig {
    public boolean enabled = true;
    public String host = "127.0.0.1";
    public int port = 25599;
    public String token = "";
    /** Where screenshots, frame sequences and videos go (relative to the game directory). */
    public String captureDir = "lensbridge";
    /** Longest edge of the image returned inline to the AI (full-size files are always saved to disk). */
    public int previewMaxEdge = 1280;
    /** Keep the game running (no pause menu) when its window loses focus, so it can be driven in the background. */
    public boolean keepRunningUnfocused = true;
    /** Capture director-camera shots without showing them in the window (the screen holds its last frame instead of flashing). */
    public boolean stealthCapture = true;
    /** Path or name of the ffmpeg binary used to encode videos. */
    public String ffmpeg = "ffmpeg";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static BridgeConfig load(Path file) {
        BridgeConfig config = new BridgeConfig();
        try {
            if (Files.exists(file)) {
                config = GSON.fromJson(Files.readString(file), BridgeConfig.class);
                if (config == null) {
                    // an empty file parses to null (a crash mid-write, a full disk): that used to stop the mod loading
                    throw new IllegalStateException("the file is empty");
                }
            }
        } catch (IOException | RuntimeException e) {
            Lensbridge.LOGGER.warn("Lensbridge: could not read {}, using defaults (the old file is kept as {}.broken)",
                    file, file.getFileName(), e);
            try {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException copy) {
                Lensbridge.LOGGER.warn("Lensbridge: could not keep a copy of the unreadable config", copy);
            }
            config = new BridgeConfig();
        }
        if (config.host == null || config.host.isBlank()) {
            config.host = "127.0.0.1";
        }
        if (config.captureDir == null || config.captureDir.isBlank()) {
            config.captureDir = "lensbridge";
        }
        if (config.ffmpeg == null || config.ffmpeg.isBlank()) {
            config.ffmpeg = "ffmpeg";
        }
        if (config.token == null || config.token.isBlank()) {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            config.token = HexFormat.of().formatHex(bytes);
        }
        try {
            Files.createDirectories(file.getParent());
            // temp file + move: a crash mid-write can't leave an empty config (and with it a lost token)
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(config));
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Lensbridge.LOGGER.warn("Lensbridge: could not write {}", file, e);
        }
        return config;
    }
}
