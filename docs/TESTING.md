# What has been tried, and how

"The tests pass" is not "it works", so this page says at which level each thing ran.

| Level | What it is | Run with |
|---|---|---|
| Unit and contract tests (124) | The engine plays real games through the same interface the mod uses: a game from the first Settler to an empire, a 150-turn game, war and peace, deals the computer accepts and refuses, a religion, a city taken by assault, air and nuclear strikes, a game won and one lost, saving and loading, a game for several people with its order of moves, the civilization this mod adds, a city as a sheet whose turns count down, news with its place and kind, a ruler's strikes and their limits. The board is checked block by block: rivers, borders, cities of every age, wonders on their own hex | `./gradlew :bridge:test :engine:test` |
| Server self-test (63 checks) | A real Minecraft server without players: a game is started, drawn and played through the code a player's clicks reach (select, found, research, build, buy, end the turn, a pantheon, a deal), a stand-in player digs and delivers, is set down on their own land only, is met by the soldiers of a barbarian unit (one beaten: 20 health off the unit), falls and is carried home, trains; a blast is held to its maker's land; a second game is started in the same world, and a game for two is played for a turn. Also run on Windows 11 | `./gradlew :fabric:runServer -Dlivingworld.selftest=true --args=nogui` |
| The real client, real mouse and keys | The unchanged game with the built jar, on a virtual screen with software rendering: every list, page and tree of the view from above clicked, long games played by keys alone, walking, digging on one's own land and being refused outside it, logs felled and carried to a city; the table in its two steps, every kind of map's sketch, a piece of news clicked; F12 where the pointer is and, pointing beyond the border, on one's own land; soldiers of an enemy unit that killed the ruler, the respawn before the capital, and three soldiers beaten one after the other; training bought | `scripts/dev/prod-client.sh` |
| Two clients, one server | Two real clients against a dedicated server, one of them on a second machine (Windows): the table for two, a civilization each, a turn in which one waits for the other, war and the order of moves that comes with it, a kept game gone back to | by hand |
| A fight, by a scripted hand | `scripts/dev/fight-hand.py` beside the real client: it reads the screen, turns to a soldier with real mouse motion and strikes with real clicks. Three soldiers of a Warrior beaten in 49 seconds, the ruler alive | by hand |
| Sound, without a sound card | The game's sound engine writing to a file (OpenAL Soft's `wave` driver: `ALSOFT_DRIVERS=wave` and an `alsoft.conf` naming the file), a scene played by the debug driver, and the file's loudness read back a quarter second at a time: every sound at its moment, silence between | by hand |
| People | One player, a few sessions on a real graphics card. Several people for an evening: **not yet** | |

What that leaves open is in [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md). The pictures in
this repository are the real client, most of them from the virtual screen (854 by 480).

## Pages the game writes itself

[CIVILIZATIONS.md](CIVILIZATIONS.md) and [UNITS.md](UNITS.md) are not written by hand:
`./gradlew :engine:test` writes every civilization and every unit from the rules the game
plays by (`engine/build/docs`), and `scripts/dev/units-page.sh` adds to each unit the creature
of Minecraft the mod shows it as and puts both pages in `docs/`. Run it after a change to the
rules or to who is who.

## Looking without starting Minecraft

`./gradlew :bridge:test` writes `bridge/build/preview/*.png`: cities of every age and style,
and every wonder, drawn by a small isometric renderer. It is the quick way to see what a
change to a building does.

## The debug driver

With `-Dlivingworld.debug=true` the mod reads one-line orders from
`config/livingworld-debug.txt` in the game folder (`found`, `simulate 10`, `gold 500`,
`building The Pyramids`, `goto 8 9` ...): a way to set a scene. It is off unless asked for,
and what it sets up says nothing about whether a click works; clicks are tested as clicks.
