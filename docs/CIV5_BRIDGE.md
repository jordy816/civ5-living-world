# The older way: showing a running Civilization V

Before the game ran inside Minecraft, this mod showed a running Civilization V and steered it
through Civ's debug socket. That path is still in the mod (`engine=civ5` in
`config/livingworld.properties`, or `LivingWorld.ps1 -Action bridge`), and this page describes
playing that way. The game as it is meant to be played now is in [GAMEPLAY.md](GAMEPLAY.md).

Everything shown here comes from the running Civ game at that moment, and every choice offered
is one Civilization V itself offered a moment earlier. How far each part was tried on the real
games is in [TESTING.md](TESTING.md). Cities look the same on both paths: see GAMEPLAY.md for the
buildings; the "Ages" and "Cities" tables below describe the first, simpler drawing and are kept
for the rules, not the looks.

## Nothing to type

Everything is done with what you carry and what stands in the world.

- **The Codex.** You are handed a book, the *Codex of* your civilization. Use it (right-click)
  for your realm at a glance: turn, year and era, treasury, science, happiness, current
  research, and what Civ is waiting for before the turn can end. From there: end the turn,
  choose research, adopt a social policy, work the land you stand on, travel to your capital or
  any city you know, and the settings. A lost Codex comes back with the next turn.
- **The hall of each of your cities.** The governor stands in the middle. Behind him are a
  **bell** (ring it to end your turn) and a **lectern** (read it to choose research).
- **Sneak and use the Codex on the ground** to choose what to build on that hex.
- **Right-click a unit or a governor** to talk to it.

You start at your capital, in front of the south door of its hall. Walk anywhere your
civilization has explored; unexplored land is a wall of cloud. The line above the hotbar names
the plot you stand on (coordinates, terrain, feature, improvement, owner or city). The bar at the top shows the
connection, the Civ turn with its year and era, your civilization, and how far the day has run.

The world is peaceful: nothing spawns, burns or grows by itself, you keep your inventory, and
falling does not hurt.

## Exploring

Walking is scouting. Stepping into a hex next to unexplored land reveals the hexes around you
in Civilization V, one hex far, two from a hilltop. The cloud wall then opens in Minecraft, and
Civ's own map shows the new land. You can only step out from land your civilization already
knows. *Walking explores the land* in the Codex's settings turns this off.

## Ages

Each civilization is drawn in its current era and its own building style.

| Era | City | Helmets of its people |
|---|---|---|
| Ancient | Mud brick or sandstone, thatched roofs, dirt paths | Leather in the civ's colour |
| Classical | Stone or marble with columns, tiled roofs | Chain |
| Medieval | Stone brick with battlements, windows | Iron |
| Renaissance | Brick, dark timber roofs | Iron |
| Industrial | Dark brick with chimneys | Diamond |
| Modern and later | White concrete, taller houses | Netherite |

The building style follows the civilization: Mediterranean ones build in sandstone and marble,
Middle Eastern ones in sandstone, Asian ones in dark timber, South American ones in mossy stone.
When your civilization enters a new era it is announced, and your cities are rebuilt in the new
style.

## Cities

A city is its keep with up to eight lots around it. In your own cities, what Civ built there
stands on those lots: a monument, granary, barracks, library, temple, market, workshop, well or
aqueduct, arena, stable, or lighthouse. Wonders come first (the Pyramids, Stonehenge and the
Great Library have their own shapes; other wonders are a tall landmark). The lots left over are
houses, one per citizen. A city with Walls, a Castle or the Great Wall has a wall around it.

Cities of other civilizations show their era, style and size, but not what was built inside:
Civ does not tell you that either.

The governor stands inside. Right-click the governor:

| City | You see | You can |
|---|---|---|
| Yours | Population, defence, health, what is being built, food, buildings and wonders | Choose what it builds; deliver logs (2 hammers per log, up to 30 logs) |
| Your capital | Also treasury, gold per turn, science, happiness, culture, current research | Also choose what to research |
| A city-state | What kind it is, your influence, whether you are friend or ally, what it asks of you | Gift gold from your Civ treasury, in the three sizes Civ offers |
| Another civilization | How its leader seems to feel about you, friendship or denouncement | Declare war (asks you to confirm; peace is made in Civ) |

In your own cities the hall also holds the bell and the lectern described above.

The production list is the one Civ's own city screen would show, with turns for each item.
Choosing replaces what the city is building, as it does there.

## Research and crafting

The lectern in any of your halls, the Codex, or the governor of your capital lists the
technologies Civ offers right now,
with the turns each takes and what it unlocks in Minecraft. Minecraft crafting follows Civ's
tech tree:

| Technology | Lets you craft |
|---|---|
| Archery | Bows and arrows |
| Sailing | Boats |
| Writing | Books, bookshelves, the lectern |
| Bronze Working | Copper tools and armour |
| Iron Working | Iron tools and armour, buckets, shears, the shield |
| Optics | The spyglass |
| Philosophy | The enchanting table |
| Metal Casting | The anvil |
| Compass | Compasses and maps |
| Machinery | The crossbow |
| Steel | Diamond tools and armour |
| Gunpowder | TNT and fireworks |
| Chemistry | The brewing stand |
| Industrialization | The blast furnace |
| Railroad | Rails and minecarts |
| Electricity | Pistons and redstone machines |

Before the technology is known the crafting grid gives nothing and tells you which one is
missing. When your civilization learns one, chat says what you can now make. Stone and wooden
tools, building blocks and food are never held back. *Crafting follows your technologies* in the
Codex's settings turns this off.

## Social policies

When your culture has earned a policy, Civ will not end the turn until you adopt one. The Codex
then shows *Adopt a policy* at the top; ringing the bell opens the same page. It lists what Civ
offers at that moment: branches of the policy tree you can open, and the policies inside the
branches you already opened. At other times the *Policies* button shows how much culture the
next one takes.

## Working the land

What Civ has on the map is what you find in Minecraft: forest hexes have trees, farms are fields
of ripe wheat, and a resource shows as an outcrop of its ore or crop next to the middle of its
hex. What you harvest regrows when a new Civ turn starts.

- **Clearing forest.** Fell 24 logs in one forest or jungle hex within one turn and the forest
  is cleared in Civ for good, with its timber going to your nearest city as production. It works
  inside your borders once your civilization knows how (Mining for forest, Bronze Working for
  jungle); otherwise
  the trees grow back next turn.
- **Improvements.** Sneak and use the Codex on the ground (or press *Work the land here* in the
  Codex) for what a Worker of your civilization could build on that hex, by Civ's own rules. You bring the materials, and the improvement is then real in
  Civ at once. Three works per turn. What only a Great Person can build (an Academy, a Citadel)
  is not on the list.

| Build | Costs |
|---|---|
| Farm, plantation | 16 wheat seeds |
| Mine, quarry | 32 cobblestone |
| Road, draining a marsh | 16 cobblestone |
| Pasture | 16 wooden fences |
| Camp | 16 logs |
| Fort | 64 cobblestone |
| Railroad | 16 iron ingots |
| Anything else (lumber mill, trading post, ...) | 32 planks |

- **Your own building stays.** Blocks you place or break are kept when the land around them is
  repainted. *Put the land around you back* in the Codex's settings undoes that nearby.

## Units

Every unit your civilization can see stands on its plot as a character in its owner's colours,
holding something that fits its type (an axe for a Warrior, a bow for an Archer, a shovel for a
Worker) and wearing its era's helmet. Its name plate shows its health; an enemy's is marked
`[Enemy]`. When Civ moves a unit, its character walks to the new plot.

Right-click one for its menu: health, moves, strength, level, where it is, and

| Choice | For | Effect in Civilization V |
|---|---|---|
| Ask about its orders | any unit | none: it answers from its real state |
| Escort me | your units | the real unit is ordered to move to the hex you stand in |
| Treat its wounds | your wounded units | heals 10 health per bread, up to 5 bread |

Bread is three wheat, so the farms Civ built feed the soldiers Civ trained.

What you hand over is taken when you press the button and given back if Civ does not carry the
command out. You are told the outcome in chat, with Civ's own before and after numbers.

## Turns and time

One Minecraft day is one Civ turn. The sun rises when your turn starts and takes ten minutes to
set. A minute before sunset you are warned; at sunset your turn ends by itself, exactly as if
you pressed Next Turn. If Civ still wants a decision from you (research, production, a unit
waiting for orders) the sun waits at the horizon, you are told what is missing, and it tries
again every minute. While the other players move, the sun stands still.

Ringing the bell in one of your halls, or *End the turn* in the Codex, ends it early. One ring
is enough, and it does what a player would do in Civ's own window before pressing Next Turn:

- Units of yours that have no orders wait this turn.
- A screen of Civ that only tells something and waits for "Continue" (a technology learned, a
  wonder built, a new era) is put away. The same news is in Minecraft's chat.
- If Civ wants a decision first (a policy, research, something for a city to build), you are
  told which, and the page where you make it opens. Ring again afterwards.

Sunset does the same, without opening pages. You can
also play the turn in Civ's window as usual; the day starts again with the next turn. Switch
*A day is a turn* off in the Codex's settings for permanent noon and turns that end only when
you say so.

## What happens in Civ shows up

A founded, captured or lost city, a finished wonder, war and peace, a learned technology, a new
era and each new turn are announced in chat. The land repaints when borders move, improvements are built or fog
lifts. Units appear, move and disappear as Civ reports them.

## Rules of the bridge

- Only your own units, cities and land take commands; Civ checks that again itself.
- Commands are sent during your turn in Civ. Given while the AI plays, they wait.
- Nothing is applied to a different Civ game than the one it was asked in.
- Single-player only. In a multiplayer game Civ switches its debug socket off, and the script
  refuses to change anything.

## Settings

The Codex's settings page switches these while you play, and remembers them:

| Setting | Default | Meaning |
|---|---|---|
| Civilization V's camera follows you | yes | Civ's view moves along as you walk into another hex |
| A day is a turn | yes | Your turn ends by itself at sunset |
| Crafting follows your technologies | yes | No iron tools before Iron Working |
| Walking explores the land | yes | Stepping into a hex reveals the land around it in Civ |

The same page puts the land around you back as Civ has it, and shows how the connection is doing.

They are kept in `config/livingworld.properties` of the Minecraft instance (`follow`,
`dayIsTurn`, `techGate`, `explore`), next to four that are only read at start: `hexWidth` (32,
the width of a Civ hex in blocks), `autoOpen` (open the Living World when Minecraft starts),
`civ.mock` (a built-in pretend game instead of Civilization V) and `civ.host` / `civ.port`.

## If you ever need them

`/civ` hands you the Codex again and opens it. The older typed commands still work and are not
needed for anything: `/civ status`, `home`, `goto <city>`, `endturn`, `research`, `work`,
`follow on|off`, `repaint`.
