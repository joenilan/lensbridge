package com.dreadedzombie.lensbridge;

import com.dreadedzombie.lensbridge.cinema.CameraProgram;
import com.dreadedzombie.lensbridge.cinema.Drone;
import com.dreadedzombie.lensbridge.cinema.Shot;
import com.dreadedzombie.lensbridge.cinema.Tripods;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Input;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Hands-on camera work for players: fly the drone (K), drop a tripod at your view (Y), cycle through
 * tripods (U), start/stop recording (J). Small HUD shows what's active (hidden when the HUD is hidden).
 */
public final class UserControls {
    static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Lensbridge.MODID, "camera"));
    static final KeyMapping DRONE = new KeyMapping("key.lensbridge.drone", GLFW.GLFW_KEY_K, CATEGORY);
    static final KeyMapping TRIPOD = new KeyMapping("key.lensbridge.tripod", GLFW.GLFW_KEY_Y, CATEGORY);
    static final KeyMapping CYCLE = new KeyMapping("key.lensbridge.cycle", GLFW.GLFW_KEY_U, CATEGORY);
    static final KeyMapping RECORD = new KeyMapping("key.lensbridge.record", GLFW.GLFW_KEY_J, CATEGORY);
    static final KeyMapping MANAGER = new KeyMapping("key.lensbridge.manager", GLFW.GLFW_KEY_M, CATEGORY);

    private static boolean piloting;
    private static float lockYaw;
    private static float lockPitch;
    private static int tripodIndex = -1;

    private UserControls() {
    }

    static void registerKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(DRONE);
        event.register(TRIPOD);
        event.register(CYCLE);
        event.register(RECORD);
        event.register(MANAGER);
    }

    public static boolean piloting() {
        return piloting && DirectorCamera.program instanceof Drone d && d.mode() == Drone.Mode.PILOT;
    }

    private static void say(String msg) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            p.displayClientMessage(Component.literal("[Lensbridge] " + msg), true);
        }
    }

    private static Shot playerView(LocalPlayer p) {
        return new Shot(p.getEyePosition(), p.getYRot(), p.getXRot(), 0, 0);
    }

    /** Start a pilotable drone at the given view (used by K and the drone_pilot tool). */
    public static void startPilot(Shot from, double maxSpeed) {
        LocalPlayer p = Minecraft.getInstance().player;
        DirectorCamera.play(new Drone(Drone.Mode.PILOT, from, null, List.of(), false, maxSpeed, 8, 120, 2.5, 0.6, 0.6,
                0, 0, 0, 0, 0, 0));
        piloting = true;
        DirectorCamera.preview = true;
        if (p != null) {
            lockYaw = p.getYRot();
            lockPitch = p.getXRot();
        }
    }

    public static void stopPilot() {
        piloting = false;
        Drone.stickForward = Drone.stickRight = Drone.stickUp = Drone.stickYaw = Drone.stickGimbal = 0;
        DirectorCamera.release();
    }

    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            return;
        }
        while (DRONE.consumeClick()) {
            if (piloting()) {
                stopPilot();
                say("Drone landed - back to your view");
            } else {
                Shot from = DirectorCamera.active
                        ? new Shot(DirectorCamera.pos, DirectorCamera.yaw, DirectorCamera.pitch, 0, DirectorCamera.fov) : playerView(p);
                startPilot(from, 6);
                say("Drone up: WASD fly, Space/Shift up/down, Sprint = fast, mouse = yaw + gimbal, K to land");
            }
        }
        while (TRIPOD.consumeClick()) {
            Shot v = DirectorCamera.active
                    ? new Shot(DirectorCamera.pos, DirectorCamera.yaw, DirectorCamera.pitch, 0, DirectorCamera.fov) : playerView(p);
            String name = Tripods.nextName();
            Tripods.put(new Tripods.Tripod(name, v.pos(), v.yaw(), v.pitch(), v.fov(), null));
            say("Placed " + name + " here (" + Tripods.all().size() + " tripods). U cycles through them.");
        }
        while (CYCLE.consumeClick()) {
            List<Tripods.Tripod> list = new ArrayList<>(Tripods.all().values());
            if (piloting()) {
                stopPilot();
            }
            tripodIndex++;
            if (list.isEmpty() || tripodIndex >= list.size()) {
                tripodIndex = -1;
                DirectorCamera.release();
                say(list.isEmpty() ? "No tripods yet - press Y to place one" : "Back to your view");
            } else {
                Tripods.Tripod t = list.get(tripodIndex);
                viewTripod(t);
                DirectorCamera.preview = true;
                say("Tripod " + (tripodIndex + 1) + "/" + list.size() + ": " + t.name());
            }
        }
        while (MANAGER.consumeClick()) {
            BridgeConfig c = Lensbridge.config();
            net.minecraft.util.Util.getPlatform().openUri("http://" + c.host + ":" + c.port + "/ui#" + c.token);
            say("Opened the camera manager in your browser");
        }
        while (RECORD.consumeClick()) {
            if (Recorder.running()) {
                Recorder.stop();
                say("Recording stopped - encoding...");
            } else {
                String name = "rec_" + Capture.stamp();
                Recorder.start(name, 30, true, 0, result -> Minecraft.getInstance().execute(() -> {
                    LocalPlayer pl = Minecraft.getInstance().player;
                    if (pl != null) {
                        pl.displayClientMessage(Component.literal("[Lensbridge] Saved video: " + result), false);
                    }
                }));
                say("Recording (HUD hidden) - press J to stop");
            }
        }

        // drone pilot: keys -> sticks, mouse -> yaw/gimbal (the player stays put and keeps its rotation)
        if (piloting()) {
            boolean fast = mc.options.keySprint.isDown();
            float s = fast ? 1.0F : 0.45F;
            Drone.stickForward = ((mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0)) * s;
            Drone.stickRight = ((mc.options.keyRight.isDown() ? 1 : 0) - (mc.options.keyLeft.isDown() ? 1 : 0)) * s;
            Drone.stickUp = ((mc.options.keyJump.isDown() ? 1 : 0) - (mc.options.keyShift.isDown() ? 1 : 0)) * s;
            float dYaw = p.getYRot() - lockYaw;
            float dPitch = p.getXRot() - lockPitch;
            if (DirectorCamera.program instanceof Drone d && (dYaw != 0 || dPitch != 0)) {
                d.nudge(dYaw, dPitch);
            }
            p.setYRot(lockYaw);
            p.setXRot(lockPitch);
            p.yRotO = lockYaw;
            p.xRotO = lockPitch;
        } else {
            piloting = false;
        }
    }

    public static void viewTripod(Tripods.Tripod t) {
        if (t.track() != null) {
            DirectorCamera.play(new CameraProgram() {
                @Override
                public Shot eval(double time, float pt, Shot prev) {
                    return Tripods.shot(t, pt);
                }

                @Override
                public String describe() {
                    return "tripod " + t.name();
                }
            });
        } else {
            DirectorCamera.set(t.pos(), t.yaw(), t.pitch(), 0, t.fov());
        }
    }

    /** Keep the player still while flying the drone. */
    static void onMovementInput(MovementInputUpdateEvent event) {
        if (piloting()) {
            event.getInput().keyPresses = Input.EMPTY;
        }
    }

    static void onHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        List<String> lines = new ArrayList<>();
        if (DirectorCamera.program instanceof Drone d) {
            lines.add("● DRONE " + d.telemetry());
            if (piloting()) {
                lines.add("WASD fly  Space/Shift up/down  Sprint fast  mouse yaw+gimbal  K land");
            }
        } else if (DirectorCamera.active) {
            lines.add("■ CAMERA " + (DirectorCamera.program != null ? DirectorCamera.program.describe() : "fixed")
                    + (tripodIndex >= 0 ? "  (U next)" : ""));
        }
        if (Recorder.running()) {
            lines.add(String.format(Locale.ROOT, "● REC %.1fs", Recorder.seconds()));
        }
        int y = 4;
        for (String line : lines) {
            g.drawString(mc.font, line, 4, y, 0xFFFFD27A, true);
            y += 10;
        }
    }
}
