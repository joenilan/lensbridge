package com.dreadedzombie.lensbridge.cinema;

import com.dreadedzombie.lensbridge.Game;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Something the camera can lock onto: a fixed point, or a (possibly moving) entity tracked smoothly with
 * render partial ticks. JSON: {"entity": id} | {"nearest": "minecraft:pig"} | {"point": [x,y,z]} | {"player": true},
 * plus optional "offset": [dx,dy,dz] (aim point offset; default = 60% of the entity's height).
 */
public final class Target {
    private final @Nullable Vec3 point;
    private final int entityId;
    private final @Nullable Vec3 offset;

    private Target(@Nullable Vec3 point, int entityId, @Nullable Vec3 offset) {
        this.point = point;
        this.entityId = entityId;
        this.offset = offset;
    }

    public static Target point(Vec3 p) {
        return new Target(p, -1, null);
    }

    /** Parse a target spec (call on the client thread when it names an entity type, so it can be resolved now). */
    public static Target parse(JsonObject o) {
        Vec3 offset = null;
        if (o.has("offset")) {
            JsonArray a = o.getAsJsonArray("offset");
            offset = new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
        }
        if (o.has("point")) {
            JsonArray a = o.getAsJsonArray("point");
            return new Target(new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()), -1, offset);
        }
        if (o.has("entity")) {
            return new Target(null, o.get("entity").getAsInt(), offset);
        }
        if (o.has("player") && o.get("player").getAsBoolean()) {
            return new Target(null, Game.call(() -> Game.mc().player.getId()), offset);
        }
        if (o.has("nearest")) {
            String type = o.get("nearest").getAsString();
            int id = Game.call(() -> {
                var mc = Game.mc();
                Entity best = null;
                double bestD = Double.MAX_VALUE;
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(type) && e != mc.player) {
                        double d = e.distanceToSqr(mc.player);
                        if (d < bestD) {
                            bestD = d;
                            best = e;
                        }
                    }
                }
                if (best == null) {
                    throw new IllegalArgumentException("No loaded entity of type " + type);
                }
                return best.getId();
            });
            return new Target(null, id, offset);
        }
        throw new IllegalArgumentException("Target needs one of: entity, nearest, point, player");
    }

    /** Where to aim, this frame. Null if the entity is gone. Client thread. */
    public @Nullable Vec3 aim(float partialTick) {
        if (this.point != null) {
            return this.offset == null ? this.point : this.point.add(this.offset);
        }
        Entity e = Game.mc().level == null ? null : Game.mc().level.getEntity(this.entityId);
        if (e == null) {
            return null;
        }
        Vec3 base = e.getPosition(partialTick);
        return base.add(this.offset != null ? this.offset : new Vec3(0, e.getBbHeight() * 0.6, 0));
    }

    /** Entity id, or -1 for a point target. */
    public int entityId() {
        return this.point != null ? -1 : this.entityId;
    }

    /** Centre of the target: an entity's hitbox middle (plus offset), or the point. Null if gone. Client thread. */
    public @Nullable Vec3 centre(float partialTick) {
        if (this.point != null) {
            return this.offset == null ? this.point : this.point.add(this.offset);
        }
        Entity e = Game.mc().level == null ? null : Game.mc().level.getEntity(this.entityId);
        if (e == null) {
            return null;
        }
        Vec3 base = e.getPosition(partialTick).add(0, e.getBbHeight() * 0.5, 0);
        return this.offset == null ? base : base.add(this.offset);
    }

    /** Facing yaw of the target (entities), for follow cams. */
    public float facing(float partialTick) {
        if (this.point != null) {
            return 0;
        }
        Entity e = Game.mc().level.getEntity(this.entityId);
        return e == null ? 0 : e.getViewYRot(partialTick);
    }

    public String describe() {
        if (this.point != null) {
            return "point " + this.point;
        }
        Entity e = Game.mc().level == null ? null : Game.mc().level.getEntity(this.entityId);
        return e == null ? "entity #" + this.entityId + " (gone)"
                : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()) + " #" + this.entityId;
    }
}
