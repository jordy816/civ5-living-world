package nl.jordythomassen.livingworld.state;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import nl.jordythomassen.livingworld.coords.PlotCoord;

/**
 * One consistent picture of the Civ game as the viewing player knows it. Immutable: the link
 * thread builds a new one and hands it over, the Minecraft thread only ever reads.
 */
public final class WorldState {
	private final SessionInfo session;
	private final int turn;
	private final Legend legend;
	private final Map<Integer, CivInfo> civs;
	private final PlotState[] plots;
	private final Map<EntityKey, UnitState> units;
	private final Map<EntityKey, CityState> cities;
	private final Empire empire;
	private final java.util.Set<String> techs;
	private final Map<Integer, Relation> relations;

	public WorldState(SessionInfo session, int turn, Legend legend, Map<Integer, CivInfo> civs, PlotState[] plots,
		Map<EntityKey, UnitState> units, Map<EntityKey, CityState> cities) {
		this(session, turn, legend, civs, plots, units, cities, null, java.util.Set.of(), Map.of());
	}

	public WorldState(SessionInfo session, int turn, Legend legend, Map<Integer, CivInfo> civs, PlotState[] plots,
		Map<EntityKey, UnitState> units, Map<EntityKey, CityState> cities, Empire empire, java.util.Set<String> techs,
		Map<Integer, Relation> relations) {
		this.empire = empire;
		this.techs = java.util.Set.copyOf(techs);
		this.relations = Collections.unmodifiableMap(new TreeMap<>(relations));
		if (plots.length != session.grid().width() * session.grid().height()) {
			throw new IllegalArgumentException("expected " + session.grid().width() * session.grid().height() + " plots, got " + plots.length);
		}
		this.session = session;
		this.turn = turn;
		this.legend = legend;
		this.civs = Collections.unmodifiableMap(new TreeMap<>(civs));
		this.plots = plots.clone();
		this.units = Collections.unmodifiableMap(new TreeMap<>(units));
		this.cities = Collections.unmodifiableMap(new TreeMap<>(cities));
	}

	public SessionInfo session() {
		return session;
	}

	public int turn() {
		return turn;
	}

	public Legend legend() {
		return legend;
	}

	public Map<Integer, CivInfo> civs() {
		return civs;
	}

	public CivInfo civ(int id) {
		return civs.get(id);
	}

	/** The plot at these coordinates; {@link PlotState#UNKNOWN} when off the map. */
	public PlotState plot(PlotCoord at) {
		PlotCoord inside = session.grid().normalize(at);
		return inside == null ? PlotState.UNKNOWN : plots[session.grid().index(inside)];
	}

	public PlotState plot(int index) {
		return plots[index];
	}

	public int plotCount() {
		return plots.length;
	}

	public Map<EntityKey, UnitState> units() {
		return units;
	}

	public Map<EntityKey, CityState> cities() {
		return cities;
	}

	/** The viewer's empire overview; null until Civ has sent it. */
	public Empire empire() {
		return empire;
	}

	/** Technologies the viewer's civilization knows (Civ's keys without "TECH_"). */
	public java.util.Set<String> techs() {
		return techs;
	}

	public boolean knows(String tech) {
		return techs.contains(tech);
	}

	/** How the other players the viewer has met stand to the viewer, by player id. */
	public Map<Integer, Relation> relations() {
		return relations;
	}

	public CityState cityAt(PlotCoord at) {
		for (CityState city : cities.values()) {
			if (city.at().equals(at)) {
				return city;
			}
		}
		return null;
	}

	/** A copy with another turn number, units and cities (the parts that change most often). */
	public WorldState with(int newTurn, Map<Integer, CivInfo> newCivs, Map<EntityKey, UnitState> newUnits, Map<EntityKey, CityState> newCities) {
		return new WorldState(session, newTurn, legend, newCivs, plots, newUnits, newCities, empire, techs, relations);
	}

	public WorldState withPlots(PlotState[] newPlots) {
		return new WorldState(session, turn, legend, civs, newPlots, units, cities, empire, techs, relations);
	}
}
