package com.dreadedzombie.lensbridge;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * A test skin for the local player (tool {@code test_skin}): a dark chequer on the base layer and a bright,
 * fully opaque chequer on every outer layer (hat, jacket, sleeves, trouser layers). Normal skins leave the outer
 * layers mostly see-through, which hides a worn model that clips into them; with this skin any clipping or flicker
 * shows at once. Only drawn on this computer; nothing is sent to the server.
 */
public final class TestSkin {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("lensbridge", "test_skin");
    private static volatile boolean on;
    private static PlayerSkin skin;

    private TestSkin() {
    }

    public static boolean on() {
        return on;
    }

    /** Render thread. */
    public static void set(boolean wear) {
        if (wear && skin == null) {
            NativeImage img = new NativeImage(64, 64, true);
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    boolean check = ((x >> 1) + (y >> 1)) % 2 == 0;
                    int color = outer(x, y)
                            ? (check ? 0xFFFF00FF : 0xFF00FFFF)    // magenta / cyan: the outer layers, all opaque
                            : (check ? 0xFF303030 : 0xFF606060);   // dark greys: the base layer
                    img.setPixel(x, y, color);
                }
            }
            Minecraft.getInstance().getTextureManager().register(ID, new DynamicTexture(() -> "lensbridge test skin", img));
            skin = new PlayerSkin(new ClientAsset.ResourceTexture(ID, ID), null, null, PlayerModelType.WIDE, true);
        }
        on = wear;
    }

    /** The skin to draw for the local player while the test skin is on, else null. */
    public static PlayerSkin skin() {
        return on ? skin : null;
    }

    /** The outer-layer areas of a 64x64 skin: hat, jacket, both sleeve and both trouser layers. */
    private static boolean outer(int x, int y) {
        return y < 16 && x >= 32                       // hat
                || y >= 32 && y < 48 && x < 56             // right trouser layer, jacket, right sleeve layer
                || y >= 48 && (x < 16 || x >= 48);         // left trouser layer, left sleeve layer
    }
}
