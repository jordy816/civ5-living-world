package nl.jordythomassen.livingworld.coords;

/**
 * How the hex board is laid out in Minecraft blocks.
 *
 * <p>East is +x in both games. Civ rows count north while Minecraft's z counts south, so z is
 * negated: north on the Civ map is north in Minecraft. The scale is free: changing it moves
 * blocks, never Civ identities.
 */
public record McLayout(double hexWidth, double originX, double originZ) {
	private static final double ROW_HEIGHT = Math.sqrt(3.0) / 2.0;

	public McLayout {
		if (!(hexWidth >= 4.0)) {
			throw new IllegalArgumentException("hex width must be at least 4 blocks, got " + hexWidth);
		}
	}

	/** Blocks between two rows of hexes. */
	public double rowSpacing() {
		return hexWidth * ROW_HEIGHT;
	}

	public double x(GridPos pos) {
		return originX + pos.u() * hexWidth;
	}

	public double z(GridPos pos) {
		return originZ - pos.v() * rowSpacing();
	}

	public GridPos toGrid(double x, double z) {
		return new GridPos((x - originX) / hexWidth, (originZ - z) / rowSpacing());
	}
}
