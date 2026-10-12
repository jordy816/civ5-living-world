package nl.jordythomassen.livingworld.state;

/**
 * What the viewing player knows about one plot. Type fields are indexes into the session's
 * {@link Legend}; -1 means none. A plot the viewer has never seen is {@link #UNKNOWN}: Civ does
 * not send its contents at all, so fog of war holds on this side by construction.
 */
public record PlotState(
	int terrain,
	int feature,
	int elevation,
	int owner,
	int improvement,
	int resource,
	int route,
	int rivers,
	int flags
) {
	public static final int FLAT = 0;
	public static final int HILLS = 1;
	public static final int MOUNTAIN = 2;

	public static final int RIVER_EAST = 1;
	public static final int RIVER_SOUTH_EAST = 2;
	public static final int RIVER_SOUTH_WEST = 4;

	public static final int F_REVEALED = 1;
	public static final int F_VISIBLE = 2;
	public static final int F_CITY = 4;
	public static final int F_WATER = 8;
	public static final int F_LAKE = 16;
	public static final int F_PILLAGED = 32;

	public static final PlotState UNKNOWN = new PlotState(-1, -1, 0, -1, -1, -1, -1, 0, 0);

	public boolean revealed() {
		return (flags & F_REVEALED) != 0;
	}

	public boolean visible() {
		return (flags & F_VISIBLE) != 0;
	}

	public boolean city() {
		return (flags & F_CITY) != 0;
	}

	public boolean water() {
		return (flags & F_WATER) != 0;
	}

	public boolean lake() {
		return (flags & F_LAKE) != 0;
	}

	public boolean pillaged() {
		return (flags & F_PILLAGED) != 0;
	}
}
