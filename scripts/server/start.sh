#!/usr/bin/env bash
# Starts the Civ V Living World server. The first start fetches Fabric's server launcher
# (which in turn fetches Minecraft's own server) into this folder.
# Needs Java 25. More memory: MEMORY=6G ./start.sh
set -euo pipefail
cd "$(dirname "$0")"
MINECRAFT=26.3
LOADER=0.19.5
INSTALLER=1.1.2
JAVA=${JAVA:-java}
MEMORY=${MEMORY:-4G}
if ! "$JAVA" -version 2>&1 | grep -q 'version "25'; then
  echo "This server needs Java 25. Found: $("$JAVA" -version 2>&1 | head -1)" >&2
  echo "Install a JDK 25 (https://adoptium.net) or point JAVA at one: JAVA=/path/to/java ./start.sh" >&2
  exit 1
fi
if ! grep -q '^eula=true' eula.txt 2>/dev/null; then
  echo "Read eula.txt first: set eula=true there to accept Minecraft's EULA (https://aka.ms/MinecraftEULA)." >&2
  exit 1
fi
if [ ! -f fabric-server-launch.jar ]; then
  echo "Fetching Fabric's server launcher for Minecraft $MINECRAFT ..."
  curl -fsSL -o fabric-server-launch.jar.part "https://meta.fabricmc.net/v2/versions/loader/$MINECRAFT/$LOADER/$INSTALLER/server/jar"
  mv fabric-server-launch.jar.part fabric-server-launch.jar
fi
exec "$JAVA" -Xms1G -Xmx"$MEMORY" -jar fabric-server-launch.jar nogui
