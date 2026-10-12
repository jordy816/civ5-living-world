# Dependencies and licences

This project is MIT (see LICENSE). It contains no files of Civilization V or of Minecraft.

## Shipped in the mod jar

| What | Licence | Note |
|---|---|---|
| This project's own code | MIT | |
| **Unciv 4.22.8** (github.com/yairm210/Unciv): its game logic (`com/unciv`) and the rule files `jsons/Civ V - Vanilla` and `jsons/Civ V - Gods & Kings` | **MPL-2.0** | Unmodified, taken from the project's own release jar by `scripts/dev/fetch-unciv.sh` (which checks its SHA-256). Its source is at the link, tag `4.22.8`. No pictures, sounds or music of Unciv are included |
| Kotlin standard library and kotlinx.coroutines, as contained in that release jar | Apache-2.0 | Unciv is written in Kotlin |
| libGDX core classes (no window backend, no natives), as contained in that release jar | Apache-2.0 | Unciv's data classes use libGDX's collections and JSON reader |
| androidx.collection and annotation classes, as contained in that release jar | Apache-2.0 | Used by Unciv |

MPL-2.0 is a file-level licence: Unciv's files stay under MPL-2.0 inside the jar, this
project's own files stay MIT. Nothing of Unciv was changed; the adapter that talks to it
(`engine/`) is this project's own code and calls Unciv's public classes.

Unciv is a remake, not a copy: it contains no code or art of Firaxis' Civilization V. Its rule
files restate that game's rules (names and numbers of units, buildings, technologies).

## Needed at run time, not shipped

| What | Licence | Note |
|---|---|---|
| Minecraft: Java Edition 26.3 | Mojang EULA | Your own copy |
| Fabric Loader 0.19.5 | Apache-2.0 | Installed by the launcher |
| Fabric API 0.161.0+26.3 | Apache-2.0 | Beside the mod in the Prism instance and the server pack of a release |
| Prism Launcher | GPL-3.0 | Used as a program, not linked |
| Sid Meier's Civilization V | Proprietary | Only for the older way (CIV5_BRIDGE.md): unmodified, reached through its own debug socket |

## Build and test only

| What | Licence |
|---|---|
| Fabric Loom 1.18 | MIT |
| Gradle 9.7.1 | Apache-2.0 |
| JUnit 5.11 | EPL-2.0 |
| LuaJ 2.0.3 (Lua 5.1 interpreter for the script tests) | MIT |

## Ideas and small pieces taken from elsewhere

| From | Licence | What |
|---|---|---|
| SkyCraft (github.com/chasmlol/SkyCraft) | MIT | The empty-world preset, opening the world at start-up, skipping the experimental-world prompt. Credited in the source files |
| civ-v-linux-mcp, civ5-agent-macos and other open tuner clients | various | The framing of the debug socket was learned from their write-ups and then verified on Windows; no code copied |
| minecraft-crossover-bridge, Minecraft-Ring, EldenCraft | MIT | Read for protocol practice (session identity, sequence numbers, whole-or-nothing lists); no code copied |
| Vox Deorum | GPL-3.0 and CC BY-NC-SA 4.0 | Read to decide against it; nothing used |
