package nl.jordythomassen.livingworld.board;

import static nl.jordythomassen.livingworld.board.Material.AIR;
import static nl.jordythomassen.livingworld.board.Material.EAST;
import static nl.jordythomassen.livingworld.board.Material.NORTH;
import static nl.jordythomassen.livingworld.board.Material.SOUTH;
import static nl.jordythomassen.livingworld.board.Material.UPPER;
import static nl.jordythomassen.livingworld.board.Material.WEST;

/**
 * The buildings of a city, each built block by block the way a player would: posts and beams,
 * walls with a base course, windows, a pitched roof of stairs with a ridge. What they are made
 * of follows the owner's era and building style; their shape follows what they are.
 *
 * <p>All are designed with the front to the south and are turned to face the street.
 */
final class Builds {
	enum Roof { THATCH, TILED, FLAT }

	/** What a people builds with in one era. */
	record Style(int tier, Material post, Material base, Material wall, Material upper, Material roof, Material roofOther, Roof roofKind, Material floor,
		Material trim, Material pillar, Material fence, int wallHeight) {
	}

	private Builds() {
	}

	static Style style(int tier, String artStyle) {
		String art = artStyle == null ? "" : artStyle;
		boolean classical = art.contains("GRECO");
		boolean desert = art.contains("MIDDLE_EAST");
		boolean eastern = art.contains("ASIAN");
		boolean jungle = art.contains("SOUTH_AMERICA");
		boolean island = art.contains("POLYNESIAN");
		switch (tier) {
			case 0:
				if (classical || desert) {
					Material stone = desert ? Material.SANDSTONE : Material.SMOOTH_SANDSTONE;
					return new Style(0, Material.CUT_SANDSTONE, stone, stone, stone, Material.SMOOTH_SANDSTONE, Material.SMOOTH_SANDSTONE, Roof.FLAT, Material.SMOOTH_SANDSTONE,
						Material.SLAB_SANDSTONE, Material.CUT_SANDSTONE, Material.FENCE, 3);
				}
				if (eastern) {
					return new Style(0, Material.LOG_SPRUCE, Material.SPRUCE_PLANKS, Material.SPRUCE_PLANKS, Material.SPRUCE_PLANKS, Material.HAY, Material.HAY, Roof.THATCH,
						Material.SPRUCE_PLANKS, Material.SLAB_SPRUCE, Material.LOG_SPRUCE, Material.SPRUCE_FENCE, 2);
				}
				if (jungle || island) {
					return new Style(0, Material.LOG_JUNGLE, jungle ? Material.MOSSY_COBBLE : Material.PLANKS, jungle ? Material.MOSSY_COBBLE : Material.PLANKS, Material.PLANKS,
						Material.HAY, Material.HAY, Roof.THATCH, Material.PLANKS, Material.SLAB_OAK, Material.LOG_JUNGLE, Material.FENCE, 2);
				}
				return new Style(0, Material.LOG_OAK, Material.MUD_BRICKS, Material.MUD_BRICKS, Material.MUD_BRICKS, Material.HAY, Material.HAY, Roof.THATCH, Material.PLANKS,
					Material.SLAB_MUD_BRICK, Material.LOG_OAK, Material.FENCE, 2);
			case 1:
				if (classical) {
					return new Style(1, Material.QUARTZ_PILLAR, Material.SMOOTH_SANDSTONE, Material.PLASTER, Material.PLASTER, Material.STAIRS_BRICK, Material.STAIRS_BRICK,
						Roof.TILED, Material.SMOOTH_SANDSTONE, Material.SLAB_QUARTZ, Material.QUARTZ_PILLAR, Material.FENCE, 3);
				}
				if (desert) {
					return new Style(1, Material.CUT_SANDSTONE, Material.SANDSTONE, Material.SMOOTH_SANDSTONE, Material.SMOOTH_SANDSTONE, Material.SMOOTH_SANDSTONE,
						Material.SMOOTH_SANDSTONE, Roof.FLAT, Material.SMOOTH_SANDSTONE, Material.SLAB_SANDSTONE, Material.CUT_SANDSTONE, Material.FENCE, 3);
				}
				if (eastern) {
					return new Style(1, Material.LOG_DARK_OAK, Material.COBBLE, Material.SPRUCE_PLANKS, Material.SPRUCE_PLANKS, Material.STAIRS_DARK_OAK, Material.STAIRS_SPRUCE,
						Roof.TILED, Material.SPRUCE_PLANKS, Material.SLAB_DARK_OAK, Material.LOG_DARK_OAK, Material.SPRUCE_FENCE, 3);
				}
				if (jungle) {
					return new Style(1, Material.LOG_JUNGLE, Material.MOSSY_COBBLE, Material.COBBLE, Material.COBBLE, Material.HAY, Material.HAY, Roof.THATCH, Material.COBBLE,
						Material.SLAB_COBBLE, Material.CHISELED_STONE, Material.FENCE, 3);
				}
				return new Style(1, Material.LOG_OAK, Material.COBBLE, Material.PLANKS, Material.PLANKS, Material.STAIRS_SPRUCE, Material.STAIRS_OAK, Roof.TILED,
					Material.PLANKS, Material.SLAB_COBBLE, Material.STRIPPED_LOG, Material.FENCE, 3);
			case 2:
				if (desert) {
					return new Style(2, Material.CUT_SANDSTONE, Material.SANDSTONE, Material.SMOOTH_SANDSTONE, Material.SMOOTH_SANDSTONE, Material.SMOOTH_SANDSTONE,
						Material.SMOOTH_SANDSTONE, Roof.FLAT, Material.SMOOTH_SANDSTONE, Material.SLAB_SANDSTONE, Material.CUT_SANDSTONE, Material.FENCE, 3);
				}
				if (classical) {
					return new Style(2, Material.STONE_BRICKS, Material.STONE_BRICKS, Material.PLASTER, Material.PLASTER, Material.STAIRS_BRICK, Material.STAIRS_BRICK, Roof.TILED,
						Material.PLANKS, Material.SLAB_STONE_BRICK, Material.QUARTZ_PILLAR, Material.FENCE, 3);
				}
				if (eastern) {
					return new Style(2, Material.LOG_DARK_OAK, Material.STONE_BRICKS, Material.PLASTER, Material.PLASTER, Material.STAIRS_DEEPSLATE, Material.STAIRS_DARK_OAK,
						Roof.TILED, Material.DARK_PLANKS, Material.SLAB_DARK_OAK, Material.LOG_DARK_OAK, Material.DARK_FENCE, 3);
				}
				// Half-timbered: a stone foot, white plaster between dark beams.
				return new Style(2, Material.LOG_DARK_OAK, Material.COBBLE, Material.PLASTER, Material.PLASTER, Material.STAIRS_DARK_OAK, Material.STAIRS_SPRUCE, Roof.TILED,
					Material.PLANKS, Material.SLAB_STONE_BRICK, Material.LOG_DARK_OAK, Material.DARK_FENCE, 3);
			case 3:
				if (desert) {
					return new Style(3, Material.CUT_SANDSTONE, Material.SANDSTONE, Material.SMOOTH_SANDSTONE, Material.PLASTER, Material.STAIRS_SANDSTONE, Material.STAIRS_SANDSTONE,
						Roof.TILED, Material.SMOOTH_SANDSTONE, Material.SLAB_SANDSTONE, Material.CUT_SANDSTONE, Material.FENCE, 4);
				}
				if (classical) {
					return new Style(3, Material.STONE_BRICKS, Material.STONE_BRICKS, Material.PLASTER, Material.PLASTER, Material.STAIRS_BRICK, Material.STAIRS_BRICK, Roof.TILED,
						Material.PLANKS, Material.SLAB_STONE_BRICK, Material.QUARTZ_PILLAR, Material.FENCE, 4);
				}
				return new Style(3, Material.STONE_BRICKS, Material.STONE_BRICKS, Material.BRICKS, Material.BRICKS, Material.STAIRS_DEEPSLATE, Material.STAIRS_DARK_OAK, Roof.TILED,
					Material.PLANKS, Material.SLAB_STONE_BRICK, Material.STONE_BRICKS, Material.DARK_FENCE, 4);
			case 4:
				return new Style(4, Material.DEEPSLATE_BRICKS, Material.DEEPSLATE_BRICKS, Material.BRICKS, Material.BRICKS, Material.STAIRS_DEEPSLATE, Material.STAIRS_DEEPSLATE,
					Roof.TILED, Material.PLANKS, Material.SLAB_STONE_BRICK, Material.POLISHED_ANDESITE, Material.IRON_BARS, 4);
			default:
				return new Style(5, Material.WHITE_CONCRETE, Material.GRAY_CONCRETE, Material.WHITE_CONCRETE, Material.WHITE_CONCRETE, Material.GRAY_CONCRETE,
					Material.GRAY_CONCRETE, Roof.FLAT, Material.POLISHED_ANDESITE, Material.SLAB_STONE_BRICK, Material.WHITE_CONCRETE, Material.IRON_BARS, 5);
		}
	}

	// ---- parts ----

	/** Walls of a rectangular building from y 1 to h: posts on the corners, a base course, then the wall. */
	private static void shell(Structure b, Style s, int rx, int rz, int h) {
		for (int y = 1; y <= h; y++) {
			Material wall = y == 1 ? s.base : y > s.wallHeight ? s.upper : s.wall;
			b.walls(-rx, y, -rz, rx, y, rz, wall);
			for (int cx = -rx; cx <= rx; cx += 2 * rx) {
				for (int cz = -rz; cz <= rz; cz += 2 * rz) {
					b.set(cx, y, cz, s.post);
				}
			}
		}
	}

	private static void roof(Structure b, Style s, int rx, int rz, int y, boolean alongX, boolean other, int over) {
		Material roof = other ? s.roofOther : s.roof;
		switch (s.roofKind) {
			case THATCH -> b.gable(-rx - (alongX ? over : 0), rx + (alongX ? over : 0), -rz - (alongX ? 0 : over), rz + (alongX ? 0 : over), y, alongX, roof,
				roof.ordinal(), s.upper, over);
			case TILED -> b.gable(-rx - (alongX ? over : 0), rx + (alongX ? over : 0), -rz - (alongX ? 0 : over), rz + (alongX ? 0 : over), y, alongX, roof,
				roof.asSlab().ordinal(), s.upper, over);
			default -> {
				b.box(-rx, y, -rz, rx, y, rz, roof);
				// A low rim around a flat roof.
				for (int x = -rx; x <= rx; x++) {
					for (int z = -rz; z <= rz; z++) {
						if (Math.abs(x) == rx || Math.abs(z) == rz) {
							b.set(x, y + 1, z, s.trim);
						}
					}
				}
			}
		}
	}

	private static void window(Structure b, Style s, int x, int y, int z) {
		// The oldest houses have openings, not glass.
		b.set(x, y, z, s.tier == 0 ? AIR : Material.GLASS_PANE);
	}

	private static void flag(Structure b, int x, int y, int z, int height, int dye, boolean capital) {
		b.box(x, y, z, x, y + height, z, Material.FENCE);
		b.box(x + 1, y + height - 1, z, x + 3, y + height, z, Material.cell(Material.FLAG, dye));
		if (capital) {
			// The capital shows its rank in gold.
			b.set(x, y + height + 1, z, Material.GOLD);
		}
	}

	// ---- houses ----

	/** A house on a lot of five by five. {@code tall} gives it an upper floor; {@code variant} turns its roof and adds a chimney. */
	static Structure house(Style s, int variant, boolean tall) {
		if (s.tier >= 5) {
			return block(s, variant, tall);
		}
		int h = s.wallHeight + (tall ? 2 : 0);
		Structure b = new Structure(2, 2, h + 6);
		b.box(-2, 0, -2, 2, 0, 2, s.floor);
		shell(b, s, 2, 2, h);
		if (tall && s.post.name().startsWith("LOG")) {
			// The beam that carries the upper floor.
			b.box(-1, s.wallHeight + 1, -2, 1, s.wallHeight + 1, -2, Material.cell(s.post, 0, Material.ALONG_X));
			b.box(-1, s.wallHeight + 1, 2, 1, s.wallHeight + 1, 2, Material.cell(s.post, 0, Material.ALONG_X));
			b.box(-2, s.wallHeight + 1, -1, -2, s.wallHeight + 1, 1, Material.cell(s.post, 0, Material.ALONG_Z));
			b.box(2, s.wallHeight + 1, -1, 2, s.wallHeight + 1, 1, Material.cell(s.post, 0, Material.ALONG_Z));
		}
		b.box(0, 1, 2, 0, 2, 2, AIR);
		if (s.wallHeight >= 3) {
			window(b, s, -2, 2, 0);
			window(b, s, 2, 2, 0);
			window(b, s, 0, 2, -2);
		}
		if (tall) {
			window(b, s, -2, h - 1, 0);
			window(b, s, 2, h - 1, 0);
			window(b, s, 0, h - 1, 2);
			window(b, s, 0, h - 1, -2);
		}
		boolean alongX = (variant & 1) == 0;
		roof(b, s, 2, 2, h + 1, alongX, (variant & 4) != 0, 0);
		if (s.roofKind == Roof.TILED && (variant & 2) != 0) {
			// A chimney through the slope of the roof, with the fire of the house under it.
			Material stack = s.tier >= 3 ? Material.BRICKS : Material.COBBLE;
			b.box(1, 1, -1, 1, h + 3, -1, stack);
			b.set(1, h + 4, -1, Material.CAMPFIRE);
		}
		return b;
	}

	/** A modern block of flats: concrete and bands of glass under a flat roof. */
	private static Structure block(Style s, int variant, boolean tall) {
		int h = tall ? 11 : 6 + (variant & 1) * 2;
		Structure b = new Structure(2, 2, h + 4);
		b.box(-2, 0, -2, 2, 0, 2, s.floor);
		for (int y = 1; y <= h; y++) {
			b.walls(-2, y, -2, 2, y, 2, y % 3 == 1 ? s.wall : Material.TINTED_GLASS);
			for (int cx = -2; cx <= 2; cx += 4) {
				for (int cz = -2; cz <= 2; cz += 4) {
					b.set(cx, y, cz, s.post);
				}
			}
		}
		b.box(0, 1, 2, 0, 2, 2, AIR);
		b.box(-2, h + 1, -2, 2, h + 1, 2, s.roof);
		b.set(-1, h + 2, -1, Material.IRON_BARS);
		b.set(1, h + 2, 1, s.base);
		return b;
	}

	// ---- the hall ----

	/** The hall of a city, nine by nine, in the manner of its era. The bell and the lectern stand inside when it is the player's own. */
	static Structure hall(Style s, int half, int dye, boolean capital, boolean own, int furniture) {
		Structure b;
		if (half < 4) {
			b = smallHall(s, half, dye, capital);
		} else {
			b = switch (s.tier) {
				case 0 -> s.roofKind == Roof.FLAT ? palace(s, dye, capital) : longhouse(s, dye, capital);
				case 1 -> s.roofKind == Roof.FLAT ? palace(s, dye, capital) : temple(s, dye, capital);
				case 2 -> s.roofKind == Roof.FLAT ? palace(s, dye, capital) : keep(s, dye, capital);
				case 3 -> townHall(s, dye, capital, false);
				case 4 -> townHall(s, dye, capital, true);
				default -> tower(s, dye, capital);
			};
		}
		if (own) {
			// Behind the governor, as seen from the south door: the bell to his right, the lectern to his left.
			b.set(furniture, 1, -furniture, Material.BELL);
			b.set(-furniture, 1, -furniture, Material.LECTERN);
		}
		return b;
	}

	/** For narrow hexes: a hall that is one large house. */
	private static Structure smallHall(Style s, int r, int dye, boolean capital) {
		int h = s.wallHeight + 1;
		Structure b = new Structure(r + 4, r + 1, h + r + 12);
		b.box(-r, 0, -r, r, 0, r, Material.PLANKS);
		shell(b, s, r, r, h);
		b.box(0, 1, r, 0, 2, r, AIR);
		if (s.roofKind == Roof.FLAT) {
			b.box(-r, h + 1, -r, r, h + 1, r, Material.cell(Material.ROOF, dye));
		} else {
			roof(b, s, r, r, h + 1, true, false, 0);
			b.box(-r, h + 1 + r, 0, r, h + 1 + r, 0, Material.cell(Material.ROOF, dye));
		}
		flag(b, r, h + 2 + (s.roofKind == Roof.FLAT ? 0 : r), 0, 5, dye, capital);
		return b;
	}

	/** Ancient: a long timber hall under a great thatched roof. */
	private static Structure longhouse(Style s, int dye, boolean capital) {
		Structure b = new Structure(8, 5, 20);
		b.box(-4, 0, -4, 4, 0, 4, Material.PLANKS);
		int h = 3;
		for (int y = 1; y <= h; y++) {
			b.walls(-4, y, -4, 4, y, 4, s.wall);
		}
		// The timber frame: posts on the corners and along the long sides, a beam on top.
		for (int x = -4; x <= 4; x += 2) {
			b.box(x, 1, -4, x, h, -4, s.post);
			b.box(x, 1, 4, x, h, 4, s.post);
		}
		b.box(-4, 1, 0, -4, h, 0, s.post);
		b.box(4, 1, 0, 4, h, 0, s.post);
		b.box(0, 1, 4, 0, 2, 4, AIR);
		b.set(0, 3, 4, Material.cell(s.post, 0, Material.ALONG_X));
		// Smoke holes low under the eaves.
		b.set(-3, 2, 4, AIR).set(3, 2, 4, AIR).set(-3, 2, -4, AIR).set(3, 2, -4, AIR);
		// The roof comes down low over the long sides and stands a block proud of the gable ends.
		b.gable(-5, 5, -5, 5, h, true, Material.HAY, Material.cell(Material.ROOF, dye), s.wall, 1);
		// The ridge beam shows at both ends, crossed like horns.
		b.set(-5, h + 6, 0, s.post).set(5, h + 6, 0, s.post);
		// A hearth in the middle of the floor would be in the governor's way; it burns at the back.
		b.set(0, 1, -3, Material.CAMPFIRE);
		flag(b, 4, h + 6, 0, 5, dye, capital);
		return b;
	}

	/** Dry lands: a walled house of sandstone around its hall, flat roofs at two heights, a tower on one corner. */
	private static Structure palace(Style s, int dye, boolean capital) {
		Structure b = new Structure(8, 5, 22);
		b.box(-4, 0, -4, 4, 0, 4, Material.PLANKS);
		int h = 4 + Math.min(2, s.tier);
		for (int y = 1; y <= h; y++) {
			b.walls(-4, y, -4, 4, y, 4, y == 1 ? s.base : s.wall);
		}
		for (int cx = -4; cx <= 4; cx += 8) {
			for (int cz = -4; cz <= 4; cz += 8) {
				b.box(cx, 1, cz, cx, h + 1, cz, s.post);
			}
		}
		// A deep doorway between two pillars, and narrow windows high up.
		b.box(0, 1, 4, 0, 3, 4, AIR);
		b.box(-1, 1, 5, -1, 3, 5, s.pillar).box(1, 1, 5, 1, 3, 5, s.pillar);
		b.box(-1, 4, 5, 1, 4, 5, s.trim);
		for (int x = -2; x <= 2; x += 4) {
			b.set(x, h - 1, 4, AIR).set(x, h - 1, -4, AIR);
			b.set(-4, h - 1, x, AIR).set(4, h - 1, x, AIR);
		}
		b.box(-4, h + 1, -4, 4, h + 1, 4, s.wall);
		b.box(-3, h + 1, -3, 3, h + 1, 3, Material.cell(Material.ROOF, dye));
		// The rim of the roof, stepped like battlements.
		for (int i = -4; i <= 4; i += 2) {
			b.set(i, h + 2, -4, s.post).set(i, h + 2, 4, s.post).set(-4, h + 2, i, s.post).set(4, h + 2, i, s.post);
		}
		// A tower over the north-east corner.
		b.box(2, h + 2, -4, 4, h + 5, -2, s.wall);
		b.box(3, h + 2, -3, 3, h + 5, -3, AIR);
		b.set(3, h + 4, -2, AIR).set(2, h + 4, -3, AIR);
		b.box(2, h + 6, -4, 4, h + 6, -2, s.trim);
		b.set(3, h + 6, -3, s.wall);
		flag(b, 3, h + 7, -3, 4, dye, capital);
		return b;
	}

	/** Classical: columns all round, a low tiled roof with its gable to the front. */
	private static Structure temple(Style s, int dye, boolean capital) {
		Structure b = new Structure(8, 5, 20);
		b.box(-5, 0, -5, 5, 0, 5, s.floor);
		b.box(-4, 0, -4, 4, 0, 4, Material.PLANKS);
		int h = 4;
		// Columns on every second place around, none in the doorway.
		for (int i = -4; i <= 4; i += 2) {
			b.box(i, 1, -4, i, h, -4, s.pillar);
			b.box(-4, 1, i, -4, h, i, s.pillar);
			b.box(4, 1, i, 4, h, i, s.pillar);
			if (i != 0) {
				b.box(i, 1, 4, i, h, 4, s.pillar);
			}
		}
		// The room behind the columns: closed at the back, open to the front.
		b.box(-3, 1, -3, 3, h, -3, s.wall);
		b.box(-3, 1, -3, -3, h, 0, s.wall);
		b.box(3, 1, -3, 3, h, 0, s.wall);
		// The beam on the columns, then the roof in half steps: low, as these roofs are.
		b.walls(-4, h + 1, -4, 4, h + 1, 4, s.base);
		Material tile = s.roof.asSlab();
		for (int z = -5; z <= 5; z++) {
			for (int i = 0; i <= 4; i++) {
				int y = h + 2 + i / 2;
				int cell = Material.cell(tile, 0, i % 2 == 1 ? UPPER : 0);
				b.set(-5 + i, y, z, cell);
				b.set(5 - i, y, z, cell);
			}
			b.set(0, h + 4, z, Material.cell(Material.ROOF, dye));
		}
		// The gable fields, front and back.
		for (int x = -3; x <= 3; x++) {
			b.set(x, h + 2, 4, s.wall).set(x, h + 2, -4, s.wall);
		}
		b.set(-1, h + 3, 4, s.wall).set(0, h + 3, 4, Material.GOLD).set(1, h + 3, 4, s.wall);
		b.box(-1, h + 3, -4, 1, h + 3, -4, s.wall);
		// Steps up to the front.
		for (int x = -1; x <= 1; x++) {
			b.set(x, 0, 5, Material.cell(s.roof.stairs() ? Material.STAIRS_SANDSTONE : Material.STAIRS_COBBLE, 0, NORTH));
		}
		flag(b, 4, h + 2, -4, 6, dye, capital);
		return b;
	}

	/** Medieval: a stone keep with battlements, a turret on each corner and the lord's colours on the roof. */
	private static Structure keep(Style s, int dye, boolean capital) {
		Structure b = new Structure(8, 5, 24);
		b.box(-4, 0, -4, 4, 0, 4, Material.PLANKS);
		int h = 6;
		for (int y = 1; y <= h; y++) {
			b.walls(-4, y, -4, 4, y, 4, y == 1 ? Material.COBBLE : Material.STONE_BRICKS);
		}
		// The gate, with a carved stone over it.
		b.box(0, 1, 4, 0, 2, 4, AIR);
		b.set(0, 3, 4, Material.CHISELED_STONE);
		b.set(-1, 3, 4, Material.cell(Material.STAIRS_STONE_BRICK, 0, WEST | Material.UPSIDE_DOWN));
		b.set(1, 3, 4, Material.cell(Material.STAIRS_STONE_BRICK, 0, EAST | Material.UPSIDE_DOWN));
		// Narrow windows with bars.
		for (int i = -2; i <= 2; i += 4) {
			b.box(i, 3, -4, i, 4, -4, Material.IRON_BARS);
			b.box(-4, 3, i, -4, 4, i, Material.IRON_BARS);
			b.box(4, 3, i, 4, 4, i, Material.IRON_BARS);
			b.box(i, 4, 4, i, 5, 4, Material.IRON_BARS);
		}
		// The roof walk in the lord's colours, and battlements around it.
		b.box(-4, h + 1, -4, 4, h + 1, 4, Material.STONE_BRICKS);
		b.box(-3, h + 1, -3, 3, h + 1, 3, Material.cell(Material.ROOF, dye));
		for (int i = -3; i <= 3; i++) {
			if ((i & 1) != 0) {
				b.set(i, h + 2, -4, Material.STONE_BRICKS).set(i, h + 2, 4, Material.STONE_BRICKS);
				b.set(-4, h + 2, i, Material.STONE_BRICKS).set(4, h + 2, i, Material.STONE_BRICKS);
			} else {
				int low = Material.cell(Material.SLAB_STONE_BRICK, 0, 0);
				b.set(i, h + 2, -4, low).set(i, h + 2, 4, low).set(-4, h + 2, i, low).set(4, h + 2, i, low);
			}
		}
		// Four turrets, standing out from the corners.
		for (int cx = -4; cx <= 4; cx += 8) {
			for (int cz = -4; cz <= 4; cz += 8) {
				int ox = cx > 0 ? 1 : -1;
				int oz = cz > 0 ? 1 : -1;
				b.box(cx, 1, cz, cx + ox, h + 3, cz + oz, Material.STONE_BRICKS);
				b.box(cx, 1, cz, cx + ox, 1, cz + oz, Material.COBBLE);
				b.set(cx, h + 4, cz, Material.STONE_BRICKS).set(cx + ox, h + 4, cz + oz, Material.STONE_BRICKS);
				b.set(cx + ox, h + 4, cz, Material.cell(Material.SLAB_STONE_BRICK, 0, 0)).set(cx, h + 4, cz + oz, Material.cell(Material.SLAB_STONE_BRICK, 0, 0));
				b.set(cx + ox, h - 1, cz + oz, Material.IRON_BARS);
			}
		}
		// Banners at the gate.
		b.set(-2, 1, 5, Material.cell(Material.BANNER, dye, 0)).set(2, 1, 5, Material.cell(Material.BANNER, dye, 0));
		// Light inside.
		b.set(-2, h, 0, Material.cell(Material.LANTERN, 0, Material.HANGING)).set(2, h, 0, Material.cell(Material.LANTERN, 0, Material.HANGING));
		flag(b, 4, h + 5, -4, 5, dye, capital);
		return b;
	}

	/** Renaissance and industrial: a town hall of brick, two floors of tall windows, a steep slate roof and a clock tower. */
	private static Structure townHall(Style s, int dye, boolean capital, boolean industrial) {
		Structure b = new Structure(8, 5, 28);
		b.box(-4, 0, -4, 4, 0, 4, Material.PLANKS);
		int h = 7;
		for (int y = 1; y <= h; y++) {
			Material wall = y == 1 || (industrial && y == 2) ? s.base : y == 4 ? s.trim.asBlock() : s.wall;
			b.walls(-4, y, -4, 4, y, 4, wall);
		}
		for (int cx = -4; cx <= 4; cx += 8) {
			for (int cz = -4; cz <= 4; cz += 8) {
				b.box(cx, 1, cz, cx, h, cz, s.post);
			}
		}
		// Two rows of windows on every side.
		for (int i = -2; i <= 2; i += 2) {
			for (int y = 2; y <= 6; y++) {
				if (y == 4) {
					continue;
				}
				b.set(-4, y, i, Material.GLASS_PANE).set(4, y, i, Material.GLASS_PANE).set(i, y, -4, Material.GLASS_PANE);
				if (i != 0 || y > 4) {
					b.set(i, y, 4, Material.GLASS_PANE);
				}
			}
		}
		// The door, under a stone arch.
		b.box(0, 1, 4, 0, 2, 4, AIR);
		b.set(0, 3, 4, Material.CHISELED_STONE);
		b.box(-1, 1, 5, -1, 3, 5, s.pillar).box(1, 1, 5, 1, 3, 5, s.pillar);
		b.box(-1, 4, 5, 1, 4, 5, Material.cell(Material.SLAB_STONE_BRICK, 0, 0));
		// The roof: steep, hanging a block over the long sides.
		b.gable(-4, 4, -5, 5, h, true, s.roof, Material.cell(Material.ROOF, dye), s.wall, 0);
		// The clock tower, rising through the ridge.
		int top = h + 6;
		b.box(-1, h + 1, -1, 1, top + 2, 1, s.post == Material.STONE_BRICKS ? Material.STONE_BRICKS : s.wall);
		b.box(0, h + 1, 0, 0, top + 1, 0, AIR);
		b.set(0, top, 1, Material.LIGHT).set(0, top, -1, Material.LIGHT).set(1, top, 0, Material.LIGHT).set(-1, top, 0, Material.LIGHT);
		b.box(-1, top + 3, -1, 1, top + 3, 1, Material.cell(Material.SLAB_COPPER, 0, 0));
		b.set(0, top + 3, 0, Material.COPPER);
		b.set(0, top + 4, 0, Material.cell(Material.SLAB_COPPER, 0, 0));
		if (industrial) {
			// A tall stack at the back, smoking.
			b.box(3, h + 1, -3, 3, h + 9, -3, Material.BRICKS);
			b.set(3, h + 10, -3, Material.CAMPFIRE);
		}
		b.set(-2, 1, 5, Material.cell(Material.BANNER, dye, 0)).set(2, 1, 5, Material.cell(Material.BANNER, dye, 0));
		b.set(-2, 3, 0, Material.cell(Material.LANTERN, 0, 0));
		flag(b, 0, top + 5, 0, 4, dye, capital);
		return b;
	}

	/** Modern: a tower of glass between bands of concrete. */
	private static Structure tower(Style s, int dye, boolean capital) {
		Structure b = new Structure(8, 5, 34);
		b.box(-4, 0, -4, 4, 0, 4, Material.PLANKS);
		int h = 16;
		for (int y = 1; y <= h; y++) {
			b.walls(-4, y, -4, 4, y, 4, y % 4 == 0 ? s.wall : Material.TINTED_GLASS);
			for (int cx = -4; cx <= 4; cx += 4) {
				for (int cz = -4; cz <= 4; cz += 4) {
					if (cx != 0 || cz != 0) {
						b.set(cx, y, cz, s.post);
					}
				}
			}
			if (y % 4 == 0 && y < h) {
				b.box(-3, y, -3, 3, y, 3, s.base);
				b.box(-1, y, -1, 1, y, 1, AIR);
			}
		}
		// A wide entrance under a canopy.
		b.box(-1, 1, 4, 1, 2, 4, AIR);
		b.box(-2, 3, 5, 2, 3, 5, Material.cell(Material.SLAB_STONE_BRICK, 0, UPPER));
		b.box(-4, h + 1, -4, 4, h + 1, 4, s.wall);
		b.box(-3, h + 1, -3, 3, h + 1, 3, Material.cell(Material.ROOF, dye));
		b.box(-2, h + 2, -2, -1, h + 2, -1, s.base);
		b.box(2, h + 2, 2, 2, h + 6, 2, Material.IRON_BARS);
		b.set(2, h + 7, 2, Material.LIGHT);
		flag(b, 4, h + 2, -4, 5, dye, capital);
		return b;
	}

	// ---- what a city builds ----

	static Structure of(CityPlan.Kind kind, Style s, int dye, int variant, boolean tall) {
		return switch (kind) {
			case HOUSE -> house(s, variant, tall);
			case MONUMENT -> monument(s);
			case GRANARY -> granary(s);
			case BARRACKS -> barracks(s, dye);
			case LIBRARY -> library(s);
			case TEMPLE -> shrine(s);
			case MARKET -> market(s, dye);
			case WORKSHOP -> workshop(s);
			case WELL -> well(s);
			case ARENA -> arena(s, dye);
			case STABLE -> stable(s);
			case LIGHTHOUSE -> lighthouse();
			case PYRAMID -> pyramid();
			case STONE_RING -> stoneRing();
			case GREAT_LIBRARY -> greatLibrary();
			case WONDER -> wonder();
			case DOME -> dome();
			case STATUE -> statue();
			case ZIGGURAT -> ziggurat(s);
		};
	}

	private static Structure monument(Style s) {
		Structure b = new Structure(2, 2, 10);
		Material stone = s.roofKind == Roof.FLAT ? Material.CUT_SANDSTONE : Material.QUARTZ_PILLAR;
		b.box(-1, 1, -1, 1, 1, 1, Material.cell(s.trim, 0, 0));
		b.set(0, 1, 0, s.trim.asBlock());
		b.box(0, 2, 0, 0, 6, 0, stone);
		b.set(0, 7, 0, Material.GOLD);
		return b;
	}

	private static Structure granary(Style s) {
		Structure b = new Structure(2, 2, 10);
		if (s.tier >= 4) {
			// A grain silo: round, tall, with a hatch on top.
			Material shell = s.tier >= 5 ? Material.WHITE_CONCRETE : Material.BRICKS;
			for (int y = 1; y <= 6; y++) {
				b.box(-1, y, 0, 1, y, 0, shell).box(0, y, -1, 0, y, 1, shell);
			}
			b.box(-1, 7, 0, 1, 7, 0, Material.cell(Material.SLAB_STONE_BRICK, 0, 0)).box(0, 7, -1, 0, 7, 1, Material.cell(Material.SLAB_STONE_BRICK, 0, 0));
			b.set(0, 7, 0, Material.IRON_BARS);
			b.set(-2, 1, 1, Material.HAY).set(2, 1, -1, Material.BARREL);
			return b;
		}
		// Off the ground on posts, out of reach of the damp and the mice.
		for (int x = -1; x <= 1; x += 2) {
			for (int z = -1; z <= 1; z += 2) {
				b.set(x, 1, z, s.fence);
			}
		}
		b.box(-1, 2, -1, 1, 3, 1, Material.BARREL);
		b.set(0, 2, 1, Material.HAY);
		Material roof = s.roofKind == Roof.TILED ? s.roof : Material.HAY;
		b.gable(-2, 2, -2, 2, 3, true, roof, roof.stairs() ? roof.asSlab().ordinal() : roof.ordinal(), null, 0);
		// The roof is a hat over the store: open under its eaves.
		b.box(-2, 3, -2, 2, 3, -2, roof.stairs() ? Material.cell(roof, 0, SOUTH) : roof.ordinal());
		b.set(-2, 1, 1, Material.HAY).set(2, 1, -1, Material.HAY);
		return b;
	}

	private static Structure barracks(Style s, int dye) {
		Structure b = new Structure(2, 2, 6);
		// A yard behind a fence, a target to shoot at, and the soldiers' tent in the city's colours.
		for (int i = -2; i <= 2; i++) {
			b.set(i, 1, -2, s.fence);
			b.set(-2, 1, i, s.fence);
			if (i != 0) {
				b.set(i, 1, 2, s.fence);
			}
		}
		b.set(-1, 1, -1, Material.TARGET);
		b.set(-1, 1, 0, Material.HAY);
		int cloth = Material.cell(Material.WOOL, dye);
		for (int z = -1; z <= 1; z++) {
			b.set(0, 1, z, cloth).set(2, 1, z, cloth).set(1, 2, z, cloth);
		}
		b.set(1, 1, -1, Material.cell(Material.WOOL, 0));
		b.set(-2, 2, 2, Material.cell(Material.LANTERN, 0, 0));
		b.set(-1, 1, 2, AIR);
		b.set(-1, 1, 1, Material.cell(Material.BANNER, dye, 0));
		return b;
	}

	private static Structure library(Style s) {
		Style built = s.roofKind == Roof.THATCH ? style(1, "") : s;
		Structure b = house(built, 1, built.tier >= 2 && built.tier < 5);
		// Books to the ceiling along the back wall, and two columns at the door.
		int h = built.tier >= 5 ? 3 : built.wallHeight;
		b.box(-1, 1, -1, 1, h, -1, Material.BOOKSHELF);
		b.box(-1, 1, 2, -1, h, 2, built.pillar);
		b.box(1, 1, 2, 1, h, 2, built.pillar);
		b.set(0, 3, 2, Material.cell(Material.LANTERN, 0, Material.HANGING));
		return b;
	}

	private static Structure shrine(Style s) {
		Structure b = new Structure(2, 2, 10);
		Material stone = s.roofKind == Roof.FLAT || s.tier == 0 ? Material.SMOOTH_SANDSTONE : Material.QUARTZ;
		Material pillar = stone == Material.QUARTZ ? Material.QUARTZ_PILLAR : Material.CUT_SANDSTONE;
		Material slab = stone == Material.QUARTZ ? Material.SLAB_QUARTZ : Material.SLAB_SANDSTONE;
		b.box(-2, 0, -2, 2, 0, 2, stone);
		for (int x = -2; x <= 2; x += 4) {
			for (int z = -2; z <= 2; z += 2) {
				b.box(x, 1, z, x, 3, z, pillar);
			}
		}
		b.box(-1, 1, -2, 1, 3, -2, stone);
		b.walls(-2, 4, -2, 2, 4, 2, stone);
		for (int z = -2; z <= 2; z++) {
			b.set(-2, 5, z, Material.cell(slab, 0, 0)).set(2, 5, z, Material.cell(slab, 0, 0));
			b.set(-1, 5, z, Material.cell(slab, 0, UPPER)).set(1, 5, z, Material.cell(slab, 0, UPPER));
			b.set(0, 6, z, Material.cell(slab, 0, 0));
		}
		b.set(0, 5, 2, Material.GOLD).set(0, 5, -2, stone);
		// The altar, and a flame before it.
		b.set(0, 1, -1, Material.GOLD);
		b.set(0, 1, 0, Material.CAMPFIRE);
		return b;
	}

	private static Structure market(Style s, int dye) {
		Structure b = new Structure(2, 2, 6);
		// Two stalls facing each other across an aisle, under striped awnings in the city's colours.
		for (int side = -1; side <= 1; side += 2) {
			int x0 = side * 2;
			int x1 = side;
			for (int z = -2; z <= 2; z += 4) {
				b.box(x0, 1, z, x0, 2, z, s.fence);
			}
			for (int z = -2; z <= 2; z++) {
				int cloth = Material.cell(Material.WOOL, (z & 1) == 0 ? dye : 0);
				b.set(x0, 3, z, cloth);
				b.set(x1, 3, z, Material.cell(Material.WOOL, (z & 1) == 0 ? dye : 0));
			}
		}
		b.set(-2, 1, -1, Material.BARREL).set(-2, 1, 0, Material.MELON).set(-2, 1, 1, Material.HAY);
		b.set(2, 1, -1, Material.PUMPKIN).set(2, 1, 0, Material.BARREL).set(2, 1, 1, Material.MELON);
		b.set(-1, 2, 0, Material.cell(Material.LANTERN, 0, Material.HANGING));
		return b;
	}

	private static Structure workshop(Style s) {
		Structure b = new Structure(2, 2, 12);
		Material stone = s.tier >= 3 ? Material.BRICKS : Material.COBBLE;
		b.box(-2, 0, -2, 2, 0, 2, Material.COBBLE);
		// Three walls and an open front, so the fire can be seen from the street.
		b.box(-2, 1, -2, 2, 3, -2, stone);
		b.box(-2, 1, -2, -2, 3, 1, stone);
		b.box(2, 1, -2, 2, 3, 1, stone);
		b.box(-2, 1, 2, -2, 3, 2, s.post).box(2, 1, 2, 2, 3, 2, s.post);
		b.set(-1, 1, -1, Material.FURNACE).set(0, 1, -1, Material.FURNACE);
		b.set(1, 1, 0, Material.BARREL);
		Material roof = s.roofKind == Roof.TILED ? s.roof : Material.STAIRS_COBBLE;
		b.gable(-2, 2, -2, 2, 4, true, roof, roof.asSlab().ordinal(), stone, 0);
		// The stack of the forge.
		b.box(1, 1, -1, 1, 8, -1, Material.BRICKS);
		b.set(1, 9, -1, Material.CAMPFIRE);
		return b;
	}

	private static Structure well(Style s) {
		Structure b = new Structure(2, 2, 7);
		Material stone = s.roofKind == Roof.FLAT ? Material.SMOOTH_SANDSTONE : Material.STONE_BRICKS;
		b.box(-1, 1, -1, 1, 1, 1, stone);
		b.set(0, 1, 0, Material.WATER).set(0, 0, 0, Material.WATER);
		for (int x = -1; x <= 1; x += 2) {
			for (int z = -1; z <= 1; z += 2) {
				b.box(x, 2, z, x, 3, z, s.fence);
			}
		}
		Material roof = s.roofKind == Roof.TILED ? s.roof.asSlab() : Material.SLAB_OAK;
		b.box(-1, 4, -1, 1, 4, 1, Material.cell(roof, 0, 0));
		b.set(0, 4, 0, roof.asBlock());
		b.set(0, 3, 0, Material.cell(Material.LANTERN, 0, Material.HANGING));
		// A trough of water beside it.
		b.set(-2, 0, 2, Material.WATER).set(2, 0, -2, Material.WATER);
		return b;
	}

	private static Structure arena(Style s, int dye) {
		Structure b = new Structure(2, 2, 6);
		Material seat = s.roofKind == Roof.FLAT || s.tier <= 1 ? Material.STAIRS_SANDSTONE : Material.STAIRS_STONE_BRICK;
		b.box(-2, 0, -2, 2, 0, 2, Material.SAND);
		// Seats around the sand, rising outwards; a way in on each side.
		for (int i = -2; i <= 2; i++) {
			b.set(i, 1, -2, Material.cell(seat, 0, NORTH)).set(i, 1, 2, Material.cell(seat, 0, SOUTH));
			b.set(-2, 1, i, Material.cell(seat, 0, WEST)).set(2, 1, i, Material.cell(seat, 0, EAST));
		}
		b.set(0, 1, -2, AIR).set(0, 1, 2, AIR).set(-2, 1, 0, AIR).set(2, 1, 0, AIR);
		for (int x = -2; x <= 2; x += 4) {
			for (int z = -2; z <= 2; z += 4) {
				b.box(x, 1, z, x, 2, z, seat.asBlock());
				b.set(x, 3, z, Material.cell(Material.BANNER, dye, 0));
			}
		}
		return b;
	}

	private static Structure stable(Style s) {
		Structure b = new Structure(2, 2, 6);
		Material wood = s.wall == Material.PLASTER || s.roofKind == Roof.FLAT ? Material.PLANKS : s.floor;
		// A shed along the back with fodder in it, and a paddock in front.
		b.box(-2, 1, -2, 2, 2, -2, wood);
		b.box(-2, 1, -1, -2, 2, -1, s.fence).box(2, 1, -1, 2, 2, -1, s.fence);
		b.box(-2, 3, -2, 2, 3, -1, Material.cell(Material.SLAB_SPRUCE, 0, 0));
		b.set(-1, 1, -1, Material.HAY).set(1, 1, -1, Material.HAY).set(1, 2, -1, Material.HAY);
		for (int i = 0; i <= 2; i++) {
			b.set(-2, 1, i, s.fence).set(2, 1, i, s.fence);
		}
		b.set(-1, 1, 2, s.fence).set(1, 1, 2, s.fence);
		b.set(0, 0, 1, Material.COARSE_DIRT).set(-1, 0, 0, Material.COARSE_DIRT).set(1, 0, 1, Material.COARSE_DIRT);
		return b;
	}

	private static Structure lighthouse() {
		Structure b = new Structure(2, 2, 14);
		b.box(-1, 1, -1, 1, 2, 1, Material.STONE_BRICKS);
		for (int y = 3; y <= 8; y++) {
			b.box(-1, y, -1, 1, y, 1, (y / 2) % 2 == 0 ? Material.BRICKS : Material.WHITE_CONCRETE);
			// Round off the corners: a tower, not a block.
			b.set(-1, y, -1, AIR).set(1, y, -1, AIR).set(-1, y, 1, AIR).set(1, y, 1, AIR);
		}
		b.box(-1, 9, -1, 1, 9, 1, Material.cell(Material.SLAB_STONE_BRICK, 0, 0));
		b.set(0, 9, 0, Material.STONE_BRICKS);
		b.set(0, 10, 0, Material.LIGHT);
		b.set(-1, 10, 0, Material.GLASS_PANE).set(1, 10, 0, Material.GLASS_PANE).set(0, 10, -1, Material.GLASS_PANE).set(0, 10, 1, Material.GLASS_PANE);
		b.box(-1, 11, -1, 1, 11, 1, Material.cell(Material.SLAB_COPPER, 0, 0));
		b.set(0, 11, 0, Material.COPPER);
		return b;
	}

	private static Structure pyramid() {
		Structure b = new Structure(2, 2, 6);
		// Smooth sides: each course a ring of steps leaning in.
		for (int ring = 2; ring >= 1; ring--) {
			int y = 3 - ring;
			b.box(-ring, y, -ring, ring, y, ring, Material.SMOOTH_SANDSTONE);
			for (int i = -ring; i <= ring; i++) {
				b.set(i, y, -ring, Material.cell(Material.STAIRS_SANDSTONE, 0, SOUTH)).set(i, y, ring, Material.cell(Material.STAIRS_SANDSTONE, 0, NORTH));
				b.set(-ring, y, i, Material.cell(Material.STAIRS_SANDSTONE, 0, EAST)).set(ring, y, i, Material.cell(Material.STAIRS_SANDSTONE, 0, WEST));
			}
		}
		b.set(0, 3, 0, Material.GOLD);
		b.set(0, 1, 2, Material.CUT_SANDSTONE);
		return b;
	}

	private static Structure stoneRing() {
		Structure b = new Structure(2, 2, 6);
		// Standing stones in a ring, with lintels across.
		for (int x = -2; x <= 2; x += 2) {
			for (int z = -2; z <= 2; z += 2) {
				if (x != 0 || z != 0) {
					b.box(x, 1, z, x, 3, z, (x + z) % 4 == 0 ? Material.STONE : Material.ANDESITE);
				}
			}
		}
		for (int i = -2; i <= 2; i++) {
			if (i != 0) {
				int lintel = Material.cell(Material.SLAB_COBBLE, 0, 0);
				b.set(i, 4, -2, lintel).set(-2, 4, i, lintel);
			}
		}
		b.set(-2, 4, -2, Material.cell(Material.SLAB_COBBLE, 0, 0));
		b.set(0, 1, 0, Material.MOSSY_COBBLE);
		return b;
	}

	private static Structure greatLibrary() {
		Structure b = new Structure(2, 2, 14);
		b.box(-2, 0, -2, 2, 0, 2, Material.QUARTZ);
		b.box(-1, 1, -1, 1, 7, 1, Material.BOOKSHELF);
		b.box(0, 1, 1, 0, 2, 1, AIR);
		for (int x = -2; x <= 2; x += 2) {
			for (int z = -2; z <= 2; z += 2) {
				if (Math.abs(x) == 2 || Math.abs(z) == 2) {
					b.box(x, 1, z, x, 7, z, Material.QUARTZ_PILLAR);
				}
			}
		}
		b.box(0, 1, 2, 0, 7, 2, AIR);
		b.box(-2, 8, -2, 2, 8, 2, Material.QUARTZ);
		for (int z = -2; z <= 2; z++) {
			b.set(-2, 9, z, Material.cell(Material.SLAB_QUARTZ, 0, 0)).set(2, 9, z, Material.cell(Material.SLAB_QUARTZ, 0, 0));
			b.set(-1, 9, z, Material.cell(Material.SLAB_QUARTZ, 0, UPPER)).set(1, 9, z, Material.cell(Material.SLAB_QUARTZ, 0, UPPER));
			b.set(0, 10, z, Material.cell(Material.SLAB_QUARTZ, 0, 0));
		}
		b.set(0, 9, 2, Material.GOLD);
		return b;
	}

	/** A wonder without a shape of its own: a white tower in three tiers under green copper, crowned in gold. */
	private static Structure wonder() {
		Structure b = new Structure(2, 2, 18);
		b.box(-2, 1, -2, 2, 1, 2, Material.QUARTZ);
		b.box(-1, 2, -1, 1, 7, 1, Material.QUARTZ);
		for (int x = -1; x <= 1; x += 2) {
			for (int z = -1; z <= 1; z += 2) {
				b.box(x, 2, z, x, 7, z, Material.QUARTZ_PILLAR);
			}
		}
		b.box(0, 2, 1, 0, 3, 1, AIR);
		b.box(0, 2, 0, 0, 6, 0, AIR);
		for (int tier = 0; tier < 2; tier++) {
			int y = tier == 0 ? 2 : 8;
			int ring = tier == 0 ? 2 : 1;
			for (int i = -ring; i <= ring; i++) {
				b.set(i, y, -ring, Material.cell(Material.STAIRS_COPPER, 0, SOUTH)).set(i, y, ring, Material.cell(Material.STAIRS_COPPER, 0, NORTH));
				b.set(-ring, y, i, Material.cell(Material.STAIRS_COPPER, 0, EAST)).set(ring, y, i, Material.cell(Material.STAIRS_COPPER, 0, WEST));
			}
		}
		b.set(0, 2, 2, AIR);
		b.box(0, 8, 0, 0, 11, 0, Material.QUARTZ_PILLAR);
		b.set(0, 12, 0, Material.LIGHT);
		b.set(0, 13, 0, Material.GOLD);
		return b;
	}

	/** A wonder under a dome: a white hall with columns, roofed in green copper. */
	private static Structure dome() {
		Structure b = new Structure(2, 2, 12);
		b.box(-2, 0, -2, 2, 0, 2, Material.QUARTZ);
		for (int y = 1; y <= 4; y++) {
			b.walls(-2, y, -2, 2, y, 2, y == 4 ? Material.CHISELED_STONE : Material.QUARTZ);
		}
		for (int x = -2; x <= 2; x += 4) {
			for (int z = -2; z <= 2; z += 4) {
				b.box(x, 1, z, x, 4, z, Material.QUARTZ_PILLAR);
			}
		}
		b.box(0, 1, 2, 0, 2, 2, AIR);
		b.set(-2, 2, 0, Material.GLASS_PANE).set(2, 2, 0, Material.GLASS_PANE).set(0, 2, -2, Material.GLASS_PANE);
		// The dome, in three courses.
		for (int ring = 2; ring >= 1; ring--) {
			int y = 7 - ring;
			for (int i = -ring; i <= ring; i++) {
				b.set(i, y, -ring, Material.cell(Material.STAIRS_COPPER, 0, SOUTH)).set(i, y, ring, Material.cell(Material.STAIRS_COPPER, 0, NORTH));
				b.set(-ring, y, i, Material.cell(Material.STAIRS_COPPER, 0, EAST)).set(ring, y, i, Material.cell(Material.STAIRS_COPPER, 0, WEST));
			}
		}
		b.set(0, 7, 0, Material.COPPER);
		b.set(0, 8, 0, Material.GOLD);
		b.set(0, 4, 0, Material.cell(Material.LANTERN, 0, Material.HANGING));
		return b;
	}

	/** A wonder that is a figure: a giant of green copper on a pedestal, one arm raised with a light. */
	private static Structure statue() {
		Structure b = new Structure(2, 2, 14);
		b.box(-1, 1, -1, 1, 1, 1, Material.STONE_BRICKS);
		b.set(0, 2, 0, Material.CHISELED_STONE);
		for (int i = -1; i <= 1; i += 2) {
			b.set(i, 2, 0, Material.cell(Material.SLAB_STONE_BRICK, 0, 0)).set(0, 2, i, Material.cell(Material.SLAB_STONE_BRICK, 0, 0));
		}
		b.box(0, 3, 0, 0, 7, 0, Material.COPPER);
		b.set(-1, 6, 0, Material.COPPER).set(1, 6, 0, Material.COPPER);
		b.set(1, 7, 0, Material.COPPER);
		b.set(1, 8, 0, Material.LIGHT);
		b.set(0, 8, 0, Material.GOLD);
		return b;
	}

	/** A wonder of terraces: two steps of stone with a stair up the front, green on its corners, a shrine of gold on top. */
	private static Structure ziggurat(Style s) {
		Structure b = new Structure(2, 2, 8);
		Material stone = s.roofKind == Roof.FLAT && s.tier < 5 ? Material.CUT_SANDSTONE : Material.STONE_BRICKS;
		Material steps = stone == Material.CUT_SANDSTONE ? Material.STAIRS_SANDSTONE : Material.STAIRS_STONE_BRICK;
		b.box(-2, 1, -2, 2, 2, 2, stone);
		b.box(-1, 3, -1, 1, 4, 1, stone);
		b.set(0, 2, 2, Material.cell(steps, 0, NORTH));
		b.set(0, 4, 1, Material.cell(steps, 0, NORTH));
		b.set(0, 3, 2, AIR);
		for (int x = -2; x <= 2; x += 4) {
			for (int z = -2; z <= 2; z += 4) {
				b.set(x, 3, z, Material.LEAVES_OAK);
			}
		}
		b.set(-2, 3, 0, Material.LEAVES_OAK).set(2, 3, 0, Material.LEAVES_OAK);
		b.set(0, 5, 0, Material.GOLD);
		b.set(0, 6, 0, Material.LIGHT);
		return b;
	}
}
