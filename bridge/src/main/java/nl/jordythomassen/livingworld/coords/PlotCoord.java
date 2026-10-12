package nl.jordythomassen.livingworld.coords;

/** A Civilization V plot: column {@code x} (west to east) and row {@code y} (south to north). */
public record PlotCoord(int x, int y) {
	@Override
	public String toString() {
		return x + "," + y;
	}
}
