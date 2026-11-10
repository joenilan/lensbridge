package com.dreadedzombie.lensbridge.server;

/**
 * What the connected server allows this player, as sent by a server that also runs Lensbridge (see
 * {@link PolicyPayload}). {@code null} = the server doesn't run Lensbridge, so the client falls back to its own default
 * rule (see Policy). No client classes here: this is set from the network handler on both sides.
 */
public final class ServerRules {
    public static volatile Boolean cameras;
    public static volatile Boolean control;

    private ServerRules() {
    }

    public static void clear() {
        cameras = null;
        control = null;
    }
}
