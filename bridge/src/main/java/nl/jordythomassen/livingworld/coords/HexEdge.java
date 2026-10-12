package nl.jordythomassen.livingworld.coords;

/** The six sides of a pointy-top hex, with the direction to the plot on the other side. */
public enum HexEdge {
	EAST(1.0, 0.0),
	NORTH_EAST(0.5, 1.0),
	NORTH_WEST(-0.5, 1.0),
	WEST(-1.0, 0.0),
	SOUTH_WEST(-0.5, -1.0),
	SOUTH_EAST(0.5, -1.0);

	/** Offset to the neighbouring hex centre, in {@link GridPos} units. */
	public final double du;
	public final double dv;

	HexEdge(double du, double dv) {
		this.du = du;
		this.dv = dv;
	}

	public HexEdge opposite() {
		return values()[(ordinal() + 3) % 6];
	}
}
