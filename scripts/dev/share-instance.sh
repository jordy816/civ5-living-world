#!/usr/bin/env bash
# Builds a Prism Launcher instance of the mod that can be sent to somebody else: Minecraft 26.3
# with Fabric, Fabric API and the built mod jar, as a zip that Prism imports (Add Instance >
# Import).
#   share-instance.sh                   writes "dist/Civ V Living World.zip" (Prism names the instance after the zip)
#   share-instance.sh --join HOST:PORT  the same, but the instance goes straight to that server when it is started
#                                       (and does not open a world of its own first): for the friends of one host
set -euo pipefail
here=$(cd "$(dirname "$0")/../.." && pwd)
jar="$here/fabric/build/libs/civ5-living-world-$(sed -n 's/^version=//p' "$here/gradle.properties").jar"
api=$(find "$HOME/.gradle/caches/modules-2" -name 'fabric-api-0.161.0+26.3.jar' | head -1)
[ -f "$jar" ] && [ -n "$api" ] || { echo "build the mod first: ./gradlew :fabric:build" >&2; exit 1; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/.minecraft/mods" "$here/dist"
cp "$jar" "$api" "$work/.minecraft/mods/"
cp "$here/scripts/share/README-FOR-A-FRIEND.txt" "$work/"
join=""
if [ "${1:-}" = "--join" ] && [ -n "${2:-}" ]; then
	join="$2"
	mkdir -p "$work/.minecraft/config"
	printf '%s\n' '# This instance joins a server when it starts; it does not open a world of its own.' 'autoOpen=false' >"$work/.minecraft/config/livingworld.properties"
fi
# What the instance is made of: Minecraft, the Fabric loader and what they need.
cp "$here/scripts/share/mmc-pack.json" "$work/mmc-pack.json"
printf '%s\n' '[General]' 'InstanceType=OneSix' 'name=Civ V Living World' 'iconKey=default' \
	'notes="Civ V Living World: Civilization played inside Minecraft. README-FOR-A-FRIEND.txt in the instance folder says how to join."' \
	'OverrideMemory=true' 'MinMemAlloc=512' 'MaxMemAlloc=4096' 'AutomaticJava=true' 'ConfigVersion=1.3' >"$work/instance.cfg"
if [ -n "$join" ]; then
	printf '%s\n' 'JoinServerOnLaunch=true' "JoinServerOnLaunchAddress=$join" >>"$work/instance.cfg"
fi
out="$here/dist/Civ V Living World.zip"
rm -f "$here/dist/CivLivingWorld-instance.zip"
rm -f "$out"
( cd "$work" && python3 -c "
import zipfile, os, sys
with zipfile.ZipFile(sys.argv[1], 'w', zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk('.'):
        for name in files:
            path = os.path.join(root, name)
            z.write(path, os.path.relpath(path, '.'))
" "$out" )
echo "wrote $out ($(du -h "$out" | cut -f1)), mod jar sha256 $(sha256sum "$jar" | cut -c1-16)"
