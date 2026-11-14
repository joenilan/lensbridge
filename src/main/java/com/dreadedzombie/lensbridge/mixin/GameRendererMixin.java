package com.dreadedzombie.lensbridge.mixin;

import com.dreadedzombie.lensbridge.Inset;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.CameraType;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Full-view watching: the screen is another camera, so the person's own first-person hand isn't drawn over it. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @WrapOperation(method = "renderItemInHand", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/CameraType;isFirstPerson()Z"))
    private boolean lensbridge$noHandInFullView(CameraType type, Operation<Boolean> original) {
        return !Inset.fullFrame && original.call(type);
    }
}
