package nl.jordythomassen.livingworld.unciv;

import com.unciv.models.ruleset.nation.Nation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/** Unciv calls things by their plain names ("Bronze Working"); the Minecraft side knows them as keys (BRONZE_WORKING). */
public final class Names {
	private Names() {
	}

	public static String key(String name) {
		if (name == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(name.length());
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (Character.isLetterOrDigit(c)) {
				out.append(Character.toUpperCase(c));
			} else if (out.length() > 0 && out.charAt(out.length() - 1) != '_') {
				out.append('_');
			}
		}
		while (out.length() > 0 && out.charAt(out.length() - 1) == '_') {
			out.setLength(out.length() - 1);
		}
		return out.toString();
	}

	/** A technology's key; the one name that differs from Civilization V's is brought in line. */
	public static String techKey(String name) {
		String key = key(name);
		return key.equals("RAILROADS") ? "RAILROAD" : key;
	}

	/** Key back to words, for text shown to a player: "BRONZE_WORKING" becomes "Bronze Working". */
	public static String words(String key) {
		StringBuilder out = new StringBuilder();
		for (String part : key.toLowerCase(Locale.ROOT).split("_")) {
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

	/**
	 * A nation's colour as 0xRRGGBB. Unciv's class has two members called getOuterColor that differ
	 * only in what they return, which Java cannot tell apart in source, so the list of numbers is
	 * fetched by its return type.
	 */
	public static int color(Nation nation, boolean outer) {
		String name = outer ? "getOuterColor" : "getInnerColor";
		try {
			for (Method method : Nation.class.getMethods()) {
				if (method.getName().equals(name) && method.getParameterCount() == 0 && List.class.isAssignableFrom(method.getReturnType())) {
					List<?> rgb = (List<?>) method.invoke(nation);
					if (rgb != null && rgb.size() >= 3) {
						return (((Number) rgb.get(0)).intValue() & 0xFF) << 16 | (((Number) rgb.get(1)).intValue() & 0xFF) << 8 | ((Number) rgb.get(2)).intValue() & 0xFF;
					}
				}
			}
		} catch (ReflectiveOperationException e) {
			// fall through to a neutral colour
		}
		return outer ? 0x808080 : 0xFFFFFF;
	}

	/** The building style of a civilization, in Civilization V's terms. Unciv has no such table. */
	public static String artStyle(String civ, boolean barbarian) {
		if (barbarian) {
			return "ARTSTYLE_BARBARIAN";
		}
		return switch (civ) {
			case "Rome", "Greece", "Byzantium", "Carthage" -> "ARTSTYLE_GRECO_ROMAN";
			case "Egypt", "Arabia", "Persia", "Songhai", "The Ottomans", "Babylon", "Ethiopia", "Morocco", "Assyria" -> "ARTSTYLE_MIDDLE_EAST";
			case "China", "Japan", "India", "Siam", "Korea", "Mongolia", "The Huns", "Indonesia" -> "ARTSTYLE_ASIAN";
			case "Aztecs", "Inca", "The Maya", "Iroquois", "Brazil", "Shoshone" -> "ARTSTYLE_SOUTH_AMERICA";
			case "Polynesia" -> "ARTSTYLE_POLYNESIAN";
			default -> "ARTSTYLE_EUROPEAN";
		};
	}
}
