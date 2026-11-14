package com.dreadedzombie.lensbridge.mixin;

import com.dreadedzombie.lensbridge.Inset;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watching someone who is somewhere dark (a cave, a tunnel, at night): that camera's frames are lit as if with night
 * vision, so there is something to see. Only the watching camera's frames; the person's own view keeps its normal
 * light. (Shader packs that do their own lighting ignore this.)
 */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {
    @Shadow
    private boolean updateLightTexture;

    /** What the light texture currently holds. */
    @Unique
    private boolean lensbridge$lit;

    @Unique
    private static boolean lensbridge$want() {
        return Inset.dark && (Inset.thisFrame || Inset.fullFrame);
    }

    @Inject(method = "updateLightTexture", at = @At("HEAD"))
    private void lensbridge$relight(float partialTicks, CallbackInfo ci) {
        boolean want = lensbridge$want();
        if (want != lensbridge$lit) {
            lensbridge$lit = want;
            this.updateLightTexture = true;   // redo it now, for this frame
        }
    }

    @WrapOperation(method = "updateLightTexture", require = 0, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/player/LocalPlayer;hasEffect(Lnet/minecraft/core/Holder;)Z"))
    private boolean lensbridge$nightVision(LocalPlayer player, Holder<MobEffect> effect, Operation<Boolean> original) {
        return lensbridge$want() || original.call(player, effect);
    }

    @WrapOperation(method = "updateLightTexture", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;getNightVisionScale(Lnet/minecraft/world/entity/LivingEntity;F)F"))
    private float lensbridge$fullNightVision(LivingEntity entity, float partialTicks, Operation<Float> original) {
        return lensbridge$want() ? 1f : original.call(entity, partialTicks);
    }
}
