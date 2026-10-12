#!/usr/bin/env bash
# Development aid: the real Minecraft client with the mod on a machine without a screen or a
# graphics card (Xvfb + Mesa's software renderer), to take pictures of what a player would see.
# Slow (a few frames a second) but true to the game: real textures, real light, real characters.
#
#   headless-client.sh start            start the client; it opens the Living World by itself
#   headless-client.sh do <<< 'cityview'   feed lines to the mod's debug driver (see DebugDriver)
#   headless-client.sh shot out.png     a picture of the screen (shot out.png X Y W H 4: that part, four times as large)
#   headless-client.sh hand focus click 3   real mouse and keyboard events (see xinput.py), as a hand would give them
#   headless-client.sh stop             close it
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
run="$root/fabric/run"
display=${LW_DISPLAY:-:97}
size=${LW_SIZE:-854x480}
case "${1:-}" in
	start)
		export JAVA_HOME=${JAVA_HOME:?point JAVA_HOME at a JDK 25}
		export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe LP_NUM_THREADS=3
		# -fbdir: the screen lives in a file (an X window dump), so a picture is a copy of that file; no capture tool needed.
		mkdir -p "$run/fb"
		Xvfb "$display" -screen 0 "${size}x24" -ac -nolisten tcp -fbdir "$run/fb" >"$run/xvfb.log" 2>&1 &
		echo $! >"$run/xvfb.pid"
		cd "$root"
		DISPLAY=$display ./gradlew --offline ${LW_GRADLE_ARGS:-} :fabric:runClient >"$run/client.log" 2>&1 || true
		kill "$(cat "$run/xvfb.pid")" 2>/dev/null || true
		;;
	do)
		cat >"$run/config/livingworld-debug.txt"
		;;
	shot)
		python3 "$root/scripts/dev/xwd2png.py" "${LW_FB:-$run/fb}/Xvfb_screen0" "${@:2}"
		;;
	hand)
		shift
		python3 "$root/scripts/dev/xinput.py" -d "$display" "$@"
		;;
	stop)
		# The client is the JVM that runs Fabric's Knot launcher; ask for it by name, never by pattern-kill.
		pid=$("${JAVA_HOME:?}/bin/jcmd" -l | awk '/KnotClient|fabricmc.devlaunchinjector/ {print $1}' | head -1)
		[ -n "$pid" ] && kill "$pid"
		kill "$(cat "$run/xvfb.pid")" 2>/dev/null || true
		;;
	*)
		sed -n '2,12p' "$0"
		;;
esac
