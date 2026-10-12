package nl.jordythomassen.livingworld.link;

import java.util.ArrayList;
import java.util.List;

/** Cuts our Lua source into pieces small enough for one message each, as Lua long strings. */
public final class BootstrapChunks {
	/**
	 * Characters per piece. The game's debug socket silently drops commands somewhere between
	 * 1 KB and 2 KB (measured differently on different ports of the game), so stay well below.
	 */
	public static final int CHUNK = 800;

	private BootstrapChunks() {
	}

	public static List<String> split(String source, int size) {
		List<String> out = new ArrayList<>();
		for (int at = 0; at < source.length(); at += size) {
			out.add(source.substring(at, Math.min(source.length(), at + size)));
		}
		return out;
	}

	/**
	 * A Lua long-string literal holding exactly {@code text}. The bracket level is chosen so the
	 * text cannot end it early, and a leading newline (which Lua would skip) is protected.
	 */
	public static String longString(String text) {
		int level = 1;
		while (text.contains("]" + "=".repeat(level) + "]") || text.endsWith("]" + "=".repeat(level))) {
			level++;
		}
		String eq = "=".repeat(level);
		return "[" + eq + "[\n" + text + "]" + eq + "]";
	}
}
