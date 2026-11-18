package com.dreadedzombie.lensbridge.mixin;

import com.dreadedzombie.lensbridge.TestSkin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** test_skin: the local player is drawn with Lensbridge's opaque-outer-layer test skin. Other players are untouched. */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true, require = 0)
    private void lensbridge$testSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerSkin test = TestSkin.skin();
        if (test != null && (Object) this == Minecraft.getInstance().player) {
            cir.setReturnValue(test);
        }
    }
}
