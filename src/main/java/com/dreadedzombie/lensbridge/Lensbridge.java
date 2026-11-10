package com.dreadedzombie.lensbridge;

import com.dreadedzombie.lensbridge.mcp.BridgeServer;
import com.dreadedzombie.lensbridge.tools.Tools;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Lensbridge: a local bridge that lets AI assistants and scripts see and drive the Minecraft client
 * (MCP over HTTP on 127.0.0.1, token-protected). Client-only.
 */
@Mod(value = Lensbridge.MODID, dist = Dist.CLIENT)
public final class Lensbridge {
    public static final String MODID = "lensbridge";
    public static final String VERSION = "0.4.1";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static BridgeConfig config;
    private static BridgeServer server;

    /** The running bridge (null until started); lets one tool reuse another. */
    public static BridgeServer server() {
        return server;
    }

    public Lensbridge(IEventBus modBus, ModContainer container) {
        config = BridgeConfig.load(FMLPaths.CONFIGDIR.get().resolve("lensbridge.json"));
        NeoForge.EVENT_BUS.addListener(Game::onFrame);
        NeoForge.EVENT_BUS.addListener(InputDriver::onTick);
        NeoForge.EVENT_BUS.addListener(ChatLog::onChat);
        NeoForge.EVENT_BUS.addListener(DirectorCamera::onAngles);
        NeoForge.EVENT_BUS.addListener(DirectorCamera::onFov);
        NeoForge.EVENT_BUS.addListener(DirectorCamera::onFramePre);
        NeoForge.EVENT_BUS.addListener(com.dreadedzombie.lensbridge.tools.CinemaTools::onTick);
        modBus.addListener(UserControls::registerKeys);
        NeoForge.EVENT_BUS.addListener(UserControls::onTick);
        NeoForge.EVENT_BUS.addListener(UserControls::onMovementInput);
        NeoForge.EVENT_BUS.addListener(UserControls::onHud);
        NeoForge.EVENT_BUS.addListener(UserControls::onScroll);
        NeoForge.EVENT_BUS.addListener(UserControls::onScreenOpening);
        if (!config.enabled) {
            LOGGER.info("Lensbridge is disabled in config/lensbridge.json");
            return;
        }
        if (config.keepRunningUnfocused) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.client.event.ClientTickEvent.Pre e) -> {
                        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                        if (mc.options.pauseOnLostFocus) {
                            mc.options.pauseOnLostFocus = false;
                        }
                        // don't throttle to AFK frame rates while being driven in the background (only when minimised)
                        if (mc.options.inactivityFpsLimit().get() != net.minecraft.client.InactivityFpsLimit.MINIMIZED) {
                            mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
                        }
                    });
        }
        server = new BridgeServer(config);
        Tools.registerAll(server);
        com.dreadedzombie.lensbridge.tools.CinemaTools.registerAll(server);
        com.dreadedzombie.lensbridge.tools.DroneTools.registerAll(server);
        com.dreadedzombie.lensbridge.tools.ManagerTools.registerAll(server);
        com.dreadedzombie.lensbridge.tools.ShowcaseTools.registerAll(server);
        try {
            server.start();
        } catch (Exception e) {
            LOGGER.error("Lensbridge could not start on {}:{}", config.host, config.port, e);
        }
    }

    public static BridgeConfig config() {
        return config;
    }
}
