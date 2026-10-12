package nl.jordythomassen.livingworld.board;

import java.util.ArrayList;
import java.util.List;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.CivInfo;

/**
 * How one city is built in blocks: what its hall and houses are made of in its owner's era and
 * building style, which of its real buildings and wonders stand around the hall, and whether it
 * has walls. Worked out once per city from Civ's state.
 */
final class CityPlan {
	/** What stands on one of the eight places around the hall. */
	enum Kind { HOUSE, MONUMENT, GRANARY, BARRACKS, LIBRARY, TEMPLE, MARKET, WORKSHOP, WELL, ARENA, STABLE, LIGHTHOUSE, WONDER, PYRAMID, STONE_RING, GREAT_LIBRARY,
		DOME, STATUE, ZIGGURAT }

	/** How many of the eight lots wonders may take, so that a city of many wonders is still a city. */
	private static final int MOST_WONDERS = 4;

	/**
	 * Where the eight lots around a hall lie, seen from its middle, and how many quarter turns a
	 * building designed with its front to the south takes to face the hall from there.
	 */
	static final int[][] LOTS = { { 8, 3, 1 }, { 3, 8, 2 }, { -3, 8, 2 }, { -8, 3, 3 }, { -8, -3, 3 }, { -3, -8, 0 }, { 3, -8, 0 }, { 8, -3, 1 } };

	final int tier;
	final int dye;
	final boolean capital;
	final Builds.Style style;
	final Material wall;
	final Material plaza;
	final Material plazaWorn;
	/** Height of the wall around the city; 0 when it has none (or we cannot know). */
	final int ringWall;
	/** One of the viewer's own cities: its hall holds the bell and the lectern the player rules with. */
	final boolean own;
	final Kind[] slots = new Kind[8];
	final Structure hall;
	final Structure[] lots = new Structure[8];

	/** Whether it builds in sandstone where others build in stone. */
	final boolean dry;

	/** {@code inTown}: the wonders that stand among its houses (those with a hex of their own on the land are not built twice). */
	CityPlan(CityState city, CivInfo civ, int dye, boolean own, int hallHalf, int furniture, List<String> inTown) {
		this.dye = dye;
		this.own = own;
		this.capital = city.capital();
		this.tier = civ == null ? 0 : civ.eraTier();
		this.style = Builds.style(tier, civ == null ? "" : civ.artStyle());
		dry = style.roofKind() == Builds.Roof.FLAT && tier < 5;
		wall = dry ? Material.SMOOTH_SANDSTONE : tier == 0 ? Material.COBBLE : tier >= 5 ? Material.WHITE_CONCRETE : Material.STONE_BRICKS;
		plaza = tier == 0 ? Material.PATH : dry ? Material.SMOOTH_SANDSTONE : tier == 1 ? Material.COBBLE : tier >= 5 ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS;
		plazaWorn = tier == 0 ? Material.COARSE_DIRT : dry ? Material.SANDSTONE : tier == 1 ? Material.GRAVEL : tier >= 5 ? Material.GRAY_CONCRETE : Material.COBBLE;

		// Wonders first, then the buildings that have a shape of their own, then houses for the people.
		List<Kind> stands = new ArrayList<>();
		for (String wonder : inTown) {
			Kind kind = wonderKind(wonder, stands.size());
			if (stands.size() < MOST_WONDERS && !stands.contains(kind)) {
				stands.add(kind);
			}
		}
		int ring = 0;
		for (String building : city.buildings()) {
			if (building.contains("CASTLE") || building.contains("ARSENAL") || building.contains("MILITARY_BASE")) {
				ring = Math.max(ring, 5);
			} else if (building.contains("WALLS")) {
				ring = Math.max(ring, 3);
			}
			Kind kind = kindOf(building);
			// The last lot stays for a house: somebody lives here.
			if (kind != null && !stands.contains(kind) && stands.size() < 7) {
				stands.add(kind);
			}
		}
		if (city.wonders().stream().anyMatch(w -> w.contains("GREAT_WALL"))) {
			ring = Math.max(ring, 4);
		}
		ringWall = ring;
		int occupied = Math.max(stands.size(), Math.max(1, Math.min(8, city.population())));
		// No two houses quite alike, and the same city always the same.
		int seed = city.key().id() * 31 + city.key().owner() * 7;
		for (int i = 0; i < 8; i++) {
			slots[i] = i < stands.size() ? stands.get(i) : i < occupied ? Kind.HOUSE : null;
			if (slots[i] != null) {
				int variant = (seed >> i) ^ (i * 5);
				// A city that outgrows its eight lots builds upwards.
				boolean tall = city.population() > 8 + (i * 3) % 8;
				lots[i] = Builds.of(slots[i], style, dye, variant & 7, tall).turned(LOTS[i][2]);
			}
		}
		hall = Builds.hall(style, hallHalf, dye, capital, own, furniture);
	}

	/**
	 * Writes what the city's buildings put in one column, {@code dx}, {@code dz} from the middle
	 * of the hall. {@code withLots} is false on a hex too narrow for anything but the hall.
	 * True when something was built there.
	 */
	boolean column(int dx, int dz, boolean withLots, int[] out) {
		boolean built = false;
		if (withLots) {
			for (int i = 0; i < 8; i++) {
				if (lots[i] != null) {
					built |= lots[i].into(dx - LOTS[i][0], dz - LOTS[i][1], out);
				}
			}
		}
		// The hall last: where its eaves or its flag reach over a lot, they are the hall's.
		return hall.into(dx, dz, out) | built;
	}

	/** The shape a wonder gets: its own where it has one, else one of four kinds of landmark by what its name suggests. */
	static Kind wonderKind(String wonder, int nth) {
		if (has(wonder, "PYRAMID")) {
			return Kind.PYRAMID;
		}
		if (has(wonder, "STONEHENGE")) {
			return Kind.STONE_RING;
		}
		if (has(wonder, "GREAT_LIBRARY")) {
			return Kind.GREAT_LIBRARY;
		}
		if (has(wonder, "LIGHTHOUSE")) {
			return Kind.LIGHTHOUSE;
		}
		if (has(wonder, "COLOSSUS", "STATUE", "CRISTO", "ZEUS", "LIBERTY")) {
			return Kind.STATUE;
		}
		if (has(wonder, "TOWER", "BIG_BEN", "EIFFEL", "PISA")) {
			return Kind.WONDER;
		}
		if (has(wonder, "ORACLE", "HANGING", "MAUSOLEUM", "TERRACOTTA", "CHICHEN", "MACHU", "PETRA", "ARTEMIS")) {
			return Kind.ZIGGURAT;
		}
		if (has(wonder, "HAGIA", "TAJ", "PANTHEON", "TEMPLE", "MOSQUE", "CATHEDRAL", "NOTRE", "SISTINE", "BOROBUDUR", "ANGKOR", "CHAPEL", "KREMLIN", "ALHAMBRA", "PALACE",
			"OPERA", "LOUVRE", "UFFIZI", "GLOBE")) {
			return Kind.DOME;
		}
		return switch (nth % 4) {
			case 0 -> Kind.WONDER;
			case 1 -> Kind.DOME;
			case 2 -> Kind.ZIGGURAT;
			default -> Kind.STATUE;
		};
	}

	/** The shape a building gets, or null when it has none of its own (it is then not shown). */
	static Kind kindOf(String building) {
		if (has(building, "MONUMENT", "OBELISK", "STELE")) {
			return Kind.MONUMENT;
		}
		if (has(building, "GRANARY")) {
			return Kind.GRANARY;
		}
		if (has(building, "BARRACKS", "ARMORY", "MILITARY_ACADEMY", "KREPOST", "IKANDA")) {
			return Kind.BARRACKS;
		}
		if (has(building, "LIBRARY", "UNIVERSITY", "PUBLIC_SCHOOL", "LABORATORY", "OBSERVATORY", "PAPER_MAKER", "WAT")) {
			return Kind.LIBRARY;
		}
		if (has(building, "SHRINE", "TEMPLE", "CATHEDRAL", "MOSQUE", "PAGODA", "MONASTERY", "PYRAMID")) {
			return Kind.TEMPLE;
		}
		if (has(building, "MARKET", "BANK", "STOCK", "MINT", "BAZAAR", "CARAVANSARY", "SATRAP")) {
			return Kind.MARKET;
		}
		if (has(building, "WORKSHOP", "FORGE", "FACTORY", "WINDMILL", "STONE_WORKS", "IRONWORKS", "LONGHOUSE")) {
			return Kind.WORKSHOP;
		}
		if (has(building, "WATERMILL", "AQUEDUCT", "GARDEN", "HOSPITAL", "MEDICAL", "FLOATING")) {
			return Kind.WELL;
		}
		if (has(building, "COLOSSEUM", "CIRCUS", "THEATRE", "THEATER", "STADIUM", "OPERA", "ARENA")) {
			return Kind.ARENA;
		}
		if (has(building, "STABLE", "DUCAL")) {
			return Kind.STABLE;
		}
		if (has(building, "LIGHTHOUSE", "HARBOR", "SEAPORT")) {
			return Kind.LIGHTHOUSE;
		}
		return null;
	}

	private static boolean has(String text, String... words) {
		for (String word : words) {
			if (text.contains(word)) {
				return true;
			}
		}
		return false;
	}
}
