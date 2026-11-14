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
| **Alt+X** | Remove a camera: the one you are looking through, else one placed right where you are, else the one you are looking at (nothing in the way), else the nearest one within 5 blocks. The message says which one and why |
| **Alt+Backspace** | Panic key: stops any capture or recording that is running, brings the HUD back, and returns you to your own view |
| **Esc** | Get out of any camera mode and back to your own view |

You can change every key under **Options → Controls → Key Binds → Lensbridge Camera**. They all use **Alt** so they don't clash with map and minimap mods.

**Try this:**
1. Stand somewhere with a nice view and press **Alt+T**. That's camera 1.
2. Walk to another spot, look at the same thing, and press **Alt+T** again. That's camera 2.
3. Press **Alt+N** to look through camera 1, again for camera 2, and again to come back.
4. To take one away, look at it (**Alt+P** shows where they are) or stand next to it and press **Alt+X**. `/lensbridge cameras` lists them all with a **[remove]** button each, `/lensbridge cameras remove all` clears them, and `/lensbridge cameras undo` brings back the last removal.
5. Press **Alt+C**. Your browser opens with both cameras and a little picture of what each one sees.

**Cameras belong to the world you placed them in.** Alt+N, Alt+X, `/lensbridge cameras`, the props and the Camera Manager show only the cameras of the world (or server) and dimension you are in. Cameras from before this was added have no world on record and show up everywhere, so nothing disappears. Names are still shared between worlds: a new `tripod_3` won't reuse a name that is in use somewhere else. With camera props hidden (Alt+P off), Alt+X only picks a looked-at camera within 16 blocks; with props shown, within 48.

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
- **Save my view here**: put the camera exactly where your view is right now (your own eyes, or the camera or drone you are looking through)
- **✕**: delete the camera

**Delete group** (at the top, once a group is selected) removes every camera in that group in one go.

The page keeps itself up to date: a camera you place, move or remove in game (Alt+T, Alt+X) appears or disappears here within a couple of seconds. A removed camera's thumbnail is deleted with it; a camera brought back with `/lensbridge cameras undo` has no thumbnail until you click **📷 Refresh**. If a camera was removed while its card was still open, the card's buttons say so instead of bringing it back.

**At the top of the page:**
- **Group buttons:** cameras whose names start the same way (`hero_1`, `hero_2`...) form a group. Click a group to show only those, and use *Enable group* / *Disable group*.
- **Refresh thumbnails:** re-take all thumbnails in view.
- **Shoot enabled:** take a full-size picture from every enabled camera, plus one overview sheet.
- **Back to my view:** if you're looking through a camera, this returns you to your own eyes.
- **Show cameras in world** (also **Alt+P**): see your cameras as props in the world. Your shots stay clean.
- **Copy connect command:** copies the command that connects Claude Code (see section 9).
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

- Press **Alt+R** to start and **Alt+R** to stop. A **● REC** line with the running time shows at the top left while it records.
- The video shows what you see, HUD included. For a clean video press **F1** (hide HUD) before Alt+R; the REC line is then hidden too, and the chat message at the end tells you where the video went.
- If the last recording is still being saved, Alt+R says so; try again a moment later. If a recording fails, chat says why in red.
- Videos are real time: 10 seconds of game is a 10-second video.
- Videos land in your instance folder under **`lensbridge/videos/`**. Screenshots go in `lensbridge/screenshots/`.
- Recording works from your own view, the drone, or any camera you're looking through.

---

## 6. Seeing your cameras in the world

Lensbridge can show your cameras as little props: each tripod as a camera on legs, the drone with spinning rotors. They exist only on your computer; servers and other players never see them. Your own shots stay clean: a camera never films the props unless you (or your assistant) make it a "crew" camera for behind-the-scenes footage. Press **Alt+P** (or **Show cameras in world** in the Camera Manager) to see them yourself. With Jade installed, looking at one shows its name (for example *Lensbridge camera: hero_1*).

## 7. For modmakers: Showcase Stages

Ask your assistant to "make a showcase of my mod". Lensbridge reads your mod's mobs, blocks and items, builds a clean stage for them high in the sky (held still, on a grey backdrop), photographs every one, and saves the pictures, a contact sheet and an index page in `lensbridge/showcase/<your mod>/`. It can also make a 360° turntable of every mob. Needs cheats (or op on a server). The stage is built just under the sky limit and never on top of anything: if the space isn't empty, it stops and says so. When you're done, ask it to clear the stage; that removes it completely.

## 8. Getting out of anything

**Press Esc.** In any camera mode (taking control, drone, looking through a camera), Esc takes you back to your own view instead of opening the pause menu. **Backspace** works too, and so does the **Back to my view** button in the Camera Manager.

---

## 9. Letting an AI assistant help

Lensbridge can be connected to an AI assistant such as Claude (it speaks "MCP"). The assistant can:
- take screenshots of the game to check how things look
- set up cameras, lighting and scenes
- record orbits, camera moves, drone shots and multi-angle videos
- adjust cameras in the Camera Manager with you

You see the same cameras in the Camera Manager, and you can take control of any of them to fine-tune the angle.

**Privacy and safety:**
- Lensbridge only listens on your own computer (`127.0.0.1`); nothing on the internet can reach it.
- Every request needs the secret **token** in `config/lensbridge.json`. Delete that file to get a new token.
- If you also installed **Agentlink** (a bridge other mods can plug into), Agentlink carries the connection instead and the
  token is in `config/agentlink.json`. The first time, it copies your Lensbridge token, so nothing needs reconnecting.
- Screenshots only ever contain the **game's** picture, never your desktop or other windows.
- The assistant's cameras are invisible to you by default. They render behind the scenes, so your screen doesn't jump around. If you *want* to watch, the assistant can turn on preview.

Once connected, the assistant reads Lensbridge's own guide by itself (the `guide` tool), so you can just tell it what you want: "set up three cameras around my build", "orbit the dragon at sunset", "show me how my new mob looks".

The guides are also inside the mod: open the Camera Manager (Alt+C) and click **Help**, or go to `http://127.0.0.1:25599/docs`.

### Your secret key (token)

The first time it runs, Lensbridge makes a long random password called a **token** and saves it in `config/lensbridge.json` in your instance folder. Only programs that have it can use the connection, so another app or web page on your PC can't take over your game. Nothing on the internet can reach the connection at all.

- You only need it once, when you connect an assistant.
- Don't share it (or screenshots of the connect command).
- To make a new one, close Minecraft and delete `config/lensbridge.json`; a new token is created on the next start. Assistants you connected before will need the new one.

### Ask from inside the game: `/agent`

Tell your AI "listen for /agent". Then type **`/agent <anything>`** in game, for example `/agent what's this block?`
or `/agent why is it lagging here?`. Your AI gets the request with where you are and what you're looking at, and
answers in your chat as **[AI]**. `/agent` on its own shows whether your AI is listening. Requests go from your game
straight to your AI and never to the server; answers show on your screen only. If the AI takes over a camera to
show you something, **Esc** or **Backspace** puts you back in your own view. A request stays in the inbox until
your AI has confirmed it received it, so one sent while the AI was between two checks is not lost.

### When something is stuck: Alt+Backspace and `/lensbridge restore`

- **Alt+Backspace** (panic key) stops any capture, render or recording that is running, shows the HUD again, lets go
  of keys an AI was holding, and puts you back in your own view.
- **`/lensbridge restore`** undoes what a shoot left changed: it unfreezes the world if Lensbridge froze it, puts the
  day cycle (`advance_time`) and shaders back as they were, and returns the window to its old size or fullscreen. Your
  AI can do the same with its `restore` tool. Time of day and weather are not put back.
- Leaving a world, respawning or changing dimension returns you to your own view and lets go of held keys; leaving a
  world also stops a recording and closes the inset.
- An AI can never confirm an Agentlink action for you: `/agentlink confirm` and `/agentlink cancel` are refused when
  an AI tries to send them, and it cannot click in your chat. You click Confirm yourself.

### Keep an eye on someone: `/lensbridge inset <player>`

A small live picture of another player (or a named mob, or the nearest mob of a type such as `villager`) appears
in the corner of your screen, following them, while you keep playing your own view.

| Command | What it does |
|---|---|
| `/lensbridge inset <player>` | Corner picture from behind them (the chase view) |
| `/lensbridge inset <player> front` / `overhead` / `eyes` | Their face, from above, or exactly what they see |
| `/lensbridge inset full` | That camera becomes your whole screen. **Esc** (or `/lensbridge inset small`) goes back to the corner picture |
| `/lensbridge inset <player> eyes full` | Start straight in full view, through their eyes |
| `/lensbridge inset off` | Close it |

- They have to be near you: your game only has players and terrain within your view distance. For someone far
  away, staff on a server with Agentlink run `/agentlink watch <player> near` first (you go there, invisible), or
  `/agentlink watch <player>` to see through their eyes from the server's side.
- The picture is smooth (30 a second). Each picture is one hidden extra frame, so your own view shows that many
  fewer while the corner picture is open. Full view has no such cost.
- The camera never sits inside a wall, a roof or leaves. If straight behind them is blocked it goes to the side;
  in a space too tight for any camera (a one-block tunnel) it looks through their eyes.
- Somewhere dark (a cave, at night), the watching camera is lit as with night vision. Your own view isn't changed.
  Shader packs that do their own lighting ignore this.
- It follows the free-camera rules above and pauses while Lensbridge records (full view doesn't: you can record it).

### Connect your AI

In game, type **`/lensbridge connect`** and click the button for your app:

- **[Claude Code]**: paste into a terminal and press Enter.
- **[Claude Desktop]**: paste into Settings → Developer → Edit Config, then restart Claude (needs Node.js).
- **[Cursor, VS Code, other apps]**: paste into the app's MCP settings.

You do this once; it keeps working in singleplayer and on every server. (The Camera Manager, **Alt+C**, also has a
**Copy connect command** button for Claude Code.) The token is only inside the buttons, never in the chat text, so
it doesn't end up in logs or screenshots.

Other assistants that support MCP over HTTP: connect to `http://127.0.0.1:25599/mcp` with the header `Authorization: Bearer <token>`. To find the token yourself, open `config/lensbridge.json` in a text editor (Prism: right-click the instance → Folder → `config`) and copy the value after `"token":`, without the quotes.

## 10. Playing on servers

Lensbridge keeps to a server's rules by default, because two of its features would be unfair in multiplayer:

- **Free cameras** (the drone, looking through your cameras, AI director shots) can see through walls.
- **Letting an AI act for you** (moving, looking, pressing keys, clicking menus, chatting, running commands) is a bot, as far as most servers are concerned.

| Where you are | Free cameras | AI acting for you |
|---|---|---|
| Singleplayer, or hosting a LAN world | Yes | Yes |
| A server without Lensbridge | If you're an operator, or in spectator mode | If you're an operator |
| A server with Lensbridge installed | Whatever the server owner set | Whatever the server owner set |

Screenshots of your own view, recording what you see, placing cameras and the Camera Manager work everywhere. If you lose permission while using a camera (say an operator removes your op status), you're put back in your own view.

### For server owners

Put the same Lensbridge `.jar` in the server's `mods` folder. Players without Lensbridge can still join. After the first start, edit `config/lensbridge-server.toml`:

```toml
freeCameras = "OPS"   # OPS (operators and spectators), EVERYONE or NOBODY
aiControl = "OPS"     # OPS, EVERYONE or NOBODY
```

Changes apply within a few seconds, without a restart. With a permission mod such as **LuckPerms** you can also allow or deny it per player or group with the permission nodes `lensbridge.free_cameras` and `lensbridge.ai_control`. Commands an AI runs still need their normal permissions either way.

### Your server's tools for your staff's AI

With **Agentlink** on the server too, staff can ask their own AI about the server: health and lag, who's online
and what they carry, any installed mod's items and recipes, and live chat, joins, deaths and commands. Staff
need only Lensbridge on their own PC, connected to their AI as usual. The calls travel over their game
connection, so you don't open a port or hand out a token. Their AI uses `server_tools`, `server_call` and
`server_events`. Staff set up once with `/lensbridge connect` (see §9); `/agentlink connect` on the server points
players there too.

```toml
serverAgent = "OPS"          # who: OPS, EVERYONE or NOBODY
serverAgentLevel = "READ"    # what: READ (look only), MODERATE (message, broadcast, mute, kick, whitelist),
                             #       ADMIN (also ban and unban)
```

With a permission mod, the string node `lensbridge.server_agent` gives a player `none`, `read`, `moderate` or
`admin`. A ban never happens on the AI's word alone: the staff member gets **[Confirm]** / **[Cancel]** buttons in
their chat. Kicks, bans and whitelist changes run the normal commands, so ops see the usual notices. The server checks every call and writes it to `agentlink/audit.log` under the staff member's name.
Changes apply within a few seconds.

## 11. Troubleshooting

| Problem | Fix |
|---|---|
| A hotkey does nothing | Check Options → Controls → Key Binds for a clash, and make sure you're holding **Alt**. |
| Alt+C doesn't open a browser | Open `http://127.0.0.1:25599/ui` yourself and add `#<token>` from `config/lensbridge.json` to the end of the address. |
| Alt+C or `/lensbridge connect` says the connection couldn't start | Another program (or a second Minecraft) is using the port, the connection is turned off, or Agentlink isn't listening. The message says which. For a port clash, change `port` in `config/lensbridge.json` and restart the game. |
| The HUD is gone, the screen is frozen or I'm in a camera I didn't ask for | Press **Alt+Backspace**. |
| The world is still frozen, time stands still, shaders are off or the window is a strange size after an AI shoot | Type `/lensbridge restore`. |
| A camera I placed is missing | Cameras show only in the world and dimension they were placed in. `/lensbridge cameras` says how many are elsewhere. |
| The Camera Manager says "unauthorized" | Open it with **Alt+C** (it adds the token for you). |
| I'm stuck looking through a camera | Press **Esc**. |
| Videos aren't created, only pictures | Install **ffmpeg** (see Install), or set its full path in `config/lensbridge.json` (`"ffmpeg": "C:/ffmpeg/bin/ffmpeg.exe"`). |
| A camera shows the wrong place or empty sky | Cameras only see the world near you (your render distance). Walk or fly closer to the area. |
| Thumbnails look washed out or too bright | That's the scene's lighting (bright sky, white blocks, noon sun, shader fog). Try darker blocks behind the subject, or evening light. |
| Captures were stale or the view flashed while the game was in the background (Dynamic FPS installed) | Fixed: while Lensbridge captures, records or shows an inset, it asks Dynamic FPS to keep drawing frames. The rest of the time Dynamic FPS saves power as usual. |
| The game pauses when I click away | Lensbridge stops that by default (`keepRunningUnfocused`). Make sure it's `true` in the config. |

---

## 12. Config file (`config/lensbridge.json`)

| Setting | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Turn the local connection on or off |
| `port` | `25599` | Port of the local connection and the Camera Manager |
| `token` | random | Secret key; delete it to generate a new one |
| `host` | `127.0.0.1` | Leave it. Any other address opens the connection to your network; the log warns when it isn't this computer only |
| `captureDir` | `lensbridge` | Folder for screenshots and videos |
| `keepRunningUnfocused` | `true` | Don't pause or slow down when the window isn't focused |
| `stealthCapture` | `true` | Assistant cameras render behind the scenes (your screen doesn't change) |
| `ffmpeg` | `ffmpeg` | Path to ffmpeg for video |
| `previewMaxEdge` | `1280` | Size of the small previews sent to an assistant |

If `config/lensbridge.json` is empty or unreadable, Lensbridge starts with the defaults (and a new token) and keeps the old file as `lensbridge.json.broken`. The camera list (`config/lensbridge/tripods.json`) is treated the same way: unreadable cameras are skipped, the rest are kept, and the damaged file is copied to `tripods.json.broken-<time>` first.
