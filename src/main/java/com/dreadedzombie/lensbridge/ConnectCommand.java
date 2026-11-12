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
                .then(Commands.literal("connect").executes(ctx -> connect(ctx.getSource()))));
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
        if (c == null || !c.enabled || Lensbridge.server() == null) {
            return null;
        }
        return new ConnectSnippets("lensbridge", "http://" + c.host + ":" + c.port + "/mcp", c.token);
    }

    private static int connect(CommandSourceStack s) {
        ConnectSnippets c = snippets();
        if (c == null) {
            s.sendFailure(Component.literal("Lensbridge's connection is off (\"enabled\" in config/lensbridge.json, or "
                    + "Agentlink couldn't start: see the log)."));
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
}
