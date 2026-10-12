package nl.jordythomassen.livingworld.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.EntityKey;
import org.junit.jupiter.api.Test;

/**
 * Builds cities of every era and style and writes pictures of them to build/preview, to be
 * looked at; and checks what must hold for any of them.
 */
class CityPreviewTest {
	private static final int SIZE = BoardModel.TOP - BoardModel.BASE;
	private static final String[] ERAS = { "ERA_ANCIENT", "ERA_CLASSICAL", "ERA_MEDIEVAL", "ERA_RENAISSANCE", "ERA_INDUSTRIAL", "ERA_MODERN" };

	private static CityPlan plan(int tier, String art, int population, boolean capital, List<String> buildings, List<String> wonders, int dye) {
		CivInfo civ = new CivInfo(0, 0, "CIVILIZATION_TEST", "Test", "Test", "Tester", true, false, false, true, 0x460076, 0xFFFFFF, false, true, ERAS[tier], art);
		CityState city = new CityState(new EntityKey(0, 8192 + tier), "Testville", new PlotCoord(3, 3), population, (capital ? CityState.F_CAPITAL : 0) | CityState.F_DETAILS,
			200, 200, "", "", 0, 0, 0, 0, 0, 10, buildings, wonders);
		return new CityPlan(city, civ, dye, true, 4, 2, city.wonders());
	}

	private static BufferedImage picture(CityPlan plan) {
		return Preview.draw((x, z, out) -> {
			Arrays.fill(out, Material.AIR.ordinal());
			for (int y = 0; y < BoardModel.LAND - BoardModel.BASE; y++) {
				out[y] = Material.DIRT.ordinal();
			}
			out[BoardModel.LAND - BoardModel.BASE] = (Math.hypot(x, z) < 13.5 ? plan.plaza : Material.GRASS).ordinal();
			plan.column(x, z, true, out);
		}, -14, -14, 14, 14, BoardModel.LAND);
	}

	@Test
	void citiesOfEveryEraAreDrawn() throws IOException {
		Path dir = Path.of("build", "preview");
		List<String> early = List.of("MONUMENT", "GRANARY", "BARRACKS");
		List<String> full = List.of("LIBRARY", "TEMPLE", "MARKET", "WORKSHOP", "AQUEDUCT", "COLOSSEUM", "STABLE", "LIGHTHOUSE");
		for (int tier = 0; tier < 6; tier++) {
			Preview.write(dir.resolve("era" + tier + ".png"), picture(plan(tier, "ARTSTYLE_EUROPEAN", 6, true, early, List.of(), 10)),
				picture(plan(tier, "ARTSTYLE_EUROPEAN", 14, false, full, List.of(), 14)));
		}
		Preview.write(dir.resolve("styles-a.png"), picture(plan(0, "ARTSTYLE_MIDDLE_EAST", 5, true, early, List.of("PYRAMID"), 4)),
			picture(plan(1, "ARTSTYLE_GRECO_ROMAN", 8, true, full, List.of(), 10)));
		Preview.write(dir.resolve("styles-b.png"), picture(plan(2, "ARTSTYLE_ASIAN", 8, true, early, List.of("GREAT_LIBRARY", "STONEHENGE"), 14)),
			picture(plan(2, "ARTSTYLE_MIDDLE_EAST", 12, false, early, List.of("HANGING_GARDEN"), 5)));
		Preview.write(dir.resolve("wonders.png"), picture(plan(3, "ARTSTYLE_EUROPEAN", 20, true, List.of("MONUMENT", "MARKET", "WALLS"),
			List.of("THE_ORACLE", "COLOSSUS", "HAGIA_SOPHIA", "PORCELAIN_TOWER", "NOTRE_DAME", "MACHU_PICCHU"), 11)),
			picture(plan(4, "ARTSTYLE_EUROPEAN", 16, false, List.of("GRANARY", "FACTORY", "STOCK_EXCHANGE", "UNIVERSITY", "STADIUM", "HARBOR"), List.of("STATUE_OF_LIBERTY"), 3)));
		assertTrue(dir.resolve("era0.png").toFile().length() > 10_000);
	}

	private static BufferedImage picture(Structure landmark) {
		return Preview.draw((x, z, out) -> {
			Arrays.fill(out, Material.AIR.ordinal());
			for (int y = 0; y < BoardModel.LAND - BoardModel.BASE; y++) {
				out[y] = Material.DIRT.ordinal();
			}
			out[BoardModel.LAND - BoardModel.BASE] = Material.GRASS.ordinal();
			landmark.into(x, z, out, BoardModel.LAND);
		}, -11, -11, 11, 11, BoardModel.LAND);
	}

	/** The wonders as they stand on the land, each on its own hex: drawn to be looked at, and checked for what must hold for all. */
	@Test
	void theWondersOnTheLandAreDrawn() throws IOException {
		Path dir = Path.of("build", "preview");
		CityPlan.Kind[] kinds = { CityPlan.Kind.PYRAMID, CityPlan.Kind.STONE_RING, CityPlan.Kind.GREAT_LIBRARY, CityPlan.Kind.LIGHTHOUSE, CityPlan.Kind.STATUE, CityPlan.Kind.WONDER,
			CityPlan.Kind.ZIGGURAT, CityPlan.Kind.DOME };
		BufferedImage[] pictures = new BufferedImage[kinds.length];
		for (int i = 0; i < kinds.length; i++) {
			Structure landmark = Landmarks.of(kinds[i], 11, i % 2 == 1);
			pictures[i] = picture(landmark);
			String what = kinds[i].name();
			// A hex is 32 across: the landmark keeps well inside it, with room for those who come to look.
			assertTrue(landmark.rx <= 9 && landmark.rz <= 9, what + " fits its hex");
			int blocks = 0;
			int top = 0;
			boolean banner = false;
			for (int x = -landmark.rx; x <= landmark.rx; x++) {
				for (int z = -landmark.rz; z <= landmark.rz; z++) {
					for (int y = 0; y < landmark.height; y++) {
						int cell = landmark.at(x, y, z);
						if (cell != Structure.KEEP && Material.of(cell) != Material.AIR) {
							blocks++;
							top = Math.max(top, y);
							banner |= Material.of(cell) == Material.BANNER;
						}
					}
				}
			}
			assertTrue(top >= 6, what + " stands over the houses of the town: " + top);
			assertTrue(blocks > 150, what + " is many times the size of a house: " + blocks + " blocks");
			assertTrue(banner, what + " flies its owner's colour");
		}
		Preview.write(dir.resolve("landmarks-a.png"), pictures[0], pictures[1]);
		Preview.write(dir.resolve("landmarks-b.png"), pictures[2], pictures[3]);
		Preview.write(dir.resolve("landmarks-c.png"), pictures[4], pictures[5]);
		Preview.write(dir.resolve("landmarks-d.png"), pictures[6], pictures[7]);
	}

	@Test
	void everyHallHasItsFloorItsDoorAndTheRulersFurniture() {
		for (int tier = 0; tier < 6; tier++) {
			for (String art : new String[] { "ARTSTYLE_EUROPEAN", "ARTSTYLE_GRECO_ROMAN", "ARTSTYLE_MIDDLE_EAST", "ARTSTYLE_ASIAN", "ARTSTYLE_SOUTH_AMERICA", "ARTSTYLE_POLYNESIAN" }) {
				CityPlan plan = plan(tier, art, 9, true, List.of("MONUMENT", "MARKET", "WALLS"), List.of("PYRAMID"), 10);
				String what = "era " + tier + " " + art;
				int[] column = new int[SIZE];
				Arrays.fill(column, Material.AIR.ordinal());
				assertTrue(plan.column(0, 0, true, column), what);
				assertEquals(Material.PLANKS, Material.of(column[BoardModel.LAND - BoardModel.BASE]), what + ": a floor to stand on");
				assertEquals(Material.AIR, Material.of(column[BoardModel.LAND + 1 - BoardModel.BASE]), what + ": room for the governor");
				assertEquals(Material.AIR, Material.of(column[BoardModel.LAND + 2 - BoardModel.BASE]), what);
				// The way in from the south: two blocks of air in the middle of the south wall, and nothing in front of it.
				for (int dz = 1; dz <= 6; dz++) {
					Arrays.fill(column, Material.AIR.ordinal());
					plan.column(0, dz, true, column);
					for (int y = 1; y <= 2; y++) {
						Material m = Material.of(column[BoardModel.LAND + y - BoardModel.BASE]);
						assertTrue(m == Material.AIR, what + ": the way in is blocked at z+" + dz + " y+" + y + " by " + m);
					}
				}
				Arrays.fill(column, Material.AIR.ordinal());
				plan.column(2, -2, true, column);
				assertEquals(Material.BELL, Material.of(column[BoardModel.LAND + 1 - BoardModel.BASE]), what);
				Arrays.fill(column, Material.AIR.ordinal());
				plan.column(-2, -2, true, column);
				assertEquals(Material.LECTERN, Material.of(column[BoardModel.LAND + 1 - BoardModel.BASE]), what);
				// The owner's colour is on every hall, and a capital carries gold.
				boolean colour = false;
				boolean gold = false;
				for (int dx = -8; dx <= 8; dx++) {
					for (int dz = -5; dz <= 5; dz++) {
						Arrays.fill(column, Material.AIR.ordinal());
						plan.column(dx, dz, false, column);
						for (int cell : column) {
							colour |= (Material.of(cell) == Material.FLAG || Material.of(cell) == Material.ROOF) && Material.dye(cell) == 10;
							gold |= Material.of(cell) == Material.GOLD;
						}
					}
				}
				assertTrue(colour, what + ": the owner's colour");
				assertTrue(gold, what + ": gold for a capital");
			}
		}
	}

	@Test
	void aBuildingTurnedFourTimesIsItselfAgain() {
		Structure house = Builds.house(Builds.style(2, ""), 3, true);
		Structure turned = house.turned(1);
		assertNotEquals(house.at(0, 1, 2), turned.at(0, 1, 2), "the door moved from the south wall");
		assertEquals(Material.AIR.ordinal(), turned.at(-2, 1, 0), "to the west wall");
		Structure back = turned.turned(3);
		for (int x = -2; x <= 2; x++) {
			for (int z = -2; z <= 2; z++) {
				for (int y = 0; y < house.height; y++) {
					assertEquals(house.at(x, y, z), back.at(x, y, z), x + "," + y + "," + z);
				}
			}
		}
	}
}
