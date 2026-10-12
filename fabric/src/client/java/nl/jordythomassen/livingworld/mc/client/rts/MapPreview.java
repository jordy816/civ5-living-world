package nl.jordythomassen.livingworld.mc.client.rts;

import java.util.HashMap;
import java.util.Map;

/**
 * A small picture of what each kind of map looks like, for the table a game is set up at. It is
 * a sketch of the shape (one continent, islands, a sea in the middle), not the map that will be
 * made: that one is new every game.
 */
final class MapPreview {
	static final int COLS = 46;
	static final int ROWS = 26;
	static final int WATER = 0;
	static final int SHORE = 1;
	static final int LAND = 2;
	static final int ROUGH = 3;

	private static final Map<String, int[][]> MADE = new HashMap<>();

	private MapPreview() {
	}

	/** What a kind of map means for a game, in a line. */
	static String says(String type) {
		return switch (type) {
			case "Pangaea" -> "One great continent. Everybody meets early, and armies walk.";
			case "Continent and Islands" -> "One continent with islands off its coast, for whoever sails first.";
			case "Two Continents" -> "Two continents. The sea parts you from half the world.";
			case "Three Continents" -> "Three continents. Neighbours first, the others by ship.";
			case "Small Continents" -> "Many small lands. Little room each, and ships matter.";
			case "Archipelago" -> "Islands everywhere. A game of ships and harbours.";
			case "Inner Sea" -> "Land all around one sea in the middle.";
			case "Lakes" -> "Land everywhere, dotted with lakes. No oceans to cross.";
			case "Fractal" -> "Ragged coasts and odd shapes. No promise what you get.";
			case "Perlin" -> "Land and sea evenly mixed, in rolling shapes.";
			default -> "The shape of the world.";
		};
	}

	private static double blob(double x, double y, double cx, double cy, double rx, double ry) {
		return 1.0 - ((x - cx) / rx) * ((x - cx) / rx) - ((y - cy) / ry) * ((y - cy) / ry);
	}

	/** A smooth unevenness between -1 and 1, the same for the same place and seed. */
	private static double uneven(double x, double y, long seed, double scale) {
		double a = (seed % 97) * 0.13;
		double b = (seed % 89) * 0.17;
		return (Math.sin(x * 3.1 * scale + a) * Math.cos(y * 2.7 * scale + b) + 0.6 * Math.sin(x * 6.3 * scale + b * 1.7 + y * 2.1 * scale)
			+ 0.4 * Math.cos(y * 8.9 * scale + a * 2.3 - x * 3.7 * scale)) / 2.0;
	}

	static int[][] cells(String type) {
		return MADE.computeIfAbsent(type, MapPreview::make);
	}

	private static int[][] make(String type) {
		long seed = Math.abs((long) type.hashCode()) + 11;
		boolean[][] land = new boolean[ROWS][COLS];
		for (int row = 0; row < ROWS; row++) {
			for (int col = 0; col < COLS; col++) {
				double x = (col + 0.5) / COLS * 2 - 1;
				double y = (row + 0.5) / ROWS * 2 - 1;
				double n = uneven(x, y, seed, 1.0);
				double edge = Math.max(Math.abs(x), Math.abs(y));
				double v = switch (type) {
					case "Pangaea" -> 0.78 - x * x * 0.95 - y * y * 1.25 + n * 0.3;
					case "Continent and Islands" -> Math.max(blob(x, y, -0.28, 0.0, 0.52, 0.72),
						Math.max(blob(x, y, 0.62, -0.5, 0.2, 0.24), Math.max(blob(x, y, 0.74, 0.3, 0.16, 0.24), blob(x, y, 0.42, 0.72, 0.16, 0.16)))) + n * 0.28;
					case "Two Continents" -> Math.max(blob(x, y, -0.5, 0.0, 0.36, 0.78), blob(x, y, 0.5, 0.0, 0.36, 0.78)) + n * 0.25;
					case "Three Continents" -> Math.max(blob(x, y, -0.64, -0.05, 0.25, 0.74), Math.max(blob(x, y, 0.0, 0.1, 0.24, 0.74), blob(x, y, 0.64, -0.05, 0.25, 0.74))) + n * 0.22;
					case "Small Continents" -> Math.max(Math.max(blob(x, y, -0.66, -0.45, 0.24, 0.36), blob(x, y, -0.6, 0.5, 0.26, 0.34)),
						Math.max(Math.max(blob(x, y, -0.05, -0.1, 0.24, 0.4), blob(x, y, 0.1, 0.68, 0.22, 0.2)), Math.max(blob(x, y, 0.62, -0.5, 0.26, 0.34), blob(x, y, 0.66, 0.4, 0.22, 0.38))))
						+ n * 0.25;
					case "Archipelago" -> uneven(x, y, seed, 2.6) - 0.3 - (edge > 0.9 ? 0.5 : 0);
					case "Inner Sea" -> 0.3 - Math.abs(Math.sqrt(x * x + y * y * 1.15) - 0.74) * 1.5 + n * 0.12;
					case "Lakes" -> 0.62 + uneven(x, y, seed, 2.2) * 1.05;
					case "Fractal" -> uneven(x, y, seed, 1.7) * 1.25 + 0.1 - edge * edge * 0.5;
					case "Perlin" -> n * 1.1 + 0.02 - (edge > 0.92 ? 0.4 : 0);
					default -> 0.6 - x * x - y * y + n * 0.3;
				};
				land[row][col] = v > 0;
			}
		}
		int[][] out = new int[ROWS][COLS];
		for (int row = 0; row < ROWS; row++) {
			for (int col = 0; col < COLS; col++) {
				if (land[row][col]) {
					double x = (col + 0.5) / COLS * 2 - 1;
					double y = (row + 0.5) / ROWS * 2 - 1;
					out[row][col] = uneven(x + 3.3, y - 1.7, seed + 5, 3.4) > 0.28 ? ROUGH : LAND;
					continue;
				}
				boolean coast = false;
				for (int dr = -1; dr <= 1 && !coast; dr++) {
					for (int dc = -1; dc <= 1 && !coast; dc++) {
						int r = row + dr;
						int c = col + dc;
						coast = r >= 0 && r < ROWS && c >= 0 && c < COLS && land[r][c];
					}
				}
				out[row][col] = coast ? SHORE : WATER;
			}
		}
		return out;
	}
}
