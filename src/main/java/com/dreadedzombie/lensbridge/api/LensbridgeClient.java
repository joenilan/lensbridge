package com.dreadedzombie.lensbridge.api;

import com.dreadedzombie.lensbridge.Capture;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Lensbridge's public client API, for other mods. Stable: these methods keep their names and meaning across
 * Lensbridge versions (since 0.6.2). Client side only.
 *
 * <p>Call it without depending on Lensbridge: check {@code ModList.get().isLoaded("lensbridge")}, then look the
 * method up by reflection once and keep it, e.g.
 * <pre>{@code
 * Method hidden = Class.forName("com.dreadedzombie.lensbridge.api.LensbridgeClient")
 *         .getMethod("isPlayerHidden", Player.class);
 * boolean skip = (boolean) hidden.invoke(null, player);
 * }</pre>
 * If the class or method is missing (an older Lensbridge), treat it as "not hidden".
 */
public final class LensbridgeClient {
    private LensbridgeClient() {
    }

    /**
     * True while a Lensbridge capture asked to leave this player out of the picture ({@code hide_player}), so a mod
     * that draws something attached to the player (a creature on their shoulder, a backpack, a pet glued to their
     * position) should not draw it either. Only ever true for the local player. Cheap: call it every frame, from a
     * renderer's {@code shouldRender}. Render thread.
     */
    public static boolean isPlayerHidden(Player player) {
        return player != null && Capture.hidingPlayer() && player == Minecraft.getInstance().player;
    }

    /**
     * True while Lensbridge is leaving this entity out of the picture: the hidden local player, or anything riding
     * them (passengers of passengers too). Render thread.
     */
    public static boolean isEntityHidden(Entity entity) {
        if (entity == null || !Capture.hidingPlayer()) {
            return false;
        }
        Player me = Minecraft.getInstance().player;
        if (me == null) {
            return false;
        }
        for (Entity e = entity; e != null; e = e.getVehicle()) {
            if (e == me) {
                return true;
            }
        }
        return false;
    }
}
