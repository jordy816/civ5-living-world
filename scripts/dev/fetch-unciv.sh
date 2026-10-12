#!/usr/bin/env bash
# Fetches the game engine this project builds on: Unciv (https://github.com/yairm210/Unciv, MPL-2.0),
# an open-source remake of Civilization V whose rules and AI run without a window.
# Leaves two files in libs/ (not in git):
#   unciv-engine-<version>.jar   Unciv's game logic with the Kotlin and libGDX classes it needs, and
#                                nothing else: no window backend, no natives, no pictures, no logging
#                                binding (Minecraft brings its own)
#   unciv-rulesets-<version>.zip the rule files (jsons/Civ V - Vanilla, jsons/Civ V - Gods & Kings)
# usage: fetch-unciv.sh        (re-run after changing VERSION below)
set -euo pipefail
VERSION=4.22.8
SHA256=04254d306e9edea0d25a495c188b7d546a5009c5cdb5850f8fc86669e8b428e3
here=$(cd "$(dirname "$0")/../.." && pwd)
libs="$here/libs"
mkdir -p "$libs"
full="$libs/Unciv-$VERSION.jar"
if [ ! -f "$full" ]; then
  curl -fsSL -o "$full.part" "https://github.com/yairm210/Unciv/releases/download/$VERSION/Unciv.jar"
  mv "$full.part" "$full"
fi
echo "$SHA256  $full" | sha256sum -c - >/dev/null || { echo "checksum mismatch for $full" >&2; exit 1; }
python3 -I - "$full" "$libs/unciv-engine-$VERSION.jar" "$libs/unciv-rulesets-$VERSION.zip" <<'PY'
import sys, zipfile
full, engine, rules = sys.argv[1:4]
KEEP = ("com/unciv/", "kotlin/", "kotlinx/", "_COROUTINE/", "com/badlogic/gdx/", "androidx/collection/", "androidx/annotation/", "org/jetbrains/", "org/intellij/")
DROP = ("com/badlogic/gdx/backends/", "com/badlogic/gdx/graphics/g3d/", "com/badlogic/gdx/physics/")
kept = dropped = ruled = 0
with zipfile.ZipFile(full) as src, zipfile.ZipFile(engine, "w", zipfile.ZIP_DEFLATED) as eng, zipfile.ZipFile(rules, "w", zipfile.ZIP_DEFLATED) as rul:
    for info in src.infolist():
        name = info.filename
        if name.endswith("/"):
            continue
        if name.startswith("jsons/Civ V - "):
            rul.writestr(info, src.read(name)); ruled += 1
        elif (name.startswith(KEEP) and not name.startswith(DROP)) or (name.startswith("META-INF/") and (name.endswith(".kotlin_module") or name.startswith("META-INF/services/kotlin"))):
            eng.writestr(info, src.read(name)); kept += 1
        else:
            dropped += 1
print(f"engine jar: {kept} files kept, {dropped} left out; rulesets: {ruled} files")
PY
ls -la "$libs" | awk 'NR>1 {print $5, $9}'
