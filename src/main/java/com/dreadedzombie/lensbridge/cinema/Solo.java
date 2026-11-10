package com.dreadedzombie.lensbridge.cinema;

import java.util.Set;
import net.minecraft.world.entity.Entity;

/**
 * Entities kept out of the picture for a moment: Showcase Stages shoot each mob on its own, so a big neighbour
 * (a Great Lumen, a dragon) can't wander into the frame or into the auto-frame measurement.
 */
public final class Solo {
    private static volatile Set<Integer> hidden = Set.of();

    private Solo() {
    }

    public static void hide(Set<Integer> ids) {
        hidden = Set.copyOf(ids);
    }

    public static void clear() {
        hidden = Set.of();
    }

    /** Render hook (EntityRenderDispatcherMixin). */
    public static boolean hides(Entity e) {
        Set<Integer> h = hidden;
        return !h.isEmpty() && h.contains(e.getId());
    }
}
