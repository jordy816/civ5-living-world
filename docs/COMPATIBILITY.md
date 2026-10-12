# Compatibility and what is changed on the PC

## The game inside Minecraft

Needs Minecraft 26.3 with Fabric and Java 25, and nothing else: no Civilization V, no network,
no second program. Everything it writes is inside the Minecraft instance:

| Where (in the instance's `.minecraft`) | What |
|---|---|
| `mods/civ5-living-world-*.jar` | The mod, with the game engine inside (about 20 MB) |
| `config/livingworld.properties` | The settings, with a comment on each |
| `saves/Civ V Living World/livingworld-civ.json` | The game, saved after every turn. `livingworld-civ.previous.json` is the game before the last "Start a new game" |
| `saves/Civ V Living World/livingworld-engine/` | The engine's rule files, unpacked from the mod at every start |
| `saves/Civ V Living World/livingworld-painted.bin` | What the board last painted, so that what you built stays |

It asks for no more memory than a normal modded instance: a small map's whole game is some
tens of megabytes. Run on a server it would work the same way (the mod is server-side but for
opening the world by itself), though that has not been tried.

The rest of this page is about the older way, a running Civilization V next to Minecraft
(CIV5_BRIDGE.md), which does change settings outside the instance for the length of a session.

## Tested with

| | Version |
|---|---|
| Civilization V | 1.0.3.279, Brave New World, Steam, DirectX 11 exe, Windows 11 |
| Minecraft | 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, Java 25 |
| Launcher for Minecraft | Prism Launcher 11.1.1 (the copy that comes with SkyCraft) |

Other Civ V mods: the script only uses stock Lua calls and falls back for anything missing, so
a modded single-player game should work, with unknown terrain or improvements shown as defaults.
Not tested. Vox Populi is not needed and not used.

Other Minecraft versions: the mod uses 26.3 names directly (mannequin entities, server dialogs,
colour collections). It will not load on 1.21.x without changes.

## Requirements

- Single-player Civ game. Multiplayer is impossible by Firaxis' design: the debug socket is
  switched off when a multiplayer game starts.
- Achievements are off in sessions started through the launcher (the game's rule for the debug
  socket). Normal starts through Steam are unaffected.
- Both games on the same PC.

## What is on the PC

| Where | What | Remove by |
|---|---|---|
| `D:\CivLivingWorld\` | Launcher, README, its log, `backup\` (your original Civ settings during a session), `dev\shot.ps1` | Deleting the folder |
| Start menu, `Civ V Living World` | Shortcut to the launcher | Deleting the shortcut |
| Desktop, `Civ V Living World` | A copy of that shortcut (added 2026-10-10 on request) | Deleting the shortcut |
| `%LOCALAPPDATA%\SkyCraft\Prism\instances\CivLivingWorld\` | The Minecraft instance: this mod, Fabric API, its world and config | Deleting the folder |

Nothing is installed into Civilization V. SkyCraft's own instance is not changed; the new
instance sits next to it and uses the same Prism, account, Minecraft and Java.

## What the launcher changes, and puts back

For the length of one session, in `Documents\My Games\Sid Meier's Civilization 5`:

| File | Setting | During a session |
|---|---|---|
| `config.ini` | `EnableTuner` | `1` |
| `GraphicsSettingsDX11.ini` | `FullScreen`, `WindowResX`, `WindowResY` | `0`, 1920, 1080 |
| `UserSettings.ini` | `SkipIntroVideo` | `1` |

The originals are copied to `D:\CivLivingWorld\backup` first. A hidden watcher process copies
them back about 20 seconds after Civ has closed (seen working on 2026-10-09, each time Civ was closed). If the
PC loses power mid-session, the next start of the launcher restores them first; or run
`LivingWorld.ps1 -Action restore`.

The launcher starts `CivilizationV_DX11.exe` directly with `SteamAppId=8930` in its
environment. Without that, Steam restarts the game as the DirectX 9 version.

## What a session changes in your Civ game

A Living World session plays in your real Civ game, and what you do there is saved with it like
anything you do in Civ's own window: a chosen production or research, a gift, a declared war.
Three things could not happen in a plain Civ game and are worth knowing before you load a save
you care about:

- Land is revealed where you walked, without a unit having been there.
- Improvements, roads and cleared forest appear at once, without a Worker.
- Hammers are added to a city and damage removed from a unit when you deliver logs or bread.

With the default settings a turn also ends by itself at sunset (ten minutes), when Civ has no
open question for you. Each of these has a switch in `config/livingworld.properties` of the
instance (`explore`, `dayIsTurn`); delivering, healing and land work only happen when you ask.
Use a new game or a copy of a save for the first sessions.

## The debug socket and the network

While a session runs, Civ listens on `0.0.0.0:4318`, all interfaces, with no authentication.
Steam created Windows Firewall rules that allow inbound connections to `CivilizationV_DX11.exe`
on all profiles (for multiplayer), and those rules cover this port too. Without anything else,
any device on the LAN could connect to it during a session and run Lua in the game.

On the gaming PC that is closed since 2026-10-10 by one firewall rule, added with the owner's
yes:

```powershell
New-NetFirewallRule -DisplayName "Civ V debug socket: local only" -Direction Inbound `
  -Protocol TCP -LocalPort 4318 -RemoteAddress Any -Action Block
```

A block rule wins over Steam's allow rules. Connections from the PC itself are not affected:
the mod reached the game over loopback all through the session that followed. Remove it with
`Remove-NetFirewallRule -DisplayName "Civ V debug socket: local only"`. On another PC, add the
same rule.
