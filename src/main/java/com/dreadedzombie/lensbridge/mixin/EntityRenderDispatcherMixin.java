package com.dreadedzombie.lensbridge.mixin;

import com.dreadedzombie.lensbridge.cinema.Props;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Camera props are drawn only for the views allowed to see them (see Props); Solo hides stage neighbours. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void lensbridge$hideProps(E entity, Frustum frustum, double camX, double camY, double camZ,
                                                         CallbackInfoReturnable<Boolean> cir) {
        if (Props.hide(entity) || com.dreadedzombie.lensbridge.cinema.Solo.hides(entity) || com.dreadedzombie.lensbridge.Inset.hides(entity)) {
            cir.setReturnValue(false);
        }
    }
}
