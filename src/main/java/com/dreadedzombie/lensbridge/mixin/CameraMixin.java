package com.dreadedzombie.lensbridge.mixin;

import com.dreadedzombie.lensbridge.DirectorCamera;
import net.minecraft.client.Camera;
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

    // 26.x split Camera.setup into update(): alignWithEntity places the camera, then FOV and the culling frustum are
    // computed from that position, so the override goes at the end of alignWithEntity (at the end of update() the
    // frustum would still be the player's and chunks/entities would be culled for the wrong view)
    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void lensbridge$director(float partialTicks, CallbackInfo ci) {
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
