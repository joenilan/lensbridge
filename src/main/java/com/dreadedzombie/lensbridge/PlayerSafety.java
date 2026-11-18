package com.dreadedzombie.lensbridge;

import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Camera-crew safety for the one thing Lensbridge does that can hurt the player: a teleport.
 * <p>
 * A teleport into the air is a normal camera move (the player's chunks have to be loaded, and some shots want the
 * player near the action), but a creative player who is not flying drops like a stone. Two guards cover it:
 * <ul>
 * <li>{@code command} calls {@link #hover} before a {@code tp}/{@code teleport}, so a known staging teleport starts
 * flying before gravity gets a tick;</li>
 * <li>{@link #onTick} watches the player's position for a one-tick jump, which catches teleports that never pass
 * through the command tool — a datapack function, a command block, a server plugin.</li>
 * </ul>
 * Both only start flight when the player already may fly; the server accepts the abilities packet only then
 * ({@code ServerGamePacketListenerImpl#handlePlayerAbilities}), so survival and adventure players are not given
 * flight they do not have.
 */
public final class PlayerSafety {
    private static LocalPlayer lastPlayer;
    private static Vec3 lastPos;

    private PlayerSafety() {
    }

    /**
     * True when the line moves the player: {@code tp}/{@code teleport}, including after
     * {@code execute ... run}. {@code spreadplayers} lands on the surface, so it does not need this.
     */
    public static boolean teleports(String command) {
        String cmd = command.trim();
        while (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        String[] parts = cmd.split("\\s+");
        if (parts.length == 0) {
            return false;
        }
        if (parts[0].equals("execute")) {
            for (int i = 1; i < parts.length - 1; i++) {
                if (parts[i].equals("run")) {
                    return teleports(String.join(" ", Arrays.copyOfRange(parts, i + 1, parts.length)));
                }
            }
            return false;
        }
        return parts[0].equals("tp") || parts[0].equals("teleport");
    }

    /**
     * Start creative flight if the player can fly and is not already flying. Returns a short note for the tool result
     * ({@code null} only when there is no player in the world).
     */
    public static String hover(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) {
            return null;
        }
        if (p.getAbilities().flying) {
            return "player already flying";
        }
        if (!p.mayFly()) {
            return "player cannot fly here, so a teleport may drop them";
        }
        p.getAbilities().flying = true;
        p.onUpdateAbilities();
        return "player flying, so the teleport cannot drop them";
    }

    /**
     * A teleport that never passes through {@code command} — a datapack function, a command block, a server plugin —
     * can still drop a creative player. Watch the position each client tick: a one-tick jump of more than eight blocks
     * while able to fly is a teleport, not movement (sprinting, falling and elytra are all well under that), so start
     * flying. This runs before the player's own tick, so gravity does not get a chance first.
     */
    public static void onTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            lastPlayer = null;
            lastPos = null;
            return;
        }
        if (p != lastPlayer) {
            lastPlayer = p;
            lastPos = null;
        }
        Vec3 now = p.position();
        Vec3 before = lastPos;
        lastPos = now;
        if (before == null || p.isPassenger() || p.getAbilities().flying || !p.mayFly()) {
            return;
        }
        if (now.distanceToSqr(before) > 64.0) {
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
            Lensbridge.LOGGER.info("Lensbridge: caught an external teleport ({} -> {}), started creative flight",
                    before, now);
        }
    }
}
