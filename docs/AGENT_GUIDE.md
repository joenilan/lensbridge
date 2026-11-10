# Lensbridge agent guide

You are driving a **live Minecraft client** that a person may be playing or watching. Lensbridge gives you eyes (game-only screenshots), cameras (director camera, tripods, drone), staging (time, weather, freeze) and capture (stills, orbits, rails, drone shots, multicam, real-time video). This guide is how to use it well. The full tool list with arguments: call `guide {"name":"tools"}` (also in docs/TOOLS.md, and at http://127.0.0.1:25599/docs/tools).

---

## 0. Connect

- MCP Streamable HTTP: `http://127.0.0.1:25599/mcp` with header `Authorization: Bearer <token>`. The token is in `<game dir>/config/lensbridge.json`.
- REST mirror for scripts: `POST /api/<tool>` with the JSON arguments as the body. `GET /api/` lists tools and schemas. `GET /health` needs no token.
- Results are MCP content blocks: text, plus a JPEG preview image for visual tools. Full-size files are saved on disk and their paths are in the text.
- Always start with **`status`**: is a world loaded, is a screen (menu) open, where is the player, what's the camera state, are shaders on.

---

## 1. Ground rules (non-negotiable)

1. **Never use the player's own view as your camera.** Don't `look`, `hold_keys` or walk the player to frame a shot. Use the **director camera** (`camera_look_at`), **tripods**, the **drone** or **camera moves**. They're detached from the player and, with stealth capture, invisible on the player's screen.
   - You *may* move the player near a location (e.g. `command tp`) only so its chunks load: cameras only see what's loaded around the player. Ask first if the person is playing.
   - Use the player as a *subject* (e.g. drone follow) only when asked.
2. **Clean up after yourself.** Remove cameras you placed for a job (`tripod_remove {"group":"tmp"}` removes a whole name group), `stage_clear` stages, `props {"enabled":false}`.
3. **Don't leave the player stuck.** If you turn on `camera_preview`, `tripod_preview` or `tripod_edit`, finish with `exit_camera`. Always `camera_release` when your session ends.
4. **Never build a scene block by block** with hundreds of `command` calls; it's slow and the person hates it. Use one `fill`, a **structure**, or a **datapack function** run by a single `/function` call (see §4).
5. **Freeze before staging** (`scene {"freeze":true}`) so nothing hatches, grows, wanders or despawns mid-shoot. Unfreeze when done.
6. **Verify with your eyes and say what you actually saw.** Open the saved images; don't assume a shot worked. Report problems honestly, including your own staging mistakes.
7. **Show results to the person.** Put the image and video file paths in your reply (the host can embed them). Videos are the point when they ask for video.
8. **Respect server rules.** On a server, free cameras and tools that act as the player (`look`, `hold_keys`, `press`, `hotbar`, `chat`, `command`, `screen_click`...) may be refused: the error says why. Don't try to work around it; tell the person (an operator or the server owner can allow it; see the user guide, "Playing on servers"). Singleplayer is unrestricted.

---

## 2. Core workflow

```text
status                                   # world loaded? menu open? (close_screen if a menu is in the way)
scene  {"look":"qa"}                     # honest neutral light: shaders off, morning sun, clear, day cycle stopped
scene  {"freeze":true}                   # hold the world still
...stage the subject (one function / structure)...
tripod_place / tripod_ring               # cameras around the subject (or camera_look_at for a one-off)
tripod_thumbs {"group":"hero"}           # cheap stealth previews to check framing
tripod_shots  {"names":[...]}            # full-size stills + a contact sheet
... look at the files, fix framing (tripod_update / tripod_nudge), re-shoot ...
scene  {"freeze":false} ; camera_release
```

---

## 3. Lighting and looks

- `scene {"look": ...}` presets: `qa` (shaders **off**, morning, clear: use it for checking how things really look), `noon`, `golden`, `sunset`, `blue_hour`, `night`, `dawn`, `overcast`. `{"shaders": true|false}` toggles Iris if installed.
- **Washed-out pictures** come from white or very bright backdrops, noon sun and shader fog. Measure them: mean brightness above ~200 with low contrast is too bright.
  - For QA, use mid/dark gray backdrops (`gray_concrete`) and `look: qa`.
  - For promo, use natural sets at `golden` or `night`.
- Check important visuals **twice**: shaders off (what most players see) and shaders on (what the person plays with).

---

## 4. Staging a scene (fast)

- Write a **datapack** into the world's `datapacks/` folder, then `reload` and run `function <ns>:build`. One command builds hundreds of blocks and entities instantly.
  - Data pack format for 26.3: `pack_format` 121 (`min_format` 121).
  - Functions can't use `/tick` (permission level). Send `tick freeze` as its own `command`.
- Display mobs: summon with `NoAI:1b,PersistenceRequired:1b,Invulnerable:1b,Silent:1b` and an explicit `Rotation` so they face the camera.
- **Killing then freezing leaves corpses.** Mobs killed right before `tick freeze` stay mid-death forever: red tint, tipped over, overlapping the new ones. Run your clear step, let ~3 s of normal ticks pass, *then* build and freeze.
- **Backdrops:** put a wall *behind* each row relative to its camera. Check that no wall sits between a camera and its subject; we once hid a whole item wall behind a row's backdrop.
- Blocks with timers (eggs, cocoons, crops) advance while you stage. Freeze first; for exact mid-stage visuals, set their timer fields via scoreboard math in the function.

---

## 4b. Showcase Stages (a whole mod in two calls)

For "show me everything in my mod" or listing images:

```text
scene {"look":"qa"}
stage_build {"namespace":"mymod"}                  # mobs, blocks and items from the registries, built in the sky, world frozen
stage_shoot {}                                     # a still per exhibit + contact_sheet.png + index.md in captures/showcase/mymod/
stage_shoot {"orbits":true}                        # also a 360 turntable per mob
stage_clear {}                                     # remove it, unfreeze (or stage_shoot {"clear":true})
```

- Variants that registries can't know about go in `extra`: `{kind:"mob", id:"mymod:dragon", nbt:"Color:2", label:"Blue dragon"}` (nbt without braces). Leave the generic entry out with `exclude`.
- `elevation` (default 38°): flat subjects (wings, rugs) read best at 40-55°, upright mobs at 15-25°.
- The stage goes just under the build limit above the player. It **refuses** to build into blocks, entities or unloaded chunks; pick other `x`/`y`/`z` rather than passing `force:true` (which clears whatever is there). A new `stage_build` replaces the previous stage; `stage_clear` also releases the chunks it forceloaded.
- Check the contact sheet, then open single shots for anything that looks off.

## 5. Framing rules

- **Flat subjects** (wings, banners, items lying down, rugs): shoot from **above at ~45–60°**. Eye-level shots show flat planes edge-on, as thin lines.
- **Distance scales with size.** For a subject of width `w` blocks at FOV 50, start at distance ≈ `0.6 + 1.1·w`, and add more for things with tails, antennae or long parts. Check the thumbnail and back off until nothing is cropped.
- **Front-facing:** rotate mobs so they face the camera (`Rotation:[0f,0f]` faces south/+Z; place the camera south of them).
- Use `tripod_ring` for N even angles around a subject, and `tripod_nudge` (forward/right/up, yaw/pitch, fov) to fine-tune.
- Thumbnails (`tripod_thumbs`) are cheap. Iterate on framing with them, then `tripod_shots` once.
- The person can fine-tune any camera themselves: Camera Manager (`/ui`, Alt+C) → **✋ Take control**. Offer it when exact taste matters.

---

## 6. Video: speed is always true (unless asked)

- `record`: **real time** from whatever view is live (director camera, drone, tripod). Late frames are repeated, never sped up. Set `timelapse_interval` **only** when a timelapse is wanted.
- `capture_orbit`, `capture_path`, `capture_program` and `multicam` render **frame by frame**, so camera motion is perfectly smooth. `world` controls the world during the render:
  - `sync` (default): the world moves at true speed.
  - `step`: frozen, then stepped 20/fps ticks per frame, for perfectly smooth moving subjects.
  - `frozen`: everything held still.
  - `free`: a deliberate timelapse look.
- Live moves (`camera_orbit`, `camera_rail`, `camera_follow`, `drone_fly`...) keep running in the background. Render them with `capture_program`, or film them live with `record`.
- **Drone** for organic aerial footage: `follow` (auto aerial follow-me), `dronie`, `reveal`, `topdown`, `orbit`, `mission` (waypoints), `fpv`. **Rails and orbits** for precise, repeatable moves.
- **Multicam** = the same action from every enabled tripod, in sync, one MP4 per angle.
- **Behind-the-scenes shots:** `props {"enabled":true,"crew":["drone"]}` shows camera props (tripods as cameras on legs, the drone with spinning rotors, the current rail as markers). Only the cameras listed in `crew` see them (`director`, `drone` or tripod names), a camera never sees its own prop, and the player sees them only with `player_view`. So multicam and tripod shots stay clean while a crew drone films the rig. Turn them off afterwards.
- **Turntables of a mob:** `capture_orbit {"target":{"entity":id}, "fps":60, "frames":240, "world":"step"}`.
  - `auto_center` (on by default with an entity target) finds where the *model* really is, which is often well above or beside the entity's position, and picks a radius so it fills ~70% of the frame. Pass `radius`/`height` to override.
  - `step` at 60 fps is perfectly smooth: the world advances exactly 20/60 tick per frame with interpolated animation.
  - Swap subjects with the world **running** for a few seconds (kill + summon), then freeze. A mob killed while frozen stays as a frozen corpse and may be picked as the next target.

---

## 7. Verifying visuals (QA)

- Shoot every exhibit up close, open each image, and list **looks right / broken** with evidence.
- Contact sheets are small. Open the full-size file before calling something fine.
- Known traps:
  - **Stale frames:** fixed in 0.3.1+ (warm-up after idle, `glFinish` in stealth batches). Since 0.3.7 a capture also waits until a frame was really drawn from your camera, so a shot can't come back as the player's own view. If consecutive shots look identical when they shouldn't, compare image hashes and re-shoot.
  - **Shader switches** rebuild all terrain; `scene` now waits for that before returning. Shots taken right after a relaunch or teleport may still need a second try.
  - **Wrong colors / red tint** on mobs means dying entities (see §4).
  - **Empty shots** of blocks that "should be there" mean they hatched, despawned or were never placed. Check with `command execute if block ...`.
  - **Cropped big subjects** means the distance formula is too small for that size.
- Measure brightness and contrast when someone says "washed out". Don't argue from vibes.

---

## 8. Working with the person

- They see your cameras and thumbnails in the **Camera Manager**. Name tripods with group prefixes (`hero_`, `wm_`, `jar_`) so the panel groups them.
- If they're playing, keep `camera_preview` off (stealth) so their screen never jumps. If they want to watch, turn it on, and turn it off again afterwards.
- Hotkeys they have: Alt+C manager, Alt+D drone, Alt+T tripod, Alt+N cycle, Alt+R record, Esc exits any camera mode.
- Ask before long or disruptive operations (resizing their window, teleporting them, changing shaders while they play).

---

## 9. File locations

| What | Where |
|---|---|
| Screenshots | `<game dir>/lensbridge/screenshots/` |
| Videos + contact sheets | `<game dir>/lensbridge/videos/` |
| Thumbnails | `<game dir>/lensbridge/thumbs/` |
| Tripods | `<game dir>/config/lensbridge/tripods.json` |
| Saved paths | `<game dir>/config/lensbridge/paths/` |
| Config + token | `<game dir>/config/lensbridge.json` |
