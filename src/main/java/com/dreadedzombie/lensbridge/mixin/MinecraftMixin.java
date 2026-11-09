package com.dreadedzombie.lensbridge.mixin;

import com.dreadedzombie.lensbridge.Stealth;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Stealth capture: don't copy capture frames to the window. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen()V"))
    private void lensbridge$maybeBlit(RenderTarget target) {
        if (!Stealth.active()) {
            target.blitToScreen();
        }
    }
}
