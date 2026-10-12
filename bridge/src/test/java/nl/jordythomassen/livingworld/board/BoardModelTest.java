package nl.jordythomassen.livingworld.board;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import nl.jordythomassen.livingworld.coords.McLayout;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.link.CivLink;
import nl.jordythomassen.livingworld.mock.MockCiv;
import nl.jordythomassen.livingworld.mock.MockTransport;
import nl.jordythomassen.livingworld.protocol.StateAssembler;
import nl.jordythomassen.livingworld.protocol.Wire;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.WorldState;
import org.junit.jupiter.api.Test;

class BoardModelTest {
	private static final int SIZE = BoardModel.TOP - BoardModel.BASE;

	private static WorldState realGame() throws IOException {
		StateAssembler assembler = new StateAssembler();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			BoardModelTest.class.getResourceAsStream("/nl/jordythomassen/livingworld/fixtures/real-duel-rome-turn0.txt"), StandardCharsets.UTF_8))) {
			for (String line; (line = in.readLine()) != null; ) {
				List<String> fields = Wire.parse(line);
				if (fields != null) {
					assembler.accept(fields);
				}
			}
		}
		return assembler.build();
	}

	private static WorldState mockGame(boolean revealAll) throws IOException {
		MockCiv civ = new MockCiv(40, 25, 7);
		if (revealAll) {
			// Send Roman scouts everywhere so the whole island is explored.
			for (int y = 1; y < 25; y += 3) {
				for (int x = 1; x < 40; x += 3) {
					civ.moveUnit(civ.spawn(MockCiv.ROME, "UNIT_SCOUT", "Scout", x, y, 2, 5), x, y);
				}
			}
		}
		CivLink link = new CivLink(() -> new MockTransport(civ), () -> "LivingWorld={VERSION=1}", s -> { });
		link.setRetryMs(0);
		for (int i = 0; i < 3; i++) {
			link.pumpOnce();
		}
		return link.state();
	}

	private static BoardModel board(WorldState state, double hexWidth) {
		double rowSpacing = hexWidth * Math.sqrt(3) / 2;
		// Centre the map on the block origin.
		return new BoardModel(state, new McLayout(hexWidth, -state.session().grid().width() * hexWidth / 2.0,
			state.session().grid().height() * rowSpacing / 2.0));
	}

	private static Material at(int[] column, int y) {
		return Material.of(column[y - BoardModel.BASE]);
	}

	private static Set<Material> inHex(BoardModel board, PlotCoord plot) {
		Set<Material> seen = EnumSet.noneOf(Material.class);
		int cx = board.centerX(plot);
		int cz = board.centerZ(plot);
		int reach = (int) board.layout().hexWidth();
		int[] column = new int[SIZE];
		for (int x = cx - reach; x <= cx + reach; x++) {
			for (int z = cz - reach; z <= cz + reach; z++) {
				if (plot.equals(board.plotAt(x, z))) {
					board.column(x, z, column);
					for (int cell : column) {
						seen.add(Material.of(cell));
					}
				}
			}
		}
		return seen;
	}

	@Test
	void theCapitalOfTheRealGameIsAKeepInRomesColourOnItsRealPlot() throws IOException {
		WorldState state = realGame();
		BoardModel board = board(state, 32);
		CityState rome = state.cities().values().iterator().next();
		int cx = board.centerX(rome.at());
		int cz = board.centerZ(rome.at());
		assertEquals(rome.at(), board.plotAt(cx, cz));
		int[] column = new int[SIZE];
		assertEquals(BoardModel.LAND, board.column(cx, cz, column));
		assertEquals(Material.PLANKS, at(column, BoardModel.LAND));
		assertEquals(Material.AIR, at(column, BoardModel.LAND + 1), "room to stand inside");
		// Turn 0 of the real game, the ancient era: a long hall of timber and mud brick under a great thatched roof,
		// with the owner's colour along its ridge.
		board.column(cx + 2, cz, column);
		assertEquals(Material.ROOF, at(column, BoardModel.LAND + 8));
		assertEquals(10, Material.dye(column[BoardModel.LAND + 8 - BoardModel.BASE]), "purple, the closest wool colour to Rome's #460076");
		board.column(cx + 2, cz + 2, column);
		assertEquals(Material.HAY, at(column, BoardModel.LAND + 6), "thatch, one block lower for every block from the ridge");
		assertEquals(Material.AIR, at(column, BoardModel.LAND + 5), "open to the rafters inside");
		board.column(cx + 4, cz + 3, column);
		assertEquals(Material.MUD_BRICKS, at(column, BoardModel.LAND + 2), "a wall");
		board.column(cx, cz + 4, column);
		assertEquals(Material.AIR, at(column, BoardModel.LAND + 1), "the door, in the middle of the south wall");
		assertEquals(Material.AIR, at(column, BoardModel.LAND + 2));
		assertEquals(Material.LOG_OAK, at(column, BoardModel.LAND + 3), "under a beam");
		// The first lot, east of the hall: a hut for its one citizen, with its door towards the hall.
		board.column(cx + 8, cz + 3, column);
		assertEquals(Material.AIR, at(column, BoardModel.LAND + 1), "room inside the hut");
		assertEquals(Material.HAY, at(column, BoardModel.LAND + 5), "the ridge of its roof");
		board.column(cx + 6, cz + 3, column);
		assertEquals(Material.AIR, at(column, BoardModel.LAND + 1), "its door");
		board.column(cx - 8, cz + 3, column);
		assertEquals(Material.AIR, at(column, BoardModel.LAND + 3), "the other lots are empty: one citizen, one house");
		Set<Material> seen = inHex(board, rome.at());
		assertTrue(seen.contains(Material.FLAG));
		assertTrue(seen.contains(Material.GOLD), "gold on the flagpole of a capital");
	}

	@Test
	void theRiverAroundRealRomeRunsAlongTheEdgesCivSaysItIsOn() throws IOException {
		WorldState state = realGame();
		BoardModel board = board(state, 32);
		PlotCoord rome = state.cities().values().iterator().next().at();
		assertEquals(PlotState.RIVER_EAST | PlotState.RIVER_SOUTH_EAST | PlotState.RIVER_SOUTH_WEST, state.plot(rome).rivers());
		int cx = board.centerX(rome);
		int cz = board.centerZ(rome);
		int[] column = new int[SIZE];
		// Walking east from the centre you reach water at the east edge...
		board.column(cx + 15, cz, column);
		assertEquals(Material.WATER, at(column, BoardModel.LAND));
		// ...and walking west you do not (no river on the west edge).
		board.column(cx - 15, cz, column);
		assertNotEquals(Material.WATER, at(column, BoardModel.LAND));
		// South-east edge: half-way between east and south.
		boolean southEast = false;
		for (int d = 10; d <= 16 && !southEast; d++) {
			board.column(cx + d / 2, cz + (int) Math.round(d * 0.866), column);
			southEast = at(column, BoardModel.LAND) == Material.WATER;
		}
		assertTrue(southEast);
	}

	@Test
	void unexploredLandIsAWallOfCloudAndNothingElse() throws IOException {
		WorldState state = realGame();
		BoardModel board = board(state, 32);
		PlotCoord unknown = new PlotCoord(2, 2);
		assertFalse(state.plot(unknown).revealed());
		Set<Material> seen = inHex(board, unknown);
		assertEquals(EnumSet.of(Material.AIR, Material.CLOUD), seen);
		int[] column = new int[SIZE];
		int top = board.column(board.centerX(unknown), board.centerZ(unknown), column);
		assertTrue(top >= BoardModel.LAND + 3, "too high to walk up onto from the land");
	}

	@Test
	void theBoardEndsInOpenSeaAndThenNothing() throws IOException {
		BoardModel board = board(mockGame(false), 32);
		int[] bounds = board.bounds();
		int[] column = new int[SIZE];
		int justOutside = board.centerX(new PlotCoord(0, 12)) - 40;
		assertEquals(null, board.plotAt(justOutside, board.centerZ(new PlotCoord(0, 12))));
		assertEquals(BoardModel.SEA, board.column(justOutside, board.centerZ(new PlotCoord(0, 12)), column));
		assertEquals(Material.WATER, at(column, BoardModel.SEA));
		assertEquals(BoardModel.NOTHING, board.column(bounds[0] - 200, 0, column));
		for (int cell : column) {
			assertEquals(Material.AIR, Material.of(cell));
		}
		// The Civ map wraps east-west; the board does not repeat.
		assertEquals(null, board.plotAt(board.centerX(new PlotCoord(0, 12)) + 40 * 32, board.centerZ(new PlotCoord(0, 12))));
	}

	@Test
	void everyColumnOfTheBoardIsWholeAndTheSameEveryTime() throws IOException {
		for (double hexWidth : new double[] { 16, 32, 48 }) {
			BoardModel board = board(mockGame(true), hexWidth);
			int[] bounds = board.bounds();
			int[] a = new int[SIZE];
			int[] b = new int[SIZE];
			int painted = 0;
			for (int x = bounds[0]; x <= bounds[2]; x += 3) {
				for (int z = bounds[1]; z <= bounds[3]; z += 3) {
					int surface = board.column(x, z, a);
					if (surface == BoardModel.NOTHING) {
						continue;
					}
					painted++;
					assertTrue(surface >= BoardModel.BASE && surface < BoardModel.TOP, "surface " + surface);
					assertNotEquals(Material.AIR, at(a, surface), "a hole at " + x + "," + z + " (hex width " + hexWidth + ")");
					assertEquals(surface, board.column(x, z, b));
					assertArrayEquals(a, b);
				}
			}
			assertTrue(painted > 1000);
		}
	}

	@Test
	void hillsCanBeWalkedAndMountainsCannot() throws IOException {
		WorldState state = mockGame(true);
		BoardModel board = board(state, 32);
		PlotCoord hill = null;
		PlotCoord mountain = null;
		for (int y = 0; y < 25; y++) {
			for (int x = 0; x < 40; x++) {
				PlotState plot = state.plot(new PlotCoord(x, y));
				if (plot.revealed() && !plot.water() && !plot.city() && plot.route() < 0) {
					if (plot.elevation() == PlotState.HILLS && hill == null) {
						hill = new PlotCoord(x, y);
					} else if (plot.elevation() == PlotState.MOUNTAIN && mountain == null) {
						mountain = new PlotCoord(x, y);
					}
				}
			}
		}
		int[] column = new int[SIZE];
		int cz = board.centerZ(hill);
		int previous = -1;
		for (int x = board.centerX(hill) - 16; x <= board.centerX(hill); x++) {
			if (!hill.equals(board.plotAt(x, cz))) {
				continue;
			}
			int surface = board.column(x, cz, column);
			if (at(column, surface) == Material.WATER || at(column, surface) == Material.BORDER) {
				continue;
			}
			if (previous >= 0) {
				assertTrue(Math.abs(surface - previous) <= 1, "a step of more than one block on a hill");
			}
			previous = surface;
		}
		assertTrue(previous >= BoardModel.LAND + 4, "the hill rises towards its middle");
		int peak = board.column(board.centerX(mountain), board.centerZ(mountain), column);
		assertTrue(peak >= BoardModel.LAND + 20, "a mountain towers over the land: " + peak);
		assertEquals(Material.SNOW_BLOCK, at(column, peak));
		int flank = board.column(board.centerX(mountain) + 4, board.centerZ(mountain), column);
		assertTrue(peak - flank >= 5, "too steep to walk up");
	}

	@Test
	void forestsFarmsRoadsAndBordersShowUpWhereCivHasThem() throws IOException {
		MockCiv civ = new MockCiv(40, 25, 7);
		CivLink link = new CivLink(() -> new MockTransport(civ), () -> "LivingWorld={VERSION=1}", s -> { });
		link.setRetryMs(0);
		for (int i = 0; i < 3; i++) {
			link.pumpOnce();
		}
		WorldState state = link.state();
		BoardModel board = board(state, 32);
		PlotCoord rome = civ.cityNamed("Rome") == null ? null : new PlotCoord(civ.cityNamed("Rome").x, civ.cityNamed("Rome").y);
		PlotCoord farm = new PlotCoord(rome.x() + 1, rome.y() - 1);
		if (state.plot(farm).improvement() >= 0) {
			Set<Material> seen = inHex(board, farm);
			assertTrue(seen.contains(Material.WHEAT) && seen.contains(Material.FARMLAND), seen.toString());
		}
		PlotCoord road = new PlotCoord(rome.x() + 1, rome.y());
		assertTrue(inHex(board, road).contains(Material.PATH), "the road towards the city");
		// Rome owns its own plot and the six around it: the outer rim of that ring is a border line.
		Set<Material> ring = inHex(board, new PlotCoord(rome.x() + 1, rome.y()));
		assertTrue(ring.contains(Material.BORDER));
		Set<Material> inside = inHex(board, rome);
		assertFalse(inside.contains(Material.BORDER), "no border between two plots of the same owner");
		boolean forest = false;
		for (int y = 0; y < 25 && !forest; y++) {
			for (int x = 0; x < 40 && !forest; x++) {
				PlotState plot = state.plot(new PlotCoord(x, y));
				if (plot.revealed() && plot.feature() == 5 && plot.elevation() != PlotState.MOUNTAIN && plot.improvement() < 0 && plot.route() < 0 && !plot.city()) {
					Set<Material> seen = inHex(board, new PlotCoord(x, y));
					assertTrue(seen.contains(Material.LOG_OAK) || seen.contains(Material.LOG_SPRUCE), "a forest hex without trees at " + x + "," + y);
					assertTrue(seen.contains(Material.LEAVES_OAK) || seen.contains(Material.LEAVES_SPRUCE));
					forest = true;
				}
			}
		}
		assertTrue(forest, "the mock has a visible forest");
	}

	private static WorldState mockWith(java.util.function.Consumer<MockCiv> setup) throws IOException {
		MockCiv civ = new MockCiv(40, 25, 7);
		setup.accept(civ);
		CivLink link = new CivLink(() -> new MockTransport(civ), () -> "LivingWorld={VERSION=1}", s -> { });
		link.setRetryMs(0);
		for (int i = 0; i < 3; i++) {
			link.pumpOnce();
		}
		return link.state();
	}

	private static CityState romeOf(WorldState state) {
		return state.cities().values().stream().filter(c -> c.name().equals("Rome")).findFirst().orElseThrow();
	}

	@Test
	void thePlayersOwnHallsHoldABellAndALecternAndNobodyElsesDo() throws IOException {
		WorldState state = mockWith(civ -> { });
		for (int width : new int[] { 16, 24, 32, 64 }) {
			BoardModel board = board(state, width);
			int[] column = new int[SIZE];
			for (CityState city : state.cities().values()) {
				boolean own = city.key().owner() == state.session().viewer();
				Set<Material> seen = inHex(board, city.at());
				assertEquals(own, seen.contains(Material.BELL), city.name() + " at hex width " + width);
				assertEquals(own, seen.contains(Material.LECTERN), city.name() + " at hex width " + width);
				if (own) {
					// Exactly where the mod looks for them, standing on the floor with room above.
					int[][] furniture = board.hallFurniture(city.at());
					board.column(furniture[0][0], furniture[0][2], column);
					assertEquals(Material.BELL, at(column, furniture[0][1]));
					assertEquals(Material.PLANKS, at(column, furniture[0][1] - 1));
					assertEquals(Material.AIR, at(column, furniture[0][1] + 1));
					board.column(furniture[1][0], furniture[1][2], column);
					assertEquals(Material.LECTERN, at(column, furniture[1][1]));
					// The middle of the hall stays free for the governor.
					board.column(board.centerX(city.at()), board.centerZ(city.at()), column);
					assertEquals(Material.AIR, at(column, BoardModel.LAND + 1));
				}
			}
			assertTrue(state.cities().values().stream().anyMatch(c -> c.key().owner() != state.session().viewer()), "the mock shows a foreign city too");
		}
	}

	@Test
	void aCityIsBuiltInTheStyleOfItsEra() throws IOException {
		// Rome builds in the Mediterranean way: sandstone, then columns and red tiles, a stone keep, plastered walls, and so on.
		Material[] hall = { Material.SMOOTH_SANDSTONE, Material.QUARTZ_PILLAR, Material.STONE_BRICKS, Material.PLASTER, Material.DEEPSLATE_BRICKS, Material.WHITE_CONCRETE };
		Material[] roofs = { Material.SMOOTH_SANDSTONE, Material.SLAB_BRICK, Material.SLAB_STONE_BRICK, Material.STAIRS_BRICK, Material.STAIRS_DEEPSLATE, Material.GRAY_CONCRETE };
		String[] eras = { "ERA_ANCIENT", "ERA_CLASSICAL", "ERA_MEDIEVAL", "ERA_RENAISSANCE", "ERA_INDUSTRIAL", "ERA_MODERN" };
		int[] height = new int[eras.length];
		for (int tier = 0; tier < eras.length; tier++) {
			String era = eras[tier];
			WorldState state = mockWith(civ -> civ.era = era);
			assertEquals(tier, state.civ(MockCiv.ROME).eraTier());
			BoardModel board = board(state, 32);
			PlotCoord rome = romeOf(state).at();
			Set<Material> seen = inHex(board, rome);
			assertTrue(seen.contains(hall[tier]), era + " hall of " + hall[tier] + ", saw " + seen);
			assertTrue(seen.contains(roofs[tier]), era + " roofs of " + roofs[tier] + ", saw " + seen);
			assertEquals(tier >= 1, seen.contains(Material.GLASS_PANE) || seen.contains(Material.TINTED_GLASS), era + ": glass in the windows after the ancient era");
			assertEquals(tier >= 5, seen.contains(Material.TINTED_GLASS), era + ": walls of glass in the modern era");
			// The owner's colour is on the roof of every hall; how high says how far the city has come.
			int[] column = new int[SIZE];
			for (int dx = -4; dx <= 4; dx++) {
				for (int dz = -4; dz <= 4; dz++) {
					board.column(board.centerX(rome) + dx, board.centerZ(rome) + dz, column);
					assertNotEquals(Material.AIR, at(column, BoardModel.LAND), "a floor");
					for (int y = BoardModel.LAND + 1; y < BoardModel.TOP; y++) {
						if (at(column, y) == Material.ROOF) {
							height[tier] = Math.max(height[tier], y - BoardModel.LAND);
						}
					}
				}
			}
			assertTrue(height[tier] >= 5, era + " roof at " + height[tier]);
		}
		for (int tier = 0; tier < 5; tier++) {
			assertTrue(height[5] > height[tier], "the modern tower stands over every older hall: " + java.util.Arrays.toString(height));
		}
	}

	@Test
	void realBuildingsWondersAndWallsStandInTheCity() throws IOException {
		WorldState bare = mockWith(civ -> civ.era = "ERA_MEDIEVAL");
		WorldState built = mockWith(civ -> {
			civ.era = "ERA_MEDIEVAL";
			civ.cityNamed("Rome").buildings.addAll(List.of("GRANARY", "LIBRARY", "WALLS", "COURTHOUSE"));
			civ.cityNamed("Rome").wonders.add("PYRAMID");
		});
		PlotCoord rome = romeOf(built).at();
		assertEquals(List.of("PALACE", "GRANARY", "LIBRARY", "WALLS", "COURTHOUSE"), romeOf(built).buildings());
		Set<Material> before = inHex(board(bare, 32), rome);
		Set<Material> after = inHex(board(built, 32), rome);
		assertFalse(before.contains(Material.BOOKSHELF));
		assertTrue(after.contains(Material.BOOKSHELF), "a library");
		assertFalse(before.contains(Material.SMOOTH_SANDSTONE));
		// A wonder of the world is too large for the town: it stands on a hex of its own beside it, on Rome's own land,
		// many times the size of a house, under Rome's colours. In the town itself it is not built a second time.
		BoardModel withWonder = board(built, 32);
		assertEquals(1, withWonder.wonderSites().size(), "the Pyramids have a hex of their own");
		BoardModel.Site site = withWonder.wonderSites().iterator().next();
		assertEquals("PYRAMID", site.wonder());
		assertEquals(romeOf(built).key(), site.city());
		assertEquals(1, built.session().grid().distance(rome, site.plot()), "beside the city");
		assertEquals(romeOf(built).key().owner(), built.plot(site.plot()).owner(), "on its own land");
		assertEquals(site, withWonder.siteAt(site.plot()));
		Set<Material> onSite = inHex(withWonder, site.plot());
		assertTrue(onSite.contains(Material.SMOOTH_SANDSTONE) && onSite.contains(Material.GOLD), "the Pyramids, capped with gold: " + onSite);
		assertTrue(onSite.contains(Material.BANNER), "under the colours of who built them");
		assertFalse(after.contains(Material.SMOOTH_SANDSTONE), "and not once more in the town");
		int[] middle = new int[SIZE];
		int peak = withWonder.column(withWonder.centerX(site.plot()), withWonder.centerZ(site.plot()), middle);
		assertTrue(peak >= BoardModel.LAND + 9, "nine courses high and more: " + (peak - BoardModel.LAND));
		assertTrue(board(bare, 32).wonderSites().isEmpty(), "no wonder, no site");
		// A city with more wonders than it has land around it keeps the rest in the town, as before.
		WorldState many = mockWith(civ -> {
			civ.era = "ERA_MEDIEVAL";
			civ.cityNamed("Rome").wonders.addAll(List.of("PYRAMID", "STONEHENGE", "GREAT_LIBRARY", "COLOSSUS", "ORACLE", "HAGIA_SOPHIA", "BIG_BEN", "GREAT_LIGHTHOUSE",
				"HANGING_GARDENS", "TAJ_MAHAL", "EIFFEL_TOWER", "STATUE_OF_ZEUS", "PETRA", "NOTRE_DAME", "MACHU_PICCHU", "KREMLIN", "LOUVRE", "PISA", "CHICHEN_ITZA", "ALHAMBRA"));
		});
		BoardModel crowded = board(many, 32);
		java.util.Set<PlotCoord> used = new java.util.HashSet<>();
		for (BoardModel.Site each : crowded.wonderSites()) {
			assertTrue(used.add(each.plot()), "one wonder to a hex: " + each);
			assertFalse(many.plot(each.plot()).water() || many.plot(each.plot()).city(), "on land, outside the town: " + each);
			assertEquals(romeOf(many).key().owner(), many.plot(each.plot()).owner(), "on its own land: " + each);
		}
		assertTrue(crowded.wonderSites().size() >= 3 && crowded.wonderSites().size() < 20, "as many as there is land for: " + crowded.wonderSites().size());
		System.out.println("wonders on the land: " + crowded.wonderSites().stream().map(s -> s.wonder() + "@" + s.plot().x() + "," + s.plot().y()).toList());
		// The city wall: a ring of the hall's stone near the rim, with a gap towards each neighbour.
		BoardModel board = board(built, 32);
		int cx = board.centerX(rome);
		int cz = board.centerZ(rome);
		int[] column = new int[SIZE];
		int walled = 0;
		int open = 0;
		for (int x = cx - 16; x <= cx + 16; x++) {
			for (int z = cz - 16; z <= cz + 16; z++) {
				if (!rome.equals(board.plotAt(x, z))) {
					continue;
				}
				board.column(x, z, column);
				double r = Math.hypot(x - cx, z - cz);
				if (r > 11.5 && r < 14.5 && at(column, BoardModel.LAND + 3) == Material.STONE_BRICKS) {
					walled++;
				}
			}
		}
		board.column(cx + 13, cz, column);
		if (at(column, BoardModel.LAND + 1) == Material.AIR || at(column, BoardModel.LAND + 1) == Material.WATER) {
			open++;
		}
		assertTrue(walled > 30, "a wall around the city: " + walled + " columns");
		assertEquals(1, open, "a gate where the road east leaves");
		// A city that has built no walls of its own still has a ring round it, a block lower, and the same six towers at its corners.
		BoardModel bareBoard = board(bare, 32);
		int bareLow = 0;
		int bareHigh = 0;
		int builtHigh = 0;
		int banners = 0;
		for (int x = cx - 16; x <= cx + 16; x++) {
			for (int z = cz - 16; z <= cz + 16; z++) {
				if (rome.equals(bareBoard.plotAt(x, z))) {
					double r = Math.hypot(x - cx, z - cz);
					boolean ring = r > 11.5 && r < 14.5;
					// Where no battlement stands: what is solid four blocks up is wall, not its teeth.
					boolean plain = ((x + z) & 1) != 0;
					bareBoard.column(x, z, column);
					bareLow += ring && at(column, BoardModel.LAND + 2) == Material.STONE_BRICKS ? 1 : 0;
					bareHigh += ring && plain && at(column, BoardModel.LAND + 4) == Material.STONE_BRICKS ? 1 : 0;
					for (int y = BoardModel.LAND + 5; y < BoardModel.LAND + 12; y++) {
						banners += at(column, y) == Material.BANNER ? 1 : 0;
					}
					board.column(x, z, column);
					builtHigh += ring && plain && at(column, BoardModel.LAND + 4) == Material.STONE_BRICKS ? 1 : 0;
				}
			}
		}
		assertTrue(bareLow > 30, "a low wall round a city without walls of its own: " + bareLow + " columns");
		assertTrue(builtHigh > bareHigh + 15, "and a higher one once it has built walls: " + builtHigh + " against " + bareHigh);
		assertEquals(6, banners, "a banner on each of the six towers");
	}

	@Test
	void aBuildingWithoutAShapeOfItsOwnTakesNoPlaceAndHousesFillTheRest() {
		assertEquals(CityPlan.Kind.GRANARY, CityPlan.kindOf("GRANARY"));
		assertEquals(CityPlan.Kind.LIBRARY, CityPlan.kindOf("UNIVERSITY"));
		assertEquals(CityPlan.Kind.TEMPLE, CityPlan.kindOf("SHRINE"));
		assertEquals(CityPlan.Kind.MARKET, CityPlan.kindOf("STOCK_EXCHANGE"));
		assertEquals(null, CityPlan.kindOf("COURTHOUSE"));
		assertEquals(null, CityPlan.kindOf("SOME_MODDED_THING"));
	}

	@Test
	void aResourceShowsAsAnOutcropOnItsPlot() throws IOException {
		WorldState state = mockWith(civ -> { });
		PlotCoord rome = romeOf(state).at();
		PlotCoord iron = new PlotCoord(rome.x() + 2, rome.y());
		assertEquals("RESOURCE_IRON", state.legend().type(nl.jordythomassen.livingworld.state.Legend.Kind.RESOURCE, state.plot(iron).resource()));
		BoardModel board = board(state, 32);
		assertTrue(inHex(board, iron).contains(Material.ORE), "iron ore to mine");
		assertFalse(inHex(board, new PlotCoord(rome.x() - 1, rome.y())).contains(Material.ORE));
		assertEquals(Material.ORE_COAL, BoardModel.resourceMaterial("RESOURCE_COAL"));
		assertEquals(Material.QUARTZ, BoardModel.resourceMaterial("RESOURCE_MARBLE"));
		assertEquals(Material.HAY, BoardModel.resourceMaterial("RESOURCE_HORSE"));
		assertEquals(Material.HAY, BoardModel.resourceMaterial("RESOURCE_SOMETHING_MODDED"));
	}

	@Test
	void theNearestWoolColourIsTheObviousOne() {
		assertEquals("purple", Dye.NAMES[Dye.nearest(0x460076)]);
		assertEquals("yellow", Dye.NAMES[Dye.nearest(0xFFFB03)]);
		assertEquals("red", Dye.NAMES[Dye.nearest(0xC02020)]);
		assertEquals("white", Dye.NAMES[Dye.nearest(0xFFFFFF)]);
		assertEquals("black", Dye.NAMES[Dye.nearest(0x000000)]);
		assertEquals("blue", Dye.NAMES[Dye.nearest(0x2030B0)]);
	}
}
