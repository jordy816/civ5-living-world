package nl.jordythomassen.livingworld.board;

/** The sixteen wool colours, to pick the one closest to a civilization's own colour. */
public final class Dye {
	public static final String[] NAMES = { "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
		"cyan", "purple", "blue", "brown", "green", "red", "black" };
	private static final int[] RGB = { 0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52, 0x9D9D97,
		0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21 };

	private Dye() {
	}

	/**
	 * The wool colour that reads as the same colour. Hue comes first: Rome's dark purple has to
	 * become purple wool, not black, even though black is "closer" in plain RGB distance.
	 */
	public static int nearest(int rgb) {
		double[] want = hsv(rgb);
		boolean grey = want[1] < 0.2 || want[2] < 0.12;
		int best = 0;
		double bestDistance = Double.MAX_VALUE;
		for (int i = 0; i < RGB.length; i++) {
			double[] have = hsv(RGB[i]);
			boolean dyeIsGrey = have[1] < 0.2;
			if (grey != dyeIsGrey) {
				continue;
			}
			double distance;
			if (grey) {
				distance = Math.abs(want[2] - have[2]);
			} else {
				double hue = Math.abs(want[0] - have[0]);
				hue = Math.min(hue, 360.0 - hue) / 180.0;
				distance = 3.0 * hue + Math.abs(want[2] - have[2]) + 0.5 * Math.abs(want[1] - have[1]);
			}
			if (distance < bestDistance) {
				bestDistance = distance;
				best = i;
			}
		}
		return best;
	}

	/** Hue in degrees, saturation and value in 0..1. */
	private static double[] hsv(int rgb) {
		double r = ((rgb >> 16) & 0xFF) / 255.0;
		double g = ((rgb >> 8) & 0xFF) / 255.0;
		double b = (rgb & 0xFF) / 255.0;
		double max = Math.max(r, Math.max(g, b));
		double min = Math.min(r, Math.min(g, b));
		double delta = max - min;
		double hue = 0;
		if (delta > 0) {
			if (max == r) {
				hue = 60.0 * (((g - b) / delta) % 6.0);
			} else if (max == g) {
				hue = 60.0 * ((b - r) / delta + 2.0);
			} else {
				hue = 60.0 * ((r - g) / delta + 4.0);
			}
			if (hue < 0) {
				hue += 360.0;
			}
		}
		return new double[] { hue, max == 0 ? 0 : delta / max, max };
	}

	public static int rgb(int dye) {
		return RGB[dye & 0xF];
	}
}
