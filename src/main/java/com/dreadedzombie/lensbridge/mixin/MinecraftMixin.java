package com.dreadedzombie.lensbridge.mixin;

import com.dreadedzombie.lensbridge.DirectorCamera;
import com.dreadedzombie.lensbridge.Stealth;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stealth capture: the window keeps showing the last normal frame while capture frames are rendered.
 * <p>
 * Up to 1.21 this skipped the copy to the window and the buffer swap. Since 26.x the window is a swapchain surface
 * that must be acquired, blitted and presented every frame (renderFrame does nothing while an image is still held),
 * so instead the last normal frame is copied aside when stealth starts and presented again on every stealth frame.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow
    @Final
    public GameRenderer gameRenderer;

    /** Copy of the last frame shown before stealth began (null when not in stealth). */
    @Unique
    private static GpuTexture lensbridge$held;
    @Unique
    private static GpuTextureView lensbridge$heldView;
    /** Stealth state latched at the start of this frame (the camera is chosen before the blit). */
    @Unique
    private static boolean lensbridge$stealthFrame;

    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void lensbridge$holdFrame(boolean advanceGameTime, CallbackInfo ci) {
        lensbridge$stealthFrame = Stealth.active();
        if (lensbridge$stealthFrame && lensbridge$held == null) {
            // nothing has drawn into the main target since the last frame, so it still holds what the window shows
            RenderTarget main = this.gameRenderer.mainRenderTarget();
            GpuTexture src = main.getColorTexture();
            if (src != null) {
                GpuTexture copy = RenderSystem.getDevice().createTexture("Lensbridge held frame",
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC, src.getFormat(), main.width, main.height, 1, 1);
                RenderSystem.getDevice().createCommandEncoder()
                        .copyTextureToTexture(src, copy, 0, 0, 0, 0, 0, main.width, main.height);
                lensbridge$held = copy;
                lensbridge$heldView = RenderSystem.getDevice().createTextureView(copy);
            }
        } else if (!lensbridge$stealthFrame && lensbridge$held != null) {
            lensbridge$heldView.close();
            lensbridge$held.close();
            lensbridge$heldView = null;
            lensbridge$held = null;
        }
    }

    /** Live camera programs and props move before the frame's camera is placed (see DirectorCamera.beforeCamera). */
    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;update(Lnet/minecraft/client/DeltaTracker;)V"))
    private void lensbridge$beforeCamera(boolean advanceGameTime, CallbackInfo ci) {
        DirectorCamera.beforeCamera(((Minecraft) (Object) this).getDeltaTracker().getGameTimeDeltaPartialTick(false));
    }

    @Redirect(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;blitFromTexture(Lcom/mojang/renderpearl/api/commands/CommandEncoder;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"))
    private void lensbridge$maybeBlit(GpuSurface surface, CommandEncoder encoder, GpuTextureView frame) {
        if (lensbridge$heldView != null && (lensbridge$stealthFrame || Stealth.active())) {
            surface.blitFromTexture(encoder, lensbridge$heldView);
        } else {
            surface.blitFromTexture(encoder, frame);
        }
    }
}
