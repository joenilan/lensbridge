package com.dreadedzombie.lensbridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

/** Rolling log of chat + system messages the client received, numbered so callers can poll "since". */
public final class ChatLog {
    public record Line(long id, long time, boolean system, String text) {
    }

    private static final Deque<Line> LINES = new ArrayDeque<>();
    private static long next = 1;

    private ChatLog() {
    }

    static synchronized void onChat(ClientChatReceivedEvent event) {
        LINES.addLast(new Line(next++, System.currentTimeMillis(), event.isSystem(), event.getMessage().getString()));
        while (LINES.size() > 500) {
            LINES.removeFirst();
        }
    }

    public static synchronized List<Line> since(long id) {
        List<Line> out = new ArrayList<>();
        for (Line line : LINES) {
            if (line.id() > id) {
                out.add(line);
            }
        }
        return out;
    }
}
