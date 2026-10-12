# Architecture

One game runs inside one Minecraft. The same mod can instead show a running Civilization V;
both are the same to everything above one interface.

```
                       Minecraft 26.3 (Fabric), integrated server
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │  mod (server thread)                                                         │
 │    board painter · characters · Codex and menus · marks · day and turn       │
 │        ▲ WorldState (immutable picture)      │ commands (a fixed list)       │
 │        │ events, tickets                     ▼                               │
 │  ┌─────┴──────────────────────── GameLink ─────────────────────────────────┐ │
 │  │ UncivLink (engine/)                  │ CivLink (bridge/)                │ │
 │  │ own thread, a queue of work          │ own thread, one TCP connection   │ │
 │  │ Unciv's rules and AI, no window      │ to Civilization V's debug socket │ │
 │  │ saves to <world>/livingworld-civ.json│ (the older way, CIV5_BRIDGE.md)  │ │
 │  └──────────────────────────────────────┴──────────────────────────────────┘ │
 └──────────────────────────────────────────────────────────────────────────────┘
```

## The three modules

| Module | Holds | Knows about |
|---|---|---|
| `bridge` | The picture of a game (`state`), commands and tickets, hex coordinates, the board model that turns a picture into columns of blocks, the buildings (`Builds`, `Structure`, `CityPlan`), the link to Civilization V with its Lua, a pretend Civ for tests | Nothing of Minecraft, nothing of Unciv |
| `engine` | `UncivLink` and its helpers: start or load a game, publish a picture after every piece of work, carry out commands, list choices | `bridge` and Unciv |
| `fabric` | The mod: paints the board, shows characters, menus, the Codex, marks, time | `bridge`, `engine`, Minecraft |

## Who decides what

The game engine is the only authority on the strategy game: terrain, ownership, units, cities,
production, war, turns, and what the other civilizations do. The Minecraft side keeps no second
copy that could disagree: it holds the last `WorldState` and rebuilds what it shows from the
next one.

Minecraft decides only what is its own: where a player stands, what is in the inventory, and
what a player built by hand on the land (kept when the board is repainted, by remembering a
fingerprint of every column the board last painted).

A player's wish reaches the game in exactly one way: as one of a fixed list of commands
(`CommandOp`). Each gets a ticket, is carried out or refused by the engine's own rules, and the
ticket says which, in words for the player. What a menu offers is always a list the engine
produced a moment earlier (`ask`), never something the mod worked out.

## The engine inside the mod

Unciv is a complete game with a window. Here it runs without: `UncivBoot` sets up what it
expects to exist (a `UncivGame` in "console mode", settings, the two rule sets loaded from
files unpacked next to the world, a file system of its own so that incidental look-ups find
nothing instead of failing, no sound). `UncivLink` owns the game on one thread: everything
that reads or changes it is a job in its queue, and after every batch of jobs it publishes a
fresh immutable `WorldState`. The mod's server thread only ever sees those.

- `UncivSnapshot` turns Unciv's game into the same `WorldState` the Civilization V link
  produces, through the viewer's eyes: unexplored land, unmet civilizations and unseen units
  are left out.
- `UncivMap` lays Unciv's hexes (two diagonal axes) onto the board's rows and columns; a
  rectangular Unciv map fills the board exactly.
- `UncivActions` carries out commands with the calls Unciv's own buttons make, and lists
  choices; `UncivDiplomacy` asks Unciv's AI whether another civilization agrees.
- The other civilizations play when the player ends the turn: `GameInfo.nextTurn`, on the
  engine's thread. The game is saved after every turn and when the world closes.

Unciv is written in Kotlin; the adapter is Java. Where Kotlin's conveniences do not reach Java
(default arguments, `internal` functions, properties that share a name with a function) the
adapter spells things out; `Kt` and `Names` hold the few helpers.

## From a picture to blocks

`BoardModel.column(x, z)` says what one column of the world is made of, from the picture alone:
terrain by hex, hills and mountains, rivers along hex edges, forests, improvements, resources,
roads, borders, cities. It is pure and repeatable, which is what lets the painter repaint only
what changed and leave what players built.

A city is a `CityPlan`: the owner's era and building style choose a `Builds.Style` (what it
builds with), the city's buildings and wonders choose what stands on its eight lots, and each
of those is a `Structure`: a small block-by-block building designed front-to-south and turned
to face the hall. Stairs, slabs and pillars carry their direction in the cell, and turn with
the building. `Preview` (in the tests) draws any piece of the board as a picture.

## The rest of this page: the Civilization V link

What follows describes the older way in detail: two real games side by side and the one thing
that connects them.

```
 Civilization V (32-bit, unmodified)              Minecraft 26.3 (Fabric)
 ┌───────────────────────────────┐                ┌──────────────────────────────────┐
 │ game rules, map, units, turns │                │ integrated server                │
 │                               │                │  ┌────────────────────────────┐  │
 │ debug socket  TCP 4318  ◄─────┼── one TCP ─────┼──┤ bridge core (link thread)  │  │
 │   runs Lua in a UI context    │   connection   │  └──────────┬─────────────────┘  │
 │                               │                │   state ▲   │ events, tickets    │
 │ livingworld.lua (sent in at   │                │  ┌──────┴───▼─────────────────┐  │
 │ run time, never installed):   │                │  │ mod (server thread):       │  │
 │   tick / snap / cmd           │                │  │ board painter, characters, │  │
 │   marker on the map           │                │  │ menus, status, /civ        │  │
 └───────────────────────────────┘                │  └────────────────────────────┘  │
                                                  └──────────────────────────────────┘
```

## Who decides what

Civilization V is the only authority on the strategy game: terrain, ownership, units, cities,
production, war, turns. Nothing on the Minecraft side stores a second copy that could disagree;
it keeps the last picture Civ sent and rebuilds everything from the next one.

Minecraft decides only what is its own: where the player stands, what is in the inventory.

A Minecraft action reaches Civ in exactly one way: as one of ten fixed commands that pass a
check on this side, a second check inside Civ, and are then confirmed by reading Civ's state
back.

## The pieces

**Civ side: no installation.** With `EnableTuner = 1` in its config.ini, Civ opens the debug
socket that Firaxis' own FireTuner tool uses. The bridge sends `livingworld.lua` through it into
one of the game's UI contexts (`GenericWorldAnchor`) and calls four functions there. The script
is gone when the game closes or another game is loaded, and is sent again when needed. Civ's
files are not touched; there is no mod to enable and no DLL.

**Bridge core** (`bridge/`, plain Java). Runs on its own thread inside Minecraft's process.
It was meant to be a separate TypeScript service; it became a library inside the mod because
the PC has no Node and one process fewer is one thing fewer to start and keep alive. It has no
Minecraft classes, so all of it is tested without either game.

| Package | What it is |
|---|---|
| `coords` | Civ plot ⇄ continuous board position ⇄ Minecraft blocks ⇄ Civ world units |
| `state` | One immutable picture of the Civ game, and the diff between two pictures |
| `protocol` | The line format the Lua prints, and assembling lines into a picture |
| `link` | The loop: connect, load the script, heartbeat, fetch, diff, commands |
| `tuner` | The debug-socket client (framing, Lua states, one call) |
| `commands` | The ten commands, their checks, and their life from request to confirmation |
| `board` | The Civ map as columns of blocks, as a pure function of the picture; `CityPlan` is a city by era, style and buildings |
| `gameplay` | Which Civ technology a Minecraft item needs |
| `mock` | A pretend Civ game that speaks the same line format |
| `script` | The Lua resource |

**Mod** (`fabric/`). Server-side almost entirely: it paints the board into an empty world,
keeps unit and city characters in line with the picture, opens menus, shows status, and feeds
the player's position to the link. `Gameplay` holds what ties the two games together (menus
for governing, exploring, working the land); it only ever asks Civ for a list or submits one of
the commands. The only client code opens the world at start-up.

**Launcher** (`scripts/windows/LivingWorld.ps1`). Turns the debug socket on for one session,
starts the games, and puts Civ's settings back when Civ closes.

## Why events come from comparing pictures

Civ's own hooks cannot be relied on for the data itself: the game core offers few (nothing for
"unit created" or "war declared"), and the interface events that fill the gaps are documented as
"may not be fired when the UI does not need it". So the hooks are used only as hints: each bumps a counter in the heartbeat, and a changed counter makes the bridge fetch that
list again. The events the Minecraft side acts on (`UnitMoved`, `CityChangedHands`, ...) are the
difference between the previous picture and the new one. A slow regular re-read catches
anything no hook announced. The two sides cannot drift apart for longer than that interval.

## Coordinates

Everything is expressed in one shared space, `GridPos(u, v)`: `u` runs east in hex widths, `v`
north in rows; the centre of plot (x, y) is at `u = x + 0.5·(y odd)`, `v = y`. Minecraft blocks
and Civ's world units are each a scale and an offset away from it. The hex spacing and which
rows are shifted are measured from the running game at connect time (the engine's own
`GridToWorld` for four plots) and never assumed. Measured on the real game: 64 world units per
column, 55.4256 per row, odd rows shifted east.

The Minecraft scale (`hexWidth`, default 32 blocks) only changes where blocks go; plot and
entity identities do not depend on it.

## Turn-based meets real-time

The player walks in real time. Civ changes when turns are played. Commands are queued and only
sent during the player's own turn in Civ, which is the moment its rules expect the player to
act; while the AI is moving they wait.

## Identity

A unit or city is `(owner, Civ's id)`, never its name. The session has two identities: `gameId`
(a hash of the map and the player's civ, the same after save and load) and `bootId` (new every
time the script is loaded into the game). A command carries the `bootId` it was made in and is
refused in any other, so nothing queued for one game can land in another. The world remembers
which `gameId` it last showed: the same game keeps the player's position, a different one sends
the player to the capital.
