package nl.jordythomassen.livingworld.state;

import nl.jordythomassen.livingworld.coords.PlotCoord;

/**
 * Something that changed in the Civ game, in terms the Minecraft side can act on.
 *
 * <p>These are derived by comparing two {@link WorldState}s, not taken from Civ's own event
 * hooks. Civ's hooks only tell us <em>when</em> to look again; that way a missed hook can never
 * leave the two games permanently out of step.
 */
public sealed interface WorldEvent {
	record SessionStarted(SessionInfo session) implements WorldEvent {
	}

	record TurnChanged(int from, int to) implements WorldEvent {
	}

	record UnitAppeared(UnitState unit) implements WorldEvent {
	}

	record UnitMoved(UnitState unit, PlotCoord from) implements WorldEvent {
	}

	record UnitChanged(UnitState unit, UnitState before) implements WorldEvent {
	}

	/** The unit died, left the viewer's sight, or was disbanded: Civ does not say which. */
	record UnitGone(UnitState last) implements WorldEvent {
	}

	record CityAppeared(CityState city) implements WorldEvent {
	}

	record CityChanged(CityState city, CityState before) implements WorldEvent {
	}

	/** Same plot, new owner: the city was captured or traded. */
	record CityChangedHands(CityState city, CityState before) implements WorldEvent {
	}

	record CityGone(CityState last) implements WorldEvent {
	}

	record PlotChanged(PlotCoord at, PlotState plot, PlotState before) implements WorldEvent {
	}

	record WarChanged(CivInfo civ, boolean atWar) implements WorldEvent {
	}

	record CivChanged(CivInfo civ) implements WorldEvent {
	}

	/** The viewer's civilization learned these technologies (Civ's keys without "TECH_"). */
	record TechsLearned(java.util.Set<String> techs) implements WorldEvent {
	}

	/** Something the game tells the player in words: a unit lost, a wonder built elsewhere, a proposal made. */
	record Notice(String text, PlotCoord at) implements WorldEvent {
	}

	/** The viewer's civilization entered another era. */
	record EraChanged(String from, String to) implements WorldEvent {
	}
}
