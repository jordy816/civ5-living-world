package nl.jordythomassen.livingworld.unciv;

import com.unciv.logic.GameInfo;
import com.unciv.logic.city.City;
import com.unciv.logic.city.CityConstructions;
import com.unciv.logic.civilization.Civilization;
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags;
import com.unciv.logic.civilization.diplomacy.DiplomacyManager;
import com.unciv.logic.civilization.diplomacy.RelationshipLevel;
import com.unciv.logic.map.tile.RoadStatus;
import com.unciv.logic.map.tile.Tile;
import com.unciv.logic.map.mapunit.MapUnit;
import com.unciv.models.ruleset.Building;
import com.unciv.models.ruleset.IConstruction;
import com.unciv.models.ruleset.INonPerpetualConstruction;
import com.unciv.models.ruleset.Ruleset;
import com.unciv.models.ruleset.tile.Terrain;
import com.unciv.models.ruleset.tile.TerrainType;
import com.unciv.models.ruleset.tile.TileResource;
import com.unciv.models.ruleset.unit.BaseUnit;
import com.unciv.models.stats.Stats;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import nl.jordythomassen.livingworld.coords.CivWorldLayout;
import nl.jordythomassen.livingworld.coords.HexGrid;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.Empire;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.Legend;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.Relation;
import nl.jordythomassen.livingworld.state.SessionInfo;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * One picture of an Unciv game as one civilization knows it: the same {@link WorldState} the
 * Minecraft side has always drawn from. Fog of war is applied here: a tile the viewer has not
 * explored is unknown, a unit the viewer cannot see is not in the picture.
 */
public final class UncivSnapshot {
	/** Marks the state as coming from the built-in engine. */
	public static final int PROTOCOL = 3;

	private final GameInfo game;
	private final UncivMap map;
	private final String bootId;
	private final Legend legend = new Legend();
	private final Map<String, Integer> terrainIds = new HashMap<>();
	private final Map<String, Integer> featureIds = new HashMap<>();
	private final Map<String, Integer> improvementIds = new HashMap<>();
	private final Map<String, Integer> resourceIds = new HashMap<>();

	public UncivSnapshot(GameInfo game, UncivMap map, String bootId) {
		this.game = game;
		this.map = map;
		this.bootId = bootId;
		Ruleset rules = game.getRuleset();
		int id = 0;
		for (Terrain terrain : rules.getTerrains().values()) {
			TerrainType type = terrain.getType();
			if (type == TerrainType.Land || type == TerrainType.Water) {
				terrainIds.put(terrain.getName(), id);
				legend.put(Legend.Kind.TERRAIN, new Legend.Entry(id, terrainKey(terrain.getName()), terrain.getName()));
			} else if (!terrain.getName().equals("Hill")) {
				featureIds.put(terrain.getName(), id);
				legend.put(Legend.Kind.FEATURE, new Legend.Entry(id, "FEATURE_" + Names.key(terrain.getName()), terrain.getName()));
			}
			id++;
		}
		id = 0;
		for (String name : rules.getTileImprovements().keySet()) {
			improvementIds.put(name, id);
			legend.put(Legend.Kind.IMPROVEMENT, new Legend.Entry(id, "IMPROVEMENT_" + Names.key(name), name));
			id++;
		}
		id = 0;
		for (String name : rules.getTileResources().keySet()) {
			resourceIds.put(name, id);
			legend.put(Legend.Kind.RESOURCE, new Legend.Entry(id, "RESOURCE_" + Names.key(name), name));
			id++;
		}
		legend.put(Legend.Kind.ROUTE, new Legend.Entry(0, "ROUTE_ROAD", "Road"));
		legend.put(Legend.Kind.ROUTE, new Legend.Entry(1, "ROUTE_RAILROAD", "Railroad"));
	}

	private static String terrainKey(String name) {
		return switch (name) {
			case "Grassland" -> "TERRAIN_GRASS";
			// A lake is fresh water on the same footing as coast; the plot's lake flag tells them apart.
			case "Lakes" -> "TERRAIN_COAST";
			default -> "TERRAIN_" + Names.key(name);
		};
	}

	/** The number a civilization goes by on the Minecraft side: its place in the game's list. */
	public int civId(Civilization civ) {
		return civ == null ? -1 : game.getCivilizations().indexOf(civ);
	}

	public Civilization civ(int id) {
		return id < 0 || id >= game.getCivilizations().size() ? null : game.getCivilizations().get(id);
	}

	/** Unciv names its cities by a long text id; the Minecraft side wants a number. */
	public static int cityId(City city) {
		return city.getId().hashCode() & 0x7FFFFFFF;
	}

	public City city(EntityKey key) {
		Civilization owner = civ(key.owner());
		if (owner == null) {
			return null;
		}
		for (City city : owner.getCities()) {
			if (cityId(city) == key.id()) {
				return city;
			}
		}
		return null;
	}

	public MapUnit unit(EntityKey key) {
		Civilization owner = civ(key.owner());
		if (owner == null) {
			return null;
		}
		for (Iterator<MapUnit> it = owner.getUnits().getCivUnits().iterator(); it.hasNext(); ) {
			MapUnit unit = it.next();
			if (unit.getId() == key.id()) {
				return unit;
			}
		}
		return null;
	}

	public UncivMap map() {
		return map;
	}

	/** The number an improvement goes by in the picture's legend, or -1. */
	public int improvementId(String name) {
		return improvementIds.getOrDefault(name, -1);
	}

	public String improvementName(int id) {
		for (Map.Entry<String, Integer> entry : improvementIds.entrySet()) {
			if (entry.getValue() == id) {
				return entry.getKey();
			}
		}
		return null;
	}

	public WorldState take(Civilization viewer) {
		return take(viewer, List.of());
	}

	/**
	 * The picture for one civilization, widened by what others know as well: the land any of them
	 * has explored, the units any of them sees. That is the world as it is built in Minecraft when
	 * several people each rule a civilization of their own; what each of them is shown of it is
	 * their own picture.
	 */
	public WorldState take(Civilization viewer, List<Civilization> also) {
		List<Civilization> eyes = new ArrayList<>();
		eyes.add(viewer);
		for (Civilization other : also) {
			if (other != viewer) {
				eyes.add(other);
			}
		}
		int viewerId = civId(viewer);
		HexGrid grid = map.grid();
		SessionInfo session = new SessionInfo(PROTOCOL, bootId, game.getGameId(), viewerId, viewerId, grid, new CivWorldLayout(0, 0, 64, 55.4256));

		Map<Integer, CivInfo> civs = new LinkedHashMap<>();
		for (Civilization civ : game.getCivilizations()) {
			if (civ.isSpectator()) {
				continue;
			}
			int id = civId(civ);
			boolean met = civ.isBarbarian();
			for (Civilization eye : eyes) {
				met |= civ == eye || eye.knows(civ);
			}
			if (!met) {
				civs.put(id, new CivInfo(id, id, "", "Unmet civilization", "Unknown", "", !civ.isDefeated(), civ.isCityState(), false, false, 0x808080, 0x404040,
					false, false, "", ""));
				continue;
			}
			String name = civ.getCivName();
			civs.put(id, new CivInfo(id, id, "CIVILIZATION_" + Names.key(name), name, name, civ.getNation().getLeaderName(), !civ.isDefeated(), civ.isCityState(),
				civ.isBarbarian(), civ.isHuman(), Names.color(civ.getNation(), true), Names.color(civ.getNation(), false),
				civ != viewer && viewer.isAtWarWith(civ), true, eraKey(civ), Names.artStyle(name, civ.isBarbarian())));
		}

		PlotState[] plots = new PlotState[grid.width() * grid.height()];
		int[] rivers = rivers();
		Set<Tile> visible = viewer.getViewableTiles();
		if (eyes.size() > 1) {
			visible = new HashSet<>(visible);
			for (Civilization eye : eyes) {
				visible.addAll(eye.getViewableTiles());
			}
		}
		for (int index = 0; index < plots.length; index++) {
			Tile tile = map.tile(index);
			if (tile == null) {
				// Outside a map that is not a rectangle: open sea, known to all.
				plots[index] = new PlotState(terrainIds.getOrDefault("Ocean", -1), -1, PlotState.FLAT, -1, -1, -1, -1, 0, PlotState.F_REVEALED | PlotState.F_WATER);
				continue;
			}
			Civilization knows = null;
			for (Civilization eye : eyes) {
				if (tile.isExplored(eye)) {
					knows = eye;
					break;
				}
			}
			if (knows == null) {
				plots[index] = PlotState.UNKNOWN;
				continue;
			}
			plots[index] = plot(tile, eyes, visible.contains(tile), rivers[index]);
		}

		Map<EntityKey, UnitState> units = new LinkedHashMap<>();
		for (Tile tile : visible) {
			for (Iterator<MapUnit> it = tile.getUnits().iterator(); it.hasNext(); ) {
				MapUnit unit = it.next();
				boolean seen = false;
				for (Civilization eye : eyes) {
					seen |= unit.isVisibleTo(eye);
				}
				if (seen) {
					UnitState state = unit(unit);
					units.put(state.key(), state);
				}
			}
		}
		// The viewer's own units are always known, also the ones a transport carries out of sight.
		for (Civilization eye : eyes) {
			for (Iterator<MapUnit> it = eye.getUnits().getCivUnits().iterator(); it.hasNext(); ) {
				UnitState state = unit(it.next());
				units.putIfAbsent(state.key(), state);
			}
		}

		Map<EntityKey, CityState> cities = new LinkedHashMap<>();
		for (Iterator<City> it = game.getCities().iterator(); it.hasNext(); ) {
			City city = it.next();
			Tile centre = city.getCenterTileOrNull();
			boolean known = false;
			for (Civilization eye : eyes) {
				known |= centre != null && centre.isExplored(eye);
			}
			if (!known) {
				continue;
			}
			CityState state = city(city, eyes.contains(city.getCiv()));
			cities.put(state.key(), state);
		}

		Set<String> techs = new HashSet<>();
		for (String tech : viewer.getTech().getTechsResearched()) {
			techs.add(Names.techKey(tech));
		}
		Map<Integer, Relation> relations = new LinkedHashMap<>();
		for (Iterator<Civilization> it = viewer.getKnownCivs().iterator(); it.hasNext(); ) {
			Civilization other = it.next();
			if (other == viewer || other.isBarbarian() || other.isSpectator() || other.isDefeated()) {
				continue;
			}
			relations.put(civId(other), relation(viewer, other));
		}
		return new WorldState(session, game.getTurns(), legend, civs, plots, units, cities, empire(viewer), techs, relations);
	}

	private PlotState plot(Tile tile, List<Civilization> eyes, boolean visible, int rivers) {
		String baseName = tile.baseTerrain;
		boolean mountain = baseName.equals("Mountain");
		int elevation = mountain ? PlotState.MOUNTAIN : tile.isHill() ? PlotState.HILLS : PlotState.FLAT;
		int feature = -1;
		if (tile.getNaturalWonder() != null) {
			feature = featureIds.getOrDefault(tile.getNaturalWonder(), -1);
		} else {
			for (String name : tile.getTerrainFeatures()) {
				Integer id = featureIds.get(name);
				if (id != null) {
					feature = id;
				}
			}
		}
		int improvement = tile.getImprovement() == null ? -1 : improvementIds.getOrDefault(tile.getImprovement(), -1);
		int resource = -1;
		if (tile.getResource() != null) {
			TileResource res = game.getRuleset().getTileResources().get(tile.getResource());
			for (Civilization eye : eyes) {
				if (res != null && eye.getTech().isRevealed(res)) {
					resource = resourceIds.getOrDefault(tile.getResource(), -1);
				}
			}
		}
		int route = tile.getRoadStatus() == RoadStatus.Railroad ? 1 : tile.getRoadStatus() == RoadStatus.Road ? 0 : -1;
		int flags = PlotState.F_REVEALED | (visible ? PlotState.F_VISIBLE : 0) | (tile.isCityCenter() ? PlotState.F_CITY : 0)
			| (tile.isWater() ? PlotState.F_WATER : 0) | (baseName.equals("Lakes") ? PlotState.F_LAKE : 0)
			| (tile.getImprovementIsPillaged() ? PlotState.F_PILLAGED : 0);
		// A mountain is drawn from its height; underneath it counts as plain land.
		int terrain = mountain ? terrainIds.getOrDefault("Plains", -1) : terrainIds.getOrDefault(baseName, -1);
		return new PlotState(terrain, feature, elevation, civId(tile.getOwner()), improvement, resource, route, rivers, flags);
	}

	/**
	 * Unciv keeps a river on three edges of a tile (its lower right, bottom and lower left as Unciv
	 * draws it); the board keeps one on a plot's east, south-east and south-west edge. Found by
	 * asking which neighbour lies across each edge, so the turn of the map needs no table.
	 */
	private int[] rivers() {
		HexGrid grid = map.grid();
		int[] out = new int[grid.width() * grid.height()];
		for (Tile tile : game.getTileMap().getTileList()) {
			river(out, grid, tile, tile.getHasBottomRightRiver(), 4);
			river(out, grid, tile, tile.getHasBottomRiver(), 6);
			river(out, grid, tile, tile.getHasBottomLeftRiver(), 8);
		}
		return out;
	}

	private void river(int[] out, HexGrid grid, Tile tile, boolean has, int clock) {
		if (!has) {
			return;
		}
		Tile across = game.getTileMap().getClockPositionNeighborTile(tile, clock);
		if (across == null) {
			return;
		}
		PlotCoord a = map.plot(tile);
		PlotCoord b = map.plot(across);
		// East, south-east and south-west of a: the edge belongs to a. Otherwise it is one of those three seen from b.
		int bit = edgeBit(grid, a, b);
		if (bit != 0) {
			out[map.index(a)] |= bit;
		} else {
			bit = edgeBit(grid, b, a);
			if (bit != 0) {
				out[map.index(b)] |= bit;
			}
		}
	}

	/** Which of a plot's three river edges faces the other plot, or 0 when it is one of the other three. */
	private static int edgeBit(HexGrid grid, PlotCoord from, PlotCoord to) {
		if (to.y() == from.y()) {
			return to.x() == from.x() + 1 ? PlotState.RIVER_EAST : 0;
		}
		if (to.y() != from.y() - 1) {
			return 0;
		}
		// The row below: in a shifted (odd) row the two lower neighbours are at x and x+1, otherwise at x-1 and x.
		boolean shifted = (from.y() & 1) != 0;
		int east = shifted ? from.x() + 1 : from.x();
		return to.x() == east ? PlotState.RIVER_SOUTH_EAST : PlotState.RIVER_SOUTH_WEST;
	}

	private UnitState unit(MapUnit unit) {
		BaseUnit base = unit.getBaseUnit();
		int flags = (unit.isEmbarked() ? UnitState.F_EMBARKED : 0) | (unit.isFortified() ? UnitState.F_FORTIFIED : 0) | (unit.isMilitary() ? UnitState.F_COMBAT : 0)
			| (unit.isGreatPerson() ? UnitState.F_GREAT_PERSON : 0) | (unit.getDue() && unit.isIdle(true) ? UnitState.F_WAITING : 0);
		String domain = base.isWaterUnit() ? "DOMAIN_SEA" : base.isAirUnit() ? "DOMAIN_AIR" : "DOMAIN_LAND";
		String name = unit.getInstanceName() == null ? base.getName() : unit.getInstanceName() + " (" + base.getName() + ")";
		return new UnitState(new EntityKey(civId(unit.getCiv()), unit.getId()), "UNIT_" + Names.key(base.getName()), name, map.plot(unit.getTile()),
			unit.getHealth(), 100, Math.round(unit.getCurrentMovement() * 60), unit.getMaxMovement(false) * 60, base.getStrength(), base.getRangedStrength(),
			domain, unit.getPromotions().getNumberOfPromotions(), unit.getPromotions().getXP(), flags);
	}

	private CityState city(City city, boolean mine) {
		// "Occupied": it came to its owner from somebody else this very turn.
		boolean taken = city.getTurnAcquired() == game.getTurns() && !city.getPreviousOwner().isEmpty() && city.getFoundingCivObject() != city.getCiv();
		int flags = (city.isCapital() ? CityState.F_CAPITAL : 0) | (city.isPuppet() ? CityState.F_PUPPET : 0) | (city.isBeingRazed() ? CityState.F_RAZING : 0)
			| (taken ? CityState.F_OCCUPIED : 0)
			| (city.isInResistance() ? CityState.F_RESISTANCE : 0) | (city.isCoastal() ? CityState.F_COASTAL : 0) | (mine ? CityState.F_DETAILS : 0);
		String type = "";
		String name = "";
		int done = 0;
		int needed = 0;
		int turns = 0;
		int food = 0;
		int growth = 0;
		List<String> buildings = new ArrayList<>();
		List<String> wonders = new ArrayList<>();
		CityConstructions constructions = city.getCityConstructions();
		if (mine) {
			String current = constructions.currentConstructionName();
			IConstruction construction = constructions.getCurrentConstruction();
			if (current != null && !current.isEmpty() && construction instanceof INonPerpetualConstruction real) {
				name = current;
				type = (construction instanceof BaseUnit ? "UNIT_" : "BUILDING_") + Names.key(current);
				done = constructions.getWorkDone(current);
				needed = real.getProductionCost(city.getCiv(), city);
				turns = constructions.turnsToConstruction(current, true);
			} else if (current != null && !current.isEmpty() && !current.equals("Nothing")) {
				// Turning production into gold or science: something is chosen, nothing is being built.
				name = current;
				type = "PROCESS_" + Names.key(current);
			}
			food = city.getPopulation().getFoodStored();
			growth = city.getPopulation().getFoodToNextPopulation();
		}
		// What stands in a city can be seen by anyone who sees the city.
		for (Object built : Kt.get(constructions, "getBuiltBuildings", java.util.Set.class)) {
			Building building = game.getRuleset().getBuildings().get((String) built);
			if (building != null) {
				(building.isWonder() ? wonders : buildings).add(Names.key(building.getName()));
			}
		}
		return new CityState(new EntityKey(civId(city.getCiv()), cityId(city)), city.getName(), map.plot(city.getCenterTile()), city.getPopulation().getPopulation(), flags,
			city.getHealth(), city.getMaxHealth$Unciv_core(), type, name, done, needed, turns, food, growth, Math.round(city.getStrength()), buildings, wonders);
	}

	private static String eraKey(Civilization civ) {
		if (civ.isBarbarian()) {
			return "ERA_ANCIENT";
		}
		String name = civ.getEra().getName();
		return switch (name) {
			case "Atomic era" -> "ERA_POSTMODERN";
			case "Information era", "Future era" -> "ERA_FUTURE";
			default -> "ERA_" + Names.key(name.replace(" era", ""));
		};
	}

	private Empire empire(Civilization viewer) {
		Stats next = viewer.getStats().getStatsForNextTurn();
		String research = viewer.getTech().currentTechnologyName();
		int researchTurns = 0;
		if (research != null) {
			try {
				researchTurns = Integer.parseInt(viewer.getTech().turnsToTech(research).trim());
			} catch (NumberFormatException e) {
				researchTurns = 999;
			}
		}
		return new Empire(viewer.getGold(), Math.round(next.getGold()), Math.round(next.getScience()), viewer.getHappiness(), viewer.getPolicies().getStoredCulture(),
			Math.round(next.getCulture()), viewer.getPolicies().getCultureNeededForNextPolicy(), viewer.getGoldenAges().getTurnsLeftForCurrentGoldenAge(), eraKey(viewer),
			game.getYear(0), research == null ? "" : "TECH_" + Names.techKey(research), research == null ? "" : research, researchTurns,
			viewer.getPolicies().getAdoptedPolicies().size(), UncivRules.blocking(viewer));
	}

	private Relation relation(Civilization viewer, Civilization other) {
		DiplomacyManager ours = viewer.getDiplomacyManager(other);
		DiplomacyManager theirs = other.getDiplomacyManager(viewer);
		if (other.isCityState()) {
			RelationshipLevel level = theirs == null ? RelationshipLevel.Neutral : theirs.relationshipLevel();
			int standing = level == RelationshipLevel.Ally ? 2 : level == RelationshipLevel.Friend ? 1 : 0;
			List<String> quests = new ArrayList<>();
			for (var quest : Kt.list(other.getQuestManager().getAssignedQuestsFor(viewer.getCivName()))) {
				quests.add(Names.key(quest.getQuestName()));
			}
			Civilization ally = other.getAllyCiv();
			return new Relation(civId(other), true, theirs == null ? 0 : Math.round(theirs.getInfluence()), standing, Names.key(other.getCityStateType().getName()),
				civId(ally), quests, 250, 500, 1000);
		}
		int flags = 0;
		if (ours != null) {
			flags |= ours.hasFlag(DiplomacyFlags.DeclarationOfFriendship) ? Relation.F_FRIENDSHIP : 0;
			flags |= ours.hasFlag(DiplomacyFlags.Denunciation) ? Relation.F_DENOUNCED : 0;
			flags |= ours.canDeclareWar() ? Relation.F_CAN_DECLARE_WAR : 0;
		}
		String word = "";
		if (theirs != null) {
			word = viewer.isAtWarWith(other) ? "WAR" : switch (theirs.relationshipLevel()) {
				case Unforgivable, Enemy -> "HOSTILE";
				case Afraid -> "AFRAID";
				case Competitor -> "GUARDED";
				case Favorable, Friend, Ally -> "FRIENDLY";
				default -> "NEUTRAL";
			};
		}
		return new Relation(civId(other), false, 0, flags, word, -1, List.of(), 0, 0, 0);
	}
}
