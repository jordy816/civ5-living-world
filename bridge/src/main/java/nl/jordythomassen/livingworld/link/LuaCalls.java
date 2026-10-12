package nl.jordythomassen.livingworld.link;

import java.util.Locale;
import nl.jordythomassen.livingworld.commands.GameCommand;
import nl.jordythomassen.livingworld.protocol.Wire;

/**
 * The only Lua this side ever sends into the game after the bootstrap: three fixed function
 * calls with numbers and quoted strings as arguments. Nothing from Minecraft reaches the game as
 * code.
 */
public final class LuaCalls {
	public static final int SNAP_STATIC = 1;
	public static final int SNAP_PLOTS = 2;
	public static final int SNAP_UNITS = 4;
	public static final int SNAP_CITIES = 8;
	public static final int SNAP_CIVS = 16;
	/** The viewer's empire overview, known technologies and relations with the others. */
	public static final int SNAP_EMPIRE = 32;
	public static final int SNAP_ALL = SNAP_STATIC | SNAP_PLOTS | SNAP_UNITS | SNAP_CITIES | SNAP_EMPIRE;

	private LuaCalls() {
	}

	/** The line that closes the reply to call {@code seq}. */
	public static String endMarker(long seq) {
		return Wire.PREFIX + "<\t" + seq + "\t";
	}

	/** What the game prints when our script is not (or no longer) loaded in it. */
	private static String notLoaded(long seq) {
		return "print(\"LW\\t>\\t" + seq + "\");print(\"LW\\tB\\t0\");print(\"LW\\t<\\t" + seq + "\\t1\")";
	}

	/**
	 * Heartbeat plus the player's position for the on-map marker. {@code u}/{@code v} are board
	 * coordinates and {@code plotX}/{@code plotY} the plot under them (-1 when off the map);
	 * {@code show} is false while no Minecraft player is on the board. The call
	 * checks first that exactly our copy of the script is loaded ({@code revision}), so a game
	 * that was just (re)loaded, or still runs an older copy, answers "not loaded" instead of
	 * raising a Lua error or behaving like the old copy.
	 */
	public static String tick(long seq, String revision, double u, double v, int plotX, int plotY, boolean show, boolean follow, String label) {
		return "if LivingWorld and LivingWorld.REV==" + Wire.luaString(revision) + " then "
			+ String.format(Locale.ROOT, "LivingWorld.tick(%d,%.4f,%.4f,%d,%d,%s,%s,%s)", seq, u, v, plotX, plotY, show, follow, Wire.luaString(label))
			+ " else " + notLoaded(seq) + " end";
	}

	public static String snap(long seq, int mask) {
		return "LivingWorld.snap(" + seq + "," + mask + ")";
	}

	/** Asks what the game offers: kind "build" (city id in a), "research", "policy", or "works" (plot a, c). */
	public static String options(long seq, String kind, int a, int c) {
		if (!kind.equals("build") && !kind.equals("research") && !kind.equals("works") && !kind.equals("policy")) {
			throw new IllegalArgumentException("unknown kind of choice: " + kind);
		}
		return "LivingWorld.opts(" + seq + "," + Wire.luaString(kind) + "," + a + "," + c + ")";
	}

	public static String command(long seq, GameCommand c) {
		int x = c.plot() == null ? -1 : c.plot().x();
		int y = c.plot() == null ? -1 : c.plot().y();
		return "LivingWorld.cmd(" + seq + "," + Wire.luaString(c.id()) + "," + Wire.luaString(c.op().wireName) + ","
			+ c.target().owner() + "," + c.target().id() + "," + x + "," + y + "," + c.amount() + ")";
	}
}
