# Known limitations

Found by trying, unless marked as a design choice.

## The view from above

- **One person has played it on a real graphics card, for about an hour and a half in all.**
  Three sittings on an RTX 4070 SUPER at 20 chunks: a game to turn 24, that game carried on
  to turn 37 (a promotion and a pantheon chosen, a wonder begun), and a new game set up at
  the table and played to turn 12. No error in the log. Everything added since (the policies
  as trees, wonders on the land, the rules for walking, spies, kept games) has run in the
  real client on a build machine without a graphics card, and on a server.
- **A fault in the view costs the view, not the game.** Drawing, clicks and keys of the view
  from above are fenced: a fault there closes the view, says so, and leaves the player on
  foot (F12 tries again). A fault in one of the hooks into the game's own drawing (the
  projection, what is in view) is not fenced and would still stop the game.
- **Some pages are simple.** Research, production, buying (with gold or with faith),
  promotions and the choice of a Great Person are lists with pictures and what each line
  does; the technologies and the social policies are trees; speaking to another people has
  its portrait and a deal its table. The rest (the list of other civilizations, proposals,
  beliefs, the vote, the overview, a city in detail and its line of builds, religion, the
  ways to win, happiness, the settings) are panels over the map with lines of text and
  buttons that carry a picture and a number.
- **What a unit could do to an enemy is foretold, not promised.** The card by the pointer is
  Unciv's own reckoning at the middle of its dice; the least and the most it can come to
  are on the card.
- **The colours over the land lie on the ground.** Territory, borders and the fog of war are
  one flat picture laid on the land. On a hillside or behind a mountain it sits a little off
  what it belongs to. O switches the colours off (not the cloud of a game with several
  players).
- **Creatures are stand-ins.** Which creature is which unit is a matter of taste (a ghast
  for artillery, a bee for a helicopter); the flag over each still says what it is, and the
  panel names it. A creature the game does not know (an older Minecraft) is replaced by the
  plain figure of a person. Because zombies and creepers cannot exist in a peaceful world,
  the world of the built-in game is on Easy: nothing spawns and none of them has a will of
  its own, but on foot you can get hungry.
- **Caravans and cargo ships do not exist in these rules.** The engine plays Civilization V
  as it was before its last expansion: a city joined to the capital by road or harbour pays
  gold, and that is the whole of trade routes. It is not something left out of the view.
- **A hex is picked at ground level.** A click on the top of a mountain lands on the hex
  behind it; click its foot.
- **How far one can zoom out depends on the render distance.** It is raised to 20 chunks while
  ruling from above and put back on the way down; set it higher to see more at once.
- **Units are as tall as houses**, as on Civilization's map, also when you walk among them.
- **A block that may not be broken breaks for a moment.** Outside your own land, in a city or on
  a wonder the block cracks and falls as usual, and is back at once with a line that says why:
  the server refuses, the player's own game had already begun. Nothing is lost or gained.
- **Wonders take the six hexes around their city first**, whatever those are used for: a farm
  goes on being worked around the Pyramids, and its wheat can be reaped by hand (what grows
  back may be taken there; the wonder itself and the ground may not). A seventh wonder goes a ring further out when the
  city owns land there, and otherwise stands among the houses as wonders did before. Other
  peoples' wonders are named on your map only when you know all the land around their city.
- **What hands bring a city is counted per turn, not kept across a reload.** A game loaded in
  the middle of a turn takes a city's share for that turn once more.
- **What a tree drops lies where it falls.** A log felled high in a tree, or on a hillside,
  drops on whatever is under it (a ledge, the leaves) and has to be walked to; nothing brings
  it to you. That is Minecraft's own way, and it makes felling on hills slower than it looks.
- **Only with the mod in the player's own game.** Without it (a plain Minecraft joining a
  server that runs the mod) the game is played on foot as before.
- **Not tried with Sodium, Iris or other mods that replace how the world is drawn.** The view
  changes the game's own camera and projection. The instance on the gaming PC holds this mod
  and Fabric API and nothing else, so this only matters if such a mod is added to it.
- **Several players, each with a civilization: played on two machines by two players, neither
  of them a person.** The rules of it (everybody at once, two at war in turns, the computer
  for whoever is away, taking over a civilization, proposals between people) hold in the
  engine's tests and in the server's self-test. On 2026-10-12 real clients joined a dedicated
  server on Windows and played it, first two on one machine (table, a turn at once, a war in
  turns, a kept game), then one on the build machine and one on a Windows PC whose player was
  another agent working that PC's screen and mouse (table, begin, a city each, the turn
  waiting and passing; TESTING.md). What two people make of it over an evening (lag over the
  internet, who clicks what when) is not known. What is known to be rough:
  - **On foot everybody walks the same world.** It is built from what any player has
    explored, so somebody who walks sees land (not units) their own people have not found.
    From above it lies under cloud.
  - **The cloud over unseen land is flat.** A mountain or a tower on land you have not
    seen can stand out a little over the edge of land you have.
  - **In a war between two people the same one always moves first** (the one whose
    civilization comes first in the game's list).
  - **Somebody who rules along has the same civilization, not a part of it.** Two people
    giving orders to one unit is theirs to sort out.
  - **Spies are a page, not a screen.** Sending one, the hideout and a coup are buttons on a
    page; a game opened from before this version has none (it was set up without them).
## The game inside Minecraft

**Started on the gaming PC on foot, not yet played for long.** The version before the view
from above ran there on 2026-10-10 on the RTX 4070 SUPER: a new game, the capital founded by a
click, a normal exit after a minute, no errors (TESTING.md). Everything in GAMEPLAY.md has run in tests and on a server, on Linux and on
Windows. The flows of a game have been
clicked through in the real client with real mouse and keyboard events, on a machine without a
graphics card; TESTING.md says at which level each thing ran and what the test driver set up
first (a siege at its end, a Great Prophet, an enemy in range). Every kind of order and every
page a player meets has been pressed that way at least once. What no test and no clicking can
say is how it feels over a whole evening.

Decided for you:

- **The first moment after a city falls.** The game does at once what it does for a computer
  player: the city becomes a puppet; a small one (under four citizens) is set to burn; one that
  a city-state founded goes back to that city-state when you are not at war with it. The page
  that opens then lets you annex, give back, raze or spare, but the automatic return of a
  city-state's city cannot be undone. (Unciv asks its own player this on a screen of its own,
  which does not exist here; to ask it before the transfer would mean changing Unciv.)
- **The name of your religion**: your people's own when it is still free, else the first that is.

A ruler in person, new in 0.2.0 and so the least played part:

- **Only armies on land send soldiers.** Workers, settlers, ships, aircraft and cities do not
  fight a ruler on foot, and a ruler does not hurt them. The tall figure of a unit itself
  cannot be hit: its soldiers are the fight.
- **Soldiers are Minecraft's creatures and behave as those do**: a skeleton shoots, a zombie
  that stays under water becomes a drowned one (it is still the same soldier). What a unit is
  shown as when that creature cannot fight on foot (a ghast, a creeper, a shulker) is stood in
  for by a zombie or a skeleton.
- **How hard a fight is has been set by reckoning, not by playing.** A soldier of an early
  unit falls to three blows of a wooden sword and takes about a heart a blow; later armies
  want the arms a governor sells. One person fought them with real mouse and keys once, and
  lost. Expect the numbers to change.
- **The Shoshone** have their wide borders, their strength on their own land, the Pathfinder
  and the Comanche Riders. The Pathfinder does not choose what it finds in a ruin (Unciv has
  no rule for that).
- **The sounds have not been heard.** The machines the client is tested on have no sound
  card. Every sound is one of Minecraft's own and its name was checked against the game's
  list; how loud and how many is a guess until somebody plays with the sound on.
- **The sketch of a kind of map** on the table is a drawing of its shape, not the map the game
  will make.

As in Civilization V, and so not a gap: **a city stands on one hex.** It does not spread over
its neighbours as it grows; here it builds taller. Eight lots is all the room there is, so of a
city's many buildings only those with a shape of their own are shown, at most four wonders, and
one lot always stays a house.

How it looks and behaves:

- **Two quick clicks are one.** Click events within six ticks (0.3 s) of each other count as
  one click, because one real click arrives as several. A deliberate double-click that fast is
  swallowed.
- **Marks for a selected unit are only on your screen**, as carpet. Use something on one and
  Minecraft shows the real block again until the marks are next refreshed.
- **The bell must be within reach** (about four blocks), like any block. From the door of the
  hall it is too far; the Codex ends the turn from anywhere.
- **With the Codex in hand, a right-click on a governor is the governor's.** Look away from him
  to open the Codex itself.
- **Explored land shows as it is now**, not as it was when last seen: the engine keeps no
  memory of that. Units and cities out of sight are not shown.
- **Characters are simple**: a figure in the owner's colours with a tool that fits; riders on a
  horse or camel, ships and anybody at sea in a rowing boat, whatever the ship. Aircraft are a
  figure standing in their city.
- **A nuclear weapon falls where you point it, at once.** There is no "are you sure".
- **A turn of the other civilizations runs in one go.** On a small map that is well under a
  second; on a huge map late in a game it can be several, during which the game does not answer.
- **The engine is one for the whole Minecraft process.** Leaving a world and opening another
  one with a different game in the same sitting has not been tried.
- **Its texts are English**, whatever language Minecraft is set to.
- **The vote for a world leader** works when a vote is called (test, and by hand in the client);
  no game has been played long enough to reach one by itself.
- **Great People** show their deeds in their menu; only the Great Prophet's have been used.

## The older way: a running Civilization V

## The connection to Civ

- **One tool at a time.** Civ's debug socket serves a single client. Do not run the
  command-line tool or FireTuner while Minecraft is connected.
- **Civ's socket only recovers from a client that leaves abruptly.** A client that closes its
  connection politely leaves the socket deaf, usually until Civ is restarted (measured, see
  PROTOCOL.md). The mod and the command-line tool therefore always leave with a reset, also when
  Minecraft crashes or is killed, and with that Minecraft can be closed and started again as
  often as you like while Civ keeps running (tried: normal exit and outright kill). Firaxis'
  own FireTuner, or any other tool that closes politely, can still leave the socket deaf: if the
  status bar stays on "Civilization V not found" with Civ running, restart Civ.
- **After losing the connection the mod waits 20 seconds** before making a new one. That pause
  dates from before the cause above was found and is probably longer than needed; it was left
  in because it does no harm.
- **Single-player only**, and no achievements in launcher sessions (Firaxis' rules for the
  socket).
- **The socket listens on all network interfaces.** On the gaming PC a Windows Firewall rule
  ("Civ V debug socket: local only", added 2026-10-10) blocks it from the network; see
  COMPATIBILITY.md.
- **A long freeze of Civ's main thread** (loading a big save, a long AI turn) makes a call time
  out after 5 seconds; the mod then drops the connection and reconnects 20 seconds later. Not
  harmful, but the display is stale meanwhile.

## What you see in Civ

- **No free camera.** Civ's camera can follow the player hex by hex; nothing in the game lets
  it be placed freely, tilted or rotated from outside.
- **The marker is an icon.** No Minecraft model in Civ's picture, no depth: it draws on top like
  a unit flag.
- **Wrapping maps.** Civ maps that wrap east-west show the marker on one copy of the world.

## What you see in Minecraft

- **The board does not wrap.** A wrapping Civ map is laid out flat; you cannot walk off the
  east edge and arrive in the west.
- **Painting takes time.** Up to six chunks per tick, nearest first. The first view of a new
  area fills in over a few seconds.
- **Your own building stays until Civ changes that very spot.** A column of blocks is only
  written again (between heights 48 and 112) when Civ's map says something else for it: a new
  improvement, a border, a city growing over it. Then what you built in that column is gone.
- **Other civilizations' cities show no buildings.** Era, style and size only; buildings and
  wonders are sent for your own cities.
- **Eight lots per city.** A city with more buildings than that shows its wonders and the first
  buildings Civ lists; the rest are not drawn. Many buildings share a shape (a University looks
  like a Library).
- **No interiors.** Buildings are shells.
- **Characters are stand-ins.** A unit is one figure whatever its size in Civ, told apart by what
  it holds; its age shows only in its helmet. Naval units stand on the water; nobody fights.
- **Units of a civ at war with you are only labelled** as enemies. They do not attack, and you
  cannot attack them.
- **Fog of war is two-level.** Explored but currently unseen land is shown as last known, like
  Civ does, but units there are simply absent.

## Commands

- Eleven exist: move a unit, deliver production, heal a unit, end the turn, choose a city's
  production, choose research, adopt a social policy, explore, work the land, gift a city-state,
  declare war. Still only in Civ's own window: founding cities, combat, peace and every other
  deal with a leader, religion, ideology, espionage, great people, trade routes, buying with
  gold, promotions, and the production queue (a choice from Minecraft replaces what is being
  built).
- **Gifting a city-state and declaring war have not run against the real game.** The test game
  never met anyone. The other nine have; see TESTING.md.
- **Civ's screens that ask for a decision still need Civ's window.** A leader who comes to talk,
  a captured city (annex, puppet or raze), a pantheon or religion to found, a free great person:
  the turn cannot end until you answer there, and Minecraft tells you that Civ is waiting for
  something in its own window. Only screens that merely tell something are put away for you.
- **Units without orders simply wait** when you end the turn from Minecraft. Workers, Settlers
  and Scouts do nothing unless you order them about in Civ's window; from Minecraft you do the
  Worker's and the Scout's job yourself.
- **Working the land skips the Worker.** An improvement, a road or a cleared forest is set on the
  plot at once, without a unit and without the turns it takes in Civ. The limit is three works
  per player per turn and the materials you bring. Civ's AI does not know you can do this.
- **Exploring skips the Scout.** Walking reveals land without a unit being there and without
  movement points, so in one ten-minute turn you can uncover far more than a Scout could.
  Revealing is all it does: from reading Civ's source, meeting another civilization and opening
  ancient ruins still need a real unit to get there. Not tried on the real game.
- **Diplomacy is one-way and small.** You can see how a leader seems to feel, gift a city-state
  and declare war. A leader's words are a fixed line per attitude, not a conversation. City-state
  requests are shown, not tracked: nothing in Minecraft counts towards them unless it also
  happens in Civ.
- **The crafting rule covers crafting by hand and at a crafting table.** The automatic crafter
  block, the smithing table, and things you get some other way are not checked.
- **The Codex is a clock underneath**, shown as a book, because a clock does nothing when used
  and no recipe takes one. A resource pack that changes the knowledge book changes its look.
- **Travel is free.** The Codex takes you to any city your civilization knows at once.
- **A day is always ten minutes.** The length of a turn does not grow with the size of your
  empire. With `dayIsTurn` on, a turn you were not finished with ends at sunset if Civ has no
  open question for you; units you meant to move simply did not move.
- **An escort order to a unit with no moves left** is accepted by Civ for next turn, and
  reported here as "accepted, but no effect showed up", because nothing moves within the
  confirmation window. (Read from the code; not tried on the real game.)
- **Delivery and healing are direct changes** to a city's hammers and a unit's damage, capped
  per command (60 hammers, 50 health). There is no cap per turn: with enough logs you can finish
  a build quickly. A design choice for the first version, not a balance decision.
- **The move order selects the unit in Civ's interface**, because it goes through the same path
  as a right-click there.

## Setup

- **The desktop must be unlocked and Steam signed in.** At the Windows lock screen Steam does
  not sign in, and Civ exits a few seconds after starting. The launcher waits two minutes for
  Steam, then stops and says so without changing any settings. It starts Steam itself when
  Steam is not running.

- The Minecraft instance lives inside SkyCraft's copy of Prism Launcher and depends on it for
  the account, Minecraft, and Java.
- The launcher assumes the DX11 exe and edits `GraphicsSettingsDX11.ini`.
- First player only: with more than one player in the world, the first is the one on Civ's map.
