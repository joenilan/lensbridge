package com.dreadedzombie.lensbridge.cinema;

import com.dreadedzombie.lensbridge.DirectorCamera;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A rail: keyframes [{x,y,z, yaw?,pitch?, look_at?:[x,y,z], fov?, roll?}] sampled as a Catmull-Rom spline.
 * Angles come from look_at when both neighbouring keys have one, else from yaw/pitch (shortest way round).
 */
public final class CameraPath {
    public record Key(Vec3 pos, @Nullable Float yaw, @Nullable Float pitch, @Nullable Vec3 lookAt, float fov, float roll) {
        static Key parse(JsonObject o) {
            Vec3 p = new Vec3(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble());
            Vec3 look = null;
            if (o.has("look_at")) {
                JsonArray l = o.getAsJsonArray("look_at");
                look = new Vec3(l.get(0).getAsDouble(), l.get(1).getAsDouble(), l.get(2).getAsDouble());
            }
            return new Key(p, o.has("yaw") ? o.get("yaw").getAsFloat() : null, o.has("pitch") ? o.get("pitch").getAsFloat() : null,
                    look, o.has("fov") ? o.get("fov").getAsFloat() : 0, o.has("roll") ? o.get("roll").getAsFloat() : 0);
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("x", pos.x);
            o.addProperty("y", pos.y);
            o.addProperty("z", pos.z);
            if (yaw != null) {
                o.addProperty("yaw", yaw);
            }
            if (pitch != null) {
                o.addProperty("pitch", pitch);
            }
            if (lookAt != null) {
                JsonArray l = new JsonArray();
                l.add(lookAt.x);
                l.add(lookAt.y);
                l.add(lookAt.z);
                o.add("look_at", l);
            }
            if (fov != 0) {
                o.addProperty("fov", fov);
            }
            if (roll != 0) {
                o.addProperty("roll", roll);
            }
            return o;
        }

        float[] angles() {
            if (this.lookAt != null) {
                return DirectorCamera.lookAt(this.pos, this.lookAt);
            }
            return new float[]{this.yaw == null ? 0 : this.yaw, this.pitch == null ? 0 : this.pitch};
        }
    }

    private final List<Key> keys;

    public CameraPath(List<Key> keys) {
        if (keys.size() < 2) {
            throw new IllegalArgumentException("A path needs at least 2 keyframes");
        }
        this.keys = keys;
    }

    public static CameraPath parse(JsonArray arr) {
        List<Key> keys = new ArrayList<>();
        for (JsonElement el : arr) {
            keys.add(Key.parse(el.getAsJsonObject()));
        }
        return new CameraPath(keys);
    }

    public JsonArray toJson() {
        JsonArray a = new JsonArray();
        keys.forEach(k -> a.add(k.toJson()));
        return a;
    }

    public List<Key> keys() {
        return this.keys;
    }

    /** Position on the spline at t in [0,1]. */
    public Vec3 position(double t) {
        int n = keys.size() - 1;
        double f = Math.max(0, Math.min(1, t)) * n;
        int i = Math.min(n - 1, (int) Math.floor(f));
        double u = f - i;
        return catmull(keys.get(Math.max(0, i - 1)).pos, keys.get(i).pos, keys.get(i + 1).pos, keys.get(Math.min(n, i + 2)).pos, u);
    }

    /** Full shot at t in [0,1] (angles from the keys). */
    public Shot sample(double t) {
        int n = keys.size() - 1;
        double f = Math.max(0, Math.min(1, t)) * n;
        int i = Math.min(n - 1, (int) Math.floor(f));
        double u = f - i;
        Key k1 = keys.get(i);
        Key k2 = keys.get(i + 1);
        Vec3 pos = position(t);
        float yaw;
        float pitch;
        if (k1.lookAt != null && k2.lookAt != null) {
            float[] yp = DirectorCamera.lookAt(pos, k1.lookAt.lerp(k2.lookAt, u));
            yaw = yp[0];
            pitch = yp[1];
        } else {
            float[] a1 = k1.angles();
            float[] a2 = k2.angles();
            float dy = ((a2[0] - a1[0]) % 360 + 540) % 360 - 180;
            yaw = (float) (a1[0] + dy * u);
            pitch = (float) (a1[1] + (a2[1] - a1[1]) * u);
        }
        return new Shot(pos, yaw, pitch, (float) (k1.roll + (k2.roll - k1.roll) * u), (float) (k1.fov + (k2.fov - k1.fov) * u));
    }

    /** Approximate length in blocks (for speed-based rails). */
    public double length() {
        double len = 0;
        Vec3 prev = position(0);
        for (int i = 1; i <= 200; i++) {
            Vec3 p = position(i / 200.0);
            len += p.distanceTo(prev);
            prev = p;
        }
        return len;
    }

    static Vec3 catmull(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return p1.scale(2).add(p2.subtract(p0).scale(t)).add(p0.scale(2).subtract(p1.scale(5)).add(p2.scale(4)).subtract(p3).scale(t2))
                .add(p3.subtract(p0).add(p1.scale(3)).subtract(p2.scale(3)).scale(t3)).scale(0.5);
    }
}
