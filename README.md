# Lensbridge

**Let AI assistants and scripts see and drive your Minecraft client**, for mod testing, automated checks, and promo stills, orbits, camera moves and video.

Lensbridge is a small **client-side NeoForge mod** (Minecraft 1.21.11). It runs a local **MCP server** (Model Context Protocol, the tool protocol Claude and other assistants use) plus a simple REST API on `127.0.0.1`. Through it an assistant can:

- **See** the game. Screenshots come from the game's own framebuffer, never the desktop, other windows or other monitors.
- **Act:** move, look, use and attack, pick hotbar slots, chat, run commands.
- **Inspect:** what's under the crosshair, nearby entities, inventory, chat and command feedback.
- **Direct:** a free director camera with position, angles, roll and FOV, HUD on or off, window size.
- **Stage:** time, weather, and freezing or stepping the world so every mob holds still.
- **Capture:** clean stills, turntable **orbits**, smooth **camera paths** through keyframes, and real-time **recordings**, encoded to MP4 or GIF with ffmpeg, with a contact-sheet preview returned inline.

It was built to let an AI assistant test the [Mothlight](https://github.com/joenilan/mothlight) mod by itself and make its promo material, but it works with any modpack.

---

## Install

1. Put `lensbridge-<version>.jar` in your client's `mods/` folder (NeoForge 21.11.x, MC 1.21.11). It's client-only, so the server doesn't need it.
2. Start the game once. That creates `config/lensbridge.json` with a random **token**:

```json
{
  "enabled": true,
  "host": "127.0.0.1",
  "port": 25599,
  "token": "…random…",
  "captureDir": "lensbridge",
  "previewMaxEdge": 1280,
  "keepRunningUnfocused": true,
  "ffmpeg": "ffmpeg"
}
```

- It only listens on **localhost**, and every request needs `Authorization: Bearer <token>`. Delete the file to get a new token.
- `keepRunningUnfocused` stops the pause menu from popping up when the window loses focus, so the game can be driven in the background.
- Captures go to `<game dir>/lensbridge/` (`screenshots/`, `frames/`, `videos/`).
- Video needs [ffmpeg](https://ffmpeg.org/) on your PATH, or its path set in `ffmpeg`.

### Connect Claude Code

```bash
claude mcp add --transport http lensbridge http://127.0.0.1:25599/mcp \
  --header "Authorization: Bearer <token from config/lensbridge.json>"
```

Any MCP client that speaks Streamable HTTP works the same way.

### Scripts / curl (REST mirror)

```bash
T=<token>
curl -H "Authorization: Bearer $T" -d '{}' http://127.0.0.1:25599/api/status
curl -H "Authorization: Bearer $T" -d '{"hide_hud":true}' http://127.0.0.1:25599/api/screenshot
curl -H "Authorization: Bearer $T" http://127.0.0.1:25599/api/          # list tools + schemas
```

`POST /api/<tool>` with the arguments as the JSON body. It returns `{"content":[{type:text|image,…}],"isError":false}`. `GET /health` needs no token.

---

## Tools

| Group | Tool | What it does |
|---|---|---|
| Observe | `status` | in-world?, open screen, position/rotation/dimension, health, gamemode, time, weather, FPS, window, director camera |
| | `screenshot` | game-only capture; saves a full-size PNG and returns a preview (`hide_hud`, `name`, `settle_frames`) |
| | `inspect` | crosshair block/entity, nearby entities (type, name, position, distance, HP, id), hands and hotbar |
| | `chat_log` | received chat and system lines, numbered (`since`) |
| | `wait` | ticks / frames / milliseconds |
| Act | `command` / `chat` | send a command (no `/` needed) or a chat message |
| | `look` | absolute yaw/pitch, or look at x/y/z |
| | `hold_keys` | hold keys for N ticks: `forward back left right jump sneak sprint use attack drop` |
| | `press` | tap a key once (click) |
| | `hotbar` / `close_screen` / `release_inputs` | pick a slot, close GUIs, release everything |
| Direct | `camera_set` / `camera_look_at` / `camera_release` | free director camera (absolute or player-relative; roll; FOV) |
| | `hud` / `window_size` | F1 toggle; resize the window (sets the capture resolution) |
| | `scene` | `time`, `weather`, `freeze`, `step`, `lock_time` (needs op) |
| Capture | `capture_orbit` | turntable around a point → frames → MP4 (+GIF) + contact sheet |
| | `capture_path` | Catmull-Rom camera move through keyframes (`look_at`, FOV, roll, easing) → MP4 |
| | `record` | real-time recording of the live view → MP4 (gameplay clips) |
| | `list_captures` | recent files with full paths |

### Promo recipe

```text
scene        {"time":"sunset","weather":"clear","freeze":true}     # everything holds still
window_size  {"width":1920,"height":1080}
camera_look_at {"x":10,"y":70,"z":4,"tx":12,"ty":68.5,"tz":8,"fov":50}
screenshot   {"hide_hud":true,"name":"hero_shot"}
capture_orbit {"cx":12,"cy":68.5,"cz":8,"radius":4,"height":1.2,"frames":240,"fps":60,"name":"turntable","gif":true}
capture_path {"seconds":8,"fps":60,"keyframes":[{"x":0,"y":72,"z":0,"look_at":[12,68,8]},
              {"x":8,"y":70,"z":2,"look_at":[12,68,8],"fov":45},{"x":14,"y":69,"z":5,"look_at":[12,68.5,8],"fov":35}]}
scene        {"freeze":false}
camera_release {}
```

`capture_orbit` and `capture_path` render frame by frame (not in real time), so they come out smooth however slow the machine is. Freeze the world first for perfectly still subjects, or leave it running for life.

---

## Limits (0.1)

- The director camera only shows chunks the player has loaded. For far shots, move the player too (spectator + `/tp`).
- The capture resolution is the window size. Use `window_size` for 1080p, 1440p or 4K-wide windows.
- `command`, `scene` and other world changes need op on the server.
- If the game window is minimised, nothing renders and captures time out.

See [ROADMAP.md](ROADMAP.md) for what's next.

## Build

```bash
./gradlew build      # → build/libs/lensbridge-<version>.jar
```

## License

MIT
