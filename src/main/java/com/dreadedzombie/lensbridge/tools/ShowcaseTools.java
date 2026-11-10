package com.dreadedzombie.lensbridge.tools;

import com.dreadedzombie.lensbridge.Capture;
import com.dreadedzombie.lensbridge.DirectorCamera;
import com.dreadedzombie.lensbridge.Game;
import com.dreadedzombie.lensbridge.Stealth;
import com.dreadedzombie.lensbridge.cinema.AutoFrame;
import com.dreadedzombie.lensbridge.mcp.BridgeServer;
import com.dreadedzombie.lensbridge.mcp.Tool;
import com.dreadedzombie.lensbridge.mcp.Tool.Result;
import com.dreadedzombie.lensbridge.mcp.Tool.Schema;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Showcase Stages: build a clean display of a mod's mobs, blocks and items in one go, then frame and shoot every
 * exhibit (stills, optional turntables, a contact sheet and an index page) for a mod page or listing.
 *
 * Built with a fast batch of ordinary commands (needs cheats/op): a floor and backdrop walls in the sky, mobs held
 * still (NoAI) in a row facing the cameras, blocks on a row of plinths, items in frames on a wall. Cameras look north,
 * so every row has its backdrop behind it.
 */
public final class ShowcaseTools {
    private ShowcaseTools() {
    }

    /** One exhibit. kind: mob / block / item. x,y,z = where it stands; w,h = rough size in blocks. */
    record Exhibit(String kind, String id, String label, String nbt, double x, double y, double z, double w, double h) {
        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("kind", kind);
            o.addProperty("id", id);
            o.addProperty("label", label);
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            o.addProperty("w", w);
            o.addProperty("h", h);
            return o;
        }
    }

    private static final List<Exhibit> STAGE = new ArrayList<>();
    private static String stageName = "stage";
    private static AABB stageBox;            // exactly the blocks the stage occupies (cleared by stage_clear)
    private static int[] stageForce;         // forceloaded rectangle {x0, z0, x1, z1}, released by stage_clear
    private static final int FILL_LIMIT = 32768;
    private static final int FORCELOAD_LIMIT = 256;

    private static void cmd(String c) {
        Game.run(() -> Game.mc().player.connection.sendCommand(c));
    }

    /** Send a batch quickly: a few commands per frame so the server keeps up and the client stays responsive. */
    private static void batch(List<String> cmds) {
        for (int i = 0; i < cmds.size(); i++) {
            cmd(cmds.get(i));
            if (i % 8 == 7) {
                Game.waitFrames(1);
            }
        }
        Game.sleep(400);
    }

    private static String block(String b) {
        return switch (b) {
            case "gray" -> "gray_concrete";
            case "black" -> "black_concrete";
            case "white" -> "white_concrete";
            case "chroma" -> "lime_concrete";
            default -> b;
        };
    }

    public static void registerAll(BridgeServer s) {
        s.register(new Tool("stage_build", "Showcase Stage: build a clean display of a mod's content in one go - mobs held still "
                + "(NoAI) in rows, blocks on plinths, items in frames, a floor and backdrop walls - high in the sky so nothing "
                + "gets in the way. Reads the mod's registered entity types, blocks and items. Freezes the world when done. "
                + "Then call stage_shoot. Needs cheats/op.",
                new Schema().req("namespace", "string", "Mod id, e.g. mothlight")
                        .prop("include", "array", "Kinds to include: mob, block, item (default all three)")
                        .prop("exclude", "array", "Ids to leave out, e.g. [\"mothlight:moth_sheet\"]")
                        .prop("extra", "array", "Extra exhibits, e.g. variants: [{kind:'mob', id:'mothlight:wild_moth', "
                                + "nbt:'Species:\"luna\"', label:'Luna moth'}] (nbt without braces)")
                        .prop("backdrop", "string", "gray (default) / black / white / chroma / any block id")
                        .prop("per_row", "integer", "Exhibits per row (default 10)")
                        .prop("x", "number", "Stage origin (default: the player's x/z, near the top of the world)")
                        .prop("y", "number", "").prop("z", "number", "")
                        .prop("force", "boolean", "Build even where the space isn't empty (it is cleared!) or not loaded")
                        .build(),
                a -> {
                    String ns = a.str("namespace").toLowerCase(Locale.ROOT);
                    if (!ns.matches("[a-z0-9_.-]+") || ns.startsWith(".")) {
                        throw new IllegalArgumentException("namespace must be a mod id like 'mothlight'");
                    }
                    List<String> include = new ArrayList<>();
                    if (a.has("include")) {
                        a.arr("include").forEach(e -> include.add(e.getAsString()));
                    } else {
                        include.addAll(List.of("mob", "block", "item"));
                    }
                    List<String> exclude = new ArrayList<>();
                    if (a.has("exclude")) {
                        a.arr("exclude").forEach(e -> exclude.add(e.getAsString()));
                    }
                    String backdrop = block(a.str("backdrop", "gray"));
                    int perRow = Math.max(2, a.i("per_row", 10));

                    // ---- what to show
                    List<Exhibit> mobs = new ArrayList<>();
                    List<Exhibit> blocks = new ArrayList<>();
                    List<Exhibit> items = new ArrayList<>();
                    Game.call(() -> {
                        if (include.contains("mob")) {
                            for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
                                Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(t);
                                if (!id.getNamespace().equals(ns) || exclude.contains(id.toString()) || !t.canSummon()
                                        || t.getCategory() == MobCategory.MISC) {
                                    continue;
                                }
                                EntityDimensions d = t.getDimensions();
                                mobs.add(new Exhibit("mob", id.toString(), t.getDescription().getString(), "", 0, 0, 0,
                                        d.width(), d.height()));
                            }
                        }
                        if (include.contains("block")) {
                            for (Block b : BuiltInRegistries.BLOCK) {
                                Identifier id = BuiltInRegistries.BLOCK.getKey(b);
                                if (id.getNamespace().equals(ns) && !exclude.contains(id.toString())) {
                                    blocks.add(new Exhibit("block", id.toString(), b.getName().getString(), "", 0, 0, 0, 1, 1));
                                }
                            }
                        }
                        if (include.contains("item")) {
                            for (Item it : BuiltInRegistries.ITEM) {
                                Identifier id = BuiltInRegistries.ITEM.getKey(it);
                                if (id.getNamespace().equals(ns) && !(it instanceof BlockItem) && !exclude.contains(id.toString())) {
                                    items.add(new Exhibit("item", id.toString(), it.getName().getString(), "", 0, 0, 0, 1, 1));
                                }
                            }
                        }
                        return null;
                    });
                    if (a.has("extra")) {
                        for (JsonElement el : a.arr("extra")) {
                            JsonObject o = el.getAsJsonObject();
                            String kind = o.has("kind") ? o.get("kind").getAsString() : "mob";
                            String id = o.get("id").getAsString();
                            String nbt = o.has("nbt") ? o.get("nbt").getAsString() : "";
                            String label = o.has("label") ? o.get("label").getAsString() : id;
                            double[] wh = Game.call(() -> {
                                EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id));
                                return t == null ? new double[] {1, 1} : new double[] {t.getDimensions().width(), t.getDimensions().height()};
                            });
                            Exhibit ex = new Exhibit(kind, id, label, nbt, 0, 0, 0, wh[0], wh[1]);
                            (kind.equals("block") ? blocks : kind.equals("item") ? items : mobs).add(ex);
                        }
                    }
                    if (mobs.isEmpty() && blocks.isEmpty() && items.isEmpty()) {
                        throw new IllegalArgumentException("Nothing registered under '" + ns + "'");
                    }

                    // ---- layout: rows run along X; each row has its backdrop wall to the north (cameras look north)
                    double maxMob = mobs.stream().mapToDouble(e -> Math.max(e.w(), e.h())).max().orElse(1);
                    int height = (int) Math.ceil(Math.max(6, maxMob * 2 + 4));
                    Vec3 p = Game.call(() -> Game.mc().player.position());
                    int top = Game.call(() -> Game.mc().level.getMaxY());
                    int ox = (int) Math.floor(a.num("x", p.x));
                    // default: just under the build limit, far above anything people build (and checked below)
                    int oy = (int) Math.floor(a.num("y", top - height - 2));
                    int oz = (int) Math.floor(a.num("z", p.z));
                    double spacing = Math.max(3, maxMob * 2 + 1);
                    int rowDepth = (int) Math.ceil(Math.max(9, maxMob * 4 + 5));
                    List<Exhibit> placed = new ArrayList<>();
                    List<String> build = new ArrayList<>();
                    int z = oz;
                    int widest = 0;
                    // mobs
                    for (int i = 0; i < mobs.size(); i += perRow) {
                        List<Exhibit> row = mobs.subList(i, Math.min(mobs.size(), i + perRow));
                        for (int k = 0; k < row.size(); k++) {
                            Exhibit e = row.get(k);
                            double x = ox + 2 + k * spacing;
                            placed.add(new Exhibit("mob", e.id(), e.label(), e.nbt(), x + 0.5, oy + 1, z + 0.5, e.w(), e.h()));
                        }
                        widest = Math.max(widest, (int) Math.ceil(row.size() * spacing) + 4);
                        z += rowDepth;
                    }
                    // blocks, on plinths
                    for (int i = 0; i < blocks.size(); i += perRow * 2) {
                        List<Exhibit> row = blocks.subList(i, Math.min(blocks.size(), i + perRow * 2));
                        for (int k = 0; k < row.size(); k++) {
                            Exhibit e = row.get(k);
                            placed.add(new Exhibit("block", e.id(), e.label(), e.nbt(), ox + 2 + k * 3, oy + 2, z, 1, 1));
                        }
                        widest = Math.max(widest, row.size() * 3 + 4);
                        z += 9;
                    }
                    // items, in frames on a wall
                    for (int i = 0; i < items.size(); i += perRow * 2) {
                        List<Exhibit> row = items.subList(i, Math.min(items.size(), i + perRow * 2));
                        for (int k = 0; k < row.size(); k++) {
                            Exhibit e = row.get(k);
                            placed.add(new Exhibit("item", e.id(), e.label(), e.nbt(), ox + 2 + k * 2, oy + 2, z, 1, 1));
                        }
                        widest = Math.max(widest, row.size() * 2 + 4);
                        z += 7;
                    }
                    ox -= 4;
                    int x1 = ox + widest + 8;
                    int z0 = oz - 3;
                    int z1 = z + 2;
                    int y1 = Math.min(oy + height, top);
                    if ((long) (x1 - ox + 1) * (z1 - z0 + 1) > FILL_LIMIT) {
                        throw new IllegalArgumentException(String.format("Stage too big (%d x %d blocks per layer, the fill "
                                + "limit is %d): use exclude/include or a smaller per_row", x1 - ox + 1, z1 - z0 + 1, FILL_LIMIT));
                    }
                    int[] force = {ox - 8, z0 - 8, x1 + 8, z1 + 8};
                    long chunks = (long) ((force[2] >> 4) - (force[0] >> 4) + 1) * ((force[3] >> 4) - (force[1] >> 4) + 1);
                    if (chunks > FORCELOAD_LIMIT) {
                        throw new IllegalArgumentException("Stage spans " + chunks + " chunks (forceload limit " + FORCELOAD_LIMIT + ")");
                    }
                    // replace any previous stage (so it is never orphaned)
                    boolean hadStage;
                    synchronized (STAGE) {
                        hadStage = stageBox != null;
                    }
                    if (hadStage) {
                        clearStage();
                    }
                    // never carve into someone's world: the box must be loaded, empty and free of entities
                    if (!a.bool("force", false)) {
                        final int fx0 = ox, fy0 = oy, fz0 = z0, fx1 = x1, fy1 = y1, fz1 = z1;
                        String problem = Game.call(() -> occupied(fx0, fy0, fz0, fx1, fy1, fz1));
                        if (problem != null) {
                            throw new IllegalStateException(String.format("Not building at %d %d %d - %d %d %d: %s. Pick another "
                                    + "x/y/z (or stand closer so it is loaded), or pass force:true to clear it anyway.",
                                    ox, oy, z0, x1, y1, z1, problem));
                        }
                    }

                    build.add("tick unfreeze");
                    build.add(String.format("forceload add %d %d %d %d", force[0], force[1], force[2], force[3]));
                    build.add(killIn(ox, oy, z0, x1, y1, z1));
                    batch(build);
                    Game.sleep(4500);   // let anything killed finish dying (and its poof fade), then freeze before placing, so
                    build.clear();      // timed blocks (eggs, crops, cocoons) and spawned mobs stay exactly as placed
                    build.add("tick freeze");
                    for (int y = oy; y <= y1; y++) {   // clear the volume layer by layer (fill limit)
                        build.add(String.format("fill %d %d %d %d %d %d air", ox, y, z0, x1, y, z1));
                    }
                    build.add(String.format("fill %d %d %d %d %d %d %s", ox, oy, z0, x1, oy, z1, backdrop));   // floor
                    // a backdrop wall north of every row
                    for (Exhibit e : placed) {
                        int wz = (int) Math.floor(e.z()) - (e.kind().equals("mob") ? 3 : 2);
                        String wall = String.format("fill %d %d %d %d %d %d %s", ox, oy + 1, wz, x1, y1, wz, backdrop);
                        if (!build.contains(wall)) {
                            build.add(wall);
                        }
                    }
                    for (Exhibit e : placed) {
                        switch (e.kind()) {
                            case "mob" -> build.add(String.format(Locale.ROOT,
                                    "summon %s %.2f %.2f %.2f {NoAI:1b,PersistenceRequired:1b,Invulnerable:1b,Silent:1b,"
                                            + "NoGravity:1b,Rotation:[0f,0f]%s}", e.id(), e.x(), e.y(), e.z(),
                                    e.nbt().isEmpty() ? "" : "," + e.nbt()));
                            case "block" -> {
                                build.add(String.format(Locale.ROOT, "setblock %d %d %d smooth_stone", (int) e.x(), (int) e.y() - 1, (int) e.z()));
                                build.add(String.format(Locale.ROOT, "setblock %d %d %d %s%s", (int) e.x(), (int) e.y(), (int) e.z(),
                                        e.id(), e.nbt()));
                            }
                            default -> build.add(String.format(Locale.ROOT,
                                    "summon item_frame %d %d %d {Facing:3b,Fixed:1b,Invulnerable:1b,Item:{id:\"%s\",count:1}}",
                                    (int) e.x(), (int) e.y(), (int) e.z() - 1, e.id()));
                        }
                    }
                    batch(build);
                    Game.sleep(800);
                    synchronized (STAGE) {
                        STAGE.clear();
                        STAGE.addAll(placed);
                        stageName = ns;
                        stageBox = new AABB(ox, oy, z0, x1, y1, z1);
                        stageForce = force;
                    }
                    saveStage();
                    long nm = placed.stream().filter(e -> e.kind().equals("mob")).count();
                    long nb = placed.stream().filter(e -> e.kind().equals("block")).count();
                    return Result.text(String.format("Stage '%s' built at %d %d %d: %d mobs, %d blocks, %d items (%d commands). "
                            + "World frozen. Next: stage_shoot.", ns, ox, oy, oz, nm, nb, placed.size() - nm - nb, build.size()));
                }));

        s.register(new Tool("stage_shoot", "Showcase Stage: frame and shoot every exhibit of the last stage_build - mobs "
                + "auto-centred from slightly above, blocks in three-quarter view, items face-on - plus a contact sheet and "
                + "an index.md, in captures/showcase/<namespace>/. Optional 360 turntables of the mobs.",
                new Schema().prop("orbits", "boolean", "Also render a 4 s turntable (MP4) of every mob").prop("fov", "number",
                        "Default 45").prop("clear", "boolean", "Remove the stage afterwards (like stage_clear)")
                        .prop("elevation", "number", "Camera angle above mobs in degrees (default 38; "
                        + "flat subjects like wings read best around 40-55, upright mobs around 15-25)").build(),
                a -> {
                    List<Exhibit> list;
                    synchronized (STAGE) {
                        list = new ArrayList<>(STAGE);
                    }
                    if (list.isEmpty()) {
                        throw new IllegalStateException("No stage yet: run stage_build first");
                    }
                    float fov = (float) a.num("fov", 45);
                    double elev = a.num("elevation", 38);
                    Path dir = Capture.dir("showcase").resolve(stageName);
                    try (var old = Files.list(dir)) {
                        old.filter(f -> f.getFileName().toString().matches("\\d+_.*\\.png")).forEach(f -> f.toFile().delete());
                    } catch (IOException ignored) {
                        // first run: nothing to clear
                    }
                    List<Path> shots = new ArrayList<>();
                    StringBuilder md = new StringBuilder("# " + stageName + " showcase\n\n");
                    boolean wasActive = DirectorCamera.active;
                    boolean hud = Game.call(() -> Game.mc().options.hideGui);
                    boolean shadows = Game.call(() -> Game.mc().options.entityShadows().get());
                    Game.run(() -> {
                        Game.mc().options.hideGui = true;
                        Game.mc().options.entityShadows().set(false);   // floating exhibits don't need a shadow far below
                    });
                    Stealth.begin();
                    try {
                        for (Exhibit e : list) {
                            Vec3 aim;
                            Vec3 cam;
                            switch (e.kind()) {
                                case "mob" -> {
                                    Entity ent = find(e);
                                    Vec3 base = new Vec3(e.x(), e.y() + e.h() / 2, e.z());
                                    double half = Math.max(e.w(), e.h()) / 2;
                                    if (ent != null) {
                                        AutoFrame.topView = true;
                                        AutoFrame.Result m;
                                        try {
                                            m = AutoFrame.measure(ent.getId(), ent.position().add(0, ent.getBbHeight() / 2, 0), 6);
                                        } finally {
                                            AutoFrame.topView = false;
                                        }
                                        base = m.centre();
                                        half = Math.max(0.08, m.halfSize());
                                    }
                                    double dist = Math.max(0.55, half / (0.62 * Math.tan(Math.toRadians(fov / 2))));
                                    aim = base;
                                    cam = base.add(0, dist * Math.sin(Math.toRadians(elev)), dist * Math.cos(Math.toRadians(elev)));
                                }
                                case "block" -> {
                                    aim = new Vec3(e.x() + 0.5, e.y() + 0.45, e.z() + 0.5);
                                    cam = aim.add(1.45, 1.15, 2.1);
                                }
                                default -> {
                                    aim = new Vec3(e.x() + 0.5, e.y() + 0.5, e.z() - 1 + 0.06);
                                    cam = aim.add(0, 0.05, 1.05);
                                }
                            }
                            float[] yp = DirectorCamera.lookAt(cam, aim);
                            DirectorCamera.set(cam, yp[0], yp[1], 0, fov);
                            String file = e.label().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
                            Path out = Capture.grab(dir.resolve(String.format("%02d_%s.png", shots.size() + 1, file)), 6);
                            shots.add(out);
                            md.append("## ").append(e.label()).append("\n\n`").append(e.id()).append("`\n\n![")
                                    .append(e.label()).append("](").append(out.getFileName()).append(")\n\n");
                        }
                    } finally {
                        Stealth.end();
                        Game.run(() -> {
                            Game.mc().options.hideGui = hud;
                            Game.mc().options.entityShadows().set(shadows);
                        });
                        if (!wasActive) {
                            DirectorCamera.release();
                        }
                    }
                    List<String> orbits = new ArrayList<>();
                    if (a.bool("orbits", false)) {
                        for (Exhibit e : list) {
                            if (!e.kind().equals("mob")) {
                                continue;
                            }
                            Entity ent = find(e);
                            if (ent == null) {
                                continue;
                            }
                            String file = e.label().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
                            JsonObject args = new JsonObject();
                            JsonObject target = new JsonObject();
                            target.addProperty("entity", ent.getId());
                            args.add("target", target);
                            args.addProperty("frames", 240);
                            args.addProperty("fps", 60);
                            args.addProperty("world", "step");
                            args.addProperty("fov", fov);
                            args.addProperty("name", "showcase_" + stageName + "_" + file);
                            AutoFrame.topView = true;   // the side probe would look through the next exhibit in the row
                            try {
                                com.dreadedzombie.lensbridge.Lensbridge.server().tools().get("capture_orbit").handler().apply(new Tool.Args(args));
                            } finally {
                                AutoFrame.topView = false;
                            }
                            orbits.add("showcase_" + stageName + "_" + file + ".mp4");
                        }
                    }
                    Path sheet = Capture.contactSheet(shots, shots.size(), dir.resolve("contact_sheet.png"));
                    try {
                        if (!orbits.isEmpty()) {
                            md.append("## Turntables\n\n");
                            orbits.forEach(o -> md.append("- videos/").append(o).append('\n'));
                        }
                        Files.writeString(dir.resolve("index.md"), md.toString());
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                    if (a.bool("clear", false)) {
                        com.dreadedzombie.lensbridge.Lensbridge.server().tools().get("stage_clear").handler().apply(new Tool.Args(new JsonObject()));
                    }
                    return Result.text(String.format("Shot %d exhibits%s -> %s (contact_sheet.png, index.md)", shots.size(),
                            orbits.isEmpty() ? "" : " and " + orbits.size() + " turntables", dir)).addImage(sheet, 1280);
                }));

        s.register(new Tool("stage_clear", "Remove the last Showcase Stage (blocks, mobs, item frames) and unfreeze the world.",
                new Schema().build(),
                a -> {
                    clearStage();
                    return Result.text("Stage cleared");
                }));
    }

    /** Remove the last stage: exactly its box, then release its forceloaded chunks and unfreeze. */
    private static void clearStage() {
        AABB b;
        int[] f;
        synchronized (STAGE) {
            b = stageBox;
            f = stageForce;
        }
        if (b == null) {
            throw new IllegalStateException("No stage to clear");
        }
        int x0 = (int) b.minX, y0 = (int) b.minY, z0 = (int) b.minZ, x1 = (int) b.maxX, y1 = (int) b.maxY, z1 = (int) b.maxZ;
        List<String> c = new ArrayList<>();
        c.add("tick unfreeze");
        c.add(killIn(x0, y0, z0, x1, y1, z1));
        for (int y = y0; y <= y1; y++) {
            c.add(String.format("fill %d %d %d %d %d %d air", x0, y, z0, x1, y, z1));
        }
        if (f != null) {
            c.add(String.format("forceload remove %d %d %d %d", f[0], f[1], f[2], f[3]));
        }
        batch(c);
        synchronized (STAGE) {
            STAGE.clear();
            stageBox = null;
            stageForce = null;
        }
    }

    /** Kill every non-player entity inside the block box (inclusive). */
    private static String killIn(int x0, int y0, int z0, int x1, int y1, int z1) {
        return String.format("kill @e[type=!player,x=%d,y=%d,z=%d,dx=%d,dy=%d,dz=%d]", x0, y0, z0, x1 - x0, y1 - y0, z1 - z0);
    }

    /** Why the box can't be used, or null if it is loaded, all air and holds no entities. Render thread. */
    private static String occupied(int x0, int y0, int z0, int x1, int y1, int z1) {
        var lvl = Game.mc().level;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                if (!lvl.hasChunk(cx, cz)) {
                    return "part of it isn't loaded on this client";
                }
            }
        }
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        int solid = 0;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (!lvl.getBlockState(pos.set(x, y, z)).isAir()) {
                        solid++;
                    }
                }
            }
        }
        if (solid > 0) {
            return solid + " non-air blocks are in the way";
        }
        long ents = lvl.getEntities((Entity) null, new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1),
                e -> !(e instanceof net.minecraft.world.entity.player.Player) && !com.dreadedzombie.lensbridge.cinema.Props.isProp(e)).size();
        return ents > 0 ? ents + " entities are in the way" : null;
    }

    /** The entity standing in for a mob exhibit (nearest of its type to where it was summoned). Off-thread. */
    private static Entity find(Exhibit e) {
        return Game.call(() -> {
            Entity best = null;
            double bd = 4;
            for (Entity ent : Game.mc().level.entitiesForRendering()) {
                if (!BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType()).toString().equals(e.id())) {
                    continue;
                }
                double d = ent.position().distanceTo(new Vec3(e.x(), e.y(), e.z()));
                if (d < bd) {
                    bd = d;
                    best = ent;
                }
            }
            return best;
        });
    }

    private static void saveStage() {
        JsonArray arr = new JsonArray();
        synchronized (STAGE) {
            STAGE.forEach(e -> arr.add(e.json()));
        }
        try {
            Path dir = Capture.dir("showcase").resolve(stageName);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("stage.json"), new GsonBuilder().setPrettyPrinting().create().toJson(arr));
        } catch (IOException ignored) {
            // the stage still works without its saved layout
        }
    }
}
