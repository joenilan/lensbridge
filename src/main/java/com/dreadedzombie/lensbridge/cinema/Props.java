package com.dreadedzombie.lensbridge.cinema;

import com.dreadedzombie.lensbridge.DirectorCamera;
import com.dreadedzombie.lensbridge.Game;
import com.dreadedzombie.lensbridge.mixin.DisplayAccessor;
import com.dreadedzombie.lensbridge.mixin.ItemDisplayAccessor;
import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Camera props: visible models of the tripods (camera head on legs), the drone (with spinning rotors) and the current
 * rail, for behind-the-scenes shots. They are client-only display entities, so servers and other players never see
 * them. Who sees them:
 * <ul>
 * <li>the player's own view only if {@link #playerView} is on (handy while setting cameras up);</li>
 * <li>a capture only if the camera shooting it is in {@link #crew} (a "behind the scenes" camera);</li>
 * <li>never the camera's own prop, from that camera.</li>
 * </ul>
 * So normal shots, thumbnails and multicam stay clean unless asked otherwise.
 */
public final class Props {
    public static volatile boolean enabled;
    public static volatile boolean playerView;
    public static final Set<String> crew = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Parked drone props for staged behind-the-scenes shots: {x, y, z, yaw}. Seen like the real drone's prop. */
    public static final List<double[]> stagedDrones = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final Identifier HEAD = model("tripod_head");
    private static final Identifier LEGS = model("tripod_legs");
    private static final Identifier DRONE = model("drone");
    private static final Identifier ROTOR = model("drone_rotor");
    private static final Identifier MARKER = model("rail_marker");
    private static final double ROTOR_ARM = 4.95 / 16.0;   // motor offset from the drone centre (model px -> blocks)

    /** prop entity id -> owning camera source ("drone", a tripod name, or "rail"). */
    private static final Map<Integer, String> owner = new HashMap<>();
    private static final Map<String, List<Display.ItemDisplay>> live = new HashMap<>();
    private static int nextId = -2_000_000;
    private static @Nullable ClientLevel level;

    private Props() {
    }

    private static Identifier model(String name) {
        return Identifier.fromNamespaceAndPath("lensbridge", name);
    }

    /** Whether an entity is one of our client-only camera props (for overlay mods like Jade to ignore). */
    public static boolean isProp(Entity e) {
        return owner.containsKey(e.getId());
    }

    /** Render hook: should this entity be skipped for the frame being drawn? */
    public static boolean hide(Entity e) {
        String own = owner.get(e.getId());
        if (own == null) {
            return false;
        }
        if (!enabled) {
            return true;
        }
        String src = DirectorCamera.shown() ? DirectorCamera.source : "player";
        if (src.equals("player")) {
            return !playerView;
        }
        return !crew.contains(src) || own.equals(src);
    }

    /** Called every frame before rendering (render thread): add, move and remove props. */
    public static void frame(float partialTick) {
        ClientLevel lvl = Game.mc().level;
        if (lvl != level) {
            owner.clear();
            live.clear();
            level = lvl;
        }
        if (lvl == null) {
            return;
        }
        Map<String, List<Pose>> want = new HashMap<>();
        if (enabled) {
            for (Tripods.Tripod t : Tripods.here()) {
                Shot s = Tripods.shot(t, partialTick);
                want.put(t.name(), tripod(lvl, s));
            }
            if (DirectorCamera.program instanceof Drone && DirectorCamera.active) {
                want.put("drone", drone(DirectorCamera.pos, DirectorCamera.yaw, DirectorCamera.roll, lvl.getGameTime() + partialTick));
            }
            for (int i = 0; i < stagedDrones.size(); i++) {
                double[] d = stagedDrones.get(i);
                want.put("staged_drone_" + i, drone(new Vec3(d[0], d[1], d[2]), (float) d[3], 0, lvl.getGameTime() + partialTick));
            }
            if (DirectorCamera.program instanceof CameraProgram.Rail rail && DirectorCamera.active) {
                List<Pose> dots = new ArrayList<>();
                CameraPath path = rail.path();
                int n = Math.max(2, (int) Math.ceil(path.length() / 0.45));
                for (int i = 0; i <= n; i++) {
                    Vec3 p = path.position((double) i / n);
                    dots.add(new Pose(MARKER, p, 0, 0, new Vector3f(1, 1, 1), null));
                }
                want.put("rail", dots);
            }
        }
        // remove props whose camera is gone (or everything when disabled)
        for (String key : new HashSet<>(live.keySet())) {
            if (!want.containsKey(key) || want.get(key).size() != live.get(key).size()) {
                for (Display.ItemDisplay d : live.remove(key)) {
                    owner.remove(d.getId());
                    lvl.removeEntity(d.getId(), Entity.RemovalReason.DISCARDED);
                }
            }
        }
        // A display builds its render state in tick(); a frozen world doesn't tick entities, so new or changed props
        // would never show in frozen staging. Tick them ourselves then (it only refreshes their render state).
        boolean frozen = lvl.tickRateManager().isFrozen();
        for (Map.Entry<String, List<Pose>> e : want.entrySet()) {
            List<Display.ItemDisplay> ents = live.computeIfAbsent(e.getKey(), k -> spawn(lvl, k, e.getValue()));
            for (int i = 0; i < ents.size(); i++) {
                place(ents.get(i), e.getValue().get(i));
                if (frozen) {
                    ents.get(i).tick();
                }
            }
        }
    }

    /** One prop part: model, position, yaw/pitch (smooth, per frame) and an optional scale/roll transformation. */
    private record Pose(Identifier model, Vec3 pos, float yaw, float pitch, Vector3f scale, @Nullable Quaternionf roll) {
    }

    private static List<Pose> tripod(ClientLevel lvl, Shot s) {
        List<Pose> parts = new ArrayList<>();
        parts.add(new Pose(HEAD, s.pos(), s.yaw(), s.pitch(), new Vector3f(1, 1, 1), null));
        // legs from just under the head down to the ground (max 4 blocks; higher looks like a jib arm without legs)
        Vec3 top = s.pos().add(0, -0.2, 0);
        HitResult hit = lvl.clip(new ClipContext(top, top.add(0, -4, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        double len = hit.getType() == HitResult.Type.MISS ? 0 : top.y - hit.getLocation().y;
        if (len > 0.2) {
            parts.add(new Pose(LEGS, new Vec3(top.x, top.y - len / 2, top.z), s.yaw(), 0, new Vector3f(1, (float) len, 1), null));
        }
        return parts;
    }

    private static List<Pose> drone(Vec3 pos, float yaw, float roll, double ticks) {
        List<Pose> parts = new ArrayList<>();
        Vec3 body = pos.add(0, -0.25, 0);   // just below the lens, so the camera isn't inside its own body
        Quaternionf bank = new Quaternionf().rotationZ((float) Math.toRadians(-roll));
        parts.add(new Pose(DRONE, body, yaw, 0, new Vector3f(1, 1, 1), bank));
        double r = Math.toRadians(-yaw);
        for (int i = 0; i < 4; i++) {
            double a = r + Math.PI / 4 + i * Math.PI / 2;
            Vec3 m = body.add(Math.sin(a) * ROTOR_ARM, 0.08, Math.cos(a) * ROTOR_ARM);
            float spin = (float) ((ticks * 97 + i * 45) % 360);
            parts.add(new Pose(ROTOR, m, spin, 0, new Vector3f(1, 1, 1), null));
        }
        return parts;
    }

    private static List<Display.ItemDisplay> spawn(ClientLevel lvl, String key, List<Pose> poses) {
        List<Display.ItemDisplay> out = new ArrayList<>();
        for (Pose p : poses) {
            Display.ItemDisplay d = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, lvl);
            d.setId(nextId--);
            ItemStack stack = new ItemStack(Items.STICK);
            stack.set(DataComponents.ITEM_MODEL, p.model());
            // a client-only mod can't add items, so the prop is a stick with our model; give it a proper name for any
            // overlay mod that shows what you are looking at (Jade also gets "Lensbridge" as the mod, see JadeCompat)
            String label = key.equals("drone") || key.startsWith("staged_drone") ? "Lensbridge drone"
                    : key.equals("rail") ? "Lensbridge camera rail" : "Lensbridge camera: " + key;
            stack.set(DataComponents.ITEM_NAME, net.minecraft.network.chat.Component.literal(label));
            ((ItemDisplayAccessor) d).lensbridge$setItemStack(stack);
            ((DisplayAccessor) d).lensbridge$setViewRange(4.0F);
            d.snapTo(p.pos().x, p.pos().y, p.pos().z, p.yaw(), p.pitch());
            lvl.addEntity(d);
            owner.put(d.getId(), key);
            out.add(d);
        }
        return out;
    }

    private static void place(Display.ItemDisplay d, Pose p) {
        d.setPos(p.pos());
        d.xo = d.xOld = p.pos().x;
        d.yo = d.yOld = p.pos().y;
        d.zo = d.zOld = p.pos().z;
        d.setYRot(p.yaw());
        d.setXRot(p.pitch());
        d.yRotO = p.yaw();
        d.xRotO = p.pitch();
        Transformation tr = new Transformation(new Vector3f(), p.roll(), p.scale(), null);
        ((DisplayAccessor) d).lensbridge$setTransformation(tr);
    }

    /** Remove everything (e.g. when disabled from a tool). Render thread. */
    public static void clear() {
        ClientLevel lvl = Game.mc().level;
        for (List<Display.ItemDisplay> list : live.values()) {
            for (Display.ItemDisplay d : list) {
                if (lvl != null) {
                    lvl.removeEntity(d.getId(), Entity.RemovalReason.DISCARDED);
                }
            }
        }
        live.clear();
        owner.clear();
    }

    public static String describe() {
        return String.format("props %s | player view %s | crew %s | %d props live", enabled ? "ON" : "off",
                playerView ? "on" : "off", crew.isEmpty() ? "(none)" : String.join(", ", crew),
                live.values().stream().mapToInt(List::size).sum());
    }
}
