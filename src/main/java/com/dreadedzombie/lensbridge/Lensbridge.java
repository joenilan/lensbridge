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
 * (MCP over HTTP on 127.0.0.1, token-protected). Client side; the optional server side is LensbridgeServer.
 */
@Mod(value = Lensbridge.MODID, dist = Dist.CLIENT)
public final class Lensbridge {
    public static final String MODID = "lensbridge";
    /** The installed version, from the mod's own metadata (so it can't go stale again). */
    public static final String VERSION = net.neoforged.fml.ModList.get().getModContainerById(MODID)
            .map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
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
        // a new server sends its own rules (or none): forget the last one's
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) ->
        {
            com.dreadedzombie.lensbridge.server.ServerRules.clear();
            com.dreadedzombie.lensbridge.server.ServerAgent.clear();
            // and nothing of this world carries into the next one or the title screen
            UserControls.leaveWorld(true);
        });
        // respawn and dimension change (both make a new player): a camera, pilot mode or held keys belong to the old place
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.Clone e) ->
                UserControls.leaveWorld(false));
        NeoForge.EVENT_BUS.addListener(InputDriver::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(ConnectCommand::register);
        NeoForge.EVENT_BUS.addListener(AgentInbox::register);
        NeoForge.EVENT_BUS.addListener(Inset::onFramePre);
        NeoForge.EVENT_BUS.addListener(Inset::onFramePost);
        NeoForge.EVENT_BUS.addListener(Inset::onHud);
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
        com.dreadedzombie.lensbridge.tools.ServerAgentTools.registerAll(server);
        com.dreadedzombie.lensbridge.tools.ServerAgentTools.registerInbox(server);
        com.dreadedzombie.lensbridge.tools.ServerAgentTools.registerInset(server);
        if (com.dreadedzombie.lensbridge.mcp.AgentlinkSupport.available()) {
            // Agentlink (the shared bridge mod) is installed: it serves Lensbridge's tools; no second server
            BridgeServer tools = server;
            modBus.addListener((net.neoforged.fml.event.lifecycle.InterModEnqueueEvent e) ->
                    com.dreadedzombie.lensbridge.mcp.AgentlinkSupport.enqueue(tools));
            LOGGER.info("Lensbridge: Agentlink is installed; handing {} tools to it instead of starting the built-in server",
                    server.tools().size());
            return;
        }
        try {
            server.start();
        } catch (Exception e) {
            LOGGER.error("Lensbridge could not start on {}:{}", config.host, config.port, e);
            // remembered, so Alt+C and /lensbridge connect say this instead of pointing at whatever owns the port
            startError = "Lensbridge couldn't start on " + config.host + ":" + config.port + " ("
                    + (e instanceof java.net.BindException ? "the port is already in use by another program or another Minecraft"
                    : e.getMessage()) + "). Change \"port\" in config/lensbridge.json or close the other program, then restart the game.";
        }
    }

    private static volatile String startError;

    /** Why there's nothing for an AI app or the browser to connect to right now, or null when the connection is up. */
    public static String unavailableReason() {
        if (!config.enabled) {
            return "Lensbridge's connection is turned off (\"enabled\": false in config/lensbridge.json).";
        }
        if (com.dreadedzombie.lensbridge.mcp.AgentlinkSupport.available()) {
            return com.dreadedzombie.lensbridge.mcp.AgentlinkSupport.linked()
                    && com.dreadedzombie.lensbridge.mcp.AgentlinkSupport.pageUrl("/ui") != null ? null
                    : "Agentlink carries Lensbridge's connection, but Agentlink isn't listening (see the Agentlink lines in the log).";
        }
        return startError;
    }

    /** The camera manager page, with the token in the fragment: from Agentlink when linked, else the built-in server. */
    public static String managerUrl() {
        String linked = com.dreadedzombie.lensbridge.mcp.AgentlinkSupport.pageUrl("/ui");
        return linked != null ? linked : "http://" + config.host + ":" + config.port + "/ui#" + config.token;
    }

    public static BridgeConfig config() {
        return config;
    }
}
