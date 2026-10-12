package nl.jordythomassen.livingworld.board;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import nl.jordythomassen.livingworld.coords.GridPos;
import nl.jordythomassen.livingworld.coords.HexEdge;
import nl.jordythomassen.livingworld.coords.HexGrid;
import nl.jordythomassen.livingworld.coords.HexMetric;
import nl.jordythomassen.livingworld.coords.McLayout;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.Legend;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * The Civ map as a landscape of blocks: for any column of blocks, what it is made of.
 *
 * <p>Everything is a pure function of the Civ state and the block coordinates, with no stored
 * blocks and no randomness that is not derived from the coordinates. Painting a column twice
 * gives the same result, any column can be painted on its own (a tree's leaves know about the
 * trunk next door), and a change in Civ is shown by simply painting the affected columns again.
 */
public final class BoardModel {
	/** Lowest and one-past-highest block height the board uses. */
	public static final int BASE = 48;
	public static final int TOP = 112;
	/** Height of the water surface and of the top block of flat land. */
	public static final int SEA = 62;
	public static final int LAND = 64;
	/** Hexes of open sea painted around the map so it does not end in a cliff. */
	public static final int MARGIN = 2;
	/** {@link #column} returns this for columns outside the board. */
	public static final int NOTHING = Integer.MIN_VALUE;

	private enum Terrain { GRASS, PLAINS, DESERT, TUNDRA, SNOW, COAST, OCEAN, OTHER }

	private enum Feature { NONE, FOREST, JUNGLE, MARSH, OASIS, FLOOD_PLAINS, ICE, FALLOUT, WONDER }

	private enum Improvement { NONE, FARM, MINE, PASTURE, CAMP, PLANTATION, QUARRY, RUINS, OTHER }

	private static final int AIR = Material.AIR.ordinal();

	private final WorldState state;
	private final McLayout layout;
	private final HexGrid grid;
	private final int width;
	private final int height;
	private final double half;
	private final Map<Integer, Terrain> terrains = new HashMap<>();
	private final Map<Integer, Feature> features = new HashMap<>();
	private final Map<Integer, Improvement> improvements = new HashMap<>();
	private final Map<Integer, CityState> cityByPlot = new HashMap<>();
	private final Map<Integer, Integer> dyeByCiv = new HashMap<>();
	private final Map<nl.jordythomassen.livingworld.state.EntityKey, CityPlan> plans = new HashMap<>();
	private final Map<Integer, Material> resources = new HashMap<>();
	/** The wonders that stand on a hex of their own, by the number of that hex, and their shapes. */
	private final Map<Integer, Site> sites = new java.util.LinkedHashMap<>();
	private final Map<Integer, Structure> landmarks = new HashMap<>();

	/**
	 * A wonder of the world where it stands on the land: its hex, the city it belongs to, and
	 * what it is. {@code certain} is false when not all the land around the city is known to
	 * whoever this board was made for: then another's board may have it a hex further on.
	 */
	public record Site(PlotCoord plot, nl.jordythomassen.livingworld.state.EntityKey city, String wonder, boolean certain) {
	}

	public BoardModel(WorldState state, McLayout layout) {
		this.state = state;
		this.layout = layout;
		this.grid = state.session().grid();
		this.width = grid.width();
		this.height = grid.height();
		this.half = layout.hexWidth() / 2.0;
		Legend legend = state.legend();
		for (Legend.Entry e : legend.entries(Legend.Kind.TERRAIN)) {
			terrains.put(e.id(), switch (e.type()) {
				case "TERRAIN_GRASS" -> Terrain.GRASS;
				case "TERRAIN_PLAINS" -> Terrain.PLAINS;
				case "TERRAIN_DESERT" -> Terrain.DESERT;
				case "TERRAIN_TUNDRA" -> Terrain.TUNDRA;
				case "TERRAIN_SNOW" -> Terrain.SNOW;
				case "TERRAIN_COAST" -> Terrain.COAST;
				case "TERRAIN_OCEAN" -> Terrain.OCEAN;
				default -> Terrain.OTHER;
			});
		}
		for (Legend.Entry e : legend.entries(Legend.Kind.FEATURE)) {
			features.put(e.id(), switch (e.type()) {
				case "FEATURE_FOREST" -> Feature.FOREST;
				case "FEATURE_JUNGLE" -> Feature.JUNGLE;
				case "FEATURE_MARSH" -> Feature.MARSH;
				case "FEATURE_OASIS" -> Feature.OASIS;
				case "FEATURE_FLOOD_PLAINS" -> Feature.FLOOD_PLAINS;
				case "FEATURE_ICE" -> Feature.ICE;
				case "FEATURE_FALLOUT" -> Feature.FALLOUT;
				// Everything else in this table is a natural wonder (or a modded feature).
				default -> Feature.WONDER;
			});
		}
		for (Legend.Entry e : legend.entries(Legend.Kind.IMPROVEMENT)) {
			String type = e.type();
			Improvement kind = Improvement.OTHER;
			if (type.contains("RUINS")) {
				kind = Improvement.RUINS;
			} else if (type.contains("FARM")) {
				kind = Improvement.FARM;
			} else if (type.contains("MINE")) {
				kind = Improvement.MINE;
			} else if (type.contains("PASTURE")) {
				kind = Improvement.PASTURE;
			} else if (type.contains("CAMP")) {
				kind = Improvement.CAMP;
			} else if (type.contains("PLANTATION")) {
				kind = Improvement.PLANTATION;
			} else if (type.contains("QUARRY")) {
				kind = Improvement.QUARRY;
			}
			improvements.put(e.id(), kind);
		}
		for (CityState city : state.cities().values()) {
			PlotCoord at = grid.normalize(city.at());
			if (at != null) {
				cityByPlot.put(grid.index(at), city);
			}
		}
		for (CivInfo civ : state.civs().values()) {
			dyeByCiv.put(civ.id(), Dye.nearest(civ.color()));
		}
		// Every wonder a hex of its own beside its city, as long as there is land for it: first the six around
		// the city (its own from the day it was founded, so a wonder stays where it was put), then the ring beyond.
		List<CityState> ordered = new ArrayList<>(state.cities().values());
		ordered.sort(java.util.Comparator.comparingInt((CityState c) -> c.key().owner()).thenComparingInt(c -> c.key().id()));
		Map<nl.jordythomassen.livingworld.state.EntityKey, List<String>> inTown = new HashMap<>();
		for (CityState city : ordered) {
			List<String> left = new ArrayList<>();
			PlotCoord at = grid.normalize(city.at());
			for (String wonder : city.wonders()) {
				Site site = at == null || half < 14 ? null : siteFor(city, at, wonder);
				if (site == null) {
					left.add(wonder);
				} else {
					sites.put(grid.index(site.plot()), site);
				}
			}
			inTown.put(city.key(), left);
		}
		for (CityState city : state.cities().values()) {
			plans.put(city.key(), new CityPlan(city, state.civ(city.key().owner()), dyeByCiv.getOrDefault(city.key().owner(), 7),
				city.key().owner() == state.session().viewer(), keepHalf(), hallFurniture(), inTown.getOrDefault(city.key(), List.of())));
		}
		int nth = 0;
		for (Map.Entry<Integer, Site> entry : sites.entrySet()) {
			CityPlan plan = plans.get(entry.getValue().city());
			landmarks.put(entry.getKey(), Landmarks.of(CityPlan.wonderKind(entry.getValue().wonder(), nth++), plan.dye, plan.dry));
		}
		for (Legend.Entry e : legend.entries(Legend.Kind.RESOURCE)) {
			resources.put(e.id(), resourceMaterial(e.type()));
		}
	}

	/** The hex a wonder of this city stands on, or null when there is no land for it (it then stands in the town). */
	private Site siteFor(CityState city, PlotCoord at, String wonder) {
		List<PlotCoord> first = grid.neighbors(at);
		List<PlotCoord> second = new ArrayList<>();
		for (PlotCoord near : first) {
			for (PlotCoord far : grid.neighbors(near)) {
				if (!far.equals(at) && !first.contains(far) && !second.contains(far)) {
					second.add(far);
				}
			}
		}
		boolean certain = true;
		for (PlotCoord plot : first) {
			certain &= state.plot(plot).revealed();
		}
		for (PlotCoord plot : second) {
			certain &= state.plot(plot).revealed();
		}
		// Each wonder begins its search on a side of its own, so that two wonders do not want the same hex first.
		int start = Math.floorMod(hash(wonder.hashCode(), city.key().id(), 77), 6);
		for (List<PlotCoord> ring : List.of(first, second)) {
			for (int i = 0; i < ring.size(); i++) {
				PlotCoord plot = ring.get((i + start) % ring.size());
				PlotState there = state.plot(plot);
				Feature feature = there.feature() < 0 ? Feature.NONE : features.getOrDefault(there.feature(), Feature.WONDER);
				if (there.revealed() && !there.water() && there.elevation() != PlotState.MOUNTAIN && !there.city() && there.owner() == city.key().owner()
					&& feature != Feature.WONDER && !sites.containsKey(grid.index(plot))) {
					return new Site(plot, city.key(), wonder, certain);
				}
			}
		}
		return null;
	}

	/** The wonders that stand on the land, each on its own hex. */
	public java.util.Collection<Site> wonderSites() {
		return java.util.Collections.unmodifiableCollection(sites.values());
	}

	/** The wonder that stands on this hex, or null. */
	public Site siteAt(PlotCoord plot) {
		PlotCoord at = plot == null ? null : grid.normalize(plot);
		return at == null ? null : sites.get(grid.index(at));
	}

	/** What a resource looks like as a small outcrop or patch on its plot. */
	static Material resourceMaterial(String type) {
		String t = type == null ? "" : type;
		if (t.contains("IRON") || t.contains("ALUMINUM") || t.contains("SILVER")) {
			return Material.ORE;
		}
		if (t.contains("COAL") || t.contains("OIL")) {
			return Material.ORE_COAL;
		}
		if (t.contains("GOLD")) {
			return Material.ORE_GOLD;
		}
		if (t.contains("COPPER")) {
			return Material.ORE_COPPER;
		}
		if (t.contains("GEMS") || t.contains("URANIUM") || t.contains("JEWELRY")) {
			return Material.ORE_GEM;
		}
		if (t.contains("MARBLE")) {
			return Material.QUARTZ;
		}
		if (t.contains("STONE")) {
			return Material.ANDESITE;
		}
		if (t.contains("SALT")) {
			return Material.CALCITE;
		}
		if (t.contains("COTTON") || t.contains("SILK") || t.contains("SHEEP")) {
			return Material.CLOUD;
		}
		if (t.contains("SPICES") || t.contains("DYE") || t.contains("INCENSE")) {
			return Material.FLOWER;
		}
		if (t.contains("WINE") || t.contains("TRUFFLES") || t.contains("COCOA")) {
			return Material.BERRY_BUSH;
		}
		if (t.contains("CITRUS") || t.contains("BANANA") || t.contains("SUGAR")) {
			return Material.MELON;
		}
		// Horses, cattle, deer, wheat and anything unknown: a stack of fodder.
		return Material.HAY;
	}

	public WorldState state() {
		return state;
	}

	public McLayout layout() {
		return layout;
	}

	/** The plot under a column, or null when the column is not over the map. */
	public PlotCoord plotAt(int x, int z) {
		PlotCoord raw = grid.plotAt(layout.toGrid(x + 0.5, z + 0.5));
		return onMap(raw) ? raw : null;
	}

	private boolean onMap(PlotCoord p) {
		// The Minecraft board is the flat map: it does not repeat where the Civ map wraps.
		return p.x() >= 0 && p.x() < width && p.y() >= 0 && p.y() < height;
	}

	/** Block bounds of everything the board paints: minX, minZ, maxX, maxZ. */
	public int[] bounds() {
		double pad = (MARGIN + 1) * layout.hexWidth();
		double minX = layout.x(new GridPos(0, 0)) - pad;
		double maxX = layout.x(new GridPos(width, 0)) + pad;
		double minZ = layout.z(new GridPos(0, height)) - pad;
		double maxZ = layout.z(new GridPos(0, 0)) + pad;
		return new int[] { (int) Math.floor(minX), (int) Math.floor(minZ), (int) Math.ceil(maxX), (int) Math.ceil(maxZ) };
	}

	/** The block column at the centre of a plot. */
	public int centerX(PlotCoord plot) {
		return (int) Math.floor(layout.x(grid.center(plot)));
	}

	public int centerZ(PlotCoord plot) {
		return (int) Math.floor(layout.z(grid.center(plot)));
	}

	/** The height a creature stands at in this column (one above the ground or water surface). */
	public int standY(int x, int z) {
		int[] scratch = new int[TOP - BASE];
		int ground = column(x, z, scratch);
		return ground == NOTHING ? LAND + 1 : ground + 1;
	}

	private static int hash(int x, int z, int salt) {
		int h = x * 0x1F1F1F1F ^ z * 0x5F356495 ^ salt * 0x2C1B3C6D;
		h ^= h >>> 15;
		h *= 0x2C1B3C6D;
		h ^= h >>> 12;
		h *= 0x297A2D39;
		h ^= h >>> 15;
		return h & 0x7FFFFFFF;
	}

	private PlotState neighbor(GridPos center, HexEdge edge) {
		PlotCoord n = grid.plotAt(new GridPos(center.u() + edge.du, center.v() + edge.dv));
		return onMap(n) ? state.plot(n.y() * width + n.x()) : null;
	}

	private boolean riverOn(PlotState plot, GridPos center, HexEdge edge) {
		switch (edge) {
			case EAST:
				return (plot.rivers() & PlotState.RIVER_EAST) != 0;
			case SOUTH_EAST:
				return (plot.rivers() & PlotState.RIVER_SOUTH_EAST) != 0;
			case SOUTH_WEST:
				return (plot.rivers() & PlotState.RIVER_SOUTH_WEST) != 0;
			default:
				// Civ stores a river with the plot on its west or north side: ask that neighbour.
				PlotState other = neighbor(center, edge);
				if (other == null) {
					return false;
				}
				int flag = edge == HexEdge.WEST ? PlotState.RIVER_EAST
					: edge == HexEdge.NORTH_WEST ? PlotState.RIVER_SOUTH_EAST : PlotState.RIVER_SOUTH_WEST;
				return (other.rivers() & flag) != 0;
		}
	}

	private int bump(PlotState plot, double rim) {
		double t = Math.max(0.0, 1.0 - rim);
		if (plot.elevation() == PlotState.MOUNTAIN) {
			return (int) Math.round(Math.min(34.0, half * 1.7) * Math.pow(t, 1.15));
		}
		if (plot.elevation() == PlotState.HILLS) {
			return (int) Math.round(Math.min(6.0, half * 0.4) * t * t * (3.0 - 2.0 * t));
		}
		return 0;
	}

	private Material topOf(Terrain terrain, int x, int z) {
		return switch (terrain) {
			case PLAINS -> hash(x, z, 1) % 100 < 35 ? Material.COARSE_DIRT : Material.GRASS;
			case DESERT -> Material.SAND;
			case TUNDRA -> Material.PODZOL;
			case SNOW -> Material.SNOW_BLOCK;
			default -> Material.GRASS;
		};
	}

	/**
	 * Fills {@code out[y - BASE]} with what this column is made of (see {@link Material#cell}) and
	 * returns the height of its surface: the top block of the ground, or of the water. Returns
	 * {@link #NOTHING}, with {@code out} all air, for a column outside the board.
	 */
	public int column(int x, int z, int[] out) {
		Arrays.fill(out, AIR);
		GridPos pos = layout.toGrid(x + 0.5, z + 0.5);
		PlotCoord raw = grid.plotAt(pos);
		if (!onMap(raw)) {
			if (raw.x() < -MARGIN || raw.x() >= width + MARGIN || raw.y() < -MARGIN || raw.y() >= height + MARGIN) {
				return NOTHING;
			}
			return water(out, 52, Material.GRAVEL, false);
		}
		PlotState plot = state.plot(raw.y() * width + raw.x());
		if (!plot.revealed()) {
			// Never seen by the player's civilization: a wall of cloud, three blocks proud of the land.
			int top = LAND + 3 + (hash(x >> 1, z >> 1, 7) % 5 == 0 ? 1 : 0);
			fill(out, 56, top, Material.CLOUD);
			return top;
		}
		Terrain terrain = terrains.getOrDefault(plot.terrain(), Terrain.OTHER);
		Feature feature = plot.feature() < 0 ? Feature.NONE : features.getOrDefault(plot.feature(), Feature.WONDER);
		if (plot.water()) {
			boolean shallow = terrain != Terrain.OCEAN;
			return water(out, shallow ? 58 : 52, shallow ? Material.SAND : Material.GRAVEL, feature == Feature.ICE);
		}
		GridPos center = grid.center(raw);
		double rim = HexMetric.rimFraction(pos, center);
		HexEdge edge = HexMetric.nearestEdge(pos, center);
		CityState city = plot.city() ? cityByPlot.get(raw.y() * width + raw.x()) : null;
		int top = LAND + (city != null ? 0 : bump(plot, rim));

		boolean mountain = plot.elevation() == PlotState.MOUNTAIN && city == null;
		Material under = mountain ? Material.STONE : terrain == Terrain.DESERT ? Material.SANDSTONE : Material.DIRT;
		fill(out, BASE, top - 4, Material.STONE);
		fill(out, top - 3, top - 1, under);
		// A mountain is not one grey: snow on its top, and veins of lighter and rougher rock down its sides.
		Material surface = mountain ? (top - LAND > 17 ? Material.SNOW_BLOCK : hash(x, z, 12) % 100 < 22 ? Material.ANDESITE : hash(x, z, 13) % 100 < 14 ? Material.COBBLE : Material.STONE)
			: topOf(terrain, x, z);
		// Where the land meets the sea it ends in a strip of beach.
		if (!mountain && city == null && rim > 1.0 - 2.6 / half && terrain != Terrain.SNOW && terrain != Terrain.TUNDRA && plot.elevation() == PlotState.FLAT) {
			PlotState across = neighbor(center, edge);
			if (across != null && across.revealed() && across.water() && !riverOn(plot, center, edge)) {
				surface = Material.SAND;
			}
		}
		set(out, top, surface);
		if (mountain) {
			return top;
		}

		// A river runs along the edge it belongs to, as a channel two blocks deep.
		if (rim > 1.0 - 2.4 / half && riverOn(plot, center, edge)) {
			fill(out, LAND - 1, top, Material.AIR);
			set(out, LAND - 2, Material.GRAVEL);
			set(out, LAND - 1, Material.WATER);
			set(out, LAND, Material.WATER);
			return LAND;
		}

		int cx = (int) Math.floor(layout.x(center));
		int cz = (int) Math.floor(layout.z(center));
		int dx = x - cx;
		int dz = z - cz;

		if (city != null) {
			city(out, city, center, rim, x, z, dx, dz);
			if (rim > 1.0 - 1.3 / half) {
				border(out, plot, center, edge, top);
			}
			return LAND;
		}

		// A resource shows as a small outcrop or patch to the north-west of the hex centre, clear of
		// whatever improvement works the middle of the hex.
		if (plot.resource() >= 0 && half >= 12) {
			int offset = (int) Math.round(half * 0.5);
			int nx = dx + offset;
			int nz = dz + offset;
			if (Math.abs(nx) <= 1 && Math.abs(nz) <= 1) {
				Material node = resources.getOrDefault(plot.resource(), Material.HAY);
				boolean plant = node == Material.FLOWER || node == Material.BERRY_BUSH;
				if (plant) {
					set(out, top, Material.GRASS);
					set(out, top + 1, node);
				} else if (nx == 0 && nz == 0) {
					fill(out, top, top + 2, node);
				} else if (hash(x, z, 30) % 3 != 0) {
					fill(out, top, top + 1, node);
				} else {
					set(out, top, node);
				}
				return top;
			}
		}

		// A wonder of the world: on a footing level with the middle of the hex, over whatever the hex is used for.
		Structure landmark = landmarks.get(raw.y() * width + raw.x());
		if (landmark != null) {
			int ground = LAND + bump(plot, 0.0);
			int high = landmark.into(dx, dz, out, ground);
			if (high != NOTHING) {
				fill(out, top, ground - 1, Material.STONE_BRICKS);
				return Math.max(high, top);
			}
		}

		boolean road = false;
		if (plot.route() >= 0) {
			road = road(center, x, z);
			if (road) {
				set(out, top, plot.route() > 0 ? Material.COBBLE : Material.PATH);
			}
		}
		if (!road && rim > 1.0 - 1.3 / half && border(out, plot, center, edge, top)) {
			return top;
		}
		if (road) {
			return top;
		}

		Improvement improvement = plot.improvement() < 0 ? Improvement.NONE : improvements.getOrDefault(plot.improvement(), Improvement.OTHER);
		if (improvement != Improvement.NONE && rim < 0.62) {
			return improvement(out, improvement, plot.pillaged(), rim, x, z, dx, dz, top);
		}

		switch (feature) {
			case MARSH -> {
				if (hash(x, z, 3) % 100 < 38) {
					set(out, top, Material.WATER);
					if (hash(x, z, 4) % 6 == 0) {
						set(out, top + 1, Material.LILY);
					}
				} else {
					set(out, top, Material.MUD);
				}
			}
			case OASIS -> {
				if (rim < 0.4) {
					set(out, top, Material.WATER);
				} else if (rim < 0.6) {
					set(out, top, Material.GRASS);
				}
			}
			case FLOOD_PLAINS -> {
				set(out, top, Material.GRASS);
				if (hash(x, z, 5) % 100 < 30) {
					set(out, top + 1, Material.SHORT_GRASS);
				}
			}
			case FALLOUT -> {
				// Poisoned land: bare, grey, nothing green on it.
				set(out, top, hash(x, z, 6) % 3 == 0 ? Material.GRAVEL : Material.COARSE_DIRT);
				if (hash(x, z, 8) % 11 == 0) {
					set(out, top + 1, Material.DEAD_BUSH);
				}
				return top;
			}
			case WONDER -> {
				// A natural wonder: a landmark spire to be seen from far away.
				if (rim < 0.32) {
					int spire = top + (int) Math.round(22 * (1.0 - rim / 0.32));
					fill(out, top, Math.min(TOP - 2, spire), Material.QUARTZ);
					return Math.min(TOP - 2, spire);
				}
			}
			default -> {
			}
		}

		// Trunks and crowns; also outside forest hexes, where a neighbour's crown may hang over.
		trees(out, x, z);

		if (Material.of(out[top + 1 - BASE]) == Material.AIR && Material.of(out[top - BASE]) != Material.WATER) {
			int roll = hash(x, z, 2) % 1000;
			switch (terrain) {
				case GRASS, OTHER -> {
					if (Material.of(out[top - BASE]) != Material.GRASS) {
						// A beach, a road's edge: nothing grows there.
					} else if (roll < 110 && feature != Feature.MARSH) {
						set(out, top + 1, Material.SHORT_GRASS);
					} else if (roll < 128 && feature == Feature.NONE) {
						// Meadows are not all one green: flowers stand in them here and there.
						set(out, top + 1, Material.FLOWER);
					}
				}
				case PLAINS -> {
					if (Material.of(out[top - BASE]) == Material.SAND) {
						// A beach.
					} else if (roll < 90) {
						set(out, top + 1, Material.DRY_GRASS);
					} else if (roll < 96 && feature == Feature.NONE) {
						set(out, top + 1, Material.FLOWER);
					}
				}
				case DESERT -> {
					if (roll < 20 && feature == Feature.NONE) {
						set(out, top + 1, Material.DEAD_BUSH);
					} else if (roll >= 996 && feature == Feature.NONE) {
						// A rock the sand has not buried.
						set(out, top + 1, Material.SANDSTONE);
					}
				}
				case TUNDRA -> {
					if (roll < 380) {
						set(out, top + 1, Material.SNOW_LAYER);
					}
				}
				default -> {
				}
			}
		}
		return top;
	}

	private static void set(int[] out, int y, Material material) {
		if (y >= BASE && y < TOP) {
			out[y - BASE] = material.ordinal();
		}
	}

	private static void set(int[] out, int y, int cell) {
		if (y >= BASE && y < TOP) {
			out[y - BASE] = cell;
		}
	}

	private static void fill(int[] out, int from, int to, Material material) {
		for (int y = Math.max(BASE, from); y <= Math.min(TOP - 1, to); y++) {
			out[y - BASE] = material.ordinal();
		}
	}

	private static int water(int[] out, int floor, Material bed, boolean ice) {
		fill(out, BASE, floor - 2, Material.STONE);
		fill(out, floor - 1, floor, bed);
		fill(out, floor + 1, SEA, Material.WATER);
		if (ice) {
			set(out, SEA, Material.ICE);
		}
		return SEA;
	}

	/** Paints the territory border where the neighbour across the nearest edge has another owner. */
	private boolean border(int[] out, PlotState plot, GridPos center, HexEdge edge, int top) {
		if (plot.owner() < 0) {
			return false;
		}
		PlotState other = neighbor(center, edge);
		if (other != null && other.owner() == plot.owner()) {
			return false;
		}
		set(out, top, Material.cell(Material.BORDER, dyeByCiv.getOrDefault(plot.owner(), 7)));
		return true;
	}

	/** Is this column on the road that runs from the hex centre to each connected neighbour? */
	private boolean road(GridPos center, int x, int z) {
		double px = x + 0.5;
		double pz = z + 0.5;
		double ax = layout.x(center);
		double az = layout.z(center);
		boolean any = false;
		for (HexEdge e : HexEdge.values()) {
			PlotState other = neighbor(center, e);
			if (other == null || !other.revealed() || (other.route() < 0 && !other.city())) {
				continue;
			}
			any = true;
			GridPos mid = new GridPos(center.u() + e.du / 2.0, center.v() + e.dv / 2.0);
			if (distanceToSegment(px, pz, ax, az, layout.x(mid), layout.z(mid)) < 1.6) {
				return true;
			}
		}
		// A lone stretch of road with no connection yet: a small patch at the centre.
		return !any && Math.hypot(px - ax, pz - az) < 2.5;
	}

	private static double distanceToSegment(double px, double pz, double ax, double az, double bx, double bz) {
		double vx = bx - ax;
		double vz = bz - az;
		double length = vx * vx + vz * vz;
		double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / length));
		return Math.hypot(px - (ax + t * vx), pz - (az + t * vz));
	}

	private int keepHalf() {
		return layout.hexWidth() >= 28 ? 4 : layout.hexWidth() >= 20 ? 3 : 2;
	}

	/** How far from the middle of a hall its bell and lectern stand (east and west, on the north side). */
	private int hallFurniture() {
		return Math.max(1, keepHalf() - 2);
	}

	/** Where the bell (index 0) and the lectern (index 1) stand in the hall of a city on that plot: x, y, z. */
	public int[][] hallFurniture(PlotCoord cityPlot) {
		int b = hallFurniture();
		int x = centerX(cityPlot);
		int z = centerZ(cityPlot);
		return new int[][] { { x + b, LAND + 1, z - b }, { x - b, LAND + 1, z - b } };
	}

	private void city(int[] out, CityState city, GridPos center, double rim, int x, int z, int dx, int dz) {
		CityPlan plan = plans.get(city.key());
		if (rim < 0.82) {
			// Paving, worn here and there.
			set(out, LAND, hash(x, z, 40) % 100 < 22 ? plan.plazaWorn : plan.plaza);
		}
		int k = keepHalf();
		// The lots around the hall are laid out for the usual hex; a narrow one has room for the hall only.
		if (plan.column(dx, dz, k == 4 && 11 < 0.82 * half, out)) {
			return;
		}
		if (k == 4 && Math.abs(dx) == 5 && Math.abs(dz) == 5) {
			// Light on the four corners of the square before the hall.
			if (plan.tier == 0) {
				if (dx == dz) {
					set(out, LAND + 1, Material.CAMPFIRE);
				}
			} else {
				Material pole = plan.tier >= 4 ? Material.IRON_BARS : plan.style.fence();
				fill(out, LAND + 1, LAND + 2, pole);
				set(out, LAND + 3, plan.tier >= 5 ? Material.LIGHT : Material.LANTERN);
			}
			return;
		}
		// A wall round every city, open where the roads to the six neighbours pass: a palisade to begin with, stone from the
		// next age on, higher once the city has built walls or a castle. A tower stands on each of its six corners, flying the
		// owner's colour: seen from above that is what says "a city of theirs" before any name is read.
		if (k < 4) {
			return;
		}
		int height = plan.ringWall > 0 ? plan.ringWall + 1 : plan.tier == 0 ? 2 : 3;
		Material stone = plan.ringWall == 0 && plan.tier == 0 ? Material.LOG_SPRUCE : plan.wall;
		double ax = layout.x(center);
		double az = layout.z(center);
		double reach = 0.80 * layout.hexWidth() / Math.sqrt(3.0);
		for (int corner = 0; corner < 6; corner++) {
			double angle = Math.toRadians(60 * corner + 30);
			int tx = (int) Math.floor(ax + reach * Math.cos(angle));
			int tz = (int) Math.floor(az + reach * Math.sin(angle));
			if (Math.abs(x - tx) > 1 || Math.abs(z - tz) > 1) {
				continue;
			}
			int top = LAND + height + (plan.capital ? 4 : 3);
			fill(out, LAND + 1, top, stone);
			if (x == tx && z == tz) {
				set(out, top + 1, Material.cell(Material.WOOL, plan.dye));
				set(out, top + 2, Material.cell(Material.BANNER, plan.dye));
			} else if (((x + z) & 1) == 0) {
				set(out, top + 1, stone);
			}
			return;
		}
		if (rim >= 0.76 && rim < 0.76 + 1.25 / half) {
			double px = x + 0.5;
			double pz = z + 0.5;
			for (HexEdge e : HexEdge.values()) {
				GridPos mid = new GridPos(center.u() + e.du / 2.0, center.v() + e.dv / 2.0);
				if (distanceToSegment(px, pz, ax, az, layout.x(mid), layout.z(mid)) < 1.6) {
					return;
				}
			}
			fill(out, LAND + 1, LAND + height, stone);
			// Battlements: every other block of the top stands up.
			if (((x + z) & 1) == 0 && stone != Material.LOG_SPRUCE) {
				set(out, LAND + height + 1, stone);
			}
		}
	}

	private int improvement(int[] out, Improvement kind, boolean pillaged, double rim, int x, int z, int dx, int dz, int top) {
		if (pillaged || kind == Improvement.RUINS) {
			if (rim < 0.5 && hash(x, z, 9) % 5 == 0) {
				set(out, top + 1, Material.COBBLE);
			}
			return top;
		}
		boolean centre = dx == 0 && dz == 0;
		switch (kind) {
			case FARM -> {
				if (centre) {
					set(out, top, Material.WATER);
				} else {
					set(out, top, Material.FARMLAND);
					set(out, top + 1, Material.WHEAT);
				}
			}
			case MINE -> {
				if (rim < 0.36) {
					set(out, top, Material.COBBLE);
					if (centre) {
						fill(out, top + 1, top + 2, Material.FENCE);
						set(out, top + 3, Material.LIGHT);
					} else if (hash(x, z, 10) % 6 == 0) {
						set(out, top + 1, Material.ORE);
					}
				}
			}
			case PASTURE -> {
				if (Math.abs(rim - 0.5) < 0.9 / half) {
					set(out, top + 1, Material.FENCE);
				} else if (centre) {
					set(out, top + 1, Material.HAY);
				}
			}
			case CAMP -> {
				if (centre) {
					set(out, top + 1, Material.CAMPFIRE);
				} else if (Math.max(Math.abs(dx), Math.abs(dz)) == 2 && hash(x, z, 11) % 3 == 0) {
					set(out, top + 1, Material.LOG_OAK);
				}
			}
			case PLANTATION -> {
				if (Math.floorMod(x, 3) == 0) {
					set(out, top + 1, Material.LEAVES_OAK);
				}
			}
			case QUARRY -> {
				if (rim < 0.4) {
					fill(out, top - 1, top, Material.AIR);
					set(out, top - 2, hash(x, z, 12) % 3 == 0 ? Material.COBBLE : Material.STONE);
					return top - 2;
				}
			}
			default -> {
				// Anything else (trading post, fort, lumber mill, a modded improvement): a small hut.
				int m = Math.max(Math.abs(dx), Math.abs(dz));
				if (m <= 2) {
					set(out, top, Material.PLANKS);
					if (m == 2) {
						for (int y = top + 1; y <= top + 3; y++) {
							if (!(dx == 0 && dz == 2 && y <= top + 2)) {
								set(out, y, Material.PLANKS);
							}
						}
					}
					set(out, top + 4, centre ? Material.LIGHT : Material.COBBLE);
				}
			}
		}
		return top;
	}

	private static final int CELL = 5;

	/** Adds the trunk or leaves of any tree whose crown reaches this column. */
	private void trees(int[] out, int x, int z) {
		int cellX = Math.floorDiv(x, CELL);
		int cellZ = Math.floorDiv(z, CELL);
		for (int gx = cellX - 1; gx <= cellX + 1; gx++) {
			for (int gz = cellZ - 1; gz <= cellZ + 1; gz++) {
				int h = hash(gx, gz, 20);
				int ax = gx * CELL + h % CELL;
				int az = gz * CELL + (h >> 4) % CELL;
				int ddx = x - ax;
				int ddz = z - az;
				if (Math.abs(ddx) > 2 || Math.abs(ddz) > 2) {
					continue;
				}
				tree(out, ax, az, ddx, ddz, h);
			}
		}
	}

	private void tree(int[] out, int ax, int az, int ddx, int ddz, int h) {
		GridPos pos = layout.toGrid(ax + 0.5, az + 0.5);
		PlotCoord raw = grid.plotAt(pos);
		if (!onMap(raw)) {
			return;
		}
		PlotState plot = state.plot(raw.y() * width + raw.x());
		if (!plot.revealed() || plot.water() || plot.city() || plot.elevation() == PlotState.MOUNTAIN) {
			return;
		}
		Feature feature = plot.feature() < 0 ? Feature.NONE : features.getOrDefault(plot.feature(), Feature.WONDER);
		boolean jungle = feature == Feature.JUNGLE;
		if (feature != Feature.FOREST && !jungle) {
			return;
		}
		if ((h >> 8) % 100 >= (jungle ? 92 : 72)) {
			return;
		}
		GridPos center = grid.center(raw);
		double rim = HexMetric.rimFraction(pos, center);
		// Keep trees off the rim (rivers, borders, roads) and off the worked middle of an improved hex.
		if (rim > 1.0 - 3.2 / half || (plot.improvement() >= 0 && rim < 0.66) || plot.route() >= 0) {
			return;
		}
		Terrain terrain = terrains.getOrDefault(plot.terrain(), Terrain.OTHER);
		Material log = jungle ? Material.LOG_JUNGLE : terrain == Terrain.TUNDRA || terrain == Terrain.SNOW ? Material.LOG_SPRUCE : Material.LOG_OAK;
		Material leaves = jungle ? Material.LEAVES_JUNGLE : log == Material.LOG_SPRUCE ? Material.LEAVES_SPRUCE : Material.LEAVES_OAK;
		int ground = LAND + bump(plot, rim);
		int trunk = (jungle ? 6 : 4) + (h >> 16) % 3;
		if (ddx == 0 && ddz == 0) {
			fill(out, ground + 1, ground + trunk, log);
		}
		for (int dy = trunk - 2; dy <= trunk + 1; dy++) {
			int radius = dy <= trunk - 1 ? 2 : 1;
			int reach = Math.max(Math.abs(ddx), Math.abs(ddz));
			if (reach > radius) {
				continue;
			}
			// Round off the corners of the crown.
			if (Math.abs(ddx) == radius && Math.abs(ddz) == radius && (radius == 1 || (h >> (dy & 7)) % 2 == 0)) {
				continue;
			}
			int y = ground + dy;
			if (y >= BASE && y < TOP && out[y - BASE] == AIR) {
				out[y - BASE] = leaves.ordinal();
			}
		}
	}
}
