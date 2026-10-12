package nl.jordythomassen.livingworld.state;

import nl.jordythomassen.livingworld.coords.PlotCoord;

/** A Civ city as the viewing player can see it. Civilization V owns every field. */
public record CityState(
	EntityKey key,
	String name,
	PlotCoord at,
	int population,
	int flags,
	int hp,
	int maxHp,
	String productionType,
	String productionName,
	int production,
	int productionNeeded,
	int productionTurns,
	int food,
	int foodNeeded,
	int strength,
	/** Buildings standing in the city (Civ's type keys without "BUILDING_"); only for the viewer's own cities. */
	java.util.List<String> buildings,
	/** World wonders standing in the city; only for the viewer's own cities. */
	java.util.List<String> wonders
) {
	public static final int F_CAPITAL = 1;
	public static final int F_PUPPET = 2;
	/** It came to its owner from somebody else this very turn. */
	public static final int F_OCCUPIED = 4;
	public static final int F_RAZING = 8;
	public static final int F_RESISTANCE = 16;
	public static final int F_COASTAL = 32;
	/** The viewer owns the city, so the production and food fields are filled in. */
	public static final int F_DETAILS = 64;

	public boolean capital() {
		return (flags & F_CAPITAL) != 0;
	}

	public boolean details() {
		return (flags & F_DETAILS) != 0;
	}
}
