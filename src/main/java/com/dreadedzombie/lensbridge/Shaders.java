package com.dreadedzombie.lensbridge;

/** Optional Iris integration (via reflection, so Iris isn't required): turn shaders on/off and report state. */
public final class Shaders {
    private Shaders() {
    }

    private static Object config() throws ReflectiveOperationException {
        Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
        Object inst = api.getMethod("getInstance").invoke(null);
        return api.getMethod("getConfig").invoke(inst);
    }

    public static boolean available() {
        try {
            config();
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    /** Client thread. Returns a status line. */
    public static String set(boolean on) {
        try {
            Object cfg = config();
            cfg.getClass().getMethod("setShadersEnabledAndApply", boolean.class).invoke(cfg, on);
            return "shaders " + (on ? "ON" : "OFF");
        } catch (ReflectiveOperationException | LinkageError e) {
            return "shaders: Iris not installed";
        }
    }

    public static String state() {
        try {
            Object cfg = config();
            return (boolean) cfg.getClass().getMethod("areShadersEnabled").invoke(cfg) ? "on" : "off";
        } catch (ReflectiveOperationException | LinkageError e) {
            return "n/a";
        }
    }
}
