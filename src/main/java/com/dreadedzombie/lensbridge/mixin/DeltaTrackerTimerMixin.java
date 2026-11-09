package com.dreadedzombie.lensbridge.mixin;

import com.dreadedzombie.lensbridge.tools.Rendering;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Smooth step renders: a frozen world normally renders every entity at partial tick 1.0, so frames between two
 * world steps are identical and motion judders at 20 Hz. While a step render runs, it sets the partial tick for each
 * frame and every caller (frozen or not) gets it, so animation is interpolated at any frame rate.
 */
@Mixin(DeltaTracker.Timer.class)
public abstract class DeltaTrackerTimerMixin {
    @Inject(method = "getGameTimeDeltaPartialTick", at = @At("HEAD"), cancellable = true)
    private void lensbridge$forcedPartial(boolean runsNormally, CallbackInfoReturnable<Float> cir) {
        float p = Rendering.forcedPartial;
        if (p >= 0) {
            cir.setReturnValue(p);
        }
    }
}
