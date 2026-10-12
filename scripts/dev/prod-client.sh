#!/usr/bin/env bash
# Development aid: the Minecraft client started the way a launcher starts it, not the way the
# build does: the unchanged game jar, and the mod as the built jar in a mods folder next to
# Fabric API. This is what runs on a player's machine, so it is the place to find what only
# breaks there (a hook that does not find its target, a class missing from the jar).
# The screen is the same virtual one as headless-client.sh uses: take pictures and give input
# with `headless-client.sh shot` and `headless-client.sh hand`.
#
#   prod-client.sh start     build the mod first (./gradlew :fabric:build) and run the dev client once (for the libraries)
#   LW_SERVER=127.0.0.1:25599 prod-client.sh start     join a server instead of opening the own world (see windows-server.sh)
#   prod-client.sh stop
#   prod-client.sh guard     (in the background, next to a running client) stop the client before this machine runs out of memory
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
dev="$root/fabric/run"
# A second client beside the first (two players on one server): LW_RUN=$root/fabric/run-prod2 LW_NAME=Second LW_DISPLAY=:98 LW_FB=$root/fabric/run-prod2/fb
run=${LW_RUN:-$root/fabric/run-prod}
display=${LW_DISPLAY:-:97}
size=${LW_SIZE:-854x480}
loom="$HOME/.gradle/caches/fabric-loom"
case "${1:-}" in
	start)
		export JAVA_HOME=${JAVA_HOME:?point JAVA_HOME at a JDK 25}
		jar="$root/fabric/build/libs/civ5-living-world-$(sed -n 's/^version=//p' "$root/gradle.properties").jar"
		api=$(find "$HOME/.gradle/caches/modules-2" -name 'fabric-api-0.161.0+26.3.jar' | head -1)
		args="$root/fabric/build/loom-cache/argFiles/runClient"
		[ -f "$jar" ] && [ -n "$api" ] && [ -f "$args" ] || { echo "build the mod and run the dev client once first" >&2; exit 1; }
		rm -rf "$run/mods" "$run/saves" "$run/logs"
		fb=${LW_FB:-$dev/fb}
		mkdir -p "$run/mods" "$run/config" "$fb"
		cp "$jar" "$api" "$run/mods/"
		cp "$dev/config/livingworld.properties" "$run/config/"
		join=()
		if [ -n "${LW_SERVER:-}" ]; then
			# Straight into a server that is not this player's own (see windows-server.sh), instead of opening the own world.
			sed -i '/^autoOpen=/d' "$run/config/livingworld.properties"
			echo 'autoOpen=false' >>"$run/config/livingworld.properties"
			join=(--quickPlayMultiplayer "$LW_SERVER")
		fi
		[ -f "$run/options.txt" ] || cp "$dev/options.txt" "$run/options.txt"
		# The libraries the game and the loader need, without anything of this project, of Fabric API or of the build's own copies of the game.
		cp=$(tr -d '"' <"$args" | tr ' ' '\n' | command grep '\.jar' | tr ':' '\n' \
			| command grep -v -e "^$root/" -e 'net.fabricmc.fabric-api' -e 'minecraftMaven' -e '/remapped_mods/' -e 'unciv' | command grep '\.jar$' | paste -sd: -)
		cp="$loom/26.3/minecraft-client.jar:$cp"
		export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe LP_NUM_THREADS=${LW_LP_THREADS:-3}
		Xvfb "$display" -screen 0 "${size}x24" -ac -nolisten tcp -fbdir "$fb" >"$run/xvfb.log" 2>&1 &
		echo $! >"$run/xvfb.pid"
		cd "$run"
		# The view from above loads the world 20 chunks around by itself. Drawn in software that is more than this
		# machine has memory for (it brought the whole host to a crawl once): 12 here unless told otherwise.
		# LW_JVM_ARGS: more of the mod's switches, as the dev client has them (-Dlivingworld.debug=true for the debug driver,
		# -Dlivingworld.autostart=true to skip the table a game is set up at). LW_HEAP: the game's heap.
		# shellcheck disable=SC2086
		DISPLAY=$display "$JAVA_HOME/bin/java" "-Xmx${LW_HEAP:-2500M}" "-Dlivingworld.rts.distance=${LW_RTS_DISTANCE:-12}" ${LW_JVM_ARGS:-} -cp "$cp" net.fabricmc.loader.impl.launch.knot.KnotClient \
			--version 26.3 --accessToken 0 --username "${LW_NAME:-Player}" --gameDir "$run" \
			--assetIndex "$(command grep -A1 -- '--assetIndex' "$root/.gradle/loom-cache/projects/fabric/launch.cfg" | tail -1 | tr -d '[:space:]')" \
			--assetsDir "$loom/assets" "${join[@]}" >"$run/client.log" 2>&1 || true
		kill "$(cat "$run/xvfb.pid")" 2>/dev/null || true
		;;
	guard)
		# This machine does not kill what eats its memory: it freezes, for everybody on it. So the client is stopped
		# here before that: when memory in use passes LW_GUARD_MB, or the load says the machine is already struggling.
		limit=${LW_GUARD_MB:-9300}
		while sleep 4; do
			pid=$("${JAVA_HOME:?}/bin/jcmd" -l 2>/dev/null | awk '/KnotClient/ {print $1}' | tr '\n' ' ')
			[ -n "$pid" ] || exit 0
			used=$(free -m | awk '/^Mem:/ {print $3}')
			load=$(cut -d' ' -f1 /proc/loadavg | cut -d. -f1)
			if [ "$used" -gt "$limit" ] || [ "$load" -gt "${LW_GUARD_LOAD:-16}" ]; then
				echo "guard: memory in use ${used} MB, load ${load}: stopping the client" | tee -a "$run/guard.log"
				# shellcheck disable=SC2086
				kill $pid
				exit 1
			fi
		done
		;;
	stop)
		# Every client that runs here (there can be two, see LW_RUN), and their screens.
		for pid in $("${JAVA_HOME:?}/bin/jcmd" -l | awk '/KnotClient/ {print $1}'); do kill "$pid"; done
		for file in "$root/fabric/run-prod/xvfb.pid" "$root/fabric/run-prod2/xvfb.pid" "$dev/xvfb.pid"; do kill "$(cat "$file" 2>/dev/null)" 2>/dev/null || true; done
		;;
	*)
		sed -n '2,12p' "$0"
		;;
esac
