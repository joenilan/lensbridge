package com.dreadedzombie.lensbridge;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Holds game keys down for a number of client ticks (movement, jump, sneak, sprint, use, attack). */
public final class InputDriver {
    private static final Map<String, Integer> HELD = new HashMap<>();

    private InputDriver() {
    }

    public static KeyMapping key(String name) {
        Options o = Game.mc().options;
        return switch (name) {
            case "forward" -> o.keyUp;
            case "back" -> o.keyDown;
            case "left" -> o.keyLeft;
            case "right" -> o.keyRight;
            case "jump" -> o.keyJump;
            case "sneak" -> o.keyShift;
            case "sprint" -> o.keySprint;
            case "use" -> o.keyUse;
            case "attack" -> o.keyAttack;
            case "drop" -> o.keyDrop;
            case "inventory" -> o.keyInventory;
            case "swap_offhand" -> o.keySwapOffhand;
            case "pick_block" -> o.keyPickItem;
            default -> {
                // any other registered key mapping by its id, e.g. "key.mothlight.glide_drop" (mod hotkeys)
                for (KeyMapping k : o.keyMappings) {
                    if (k.getName().equals(name)) {
                        yield k;
                    }
                }
                throw new IllegalArgumentException("Unknown key '" + name
                        + "' (forward, back, left, right, jump, sneak, sprint, use, attack, drop, inventory, swap_offhand,"
                        + " pick_block, or any key mapping id such as key.lensbridge.drone)");
            }
        };
    }

    /** Hold {@code name} for {@code ticks} client ticks (0 releases). Call on the client thread. */
    public static void hold(String name, int ticks) {
        KeyMapping k = key(name);
        if (ticks <= 0) {
            HELD.remove(name);
            k.setDown(false);
        } else {
            HELD.put(name, ticks);
            k.setDown(true);
        }
    }

    /** One press: counts as a click for use/attack/drop etc. */
    public static void tap(String name) {
        KeyMapping k = key(name);
        if (name.startsWith("key.")) {
            // a named mapping (mod hotkey): count the click on that mapping itself, so modifier bindings work too
            com.dreadedzombie.lensbridge.mixin.KeyMappingAccessor a = (com.dreadedzombie.lensbridge.mixin.KeyMappingAccessor) k;
            a.lensbridge$setClickCount(a.lensbridge$getClickCount() + 1);
        } else {
            KeyMapping.click(k.getKey());
        }
    }

    public static void releaseAll() {
        for (String name : HELD.keySet()) {
            key(name).setDown(false);
        }
        HELD.clear();
    }

    public static boolean busy() {
        return !HELD.isEmpty();
    }

    static void onTick(ClientTickEvent.Pre event) {
        if (HELD.isEmpty()) {
            return;
        }
        HELD.replaceAll((name, ticks) -> ticks - 1);
        HELD.entrySet().removeIf(e -> {
            if (e.getValue() <= 0) {
                key(e.getKey()).setDown(false);
                return true;
            }
            key(e.getKey()).setDown(true);   // keep it held even if the real keyboard state says otherwise
            return false;
        });
    }
}
