package com.dreadedzombie.lensbridge;

import com.dreadedzombie.lensbridge.mcp.AgentlinkSupport;
import com.dreadedzombie.lensbridge.mcp.ConnectSnippets;
import com.dreadedzombie.lensbridge.server.ServerAgent;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * {@code /lensbridge connect} (client-side, works on any server): click-to-copy setup that connects an AI app to this
 * game's Lensbridge (or to Agentlink, when it carries Lensbridge's tools). The one setup step for staff too: a server
 * that relays its tools sends them through this same connection.
 * <p>The token lives only inside the copy buttons: the client logs the visible text of chat, not what a click copies.
 */
final class ConnectCommand {
    private ConnectCommand() {
    }

    static void register(RegisterClientCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("lensbridge")
                .then(Commands.literal("connect").executes(ctx -> connect(ctx.getSource())))
                .then(Commands.literal("restore").executes(ctx -> {
                    // what `scene` / `window_size` left changed: world freeze, advance_time, shaders, window
                    String done = Restore.apply();
                    ctx.getSource().sendSuccess(() -> Component.literal(done), false);
                    return 1;
                }))
                .then(Commands.literal("cameras")
                        .executes(ctx -> cameras(ctx.getSource()))
                        .then(Commands.literal("undo").executes(ctx -> {
                            int n = UserControls.undoRemove();
                            ctx.getSource().sendSuccess(() -> Component.literal(n == 0 ? "Nothing to bring back."
                                    : "Brought back " + n + (n == 1 ? " camera." : " cameras.")), false);
                            return n;
                        }))
                        .then(Commands.literal("remove")
                                .then(Commands.literal("all").executes(ctx -> removeCameras(ctx.getSource(), null)))
                                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                com.dreadedzombie.lensbridge.cinema.Tripods.here().stream()
                                                        .map(com.dreadedzombie.lensbridge.cinema.Tripods.Tripod::name), b))
                                        .executes(ctx -> removeCameras(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("inset")
                        .then(Commands.literal("off").executes(ctx -> {
                            Inset.stop();
                            return 1;
                        }))
                        .then(Commands.literal("full").executes(ctx -> view(ctx.getSource(), true)))
                        .then(Commands.literal("small").executes(ctx -> view(ctx.getSource(), false)))
                        .then(Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(ctx -> inset(ctx.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player"), "chase", false))
                                .then(mode("chase")).then(mode("front")).then(mode("overhead")).then(mode("eyes"))
                                .then(Commands.literal("full").executes(ctx -> inset(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player"), "chase", true))))));
    }

    /** /lensbridge inset <player> <mode> [full] */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> mode(String m) {
        return Commands.literal(m)
                .executes(ctx -> inset(ctx.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player"), m, false))
                .then(Commands.literal("full").executes(ctx -> inset(ctx.getSource(),
                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player"), m, true)));
    }

    private static int inset(net.minecraft.commands.CommandSourceStack src, String player, String mode, boolean full) {
        try {
            Inset.start(player, mode, 30, 0.3f, "top_right");
            Inset.setFull(full);
        } catch (IllegalStateException ex) {
            src.sendFailure(Component.literal(ex.getMessage()));
            return 0;
        }
        return 1;
    }

    private static int view(net.minecraft.commands.CommandSourceStack src, boolean full) {
        if (!Inset.on) {
            src.sendFailure(Component.literal("No inset is open. Start one with /lensbridge inset <player>."));
            return 0;
        }
        Inset.setFull(full);
        return 1;
    }

    /** Where an AI app should connect: Agentlink when it carries Lensbridge's tools, else Lensbridge's own server. */
    static ConnectSnippets snippets() {
        if (AgentlinkSupport.available()) {
            String page = AgentlinkSupport.pageUrl("/ui"); // <base>/ui#<token>
            if (page == null) {
                return null;
            }
            int hash = page.indexOf('#');
            return new ConnectSnippets("lensbridge", page.substring(0, page.indexOf("/ui")) + "/mcp", page.substring(hash + 1));
        }
        BridgeConfig c = Lensbridge.config();
        if (c == null || !c.enabled || Lensbridge.server() == null || Lensbridge.unavailableReason() != null) {
            return null;   // off, or the server didn't start (port in use): don't hand out an address that isn't ours
        }
        return new ConnectSnippets("lensbridge", "http://" + c.host + ":" + c.port + "/mcp", c.token);
    }

    private static int connect(CommandSourceStack s) {
        ConnectSnippets c = snippets();
        if (c == null) {
            String why = Lensbridge.unavailableReason();
            s.sendFailure(Component.literal(why != null ? why : "Lensbridge's connection is off (\"enabled\" in "
                    + "config/lensbridge.json, or Agentlink couldn't start: see the log)."));
            return 0;
        }
        MutableComponent msg = Component.literal("Connect an AI app to Lensbridge").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
        msg.append(Component.literal("\nThe AI app runs on this computer. Click to copy, then paste:").withStyle(ChatFormatting.GRAY));
        msg.append(line("Claude Code", "into a terminal", c.claudeCode()));
        msg.append(line("Claude Desktop", "into Settings > Developer > Edit Config, restart Claude (needs Node.js)", c.claudeDesktop()));
        msg.append(line("Cursor, VS Code, other apps", "into the app's MCP settings", c.otherApps()));
        msg.append(line("Token only", "wherever an app asks for it", c.token()));
        msg.append(Component.literal("\nOnce only: it keeps working on every server and in singleplayer.").withStyle(ChatFormatting.GRAY));
        var hello = ServerAgent.hello;
        if (hello != null && !"none".equals(hello.get("access").getAsString())) {
            msg.append(Component.literal("\nThis server also lends your AI its own tools (access: "
                    + hello.get("access").getAsString() + "): ask it to use server_tools.").withStyle(ChatFormatting.AQUA));
        }
        msg.append(Component.literal("\nThe token is a password: don't paste it into chat or screenshots.").withStyle(ChatFormatting.DARK_GRAY));
        s.sendSuccess(() -> msg, false);
        return 1;
    }

    private static MutableComponent line(String label, String where, String copied) {
        return Component.literal("\n ").append(Component.literal("[" + label + "]").withStyle(st -> st.withColor(ChatFormatting.AQUA)
                        .withUnderlined(true).withClickEvent(new ClickEvent.CopyToClipboard(copied))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy, then paste " + where)))))
                .append(Component.literal(" " + where).withStyle(ChatFormatting.DARK_GRAY));
    }

    /** /lensbridge cameras: every camera of this world and dimension with how far away it is, and how to remove one. */
    private static int cameras(CommandSourceStack src) {
        var all = com.dreadedzombie.lensbridge.cinema.Tripods.here();
        int elsewhere = com.dreadedzombie.lensbridge.cinema.Tripods.all().size() - all.size();
        if (all.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No cameras placed here. Alt+T places one where you're looking from."
                    + (elsewhere > 0 ? " (" + elsewhere + " in other worlds or dimensions.)" : "")), false);
            return 0;
        }
        var here = src.getPosition();
        MutableComponent msg = Component.literal(all.size() + (all.size() == 1 ? " camera" : " cameras")).withStyle(ChatFormatting.GREEN);
        for (var t : all) {
            String name = t.name();
            msg.append(Component.literal("\n " + name + "  " + Math.round(Math.sqrt(t.pos().distanceToSqr(here))) + " blocks away  ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("[remove]").withStyle(st -> st.withColor(ChatFormatting.RED)
                            .withClickEvent(new ClickEvent.RunCommand("/lensbridge cameras remove " + name))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Remove " + name)))));
        }
        msg.append(Component.literal("\nAlt+X removes the one you look at or stand next to. /lensbridge cameras remove all clears "
                + "the ones listed here; /lensbridge cameras undo brings the last removal back."
                + (elsewhere > 0 ? " " + elsewhere + " more in other worlds or dimensions are not shown." : ""))
                .withStyle(ChatFormatting.DARK_GRAY));
        src.sendSuccess(() -> msg, false);
        return all.size();
    }

    /** name == null removes every camera of this world and dimension (other worlds' cameras are left alone). */
    private static int removeCameras(CommandSourceStack src, String name) {
        var here = com.dreadedzombie.lensbridge.cinema.Tripods.here();
        java.util.List<com.dreadedzombie.lensbridge.cinema.Tripods.Tripod> gone;
        if (name == null) {
            gone = here;
        } else {
            gone = here.stream().filter(t -> t.name().equals(name)).toList();
            if (gone.isEmpty()) {
                src.sendFailure(Component.literal(com.dreadedzombie.lensbridge.cinema.Tripods.get(name) != null
                        ? "\"" + name + "\" is a camera in another world or dimension: remove it there."
                        : "No camera called \"" + name + "\". /lensbridge cameras lists them."));
                return 0;
            }
        }
        UserControls.removeCameras(gone);
        int n = gone.size();
        src.sendSuccess(() -> Component.literal(n == 0 ? "No cameras to remove."
                : "Removed " + n + (n == 1 ? " camera" : " cameras") + ". /lensbridge cameras undo brings "
                        + (n == 1 ? "it" : "them") + " back."), false);
        return n;
    }
}
