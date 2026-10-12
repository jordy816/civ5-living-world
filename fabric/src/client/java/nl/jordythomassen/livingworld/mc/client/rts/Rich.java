package nl.jordythomassen.livingworld.mc.client.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.gui.Font;
import nl.jordythomassen.livingworld.mc.Looks;

/**
 * Words with small pictures among them. The measures of Civilization (food, production, gold
 * ...) are each drawn as one small picture, the same one everywhere on the screen, with their
 * number before them in the measure's own colour; and a line that names a thing ("Unit: Archer")
 * begins with that thing's picture. So a line is read at a glance, and a list of them is not a
 * wall of text.
 */
final class Rich {
	/** A stretch of a line: words in a colour, or a picture (then {@code picture} is an item's name). */
	record Piece(String text, String picture, int color) {
	}

	/**
	 * One of the measures: the word the game's texts have for it, its picture, its colour, and
	 * whether the word gets its picture also where no number stands before it.
	 */
	record Measure(String word, String picture, int color, boolean alone) {
		Measure(String word, String picture, int color) {
			this(word, picture, color, true);
		}
	}

	static final Measure FOOD = new Measure("Food", "apple", 0xFF7FE060);
	static final Measure PRODUCTION = new Measure("Production", "copper_pickaxe", 0xFFFFA040);
	static final Measure GOLD = new Measure("Gold", "gold_ingot", 0xFFFFD75E);
	static final Measure SCIENCE = new Measure("Science", "experience_bottle", 0xFF7FD4FF);
	static final Measure CULTURE = new Measure("Culture", "amethyst_shard", 0xFFE28BFF);
	static final Measure FAITH = new Measure("Faith", "feather", 0xFFF4F0DC);
	static final Measure HAPPINESS = new Measure("Happiness", "sunflower", 0xFFFFE860);
	static final Measure STRENGTH = new Measure("Strength", "iron_sword", 0xFFFFB0A0);
	static final Measure RANGED = new Measure("Ranged Strength", "bow", 0xFFFFB0A0);
	static final Measure MOVEMENT = new Measure("Movement", "leather_boots", 0xFF9FE08A);
	/** The longer names first, so that "Ranged Strength" is not read as "Strength". */
	static final Measure[] MEASURES = { RANGED, FOOD, PRODUCTION, GOLD, SCIENCE, CULTURE, FAITH, HAPPINESS, STRENGTH, MOVEMENT, new Measure("Range", "target", 0xFFD8D8E0, false),
		new Measure("Sight", "spyglass", 0xFFD8D8E0, false), new Measure("Defence", "shield", 0xFFD8D8E0, false), new Measure("Population", "player_head", 0xFFD8D8E0, false) };

	/** Lines that name a thing: what they begin with, the picture (empty: the thing's own, found by its name), and the group it is found in. */
	private static final String[][] KINDS = { { "Unit:", "", "0" }, { "Building:", "", "1" }, { "Wonder:", "", "2" }, { "Project:", "", "2" }, { "Improvement:", "iron_shovel", "" },
		{ "Reveals:", "spyglass", "" } };

	/** A space that keeps what is on either side of it together on one line. */
	private static final char TIE = ' ';
	private static final String NUMBER = "[+\\-\\u2212]?\\d+(?:[.,]\\d+)?%?(?:/\\d+)?";
	private static final String NAMES;
	private static final Pattern TO_TIE;
	private static final Pattern NUMBERED;
	private static final Pattern BARE;
	/** How much of a line a picture takes. */
	static final int PICTURE = 10;

	static {
		StringBuilder names = new StringBuilder();
		for (Measure measure : MEASURES) {
			names.append(names.length() == 0 ? "" : "|").append(measure.word().replace(" ", "[ " + TIE + "]"));
		}
		NAMES = names.toString();
		TO_TIE = Pattern.compile("(" + NUMBER + ") (" + NAMES + ")\\b");
		NUMBERED = Pattern.compile("^([^\\w+\\-\\u2212]*)(" + NUMBER + ")" + TIE + "(" + NAMES + ")(\\W*)$");
		BARE = Pattern.compile("^(\\W*)(" + NAMES + ")(\\W*)$");
	}

	private Rich() {
	}

	private static Measure measure(String word) {
		String plain = word.replace(TIE, ' ');
		for (Measure measure : MEASURES) {
			if (measure.word().equals(plain)) {
				return measure;
			}
		}
		return MEASURES[0];
	}

	/**
	 * A line as the words that belong together: a number stays with its measure, a named thing
	 * with its kind. {@code tight} leaves out what follows a named thing (its numbers).
	 */
	static List<String> words(String line, boolean tight) {
		List<String> out = new ArrayList<>();
		String rest = line;
		for (String[] kind : KINDS) {
			if (line.startsWith(kind[0] + " ")) {
				String after = line.substring(kind[0].length() + 1);
				int end = after.indexOf("   ");
				String name = end < 0 ? after : after.substring(0, end);
				out.add(kind[0] + TIE + name.replace(' ', TIE));
				rest = end < 0 || tight ? "" : after.substring(end + 3);
				break;
			}
		}
		rest = rest.replace("Ranged Strength", "Ranged" + TIE + "Strength").replace("   ", " " + TIE + " ");
		rest = TO_TIE.matcher(rest).replaceAll(found -> Matcher.quoteReplacement(found.group(1) + TIE + found.group(2).replace(' ', TIE)));
		for (String word : rest.split(" ")) {
			if (!word.isEmpty()) {
				out.add(word);
			}
		}
		return out;
	}

	/** The picture a line that names a thing begins with, or null when it names none. */
	static String picture(String line) {
		for (String[] kind : KINDS) {
			if (line.startsWith(kind[0] + " ") || line.startsWith(kind[0] + TIE)) {
				String name = line.substring(kind[0].length() + 1).replace(TIE, ' ');
				int end = name.indexOf("   ");
				return kind[1].isEmpty() ? Looks.choiceIcon("build", Integer.parseInt(kind[2]), "", end < 0 ? name : name.substring(0, end)) : kind[1];
			}
		}
		return null;
	}

	/**
	 * Words as what is drawn. {@code named} writes a measure's word after its picture (a card has
	 * room for it; a line in a list has not, and there the picture stands for the word after a
	 * number). {@code tinted} gives a number its measure's colour.
	 */
	static List<Piece> pieces(List<String> words, boolean named, int color, boolean tinted) {
		List<Piece> out = new ArrayList<>();
		StringBuilder run = new StringBuilder();
		for (int i = 0; i < words.size(); i++) {
			String word = words.get(i);
			String gap = i == 0 ? "" : " ";
			String picture = picture(word);
			if (picture != null) {
				flush(out, run.append(gap), color);
				out.add(new Piece("", picture, color));
				if (named) {
					// On a card, also in words what kind of thing it is, a shade back.
					out.add(new Piece(word.substring(0, word.indexOf(TIE)) + " ", null, mute(color)));
				}
				run.append(word.substring(word.indexOf(TIE) + 1).replace(TIE, ' '));
				continue;
			}
			Matcher numbered = NUMBERED.matcher(word);
			if (numbered.matches()) {
				Measure measure = measure(numbered.group(3));
				flush(out, run.append(gap).append(numbered.group(1)), color);
				out.add(new Piece(numbered.group(2), null, tinted ? measure.color() : color));
				out.add(new Piece("", measure.picture(), color));
				run.append(named ? measure.word() : "").append(numbered.group(4));
				continue;
			}
			Matcher bare = named ? BARE.matcher(word) : null;
			if (bare != null && bare.matches() && measure(bare.group(2)).alone()) {
				Measure measure = measure(bare.group(2));
				flush(out, run.append(gap).append(bare.group(1)), color);
				out.add(new Piece("", measure.picture(), color));
				run.append(measure.word()).append(bare.group(3));
				continue;
			}
			run.append(gap).append(word.replace(TIE, ' '));
		}
		flush(out, run, color);
		return out;
	}

	private static int mute(int color) {
		return (color & 0xFF000000) | (((color >> 16) & 0xFF) * 2 / 3 << 16) | (((color >> 8) & 0xFF) * 2 / 3 << 8) | ((color & 0xFF) * 2 / 3);
	}

	private static void flush(List<Piece> out, StringBuilder run, int color) {
		if (run.length() > 0) {
			out.add(new Piece(run.toString(), null, color));
			run.setLength(0);
		}
	}

	static int width(Font font, List<Piece> pieces) {
		int width = 0;
		for (Piece piece : pieces) {
			width += piece.picture() != null ? PICTURE : font.width(piece.text());
		}
		return width;
	}

	/**
	 * Words laid on lines no wider than {@code width}, at most {@code rows} of them: what does not
	 * fit is left out and the last line ends in two dots (the card by the pointer has all of it).
	 */
	static List<List<String>> flow(Font font, List<String> words, int width, int rows, boolean named) {
		List<List<String>> out = new ArrayList<>();
		List<String> row = new ArrayList<>();
		for (String word : words) {
			if (!row.isEmpty()) {
				List<String> longer = new ArrayList<>(row);
				longer.add(word);
				if (width(font, pieces(longer, named, 0, false)) > width) {
					if (out.size() + 1 >= rows) {
						row.add("..");
						while (row.size() > 2 && (width(font, pieces(row, named, 0, false)) > width || row.get(row.size() - 2).equals("·"))) {
							row.remove(row.size() - 2);
						}
						out.add(row);
						return out;
					}
					out.add(row);
					row = new ArrayList<>();
				}
			}
			// A line does not begin with the dot that parts two things.
			if (!row.isEmpty() || !word.equals("·")) {
				row.add(word);
			}
		}
		if (!row.isEmpty()) {
			out.add(row);
		}
		return out;
	}

	/**
	 * What a thing does, in brief: its lines one after the other with a dot between, on at most
	 * {@code rows} lines of a list.
	 */
	static List<List<String>> brief(Font font, String about, int width, int rows) {
		List<String> words = new ArrayList<>();
		for (String line : about.split("\n")) {
			if (line.isEmpty()) {
				continue;
			}
			if (!words.isEmpty()) {
				words.add("·");
			}
			words.addAll(words(line, true));
		}
		return flow(font, words, width, rows, false);
	}
}
