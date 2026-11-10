package com.dreadedzombie.lensbridge.mixin;

import com.dreadedzombie.lensbridge.DirectorCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Director camera: override position/rotation after vanilla setup, and draw the player as if in 3rd person. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot, float roll);

    @Inject(method = "setup", at = @At("TAIL"))
    private void lensbridge$director(Level level, Entity entity, boolean detached, boolean mirror, float partialTick,
                                     CallbackInfo ci) {
        if (DirectorCamera.shown()) {
            this.setRotation(DirectorCamera.yaw, DirectorCamera.pitch, DirectorCamera.roll);
            this.setPosition(DirectorCamera.pos);
            DirectorCamera.appliedThisFrame = true;
        }
    }

    @Inject(method = "isDetached", at = @At("HEAD"), cancellable = true)
    private void lensbridge$detached(CallbackInfoReturnable<Boolean> cir) {
        if (DirectorCamera.shown()) {
            cir.setReturnValue(true);
        }
    }
}
