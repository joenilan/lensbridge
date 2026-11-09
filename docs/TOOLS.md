# Lensbridge tool reference

*Generated from a running Lensbridge by `tools/gen_tool_reference.py` (58 tools). Every tool is available over MCP (`tools/call`) and REST (`POST /api/<tool>` with the arguments as JSON).*

## Observe

### `status`

Game state: in-world?, open screen, player position/rotation/dimension/health/gamemode, time, weather, FPS, window size, server, director camera, capture folder.

### `screenshot`

Capture the game view (the game's own framebuffer only - never the desktop). Saves a full-size PNG and returns a preview image. Use it to SEE what is happening.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `hide_hud` | boolean |  | Hide HUD/hand for a clean shot (default false) |
| `name` | string |  | File name (without .png); default is a timestamp |
| `settle_frames` | integer |  | Frames to wait before capture (default 3) |
| `preview_size` | integer |  | Longest edge of the returned preview (default from config) |

### `inspect`

What the player is looking at (block or entity), nearby entities with positions and names, held items and hotbar.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `radius` | number |  | Entity search radius (default 12) |
| `max_entities` | integer |  | Max entities listed (default 30) |

### `chat_log`

Chat and system messages the client received (command feedback included). Pass 'since' = last id you saw to get only new lines.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `since` | integer |  | Only lines with id greater than this (default 0) |

### `wait`

Wait for game ticks / frames / milliseconds (let things happen before the next screenshot).

| Argument | Type | Required | Notes |
|---|---|---|---|
| `ticks` | integer |  | Game ticks (20 per second) |
| `frames` | integer |  | Rendered frames |
| `millis` | integer |  | Milliseconds |

### `find_entities`

Find loaded entities by type and/or name (to lock the camera on them). Returns id, type, name, position and distance from the player.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `type` | string |  | e.g. mothlight:lumen (substring match) |
| `name` | string |  | Name contains (case-insensitive) |
| `limit` | integer |  | Default 20 |

### `list_captures`

List recent captures (screenshots, videos, frame folders) with full paths.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `limit` | integer |  | Default 20 |

## Act (player input)

### `command`

Run a command as the player (no leading slash needed). Needs op for most commands. Feedback appears in chat_log.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `command` | string | yes | e.g. 'time set midnight' |

### `chat`

Send a chat message as the player.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `message` | string | yes | Message text |

### `look`

Turn the player's head: absolute yaw/pitch, or look at a world position.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `yaw` | number |  | Degrees (0 = south/+Z, 90 = west, 180 = north, -90 = east) |
| `pitch` | number |  | Degrees (-90 up .. 90 down) |
| `x` | number |  | Look-at target X |
| `y` | number |  | Look-at target Y |
| `z` | number |  | Look-at target Z |

### `hold_keys`

Hold movement/action keys for a number of ticks (20 = 1 s), e.g. {"keys":{"forward":40,"jump":5}}. Keys: forward back left right jump sneak sprint use attack drop.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `keys` | object | yes | Map of key -> ticks to hold (0 releases) |
| `wait` | boolean |  | Block until all keys are released (default true) |

### `press`

Tap a key once (a click): use, attack, drop, inventory, swap_offhand, pick_block, jump.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `key` | string | yes | Key name |

### `hotbar`

Select a hotbar slot (1-9) and report what is held.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `slot` | integer | yes | 1-9 |

### `close_screen`

Close any open GUI/screen (inventory, menu, chat...).

### `release_inputs`

Release every held key and return the camera to the player.

## Stage & look

### `scene`

Stage a scene with one call (sends commands; needs op): time, weather, freeze the world (/tick freeze) so everything holds still for shots, step frozen ticks, clear the weather/time cycle.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `look` | string |  | Lighting preset: qa (shaders off, morning light, clear - honest neutral look), noon, golden (low warm sun), sunset, blue_hour, night, dawn, overcast |
| `shaders` | boolean |  | Iris shaders on/off (if Iris is installed) |
| `time` | string |  | day / noon / sunset / night / midnight / sunrise / or a tick number |
| `weather` | string |  | clear / rain / thunder |
| `freeze` | boolean |  | true = /tick freeze, false = /tick unfreeze |
| `step` | integer |  | Advance a frozen world by N ticks |
| `lock_time` | boolean |  | Stop the day cycle (gamerule) |

### `hud`

Show or hide the HUD (like F1).

| Argument | Type | Required | Notes |
|---|---|---|---|
| `visible` | boolean | yes | true = show |

### `window_size`

Resize the game window (e.g. 1920x1080 for promo captures). Captures use the window resolution.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `width` | integer | yes |  |
| `height` | integer | yes |  |

## Director camera

### `camera_set`

Director camera: place a free camera (independent of the player; the player is drawn too). Stay within the player's loaded chunks. Use camera_release to return to normal.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `x` | number | yes |  |
| `y` | number | yes |  |
| `z` | number | yes |  |
| `yaw` | number |  | Degrees (0 = south/+Z) |
| `pitch` | number |  | Degrees (+ = down) |
| `roll` | number |  | Degrees (Dutch angle) |
| `fov` | number |  | Degrees (default: player setting) |
| `relative` | boolean |  | x/y/z are offsets from the player (default false) |

### `camera_look_at`

Director camera at a position, aimed at a target point (great for framing a subject).

| Argument | Type | Required | Notes |
|---|---|---|---|
| `x` | number | yes | camera X |
| `y` | number | yes | camera Y |
| `z` | number | yes | camera Z |
| `tx` | number | yes | target X |
| `ty` | number | yes | target Y |
| `tz` | number | yes | target Z |
| `fov` | number |  | Degrees |
| `roll` | number |  | Degrees |

### `camera_preview`

Show the director camera live on the player's screen (on), or keep it hidden so captures never disturb the player's view (off, default).

| Argument | Type | Required | Notes |
|---|---|---|---|
| `on` | boolean | yes |  |

### `camera_release`

Return to the normal player camera.

### `exit_camera`

Return the player to their own view from any camera mode (edit -> cancel, drone -> land, preview -> off). Same as pressing Esc in game.

## Cinematic moves

### `camera_lock`

Target lock: the camera holds its position (given x/y/z, or the current view) and keeps the target framed as it moves. Target: {"entity": id} | {"nearest": "minecraft:pig"} | {"point": [x,y,z]} | {"player": true}; optional "offset": [dx,dy,dz] for the aim point.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `target` | object | yes | Target: {"entity": id} / {"nearest": "minecraft:pig"} / {"point": [x,y,z]} / {"player": true}; optional "offset": [dx,dy,dz] for the aim point. |
| `x` | number |  | Camera X (optional) |
| `y` | number |  |  |
| `z` | number |  |  |
| `relative` | boolean |  | x/y/z relative to the player |
| `fov` | number |  | Degrees |
| `smoothing` | number |  | 0 = snap, 0.9 = very smooth (default 0.6) |

### `camera_orbit`

Live orbit around a target (follows it if it moves) - 360 turntable views. Target: {"entity": id} | {"nearest": "minecraft:pig"} | {"point": [x,y,z]} | {"player": true}; optional "offset": [dx,dy,dz] for the aim point.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `target` | object | yes | Target: {"entity": id} / {"nearest": "minecraft:pig"} / {"point": [x,y,z]} / {"player": true}; optional "offset": [dx,dy,dz] for the aim point. |
| `radius` | number |  | Default 4 |
| `height` | number |  | Above the aim point (default 1) |
| `speed` | number |  | Degrees/second, negative = other way (default 30) |
| `start_angle` | number |  | Degrees (default 0) |
| `sweep` | number |  | Stop after N degrees (0 = endless) |
| `look_height` | number |  | Aim offset above the target (default 0) |
| `fov` | number |  | Degrees |

### `camera_pan`

Live pan: fixed position, the view sweeps horizontally (360 panorama pans).

| Argument | Type | Required | Notes |
|---|---|---|---|
| `x` | number |  | Camera X (default: current view) |
| `y` | number |  |  |
| `z` | number |  |  |
| `relative` | boolean |  | x/y/z relative to the player |
| `start_yaw` | number |  | Degrees (default: current) |
| `speed` | number |  | Degrees/second (default 20) |
| `sweep` | number |  | Degrees then stop (default 360, 0 = endless) |
| `pitch` | number |  | Degrees (default: current) |
| `fov` | number |  | Degrees |

### `camera_rail`

Rail cam: glide along a saved path or keyframes (smooth spline). Optionally lock onto a target while travelling. Modes: once, loop, pingpong.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `path` | string |  | Saved path name |
| `keyframes` | array |  | Or inline keyframes [{x,y,z,yaw?,pitch?,look_at?,fov?,roll?}] |
| `seconds` | number |  | Time for one pass (default 8) |
| `mode` | string |  | once / loop / pingpong (default once) |
| `easing` | string |  | smooth / linear (default smooth) |
| `target` | object |  | Optional lock. Target: {"entity": id} / {"nearest": "minecraft:pig"} / {"point": [x,y,z]} / {"player": true}; optional "offset": [dx,dy,dz] for the aim point. |

### `camera_follow`

Chase cam: stays behind/above the target relative to its facing, smoothly. Great for riding, flying and walking shots. Target: {"entity": id} | {"nearest": "minecraft:pig"} | {"point": [x,y,z]} | {"player": true}; optional "offset": [dx,dy,dz] for the aim point.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `target` | object | yes | Target: {"entity": id} / {"nearest": "minecraft:pig"} / {"point": [x,y,z]} / {"player": true}; optional "offset": [dx,dy,dz] for the aim point. |
| `distance` | number |  | Behind, blocks (default 4) |
| `height` | number |  | Above (default 1.5) |
| `side` | number |  | Right (+) / left (-) offset (default 0) |
| `smoothing` | number |  | 0..0.95 (default 0.85) |
| `fov` | number |  | Degrees |

### `camera_dolly_zoom`

Vertigo / dolly zoom: the camera pushes toward (or pulls away from) the target while the FOV changes so the subject stays the same size and the background warps. Target: {"entity": id} | {"nearest": "minecraft:pig"} | {"point": [x,y,z]} | {"player": true}; optional "offset": [dx,dy,dz] for the aim point.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `target` | object | yes | Target: {"entity": id} / {"nearest": "minecraft:pig"} / {"point": [x,y,z]} / {"player": true}; optional "offset": [dx,dy,dz] for the aim point. |
| `x` | number |  | Start X (default: current view) |
| `y` | number |  |  |
| `z` | number |  |  |
| `end_distance` | number |  | Blocks from the target at the end (default 2) |
| `seconds` | number |  | Default 4 |
| `fov` | number |  | Starting FOV (default 30) |

## Drone

### `drone_fly`

Fly a PHYSICS DRONE camera (inertia, speed/accel limits, banking, stabilised gimbal, hover wobble, ground clearance) - real drone footage. Autonomous modes: hover (hold, gimbal tracks target), mission (fly waypoints, optional target to keep framed), orbit (physics orbit, natural lag+bank), follow (auto follow-me aerial: trails, banks, frames the subject), dronie (pull back + up away from subject), reveal (rise up, gimbal tilts down), topdown (god view, slow spin), fpv (racing-drone lines: hard banks, no gimbal), pilot (sticks via drone_control). Starts from the current view unless x/y/z given. Then screenshot / record (live) / capture_program (frame-exact).

| Argument | Type | Required | Notes |
|---|---|---|---|
| `mode` | string | yes | hover / mission / orbit / follow / dronie / reveal / topdown / fpv / pilot |
| `target` | object |  | Subject: {entity:id} / {nearest:type} / {point:[x,y,z]} / {player:true} |
| `waypoints` | array |  | mission/fpv: [{x,y,z,hold?}] (hold = seconds to hover there) |
| `loop` | boolean |  | mission/fpv: loop the waypoints |
| `x` | number |  | Start X (default: current view) |
| `y` | number |  |  |
| `z` | number |  |  |
| `max_speed` | number |  | blocks/s (default 6; fpv 14) |
| `accel` | number |  | blocks/s^2 (default 4; fpv 12) |
| `yaw_rate` | number |  | deg/s (default 60; fpv 180) |
| `bank` | number |  | Banking amount (default 3) |
| `wobble` | number |  | Hover wobble 0..1 (default 0.5) |
| `clearance` | number |  | Min blocks above ground (default 1) |
| `distance` | number |  | follow: behind (default 6); dronie: pull-back blocks (default 18); reveal: forward push (default 2) |
| `height` | number |  | follow: above (default 4); dronie: climb (default 10); reveal: rise (default 8); topdown: altitude (default 20); orbit: above (default 3) |
| `radius` | number |  | orbit radius (default 8) |
| `speed` | number |  | orbit / topdown spin deg/s (default 20 / 6) |
| `seconds` | number |  | dronie / reveal length (default 8) |
| `fov` | number |  | Degrees |

### `drone_control`

Pilot the drone with sticks (starts a pilot-mode drone at the current view if needed). Values -1..1, held for 'seconds' then centred.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `forward` | number |  |  |
| `right` | number |  |  |
| `up` | number |  |  |
| `yaw` | number |  | Turn (+ = right) |
| `gimbal` | number |  | Tilt (+ = down) |
| `seconds` | number |  | Default 1 |
| `max_speed` | number |  | blocks/s (default 6) |

### `drone_status`

Drone telemetry: mode, altitude above ground, speed, yaw, gimbal.

## Tripods & Camera Manager

### `tripod_place`

Place a named fixed camera (tripod) - at the current view, or at x/y/z aimed at look_at or tracking a target. Use several around a subject for multiple angles.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `name` | string |  | Default tripod_N |
| `x` | number |  |  |
| `y` | number |  |  |
| `z` | number |  |  |
| `look_at` | array |  | [x,y,z] fixed aim point |
| `target` | object |  | Track a subject instead (aims every frame) |
| `fov` | number |  | Degrees |

### `tripod_ring`

Place a ring of tripods around a subject in one go (e.g. 4 or 6 angles), all aimed at it.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `cx` | number | yes |  |
| `cy` | number | yes |  |
| `cz` | number | yes |  |
| `count` | integer |  | Default 4 |
| `radius` | number |  | Default 5 |
| `height` | number |  | Above the subject (default 1.5) |
| `start_angle` | number |  | Degrees (default 45) |
| `fov` | number |  |  |
| `prefix` | string |  | Name prefix (default 'ring') |

### `tripod_list`

List tripods.

### `tripod_all`

All tripods as JSON (name, group, x, y, z, yaw, pitch, fov, enabled, track, has_thumb).

### `tripod_update`

Edit a tripod: any of x, y, z, yaw, pitch, fov, enabled, rename (new_name), look_at [x,y,z] (re-aim), track (target object, or null to stop tracking).

| Argument | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes |  |
| `x` | number |  |  |
| `y` | number |  |  |
| `z` | number |  |  |
| `yaw` | number |  |  |
| `pitch` | number |  |  |
| `fov` | number |  |  |
| `enabled` | boolean |  |  |
| `new_name` | string |  |  |
| `look_at` | array |  |  |
| `track` | object |  |  |

### `tripod_nudge`

Nudge a tripod relative to its own view: forward/right/up in blocks, yaw/pitch/fov in degrees.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes |  |
| `forward` | number |  |  |
| `right` | number |  |  |
| `up` | number |  |  |
| `yaw` | number |  |  |
| `pitch` | number |  |  |
| `fov` | number |  |  |

### `tripod_set_from_view`

Store the current view (director camera if active, else the player's eyes) into a tripod (creates it if missing). Fly the drone (K) or look around, then save the angle you like.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes |  |

### `tripod_thumbs`

Refresh tripod thumbnails (stealth - the player's screen doesn't change). Default: all.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `names` | array |  | Tripod names |
| `group` | string |  | Only this group |

### `tripod_preview`

Show a tripod's view live on the player's screen (or 'off' to return to the player).

| Argument | Type | Required | Notes |
|---|---|---|---|
| `name` | string |  | Tripod name, omit to turn preview off |

### `tripod_edit`

Take control of a tripod in game: the player flies its camera (WASD/Space/Shift, mouse, scroll = FOV), Enter saves, Backspace cancels - or finish with tripod_edit_finish.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes |  |

### `tripod_edit_finish`

Finish taking control: save the current view into the tripod, or cancel.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `save` | boolean |  | Default true |

### `tripod_view`

Look through a tripod (live).

| Argument | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes |  |

### `tripod_remove`

Remove a tripod (or all).

| Argument | Type | Required | Notes |
|---|---|---|---|
| `name` | string |  |  |
| `all` | boolean |  |  |

## Capture

### `screenshot`

Capture the game view (the game's own framebuffer only - never the desktop). Saves a full-size PNG and returns a preview image. Use it to SEE what is happening.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `hide_hud` | boolean |  | Hide HUD/hand for a clean shot (default false) |
| `name` | string |  | File name (without .png); default is a timestamp |
| `settle_frames` | integer |  | Frames to wait before capture (default 3) |
| `preview_size` | integer |  | Longest edge of the returned preview (default from config) |

### `record`

Record the live view (player camera, director camera, drone...) in REAL TIME - the clip lasts exactly as long as the action (late frames are repeated, never sped up). Set timelapse_interval for a timelapse.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `seconds` | number |  | Default 5 |
| `fps` | integer |  | Default 30 |
| `timelapse_interval` | number |  | Only for timelapses: seconds of game time between frames (e.g. 2) |
| `name` | string |  | Output name |
| `hide_hud` | boolean |  | default false |
| `gif` | boolean |  | Also make a GIF |

### `capture_orbit`

Turntable shot: the director camera circles a point (or a target entity's centre) and every frame is saved, then encoded to MP4 (and optional GIF) with ffmpeg. Returns a contact sheet preview. Tip: scene freeze first; world=step + fps 60 for the smoothest turntables.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `cx` | number |  | Centre X (or use target) |
| `cy` | number |  | Centre Y |
| `cz` | number |  | Centre Z |
| `target` | object |  | Centre on a target instead: {entity:id} / {nearest:type}; centre = middle of its hitbox, plus optional offset [dx,dy,dz] |
| `auto_center` | boolean |  | With an entity target (default true): measure where the MODEL really is (two probe views, with/without the entity) and centre on it; radius defaults to fit the model |
| `radius` | number |  | Distance (default 4) |
| `height` | number |  | Camera height above centre (default 1.5) |
| `frames` | integer |  | Frame count (default 120) |
| `fps` | integer |  | Playback fps (default 30) |
| `start_angle` | number |  | Degrees (default 0) |
| `sweep` | number |  | Degrees to travel (default 360) |
| `fov` | number |  | Degrees |
| `name` | string |  | Output name |
| `gif` | boolean |  | Also make a GIF |
| `hide_hud` | boolean |  | default true |
| `world` | string |  | World speed during the render: sync (default - mobs move at true speed), frozen (world held still), step (frozen + world advanced exactly 20/fps ticks per frame, interpolated - perfectly smooth at any fps, e.g. 60), free (world runs at full speed = timelapse look). sync/frozen/step need op. |

### `capture_path`

Cinematic camera move through keyframes, saved frame by frame and encoded to MP4. Keyframes: [{x,y,z, yaw?,pitch?, look_at?:[x,y,z], fov?, roll?}, ...] (at least 2). Smooth Catmull-Rom path; angles from look_at if given.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `keyframes` | array |  | List of keyframe objects |
| `path` | string |  | Or: name of a saved path |
| `seconds` | number |  | Duration (default 6) |
| `fps` | integer |  | Default 30 |
| `easing` | string |  | smooth (ease in/out, default) / linear |
| `name` | string |  | Output name |
| `gif` | boolean |  | Also make a GIF |
| `hide_hud` | boolean |  | default true |
| `world` | string |  | World speed during the render: sync (default - mobs move at true speed), frozen (world held still), step (frozen + world advanced exactly 20/fps ticks per frame, interpolated - perfectly smooth at any fps, e.g. 60), free (world runs at full speed = timelapse look). sync/frozen/step need op. |

### `capture_program`

Render the currently playing camera move (orbit/pan/rail/follow/dolly/lock) frame by frame at an exact frame rate, then encode to MP4. The world is kept at its true speed (world=sync) so mobs move naturally; world=step gives perfectly smooth motion; world=free is a timelapse look.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `seconds` | number |  | Length (default: the move's own duration, or 6) |
| `fps` | integer |  | Default 30 |
| `world` | string |  | World speed during the render: sync (default - mobs move at true speed), frozen (world held still), step (frozen + world advanced exactly 20/fps ticks per frame, interpolated - perfectly smooth at any fps, e.g. 60), free (world runs at full speed = timelapse look). sync/frozen/step need op. |
| `tick_step` | integer |  | Legacy: >0 = world 'step' |
| `name` | string |  | Output name |
| `gif` | boolean |  | Also make a GIF |
| `hide_hud` | boolean |  | default true |

### `tripod_shots`

Multi-angle stills: a clean screenshot from every tripod (or the named ones), plus one contact sheet of all angles.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `names` | array |  | Tripod names (default all) |
| `name` | string |  | Output prefix |

### `multicam`

Synced multi-angle VIDEO at true world speed: freezes the world and, tick by tick, renders every tripod's view, so you get one MP4 per angle with identical action (perfect for editing between angles). Needs op.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `seconds` | number |  | Default 5 |
| `fps` | integer |  | Default 20 (1 world tick per frame) |
| `names` | array |  | Tripod names (default all) |
| `name` | string |  | Output prefix |

## Paths

### `path_mark`

Build a rail interactively: append the current view (director camera, or where the player is looking from) as a keyframe to a named path. Fly/walk, mark, repeat. Optional look_at makes that key aim at a point.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes | Path name |
| `look_at` | array |  | [x,y,z] aim point for this key |
| `fov` | number |  | FOV for this key |
| `reset` | boolean |  | Start the path over |

### `path_save`

Save keyframes as a named path (for camera_rail / capture_path / path_show).

| Argument | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes |  |
| `keyframes` | array | yes | [{x,y,z,yaw?,pitch?,look_at?,fov?,roll?}, ...] |

### `path_get`

Return a saved path's keyframes as JSON (to edit and path_save back).

| Argument | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes |  |

### `path_list`

List saved paths with keyframe count and length.

### `path_delete`

Delete a saved path.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes |  |

### `path_show`

Preview a path in the world: draws the rail with particles (only on this client) for a few seconds, with the keyframes marked. Take a screenshot to check the line before rendering.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes | Saved path name |
| `seconds` | number |  | Default 10 |

## Other

### `props`

Camera props for behind-the-scenes shots: visible models of the tripods (camera on legs), the drone (spinning rotors) and the current rail. Client-only. Normal shots stay clean: props show only in the player's view if player_view is on, and in captures from 'crew' cameras (e.g. a drone or a tripod named in crew). A camera never sees its own prop.

