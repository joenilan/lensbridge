# AGENTS.md

Two kinds of agents read this repo.

## Using Lensbridge (driving a game)

Read **[docs/AGENT_GUIDE.md](docs/AGENT_GUIDE.md)** first, then use [docs/TOOLS.md](docs/TOOLS.md) as the reference. The short version:
- Never use the player's view as your camera; use the director camera, tripods or the drone.
- Stage in one command (a datapack function), freeze the world first, and light it properly.
- Verify by opening the images, and show the person the files.
- Leave the player on their own view when done (`exit_camera`, `camera_release`).

## Working on Lensbridge (changing the code)

- NeoForge 21.11.x / MC 1.21.11, Java 21. A client mod (`Lensbridge`, `@Mod(dist = CLIENT)`) with an optional server side (`server/LensbridgeServer`: config + permission nodes, sent to clients over an optional channel; must not touch client classes). Build: `./gradlew build` → `build/libs/`.
- **Map:**

  | Package | Contents |
  |---|---|
  | `mcp/` | HTTP server (MCP JSON-RPC + REST + `/ui` + `/file`) and the `Tool` registry types |
  | `tools/` | Tool groups: `Tools` (core), `CinemaTools`, `DroneTools`, `ManagerTools`, `Rendering` (frame-exact and real-time video) |
  | `cinema/` | `Shot`, `Target`, `CameraPath`, `CameraProgram` (lock/orbit/pan/rail/follow/dolly), `Drone`, `Tripods` |
  | `mixin/` | Camera override (`CameraMixin`), stealth present-skipping (`MinecraftMixin`, `RenderSystemMixin`) |
  | `server/` | Optional server install: server rules (`LensbridgeServer`), `PolicyPayload`, `ServerRules`; the client applies them in `Policy` |
  | top level | `Capture` (grab, streams, thumbnails, contact sheets), `VideoStream` (raw frames → ffmpeg), `DirectorCamera`, `Stealth`, `UserControls` (hotkeys, drone pilot, take-control, HUD), `Shaders` (optional Iris) |
  | UI | `src/main/resources/assets/lensbridge/ui/index.html` (single file, no build step) |

- **Threading:** tool handlers run on HTTP threads. Anything touching the game goes through `Game.call/run` (client thread). Never block the client thread waiting on frames; `Game.waitFrames` is for HTTP threads only.
- **Captures:** use `Capture.grabImage` / `grab`, and wrap multi-frame work in `Stealth.begin()/end()` so the player's screen doesn't change.
- **Adding a tool:** register it in one of the `tools/*Tools.registerAll`, give it a precise description (agents rely on it), then regenerate the reference with `python3 tools/gen_tool_reference.py <token>` against a running client.
- **Docs are part of every change:** update `README.md`, `docs/USER_GUIDE.md` (players), `docs/AGENT_GUIDE.md` (agents), `ROADMAP.md` and `docs/TOOLS.md`.
- **Testing:** there's no unit-test harness; test against a live client (REST via curl), look at the captured images, and state what was and wasn't verified.
