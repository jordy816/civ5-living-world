# Protocol

Version 2 (`Wire.PROTOCOL`). Two layers: the game's debug socket, and on top of it the calls
and records of `livingworld.lua`.

## Debug socket (Civilization V, TCP 4318)

Open while the game runs with `EnableTuner = 1`. Verified on the Windows build 1.0.3.279.

```
frame   := uint32 LE payload length | int32 LE tag | payload, NUL terminated
tag 4   "APP:"                 -> the game identifies itself
tag 4   "LSQ:"                 -> its Lua states as NUL separated (id, name) pairs
tag 3   "CMD:<state id>:<lua>" -> run Lua in that state
replies tag -1 "O\0<text>"     one per print() line, prefixed "<Context>: "
        tag 3  ""              the command finished
        tag 3  "ERR:..."       the command raised an error
```

Facts that shape everything above it:

- **One client, and it must leave with a reset.** Measured on the real game on 2026-10-10: five
  clients in a row that each left with a TCP reset (`SO_LINGER` 0) were all answered within half
  a second. After one client that closed politely (FIN), the next got no answer in 90 seconds; in
  other runs not in five minutes, and each abandoned attempt then sits in the game's queue ahead
  of the next one. Windows showed Civ's side of the politely closed connection still open
  (`CloseWait`) a minute later. So `TunerTransport` sets `SO_LINGER` 0 when it connects: the
  connection is reset however it ends, also when the process is killed or crashes. With that,
  Minecraft reconnected at once after a normal exit and after being killed outright. Every
  "the socket is stuck, restart Civ" seen before this came from a polite close.
- **A reset does not cross an SSH tunnel.** `sshd` closes its end to the game politely. Run the
  command-line tool on the PC itself (`scripts/dev/pc-cli.sh`), not through `ssh -L`.
- **A second connection while one is active** is not answered. One client at a time.
- **Commands must be short.** Long ones are silently dropped (between 1 and 2 KB on other ports
  of the game); the bridge refuses to send more than 1400 bytes and sends the script in 800
  character pieces.
- **One `print()` carries about 4 KB.** Longer records are split (see `P`).
- **Output is asynchronous.** The "finished" frame can arrive before the output, so every reply
  of ours ends with a marker line and the client reads until it sees it.
- **State ids change on every load.** The state is looked up by name; the newest wins.
- Tabs and backslashes in printed text arrive intact (checked).

## Calls (bridge → Civ)

After the script is loaded, the bridge sends only these, with numbers and quoted strings as
arguments. `seq` is echoed in the reply markers.

```lua
LivingWorld.tick(seq, u, v, plotX, plotY, show, follow, label)   -- heartbeat + marker, 10/s
LivingWorld.snap(seq, mask)       -- 1 geometry+types+civs, 2 plots, 4 units, 8 cities, 16 civs, 32 empire
LivingWorld.opts(seq, kind, a, c) -- what Civ offers right now: "build" (city a), "research", "policy", "works" (plot a,c)
LivingWorld.cmd(seq, id, op, owner, entity, x, y, amount)        -- op: see Commands
```

`opts` is a question, not a command: it changes nothing and its answer is a list of `O` records.
The menus in Minecraft are built from that list, so they never offer what Civ would refuse.

`tick` is wrapped so that a game without (exactly this copy of) the script answers
`LW B 0` instead of raising an error; that is how the bridge notices a reload and sends the
script again. The copy is identified by a hash of its source, so a changed script replaces an
older one that is still loaded.

## Records (Civ → bridge)

One per line, fields separated by tabs, every line starting with `LW<tab>`. Tab, newline,
carriage return and backslash inside a field are escaped with a backslash. Lines that do not
start with the prefix are ignored (other scripts print on the same channel).

| Tag | Fields |
|---|---|
| `>` | `seq`: start of the reply to that call |
| `<` | `seq count`: end of the reply |
| `B` | `0`: the script is not loaded |
| `J` | `ok` or an error text: result of loading the script |
| `S` | `protocol bootId gameId turn viewer viewerTeam width height wrapX unitsRev citiesRev plotsRev civsRev viewerTurn` |
| `G` | world x,y of plots (0,0) (1,0) (0,1) (0,2) |
| `N` | `kind count`: the next `count` records of that kind are one complete list |
| `L` | `kind id type name`: kind T terrain, F feature, I improvement, R resource, O route |
| `V` | `id team type name adjective leader flags colour accent era artStyle` |
| `P` | `y x0 cell;cell;...`: a cell is `?` (never seen) or `terrain,feature,elevation,owner,improvement,resource,route,rivers,flags` |
| `U` | `owner id type name x y hp maxHp moves maxMoves strength ranged domain level xp flags` |
| `T` | `owner id name x y population flags hp maxHp prodType prodName prod prodNeeded prodTurns food foodNeeded strength buildings wonders` (the last two comma separated, the viewer's own cities only) |
| `Y` | `gold goldPerTurn science happiness culture culturePerTurn nextPolicyCost goldenAgeTurns era year researchType researchName researchTurns policies blocking`: the viewer's empire. `policies` counts policies adopted plus branches opened; `blocking` is what stops the Next Turn button (`POLICY`, `RESEARCH`, `PRODUCTION`, `UNITS`, ..., `NEWS` for a "Continue" screen), empty when nothing does |
| `K` | technologies the viewer's team knows, comma separated without `TECH_`; split over several records when long |
| `M` | `civ minor influence standing word ally quests giftSmall giftMedium giftLarge`: how a met civilization or city-state stands towards the viewer |
| `O` | `group id type name turns`: one thing Civ offers; group 0 unit, 1 building, 2 project, 3 process, 4 land work, 5 policy branch to open, 6 policy to adopt (research uses 0). Names are plain words: Civ's markup (`[LINK=...]`, `[ICON_...]`) is taken out |
| `E` | `number type args...`: one of Civ's own hooks fired (a hint, for logs) |
| `R` | `commandId status code message key=value...`: status 1 = applied |
| `X` | `where message`: the script caught an error |

A list only replaces the previous one once all `count` records have arrived. A dropped or
malformed line can make the picture older, never half-updated.

Flags: civ 1 alive, 2 city-state, 4 barbarians, 8 human, 16 at war with the viewer, 32 met.
Plot 2 visible now, 4 city, 8 water, 16 lake, 32 pillaged; rivers 1 east edge, 2 south-east,
4 south-west. Unit 1 embarked, 2 fortified, 4 combat, 8 garrisoned, 16 great person. City
1 capital, 2 puppet, 4 occupied, 8 razing, 16 resistance, 32 coastal, 64 details included.
`M` standing: for a city-state 0 neutral, 1 friend, 2 ally; for a civilization bits 1 declared
friendship, 2 denounced by the viewer, 4 the viewer may declare war. `word` is the city-state's
trait or the civilization's apparent attitude, as Civ's own diplomacy screen guesses it.

## Fog of war

Enforced inside Civ, by the script: a plot the active team has not revealed is sent as `?`, a
unit is sent only when the active team can see its plot right now, a city only when its plot is
revealed, and production and food only for the viewer's own cities. A civilization not yet met
is sent as "Unmet civilization", and relations only for those met. The empire and technology
records are the viewer's own. What the player's civ does not know never leaves the game.

One command works against the fog on purpose: `reveal` uncovers plots, as a scout standing
there would.

## Commands

| Op | Does | Checked here | Checked in Civ | Confirmed when |
|---|---|---|---|---|
| `move` | The unit gets a move-to order through the game's own right-click path | own unit, plot on map, revealed, not a mountain, not water for a land unit | own unit, alive, plot revealed, player's turn, not multiplayer | the unit is closer to the plot than before |
| `prod` | Adds hammers to what a city is building | own city, 1..60, building something | same, plus not multiplayer | the city's production in a later picture is at least the value Civ reported |
| `heal` | Removes damage from a unit | own unit, 1..50, wounded | same | the unit's health is at least the value Civ reported |
| `turn` | Ends the player's turn through the Next Turn control | asked in the current turn | player's turn, not multiplayer, nothing blocking (see below), `UI.CanEndTurn()` | a later picture shows a higher turn number (waits up to 2 minutes for the other players) |
| `build` | A city starts building something else, as its production screen does | own city, a valid group | own city, `CanTrain` / `CanConstruct` / `CanCreate` / `CanMaintain` | the city is building that item |
| `research` | Chooses the technology to research | a technology given | `CanResearch` | that technology is being researched, or known |
| `reveal` | Uncovers the plots within 1 or 2 hexes of where the player stands | plot revealed, radius 1..2 | plot revealed for the viewer's team | the neighbours are revealed (or Civ reported nothing new) |
| `improve` | Places an improvement or route, or clears a forest, jungle or marsh | own land, not a city, water or mountain | own land, a build the Worker unit has in `Unit_Builds`, technology known, not a Great Person's improvement, another civilization's own improvement only for that civilization, `CanBuild`, `CanHaveImprovement`, and not already there | the plot changed |
| `gift` | Gives gold to a city-state | a met city-state, one of Civ's three gift sizes, enough gold | the same | influence is higher than before |
| `war` | Declares war on a civilization | a met civilization, not at war, war allowed | the same, `CanDeclareWar` | the civilization is at war with the viewer |
| `policy` | Opens a branch of the policy tree or adopts a policy, as the policy screen does (`Network.SendUpdatePolicies`) | a branch or a policy given | not open or adopted already, `CanUnlockPolicyBranch` / `CanAdoptPolicy` | the count of policies and branches went up |

All commands are also refused in Civ when the target is not the viewer's, when it is not the
viewer's turn, and in multiplayer.

`improve` and `reveal` set the result directly (`SetImprovementType`, `SetRouteType`,
`SetFeatureType`, `SetRevealed`) instead of going through a Worker or a Scout. Everything else
takes the path Civ's own interface takes for the same click.

**Ending a turn.** Measured on the real game: `UI.CanEndTurn()` says yes while a unit still needs
orders, and the end-turn control then silently does nothing. So `turn` reads
`GetEndTurnBlockingType()` itself, as the Next Turn button's own code does, and first clears what
a player would clear by hand:

1. "Continue" screens that only tell something (technology, wonder, new era, natural wonder,
   ruins, golden age, great person, city-state greeting and a few more) are closed through
   `UIManager:DequeuePopup`, the call their own button makes. Screens that ask for a decision are
   never touched.
2. Units of the viewer that are ready and have no orders get the skip-turn mission.

Either needs a frame to take effect, so the answer is then a refusal ending in "ask again", and
the mod asks again by itself a second and a half later (at most three times per turn). Anything
else that blocks is refused with its name ("adopt a social policy").

`CanUnlockPolicyBranch` also says yes for a branch that is open already; the policy screen checks
`IsPolicyBranchUnlocked` first, and so does the script.

A refused or expired command did nothing, so the same request (same idempotency key) may be made
again and is then a new attempt. Without that, a turn Civ refused to end could never be ended
from Minecraft.

Life of a command: `CREATED → VALIDATED → QUEUED → SUBMITTED → APPLIED → CONFIRMED`, or
`REJECTED` / `EXPIRED` from any point. `APPLIED` is Civ saying so; `CONFIRMED` needs the effect
to show in Civ's state afterwards. No answer within 8 s, or no visible effect within 12 s,
expires it. Commands wait in the queue until it is the player's turn in Civ.

Sending the same thing twice does it once: this side returns the first ticket for a repeated
idempotency key while that ticket is under way or was carried out, and the script keeps the answer per command id and returns it again without
applying anything.

There is no path from Minecraft to arbitrary Lua. The raw `exec` and `run` commands exist only
in the command-line tool, for the person at the keyboard.
