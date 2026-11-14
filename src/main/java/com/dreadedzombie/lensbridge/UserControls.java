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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Input;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Hands-on camera work for players: fly the drone (K), drop a tripod at your view (Y), cycle through
 * tripods (U), start/stop recording (J). Small HUD shows what's active (hidden when the HUD is hidden).
 */
public final class UserControls {
    static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Lensbridge.MODID, "camera"));
    // Alt + key by default: plain letters collide with map/minimap mods (M = map, U/Y = Xaero, J = JourneyMap)
    private static KeyMapping alt(String name, int key) {
        return new KeyMapping(name, net.neoforged.neoforge.client.settings.KeyConflictContext.IN_GAME,
                net.neoforged.neoforge.client.settings.KeyModifier.ALT, InputConstants.Type.KEYBOARD, key, CATEGORY);
    }

    static final KeyMapping DRONE = alt("key.lensbridge.drone", InputConstants.KEY_D);
    static final KeyMapping TRIPOD = alt("key.lensbridge.tripod", InputConstants.KEY_T);
    static final KeyMapping CYCLE = alt("key.lensbridge.cycle", InputConstants.KEY_N);
    static final KeyMapping RECORD = alt("key.lensbridge.record", InputConstants.KEY_R);
    static final KeyMapping MANAGER = alt("key.lensbridge.manager", InputConstants.KEY_C);
    static final KeyMapping PROPS = alt("key.lensbridge.props", InputConstants.KEY_P);
    static final KeyMapping REMOVE = alt("key.lensbridge.remove", InputConstants.KEY_X);
    static final KeyMapping PANIC = alt("key.lensbridge.panic", InputConstants.KEY_BACKSPACE);

    private static boolean piloting;
    private static float lockYaw;
    private static float lockPitch;

    /**
     * The tripod the player is looking through on screen right now, found by NAME (DirectorCamera.source). There used to
     * be an index into the camera list here; it went stale whenever the list or the view changed some other way (Alt+D
     * from a tripod, a browser delete, tripod_preview), and Alt+X then removed the wrong camera.
     */
    private static Tripods.Tripod viewedTripod() {
        if (!DirectorCamera.onScreen() || (DirectorCamera.program instanceof Drone && editing == null)) {
            return null;   // not looking through a camera, or flying the drone (whose source is "drone")
        }
        return Tripods.get(DirectorCamera.source);
    }

    /** What is on the player's screen: the shown camera, else their own eyes. */
    private static Shot screenView(LocalPlayer p) {
        return DirectorCamera.onScreen()
                ? new Shot(DirectorCamera.pos, DirectorCamera.yaw, DirectorCamera.pitch, 0, DirectorCamera.fov) : playerView(p);
    }

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
        event.register(REMOVE);
        event.register(PANIC);
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

    // ---------------------------------------------------------------- removing cameras in game

    /** How far away a camera can be picked by looking at it, how close the view must point, and the "nearest" reach. */
    private static final double REMOVE_LOOK_RANGE = 48.0D;
    /** With the camera props hidden (Alt+P off) the player can't see what they point at: only pick close ones. */
    private static final double REMOVE_LOOK_RANGE_UNSEEN = 16.0D;
    private static final double REMOVE_LOOK_DEGREES = 10.0D;
    private static final double REMOVE_NEAR = 5.0D;
    private static final double REMOVE_HERE = 1.0D;
    /** The cameras taken away by the last removal, so one slip can be undone. */
    private static List<Tripods.Tripod> lastRemoved = List.of();

    /**
     * Remove one camera without opening the browser. In this order: the one on screen (Alt+N), the one placed where
     * the view is (within a block: a camera just dropped with Alt+T), the one being looked at with nothing in the
     * way, else the nearest one within a few blocks. Only cameras of this world and dimension. Returns the line to
     * show the player.
     */
    public static String removeCamera(LocalPlayer p) {
        List<Tripods.Tripod> list = Tripods.here();
        if (list.isEmpty()) {
            return "No cameras to remove here";
        }
        Tripods.Tripod pick = viewedTripod();
        String how = " (the one you were looking through)";
        if (pick == null) {
            // measured from what is on screen, not from the player's body (they may be watching a drone or a preview)
            Shot view = screenView(p);
            net.minecraft.world.phys.Vec3 eye = view.pos();
            net.minecraft.world.phys.Vec3 look = net.minecraft.world.phys.Vec3.directionFromRotation(view.pitch(), view.yaw());
            // 1. right here first: a camera placed where you stand has no direction to "look at", and used to be
            //    skipped in favour of a distant one that happened to be in the cone
            double here = REMOVE_HERE * REMOVE_HERE;
            for (Tripods.Tripod t : list) {
                double d = t.pos().distanceToSqr(eye);
                if (d <= here) {
                    here = d;
                    pick = t;
                    how = " (the one right where you are)";
                }
            }
            if (pick == null) {
                boolean propsShown = com.dreadedzombie.lensbridge.cinema.Props.enabled && com.dreadedzombie.lensbridge.cinema.Props.playerView;
                double range = propsShown ? REMOVE_LOOK_RANGE : REMOVE_LOOK_RANGE_UNSEEN;
                double best = Math.cos(Math.toRadians(REMOVE_LOOK_DEGREES));
                for (Tripods.Tripod t : list) {
                    net.minecraft.world.phys.Vec3 to = t.pos().subtract(eye);
                    double d = to.length();
                    if (d <= range && to.scale(1.0D / d).dot(look) > best && clearLine(p, eye, t.pos())) {
                        best = to.scale(1.0D / d).dot(look);
                        pick = t;
                        how = " (the one you were looking at)";
                    }
                }
            }
            if (pick == null) {
                double nearest = REMOVE_NEAR * REMOVE_NEAR;
                for (Tripods.Tripod t : list) {
                    double d = t.pos().distanceToSqr(eye);
                    if (d <= nearest) {
                        nearest = d;
                        pick = t;
                        how = " (the nearest one)";
                    }
                }
            }
        }
        if (pick == null) {
            return "Look at a camera, stand next to one, or look through one (Alt+N), then press this again. "
                    + "Alt+P shows where they are.";
        }
        removeCameras(List.of(pick));
        return "Removed " + pick.name() + how + ". " + Tripods.here().size() + " left. /lensbridge cameras undo brings it back.";
    }

    /** Nothing solid between the view and the camera (a camera behind a wall isn't "looked at"). */
    private static boolean clearLine(LocalPlayer p, net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to) {
        return p.level().clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.VISUAL,
                net.minecraft.world.level.ClipContext.Fluid.NONE, p)).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    /** Remove these cameras, remember them for undo, and get the player out of any view that no longer exists. */
    public static void removeCameras(List<Tripods.Tripod> gone) {
        if (gone.isEmpty()) {
            return;
        }
        Tripods.Tripod viewed = viewedTripod();
        String edited = editing;
        for (Tripods.Tripod t : gone) {
            Tripods.remove(t.name());   // also deletes its thumbnail
            com.google.gson.JsonObject removed = new com.google.gson.JsonObject();
            removed.addProperty("name", t.name());
            removed.addProperty("source", "player");
            com.dreadedzombie.lensbridge.mcp.AgentlinkSupport.publish("lensbridge.tripod_removed", removed);
            if (t.name().equals(edited)) {
                // being edited right now: cancel the edit, or Enter would save the camera straight back
                editing = null;
                stopPilot();
            } else if (viewed != null && t.name().equals(viewed.name())) {
                DirectorCamera.release();   // looking through it: back to the player's own view
            }
        }
        lastRemoved = List.copyOf(gone);
    }

    /** Put back what the last removal took. Returns how many came back. (Their thumbnails are retaken on demand.) */
    public static int undoRemove() {
        int n = 0;
        for (Tripods.Tripod t : lastRemoved) {
            if (Tripods.get(t.name()) == null) {
                Tripods.put(t);
                n++;
            }
        }
        lastRemoved = List.of();
        return n;
    }

    // ---------------------------------------------------------------- getting unstuck

    /**
     * Alt+Backspace: stop whatever capture is running and give the player their game back. For a stuck capture (HUD
     * gone, screen frozen, camera somewhere else); it does not undo scene changes, /lensbridge restore does that.
     */
    static void panic() {
        Minecraft mc = Minecraft.getInstance();
        Capture.abortRunning();   // the capture thread stops at its next frame and runs its own clean-up
        Recorder.stop();
        Stealth.reset();          // the screen shows frames again right away
        Game.setHudHidden(false);
        editing = null;
        stopPilot();              // also releases the camera
        InputDriver.releaseAll();
        say("Stopped: captures cancelled, HUD back, camera and held keys released. /lensbridge restore undoes scene changes.");
    }

    /**
     * Leaving the world (or, with {@code quit} false, respawning / changing dimension): a camera view, pilot mode, an
     * edit and held keys belong to the place just left. On quitting also the recorder and the inset, which would
     * otherwise carry on into the next world or the title screen.
     */
    static void leaveWorld(boolean quit) {
        editing = null;
        if (piloting || DirectorCamera.active) {
            stopPilot();
        }
        InputDriver.releaseAll();
        if (quit) {
            Recorder.stop();
            Inset.stop();
        }
    }

    public static boolean piloting() {
        return piloting && DirectorCamera.program instanceof Drone d && d.mode() == Drone.Mode.PILOT;
    }

    private static void say(String msg) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            p.sendOverlayMessage(Component.literal("[Lensbridge] " + msg));
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
        Tripods.Tripod t = Tripods.get(name);
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
        Tripods.Tripod old = Tripods.get(name);
        if (save && old == null) {
            // removed while it was being edited (browser, command): saving would bring a deleted camera back
            msg = name + " was removed while you were editing it - nothing saved";
        } else if (save) {
            float fov = DirectorCamera.program instanceof Drone d ? d.fov() : DirectorCamera.fov;
            Tripods.Tripod t = new Tripods.Tripod(name, DirectorCamera.pos, DirectorCamera.yaw, DirectorCamera.pitch, fov,
                    old.track(), old.enabled(), old.world(), old.dim());
            Tripods.put(t);
            msg = "Saved " + name;
            Thread th = new Thread(() -> {
                try {
                    com.dreadedzombie.lensbridge.tools.ManagerTools.thumbs(List.of(t));
                } catch (RuntimeException e) {   // e.g. another capture is running: the thumbnail just stays old
                    Lensbridge.LOGGER.warn("Lensbridge: thumbnail of {} not refreshed: {}", name, e.getMessage());
                }
            }, "lensbridge-thumb");
            th.setDaemon(true);
            th.start();
        }
        stopPilot();
        say(msg);
        return msg;
    }

    /**
     * Leave whatever camera mode the player is in (edit -> cancel, drone -> land, preview -> back). Client thread.
     * {@code hiddenToo}: also release a camera the player isn't looking through. The exit_camera tool and the browser's
     * "Back to my view" pass true, so an AI that ends its work with exit_camera doesn't leave a hidden camera active;
     * Esc and Backspace pass false, so they don't swallow a key press (or kill an AI's shot) when nothing is on screen.
     */
    public static boolean exitCameraMode(boolean hiddenToo) {
        if (editing != null) {
            finishEdit(false);
            return true;
        }
        if (piloting()) {
            stopPilot();
            say("Drone landed - back to your view");
            return true;
        }
        if (Inset.full) {
            Inset.setFull(false);
            say("Back to your view (the small picture stays; /lensbridge inset off closes it)");
            return true;
        }
        if (DirectorCamera.onScreen()) {
            DirectorCamera.release();
            say("Back to your view");
            return true;
        }
        if (hiddenToo && DirectorCamera.active) {
            DirectorCamera.release();   // nothing changes on screen, so nothing to tell the player
            return true;
        }
        return false;
    }

    /** Esc while in a camera mode exits that mode instead of opening the pause menu. */
    static void onScreenOpening(net.neoforged.neoforge.client.event.ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof net.minecraft.client.gui.screens.PauseScreen && event.getCurrentScreen() == null
                && exitCameraMode(false)) {
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
        while (PANIC.consumeClick()) {
            panic();
        }
        while (DRONE.consumeClick()) {
            if (piloting()) {
                stopPilot();
                say("Drone landed - back to your view");
            } else {
                Shot from = screenView(p);   // a hidden AI camera is not the player's view
                try {
                    startPilot(from, 6);
                    say("Drone up: WASD fly, Space/Shift up/down, Sprint = fast, mouse = yaw + gimbal, Alt+D to land");
                } catch (IllegalStateException e) {   // not allowed on this server (Policy)
                    say(e.getMessage());
                }
            }
        }
        while (TRIPOD.consumeClick()) {
            Shot v = screenView(p);   // a hidden AI camera is not the player's view
            String name = Tripods.nextName();
            Tripods.put(new Tripods.Tripod(name, v.pos(), v.yaw(), v.pitch(), v.fov(), null));
            com.google.gson.JsonObject placed = new com.google.gson.JsonObject();
            placed.addProperty("name", name);
            placed.addProperty("x", v.pos().x);
            placed.addProperty("y", v.pos().y);
            placed.addProperty("z", v.pos().z);
            placed.addProperty("yaw", v.yaw());
            placed.addProperty("pitch", v.pitch());
            placed.addProperty("source", "hotkey");
            com.dreadedzombie.lensbridge.mcp.AgentlinkSupport.publish("lensbridge.tripod_placed", placed);
            say("Placed " + name + " here (" + Tripods.here().size() + " tripods). Alt+N cycles through them.");
        }
        while (CYCLE.consumeClick()) {
            List<Tripods.Tripod> list = Tripods.here();   // this world and dimension only
            // the position in the cycle is wherever the camera on screen is in the list right now (by name)
            Tripods.Tripod viewed = viewedTripod();
            int tripodIndex = -1;
            for (int i = 0; viewed != null && i < list.size(); i++) {
                if (list.get(i).name().equals(viewed.name())) {
                    tripodIndex = i;
                }
            }
            if (piloting()) {
                stopPilot();
            }
            tripodIndex++;
            if (list.isEmpty() || tripodIndex >= list.size()) {
                DirectorCamera.release();
                say(list.isEmpty() ? "No tripods yet - press Alt+T to place one" : "Back to your view");
            } else {
                Tripods.Tripod t = list.get(tripodIndex);
                try {
                    viewTripod(t);
                    DirectorCamera.preview = true;
                    say("Tripod " + (tripodIndex + 1) + "/" + list.size() + ": " + t.name());
                } catch (IllegalStateException e) {   // not allowed on this server (Policy)
                    say(e.getMessage());
                }
            }
        }
        while (REMOVE.consumeClick()) {
            say(removeCamera(p));
        }
        while (PROPS.consumeClick()) {
            say(toggleShowCameras() ? "Showing your cameras in the world (Alt+P to hide)" : "Cameras hidden");
        }
        while (MANAGER.consumeClick()) {
            String why = Lensbridge.unavailableReason();
            if (why != null) {
                // no working connection: say why in chat instead of opening a dead page (or another program's)
                Minecraft.getInstance().gui.chatListener().handleSystemMessage(Component.literal("[Lensbridge] " + why), false);
            } else {
                com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(Lensbridge.managerUrl()));
                say("Opened the camera manager in your browser");
            }
        }
        while (RECORD.consumeClick()) {
            if (Recorder.running()) {
                Recorder.stop();
                say("Recording stopped - encoding...");
            } else if (Recorder.encoding()) {
                say("Still saving the last recording - try again in a moment");
            } else {
                if (DirectorCamera.active && !DirectorCamera.preview) {
                    // a hidden camera (left by an AI) would be what gets recorded, with the screen frozen meanwhile:
                    // the hotkey records what the player sees
                    DirectorCamera.release();
                }
                String name = "rec_" + Capture.stamp();
                // HUD left as it is: the "REC" line is part of the HUD, and hidden with it the player had no sign that
                // a recording was running. F1 before Alt+R gives a clean video.
                boolean started = Recorder.start(name, 30, Game.hudHidden(), 0, (ok, result) -> Minecraft.getInstance().execute(() -> {
                    LocalPlayer pl = Minecraft.getInstance().player;
                    if (pl != null) {
                        // a client-side chat line (remote = false: not logged as a server message)
                        Minecraft.getInstance().gui.chatListener().handleSystemMessage(ok
                                ? Component.literal("[Lensbridge] Saved video: " + result)
                                : Component.literal("[Lensbridge] Recording failed: " + result)
                                        .withStyle(net.minecraft.ChatFormatting.RED), false);
                    }
                }));
                say(started ? "Recording - press Alt+R to stop (F1 first for a video without the HUD)"
                        : "Still saving the last recording - try again in a moment");
            }
        }

        // editing a tripod: Enter saves, Backspace cancels (edge-triggered); leaving pilot mode (K) cancels
        if (editing != null) {
            boolean enter = InputConstants.isKeyDown(InputConstants.KEY_RETURN) || InputConstants.isKeyDown(InputConstants.KEY_NUMPADENTER);
            boolean back = InputConstants.isKeyDown(InputConstants.KEY_BACKSPACE);
            if (mc.gui.screen() == null && enter && !enterWas) {
                finishEdit(true);
            } else if (mc.gui.screen() == null && back && !backWas) {
                finishEdit(false);
            } else if (!piloting()) {
                editing = null;
            }
            enterWas = enter;
            backWas = back;
        }

        // Backspace also leaves a previewed camera / the drone (Esc is handled in onScreenOpening)
        if (editing == null && DirectorCamera.active && DirectorCamera.preview && mc.gui.screen() == null) {
            boolean back = InputConstants.isKeyDown(InputConstants.KEY_BACKSPACE);
            if (back && !backWas) {
                exitCameraMode(false);
            }
            backWas = back;
        } else if (editing == null) {
            backWas = InputConstants.isKeyDown(InputConstants.KEY_BACKSPACE);
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
        if (Game.hudHidden() || mc.player == null) {
            return;
        }
        GuiGraphicsExtractor g = event.getGuiGraphics();
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
                    + (viewedTripod() != null ? "   Alt+N = next camera" : ""));
            lines.add("Esc / Backspace = back to your view");
        }
        if (Recorder.running()) {
            lines.add(String.format(Locale.ROOT, "● REC %.1fs", Recorder.seconds()));
        }
        int y = 4;
        for (String line : lines) {
            g.text(mc.font, line, 4, y, 0xFFFFD27A, true);
            y += 10;
        }
    }
}
