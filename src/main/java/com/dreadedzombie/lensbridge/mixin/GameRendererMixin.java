package com.dreadedzombie.lensbridge.mixin;

import com.dreadedzombie.lensbridge.Inset;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.CameraType;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.x: renderItemInHand still asks CameraType.isFirstPerson() (through the options render state), so the same hook applies.
 * No first-person hand over a frame that isn't the person's own view: full-view watching (another camera), and captures
 * asked to leave the player out ({@code hide_player}).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @WrapOperation(method = "renderItemInHand", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/CameraType;isFirstPerson()Z"))
    private boolean lensbridge$noHandInFullView(CameraType type, Operation<Boolean> original) {
        return !Inset.fullFrame && !com.dreadedzombie.lensbridge.Capture.hidingPlayer() && original.call(type);
    }
}
