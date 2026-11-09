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

## 0.3: drone + tripods (done 2026-11-09)

- [x] Physics drone (`drone_fly`): inertia, accel/speed limits, yaw-rate limit, banking, stabilised gimbal, hover wobble, ground clearance; deterministic under capture_program
- [x] Autonomous modes: hover, mission (waypoints + holds + framing target), orbit, follow (aerial follow-me), dronie, reveal, topdown, fpv; pilot via sticks (`drone_control`)
- [x] Tripods: place / ring / track a target / view / list / remove; `tripod_shots` multi-angle stills; **`multicam`** synced per-angle video via tick stepping
- [x] Player hotkeys: K drone pilot (WASD/Space/Shift/Sprint + mouse), Y place tripod, U cycle tripods, J record; HUD telemetry
- [x] Unfocused windows aren't FPS-throttled (inactivity limit = minimized only)
- [x] Tested live: aerial follow of a sprinting player + Lumens, dronie, top-down, 4-tripod stills, 4-angle 3 s multicam
- [x] 0.3.1: true-speed video. Frames stream to ffmpeg (no PNG per frame); `record` is real time (late frames repeated); renders keep the world in sync (tick-rate scaling + real-time pacing) with `world` = sync/step/frozen/free; timelapse only via `timelapse_interval`
- [ ] Fix: the first 1–2 frames of `capture_program` can show the pre-reset view (drone restart)
- [ ] Visible drone/tripod models for other players (optional "behind the scenes" shots)

## 0.4: testing power

- [ ] **Event stream:** subscribe to chat, deaths, advancements, screen opens, entity spawns near the player (SSE on `/mcp` GET)
- [ ] `look_at_entity` / `follow_entity` (camera or player tracks a mob), `nearest(type)` helper
- [ ] `walk_to x y z` with simple pathing (straight line + jump), `interact_entity id`, `interact_block x y z face`
- [ ] Inventory and GUI: read container contents, click slots, craft by recipe id
- [ ] `world_query`: block states in a box, light levels, biome at position
- [ ] Assertions for scripted tests: `expect` (entity present, block state, chat line) with timeouts → pass/fail report
- [ ] A second "bot" client profile (offline-mode dev server) so tests don't take over the player's own game

## 0.5: promo studio

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
