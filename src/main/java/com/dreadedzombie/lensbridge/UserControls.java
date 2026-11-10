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
    // Alt + key by default: plain letters collide with map/minimap mods (M = map, U/Y = Xaero, J = JourneyMap)
    private static KeyMapping alt(String name, int key) {
        return new KeyMapping(name, net.neoforged.neoforge.client.settings.KeyConflictContext.IN_GAME,
                net.neoforged.neoforge.client.settings.KeyModifier.ALT, InputConstants.Type.KEYSYM, key, CATEGORY);
    }

    static final KeyMapping DRONE = alt("key.lensbridge.drone", GLFW.GLFW_KEY_D);
    static final KeyMapping TRIPOD = alt("key.lensbridge.tripod", GLFW.GLFW_KEY_T);
    static final KeyMapping CYCLE = alt("key.lensbridge.cycle", GLFW.GLFW_KEY_N);
    static final KeyMapping RECORD = alt("key.lensbridge.record", GLFW.GLFW_KEY_R);
    static final KeyMapping MANAGER = alt("key.lensbridge.manager", GLFW.GLFW_KEY_C);
    static final KeyMapping PROPS = alt("key.lensbridge.props", GLFW.GLFW_KEY_P);

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
        event.register(PROPS);
    }

    /** Alt+P / Camera Manager switch: show or hide the camera props in the player's own view. */
    public static boolean toggleShowCameras() {
        boolean show = !(com.dreadedzombie.lensbridge.cinema.Props.enabled && com.dreadedzombie.lensbridge.cinema.Props.playerView);
        com.dreadedzombie.lensbridge.cinema.Props.playerView = show;
        if (show) {
            com.dreadedzombie.lensbridge.cinema.Props.enabled = true;
        } else if (com.dreadedzombie.lensbridge.cinema.Props.crew.isEmpty()) {
            com.dreadedzombie.lensbridge.cinema.Props.enabled = false;   // nobody needs them any more
        }
        return show;
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
        startPilot(from, maxSpeed, 8, 0.6, 2.5);
    }

    public static void startPilot(Shot from, double maxSpeed, double accel, double wobble, double bank) {
        LocalPlayer p = Minecraft.getInstance().player;
        DirectorCamera.play(new Drone(Drone.Mode.PILOT, from, null, List.of(), false, maxSpeed, accel, 120, bank, wobble, 0.2,
                0, 0, 0, 0, 0, from.fov()));
        piloting = true;
        DirectorCamera.preview = true;
        if (p != null) {
            lockYaw = p.getYRot();
            lockPitch = p.getXRot();
        }
    }

    // ------------------------------------------------------------------ "take control" of a tripod (camera manager)

    private static volatile String editing;
    private static boolean enterWas;
    private static boolean backWas;

    public static String editing() {
        return editing;
    }

    /** Fly a tripod's camera like a precise drone; Enter saves the view into it, Backspace cancels. Client thread. */
    public static void startEdit(String name) {
        Tripods.Tripod t = Tripods.all().get(name);
        if (t == null) {
            throw new IllegalArgumentException("No tripod " + name);
        }
        if (piloting()) {
            stopPilot();
        }
        Shot s = Tripods.shot(t, 1.0F);
        float fov = t.fov() > 0 ? t.fov() : Minecraft.getInstance().options.fov().get().floatValue();
        startPilot(new Shot(s.pos(), s.yaw(), s.pitch(), 0, fov), 2.5, 14, 0, 0);
        DirectorCamera.source = name;
        editing = name;
        enterWas = true;
        backWas = true;
        say("Editing " + name + ": WASD/Space/Shift move, mouse look, scroll zoom. Enter = save, Esc/Backspace = cancel");
    }

    /** Client thread. */
    public static String finishEdit(boolean save) {
        String name = editing;
        if (name == null) {
            return "Not editing";
        }
        editing = null;
        String msg = "Cancelled - " + name + " unchanged";
        if (save) {
            Tripods.Tripod old = Tripods.all().get(name);
            float fov = DirectorCamera.program instanceof Drone d ? d.fov() : DirectorCamera.fov;
            Tripods.put(new Tripods.Tripod(name, DirectorCamera.pos, DirectorCamera.yaw, DirectorCamera.pitch, fov,
                    old != null ? old.track() : null, old == null || old.enabled()));
            msg = "Saved " + name;
            Tripods.Tripod t = Tripods.all().get(name);
            Thread th = new Thread(() -> com.dreadedzombie.lensbridge.tools.ManagerTools.thumbs(List.of(t)), "lensbridge-thumb");
            th.setDaemon(true);
            th.start();
        }
        stopPilot();
        say(msg);
        return msg;
    }

    /** Leave whatever camera mode the player is in (edit -> cancel, drone -> land, preview -> back). Client thread. */
    public static boolean exitCameraMode() {
        if (editing != null) {
            finishEdit(false);
            return true;
        }
        if (piloting()) {
            stopPilot();
            say("Drone landed - back to your view");
            return true;
        }
        if (DirectorCamera.active && DirectorCamera.preview) {
            DirectorCamera.release();
            tripodIndex = -1;
            say("Back to your view");
            return true;
        }
        return false;
    }

    /** Esc while in a camera mode exits that mode instead of opening the pause menu. */
    static void onScreenOpening(net.neoforged.neoforge.client.event.ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof net.minecraft.client.gui.screens.PauseScreen && event.getCurrentScreen() == null
                && exitCameraMode()) {
            event.setCanceled(true);
        }
    }

    static void onScroll(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent event) {
        if (editing != null && DirectorCamera.program instanceof Drone d) {
            d.setFov((float) Math.max(10, Math.min(140, d.fov() - event.getScrollDeltaY() * 2)));
            event.setCanceled(true);
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
                try {
                    startPilot(from, 6);
                    say("Drone up: WASD fly, Space/Shift up/down, Sprint = fast, mouse = yaw + gimbal, Alt+D to land");
                } catch (IllegalStateException e) {   // not allowed on this server (Policy)
                    say(e.getMessage());
                }
            }
        }
        while (TRIPOD.consumeClick()) {
            Shot v = DirectorCamera.active
                    ? new Shot(DirectorCamera.pos, DirectorCamera.yaw, DirectorCamera.pitch, 0, DirectorCamera.fov) : playerView(p);
            String name = Tripods.nextName();
            Tripods.put(new Tripods.Tripod(name, v.pos(), v.yaw(), v.pitch(), v.fov(), null));
            say("Placed " + name + " here (" + Tripods.all().size() + " tripods). Alt+N cycles through them.");
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
                say(list.isEmpty() ? "No tripods yet - press Alt+T to place one" : "Back to your view");
            } else {
                Tripods.Tripod t = list.get(tripodIndex);
                try {
                    viewTripod(t);
                    DirectorCamera.preview = true;
                    say("Tripod " + (tripodIndex + 1) + "/" + list.size() + ": " + t.name());
                } catch (IllegalStateException e) {   // not allowed on this server (Policy)
                    tripodIndex = -1;
                    say(e.getMessage());
                }
            }
        }
        while (PROPS.consumeClick()) {
            say(toggleShowCameras() ? "Showing your cameras in the world (Alt+P to hide)" : "Cameras hidden");
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
                say("Recording (HUD hidden) - press Alt+R to stop");
            }
        }

        // editing a tripod: Enter saves, Backspace cancels (edge-triggered); leaving pilot mode (K) cancels
        if (editing != null) {
            boolean enter = InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_ENTER) || InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_KP_ENTER);
            boolean back = InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_BACKSPACE);
            if (mc.screen == null && enter && !enterWas) {
                finishEdit(true);
            } else if (mc.screen == null && back && !backWas) {
                finishEdit(false);
            } else if (!piloting()) {
                editing = null;
            }
            enterWas = enter;
            backWas = back;
        }

        // Backspace also leaves a previewed camera / the drone (Esc is handled in onScreenOpening)
        if (editing == null && DirectorCamera.active && DirectorCamera.preview && mc.screen == null) {
            boolean back = InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_BACKSPACE);
            if (back && !backWas) {
                exitCameraMode();
            }
            backWas = back;
        } else if (editing == null) {
            backWas = InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_BACKSPACE);
        }

        // drone pilot: keys -> sticks, mouse -> yaw/gimbal (the player stays put and keeps its rotation)
        if (piloting() && System.currentTimeMillis() >= Drone.aiSticksUntil) {
            boolean fast = mc.options.keySprint.isDown();
            float s = fast ? 1.0F : 0.45F;
            Drone.stickForward = ((mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0)) * s;
            Drone.stickRight = ((mc.options.keyRight.isDown() ? 1 : 0) - (mc.options.keyLeft.isDown() ? 1 : 0)) * s;
            Drone.stickUp = ((mc.options.keyJump.isDown() ? 1 : 0) - (mc.options.keyShift.isDown() ? 1 : 0)) * s;
        }
        if (piloting()) {
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
        DirectorCamera.source = t.name();
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
        if (editing != null && DirectorCamera.program instanceof Drone d) {
            lines.add("✋ EDITING " + editing + String.format(Locale.ROOT, "   FOV %.0f", d.fov()));
            lines.add("WASD / Space / Shift move   Sprint fast   mouse look   scroll zoom");
            lines.add("Enter = save    Esc / Backspace = cancel");
        } else if (DirectorCamera.program instanceof Drone d) {
            lines.add("● DRONE " + d.telemetry());
            if (piloting()) {
                lines.add("WASD fly  Space/Shift up/down  Sprint fast  mouse yaw+gimbal    Alt+D / Esc = land");
            }
        } else if (DirectorCamera.active && DirectorCamera.preview) {
            lines.add("■ CAMERA " + (DirectorCamera.program != null ? DirectorCamera.program.describe() : "fixed")
                    + (tripodIndex >= 0 ? "   Alt+N = next camera" : ""));
            lines.add("Esc / Backspace = back to your view");
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
