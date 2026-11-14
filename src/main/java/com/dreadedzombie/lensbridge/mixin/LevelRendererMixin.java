package com.dreadedzombie.lensbridge.mixin;

import com.dreadedzombie.lensbridge.Inset;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inset frames must not move the world's chunk bookkeeping (the section grid and the visibility graph) to the inset
 * camera: the next normal frame would draw with the other camera's visible chunks (flashing holes) and both would
 * rebuild it every time (FPS drop). An inset frame only re-filters the chunks already in view by its own frustum;
 * the next normal frame re-filters for the player again. While an inset is open, {@link Inset} also turns smart
 * culling off, so chunks hidden from the player (but not from the inset) are still in that list.
 *
 * <p>Vanilla renderer only: Sodium works out what is visible from scratch for every camera, in the frame itself, so
 * with Sodium the inset frame simply takes the normal path.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow
    private double prevCamRotX;

    @Shadow
    private void applyFrustum(Frustum frustum) {
    }

    @Inject(method = "cullTerrain", at = @At("HEAD"), cancellable = true)
    private void lensbridge$insetKeepsTerrain(Camera camera, Frustum frustum, boolean spectator, CallbackInfo ci) {
        if (Inset.thisFrame && !com.dreadedzombie.lensbridge.FrameGuard.sodium()) {
            applyFrustum(LevelRenderer.offsetFrustum(frustum));
            prevCamRotX = Double.MIN_VALUE; // the next normal frame applies its own frustum again
            ci.cancel();
        }
    }
}
