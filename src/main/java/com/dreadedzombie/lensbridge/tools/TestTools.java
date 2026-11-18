package com.dreadedzombie.lensbridge.tools;

import com.dreadedzombie.lensbridge.Game;
import com.dreadedzombie.lensbridge.TestSkin;
import com.dreadedzombie.lensbridge.mcp.BridgeServer;
import com.dreadedzombie.lensbridge.mcp.Tool;
import com.dreadedzombie.lensbridge.mcp.Tool.Result;
import com.dreadedzombie.lensbridge.mcp.Tool.Schema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * Testing without leaving a mess (the owner's rule: test in a fresh throwaway world, never in a world someone uses).
 *
 * <ul>
 * <li>{@code test_world}: create a fresh creative world with cheats, named {@code Agent test <date time>}, and go into
 * it, in one call, with no menu clicking; leave it; delete it. Only worlds whose name starts with "Agent test" can be
 * deleted, so a player's own worlds can never be touched.</li>
 * <li>{@code test_skin}: draw the player with a test skin whose outer layers (hat, jacket, sleeves, trouser layers)
 * are fully opaque and brightly chequered, to spot worn models that clip into the skin's outer layer.</li>
 * </ul>
 */
public final class TestTools {
    public static final String PREFIX = "Agent test ";

    private TestTools() {
    }

    public static void registerAll(BridgeServer s) {
        s.register(new Tool("test_world", "A throwaway world for testing, so nobody's real world is touched. "
                + "action=create (default): leave the current world (saving it) and create and enter a fresh creative "
                + "world with cheats on, named \"Agent test <date time>\" (flat by default: nothing in the way; "
                + "flat:false for normal terrain). action=leave: back to the title screen. action=delete: delete "
                + "throwaway worlds (name:\"all\" or one name); only worlds named \"Agent test ...\" can be deleted, and "
                + "not the one that is open. action=list: the throwaway worlds there are. Delete it when done.",
                new Schema().prop("action", "string", "create (default) / leave / delete / list")
                        .prop("flat", "boolean", "create: a superflat world (default true)")
                        .prop("name", "string", "delete: a world's name, or \"all\" (default) for every Agent test world")
                        .build(),
                a -> {
                    String action = a.str("action", "create");
                    return switch (action) {
                        case "create" -> Result.text(create(a.bool("flat", true)));
                        case "leave" -> Result.text(leave() ? "Back at the title screen (the world was saved)." : "No world was open.");
                        case "delete" -> Result.text(delete(a.str("name", "all")));
                        case "list" -> Result.text(list().isEmpty() ? "No Agent test worlds." : String.join("\n", list()));
                        default -> throw new IllegalArgumentException("action is create, leave, delete or list");
                    };
                }));

        s.register(new Tool("test_skin", "Draw the player with a test skin for checking worn models (hats, hoods, "
                + "backpacks): the skin's outer layers (hat, jacket, sleeves, trouser layers) are fully opaque, in a bright "
                + "chequer over a dark base, so anything that clips into or flickers against them shows up. Only on "
                + "this computer: nobody else sees it. on=false puts your own skin back (it also goes back when the game "
                + "restarts).",
                new Schema().prop("on", "boolean", "true (default) to wear the test skin, false for your own").build(),
                a -> {
                    boolean on = a.bool("on", true);
                    Game.call(() -> {
                        TestSkin.set(on);
                        return null;
                    });
                    return Result.text(on ? "Wearing the test skin (outer layers opaque). test_skin on=false puts yours back."
                            : "Your own skin is back.");
                }));
    }

    /** Leave any world, then create and enter a fresh throwaway one. HTTP thread. */
    static String create(boolean flat) {
        leave();
        String name = PREFIX + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"));
        Game.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            // 26.x: difficulty settings in their own record, game rules no longer part of LevelSettings
            LevelSettings settings = new LevelSettings(name, GameType.CREATIVE,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            WorldOptions options = new WorldOptions(WorldOptions.randomSeed(), !flat, false);
            mc.createWorldOpenFlows().createFreshLevel(name, settings, options,
                    flat ? WorldPresets::createTestWorldDimensions : WorldPresets::createNormalWorldDimensions, new TitleScreen());
            return null;
        });
        for (int i = 0; i < 240; i++) {   // up to a minute for the world to generate and the player to arrive
            if (Boolean.TRUE.equals(Game.call(() -> Minecraft.getInstance().player != null && Minecraft.getInstance().gui.screen() == null))) {
                Game.sleep(1500);   // terrain around the player
                return "Created and entered \"" + name + "\" (creative, cheats on, " + (flat ? "flat" : "normal terrain")
                        + "). Delete it when done: test_world action=delete.";
            }
            Game.sleep(250);
        }
        throw new IllegalStateException("The world \"" + name + "\" didn't open within a minute (see the game's log).");
    }

    /** Leave the current world, if any, saving it, and wait for the title screen. HTTP thread. */
    static boolean leave() {
        boolean inWorld = Boolean.TRUE.equals(Game.call(() -> Minecraft.getInstance().level != null));
        if (!inWorld) {
            return false;
        }
        Game.call(() -> {
            Minecraft.getInstance().disconnectFromWorld(Component.translatable("menu.savingLevel"));
            return null;
        });
        for (int i = 0; i < 240; i++) {
            if (Boolean.TRUE.equals(Game.call(() -> Minecraft.getInstance().level == null && Minecraft.getInstance().getSingleplayerServer() == null))) {
                return true;
            }
            Game.sleep(250);
        }
        throw new IllegalStateException("The world didn't close within a minute.");
    }

    static List<String> list() {
        Path saves = Game.call(() -> Minecraft.getInstance().getLevelSource().getBaseDir());
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(saves)) {
            s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(n -> n.startsWith(PREFIX)).sorted()
                    .forEach(out::add);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the saves folder: " + e.getMessage());
        }
        return out;
    }

    /** Delete throwaway worlds. Only "Agent test ..." worlds, never the open one. HTTP thread. */
    static String delete(String which) {
        String open = Game.call(() -> {
            var srv = Minecraft.getInstance().getSingleplayerServer();
            return srv == null ? null : srv.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .toAbsolutePath().normalize().getFileName().toString();
        });
        List<String> targets = new ArrayList<>();
        if (which.equalsIgnoreCase("all")) {
            targets.addAll(list());
        } else {
            if (!which.startsWith(PREFIX)) {
                throw new IllegalArgumentException("Only throwaway worlds (named \"" + PREFIX + "...\") can be deleted.");
            }
            targets.add(which);
        }
        LevelStorageSource source = Game.call(() -> Minecraft.getInstance().getLevelSource());
        List<String> deleted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String name : targets) {
            if (name.equals(open)) {
                skipped.add(name + " (open: test_world action=leave first)");
                continue;
            }
            if (!source.levelExists(name)) {
                skipped.add(name + " (no such world)");
                continue;
            }
            try (LevelStorageSource.LevelStorageAccess access = source.createAccess(name)) {
                access.deleteLevel();
                deleted.add(name);
            } catch (IOException | RuntimeException e) {
                skipped.add(name + " (" + e.getMessage() + ")");
            }
        }
        return (deleted.isEmpty() ? "Deleted nothing." : "Deleted: " + String.join(", ", deleted) + ".")
                + (skipped.isEmpty() ? "" : " Not deleted: " + String.join("; ", skipped) + ".");
    }
}
