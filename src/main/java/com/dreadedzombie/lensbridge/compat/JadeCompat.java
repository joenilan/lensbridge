package com.dreadedzombie.lensbridge.compat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade integration (only loaded when Jade is installed). Camera props are client-only displays of a stick wearing a
 * Lensbridge model and named after the camera ("Lensbridge camera: sheet_wide"), so Jade shows that name; this makes
 * the mod line say Lensbridge instead of Minecraft.
 */
@WailaPlugin
public class JadeCompat implements IWailaPlugin {
    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.addItemModNameCallback(stack -> {
            Identifier model = stack.get(DataComponents.ITEM_MODEL);
            return model != null && model.getNamespace().equals("lensbridge") ? "Lensbridge" : null;
        });
    }
}
