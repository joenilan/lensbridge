package com.dreadedzombie.lensbridge.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets {@code press} click a key mapping by name, even one bound with a modifier (Alt+D...). */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
    @Accessor("clickCount")
    int lensbridge$getClickCount();

    @Accessor("clickCount")
    void lensbridge$setClickCount(int count);
}
