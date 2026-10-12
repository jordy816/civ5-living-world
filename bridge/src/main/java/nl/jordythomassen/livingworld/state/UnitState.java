package nl.jordythomassen.livingworld.state;

import nl.jordythomassen.livingworld.coords.PlotCoord;

/** A Civ unit as the viewing player can see it. Civilization V owns every field. */
public record UnitState(
	EntityKey key,
	String type,
	String name,
	PlotCoord at,
	int hp,
	int maxHp,
	int moves,
	int maxMoves,
	int strength,
	int rangedStrength,
	String domain,
	int level,
	int experience,
	int flags
) {
	public static final int F_EMBARKED = 1;
	public static final int F_FORTIFIED = 2;
	public static final int F_COMBAT = 4;
	public static final int F_GARRISONED = 8;
	public static final int F_GREAT_PERSON = 16;
	/** One of the viewer's own units that can still act this turn and has been given nothing to do. */
	public static final int F_WAITING = 32;

	public boolean waiting() {
		return (flags & F_WAITING) != 0;
	}

	public boolean combat() {
		return (flags & F_COMBAT) != 0;
	}

	public boolean embarked() {
		return (flags & F_EMBARKED) != 0;
	}

	public boolean fortified() {
		return (flags & F_FORTIFIED) != 0;
	}
}
