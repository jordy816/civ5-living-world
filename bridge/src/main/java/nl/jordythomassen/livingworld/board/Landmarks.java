package nl.jordythomassen.livingworld.board;

import static nl.jordythomassen.livingworld.board.Material.EAST;
import static nl.jordythomassen.livingworld.board.Material.NORTH;
import static nl.jordythomassen.livingworld.board.Material.SOUTH;
import static nl.jordythomassen.livingworld.board.Material.WEST;

/**
 * The wonders of the world as they stand on the land: each on a hex of its own beside its city,
 * many times the size of a house, so that from above one sees at a glance who built the Pyramids.
 * (In the city itself a wonder would be one more small building among eight.) Every shape stands
 * on a plinth one block thick (y 0) and flies its owner's colour.
 */
final class Landmarks {
	private static final int AIR = Material.AIR.ordinal();

	private Landmarks() {
	}

	/** The landmark for a kind of wonder, in the owner's colour; {@code dry} builds in sandstone where it would be stone. */
	static Structure of(CityPlan.Kind kind, int dye, boolean dry) {
		Structure built = switch (kind) {
			case PYRAMID -> pyramid();
			case STONE_RING -> stoneRing();
			case GREAT_LIBRARY -> greatLibrary();
			case LIGHTHOUSE -> lighthouse();
			case STATUE -> colossus();
			case DOME -> dome(dry);
			case ZIGGURAT -> terraces(dry);
			default -> tower();
		};
		return flagged(built, dye);
	}

	/** Two banners in the owner's colour, on posts before the front (the south side). */
	private static Structure flagged(Structure b, int dye) {
		for (int side = -1; side <= 1; side += 2) {
			int x = side * 3;
			int z = b.rz;
			b.set(x, 0, z, Material.STONE_BRICKS);
			b.box(x, 1, z, x, 3, z, Material.STONE_BRICK_WALL);
			b.set(x, 4, z, Material.cell(Material.WOOL, dye));
			b.set(x, 5, z, Material.cell(Material.BANNER, dye));
		}
		return b;
	}

	/** The Pyramids: seventeen across at the foot, smooth sides, a cap of gold, a dark door in the south face. */
	private static Structure pyramid() {
		Structure b = new Structure(9, 9, 13);
		b.box(-9, 0, -9, 9, 0, 9, Material.SANDSTONE);
		for (int course = 0; course <= 8; course++) {
			int ring = 8 - course;
			int y = 1 + course;
			b.box(-ring, y, -ring, ring, y, ring, Material.SMOOTH_SANDSTONE);
			if (ring == 0) {
				break;
			}
			for (int i = -ring; i <= ring; i++) {
				b.set(i, y, -ring, Material.cell(Material.STAIRS_SANDSTONE, 0, SOUTH)).set(i, y, ring, Material.cell(Material.STAIRS_SANDSTONE, 0, NORTH));
				b.set(-ring, y, i, Material.cell(Material.STAIRS_SANDSTONE, 0, EAST)).set(ring, y, i, Material.cell(Material.STAIRS_SANDSTONE, 0, WEST));
			}
		}
		b.set(0, 9, 0, Material.GOLD);
		b.set(0, 10, 0, Material.GOLD);
		// The door, framed.
		b.box(-1, 1, 7, 1, 3, 8, Material.CUT_SANDSTONE);
		b.box(0, 1, 7, 0, 2, 8, AIR);
		b.set(0, 1, 6, Material.LIGHT);
		return b;
	}

	/** Stonehenge: a ring of standing stones with lintels across, a horseshoe of taller ones inside, an altar in the middle. */
	private static Structure stoneRing() {
		Structure b = new Structure(8, 8, 8);
		for (int step = 0; step < 16; step++) {
			double angle = Math.toRadians(step * 22.5);
			int x = (int) Math.round(7 * Math.cos(angle));
			int z = (int) Math.round(7 * Math.sin(angle));
			Material stone = step % 3 == 0 ? Material.ANDESITE : Material.STONE;
			b.set(x, 0, z, Material.COARSE_DIRT);
			b.box(x, 1, z, x, 4, z, stone);
			// A lintel to the next stone; a few have fallen in three thousand years.
			if (step % 5 != 4) {
				double next = Math.toRadians((step + 1) * 22.5);
				int nx = (int) Math.round(7 * Math.cos(next));
				int nz = (int) Math.round(7 * Math.sin(next));
				for (int part = 0; part <= 4; part++) {
					int lx = (int) Math.round(x + (nx - x) * part / 4.0);
					int lz = (int) Math.round(z + (nz - z) * part / 4.0);
					b.set(lx, 5, lz, Material.cell(Material.SLAB_STONE_BRICK, 0, 0));
				}
			}
		}
		int[][] inner = { { -3, 2 }, { -4, -1 }, { -2, -4 }, { 2, -4 }, { 4, -1 }, { 3, 2 } };
		for (int i = 0; i < inner.length; i++) {
			int x = inner[i][0];
			int z = inner[i][1];
			b.set(x, 0, z, Material.COARSE_DIRT);
			b.box(x, 1, z, x, 5, z, i % 2 == 0 ? Material.STONE : Material.MOSSY_COBBLE);
			b.set(x, 6, z, Material.STONE);
			if (i % 2 == 0 && i + 1 < inner.length) {
				int ox = inner[i + 1][0];
				int oz = inner[i + 1][1];
				b.set((x + ox) / 2, 6, (z + oz) / 2, Material.STONE);
			}
		}
		b.box(-1, 0, -1, 1, 0, 1, Material.COARSE_DIRT);
		b.box(-1, 1, 0, 1, 1, 0, Material.MOSSY_COBBLE);
		b.set(0, 2, 0, Material.CAMPFIRE);
		return b;
	}

	/** The Great Library: a hall of books behind a colonnade, on a stepped podium, under a low roof. */
	private static Structure greatLibrary() {
		Structure b = new Structure(8, 6, 17);
		b.box(-8, 0, -6, 8, 0, 6, Material.STONE_BRICKS);
		b.box(-7, 1, -5, 7, 1, 5, Material.QUARTZ);
		for (int x = -8; x <= 8; x++) {
			b.set(x, 1, 6, Material.cell(Material.STAIRS_QUARTZ, 0, NORTH));
		}
		// The hall itself, walls of books between bands of stone.
		b.walls(-5, 2, -3, 5, 6, 3, Material.SMOOTH_SANDSTONE);
		b.walls(-5, 3, -3, 5, 4, 3, Material.BOOKSHELF);
		b.box(0, 2, 3, 0, 4, 3, AIR);
		b.set(0, 5, 3, Material.CHISELED_STONE);
		b.set(0, 2, 0, Material.CHISELED_STONE);
		b.set(-3, 4, 0, Material.LANTERN).set(3, 4, 0, Material.LANTERN);
		// The colonnade all round.
		for (int x = -7; x <= 7; x += 2) {
			b.box(x, 2, -5, x, 6, -5, Material.QUARTZ_PILLAR);
			b.box(x, 2, 5, x, 6, 5, Material.QUARTZ_PILLAR);
		}
		for (int z = -3; z <= 3; z += 2) {
			b.box(-7, 2, z, -7, 6, z, Material.QUARTZ_PILLAR);
			b.box(7, 2, z, 7, 6, z, Material.QUARTZ_PILLAR);
		}
		b.box(-7, 7, -5, 7, 7, 5, Material.QUARTZ);
		b.gable(-8, 8, -6, 6, 8, true, Material.STAIRS_BRICK, Material.BRICKS.ordinal(), Material.QUARTZ, 1);
		return b;
	}

	/** The Great Lighthouse: a square foot, an eight-sided shaft, a round lantern with a fire that never goes out. */
	private static Structure lighthouse() {
		Structure b = new Structure(5, 5, 30);
		b.box(-5, 0, -5, 5, 0, 5, Material.STONE_BRICKS);
		b.box(-4, 1, -4, 4, 6, 4, Material.STONE_BRICKS);
		b.box(-4, 7, -4, 4, 7, 4, Material.CHISELED_STONE);
		for (int corner = 0; corner < 4; corner++) {
			int x = corner < 2 ? -4 : 4;
			int z = corner % 2 == 0 ? -4 : 4;
			b.set(x, 8, z, Material.STONE_BRICK_WALL);
		}
		b.box(0, 1, 4, 0, 2, 4, AIR);
		for (int y = 8; y <= 19; y++) {
			for (int x = -2; x <= 2; x++) {
				for (int z = -2; z <= 2; z++) {
					if (Math.abs(x) + Math.abs(z) <= 3) {
						b.set(x, y, z, y % 4 == 0 ? Material.QUARTZ : Material.PLASTER);
					}
				}
			}
		}
		b.box(-3, 20, -3, 3, 20, 3, Material.STONE_BRICKS);
		for (int x = -3; x <= 3; x++) {
			for (int z = -3; z <= 3; z++) {
				if (Math.abs(x) == 3 || Math.abs(z) == 3) {
					b.set(x, 21, z, Material.STONE_BRICK_WALL);
				}
			}
		}
		b.box(-1, 21, -1, 1, 24, 1, Material.GLASS);
		b.box(0, 21, 0, 0, 24, 0, Material.LIGHT);
		b.box(-1, 25, -1, 1, 25, 1, Material.COPPER);
		b.set(0, 26, 0, Material.GOLD);
		return b;
	}

	/** A colossus: a figure of copper twenty blocks tall on a plinth, one arm raised with a light in its hand. */
	private static Structure colossus() {
		Structure b = new Structure(5, 5, 28);
		b.box(-5, 0, -5, 5, 0, 5, Material.STONE_BRICKS);
		b.box(-4, 1, -4, 4, 2, 4, Material.STONE_BRICKS);
		b.box(-3, 3, -3, 3, 3, 3, Material.CHISELED_STONE);
		// Legs, a little apart.
		b.box(-2, 4, -1, -1, 10, 0, Material.COPPER);
		b.box(1, 4, -1, 2, 10, 0, Material.COPPER);
		// Hips, body, shoulders.
		b.box(-2, 11, -1, 2, 12, 0, Material.COPPER);
		b.box(-2, 13, -1, 2, 17, 0, Material.COPPER);
		b.box(-3, 17, -1, 3, 18, 0, Material.COPPER);
		// One arm down, one up with the light.
		b.box(-3, 12, -1, -3, 16, 0, Material.COPPER);
		b.box(3, 19, -1, 3, 23, 0, Material.COPPER);
		b.set(3, 24, 0, Material.GOLD);
		b.set(3, 25, 0, Material.LIGHT);
		// Head, and a crown of rays.
		b.box(-1, 19, -1, 1, 21, 0, Material.COPPER);
		b.box(-1, 22, -1, 1, 22, 0, Material.GOLD);
		b.set(0, 23, 0, Material.GOLD);
		return b;
	}

	/** A great tower: a hall at its foot, a square shaft with a face of white on every side, a pointed roof. */
	private static Structure tower() {
		Structure b = new Structure(5, 5, 32);
		b.box(-5, 0, -5, 5, 0, 5, Material.STONE_BRICKS);
		b.walls(-4, 1, -4, 4, 5, 4, Material.BRICKS);
		b.box(-4, 6, -4, 4, 6, 4, Material.STONE_BRICKS);
		b.box(0, 1, 4, 0, 2, 4, AIR);
		for (int x = -2; x <= 2; x += 4) {
			b.set(x, 3, 4, Material.GLASS_PANE).set(x, 3, -4, Material.GLASS_PANE);
			b.set(4, 3, x, Material.GLASS_PANE).set(-4, 3, x, Material.GLASS_PANE);
		}
		b.walls(-2, 7, -2, 2, 21, 2, Material.BRICKS);
		for (int y = 9; y <= 15; y += 3) {
			b.set(0, y, 2, Material.GLASS_PANE).set(0, y, -2, Material.GLASS_PANE).set(2, y, 0, Material.GLASS_PANE).set(-2, y, 0, Material.GLASS_PANE);
		}
		// The faces, and the light behind them.
		b.box(-1, 18, 2, 1, 20, 2, Material.QUARTZ).box(-1, 18, -2, 1, 20, -2, Material.QUARTZ);
		b.box(2, 18, -1, 2, 20, 1, Material.QUARTZ).box(-2, 18, -1, -2, 20, 1, Material.QUARTZ);
		b.set(0, 19, 0, Material.LIGHT);
		b.box(-3, 22, -3, 3, 22, 3, Material.STONE_BRICKS);
		for (int course = 0; course <= 2; course++) {
			int ring = 2 - course;
			int y = 23 + course * 2;
			b.box(-ring, y, -ring, ring, y + 1, ring, Material.DEEPSLATE_TILES);
		}
		b.set(0, 29, 0, Material.GOLD);
		return b;
	}

	/** A temple of terraces: four steps with a stair up the front, green hanging from every edge, a shrine of gold on top. */
	private static Structure terraces(boolean dry) {
		Structure b = new Structure(9, 9, 18);
		Material stone = dry ? Material.CUT_SANDSTONE : Material.STONE_BRICKS;
		Material steps = dry ? Material.STAIRS_SANDSTONE : Material.STAIRS_STONE_BRICK;
		b.box(-9, 0, -9, 9, 0, 9, dry ? Material.SANDSTONE : Material.STONE_BRICKS);
		for (int terrace = 0; terrace < 4; terrace++) {
			int ring = 8 - terrace * 2;
			int y = 1 + terrace * 3;
			b.box(-ring, y, -ring, ring, y + 2, ring, stone);
			// Gardens along the edge of each step.
			for (int i = -ring; i <= ring; i += 2) {
				b.set(i, y + 3, -ring, Material.LEAVES_OAK).set(-ring, y + 3, i, Material.LEAVES_OAK).set(ring, y + 3, i, Material.LEAVES_OAK);
				if (Math.abs(i) > 1) {
					b.set(i, y + 3, ring, Material.LEAVES_OAK);
				}
			}
			for (int corner = 0; corner < 4; corner++) {
				int x = corner < 2 ? -ring : ring;
				int z = corner % 2 == 0 ? -ring : ring;
				b.set(x, y + 4, z, Material.LEAVES_JUNGLE);
			}
			// The stair up the front, three blocks to a terrace.
			for (int step = 0; step < 3; step++) {
				int z = ring + 1 - step;
				for (int x = -1; x <= 1; x++) {
					b.box(x, y, z, x, y + step, z, stone);
					b.set(x, y + step, z, Material.cell(steps, 0, NORTH));
				}
			}
		}
		b.box(-1, 13, -1, 1, 13, 1, stone);
		for (int x = -1; x <= 1; x += 2) {
			for (int z = -1; z <= 1; z += 2) {
				b.box(x, 14, z, x, 15, z, Material.QUARTZ_PILLAR);
			}
		}
		b.box(-1, 16, -1, 1, 16, 1, Material.GOLD);
		b.set(0, 14, 0, Material.LIGHT);
		return b;
	}

	/** A domed hall: thick walls, a great round dome of copper, a slender tower on each corner. */
	private static Structure dome(boolean dry) {
		Structure b = new Structure(8, 8, 22);
		Material wall = dry ? Material.SMOOTH_SANDSTONE : Material.QUARTZ;
		b.box(-8, 0, -8, 8, 0, 8, Material.STONE_BRICKS);
		b.walls(-6, 1, -6, 6, 7, 6, wall);
		b.box(-6, 8, -6, 6, 8, 6, wall);
		b.box(-1, 1, 6, 1, 4, 6, AIR);
		b.box(-1, 5, 6, 1, 5, 6, Material.CHISELED_STONE);
		for (int i = -4; i <= 4; i += 4) {
			b.box(i, 3, -6, i, 5, -6, Material.TINTED_GLASS);
			b.box(-6, 3, i, -6, 5, i, Material.TINTED_GLASS).box(6, 3, i, 6, 5, i, Material.TINTED_GLASS);
		}
		b.set(0, 1, 0, Material.LIGHT);
		// The dome: half a ball, five blocks in radius.
		for (int x = -5; x <= 5; x++) {
			for (int z = -5; z <= 5; z++) {
				for (int y = 0; y <= 5; y++) {
					double far = Math.sqrt(x * x + z * z + y * y);
					if (far <= 5.4 && far > 4.2) {
						b.set(x, 9 + y, z, Material.COPPER);
					}
				}
			}
		}
		b.set(0, 15, 0, Material.GOLD);
		b.set(0, 16, 0, Material.GOLD);
		// The four towers.
		for (int corner = 0; corner < 4; corner++) {
			int x = corner < 2 ? -7 : 7;
			int z = corner % 2 == 0 ? -7 : 7;
			b.box(x, 1, z, x, 13, z, wall);
			b.set(x, 14, z, Material.STONE_BRICK_WALL);
			b.set(x, 15, z, Material.COPPER);
			b.set(x, 16, z, Material.GOLD);
		}
		return b;
	}
}
