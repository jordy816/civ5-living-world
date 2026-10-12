package nl.jordythomassen.livingworld.board;

/**
 * What a block of the board is made of, independent of Minecraft's own classes so the board can
 * be computed and tested without the game. The Minecraft side maps each to a block.
 */
public enum Material {
	AIR, WATER, STONE, DIRT, GRASS, COARSE_DIRT, SAND, SANDSTONE, PODZOL, SNOW_BLOCK, SNOW_LAYER, ICE, GRAVEL, MUD,
	/** Unexplored land: a solid white mass, like the clouds over Civ's map. */
	CLOUD,
	LOG_OAK, LEAVES_OAK, LOG_JUNGLE, LEAVES_JUNGLE, LOG_SPRUCE, LEAVES_SPRUCE,
	FARMLAND, WHEAT, PATH, COBBLE, STONE_BRICKS, PLANKS, BRICKS, GLASS, ORE, HAY, FENCE, CAMPFIRE, LIGHT, QUARTZ,
	SHORT_GRASS, DRY_GRASS, DEAD_BUSH, LILY,
	// City building by era and style.
	MUD_BRICKS, SMOOTH_SANDSTONE, QUARTZ_PILLAR, DARK_PLANKS, SPRUCE_PLANKS, TERRACOTTA, DEEPSLATE_BRICKS, WHITE_CONCRETE, GRAY_CONCRETE,
	MOSSY_COBBLE, BOOKSHELF, FURNACE, TARGET, GOLD,
	// Resources on the map.
	ORE_COAL, ORE_GOLD, ORE_COPPER, ORE_GEM, CALCITE, ANDESITE, MELON, PUMPKIN, FLOWER, BERRY_BUSH,
	/** Painted in the owning civilization's colour: territory border, city roof, city flag. */
	BORDER, ROOF, FLAG,
	// The ruler's furniture in the halls of the viewer's own cities: ring the bell to end the turn,
	// read at the lectern to choose research. Added last, so older worlds keep their painted columns.
	BELL, LECTERN,
	// Building parts with a shape (see {@link #cell(Material, int, int)}): which way a stair faces, which half a slab fills.
	STAIRS_OAK, STAIRS_SPRUCE, STAIRS_DARK_OAK, STAIRS_COBBLE, STAIRS_STONE_BRICK, STAIRS_BRICK, STAIRS_SANDSTONE, STAIRS_QUARTZ, STAIRS_DEEPSLATE,
	STAIRS_MUD_BRICK, STAIRS_COPPER,
	SLAB_OAK, SLAB_SPRUCE, SLAB_DARK_OAK, SLAB_COBBLE, SLAB_STONE_BRICK, SLAB_BRICK, SLAB_SANDSTONE, SLAB_QUARTZ, SLAB_DEEPSLATE, SLAB_MUD_BRICK, SLAB_COPPER,
	STRIPPED_LOG, LOG_DARK_OAK, PLASTER, GLASS_PANE, LANTERN, COBBLE_WALL, STONE_BRICK_WALL, DEEPSLATE_TILES, POLISHED_ANDESITE, CUT_SANDSTONE, IRON_BARS,
	/** A standing banner in the owning civilization's colour. */
	BANNER,
	BARREL, CHISELED_STONE, COPPER, DARK_FENCE, SPRUCE_FENCE, TINTED_GLASS, WOOL;

	/** Stairs: the side their high back is on. Add {@link #UPSIDE_DOWN} for a stair hanging under something. */
	public static final int NORTH = 0;
	public static final int EAST = 1;
	public static final int SOUTH = 2;
	public static final int WEST = 3;
	public static final int UPSIDE_DOWN = 4;
	/** Slabs: the upper half of the block instead of the lower. */
	public static final int UPPER = 1;
	/** Logs and pillars: lying along x or along z instead of standing. */
	public static final int ALONG_X = 1;
	public static final int ALONG_Z = 2;
	/** A lantern hanging from the block above instead of standing. */
	public static final int HANGING = 1;

	private static final Material[] VALUES = values();

	/** Things a player gathers and that Civ's land yields again every turn: wood, crops, ore. */
	public boolean regrows() {
		return switch (this) {
			case LOG_OAK, LEAVES_OAK, LOG_JUNGLE, LEAVES_JUNGLE, LOG_SPRUCE, LEAVES_SPRUCE, WHEAT, ORE, ORE_COAL, ORE_GOLD, ORE_COPPER, ORE_GEM,
				HAY, MELON, PUMPKIN, FLOWER, BERRY_BUSH, CALCITE, ANDESITE, QUARTZ -> true;
			default -> false;
		};
	}

	/** A material with a colour (0-15, the wool colours) packed into one int. */
	public static int cell(Material material, int dye) {
		return material.ordinal() | (dye << 8);
	}

	/** A material with a colour and a shape (0-15; what it means depends on the material) packed into one int. */
	public static int cell(Material material, int dye, int shape) {
		return material.ordinal() | (dye << 8) | (shape << 12);
	}

	public static int shape(int cell) {
		return (cell >> 12) & 0xF;
	}

	public boolean stairs() {
		return ordinal() >= STAIRS_OAK.ordinal() && ordinal() <= STAIRS_COPPER.ordinal();
	}

	public boolean slab() {
		return ordinal() >= SLAB_OAK.ordinal() && ordinal() <= SLAB_COPPER.ordinal();
	}

	/** Thin parts that join up with what stands next to them: fences, panes, bars, low walls. */
	public boolean joins() {
		return this == FENCE || this == DARK_FENCE || this == SPRUCE_FENCE || this == GLASS_PANE || this == IRON_BARS || this == COBBLE_WALL
			|| this == STONE_BRICK_WALL;
	}

	/** The stair of the same stuff as a slab, and the other way round; itself for anything else. */
	public Material asStairs() {
		return slab() ? VALUES[ordinal() - SLAB_OAK.ordinal() + STAIRS_OAK.ordinal()] : this;
	}

	public Material asSlab() {
		return stairs() ? VALUES[ordinal() - STAIRS_OAK.ordinal() + SLAB_OAK.ordinal()] : this;
	}

	/** The full block a stair or slab is cut from. */
	public Material asBlock() {
		return switch (asStairs()) {
			case STAIRS_OAK -> PLANKS;
			case STAIRS_SPRUCE -> SPRUCE_PLANKS;
			case STAIRS_DARK_OAK -> DARK_PLANKS;
			case STAIRS_COBBLE -> COBBLE;
			case STAIRS_STONE_BRICK -> STONE_BRICKS;
			case STAIRS_BRICK -> BRICKS;
			case STAIRS_SANDSTONE -> SMOOTH_SANDSTONE;
			case STAIRS_QUARTZ -> QUARTZ;
			case STAIRS_DEEPSLATE -> DEEPSLATE_TILES;
			case STAIRS_MUD_BRICK -> MUD_BRICKS;
			case STAIRS_COPPER -> COPPER;
			default -> this;
		};
	}

	public static Material of(int cell) {
		return VALUES[cell & 0xFF];
	}

	public static int dye(int cell) {
		return (cell >> 8) & 0xF;
	}
}
