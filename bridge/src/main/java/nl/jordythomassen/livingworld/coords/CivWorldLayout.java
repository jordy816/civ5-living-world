package nl.jordythomassen.livingworld.coords;

/**
 * How the hex board is laid out in Civ V's own world units (what the engine uses to place unit
 * flags and city banners). Measured from the running game: the world position of plot (0,0), and
 * the step to the next column and the next-but-one row.
 */
public record CivWorldLayout(double originX, double originY, double columnStep, double rowStep) {
	public double x(GridPos pos) {
		return originX + pos.u() * columnStep;
	}

	public double y(GridPos pos) {
		return originY + pos.v() * rowStep;
	}

	public GridPos toGrid(double x, double y) {
		return new GridPos((x - originX) / columnStep, (y - originY) / rowStep);
	}
}
