# What has been tried, and how

"The tests pass" is not "it works", so this page says at which level each thing ran.

| Level | What it is | Run with |
|---|---|---|
| Unit and contract tests (118) | The engine plays real games through the same interface the mod uses: a game from the first Settler to an empire, a 150-turn game, war and peace, deals the computer accepts and refuses, a religion, a city taken by assault, air and nuclear strikes, a game won and one lost, saving and loading, a game for several people with its order of moves. The board is checked block by block: rivers, borders, cities of every age, wonders on their own hex | `./gradlew :bridge:test :engine:test` |
| Server self-test (56 checks) | A real Minecraft server without players: a game is started, drawn and played through the code a player's clicks reach (select, found, research, build, buy, end the turn, a pantheon, a deal), a stand-in player digs and delivers, a second game is started in the same world, and a game for two is played for a turn. Also run on Windows 11 | `./gradlew :fabric:runServer -Dlivingworld.selftest=true --args=nogui` |
| The real client, real mouse and keys | The unchanged game with the built jar, on a virtual screen with software rendering: every list, page and tree of the view from above clicked, long games played by keys alone, walking, digging on one's own land and being refused outside it, logs felled and carried to a city | `scripts/dev/prod-client.sh` |
| Two clients, one server | Two real clients against a dedicated server, one of them on a second machine (Windows): the table for two, a civilization each, a turn in which one waits for the other, war and the order of moves that comes with it, a kept game gone back to | by hand |
| People | One player, a few sessions on a real graphics card. Several people for an evening: **not yet** | |

What that leaves open is in [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md). The pictures in
this repository are the real client, most of them from the virtual screen (854 by 480).

## Looking without starting Minecraft

`./gradlew :bridge:test` writes `bridge/build/preview/*.png`: cities of every age and style,
and every wonder, drawn by a small isometric renderer. It is the quick way to see what a
change to a building does.

## The debug driver

With `-Dlivingworld.debug=true` the mod reads one-line orders from
`config/livingworld-debug.txt` in the game folder (`found`, `simulate 10`, `gold 500`,
`building The Pyramids`, `goto 8 9` ...): a way to set a scene. It is off unless asked for,
and what it sets up says nothing about whether a click works; clicks are tested as clicks.
