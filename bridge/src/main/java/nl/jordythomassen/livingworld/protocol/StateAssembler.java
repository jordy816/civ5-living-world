package nl.jordythomassen.livingworld.protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import nl.jordythomassen.livingworld.coords.HexGrid;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.Legend;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.SessionInfo;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * Collects the records Civ sends and keeps the latest complete picture.
 *
 * <p>Every list is announced with its length first ({@code N kind count}). A list only replaces
 * the previous one once all of it has arrived, so a dropped line can make the picture older but
 * never half-updated.
 */
public final class StateAssembler {
	private Heartbeat heartbeat;
	private Geometry geometry;
	private Legend legend;
	private Map<Integer, CivInfo> civs;
	private PlotState[] plots;
	private Map<EntityKey, UnitState> units;
	private Map<EntityKey, CityState> cities;
	private nl.jordythomassen.livingworld.state.Empire empire;
	private java.util.Set<String> techs = java.util.Set.of();
	private Map<Integer, nl.jordythomassen.livingworld.state.Relation> relations = Map.of();

	private String pendingKind = "";
	private int pendingLeft;
	private Legend pendingLegend;
	private Map<Integer, CivInfo> pendingCivs;
	private PlotState[] pendingPlots;
	private Map<EntityKey, UnitState> pendingUnits;
	private Map<EntityKey, CityState> pendingCities;
	private java.util.Set<String> pendingTechs;
	private Map<Integer, nl.jordythomassen.livingworld.state.Relation> pendingRelations;
	private List<Records.Option> pendingOptions;
	private List<Records.Option> options;

	private final List<Hint> hints = new ArrayList<>();
	private final List<CommandResult> results = new ArrayList<>();
	private final List<String> problems = new ArrayList<>();
	private int version;

	/** Forget everything: the game was reloaded or another game was started. */
	public void reset() {
		heartbeat = null;
		geometry = null;
		legend = null;
		civs = null;
		plots = null;
		units = null;
		cities = null;
		empire = null;
		techs = java.util.Set.of();
		relations = Map.of();
		options = null;
		pendingKind = "";
		pendingLeft = 0;
		version++;
	}

	public Heartbeat heartbeat() {
		return heartbeat;
	}

	/** Goes up whenever a complete list replaced an older one. */
	public int version() {
		return version;
	}

	public boolean hasStatic() {
		return geometry != null && legend != null && civs != null;
	}

	public boolean hasPlots() {
		return plots != null;
	}

	public List<Hint> drainHints() {
		List<Hint> out = new ArrayList<>(hints);
		hints.clear();
		return out;
	}

	public List<CommandResult> drainResults() {
		List<CommandResult> out = new ArrayList<>(results);
		results.clear();
		return out;
	}

	/** The choices that came with the last "what can I choose" answer; null when none arrived whole. */
	public List<Records.Option> takeOptions() {
		List<Records.Option> out = options;
		options = null;
		return out;
	}

	public List<String> drainProblems() {
		List<String> out = new ArrayList<>(problems);
		problems.clear();
		return out;
	}

	/** Takes the fields of one line. Bad lines are noted and skipped. */
	public void accept(List<String> f) {
		try {
			dispatch(f);
		} catch (Records.BadRecord | IndexOutOfBoundsException e) {
			problems.add(e.getMessage());
			// A broken line inside a list makes that list incomplete: drop it, keep the old one.
			pendingKind = "";
			pendingLeft = 0;
		}
	}

	private void dispatch(List<String> f) {
		switch (f.get(0)) {
			case "S" -> {
				Heartbeat hb = Records.heartbeat(f);
				if (heartbeat != null && (!heartbeat.bootId().equals(hb.bootId()) || !heartbeat.gameId().equals(hb.gameId())
					|| heartbeat.width() != hb.width() || heartbeat.height() != hb.height() || heartbeat.viewer() != hb.viewer())) {
					reset();
				}
				heartbeat = hb;
			}
			case "N" -> begin(f);
			case "G" -> geometry = Records.geometry(f);
			case "L" -> {
				expect("L");
				Records.legend(f, pendingLegend);
				one();
			}
			case "V" -> {
				expect("V");
				CivInfo civ = Records.civ(f);
				pendingCivs.put(civ.id(), civ);
				one();
			}
			case "P" -> {
				expect("P");
				Records.PlotRow row = Records.plotRow(f);
				if (heartbeat == null || row.y() < 0 || row.y() >= heartbeat.height() || row.x0() < 0
					|| row.x0() + row.plots().size() > heartbeat.width()) {
					throw new Records.BadRecord("plot row " + row.y() + " does not fit the map");
				}
				for (int i = 0; i < row.plots().size(); i++) {
					pendingPlots[row.y() * heartbeat.width() + row.x0() + i] = row.plots().get(i);
				}
				one();
			}
			case "U" -> {
				expect("U");
				UnitState unit = Records.unit(f);
				pendingUnits.put(unit.key(), unit);
				one();
			}
			case "T" -> {
				expect("T");
				CityState city = Records.city(f);
				pendingCities.put(city.key(), city);
				one();
			}
			case "Y" -> {
				nl.jordythomassen.livingworld.state.Empire now = Records.empire(f);
				if (!now.equals(empire)) {
					empire = now;
					version++;
				}
			}
			case "K" -> {
				expect("K");
				pendingTechs.addAll(Records.techs(f));
				one();
			}
			case "M" -> {
				expect("M");
				nl.jordythomassen.livingworld.state.Relation relation = Records.relation(f);
				pendingRelations.put(relation.id(), relation);
				one();
			}
			case "O" -> {
				expect("O");
				pendingOptions.add(Records.option(f));
				one();
			}
			case "E" -> hints.add(Records.hint(f));
			case "R" -> results.add(Records.result(f));
			case "X" -> problems.add("Civ reported: " + String.join(" ", f.subList(1, f.size())));
			default -> {
				// Framing records (> and <) and anything newer than this side knows.
			}
		}
	}

	private void begin(List<String> f) {
		if (f.size() < 3) {
			throw new Records.BadRecord("list announcement without a count");
		}
		pendingKind = f.get(1);
		try {
			pendingLeft = Integer.parseInt(f.get(2));
		} catch (NumberFormatException e) {
			throw new Records.BadRecord("list count is not a number: " + f.get(2));
		}
		if (pendingLeft < 0 || pendingLeft > 1_000_000) {
			throw new Records.BadRecord("implausible list count " + pendingLeft);
		}
		switch (pendingKind) {
			case "L" -> pendingLegend = new Legend();
			case "V" -> pendingCivs = new HashMap<>();
			case "U" -> pendingUnits = new HashMap<>();
			case "T" -> pendingCities = new HashMap<>();
			case "K" -> pendingTechs = new java.util.HashSet<>();
			case "M" -> pendingRelations = new HashMap<>();
			case "O" -> pendingOptions = new ArrayList<>();
			case "P" -> {
				if (heartbeat == null) {
					throw new Records.BadRecord("plots before the map size is known");
				}
				// Partial updates start from what we have; a first full send starts from "unknown".
				pendingPlots = plots != null ? plots.clone() : new PlotState[heartbeat.width() * heartbeat.height()];
				if (plots == null) {
					java.util.Arrays.fill(pendingPlots, PlotState.UNKNOWN);
				}
			}
			default -> throw new Records.BadRecord("unknown list kind " + pendingKind);
		}
		if (pendingLeft == 0) {
			complete();
		}
	}

	private void expect(String kind) {
		if (!pendingKind.equals(kind) || pendingLeft <= 0) {
			throw new Records.BadRecord("unannounced " + kind + " record");
		}
	}

	private void one() {
		if (--pendingLeft == 0) {
			complete();
		}
	}

	private void complete() {
		switch (pendingKind) {
			case "L" -> legend = pendingLegend;
			case "V" -> civs = pendingCivs;
			case "U" -> units = pendingUnits;
			case "T" -> cities = pendingCities;
			case "K" -> techs = pendingTechs;
			case "M" -> relations = pendingRelations;
			case "O" -> {
				// The answer to a question, not part of the picture.
				options = pendingOptions;
				pendingKind = "";
				return;
			}
			case "P" -> plots = pendingPlots;
			default -> {
			}
		}
		pendingKind = "";
		version++;
	}

	/** The current picture, or null while a part of it has not arrived yet. */
	public WorldState build() {
		if (heartbeat == null || geometry == null || legend == null || civs == null || plots == null || units == null || cities == null) {
			return null;
		}
		HexGrid grid = new HexGrid(heartbeat.width(), heartbeat.height(), heartbeat.wrapX(), geometry.oddRowShift());
		SessionInfo session = new SessionInfo(heartbeat.protocol(), heartbeat.bootId(), heartbeat.gameId(), heartbeat.viewer(),
			heartbeat.viewerTeam(), grid, geometry.layout());
		return new WorldState(session, heartbeat.turn(), legend, civs, plots, units, cities, empire, techs, relations);
	}
}
