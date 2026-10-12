package nl.jordythomassen.livingworld.mc;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import nl.jordythomassen.livingworld.board.Material;

/** Which Minecraft block each board material is. */
final class BlockPalette {
	private static final BlockState[] PLAIN = new BlockState[Material.values().length];
	private static final BlockState[] BORDER = new BlockState[16];
	private static final BlockState[] ROOF = new BlockState[16];
	private static final BlockState[] FLAG = new BlockState[16];

	static {
		for (Material material : Material.values()) {
			PLAIN[material.ordinal()] = switch (material) {
				case AIR, BORDER, ROOF, FLAG, BANNER, WOOL -> Blocks.AIR.defaultBlockState();
				case WATER -> Blocks.WATER.defaultBlockState();
				case STONE -> Blocks.STONE.defaultBlockState();
				case DIRT -> Blocks.DIRT.defaultBlockState();
				case GRASS -> Blocks.GRASS_BLOCK.defaultBlockState();
				case COARSE_DIRT -> Blocks.COARSE_DIRT.defaultBlockState();
				case SAND -> Blocks.SAND.defaultBlockState();
				case SANDSTONE -> Blocks.SANDSTONE.defaultBlockState();
				case PODZOL -> Blocks.PODZOL.defaultBlockState();
				case SNOW_BLOCK -> Blocks.SNOW_BLOCK.defaultBlockState();
				case SNOW_LAYER -> Blocks.SNOW.defaultBlockState();
				case ICE -> Blocks.PACKED_ICE.defaultBlockState();
				case GRAVEL -> Blocks.GRAVEL.defaultBlockState();
				case MUD -> Blocks.MUD.defaultBlockState();
				case CLOUD -> Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState();
				case LOG_OAK -> Blocks.OAK_LOG.defaultBlockState();
				// Leaves that are not next to a log would otherwise wither away.
				case LEAVES_OAK -> Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true);
				case LOG_JUNGLE -> Blocks.JUNGLE_LOG.defaultBlockState();
				case LEAVES_JUNGLE -> Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true);
				case LOG_SPRUCE -> Blocks.SPRUCE_LOG.defaultBlockState();
				case LEAVES_SPRUCE -> Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true);
				case FARMLAND -> Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7);
				case WHEAT -> Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7);
				case PATH -> Blocks.DIRT_PATH.defaultBlockState();
				case COBBLE -> Blocks.COBBLESTONE.defaultBlockState();
				case STONE_BRICKS -> Blocks.STONE_BRICKS.defaultBlockState();
				case PLANKS -> Blocks.OAK_PLANKS.defaultBlockState();
				case BRICKS -> Blocks.BRICKS.defaultBlockState();
				case GLASS -> Blocks.GLASS.defaultBlockState();
				case ORE -> Blocks.IRON_ORE.defaultBlockState();
				case HAY -> Blocks.HAY_BLOCK.defaultBlockState();
				case FENCE -> Blocks.OAK_FENCE.defaultBlockState();
				case CAMPFIRE -> Blocks.CAMPFIRE.defaultBlockState();
				case LIGHT -> Blocks.SEA_LANTERN.defaultBlockState();
				case QUARTZ -> Blocks.QUARTZ_BLOCK.defaultBlockState();
				case SHORT_GRASS -> Blocks.SHORT_GRASS.defaultBlockState();
				case DRY_GRASS -> Blocks.SHORT_DRY_GRASS.defaultBlockState();
				case DEAD_BUSH -> Blocks.DEAD_BUSH.defaultBlockState();
				case LILY -> Blocks.LILY_PAD.defaultBlockState();
				case MUD_BRICKS -> Blocks.MUD_BRICKS.defaultBlockState();
				case SMOOTH_SANDSTONE -> Blocks.SMOOTH_SANDSTONE.defaultBlockState();
				case QUARTZ_PILLAR -> Blocks.QUARTZ_PILLAR.defaultBlockState();
				case DARK_PLANKS -> Blocks.DARK_OAK_PLANKS.defaultBlockState();
				case SPRUCE_PLANKS -> Blocks.SPRUCE_PLANKS.defaultBlockState();
				case TERRACOTTA -> Blocks.TERRACOTTA.defaultBlockState();
				case DEEPSLATE_BRICKS -> Blocks.DEEPSLATE_BRICKS.defaultBlockState();
				case WHITE_CONCRETE -> Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState();
				case GRAY_CONCRETE -> Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState();
				case MOSSY_COBBLE -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
				case BOOKSHELF -> Blocks.BOOKSHELF.defaultBlockState();
				case FURNACE -> Blocks.FURNACE.defaultBlockState();
				case TARGET -> Blocks.TARGET.defaultBlockState();
				case GOLD -> Blocks.GOLD_BLOCK.defaultBlockState();
				case ORE_COAL -> Blocks.COAL_ORE.defaultBlockState();
				case ORE_GOLD -> Blocks.GOLD_ORE.defaultBlockState();
				case ORE_COPPER -> Blocks.COPPER_ORE.defaultBlockState();
				case ORE_GEM -> Blocks.EMERALD_ORE.defaultBlockState();
				case CALCITE -> Blocks.CALCITE.defaultBlockState();
				case ANDESITE -> Blocks.ANDESITE.defaultBlockState();
				case MELON -> Blocks.MELON.defaultBlockState();
				case PUMPKIN -> Blocks.PUMPKIN.defaultBlockState();
				case FLOWER -> Blocks.POPPY.defaultBlockState();
				case BERRY_BUSH -> Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(BlockStateProperties.AGE_3, 3);
				case BELL -> Blocks.BELL.defaultBlockState();
				// Turned to whoever comes in through the south door, with a book lying open on it.
				case LECTERN -> Blocks.LECTERN.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.SOUTH)
					.setValue(BlockStateProperties.HAS_BOOK, true);
				case STAIRS_OAK -> Blocks.OAK_STAIRS.defaultBlockState();
				case STAIRS_SPRUCE -> Blocks.SPRUCE_STAIRS.defaultBlockState();
				case STAIRS_DARK_OAK -> Blocks.DARK_OAK_STAIRS.defaultBlockState();
				case STAIRS_COBBLE -> Blocks.COBBLESTONE_STAIRS.defaultBlockState();
				case STAIRS_STONE_BRICK -> Blocks.STONE_BRICK_STAIRS.defaultBlockState();
				case STAIRS_BRICK -> Blocks.BRICK_STAIRS.defaultBlockState();
				case STAIRS_SANDSTONE -> Blocks.SMOOTH_SANDSTONE_STAIRS.defaultBlockState();
				case STAIRS_QUARTZ -> Blocks.QUARTZ_STAIRS.defaultBlockState();
				case STAIRS_DEEPSLATE -> Blocks.DEEPSLATE_TILE_STAIRS.defaultBlockState();
				case STAIRS_MUD_BRICK -> Blocks.MUD_BRICK_STAIRS.defaultBlockState();
				// Waxed, so the green stays green.
				case STAIRS_COPPER -> Blocks.CUT_COPPER_STAIRS.waxed().oxidized().defaultBlockState();
				case SLAB_OAK -> Blocks.OAK_SLAB.defaultBlockState();
				case SLAB_SPRUCE -> Blocks.SPRUCE_SLAB.defaultBlockState();
				case SLAB_DARK_OAK -> Blocks.DARK_OAK_SLAB.defaultBlockState();
				case SLAB_COBBLE -> Blocks.COBBLESTONE_SLAB.defaultBlockState();
				case SLAB_STONE_BRICK -> Blocks.STONE_BRICK_SLAB.defaultBlockState();
				case SLAB_BRICK -> Blocks.BRICK_SLAB.defaultBlockState();
				case SLAB_SANDSTONE -> Blocks.SMOOTH_SANDSTONE_SLAB.defaultBlockState();
				case SLAB_QUARTZ -> Blocks.QUARTZ_SLAB.defaultBlockState();
				case SLAB_DEEPSLATE -> Blocks.DEEPSLATE_TILE_SLAB.defaultBlockState();
				case SLAB_MUD_BRICK -> Blocks.MUD_BRICK_SLAB.defaultBlockState();
				case SLAB_COPPER -> Blocks.CUT_COPPER_SLAB.waxed().oxidized().defaultBlockState();
				case STRIPPED_LOG -> Blocks.STRIPPED_OAK_LOG.defaultBlockState();
				case LOG_DARK_OAK -> Blocks.DARK_OAK_LOG.defaultBlockState();
				case PLASTER -> Blocks.DYED_TERRACOTTA.pick(DyeColor.WHITE).defaultBlockState();
				case GLASS_PANE -> Blocks.GLASS_PANE.defaultBlockState();
				case LANTERN -> Blocks.LANTERN.defaultBlockState();
				case COBBLE_WALL -> Blocks.COBBLESTONE_WALL.defaultBlockState();
				case STONE_BRICK_WALL -> Blocks.STONE_BRICK_WALL.defaultBlockState();
				case DEEPSLATE_TILES -> Blocks.DEEPSLATE_TILES.defaultBlockState();
				case POLISHED_ANDESITE -> Blocks.POLISHED_ANDESITE.defaultBlockState();
				case CUT_SANDSTONE -> Blocks.CUT_SANDSTONE.defaultBlockState();
				case IRON_BARS -> Blocks.IRON_BARS.defaultBlockState();
				case BARREL -> Blocks.BARREL.defaultBlockState();
				case CHISELED_STONE -> Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
				case COPPER -> Blocks.CUT_COPPER.waxed().oxidized().defaultBlockState();
				case DARK_FENCE -> Blocks.DARK_OAK_FENCE.defaultBlockState();
				case SPRUCE_FENCE -> Blocks.SPRUCE_FENCE.defaultBlockState();
				case TINTED_GLASS -> Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE).defaultBlockState();
			};
		}
		for (int dye = 0; dye < 16; dye++) {
			DyeColor color = DyeColor.byId(dye);
			BORDER[dye] = Blocks.CONCRETE.pick(color).defaultBlockState();
			ROOF[dye] = Blocks.CONCRETE.pick(color).defaultBlockState();
			FLAG[dye] = Blocks.WOOL.pick(color).defaultBlockState();
		}
	}

	private BlockPalette() {
	}

	private static final net.minecraft.core.Direction[] FACING = { net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.EAST,
		net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST };
	/** Blocks whose shape says how they lie, by cell: worked out once each. */
	private static final java.util.Map<Integer, BlockState> SHAPED = new java.util.HashMap<>();

	static BlockState of(int cell) {
		Material material = Material.of(cell);
		int shape = Material.shape(cell);
		switch (material) {
			case BORDER:
				return BORDER[Material.dye(cell)];
			case ROOF:
				return ROOF[Material.dye(cell)];
			case FLAG, WOOL:
				return FLAG[Material.dye(cell)];
			case BANNER:
				return SHAPED.computeIfAbsent(cell, c -> Blocks.BANNER.pick(DyeColor.byId(Material.dye(c))).defaultBlockState()
					.setValue(BlockStateProperties.ROTATION_16, (Material.shape(c) & 3) * 4));
			default:
				break;
		}
		if (shape == 0) {
			return PLAIN[material.ordinal()];
		}
		return SHAPED.computeIfAbsent(cell, c -> shaped(material, shape));
	}

	private static BlockState shaped(Material material, int shape) {
		BlockState plain = PLAIN[material.ordinal()];
		if (material.stairs()) {
			return plain.setValue(BlockStateProperties.HORIZONTAL_FACING, FACING[shape & 3]).setValue(BlockStateProperties.HALF,
				(shape & Material.UPSIDE_DOWN) != 0 ? net.minecraft.world.level.block.state.properties.Half.TOP : net.minecraft.world.level.block.state.properties.Half.BOTTOM);
		}
		if (material.slab()) {
			return plain.setValue(BlockStateProperties.SLAB_TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP);
		}
		if (material == Material.LANTERN) {
			return plain.setValue(BlockStateProperties.HANGING, true);
		}
		if (plain.hasProperty(BlockStateProperties.AXIS)) {
			return plain.setValue(BlockStateProperties.AXIS, shape == Material.ALONG_X ? net.minecraft.core.Direction.Axis.X : net.minecraft.core.Direction.Axis.Z);
		}
		return plain;
	}

	/** Blocks that take their final shape from what stands next to them: thin parts that join up, and stairs that turn a corner. */
	static boolean joins(BlockState state) {
		net.minecraft.world.level.block.Block block = state.getBlock();
		return block instanceof net.minecraft.world.level.block.FenceBlock || block instanceof net.minecraft.world.level.block.IronBarsBlock
			|| block instanceof net.minecraft.world.level.block.WallBlock || block instanceof net.minecraft.world.level.block.StairBlock;
	}
}
