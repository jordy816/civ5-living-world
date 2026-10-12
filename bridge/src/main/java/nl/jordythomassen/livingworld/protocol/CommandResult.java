package nl.jordythomassen.livingworld.protocol;

import java.util.Map;

/** What our Lua reports after trying to carry out a command. */
public record CommandResult(String commandId, boolean applied, String code, String message, Map<String, String> values) {
	public long number(String key, long fallback) {
		String value = values.get(key);
		if (value == null) {
			return fallback;
		}
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
