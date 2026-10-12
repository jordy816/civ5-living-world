package nl.jordythomassen.livingworld.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import nl.jordythomassen.livingworld.coords.PlotCoord;

/** Turns "before" and "after" pictures of the Civ game into the list of things that changed. */
public final class WorldDiff {
	private WorldDiff() {
	}

	public static List<WorldEvent> between(WorldState before, WorldState after) {
		List<WorldEvent> out = new ArrayList<>();
		if (before == null || !sameSession(before.session(), after.session())) {
			// A different game or a reload: everything is new, the old picture is not comparable.
			out.add(new WorldEvent.SessionStarted(after.session()));
			return out;
		}
		if (before.turn() != after.turn()) {
			out.add(new WorldEvent.TurnChanged(before.turn(), after.turn()));
		}
		for (CivInfo civ : after.civs().values()) {
			CivInfo old = before.civ(civ.id());
			if (old == null || !old.equals(civ)) {
				if (old != null && old.atWarWithViewer() != civ.atWarWithViewer()) {
					out.add(new WorldEvent.WarChanged(civ, civ.atWarWithViewer()));
				} else {
					out.add(new WorldEvent.CivChanged(civ));
				}
			}
		}
		for (int i = 0; i < after.plotCount(); i++) {
			PlotState now = after.plot(i);
			PlotState old = before.plot(i);
			if (!now.equals(old)) {
				int width = after.session().grid().width();
				out.add(new WorldEvent.PlotChanged(new PlotCoord(i % width, i / width), now, old));
			}
		}
		cities(before, after, out);
		units(before, after, out);
		if (!before.techs().isEmpty() && !before.techs().equals(after.techs())) {
			java.util.Set<String> learned = new java.util.TreeSet<>(after.techs());
			learned.removeAll(before.techs());
			if (!learned.isEmpty()) {
				out.add(new WorldEvent.TechsLearned(learned));
			}
		}
		if (before.empire() != null && after.empire() != null && !before.empire().era().equals(after.empire().era())) {
			out.add(new WorldEvent.EraChanged(before.empire().era(), after.empire().era()));
		}
		return out;
	}

	private static boolean sameSession(SessionInfo a, SessionInfo b) {
		return a.gameId().equals(b.gameId()) && a.bootId().equals(b.bootId()) && a.viewer() == b.viewer();
	}

	private static void cities(WorldState before, WorldState after, List<WorldEvent> out) {
		// A captured city keeps its plot but gets a new owner and a new id: match those by plot.
		Map<PlotCoord, CityState> vanishedByPlot = new HashMap<>();
		for (CityState old : before.cities().values()) {
			if (!after.cities().containsKey(old.key())) {
				vanishedByPlot.put(old.at(), old);
			}
		}
		for (CityState city : after.cities().values()) {
			CityState old = before.cities().get(city.key());
			if (old == null) {
				CityState previousOwner = vanishedByPlot.remove(city.at());
				if (previousOwner != null) {
					out.add(new WorldEvent.CityChangedHands(city, previousOwner));
				} else {
					out.add(new WorldEvent.CityAppeared(city));
				}
			} else if (!old.equals(city)) {
				out.add(new WorldEvent.CityChanged(city, old));
			}
		}
		for (CityState gone : vanishedByPlot.values()) {
			out.add(new WorldEvent.CityGone(gone));
		}
	}

	private static void units(WorldState before, WorldState after, List<WorldEvent> out) {
		for (UnitState unit : after.units().values()) {
			UnitState old = before.units().get(unit.key());
			if (old == null) {
				out.add(new WorldEvent.UnitAppeared(unit));
			} else if (!old.at().equals(unit.at())) {
				out.add(new WorldEvent.UnitMoved(unit, old.at()));
			} else if (!old.equals(unit)) {
				out.add(new WorldEvent.UnitChanged(unit, old));
			}
		}
		for (UnitState old : before.units().values()) {
			if (!after.units().containsKey(old.key())) {
				out.add(new WorldEvent.UnitGone(old));
			}
		}
	}
}
