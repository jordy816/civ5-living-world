package nl.jordythomassen.livingworld.mock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import nl.jordythomassen.livingworld.coords.HexGrid;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.protocol.Records;
import nl.jordythomassen.livingworld.protocol.Wire;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.UnitState;

/**
 * A stand-in for a running Civilization V game, for tests and for trying the Minecraft side
 * without Civ. It keeps a small world (Rome, Egypt, a city-state, barbarians) and answers in the
 * same wire format as our Lua, so everything above the transport runs unchanged against it.
 *
 * <p>It is deliberately not a strategy game: units wander and hammers tick up so there is
 * something to see change. Not thread-safe; the transport serialises access.
 */
public final class MockCiv {
	public static final int ROME = 0;
	public static final int EGYPT = 1;
	public static final int GENEVA = 22;
	public static final int BARBARIANS = 63;

	static final int GRASS = 0, PLAINS = 1, DESERT = 2, TUNDRA = 3, SNOW = 4, COAST = 5, OCEAN = 6;
	static final int F_JUNGLE = 1, F_MARSH = 2, F_FOREST = 5;
	static final int I_FARM = 3, I_MINE = 4;
	static final int R_ROAD = 0;

	/** Hex spacing of the pretend engine, in its world units. */
	static final double WORLD_STEP_X = 64.0;
	static final double WORLD_STEP_Y = 64.0 * Math.sqrt(3.0) / 2.0;

	public static final class Unit {
		public final int owner;
		public final int id;
		public final String type;
		public final String name;
		public int x;
		public int y;
		public int hp = 100;
		public int moves;
		public final int maxMoves;
		public final int strength;
		public boolean dead;
		PlotCoord goal;

		Unit(int owner, int id, String type, String name, int x, int y, int maxMoves, int strength) {
			this.owner = owner;
			this.id = id;
			this.type = type;
			this.name = name;
			this.x = x;
			this.y = y;
			this.maxMoves = maxMoves;
			this.moves = maxMoves * 60;
			this.strength = strength;
		}
	}

	public static final class City {
		public int owner;
		public int id;
		public final String name;
		public final int x;
		public final int y;
		public int population = 1;
		public int production;
		public int productionNeeded = 40;
		public String productionType = "UNIT_WARRIOR";
		public String productionName = "Warrior";
		public final List<String> buildings = new ArrayList<>(List.of("PALACE"));
		public final List<String> wonders = new ArrayList<>();
		public int food = 3;
		public final boolean capital;

		City(int owner, int id, String name, int x, int y, boolean capital) {
			this.owner = owner;
			this.id = id;
			this.name = name;
			this.x = x;
			this.y = y;
			this.capital = capital;
		}
	}

	public final int width;
	public final int height;
	public final HexGrid grid;
	public final List<Unit> units = new ArrayList<>();
	public final List<City> cities = new ArrayList<>();
	private final int[] terrain;
	private final int[] feature;
	private final int[] elevation;
	private final int[] owner;
	private final int[] improvement;
	private final int[] resource;
	private final int[] route;
	private final int[] rivers;
	private final boolean[] revealed;
	private final Random random;
	private final Map<String, String> answered = new LinkedHashMap<>();
	private final List<String> pendingHints = new ArrayList<>();

	public String bootId = "mock-boot-1";
	public final String gameId;
	public int turn;
	public boolean viewerTurn = true;
	public boolean atWarWithEgypt;
	public boolean loaded;
	public boolean inMatch = true;
	public long unitsRev, citiesRev, plotsRev, civsRev;
	private long hintNumber;
	private int nextId = 8192;

	public int gold = 620;
	/** Policies adopted, and whether the game is waiting for the next one (it then refuses to end the turn). */
	public int policies;
	public boolean policyDue;
	public String era = "ERA_ANCIENT";
	public String researchType = "TECH_POTTERY";
	public String researchName = "Pottery";
	public final java.util.Set<String> techs = new java.util.TreeSet<>(List.of("AGRICULTURE"));
	public int genevaInfluence = 10;

	public double markerU, markerV;
	public int markerPlotX = -1, markerPlotY = -1;
	public boolean markerShown;
	public boolean markerFollow;
	public String markerLabel = "";
	public int ticks;

	public MockCiv(int width, int height, long seed) {
		this.width = width;
		this.height = height;
		this.grid = new HexGrid(width, height, false, 0.5);
		this.random = new Random(seed);
		this.gameId = "mock-" + width + "x" + height + "-" + Long.toHexString(seed);
		int n = width * height;
		terrain = new int[n];
		feature = new int[n];
		elevation = new int[n];
		owner = new int[n];
		improvement = new int[n];
		resource = new int[n];
		route = new int[n];
		rivers = new int[n];
		revealed = new boolean[n];
		generate();
	}

	private int idx(int x, int y) {
		return y * width + x;
	}

	private void generate() {
		java.util.Arrays.fill(owner, -1);
		java.util.Arrays.fill(feature, -1);
		java.util.Arrays.fill(improvement, -1);
		java.util.Arrays.fill(resource, -1);
		java.util.Arrays.fill(route, -1);
		double cx = (width - 1) / 2.0;
		double cy = (height - 1) / 2.0;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int i = idx(x, y);
				// An island: land in the middle, coast, then ocean.
				double dx = (x - cx) / (width / 2.0);
				double dy = (y - cy) / (height / 2.0);
				double edge = Math.sqrt(dx * dx + dy * dy) + (random.nextDouble() - 0.5) * 0.18;
				if (edge > 0.92) {
					terrain[i] = OCEAN;
				} else if (edge > 0.78) {
					terrain[i] = COAST;
				} else {
					double lat = Math.abs(dy);
					terrain[i] = lat > 0.62 ? TUNDRA : (random.nextDouble() < 0.5 ? GRASS : PLAINS);
					if (lat < 0.2 && random.nextDouble() < 0.25) {
						terrain[i] = DESERT;
					}
					double roll = random.nextDouble();
					if (roll < 0.06) {
						elevation[i] = PlotState.MOUNTAIN;
					} else if (roll < 0.26) {
						elevation[i] = PlotState.HILLS;
					}
					if (elevation[i] != PlotState.MOUNTAIN && terrain[i] != DESERT) {
						double f = random.nextDouble();
						if (f < 0.22) {
							feature[i] = F_FOREST;
						} else if (f < 0.28 && lat < 0.3) {
							feature[i] = F_JUNGLE;
						} else if (f < 0.31 && elevation[i] == PlotState.FLAT) {
							feature[i] = F_MARSH;
						}
					}
				}
			}
		}
		// A river running north through the middle.
		int rx = width / 2;
		for (int y = height / 4; y < height * 3 / 4; y++) {
			if (land(rx, y)) {
				rivers[idx(rx, y)] |= PlotState.RIVER_EAST;
			}
		}
		PlotCoord rome = settle(width / 2 - 3, height / 2);
		PlotCoord thebes = settle(width / 2 + 6, height / 2 + 3);
		// Close enough to Rome to be in sight from the first turn.
		PlotCoord geneva = settle(width / 2 - 3, height / 2 - 3);
		found(ROME, "Rome", rome, true);
		found(EGYPT, "Thebes", thebes, true);
		found(GENEVA, "Geneva", geneva, true);
		spawn(ROME, "UNIT_WARRIOR", "Warrior", rome.x() + 1, rome.y(), 2, 8);
		spawn(ROME, "UNIT_WORKER", "Worker", rome.x(), rome.y() + 1, 2, 0);
		spawn(EGYPT, "UNIT_WARRIOR", "Warrior", thebes.x() - 1, thebes.y(), 2, 8);
		spawn(EGYPT, "UNIT_SCOUT", "Scout", thebes.x() - 2, thebes.y() - 1, 2, 5);
		spawn(GENEVA, "UNIT_WARRIOR", "Warrior", geneva.x(), geneva.y() + 1, 2, 8);
		spawn(BARBARIANS, "UNIT_BARBARIAN_WARRIOR", "Brute", rome.x() - 4, rome.y() + 3, 2, 8);
		// A farm and a road by Rome, so improvements and routes are exercised.
		int farm = idx(rome.x() + 1, rome.y() - 1);
		if (terrain[farm] < COAST && elevation[farm] == PlotState.FLAT) {
			feature[farm] = -1;
			improvement[farm] = I_FARM;
		}
		route[idx(rome.x() + 1, rome.y())] = R_ROAD;
		// Iron two plots east of Rome, on open ground.
		int iron = idx(rome.x() + 2, rome.y());
		terrain[iron] = PLAINS;
		elevation[iron] = PlotState.FLAT;
		feature[iron] = -1;
		resource[iron] = 1;
		reveal();
	}

	private boolean land(int x, int y) {
		return x >= 0 && y >= 0 && x < width && y < height && terrain[idx(x, y)] < COAST;
	}

	/** Makes the plot (and nothing else) fit for a city: flat grass, no feature. */
	private PlotCoord settle(int x, int y) {
		int i = idx(x, y);
		terrain[i] = GRASS;
		elevation[i] = PlotState.FLAT;
		feature[i] = -1;
		return new PlotCoord(x, y);
	}

	private void found(int player, String name, PlotCoord at, boolean capital) {
		cities.add(new City(player, nextId++, name, at.x(), at.y(), capital));
		owner[idx(at.x(), at.y())] = player;
		for (PlotCoord n : grid.neighbors(at)) {
			owner[idx(n.x(), n.y())] = player;
		}
		citiesRev++;
		plotsRev++;
	}

	public Unit spawn(int player, String type, String name, int x, int y, int maxMoves, int strength) {
		Unit unit = new Unit(player, nextId++, type, name, x, y, maxMoves, strength);
		units.add(unit);
		unitsRev++;
		hint("UnitCreated", player, unit.id, x, y);
		return unit;
	}

	private void hint(String type, Object... args) {
		StringBuilder line = new StringBuilder(Wire.PREFIX).append("E\t").append(++hintNumber).append('\t').append(type);
		for (Object arg : args) {
			line.append('\t').append(arg);
		}
		pendingHints.add(line.toString());
	}

	/** What Rome can see: everything within three plots of its units and cities. */
	private void reveal() {
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				if (visibleToRome(new PlotCoord(x, y), 3)) {
					revealed[idx(x, y)] = true;
				}
			}
		}
	}

	private boolean visibleToRome(PlotCoord at, int range) {
		for (Unit unit : units) {
			if (unit.owner == ROME && !unit.dead && grid.distance(at, new PlotCoord(unit.x, unit.y)) <= range - 1) {
				return true;
			}
		}
		for (City city : cities) {
			if (city.owner == ROME && grid.distance(at, new PlotCoord(city.x, city.y)) <= range) {
				return true;
			}
		}
		return false;
	}

	public Unit unit(int player, int id) {
		for (Unit unit : units) {
			if (unit.owner == player && unit.id == id && !unit.dead) {
				return unit;
			}
		}
		return null;
	}

	public City city(int player, int id) {
		for (City city : cities) {
			if (city.owner == player && city.id == id) {
				return city;
			}
		}
		return null;
	}

	public City cityNamed(String name) {
		for (City city : cities) {
			if (city.name.equals(name)) {
				return city;
			}
		}
		return null;
	}

	// ---- things a test (or the demo clock) can make happen ----

	public void moveUnit(Unit unit, int x, int y) {
		unit.x = x;
		unit.y = y;
		unitsRev++;
		hint("UnitSetXY", unit.owner, unit.id, x, y);
		if (unit.owner == ROME) {
			int before = countRevealed();
			reveal();
			if (countRevealed() != before) {
				plotsRev++;
			}
		}
	}

	private int countRevealed() {
		int n = 0;
		for (boolean r : revealed) {
			if (r) {
				n++;
			}
		}
		return n;
	}

	public void killUnit(Unit unit) {
		unit.dead = true;
		unitsRev++;
		hint("UnitKilled", unit.owner, unit.id);
	}

	public void declareWar(boolean war) {
		atWarWithEgypt = war;
		civsRev++;
		hint(war ? "DeclareWar" : "MakePeace", EGYPT, ROME);
	}

	public void captureCity(City city, int newOwner) {
		int old = city.owner;
		city.owner = newOwner;
		city.id = nextId++;
		for (int i = 0; i < owner.length; i++) {
			if (owner[i] == old) {
				owner[i] = newOwner;
			}
		}
		citiesRev++;
		plotsRev++;
		hint("CityCaptureComplete", old, newOwner, city.x, city.y);
	}

	public void setImprovement(int x, int y, int type) {
		improvement[idx(x, y)] = type;
		plotsRev++;
	}

	/** A new game was loaded: our Lua is gone and has to be sent again. */
	public void reload(String newBootId) {
		loaded = false;
		bootId = newBootId;
		answered.clear();
		pendingHints.clear();
	}

	/** One turn passes: every unit gets its moves back and walks, cities build. */
	public void endTurn() {
		turn++;
		for (Unit unit : units) {
			if (unit.dead) {
				continue;
			}
			unit.moves = unit.maxMoves * 60;
			if (unit.hp < 100) {
				unit.hp = Math.min(100, unit.hp + 10);
				unitsRev++;
			}
			PlotCoord at = new PlotCoord(unit.x, unit.y);
			PlotCoord step = null;
			if (unit.goal != null) {
				step = toward(at, unit.goal);
				if (step == null || step.equals(unit.goal)) {
					unit.goal = null;
				}
			} else if (unit.owner != ROME) {
				List<PlotCoord> options = new ArrayList<>();
				for (PlotCoord n : grid.neighbors(at)) {
					if (walkable(n)) {
						options.add(n);
					}
				}
				if (!options.isEmpty() && random.nextInt(3) != 0) {
					step = options.get(random.nextInt(options.size()));
				}
			}
			if (step != null) {
				moveUnit(unit, step.x(), step.y());
			}
		}
		for (City city : cities) {
			city.production += 4 + city.population;
			city.food += 2;
			if (city.production >= city.productionNeeded) {
				city.production = 0;
				spawn(city.owner, city.productionType, city.productionName, city.x, city.y, 2, 8);
				hint("CityTrained", city.owner, city.id);
			}
			if (city.food >= 15 + city.population * 8) {
				city.food = 0;
				city.population++;
			}
		}
		if (!researchType.isEmpty() && turn % 3 == 0) {
			techs.add(researchType.substring(5));
			researchType = "";
			researchName = "";
			civsRev++;
		}
		citiesRev++;
		hint("TurnStart", turn);
	}

	private boolean walkable(PlotCoord p) {
		int i = idx(p.x(), p.y());
		if (terrain[i] >= COAST || elevation[i] == PlotState.MOUNTAIN) {
			return false;
		}
		for (Unit other : units) {
			if (!other.dead && other.x == p.x() && other.y == p.y()) {
				return false;
			}
		}
		return true;
	}

	private PlotCoord toward(PlotCoord from, PlotCoord goal) {
		PlotCoord best = null;
		int bestDistance = grid.distance(from, goal);
		for (PlotCoord n : grid.neighbors(from)) {
			int d = grid.distance(n, goal);
			if (d < bestDistance && (walkable(n) || n.equals(goal) && cityAt(n) == null && walkable(n))) {
				bestDistance = d;
				best = n;
			}
		}
		return best;
	}

	private City cityAt(PlotCoord p) {
		for (City city : cities) {
			if (city.x == p.x() && city.y == p.y()) {
				return city;
			}
		}
		return null;
	}

	// ---- the wire side: what our Lua would print ----

	private static String line(Object... fields) {
		StringBuilder out = new StringBuilder(Wire.PREFIX);
		for (int i = 0; i < fields.length; i++) {
			if (i > 0) {
				out.append('\t');
			}
			out.append(Wire.escape(String.valueOf(fields[i])));
		}
		return out.toString();
	}

	String heartbeat() {
		return line("S", Wire.PROTOCOL, bootId, gameId, turn, ROME, ROME, width, height, 0, unitsRev, citiesRev, plotsRev, civsRev, viewerTurn ? 1 : 0);
	}

	List<String> tick(double u, double v, boolean show, boolean follow, String label) {
		ticks++;
		markerU = u;
		markerV = v;
		markerShown = show;
		markerFollow = follow;
		markerLabel = label;
		List<String> out = new ArrayList<>();
		out.add(heartbeat());
		out.addAll(pendingHints);
		pendingHints.clear();
		return out;
	}

	List<String> snap(int mask) {
		List<String> out = new ArrayList<>();
		out.add(heartbeat());
		if ((mask & 1) != 0) {
			out.add(line("G", 0.0, 0.0, WORLD_STEP_X, 0.0, WORLD_STEP_X / 2, WORLD_STEP_Y, 0.0, WORLD_STEP_Y * 2));
			String[][] legend = {
				{ "T", "0", "TERRAIN_GRASS", "Grassland" }, { "T", "1", "TERRAIN_PLAINS", "Plains" }, { "T", "2", "TERRAIN_DESERT", "Desert" },
				{ "T", "3", "TERRAIN_TUNDRA", "Tundra" }, { "T", "4", "TERRAIN_SNOW", "Snow" }, { "T", "5", "TERRAIN_COAST", "Coast" },
				{ "T", "6", "TERRAIN_OCEAN", "Ocean" }, { "F", "0", "FEATURE_ICE", "Ice" }, { "F", "1", "FEATURE_JUNGLE", "Jungle" },
				{ "F", "2", "FEATURE_MARSH", "Marsh" }, { "F", "3", "FEATURE_OASIS", "Oasis" }, { "F", "4", "FEATURE_FLOOD_PLAINS", "Flood Plains" },
				{ "F", "5", "FEATURE_FOREST", "Forest" }, { "I", "3", "IMPROVEMENT_FARM", "Farm" }, { "I", "4", "IMPROVEMENT_MINE", "Mine" },
				{ "O", "0", "ROUTE_ROAD", "Road" }, { "O", "1", "ROUTE_RAILROAD", "Railroad" },
				{ "R", "1", "RESOURCE_IRON", "Iron" }, { "R", "2", "RESOURCE_WHEAT", "Wheat" },
			};
			out.add(line("N", "L", legend.length));
			for (String[] entry : legend) {
				out.add(line("L", entry[0], entry[1], entry[2], entry[3]));
			}
		}
		if ((mask & (1 | 16)) != 0) {
			out.add(line("N", "V", 4));
			out.add(line("V", ROME, ROME, "CIVILIZATION_ROME", "Rome", "Roman", "Augustus Caesar",
				Records.CIV_ALIVE | Records.CIV_HUMAN | Records.CIV_MET, 0x460076, 0xEFC600, era, "ARTSTYLE_GRECO_ROMAN"));
			out.add(line("V", EGYPT, EGYPT, "CIVILIZATION_EGYPT", "Egypt", "Egyptian", "Ramesses II",
				Records.CIV_ALIVE | Records.CIV_MET | (atWarWithEgypt ? Records.CIV_AT_WAR : 0), 0xFFFB03, 0x5200D0, "ERA_ANCIENT", "ARTSTYLE_MIDDLE_EAST"));
			out.add(line("V", GENEVA, GENEVA, "MINOR_CIV_GENEVA", "Geneva", "Genevan", "",
				Records.CIV_ALIVE | Records.CIV_MINOR | Records.CIV_MET, 0x202020, 0xE0E0E0, "ERA_ANCIENT", "ARTSTYLE_EUROPEAN"));
			out.add(line("V", BARBARIANS, BARBARIANS, "CIVILIZATION_BARBARIAN", "Barbarians", "Barbarian", "",
				Records.CIV_ALIVE | Records.CIV_BARBARIAN | Records.CIV_AT_WAR | Records.CIV_MET, 0x8A0000, 0x000000, "ERA_ANCIENT", ""));
		}
		if ((mask & 2) != 0) {
			out.add(line("N", "P", height));
			for (int y = 0; y < height; y++) {
				StringBuilder cells = new StringBuilder();
				for (int x = 0; x < width; x++) {
					if (x > 0) {
						cells.append(';');
					}
					int i = idx(x, y);
					if (!revealed[i]) {
						cells.append('?');
						continue;
					}
					int flags = (visibleToRome(new PlotCoord(x, y), 3) ? PlotState.F_VISIBLE : 0)
						| (cityAt(new PlotCoord(x, y)) != null ? PlotState.F_CITY : 0)
						| (terrain[i] >= COAST ? PlotState.F_WATER : 0);
					cells.append(terrain[i]).append(',').append(feature[i]).append(',').append(elevation[i]).append(',').append(owner[i])
						.append(',').append(improvement[i]).append(',').append(resource[i]).append(',').append(route[i]).append(',').append(rivers[i])
						.append(',').append(flags);
				}
				out.add(line("P", y, 0, cells));
			}
		}
		if ((mask & 32) != 0) {
			out.add(line("Y", gold, 3, 2, 4, 0, 1, 25, 0, era, -4000 + turn * 40, researchType, researchName, researchType.isEmpty() ? 0 : 6,
				policies, policyDue ? "POLICY" : ""));
			out.add(line("N", "K", 1));
			out.add(line("K", String.join(",", techs)));
			out.add(line("N", "M", 2));
			out.add(line("M", EGYPT, 0, 0, atWarWithEgypt ? 0 : 4, "NEUTRAL", -1, "", 0, 0, 0));
			out.add(line("M", GENEVA, 1, genevaInfluence, genevaInfluence >= 60 ? 2 : genevaInfluence >= 30 ? 1 : 0, "CULTURED", -1, "KILL_CAMP", 250, 500, 1000));
		}
		if ((mask & 4) != 0) {
			List<String> rows = new ArrayList<>();
			for (Unit unit : units) {
				PlotCoord at = new PlotCoord(unit.x, unit.y);
				if (unit.dead || !visibleToRome(at, 3)) {
					continue;
				}
				rows.add(line("U", unit.owner, unit.id, unit.type, unit.name, unit.x, unit.y, unit.hp, 100, unit.moves, unit.maxMoves * 60,
					unit.strength, 0, "DOMAIN_LAND", 1, 0, unit.strength > 0 ? UnitState.F_COMBAT : 0));
			}
			out.add(line("N", "U", rows.size()));
			out.addAll(rows);
		}
		if ((mask & 8) != 0) {
			List<String> rows = new ArrayList<>();
			for (City city : cities) {
				if (!revealed[idx(city.x, city.y)]) {
					continue;
				}
				boolean mine = city.owner == ROME;
				rows.add(line("T", city.owner, city.id, city.name, city.x, city.y, city.population,
					(city.capital ? CityState.F_CAPITAL : 0) | (mine ? CityState.F_DETAILS : 0), 200, 200,
					mine ? city.productionType : "", mine ? city.productionName : "", mine ? city.production : 0,
					mine ? city.productionNeeded : 0, mine ? Math.max(1, (city.productionNeeded - city.production) / (4 + city.population)) : 0,
					mine ? city.food : 0, mine ? 15 + city.population * 8 : 0, 8 + city.population,
					mine ? String.join(",", city.buildings) : "", mine ? String.join(",", city.wonders) : ""));
			}
			out.add(line("N", "T", rows.size()));
			out.addAll(rows);
		}
		return out;
	}

	/** The choices our Lua would list: what a city can build, what can be researched, what a plot can take. */
	List<String> options(String kind, int a, int c) {
		List<String> rows = new ArrayList<>();
		switch (kind) {
			case "build" -> {
				if (city(ROME, a) != null) {
					rows.add(line("O", 1, 5, "BUILDING_GRANARY", "Granary", 6));
					rows.add(line("O", 1, 6, "BUILDING_MONUMENT", "Monument", 5));
					rows.add(line("O", 0, 10, "UNIT_WARRIOR", "Warrior", 4));
					rows.add(line("O", 0, 11, "UNIT_WORKER", "Worker", 7));
				}
			}
			case "policy" -> {
				if (policyDue) {
					rows.add(line("O", 5, 0, "POLICY_BRANCH_TRADITION", "Tradition", 0));
					rows.add(line("O", 5, 1, "POLICY_BRANCH_LIBERTY", "Liberty", 0));
				}
			}
			case "research" -> {
				if (!techs.contains("BRONZE_WORKING")) {
					rows.add(line("O", 0, 3, "TECH_BRONZE_WORKING", "Bronze Working", 8));
				}
				rows.add(line("O", 0, 4, "TECH_WRITING", "Writing", 9));
			}
			default -> {
				boolean ours = a >= 0 && c >= 0 && a < width && c < height && owner[idx(a, c)] == ROME && terrain[idx(a, c)] < COAST
					&& elevation[idx(a, c)] != PlotState.MOUNTAIN && cityAt(new PlotCoord(a, c)) == null;
				if (ours) {
					if (improvement[idx(a, c)] != I_FARM && feature[idx(a, c)] != F_FOREST) {
						rows.add(line("O", 4, 1, "BUILD_FARM", "Farm", 0));
					}
					if (route[idx(a, c)] < 0) {
						rows.add(line("O", 4, 2, "BUILD_ROAD", "Road", 0));
					}
					if (feature[idx(a, c)] == F_FOREST && techs.contains("BRONZE_WORKING")) {
						rows.add(line("O", 4, 3, "BUILD_REMOVE_FOREST", "Clear the forest", 0));
					}
				}
			}
		}
		List<String> out = new ArrayList<>();
		out.add(line("N", "O", rows.size()));
		out.addAll(rows);
		return out;
	}

	/** Makes a plot the viewer's own forest or open land (for tests of plot works). */
	public void setPlot(int x, int y, int newOwner, boolean forest) {
		owner[idx(x, y)] = newOwner;
		feature[idx(x, y)] = forest ? F_FOREST : -1;
		terrain[idx(x, y)] = GRASS;
		elevation[idx(x, y)] = PlotState.FLAT;
		improvement[idx(x, y)] = -1;
		route[idx(x, y)] = -1;
		revealed[idx(x, y)] = true;
		plotsRev++;
	}

	/** Carries out a command the way our Lua does, including answering a repeat with the first answer. */
	List<String> command(String id, String op, int player, int entity, int x, int y, int amount) {
		String previous = answered.get(id);
		if (previous != null) {
			return List.of(heartbeat(), previous);
		}
		String result;
		if (player != ROME) {
			result = line("R", id, 0, "NOT_YOURS", "That does not belong to the active player.");
		} else if (!viewerTurn) {
			result = line("R", id, 0, "NOT_YOUR_TURN", "It is not the player's turn.");
		} else {
			switch (op) {
				case "build" -> {
					City city = city(player, entity);
					String[][] known = { { "0", "10", "UNIT_WARRIOR", "Warrior" }, { "0", "11", "UNIT_WORKER", "Worker" }, { "1", "5", "BUILDING_GRANARY", "Granary" },
						{ "1", "6", "BUILDING_MONUMENT", "Monument" } };
					String[] chosen = null;
					for (String[] k : known) {
						if (Integer.parseInt(k[0]) == x && Integer.parseInt(k[1]) == y) {
							chosen = k;
						}
					}
					if (city == null || chosen == null) {
						result = line("R", id, 0, "CANNOT", "It cannot build that right now.");
					} else {
						city.productionType = chosen[2];
						city.productionName = chosen[3];
						city.production = 0;
						citiesRev++;
						result = line("R", id, 1, "OK", city.name + " has new orders.");
					}
				}
				case "research" -> {
					if (y == 3 && !techs.contains("BRONZE_WORKING")) {
						researchType = "TECH_BRONZE_WORKING";
						researchName = "Bronze Working";
						civsRev++;
						result = line("R", id, 1, "OK", "Research chosen.");
					} else {
						result = line("R", id, 0, "CANNOT", "That cannot be researched right now.");
					}
				}
				case "reveal" -> {
					if (x < 0 || y < 0 || x >= width || y >= height || !revealed[idx(x, y)]) {
						result = line("R", id, 0, "BAD_PLOT", "That spot is not on your map.");
					} else {
						int found = 0;
						PlotCoord centre = new PlotCoord(x, y);
						for (int py = 0; py < height; py++) {
							for (int px = 0; px < width; px++) {
								if (!revealed[idx(px, py)] && grid.distance(centre, new PlotCoord(px, py)) <= Math.max(1, Math.min(2, amount))) {
									revealed[idx(px, py)] = true;
									found++;
								}
							}
						}
						if (found > 0) {
							plotsRev++;
						}
						result = line("R", id, 1, "OK", "Explored.", "before=0", "after=" + found);
					}
				}
				case "improve" -> {
					boolean ours = x >= 0 && y >= 0 && x < width && y < height && owner[idx(x, y)] == ROME && terrain[idx(x, y)] < COAST
						&& elevation[idx(x, y)] != PlotState.MOUNTAIN && cityAt(new PlotCoord(x, y)) == null;
					if (!ours) {
						result = line("R", id, 0, "CANNOT", "That cannot be built here.");
					} else if (amount == 1 && improvement[idx(x, y)] != I_FARM && feature[idx(x, y)] != F_FOREST) {
						improvement[idx(x, y)] = I_FARM;
						plotsRev++;
						result = line("R", id, 1, "OK", "Done.", "kind=imp");
					} else if (amount == 2 && route[idx(x, y)] < 0) {
						route[idx(x, y)] = R_ROAD;
						plotsRev++;
						result = line("R", id, 1, "OK", "Done.", "kind=route");
					} else if (amount == 3 && feature[idx(x, y)] == F_FOREST && techs.contains("BRONZE_WORKING")) {
						feature[idx(x, y)] = -1;
						plotsRev++;
						result = line("R", id, 1, "OK", "Done.", "kind=clear");
					} else {
						result = line("R", id, 0, "CANNOT", "That cannot be built here.");
					}
				}
				case "gift" -> {
					if (entity != GENEVA || (amount != 250 && amount != 500 && amount != 1000)) {
						result = line("R", id, 0, "NO_MINOR", "That is not a city-state you know.");
					} else if (gold < amount) {
						result = line("R", id, 0, "NO_GOLD", "Your treasury does not hold " + amount + " gold.");
					} else {
						int before = genevaInfluence;
						gold -= amount;
						genevaInfluence += amount / 10;
						civsRev++;
						result = line("R", id, 1, "OK", "Gift sent.", "before=" + before, "after=" + (before + 1));
					}
				}
				case "war" -> {
					if (entity != EGYPT || atWarWithEgypt) {
						result = line("R", id, 0, "CANNOT", "You cannot declare war on them right now.");
					} else {
						declareWar(true);
						result = line("R", id, 1, "OK", "War declared.");
					}
				}
				case "turn" -> {
					if (policyDue) {
						result = line("R", id, 0, "BLOCKED", "Civilization V still needs a decision from you first: adopt a social policy.");
					} else {
						int before = turn;
						endTurn();
						result = line("R", id, 1, "OK", "Turn ended.", "before=" + before, "after=" + turn);
					}
				}
				case "policy" -> {
					if (!policyDue || x != 5 || y < 0 || y > 1) {
						result = line("R", id, 0, "CANNOT", "That policy cannot be adopted right now.");
					} else {
						int before = policies;
						policies++;
						policyDue = false;
						civsRev++;
						result = line("R", id, 1, "OK", "Policy adopted.", "before=" + before, "after=" + policies);
					}
				}
				case "move" -> {
					Unit unit = unit(player, entity);
					if (unit == null) {
						result = line("R", id, 0, "NO_UNIT", "No such unit.");
					} else if (unit.moves <= 0) {
						unit.goal = new PlotCoord(x, y);
						result = line("R", id, 1, "QUEUED", "The unit will set out next turn.", "x=" + unit.x, "y=" + unit.y);
					} else {
						PlotCoord goal = new PlotCoord(x, y);
						PlotCoord step = toward(new PlotCoord(unit.x, unit.y), goal);
						if (step == null) {
							result = line("R", id, 0, "NO_PATH", "The unit cannot get there.");
						} else {
							unit.moves = Math.max(0, unit.moves - 60);
							moveUnit(unit, step.x(), step.y());
							unit.goal = step.equals(goal) ? null : goal;
							result = line("R", id, 1, "OK", "On the way.", "x=" + unit.x, "y=" + unit.y);
						}
					}
				}
				case "prod" -> {
					City city = city(player, entity);
					if (city == null) {
						result = line("R", id, 0, "NO_CITY", "No such city.");
					} else {
						int before = city.production;
						city.production += amount;
						citiesRev++;
						result = line("R", id, 1, "OK", "Delivered.", "before=" + before, "after=" + city.production);
					}
				}
				case "heal" -> {
					Unit unit = unit(player, entity);
					if (unit == null) {
						result = line("R", id, 0, "NO_UNIT", "No such unit.");
					} else {
						int before = unit.hp;
						unit.hp = Math.min(100, unit.hp + amount);
						unitsRev++;
						result = line("R", id, 1, "OK", "Patched up.", "before=" + before, "after=" + unit.hp);
					}
				}
				default -> result = line("R", id, 0, "UNKNOWN_OP", "Unknown operation.");
			}
		}
		answered.put(id, result);
		return List.of(heartbeat(), result);
	}
}
