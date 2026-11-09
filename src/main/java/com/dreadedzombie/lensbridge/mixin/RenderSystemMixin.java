package com.dreadedzombie.lensbridge.mixin;

import com.dreadedzombie.lensbridge.Stealth;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Stealth capture: don't swap buffers, so the window keeps showing the last normal frame. */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    @Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"), remap = false)
    private static void lensbridge$maybeSwap(long window) {
        if (!Stealth.active()) {
            GLFW.glfwSwapBuffers(window);
        } else {
            // No present this frame, so nothing implicitly flushes the driver; make sure the frame's GPU work
            // (including screenshot copies) really completes, or later readbacks can see stale data.
            org.lwjgl.opengl.GL11.glFinish();
        }
    }
}
