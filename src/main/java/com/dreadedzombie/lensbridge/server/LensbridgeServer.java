package com.dreadedzombie.lensbridge.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * The optional server side of Lensbridge. Lensbridge is a client mod, but a server owner can also install it to set
 * the rules for their server: who may use free cameras (drone, tripod views, director shots) and who may let an AI
 * assistant act for them (move, look, press keys, click menus, chat, run commands). Without it, clients use their
 * built-in rule: ops and spectators only, anything goes in singleplayer.
 *
 * Rules come from config/serverconfig/lensbridge-server.toml (per world) and can be overridden per player or group
 * with a permission mod (e.g. LuckPerms) through the nodes {@code lensbridge.free_cameras} and
 * {@code lensbridge.ai_control}. Players without Lensbridge can still join: the channel is optional.
 */
@Mod(LensbridgeServer.MODID)
public final class LensbridgeServer {
    public static final String MODID = "lensbridge";

    public enum Who { OPS, EVERYONE, NOBODY }

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();
    public static final ModConfigSpec.EnumValue<Who> CAMERAS = B
            .comment("Who may use Lensbridge's free cameras here: the drone, looking through placed cameras, director shots.",
                    "OPS = operators (and players in spectator mode), EVERYONE, or NOBODY.",
                    "A permission mod can override this per player with the node lensbridge.free_cameras.")
            .defineEnum("freeCameras", Who.OPS);
    public static final ModConfigSpec.EnumValue<Who> CONTROL = B
            .comment("Who may let an AI assistant act for them through Lensbridge here: move, look, press keys, click",
                    "menus, chat and run commands (commands still need their normal permissions). OPS, EVERYONE or NOBODY.",
                    "Permission node: lensbridge.ai_control.")
            .defineEnum("aiControl", Who.OPS);
    public static final ModConfigSpec SPEC = B.build();

    public static final PermissionNode<Boolean> CAMERAS_NODE = new PermissionNode<>(MODID, "free_cameras",
            PermissionTypes.BOOLEAN, (player, uuid, ctx) -> allowed(CAMERAS.get(), player, true));
    public static final PermissionNode<Boolean> CONTROL_NODE = new PermissionNode<>(MODID, "ai_control",
            PermissionTypes.BOOLEAN, (player, uuid, ctx) -> allowed(CONTROL.get(), player, false));

    /** Last rule sent to each player, so changes (op/deop, gamemode, permission edits) are pushed within seconds. */
    private static final Map<UUID, Integer> SENT = new HashMap<>();

    public LensbridgeServer(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC);
        modBus.addListener(LensbridgeServer::registerPayloads);
        NeoForge.EVENT_BUS.addListener((PermissionGatherEvent.Nodes e) -> e.addNodes(CAMERAS_NODE, CONTROL_NODE));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                SENT.remove(p.getUUID());
                send(p);
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> SENT.remove(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            if (e.getServer().getTickCount() % 40 == 0) {
                e.getServer().getPlayerList().getPlayers().forEach(LensbridgeServer::send);
            }
        });
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(PolicyPayload.TYPE, PolicyPayload.CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> {
                    ServerRules.cameras = payload.cameras();
                    ServerRules.control = payload.control();
                }));
    }

    static boolean allowed(Who who, ServerPlayer player, boolean spectatorsToo) {
        return switch (who) {
            case EVERYONE -> true;
            case NOBODY -> false;
            case OPS -> player != null && (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
                    || (spectatorsToo && player.isSpectator()));
        };
    }

    private static void send(ServerPlayer p) {
        if (!p.connection.hasChannel(PolicyPayload.TYPE)) {
            return;   // the player doesn't have Lensbridge
        }
        boolean cams = PermissionAPI.getPermission(p, CAMERAS_NODE);
        boolean ctrl = PermissionAPI.getPermission(p, CONTROL_NODE);
        int code = (cams ? 1 : 0) | (ctrl ? 2 : 0);
        Integer before = SENT.put(p.getUUID(), code);
        if (before == null || before != code) {
            PacketDistributor.sendToPlayer(p, new PolicyPayload(cams, ctrl));
        }
    }
}
