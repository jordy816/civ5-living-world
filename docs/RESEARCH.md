# Research

What was looked at before building, and what was measured on the real games afterwards. Source
references are to the repositories as they were on 2026-10-08.

## Decision in one paragraph

The Civ side talks to the game's own debug socket and installs nothing. Vox Deorum was the
suggested foundation and was rejected for this PC. The Minecraft-in-Elden-Ring projects were
studied for rendering and none of their host-side code carries over; what they do is out of
reach for Civ V's engine without long reverse engineering. So the direction of the picture was
turned around: Minecraft shows Civ's world, and Civ shows where the player is.

## Vox Deorum (github.com/vox-deorum/vox-deorum)

A real and active project (2,400+ commits) that lets external software drive Civ V through a
modified game-core DLL, a named pipe, and a Node bridge with REST and server-sent events.

Why it is not used here:

- Its installer overwrites `lua51_win32.dll` in the Steam folder with no backup and no restore
  on uninstall (`scripts/install.cmd:406-419`).
- It installs two interface packs into `Assets\DLC` (`VPUI`, `UI_bc1`) that load in every game,
  including unmodded and multiplayer ones.
- It requires the full Vox Populi overhaul, which changes the game's rules, and brings 266 MB
  including a Node runtime.
- Its Lua endpoint runs arbitrary code with no allowlist and no authentication.

That breaks the requirement that existing mods, saves and normal play stay untouched. A leaner
"only its DLL" route looks possible from the code, but the project neither ships nor documents it.

Worth knowing from its code: the unmodified game core has few hooks (`UnitSetXY`,
`PlayerDoTurn`, `CityCaptureComplete`, ...), and Vox Deorum's own fork adds the rest. Its
scripts show that a game can be started from Lua without clicks.

## The debug socket (FireTuner)

Civ V contains a debug listener that Firaxis' FireTuner tool connects to. Open-source clients
for the Linux and macOS ports documented the framing; nothing was found for the Windows build,
so that was verified here (see [PROTOCOL.md](PROTOCOL.md)).

Measured on Windows, 1.0.3.279, DX11 exe, 2026-10-09:

| Question | Answer |
|---|---|
| Does it exist without the SDK installed? | Yes. Listening within about a minute of launch. |
| Bind address | `0.0.0.0:4318`, all interfaces (see COMPATIBILITY.md) |
| Lua states | 48 in the main menu, 130 in a game |
| Can it start a game without clicks? | Yes: `PreGame.*` then `Events.SerialEventStartGame()` in the `MainMenu` state, then `OnActivateButtonClicked()` in `LoadScreen` |
| Round trip for one small call | 7 to 13 ms through an SSH tunnel |
| Size of the script as sent | 22 pieces of 800 characters, loaded in about a second |
| Second client | Not answered, and afterwards nothing was answered for several minutes (two occasions); restarting Civ fixed it |
| Starting the exe directly | Steam restarts it as the DirectX 9 version unless `SteamAppId=8930` is in the environment |

Not available through it, by Firaxis' design: multiplayer (the listener is switched off when a
multiplayer game starts), and achievements while it is enabled.

## Lua inside Civ V

- Every interface context has the whole game API and can change game state; there is no
  separate gameplay state as in Civ VI.
- A world-anchored control takes a free 3D position (`SetWorldPositionVal`), which the engine
  keeps in place as the camera moves. That is what unit flags and city banners use, and what
  the player's marker uses.
- Without a mod, only controls that an existing context already defines can be created. The
  marker reuses the settler-recommendation icon of `GenericWorldAnchor`, with the portrait of
  the player's own leader set on it.
- The camera can be told to look at a plot (`UI.LookAt`), pan and zoom in steps. No call sets a
  free position, and there is no getter. Pitch and rotation exist only as debug-menu events.
- Colours: `GetPlayerColors()` returns (icon colour, background colour) for a civilization and
  the reverse for a city-state; borders and flags use the background.

## The Minecraft-in-another-game projects

| | minecraft-crossover-bridge | Minecraft-Ring | EldenCraft | SkyCraft |
|---|---|---|---|---|
| Host | Elden Ring, Monster Hunter (macOS, CrossOver) | Elden Ring (Windows) | Elden Ring (Windows) | Skyrim (Windows) |
| Minecraft | 1.21.1 | 1.21.1 | 26.3 | 26.3 |
| Picture | finished frames, CPU | finished frames, shared GPU textures | frames plus baked geometry | geometry and atlas |
| Host camera | fixed engine addresses | fixed engine addresses | pinned addresses with fingerprints | engine structs via CommonLibSSE |
| Licence | MIT | MIT | MIT | MIT |

All four are real, working-looking code. Every one of them gets the host's camera from
engine-specific memory, and three of the four are 64-bit D3D12. Civ V is a 32-bit process with
a closed engine and no camera access. Between half and nine tenths of each native side is glue
for its own host.

What was taken from them is protocol hygiene, not code: session identity in every message,
sequence counters, version checks, "a list is whole or it is not used", and stopping a feature
on any doubt instead of guessing. From SkyCraft specifically (MIT): the empty-world preset and
skipping the experimental-world prompt.

## On Rust

Asked at the start: are agents not good at Rust? Not the lever here. The languages are set by
the games: Lua inside Civ, Java inside Minecraft. Rust would only come in for a native piece
injected into Civ, and the design has none.
