# Lensbridge user guide

Lensbridge turns Minecraft into a little film studio. It gives you:
- cameras you can place anywhere
- a drone you can fly
- a browser page to manage your shots
- one-key video recording
- a safe local connection that an AI assistant can use to take screenshots and shoot video for you

This guide is for players. It needs no coding.

---

## 1. Install

### Get Minecraft 1.21.11 with NeoForge
Pick the launcher you use:
- **Prism Launcher:** Add Instance → Minecraft **1.21.11** → Mod loader **NeoForge** → OK. Right-click the instance → **Folder** → open `mods`.
- **CurseForge app:** Minecraft → Create Custom Profile → **1.21.11**, **NeoForge** → Create. On the profile, **⋯** → **Open Folder** → `mods`.
- **Modrinth App:** **+** → **1.21.11**, **NeoForge** → Create. Instance → **⋯** → **Open folder** → `mods`.
- **Official launcher:** run the NeoForge 1.21.11 installer from neoforged.net → **Install client**, start the **neoforge** profile once, then open `.minecraft/mods` (`%APPDATA%\.minecraft\mods` on Windows, `~/Library/Application Support/minecraft/mods` on macOS, `~/.minecraft/mods` on Linux).

### Add Lensbridge
- Put `lensbridge-<version>.jar` in that `mods` folder (don't unzip it).
- Lensbridge only runs on **your** computer (the client). Servers don't need it, and it works on any server.

### Install ffmpeg (for video)
Stills work without it; videos need it.
- Windows: `winget install Gyan.FFmpeg` in Terminal
- macOS: `brew install ffmpeg`
- Linux: your package manager
- Restart Minecraft afterwards.

Start the game once. Lensbridge creates `config/lensbridge.json`; you don't need to edit it.

## 2. Your first five minutes

| Key | What it does |
|---|---|
| **Alt+T** | Place a camera (a "tripod") exactly where you're looking from |
| **Alt+N** | Look through your cameras one by one; keep pressing to cycle, and it ends back at you |
| **Alt+D** | Fly the drone camera |
| **Alt+R** | Start / stop recording a video |
| **Alt+C** | Open the **Camera Manager** in your web browser |
| **Alt+P** | Show or hide your cameras as props in the world |
| **Esc** | Get out of any camera mode and back to your own view |

You can change every key under **Options → Controls → Key Binds → Lensbridge Camera**. They all use **Alt** so they don't clash with map and minimap mods.

**Try this:**
1. Stand somewhere with a nice view and press **Alt+T**. That's camera 1.
2. Walk to another spot, look at the same thing, and press **Alt+T** again. That's camera 2.
3. Press **Alt+N** to look through camera 1, again for camera 2, and again to come back.
4. Press **Alt+C**. Your browser opens with both cameras and a little picture of what each one sees.

---

## 3. The Camera Manager (Alt+C)

A web page on your own computer (`http://127.0.0.1:25599/ui`) where you can see and adjust every camera.

**Each camera card shows:**
- a **thumbnail** of what the camera sees
- an **on** switch: only cameras that are on are used for "Shoot enabled" and multi-angle videos
- its **position and angle numbers** (x, y, z, yaw, pitch, fov), which you can type into directly
- **nudge buttons**: fwd/back, left/right, up/down, yaw, tilt, zoom. The thumbnail updates after each nudge.
- **✋ Take control**: fly this camera yourself (see below)
- **📷 Refresh**: re-take the thumbnail
- **👁 On screen**: look through this camera in game
- **Save my view here**: put the camera exactly where your view is right now
- **✕**: delete the camera

**At the top of the page:**
- **Group buttons:** cameras whose names start the same way (`hero_1`, `hero_2`...) form a group. Click a group to show only those, and use *Enable group* / *Disable group*.
- **Refresh thumbnails:** re-take all thumbnails in view.
- **Shoot enabled:** take a full-size picture from every enabled camera, plus one overview sheet.
- **Back to my view:** if you're looking through a camera, this returns you to your own eyes.
- **Show cameras in world** (also **Alt+P**): see your cameras as props in the world. Your shots stay clean.
- **Copy connect command:** copies the command that connects Claude Code (see section 8).
- **Help:** opens this guide.

### Taking control of a camera

The best way to get an angle exactly right:

1. Click **✋ Take control** on a camera card, then click back into the game.
2. You're now flying that camera:
   - **WASD** to move, **Space / Shift** for up and down
   - **Sprint** (Ctrl) to move faster; it's slow by default for fine adjustments
   - **Mouse** to look around
   - **Scroll wheel** to zoom (field of view)
3. Press **Enter** to save the new angle. Press **Esc** or **Backspace** to cancel and keep the old one.

The thumbnail updates on its own when you save.

---

## 4. The drone (Alt+D)

Press **Alt+D** and your view lifts off as a camera drone. Your character stays where it is.

- **WASD** to fly, **Space / Shift** to go up and down, **Sprint** for speed
- **Mouse** to turn and tilt the camera
- **Alt+D** or **Esc** to land and return to your own view

While flying, you can press **Alt+R** to record, or **Alt+T** to drop a camera at the drone's position.

---

## 5. Recording video (Alt+R)

- Press **Alt+R** to start and **Alt+R** to stop. The HUD hides while recording, so the video is clean.
- Videos are real time: 10 seconds of game is a 10-second video.
- Videos land in your instance folder under **`lensbridge/videos/`**. Screenshots go in `lensbridge/screenshots/`.
- Recording works from your own view, the drone, or any camera you're looking through.

---

## 6. Seeing your cameras in the world

Lensbridge can show your cameras as little props: each tripod as a camera on legs, the drone with spinning rotors. They exist only on your computer; servers and other players never see them. Your own shots stay clean: a camera never films the props unless you (or your assistant) make it a "crew" camera for behind-the-scenes footage. Press **Alt+P** (or **Show cameras in world** in the Camera Manager) to see them yourself.

## 7. Getting out of anything

**Press Esc.** In any camera mode (taking control, drone, looking through a camera), Esc takes you back to your own view instead of opening the pause menu. **Backspace** works too, and so does the **Back to my view** button in the Camera Manager.

---

## 8. Letting an AI assistant help

Lensbridge can be connected to an AI assistant such as Claude (it speaks "MCP"). The assistant can:
- take screenshots of the game to check how things look
- set up cameras, lighting and scenes
- record orbits, camera moves, drone shots and multi-angle videos
- adjust cameras in the Camera Manager with you

You see the same cameras in the Camera Manager, and you can take control of any of them to fine-tune the angle.

**Privacy and safety:**
- Lensbridge only listens on your own computer (`127.0.0.1`); nothing on the internet can reach it.
- Every request needs the secret **token** in `config/lensbridge.json`. Delete that file to get a new token.
- Screenshots only ever contain the **game's** picture, never your desktop or other windows.
- The assistant's cameras are invisible to you by default. They render behind the scenes, so your screen doesn't jump around. If you *want* to watch, the assistant can turn on preview.

Once connected, the assistant reads Lensbridge's own guide by itself (the `guide` tool), so you can just tell it what you want: "set up three cameras around my build", "orbit the dragon at sunset", "show me how my new mob looks".

The guides are also inside the mod: open the Camera Manager (Alt+C) and click **Help**, or go to `http://127.0.0.1:25599/docs`.

### Your secret key (token)

The first time it runs, Lensbridge makes a long random password called a **token** and saves it in `config/lensbridge.json` in your instance folder. Only programs that have it can use the connection, so another app or web page on your PC can't take over your game. Nothing on the internet can reach the connection at all.

- You only need it once, when you connect an assistant.
- Don't share it (or screenshots of the connect command).
- To make a new one, close Minecraft and delete `config/lensbridge.json`; a new token is created on the next start. Assistants you connected before will need the new one.

### Connect Claude Code

1. In game, press **Alt+C** to open the Camera Manager.
2. Click **Copy connect command**.
3. Paste it into a terminal and press Enter. That's it: start Claude Code and ask for a shot.

Other assistants that support MCP over HTTP: connect to `http://127.0.0.1:25599/mcp` with the header `Authorization: Bearer <token>`. To find the token yourself, open `config/lensbridge.json` in a text editor (Prism: right-click the instance → Folder → `config`) and copy the value after `"token":`, without the quotes.

## 9. Troubleshooting

| Problem | Fix |
|---|---|
| A hotkey does nothing | Check Options → Controls → Key Binds for a clash, and make sure you're holding **Alt**. |
| Alt+C doesn't open a browser | Open `http://127.0.0.1:25599/ui` yourself and add `#<token>` from `config/lensbridge.json` to the end of the address. |
| The Camera Manager says "unauthorized" | Open it with **Alt+C** (it adds the token for you). |
| I'm stuck looking through a camera | Press **Esc**. |
| Videos aren't created, only pictures | Install **ffmpeg** (see Install), or set its full path in `config/lensbridge.json` (`"ffmpeg": "C:/ffmpeg/bin/ffmpeg.exe"`). |
| A camera shows the wrong place or empty sky | Cameras only see the world near you (your render distance). Walk or fly closer to the area. |
| Thumbnails look washed out or too bright | That's the scene's lighting (bright sky, white blocks, noon sun, shader fog). Try darker blocks behind the subject, or evening light. |
| The game pauses when I click away | Lensbridge stops that by default (`keepRunningUnfocused`). Make sure it's `true` in the config. |

---

## 10. Config file (`config/lensbridge.json`)

| Setting | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Turn the local connection on or off |
| `port` | `25599` | Port of the local connection and the Camera Manager |
| `token` | random | Secret key; delete it to generate a new one |
| `captureDir` | `lensbridge` | Folder for screenshots and videos |
| `keepRunningUnfocused` | `true` | Don't pause or slow down when the window isn't focused |
| `stealthCapture` | `true` | Assistant cameras render behind the scenes (your screen doesn't change) |
| `ffmpeg` | `ffmpeg` | Path to ffmpeg for video |
| `previewMaxEdge` | `1280` | Size of the small previews sent to an assistant |
