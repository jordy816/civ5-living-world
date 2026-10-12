package nl.jordythomassen.livingworld.board;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Draws a piece of the board as a picture, seen from the south-east and above, so that what the
 * city builder makes can be looked at without starting Minecraft. Colours are rough averages of
 * the blocks' textures; stairs and slabs are drawn in their shape.
 */
final class Preview {
	private static final int HW = 11;
	private static final int HH = 8;
	private static final int VH = 10;
	private static final int[] DYES = { 0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52, 0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA,
		0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21 };

	interface Columns {
		/** Fills {@code out} (index 0 = BoardModel.BASE) for the column at x, z. */
		void column(int x, int z, int[] out);
	}

	private Preview() {
	}

	static int color(int cell) {
		Material m = Material.of(cell);
		return switch (m) {
			case BORDER, ROOF, FLAG, BANNER, WOOL -> DYES[Material.dye(cell)];
			case WATER -> 0x3F76E4;
			case STONE, ANDESITE, POLISHED_ANDESITE -> 0x8A8A8A;
			case DIRT, COARSE_DIRT -> 0x8A6243;
			case GRASS -> 0x6DAA3C;
			case SAND -> 0xDBD3A0;
			case SANDSTONE, SMOOTH_SANDSTONE, CUT_SANDSTONE, STAIRS_SANDSTONE, SLAB_SANDSTONE -> 0xD9CC9E;
			case PODZOL -> 0x6A4A2A;
			case SNOW_BLOCK, SNOW_LAYER, CLOUD -> 0xF4FAFA;
			case ICE -> 0x9CC0F0;
			case GRAVEL -> 0x837F7E;
			case MUD -> 0x3C3837;
			case LOG_OAK -> 0x6B5330;
			case LOG_JUNGLE -> 0x554419;
			case LOG_SPRUCE -> 0x3A2710;
			case LOG_DARK_OAK -> 0x3B2912;
			case STRIPPED_LOG -> 0xB29157;
			case LEAVES_OAK, LEAVES_JUNGLE -> 0x3F7D22;
			case LEAVES_SPRUCE -> 0x3B5F3B;
			case FARMLAND -> 0x5A3A1E;
			case WHEAT, HAY -> 0xC9A51C;
			case PATH -> 0x94793F;
			case COBBLE, STAIRS_COBBLE, SLAB_COBBLE, COBBLE_WALL -> 0x7F7F7F;
			case MOSSY_COBBLE -> 0x6C7A5C;
			case STONE_BRICKS, STAIRS_STONE_BRICK, SLAB_STONE_BRICK, STONE_BRICK_WALL, CHISELED_STONE -> 0x7A7A7A;
			case PLANKS, STAIRS_OAK, SLAB_OAK, FENCE, BARREL, LECTERN -> 0xA2824E;
			case SPRUCE_PLANKS, STAIRS_SPRUCE, SLAB_SPRUCE, SPRUCE_FENCE -> 0x72542F;
			case DARK_PLANKS, STAIRS_DARK_OAK, SLAB_DARK_OAK, DARK_FENCE -> 0x432B14;
			case BRICKS, STAIRS_BRICK, SLAB_BRICK -> 0x96614F;
			case GLASS, GLASS_PANE -> 0xC0E8F0;
			case TINTED_GLASS -> 0x4A5560;
			case ORE, ORE_COAL, ORE_GOLD, ORE_COPPER, ORE_GEM -> 0x8F8F8F;
			case CAMPFIRE -> 0xE0701A;
			case LIGHT -> 0xD6F0E8;
			case LANTERN -> 0xF2C24A;
			case QUARTZ, QUARTZ_PILLAR, STAIRS_QUARTZ, SLAB_QUARTZ, CALCITE -> 0xECE6DF;
			case PLASTER, WHITE_CONCRETE -> 0xD9D2C8;
			case GRAY_CONCRETE -> 0x36393D;
			case MUD_BRICKS, STAIRS_MUD_BRICK, SLAB_MUD_BRICK -> 0x89674F;
			case TERRACOTTA -> 0x985E43;
			case DEEPSLATE_BRICKS, DEEPSLATE_TILES, STAIRS_DEEPSLATE, SLAB_DEEPSLATE -> 0x3D3D42;
			case BOOKSHELF -> 0x75563A;
			case FURNACE -> 0x5E5E5E;
			case TARGET -> 0xE4B0A8;
			case GOLD, BELL -> 0xF6D03D;
			case COPPER, STAIRS_COPPER, SLAB_COPPER -> 0x4FAB90;
			case IRON_BARS -> 0x8C8E8A;
			case MELON -> 0x71A81B;
			case PUMPKIN -> 0xC87616;
			case FLOWER -> 0xD03030;
			case BERRY_BUSH, SHORT_GRASS, LILY -> 0x4E8A32;
			case DRY_GRASS, DEAD_BUSH -> 0xA08A4A;
			default -> 0xFF00FF;
		};
	}

	/** Parts of a block, each as x0, y0, z0, x1, y1, z1 within the unit cube. */
	private static double[][] boxes(int cell) {
		Material m = Material.of(cell);
		int shape = Material.shape(cell);
		if (m.slab()) {
			return shape == Material.UPPER ? new double[][] { { 0, 0.5, 0, 1, 1, 1 } } : new double[][] { { 0, 0, 0, 1, 0.5, 1 } };
		}
		if (m.stairs()) {
			boolean hanging = (shape & Material.UPSIDE_DOWN) != 0;
			double y0 = hanging ? 0 : 0.5;
			double y1 = hanging ? 0.5 : 1;
			double[] step = switch (shape & 3) {
				case Material.NORTH -> new double[] { 0, y0, 0, 1, y1, 0.5 };
				case Material.SOUTH -> new double[] { 0, y0, 0.5, 1, y1, 1 };
				case Material.EAST -> new double[] { 0.5, y0, 0, 1, y1, 1 };
				default -> new double[] { 0, y0, 0, 0.5, y1, 1 };
			};
			return new double[][] { hanging ? new double[] { 0, 0.5, 0, 1, 1, 1 } : new double[] { 0, 0, 0, 1, 0.5, 1 }, step };
		}
		return switch (m) {
			case FENCE, DARK_FENCE, SPRUCE_FENCE, IRON_BARS, COBBLE_WALL, STONE_BRICK_WALL -> new double[][] { { 0.38, 0, 0.38, 0.62, 1, 0.62 } };
			case LANTERN -> shape == Material.HANGING ? new double[][] { { 0.3, 0.45, 0.3, 0.7, 1, 0.7 } } : new double[][] { { 0.3, 0, 0.3, 0.7, 0.55, 0.7 } };
			case BANNER -> new double[][] { { 0.44, 0, 0.44, 0.56, 1.9, 0.56 }, { 0.15, 0.6, 0.4, 0.85, 1.9, 0.6 } };
			case CAMPFIRE -> new double[][] { { 0, 0, 0, 1, 0.35, 1 } };
			case SNOW_LAYER, LILY -> new double[][] { { 0, 0, 0, 1, 0.12, 1 } };
			case SHORT_GRASS, DRY_GRASS, DEAD_BUSH, FLOWER, BERRY_BUSH, WHEAT -> new double[][] { { 0.25, 0, 0.25, 0.75, 0.6, 0.75 } };
			case BELL -> new double[][] { { 0.25, 0.2, 0.25, 0.75, 0.9, 0.75 } };
			case LECTERN -> new double[][] { { 0.2, 0, 0.2, 0.8, 0.9, 0.8 } };
			case PATH, FARMLAND -> new double[][] { { 0, 0, 0, 1, 0.94, 1 } };
			default -> new double[][] { { 0, 0, 0, 1, 1, 1 } };
		};
	}

	private static boolean seeThrough(Material m) {
		return m == Material.GLASS || m == Material.GLASS_PANE || m == Material.TINTED_GLASS || m == Material.WATER;
	}

	/** Draws the columns from (x0, z0) to (x1, z1), everything from {@code fromY} up. */
	static BufferedImage draw(Columns columns, int x0, int z0, int x1, int z1, int fromY) {
		int nx = x1 - x0 + 1;
		int nz = z1 - z0 + 1;
		int tall = BoardModel.TOP - fromY;
		int[][][] cells = new int[nx][nz][];
		int highest = 0;
		int[] column = new int[BoardModel.TOP - BoardModel.BASE];
		for (int x = 0; x < nx; x++) {
			for (int z = 0; z < nz; z++) {
				columns.column(x0 + x, z0 + z, column);
				cells[x][z] = new int[tall];
				for (int y = 0; y < tall; y++) {
					cells[x][z][y] = column[fromY + y - BoardModel.BASE];
					if (Material.of(cells[x][z][y]) != Material.AIR) {
						highest = Math.max(highest, y);
					}
				}
			}
		}
		int width = (nx + nz) * HW + 20;
		int height = (nx + nz) * HH + (highest + 2) * VH + 20;
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x1B2430));
		g.fillRect(0, 0, width, height);
		double ox = nz * HW + 10;
		double oy = (highest + 2) * VH + 10;
		for (int z = 0; z < nz; z++) {
			for (int x = 0; x < nx; x++) {
				for (int y = 0; y < tall; y++) {
					int cell = cells[x][z][y];
					Material m = Material.of(cell);
					if (m == Material.AIR) {
						continue;
					}
					boolean hiddenTop = y + 1 < tall && solid(cells[x][z][y + 1]);
					boolean hiddenSouth = z + 1 < nz && solid(cells[x][z + 1][y]);
					boolean hiddenEast = x + 1 < nx && solid(cells[x + 1][z][y]);
					if (hiddenTop && hiddenSouth && hiddenEast && solid(cell)) {
						continue;
					}
					int rgb = color(cell);
					int alpha = seeThrough(m) ? 120 : 255;
					for (double[] b : boxes(cell)) {
						face(g, ox, oy, x, y, z, rgb, alpha, 1.0, new double[][] { { b[0], b[4], b[2] }, { b[3], b[4], b[2] }, { b[3], b[4], b[5] }, { b[0], b[4], b[5] } });
						face(g, ox, oy, x, y, z, rgb, alpha, 0.80, new double[][] { { b[0], b[1], b[5] }, { b[3], b[1], b[5] }, { b[3], b[4], b[5] }, { b[0], b[4], b[5] } });
						face(g, ox, oy, x, y, z, rgb, alpha, 0.62, new double[][] { { b[3], b[1], b[2] }, { b[3], b[1], b[5] }, { b[3], b[4], b[5] }, { b[3], b[4], b[2] } });
					}
				}
			}
		}
		g.dispose();
		return image;
	}

	private static boolean solid(int cell) {
		Material m = Material.of(cell);
		if (m == Material.AIR || seeThrough(m) || m.slab() || m.stairs()) {
			return false;
		}
		double[][] parts = boxes(cell);
		return parts.length == 1 && parts[0][0] == 0 && parts[0][1] == 0 && parts[0][3] == 1 && parts[0][4] >= 0.94;
	}

	private static void face(Graphics2D g, double ox, double oy, int x, int y, int z, int rgb, int alpha, double shade, double[][] corners) {
		Polygon p = new Polygon();
		for (double[] c : corners) {
			double wx = x + c[0];
			double wy = y + c[1];
			double wz = z + c[2];
			p.addPoint((int) Math.round(ox + (wx - wz) * HW), (int) Math.round(oy + (wx + wz) * HH - wy * VH));
		}
		Color fill = new Color((int) (((rgb >> 16) & 0xFF) * shade), (int) (((rgb >> 8) & 0xFF) * shade), (int) ((rgb & 0xFF) * shade), alpha);
		g.setColor(fill);
		g.fillPolygon(p);
		g.setColor(new Color(0, 0, 0, alpha == 255 ? 46 : 20));
		g.drawPolygon(p);
	}

	/** Several pictures side by side, written as one PNG. */
	static void write(Path file, BufferedImage... images) throws IOException {
		int width = 0;
		int height = 0;
		for (BufferedImage image : images) {
			width += image.getWidth();
			height = Math.max(height, image.getHeight());
		}
		BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		g.setColor(new Color(0x1B2430));
		g.fillRect(0, 0, width, height);
		int x = 0;
		for (BufferedImage image : images) {
			g.drawImage(image, x, height - image.getHeight(), null);
			x += image.getWidth();
		}
		g.dispose();
		Files.createDirectories(file.getParent());
		ImageIO.write(sheet, "png", file.toFile());
	}
}
