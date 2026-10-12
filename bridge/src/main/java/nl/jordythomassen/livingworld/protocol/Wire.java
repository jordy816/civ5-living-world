package nl.jordythomassen.livingworld.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * The text format our Lua inside Civ V speaks: one record per line, fields separated by tabs.
 * Civ's Lua has no JSON library, and a format this simple is easy to get exactly right on both
 * sides. Tabs, newlines and backslashes inside a field are escaped with a backslash.
 */
public final class Wire {
	/** Bumped whenever a record changes shape; the Lua side reports the version it speaks. */
	public static final int PROTOCOL = 2;

	/** Every line of ours starts with this, so other output on the same channel is ignored. */
	public static final String PREFIX = "LW\t";

	public static final int MAX_LINE = 64 * 1024;

	private Wire() {
	}

	public static String escape(String value) {
		StringBuilder out = null;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			String replacement = switch (c) {
				case '\\' -> "\\\\";
				case '\t' -> "\\t";
				case '\n' -> "\\n";
				case '\r' -> "\\r";
				default -> null;
			};
			if (replacement != null && out == null) {
				out = new StringBuilder(value.length() + 8).append(value, 0, i);
			}
			if (out != null) {
				if (replacement != null) {
					out.append(replacement);
				} else {
					out.append(c);
				}
			}
		}
		return out == null ? value : out.toString();
	}

	public static String unescape(String value) {
		if (value.indexOf('\\') < 0) {
			return value;
		}
		StringBuilder out = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '\\' && i + 1 < value.length()) {
				char next = value.charAt(++i);
				out.append(switch (next) {
					case 't' -> '\t';
					case 'n' -> '\n';
					case 'r' -> '\r';
					default -> next;
				});
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	/**
	 * Splits one of our lines into its unescaped fields (without the prefix), or returns null when
	 * the line is not ours or is malformed. Never throws: this is fed whatever the game prints.
	 */
	public static List<String> parse(String line) {
		if (line == null || line.length() > MAX_LINE) {
			return null;
		}
		int start = line.indexOf(PREFIX);
		if (start < 0) {
			return null;
		}
		String body = line.substring(start + PREFIX.length());
		// The game may append a carriage return.
		while (!body.isEmpty() && (body.charAt(body.length() - 1) == '\r' || body.charAt(body.length() - 1) == '\n')) {
			body = body.substring(0, body.length() - 1);
		}
		List<String> fields = new ArrayList<>();
		int from = 0;
		while (true) {
			int tab = body.indexOf('\t', from);
			if (tab < 0) {
				fields.add(unescape(body.substring(from)));
				break;
			}
			fields.add(unescape(body.substring(from, tab)));
			from = tab + 1;
		}
		return fields.isEmpty() || fields.get(0).isEmpty() ? null : fields;
	}

	/** A Lua string literal for {@code value}, safe to embed in code we send to the game. */
	public static String luaString(String value) {
		StringBuilder out = new StringBuilder(value.length() + 2).append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '\\' -> out.append("\\\\");
				case '"' -> out.append("\\\"");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				case 0 -> out.append("\\0");
				default -> {
					if (c < 32 || c > 126) {
						// Lua 5.1 takes decimal byte escapes; keep to ASCII and drop the rest.
						out.append('?');
					} else {
						out.append(c);
					}
				}
			}
		}
		return out.append('"').toString();
	}
}
