package nl.jordythomassen.livingworld.coords;

/** Where a position sits inside its hex: how close to the rim, and which side is nearest. */
public final class HexMetric {
	// One row is sqrt(3)/2 hex widths tall; projecting onto the diagonal axes multiplies by
	// sqrt(3)/2 again, hence 0.75.
	private static final double ROW = 0.75;

	private HexMetric() {
	}

	/** 0 at the centre of the hex, 1 on its rim, above 1 outside it. */
	public static double rimFraction(GridPos pos, GridPos center) {
		double du = pos.u() - center.u();
		double dv = pos.v() - center.v();
		double east = Math.abs(du);
		double ne = Math.abs(du / 2.0 + ROW * dv);
		double nw = Math.abs(-du / 2.0 + ROW * dv);
		return 2.0 * Math.max(east, Math.max(ne, nw));
	}

	/** The side of the hex this position is closest to. */
	public static HexEdge nearestEdge(GridPos pos, GridPos center) {
		double du = pos.u() - center.u();
		double dv = pos.v() - center.v();
		double east = du;
		double ne = du / 2.0 + ROW * dv;
		double nw = -du / 2.0 + ROW * dv;
		double best = Math.abs(east);
		HexEdge edge = east >= 0 ? HexEdge.EAST : HexEdge.WEST;
		if (Math.abs(ne) > best) {
			best = Math.abs(ne);
			edge = ne >= 0 ? HexEdge.NORTH_EAST : HexEdge.SOUTH_WEST;
		}
		if (Math.abs(nw) > best) {
			edge = nw >= 0 ? HexEdge.NORTH_WEST : HexEdge.SOUTH_EAST;
		}
		return edge;
	}
}
