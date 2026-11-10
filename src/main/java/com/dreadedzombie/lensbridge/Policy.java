package com.dreadedzombie.lensbridge;

import com.dreadedzombie.lensbridge.server.ServerRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.GameType;

/**
 * Multiplayer manners. Free cameras (they can look through walls) and letting an AI act for the player (it is a bot,
 * as far as a server is concerned) are only allowed where it's fair:
 * <ul>
 * <li>singleplayer, or the host of a LAN world: always;</li>
 * <li>a server that runs Lensbridge too: whatever its owner set (config + permission nodes, see LensbridgeServer);</li>
 * <li>any other server: operators only (free cameras also in spectator mode, which can see through walls anyway).</li>
 * </ul>
 * A client mod can't truly be enforced by a server; this is Lensbridge keeping to the server's rules by default.
 */
public final class Policy {
    private Policy() {
    }

    public static boolean camerasAllowed() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.hasSingleplayerServer()) {
            return true;
        }
        Boolean server = ServerRules.cameras;
        if (server != null) {
            return server;
        }
        return isOp(p) || (mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SPECTATOR);
    }

    public static boolean controlAllowed() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.hasSingleplayerServer()) {
            return true;
        }
        Boolean server = ServerRules.control;
        return server != null ? server : isOp(p);
    }

    private static boolean isOp(LocalPlayer p) {
        return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    public static void requireCameras() {
        if (!Game.call(Policy::camerasAllowed)) {
            throw new IllegalStateException(ServerRules.cameras != null
                    ? "This server doesn't allow Lensbridge free cameras for you (its owner can change that)."
                    : "Free cameras on a server need operator status or spectator mode (they can see through walls). "
                    + "Singleplayer is unrestricted; a server owner can open them up by installing Lensbridge on the server.");
        }
    }

    public static void requireControl(String tool) {
        if (!Game.call(Policy::controlAllowed)) {
            throw new IllegalStateException(ServerRules.control != null
                    ? "This server doesn't allow an AI to act for you through Lensbridge (" + tool + ")."
                    : "On a server, letting an AI move, click, chat or run commands for you (" + tool + ") needs operator "
                    + "status: many servers treat it as a bot. Singleplayer is unrestricted; a server owner can allow it "
                    + "by installing Lensbridge on the server.");
        }
    }
}
