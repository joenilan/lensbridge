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
  | `mcp/` | HTTP server (MCP JSON-RPC + REST + `/ui` + `/file`), the `Tool` registry types, and `AgentlinkSupport` (hands everything to the Agentlink mod over InterModComms when it's installed; no compile dependency) |
  | `tools/` | Tool groups: `Tools` (core), `CinemaTools`, `DroneTools`, `ManagerTools`, `Rendering` (frame-exact and real-time video) |
  | `cinema/` | `Shot`, `Target`, `CameraPath`, `CameraProgram` (lock/orbit/pan/rail/follow/dolly), `Drone`, `Tripods` |
  | `mixin/` | Camera override (`CameraMixin`), stealth present-skipping (`MinecraftMixin`, `RenderSystemMixin`), vanilla-only chunk bookkeeping for inset frames (`LevelRendererMixin`; Sodium needs none), optional Dynamic FPS override (`DynamicFpsMixin`, `@Pseudo`) |
  | `server/` | Optional server install: server rules (`LensbridgeServer`), `PolicyPayload`, `ServerRules`; the client applies them in `Policy` |
  | top level | `Capture` (grab, streams, thumbnails, contact sheets), `VideoStream` (raw frames → ffmpeg), `DirectorCamera`, `Stealth`, `UserControls` (hotkeys, drone pilot, take-control, HUD), `Shaders` (optional Iris) |
  | UI | `src/main/resources/assets/lensbridge/ui/index.html` (single file, no build step) |

- **Threading:** tool handlers run on HTTP threads. Anything touching the game goes through `Game.call/run` (client thread). Never block the client thread waiting on frames; `Game.waitFrames` is for HTTP threads only.
- **Other mods:** `FrameGuard.needed()` says when Lensbridge needs every frame really drawn; Dynamic FPS (which skips most frames while the window is unfocused or idle) is overridden only then. `FrameGuard.sodium()` gates anything that touches vanilla's chunk renderer: Sodium `@Overwrite`s `LevelRenderer.cullTerrain` and recomputes visibility per camera in the frame. Test render changes with Sodium + Iris + Dynamic FPS (the owner's client) and without (`./gradlew runClient`, offline).
- **Captures:** use `Capture.grabImage` / `grab`, and wrap multi-frame work in `Stealth.begin()/end()` so the player's screen doesn't change.
- **Adding a tool:** register it in one of the `tools/*Tools.registerAll`, give it a precise description (agents rely on it), then regenerate the reference with `python3 tools/gen_tool_reference.py <token>` against a running client.
- **Docs are part of every change:** update `README.md`, `docs/USER_GUIDE.md` (players), `docs/AGENT_GUIDE.md` (agents), `ROADMAP.md` and `docs/TOOLS.md`.
- **Testing:** there's no unit-test harness; test against a live client (REST via curl), look at the captured images, and state what was and wasn't verified.

## Before you start or join the owner's game

Read [../AGENTS.md](../AGENTS.md) first: every join is a Mojang login, and too many got the owner locked out (HTTP 429).
Test offline with `./gradlew runClient` where you can, batch changes into one restart, join once, retry at most once.
