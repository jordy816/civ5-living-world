package nl.jordythomassen.livingworld.gameplay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which Civilization V technology a Minecraft item needs before it can be crafted. This is how
 * Civ's tech tree becomes Minecraft's progression: no iron tools before Iron Working, no books
 * before Writing. Items are named by their Minecraft id without the namespace; technologies by
 * Civ's key without "TECH_".
 */
public final class TechGate {
	private record Rule(String tech, String what, List<String> exact, List<String> prefixes, List<String> suffixes) {
		boolean covers(String item) {
			if (exact.contains(item)) {
				return true;
			}
			for (String prefix : prefixes) {
				if (item.startsWith(prefix)) {
					return true;
				}
			}
			for (String suffix : suffixes) {
				if (item.endsWith(suffix)) {
					return true;
				}
			}
			return false;
		}
	}

	private static final List<Rule> RULES = List.of(
		new Rule("ARCHERY", "bows and arrows", List.of("bow", "arrow"), List.of(), List.of()),
		new Rule("SAILING", "boats", List.of(), List.of(), List.of("_boat", "_raft")),
		new Rule("WRITING", "books and bookshelves", List.of("book", "writable_book", "bookshelf", "chiseled_bookshelf", "lectern"), List.of(), List.of()),
		new Rule("BRONZE_WORKING", "copper tools and armour", List.of(), List.of("copper_sword", "copper_pickaxe", "copper_axe", "copper_shovel", "copper_hoe",
			"copper_helmet", "copper_chestplate", "copper_leggings", "copper_boots"), List.of()),
		new Rule("IRON_WORKING", "iron tools and armour, buckets and shears", List.of("bucket", "shears", "shield"), List.of("iron_sword", "iron_pickaxe",
			"iron_axe", "iron_shovel", "iron_hoe", "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots"), List.of()),
		new Rule("OPTICS", "the spyglass", List.of("spyglass"), List.of(), List.of()),
		new Rule("PHILOSOPHY", "the enchanting table", List.of("enchanting_table"), List.of(), List.of()),
		new Rule("METAL_CASTING", "the anvil", List.of("anvil"), List.of(), List.of()),
		new Rule("COMPASS", "compasses and maps", List.of("compass", "map"), List.of(), List.of()),
		new Rule("MACHINERY", "the crossbow", List.of("crossbow"), List.of(), List.of()),
		new Rule("STEEL", "diamond tools and armour", List.of(), List.of("diamond_sword", "diamond_pickaxe", "diamond_axe", "diamond_shovel", "diamond_hoe",
			"diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots"), List.of()),
		new Rule("GUNPOWDER", "TNT and fireworks", List.of("tnt", "firework_rocket", "firework_star"), List.of(), List.of()),
		new Rule("CHEMISTRY", "the brewing stand", List.of("brewing_stand"), List.of(), List.of()),
		new Rule("INDUSTRIALIZATION", "the blast furnace", List.of("blast_furnace"), List.of(), List.of()),
		new Rule("RAILROAD", "rails and minecarts", List.of("rail", "powered_rail", "detector_rail", "activator_rail", "minecart"), List.of(), List.of()),
		new Rule("ELECTRICITY", "pistons and redstone machines", List.of("piston", "sticky_piston", "repeater", "comparator", "observer", "redstone_lamp",
			"dispenser", "dropper"), List.of(), List.of())
	);

	private TechGate() {
	}

	/** The technology (Civ's key without "TECH_") this item needs, or null when it needs none. */
	public static String required(String item) {
		String name = item.indexOf(':') >= 0 ? item.substring(item.indexOf(':') + 1) : item;
		for (Rule rule : RULES) {
			if (rule.covers(name)) {
				return rule.tech;
			}
		}
		return null;
	}

	/** The technology that stops this item from being crafted with what the civilization knows, or null. */
	public static String missing(String item, Set<String> known) {
		String tech = required(item);
		// Without any knowledge of the tech tree (no Civ game shown) nothing is held back.
		return tech == null || known.isEmpty() || known.contains(tech) ? null : tech;
	}

	/** What learning a technology lets the player craft, in words; null when nothing. */
	public static String unlocks(String tech) {
		for (Rule rule : RULES) {
			if (rule.tech.equals(tech)) {
				return rule.what;
			}
		}
		return null;
	}

	/** Technology key as words: "BRONZE_WORKING" -> "Bronze Working". */
	public static String words(String key) {
		StringBuilder out = new StringBuilder();
		for (String part : key.toLowerCase(java.util.Locale.ROOT).split("_")) {
			if (part.isEmpty()) {
				continue;
			}
			if (out.length() > 0) {
				out.append(' ');
			}
			out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return out.toString();
	}

	/** Every gated technology with what it unlocks, in tech-tree order (for documentation). */
	public static Map<String, String> table() {
		Map<String, String> out = new LinkedHashMap<>();
		for (Rule rule : RULES) {
			out.put(rule.tech, rule.what);
		}
		return out;
	}

	public static List<String> gatedTechs() {
		return new ArrayList<>(table().keySet());
	}
}
