package com.dreadedzombie.lensbridge.mixin;

import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Camera props (client-only display entities) need the private setters. */
@Mixin(Display.class)
public interface DisplayAccessor {
    @Invoker("setTransformation")
    void lensbridge$setTransformation(Transformation transformation);

    @Invoker("setViewRange")
    void lensbridge$setViewRange(float viewRange);
}
