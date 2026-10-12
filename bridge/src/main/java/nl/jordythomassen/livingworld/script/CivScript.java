package nl.jordythomassen.livingworld.script;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** The Lua that runs inside Civilization V, kept as a resource next to this class. */
public final class CivScript {
	/**
	 * The UI context of the game the script is loaded into. It is part of every running game,
	 * has the whole game API, and owns the stock world-anchored icon the player marker reuses.
	 */
	public static final String STATE = "GenericWorldAnchor";

	private static volatile String cached;

	private CivScript() {
	}

	/** The script as it is sent to the game: comments and indentation removed to keep it short. */
	public static String source() {
		String s = cached;
		if (s == null) {
			cached = s = minify(raw());
		}
		return s;
	}

	public static String raw() {
		try (InputStream in = CivScript.class.getResourceAsStream("livingworld.lua")) {
			if (in == null) {
				throw new IllegalStateException("livingworld.lua is missing from the jar");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Drops whole-line comments, blank lines and leading whitespace. Line structure is kept. */
	static String minify(String source) {
		StringBuilder out = new StringBuilder(source.length());
		for (String line : source.split("\n")) {
			String trimmed = line.strip();
			if (trimmed.isEmpty() || trimmed.startsWith("--")) {
				continue;
			}
			out.append(trimmed).append('\n');
		}
		return out.toString();
	}
}
