package com.dreadedzombie.lensbridge.mixin;

import com.dreadedzombie.lensbridge.FrameGuard;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dynamic FPS (optional): while Lensbridge is capturing, recording or showing an inset, report the player's normal
 * frame rate limit instead of the reduced one, so frames are really drawn even with the window in the background.
 * Does nothing when Dynamic FPS isn't installed, or if a future version changes this method.
 */
@Pseudo
@Mixin(targets = "dynamic_fps.impl.DynamicFPSMod", remap = false)
public abstract class DynamicFpsMixin {
    @Inject(method = "targetFrameRate()I", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void lensbridge$keepFrames(CallbackInfoReturnable<Integer> cir) {
        if (FrameGuard.needed()) {
            cir.setReturnValue(Minecraft.getInstance().options.framerateLimit().get());
        }
    }
}
