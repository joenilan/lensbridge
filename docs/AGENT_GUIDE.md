# Lensbridge agent guide

You are driving a **live Minecraft client** that a person may be playing or watching. Lensbridge gives you eyes (game-only screenshots), cameras (director camera, tripods, drone), staging (time, weather, freeze) and capture (stills, orbits, rails, drone shots, multicam, real-time video). This guide is how to use it well. The full tool list with arguments: call `guide {"name":"tools"}` (also in docs/TOOLS.md, and at http://127.0.0.1:25599/docs/tools).

---

## 0. Connect

- MCP Streamable HTTP: `http://127.0.0.1:25599/mcp` with header `Authorization: Bearer <token>`. The token is in `<game dir>/config/lensbridge.json`.
- REST mirror for scripts: `POST /api/<tool>` with the JSON arguments as the body. `GET /api/` lists tools and schemas. `GET /health` needs no token.
- Results are MCP content blocks: text, plus a JPEG preview image for visual tools. Full-size files are saved on disk and their paths are in the text.
- If **Agentlink** is installed (`GET /health` says `"name":"agentlink"`), the same tools come through it: same names, same
  address, but the token is in `config/agentlink.json`. `list_capabilities` then shows every other mod's tools too
  (ids like `mothlight.nearby`, MCP names like `mothlight_nearby`). Lensbridge also publishes events there
  (`poll_events`, or `GET /events`): `lensbridge.video_saved` (any MP4 finished), `lensbridge.recording_started` /
  `lensbridge.recording_finished` (the player's Alt+R recorder) and `lensbridge.tripod_placed` (the player pressed Alt+T).
- Always start with **`status`**: is a world loaded, is a screen (menu) open, where is the player, what's the camera state, are shaders on.

---

## 1. Ground rules (non-negotiable)

1. **Never use the player's own view as your camera.** Don't `look`, `hold_keys` or walk the player to frame a shot. Use the **director camera** (`camera_look_at`), **tripods**, the **drone** or **camera moves**. They're detached from the player and, with stealth capture, invisible on the player's screen.
   - You *may* move the player near a location (e.g. `command tp`) only so its chunks load: cameras only see what's loaded around the player. Ask first if the person is playing.
   - Use the player as a *subject* (e.g. drone follow) only when asked.
2. **Clean up after yourself.** Remove cameras you placed for a job (`tripod_remove {"group":"tmp"}` removes a whole name group), `stage_clear` stages, `props {"enabled":false}`.
3. **Don't leave the player stuck.** Finish every session with `exit_camera` (it returns the player to their own view and releases the director camera, hidden or shown) and then `restore` (it undoes what `scene` and `window_size` left changed: world freeze, the `advance_time` gamerule, shaders, window size / fullscreen). Time of day and weather are yours to put back. The player's own way out is Alt+Backspace (stops your capture) and `/lensbridge restore`.
   - One capture runs at a time. A second one waits up to 30 s, then fails with "Another capture is running": wait for the first, don't retry in a loop.
   - **Never send `agentlink confirm` or `agentlink cancel`** (the `command` and `chat` tools refuse them, and `screen_click` doesn't click in the chat screen). A person confirms a ban or rollback in game.
4. **Never build a scene block by block** with hundreds of `command` calls; it's slow and the person hates it. Use one `fill`, a **structure**, or a **datapack function** run by a single `/function` call (see §4).
5. **Freeze before staging** (`scene {"freeze":true}`) so nothing hatches, grows, wanders or despawns mid-shoot. Unfreeze when done (`restore` does it).
6. **Verify with your eyes and say what you actually saw.** Open the saved images; don't assume a shot worked. Report problems honestly, including your own staging mistakes.
7. **Show results to the person.** Put the image and video file paths in your reply (the host can embed them). Videos are the point when they ask for video.
8. **Respect server rules.** On a server, free cameras and tools that act as the player (`look`, `hold_keys`, `press`, `hotbar`, `chat`, `command`, `screen_click`...) may be refused: the error says why. Don't try to work around it; tell the person (an operator or the server owner can allow it; see the user guide, "Playing on servers"). Singleplayer is unrestricted.

---

## 1b. The server's own tools (staff)

On a server that runs Agentlink and Lensbridge and gives the player staff access, `server_tools` lists that
server's tools (status and lag, players and inventories, every mod's items and recipes) and the player's access;
`server_call {"tool": "...", "args": {...}}` runs one; `server_events` reads its live chat, joins, deaths and
commands. They run on the server, as the player, and are logged there under their name: only look into players
or inventories when the person asked for it. Anywhere else these tools say why they're unavailable.

## 1c. In-game requests (/agent)

When the person wants to ask from inside the game, listen: call `agent_inbox` (it waits up to `wait_seconds`) in a
loop, and answer each request with `agent_say`, a line or two in their chat. Each call returns `next_ack`: pass it as
`ack` on your next call. A request stays in the inbox until acknowledged and comes back otherwise (marked
`"delivery": 2`), so check the id before answering one twice. Each request includes the player's
position, dimension, facing and what they're looking at, so "what's this?" and "go look over there" are answerable.
To show them something, use a camera (drone_fly, camera_look_at, tripod_preview), never their own view; Esc or
Backspace returns them. If they're staff on a server with tools, use server_call (e.g. agentlink_lag,
agentlink_player) for server questions. Moderation (message, mute, kick, ban...) only when the person asks for it,
with the reason they gave; say what you did. A ban waits for them to click [Confirm] in their chat: tell them so. To show
them a player or a place, server_call agentlink_watch (spectator; agentlink_watch_stop or /agentlink back returns
them); to keep evidence, `record` while they watch, then agentlink_note with the file name. To let them keep
playing while watching someone NEAR them, `inset {"target": "<player>"}` (picture-in-picture; mode chase / front /
overhead), `inset_snapshot` saves its picture as evidence, `inset {"target": "off"}` closes it. `mode: "eyes"` is exactly what they see; `full: true`
makes the camera the person's whole screen (Esc returns them to the corner picture). To watch someone far away, first
`server_call` `agentlink.watch` with `view: "near"` (the staff member goes there, invisible), then `inset`. The inset camera
moves in front of walls, roofs and leaves on its own. `inset_snapshot` waits for a fresh picture. Captures work with
the game window in the background too, including with Dynamic FPS installed (Lensbridge keeps frames coming while it
works), and with Sodium and Iris.

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
exit_camera ; restore                    # finish: player back on their own view, camera released; freeze,
                                         # advance_time, shaders and window put back as they were
```

Tripods belong to the world and dimension they were placed in: `tripod_list`, `tripod_all`, `tripod_thumbs`,
`tripod_shots` and `multicam` use the current one by default (`all: true` on the two lists shows every world;
`tripod_remove` with `group` / `all` takes `everywhere: true`). Names are global, so a name used in another world
is taken. `tripod_set_from_view` only updates an existing tripod. Output `name` arguments are reduced to letters,
digits, `_`, `.` and `-`.

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
  - Data pack format for 1.21.11: `pack_format` 94 (`min_format` 94).
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
  - `sync`, `frozen` and `step` send `/tick` commands, so on a server they need the same permission as `command` (the server's AI-control rule). `free` sends none.
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
- Hotkeys they have: Alt+C manager, Alt+D drone, Alt+T tripod, Alt+X remove a tripod, Alt+N cycle, Alt+R record, Esc exits any camera mode, Alt+Backspace stops whatever capture is running (yours included: the tool call fails with "Capture stopped by the player"; don't restart it without asking).
- Their Alt+T / Alt+D / "Save my view here" use what is on their screen; your hidden camera is never mistaken for their view. Alt+R releases a hidden camera first and records what they see.
- `hold_keys` holds at most 60 s (1200 ticks) and lets go when any screen opens.
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
