# Lensbridge roadmap

Goal: an **in-depth, genuinely useful** bridge for (1) AI-driven mod testing and (2) promo stills, shots and video, ready for a CurseForge/Modrinth release.

## 0.1: done (2026-11-09)

- [x] MCP Streamable HTTP server (JSON-RPC: initialize, tools/list, tools/call, ping) + REST mirror, localhost and token only
- [x] Game-only screenshots with inline previews; frame sequences; ffmpeg MP4/GIF; contact sheets
- [x] Input: hold keys for N ticks, tap, look / look-at, hotbar, chat, commands, close screens
- [x] Inspect: crosshair, nearby entities, hands and hotbar, chat/system log
- [x] Director camera (Camera mixin): free position, yaw/pitch/roll, FOV, player visible
- [x] Promo: `capture_orbit`, `capture_path` (Catmull-Rom, easing, look-at targets), `record` (real time)
- [x] Staging: `scene` (time, weather, freeze/step, lock time), `hud`, `window_size`
- [x] Keeps running unfocused (no pause menu when driven in the background)
- [x] Tested end to end against a live 1.21.11 client: handshake, status, screenshots, director camera, 60-frame orbit → MP4

## 0.2: cinema (done 2026-11-09)

- [x] Targets: entity id / nearest type / point / player, aim offset, smooth partial-tick tracking of moving mobs
- [x] Live camera programs evaluated every frame: **lock**, **orbit** (follows moving targets), **pan** (360°), **rail** (spline; once/loop/pingpong; optional lock), **follow** (chase cam), **dolly zoom**
- [x] `capture_program`: frame-exact rendering of any move at a fixed fps, with optional `tick_step` per frame for perfectly smooth moving subjects in a frozen world
- [x] Named paths: `path_mark` (build interactively from the current view), save / get / list / delete, `path_show` particle preview
- [x] `find_entities`
- [x] Tested live: orbit around a moth, chase cam on a flying wild moth, rail preview, 90-frame rail render → MP4

## 0.3.2: stealth capture, looks, smooth turntables (2026-11-09)

- [x] Step renders interpolate the partial tick per frame (smooth at 60 fps, no 20 Hz judder); multicam too
- [x] `capture_orbit` target + **auto-centre**: shown/hidden probe diff finds the model's real centre and size (largest blob, stable frames, immune to twinkling stars/particles)
- [x] Renders leave the world frozen if it was frozen before

- [x] Stealth capture: director-camera frames render but aren't presented, so the player's screen holds its last frame (no flashing); AI cameras are hidden unless `camera_preview` is on; player hotkeys preview live
- [x] `scene look` presets (qa, noon, golden, sunset, blue_hour, night, dawn, overcast) + Iris shaders on/off (reflection, optional)
- [x] Capture warm-up after idle (fixes stale first frames)
- [x] Stealth batches: `glFinish` on skipped presents (fixes repeated/stale frames in long stealth batches)
- [x] **Camera Manager**: browser panel (M / `/ui`) with thumbnails, groups, enable/disable, field edits, nudges with auto re-render, on-screen preview, save-current-view, delete, shoot enabled; plus the matching AI tools; `/file` serves captures (token, no path escape)
- [x] **Take control** of a camera from the manager (`tripod_edit` / `tripod_edit_finish`): precise drone-style flying, scroll = FOV, Enter saves / Backspace cancels, auto thumbnail refresh; AI `drone_control` sticks take priority over the keyboard while active

## 0.3: drone + tripods (done 2026-11-09)

- [x] Physics drone (`drone_fly`): inertia, accel/speed limits, yaw-rate limit, banking, stabilised gimbal, hover wobble, ground clearance; deterministic under capture_program
- [x] Autonomous modes: hover, mission (waypoints + holds + framing target), orbit, follow (aerial follow-me), dronie, reveal, topdown, fpv; pilot via sticks (`drone_control`)
- [x] Tripods: place / ring / track a target / view / list / remove; `tripod_shots` multi-angle stills; **`multicam`** synced per-angle video via tick stepping
- [x] Player hotkeys: K drone pilot (WASD/Space/Shift/Sprint + mouse), Y place tripod, U cycle tripods, J record; HUD telemetry
- [x] Unfocused windows aren't FPS-throttled (inactivity limit = minimized only)
- [x] Tested live: aerial follow of a sprinting player + Lumens, dronie, top-down, 4-tripod stills, 4-angle 3 s multicam
- [x] 0.3.1: true-speed video. Frames stream to ffmpeg (no PNG per frame); `record` is real time (late frames repeated); renders keep the world in sync (tick-rate scaling + real-time pacing) with `world` = sync/step/frozen/free; timelapse only via `timelapse_interval`
- [x] Fix: the first frames of `capture_program` showed the pre-reset view (pre-roll at t=0), and orbits had occasional repeated frames (2-frame settle)
- [x] Camera props for behind-the-scenes shots (`props`): tripod/drone/rail models, client-only, seen only by crew cameras (never their own) and optionally the player
- [x] Camera Manager switch for props + Alt+P (0.3.3)
- [x] Docs bundled in the jar: `guide` tool for agents, /docs pages + Help link for players (0.3.4)
- [x] Install guide per launcher, secret-key explainer, Copy connect command (0.3.5)
- [x] Reliable first shots (0.3.7): captures wait for a frame actually drawn from the AI camera, and for terrain to finish rebuilding after a shader switch (Sodium-aware)
- [x] Camera props named after their camera; Jade shows the name with Lensbridge as the mod (0.3.8)

## 0.4: Showcase Stages for modmakers (owner's headline idea, 2026-11-09)

Turn the Mothlight QA stage into a reusable template so any modmaker can make a showcase of their own items, blocks and mobs at any size.

- [x] `stage_build {namespace, include, exclude, extra, backdrop, per_row}` (0.4.0): reads the mod's registries and builds the whole stage in one fast command batch:
  - neutral backdrop walls; world frozen first
  - mobs held still (NoAI) on pedestals; blocks on plinths; items in frames
  - built server-side, never block by block: directly on the integrated server in singleplayer, or via an optional server half of Lensbridge on dedicated servers
- [x] Each exhibit framed by measuring its model (auto-centre with a top-down second probe, so neighbours never block it)
- [x] `stage_shoot {orbits, elevation, fov}`: one still per exhibit, optional 360° orbits per mob, a contact sheet and index.md; `stage_clear`
- [ ] Any output size (needs offscreen hi-res rendering, see 0.6)
- [ ] Template file (`.lensbridge-stage.json`) to save, tweak and re-run a stage after asset changes
- [x] First real use: Mothlight, 53 exhibits (36 mobs incl. all 32 wild species as `extra` variants) in ~1.5 min

## 0.5: testing power

- [ ] **Event stream:** subscribe to chat, deaths, advancements, screen opens, entity spawns near the player (SSE on `/mcp` GET)
- [ ] `look_at_entity` / `follow_entity` (camera or player tracks a mob), `nearest(type)` helper
- [ ] `walk_to x y z` with simple pathing (straight line + jump), `interact_entity id`, `interact_block x y z face`
- [ ] Inventory and GUI: read container contents, click slots, craft by recipe id
- [ ] `world_query`: block states in a box, light levels, biome at position
- [ ] Assertions for scripted tests: `expect` (entity present, block state, chat line) with timeouts → pass/fail report
- [ ] A second "bot" client profile (offline-mode dev server) so tests don't take over the player's own game

## Next: Agentlink support (branch `agentlink-support`, not released)

- [x] Agentlink installed → Lensbridge registers its tools (bare names kept as aliases, ids `lensbridge.<tool>`), `/ui`,
      `/docs`, `/file` and its agent instructions with it over InterModComms, and starts no server of its own
- [x] Agentlink missing → built-in server, unchanged
- [x] Alt+C opens the Camera Manager on whichever server is live
- [ ] In-game check with and without Agentlink (owner's go-ahead needed)
- [x] Inset (`/lensbridge inset`, tools `inset`, `inset_snapshot`): picture-in-picture of a nearby player or mob.
      Tested 2026-11-13 on vanilla rendering (dev client) and on Sodium + Iris (shaders off and on) + Dynamic FPS,
      target up to 90 m away
- [x] Dynamic FPS: frames keep coming while Lensbridge captures, records or shows an inset (`FrameGuard`,
      `DynamicFpsMixin`); a skipped frame is never read back as a picture or left on screen
- [x] Inset camera collision (never inside walls, roofs, leaves)
- [x] Inset at 30 pictures a second (GPU texture copy, no readback; up to 60); the game camera is put back on the
      player after each hidden frame (Jade/WAILA and sound read it between frames)
- [x] Inset `eyes` mode, `full` view (the watching camera as the whole screen; Esc back), chase camera goes to the
      side when behind is blocked and falls back to eyes in a one-block space, night-vision lighting for the
      watching camera in the dark (`LightTextureMixin`), no first-person hand in full view (`GameRendererMixin`).
      Tested 2026-11-13 in the offline dev game
- [x] Far players: Agentlink `watch` with `view: "near"` puts the staff member there invisibly, then the inset works
- [ ] Carry to `mc/26.3` (only `UserControls` differs: 26.3 opens the URL with `Blaze3D.openUri`)
- [ ] Publish Lensbridge events on the bus (`lensbridge.capture_saved`, `lensbridge.recording_started/stopped`)

## Next: solid for release (owner, 2026-11-13: "we want lensbridge/agentlink to be a combo plugin (and standalones) so they need to be solid")

- [x] **Remove a camera in game** (owner: "I can place cameras but not remove them"; until now only the browser Camera Manager could). **Alt+X** removes the camera you are looking through, else one right where you are, else the one you look at, else the nearest within 5 blocks. `/lensbridge cameras` lists them with [remove] buttons, `/lensbridge cameras remove <name|all>`, `/lensbridge cameras undo`. Publishes `lensbridge.tripod_removed`. Built 2026-11-13, not yet tested in game
- [x] Bug audit findings (2026-11-13). Fixed and built 2026-11-13; **none of it tested in a live game yet**:
  - [x] One capture at a time: a global re-entrant lock around every capture sequence (`Capture.lock`); a second caller waits 30 s, then gets "Another capture is running". Alt+T, Alt+D and "Save my view here" use the camera only when it is on the player's screen
  - [x] `exit_camera` / "Back to my view" releases a hidden camera too (Esc and Backspace still only act on what is on screen)
  - [x] Alt+R releases a hidden camera first, leaves the HUD as it is so the REC line stays visible (F1 first for a clean video), prints failures as failures, and refuses to start while the last clip is still encoding
  - [x] Deleted cameras no longer come back: saving an edit of a removed camera, removing a camera that is being edited, and "Save my view here" on a stale card (`tripod_set_from_view` is update-only)
  - [x] Alt+X picks by name and from the on-screen view: looked-through, then right here (1 block), then looked-at with a clear line (16 blocks with props hidden, 48 shown), then nearest within 5
  - [x] `tripods.json`: bad entries skipped (damaged file copied to `tripods.json.broken-<time>`), atomic saves, save failures logged not thrown, `Tripods.all()` returns a copy
  - [x] Cameras carry their world (server address / world folder) and dimension; hotkeys, `/lensbridge cameras`, props, the manager and the default tool lists show only the current one; older cameras show everywhere
  - [x] Camera Manager reloads on a cheap signature poll, thumbnails are deleted with their camera, names are escaped in the page
  - [x] Leaving a world stops pilot mode, the camera, the recorder, the inset, an edit and held keys; respawn and dimension change do the camera / pilot / keys part
  - [x] Every restore step in thumbnails, renders, recordings and multicam runs whatever the others do; **Alt+Backspace** panic key
  - [x] `hold_keys` capped at 60 s and released when a screen opens
  - [x] Recorder: one token per recording
  - [x] Full-view inset stays out of hidden captures; `Inset.stop()` from the tool runs on the client thread
  - [x] `agent_inbox`: requests stay queued until a later poll acknowledges them (`ack` / `next_ack`; at most 3 deliveries)
  - [x] Empty or garbled `lensbridge.json` = defaults plus a `.broken` copy, atomic write; Alt+C and `/lensbridge connect` say why when the connection didn't start (port in use, disabled, Agentlink not listening)
  - [x] Hardening: `?token=` on `/file` only, output names sanitised, HTTP 400 for malformed JSON, warning for a non-loopback `host`; `scene`, `multicam`, `stage_*` and world=sync/frozen/step captures follow the server's AI-control rule
  - [x] `command` / `chat` refuse `agentlink confirm` / `agentlink cancel`; `screen_click` does not click in the chat screen
  - [x] Staff relay: staff-only events (staff chat, reports, moderation, player commands) go to moderate / admin only, using Agentlink's `eventTier` when it offers one; relayed calls time out after 25 s; pool of 4; payload limit counted in UTF-8 bytes
  - [x] `restore` tool and `/lensbridge restore`: unfreeze, `advance_time`, shaders, window size / fullscreen
- [ ] Regenerate `docs/TOOLS.md` with `tools/gen_tool_reference.py` against a running client (hand-edited for this change)
- [ ] Live test of everything above (owner's go-ahead needed to start a game)

Checked in the owner's game 2026-11-13 (one session): three captures at once all finished with whole pictures (capture
lock); kicked from the server with an inset open and a previewed director camera: both were gone at the disconnect
screen and the window showed it (no stuck stealth); a new camera is saved under its world (`mp:localhost`) and
dimension. Still not run: the panic key (needs a real key press), and the camera list in a second world.

### 26.3 edition of this work (branch `mc/26.3`)

- [x] Merged 2026-11-13: camera clean-up, restore, panic key, per-world cameras, capture lock, the Dynamic FPS guard,
      the hand-back of the game camera after hidden frames (through `Camera.update`), Agentlink support and the relay
- [ ] The inset is a stub on 26.3 (`Inset` answers "not in the 26.3 edition yet"): 26.3 changed the chunk culling,
      the light map and the frame textures it hooks into. `LevelRendererMixin`, `LightTextureMixin` and
      `GameRendererMixin` are not on this branch
- [ ] Not yet run in a 26.3 game: compiles and builds only

### Known gaps from the 2026-11-13 audit (left for later)

- [ ] world=sync renders change `tick rate` for everyone on a multiplayer server
- [ ] `required: true` `@Redirect` mixins on `blitToScreen` / `glfwSwapBuffers` (prefer `@WrapWithCondition`); `LevelRendererMixin` should be `require = 0`
- [ ] Stealth is read separately by two mixins within one frame (latch it once per frame)
- [ ] `keepRunningUnfocused` forces `pauseOnLostFocus` off every tick
- [ ] No fallback server if Agentlink rejects the provider
- [ ] Camera names with spaces can't be removed by command, and a camera can be named "all"
- [ ] Camera names are still global across worlds (a name used in one world is taken in all); a server reached under two addresses counts as two worlds
- [ ] Tools share the one director camera: `tripod_shots` (and anything else that borrows it) releases it when done, so
      a `camera_look_at` + `screenshot` running at the same time from another caller captures the player's view. The
      capture lock keeps frames from mixing; it does not give each caller its own camera (seen 2026-11-13)
- [ ] Cameras saved before per-world cameras existed have no world and show in every world
- [ ] With no world open, `tripod_list` still lists cameras
- [ ] `restore` does not put back time of day or weather; a freeze made by `stage_build` is undone by `stage_clear`, not `restore`

## 0.6: promo studio

- [ ] **Hi-res stills:** offscreen render target at any size (4K/8K) without resizing the window; supersampling
- [ ] **Panoramas:** 360° equirectangular and the 6-face cubemap (the vanilla title-screen panorama format)
- [ ] Depth-of-field / tilt-shift post effect; letterbox and safe-area overlays for framing
- [ ] Shot list files (`.lensbridge.json`): named cameras, paths and scene presets, run as a batch to re-shoot everything after a texture change
- [ ] Per-entity "hold still" for one subject (NoAI + no tick) instead of freezing the whole world
- [ ] Camera path preview: draw the spline and keyframes in-world before rendering
- [ ] Audio capture for `record` (game audio → AAC)
- [ ] Timelapse (`record` with tick stepping between frames)

## Release checklist

- [ ] Name check on CurseForge/Modrinth (Lensbridge)
- [ ] Icon and screenshots (made with Lensbridge itself)
- [ ] Clear security note in the description (localhost, token, opt-in)
- [ ] Config screen (NeoForge config UI)
- [ ] Multi-version support (1.21.1 LTS?)
