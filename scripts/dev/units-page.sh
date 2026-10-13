#!/usr/bin/env bash
# Writes the two pages of the docs that come from the game itself, not from somebody's memory of it:
#   docs/CIVILIZATIONS.md   as the engine's tests wrote it (engine/build/docs/CIVILIZATIONS.md)
#   docs/UNITS.md           the units' numbers from the rules (engine/build/docs/units.tsv) and, for each, the creature
#                           of Minecraft that stands for it (the mod's Looks.java), put together by UnitsPage.java here
# Run after a change to the rules, to the Shoshone's files or to Looks.java:
#   ./gradlew :engine:test && scripts/dev/units-page.sh
set -euo pipefail
here=$(cd "$(dirname "$0")/../.." && pwd)
: "${JAVA_HOME:?point JAVA_HOME at a JDK 25}"
[ -f "$here/engine/build/docs/units.tsv" ] && [ -d "$here/bridge/build/classes/java/main" ] || { echo "run ./gradlew :engine:test first" >&2; exit 1; }
out=$(mktemp -d)
"$JAVA_HOME/bin/javac" -d "$out" -cp "$here/bridge/build/classes/java/main" "$here/fabric/src/main/java/nl/jordythomassen/livingworld/mc/Looks.java" "$here/scripts/dev/UnitsPage.java"
"$JAVA_HOME/bin/java" -cp "$out:$here/bridge/build/classes/java/main" UnitsPage "$here/engine/build/docs/units.tsv" "$here/docs/UNITS.md"
cp "$here/engine/build/docs/CIVILIZATIONS.md" "$here/docs/CIVILIZATIONS.md"
echo "wrote $here/docs/CIVILIZATIONS.md"
