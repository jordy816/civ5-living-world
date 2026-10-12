# What is in the box, and whose it is

Civ V Living World itself is MIT (see [LICENSE](LICENSE)).

## Carried inside the mod jar

| What | Licence | How it is used |
|---|---|---|
| [Unciv](https://github.com/yairm210/Unciv) 4.22.8: its game logic and its rule files for "Civ V - Vanilla" and "Civ V - Gods & Kings" | [MPL-2.0](https://www.mozilla.org/MPL/2.0/) | Unmodified. `scripts/dev/fetch-unciv.sh` downloads Unciv's own release (checksum checked) and leaves out what a game without a window does not need: the window backend, natives, pictures and sounds. Unciv's source is at the link, tag `4.22.8` |
| Kotlin standard library and kotlinx.coroutines (needed by Unciv) | Apache-2.0 | Unmodified |
| [libGDX](https://libgdx.com) core classes (needed by Unciv) | Apache-2.0 | Unmodified; no backend, no natives |
| androidx.collection and annotation classes, JetBrains annotations (needed by Unciv) | Apache-2.0 | Unmodified |

The rule files for the Shoshone (`engine/src/main/resources/livingworld/rules`) are this
project's own, written in Unciv's rule language and read on top of Unciv's rules the way
Unciv reads a mod. Unciv's own files are carried as they are.

## Beside it, in the instance and the server pack

| What | Licence |
|---|---|
| [Fabric API](https://github.com/FabricMC/fabric) 0.161.0+26.3 | Apache-2.0 |

Fabric Loader, Minecraft itself and Java are not in any file of this project: the launcher
or the server's start script fetches them from their own makers.

## Ideas, not code

- The view from above follows the way the mod [Reign of Nether](https://github.com/SoLegendary/reignofnether)
  (GPL-3.0) shows its battles. None of its code is used.
- Opening the one world at start-up and the empty-world preset follow
  [SkyCraft](https://github.com/chasmlol/SkyCraft) (MIT), credited in the source files.

## Names

Minecraft is a trademark of Mojang Synergies AB. Sid Meier's Civilization and Civilization V
are trademarks of Take-Two Interactive Software and its subsidiaries. This fan project is not
affiliated with or endorsed by any of them, nor by the Unciv project. It contains no code or
art from Civilization V; the rules are Unciv's own open-source remake.
