package nl.jordythomassen.livingworld.state;

import java.util.HashMap;
import java.util.Map;

/**
 * Civ's own type tables for this game (terrain, features, improvements, resources, routes),
 * as sent by the game. Plots refer to entries by id, so modded types arrive as-is and anything
 * this side does not recognise can fall back to a default instead of breaking.
 */
public final class Legend {
	public enum Kind { TERRAIN, FEATURE, IMPROVEMENT, RESOURCE, ROUTE }

	public record Entry(int id, String type, String name) {
	}

	private final Map<Kind, Map<Integer, Entry>> tables = new HashMap<>();

	public void put(Kind kind, Entry entry) {
		tables.computeIfAbsent(kind, k -> new HashMap<>()).put(entry.id(), entry);
	}

	/** The Civ type key (for example TERRAIN_GRASS), or an empty string for "none"/unknown. */
	public String type(Kind kind, int id) {
		Entry entry = entry(kind, id);
		return entry == null ? "" : entry.type();
	}

	public String name(Kind kind, int id) {
		Entry entry = entry(kind, id);
		return entry == null ? "" : entry.name();
	}

	public Entry entry(Kind kind, int id) {
		Map<Integer, Entry> table = tables.get(kind);
		return table == null ? null : table.get(id);
	}

	public java.util.Collection<Entry> entries(Kind kind) {
		Map<Integer, Entry> table = tables.get(kind);
		return table == null ? java.util.List.of() : table.values();
	}

	public int size() {
		return tables.values().stream().mapToInt(Map::size).sum();
	}
}
