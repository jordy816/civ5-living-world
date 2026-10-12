#!/usr/bin/env bash
# Builds the server pack of a release: the mod and Fabric API in mods/, start scripts that fetch
# Fabric's server launcher on the first start, and a server.properties with what the mod needs.
#   server-pack.sh      writes dist/CivLivingWorld-<version>-server.zip
set -euo pipefail
here=$(cd "$(dirname "$0")/../.." && pwd)
version=$(sed -n 's/^version=//p' "$here/gradle.properties")
jar="$here/fabric/build/libs/civ5-living-world-$version.jar"
api=$(find "$HOME/.gradle/caches/modules-2" -name 'fabric-api-0.161.0+26.3.jar' | head -1)
[ -f "$jar" ] && [ -n "$api" ] || { echo "build the mod first: ./gradlew :fabric:build" >&2; exit 1; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/mods" "$here/dist"
cp "$jar" "$api" "$work/mods/"
cp "$here/scripts/server/start.sh" "$here/scripts/server/start.bat" "$here/scripts/server/server.properties" "$here/scripts/server/README.txt" "$work/"
cp "$here/LICENSE" "$here/NOTICE.md" "$work/"
printf '%s\n' "# By changing the setting below to TRUE you are indicating your agreement to Minecraft's EULA (https://aka.ms/MinecraftEULA)." 'eula=false' >"$work/eula.txt"
out="$here/dist/CivLivingWorld-$version-server.zip"
rm -f "$out"
( cd "$work" && python3 -c "
import zipfile, os, sys
with zipfile.ZipFile(sys.argv[1], 'w', zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk('.'):
        for name in sorted(files):
            path = os.path.join(root, name)
            info = zipfile.ZipInfo.from_file(path, os.path.relpath(path, '.'))
            if name == 'start.sh':
                info.external_attr = (0o755 << 16)
            with open(path, 'rb') as f:
                z.writestr(info, f.read(), zipfile.ZIP_DEFLATED)
" "$out" )
echo "wrote $out ($(du -h "$out" | cut -f1)), mod jar sha256 $(sha256sum "$jar" | cut -c1-16)"
