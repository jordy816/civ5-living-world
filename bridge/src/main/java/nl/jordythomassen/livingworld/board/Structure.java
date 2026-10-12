package nl.jordythomassen.livingworld.board;

import java.util.Arrays;

/**
 * A small building as a block of cells around its own middle: x and z run from -r to r, y from
 * 0 (the ground block it stands on) upward. A cell that was never set leaves the board as it is.
 * Buildings are designed with their front to the south and turned to face their street.
 */
final class Structure {
	/** Not part of the building: whatever the board has there stays. */
	static final int KEEP = -1;

	final int rx;
	final int rz;
	final int height;
	private final int[] cells;

	Structure(int rx, int rz, int height) {
		this.rx = rx;
		this.rz = rz;
		this.height = height;
		cells = new int[(2 * rx + 1) * (2 * rz + 1) * height];
		Arrays.fill(cells, KEEP);
	}

	boolean covers(int lx, int lz) {
		return Math.abs(lx) <= rx && Math.abs(lz) <= rz;
	}

	private int index(int lx, int y, int lz) {
		return ((lx + rx) * (2 * rz + 1) + (lz + rz)) * height + y;
	}

	int at(int lx, int y, int lz) {
		return covers(lx, lz) && y >= 0 && y < height ? cells[index(lx, y, lz)] : KEEP;
	}

	Structure set(int lx, int y, int lz, int cell) {
		if (covers(lx, lz) && y >= 0 && y < height) {
			cells[index(lx, y, lz)] = cell;
		}
		return this;
	}

	Structure set(int lx, int y, int lz, Material material) {
		return set(lx, y, lz, material.ordinal());
	}

	Structure set(int lx, int y, int lz, Material material, int shape) {
		return set(lx, y, lz, Material.cell(material, 0, shape));
	}

	/** Fills a box, corners included. */
	Structure box(int x0, int y0, int z0, int x1, int y1, int z1, int cell) {
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
			for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
				for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
					set(x, y, z, cell);
				}
			}
		}
		return this;
	}

	Structure box(int x0, int y0, int z0, int x1, int y1, int z1, Material material) {
		return box(x0, y0, z0, x1, y1, z1, material.ordinal());
	}

	/** The four walls of a box, without floor or roof. */
	Structure walls(int x0, int y0, int z0, int x1, int y1, int z1, Material material) {
		box(x0, y0, z0, x1, y1, z0, material);
		box(x0, y0, z1, x1, y1, z1, material);
		box(x0, y0, z0, x0, y1, z1, material);
		box(x1, y0, z0, x1, y1, z1, material);
		return this;
	}

	/**
	 * A pitched roof over the rectangle, rising from its two long edges to a ridge. The ridge
	 * runs along x ({@code alongX}) or along z; {@code y} is the height of the lowest row.
	 * {@code roof} is a stair (a tiled or shingled roof, one block up per block in) or any full
	 * block (thatch, laid in steps). {@code ridge} caps an odd span; {@code gable} fills the two
	 * triangular end walls from {@code inset} blocks inside the roof's ends, or is null.
	 */
	Structure gable(int x0, int x1, int z0, int z1, int y, boolean alongX, Material roof, int ridge, Material gable, int inset) {
		int span = alongX ? z1 - z0 + 1 : x1 - x0 + 1;
		int from = alongX ? x0 : z0;
		int to = alongX ? x1 : z1;
		for (int i = 0; i < (span + 1) / 2; i++) {
			boolean top = span % 2 == 1 && i == span / 2;
			int low = (alongX ? z0 : x0) + i;
			int high = (alongX ? z1 : x1) - i;
			for (int along = from; along <= to; along++) {
				if (top) {
					put(alongX, along, y + i, low, ridge);
				} else if (roof.stairs()) {
					// Each side rises towards the ridge: its high back is on the inside.
					put(alongX, along, y + i, low, Material.cell(roof, 0, alongX ? Material.SOUTH : Material.EAST));
					put(alongX, along, y + i, high, Material.cell(roof, 0, alongX ? Material.NORTH : Material.WEST));
				} else {
					put(alongX, along, y + i, low, roof.ordinal());
					put(alongX, along, y + i, high, roof.ordinal());
				}
				boolean end = along == from + inset || along == to - inset;
				if (gable != null && end) {
					for (int fill = y; fill < y + i; fill++) {
						put(alongX, along, fill, low, gable.ordinal());
						put(alongX, along, fill, high, gable.ordinal());
					}
				}
			}
		}
		return this;
	}

	private void put(boolean alongX, int along, int y, int across, int cell) {
		if (alongX) {
			set(along, y, across, cell);
		} else {
			set(across, y, along, cell);
		}
	}

	/** The same building turned clockwise (seen from above) by a number of quarter turns. */
	Structure turned(int quarterTurns) {
		int turns = Math.floorMod(quarterTurns, 4);
		if (turns == 0) {
			return this;
		}
		Structure out = new Structure(rz, rx, height);
		for (int x = -rx; x <= rx; x++) {
			for (int z = -rz; z <= rz; z++) {
				for (int y = 0; y < height; y++) {
					int cell = cells[index(x, y, z)];
					if (cell != KEEP) {
						// North goes to east: (x, z) becomes (-z, x).
						out.set(-z, y, x, turn(cell));
					}
				}
			}
		}
		return out.turned(turns - 1);
	}

	private static int turn(int cell) {
		Material material = Material.of(cell);
		int shape = Material.shape(cell);
		if (material.stairs()) {
			shape = (shape & Material.UPSIDE_DOWN) | ((shape & 3) + 1) % 4;
		} else if (material == Material.BANNER) {
			shape = (shape + 1) % 4;
		} else if (shape == Material.ALONG_X || shape == Material.ALONG_Z) {
			if (material.name().startsWith("LOG") || material == Material.STRIPPED_LOG || material == Material.QUARTZ_PILLAR || material == Material.HAY) {
				shape = shape == Material.ALONG_X ? Material.ALONG_Z : Material.ALONG_X;
			}
		}
		return Material.cell(material, Material.dye(cell), shape);
	}

	/**
	 * Writes the building's cells in one column into a column of the board, its ground block at
	 * height {@code ground}. Returns the height of the topmost block written, or
	 * {@link BoardModel#NOTHING} when the building has nothing in this column.
	 */
	int into(int lx, int lz, int[] out, int ground) {
		if (!covers(lx, lz)) {
			return BoardModel.NOTHING;
		}
		int top = BoardModel.NOTHING;
		int base = index(lx, 0, lz);
		for (int y = 0; y < height; y++) {
			int cell = cells[base + y];
			int at = ground + y - BoardModel.BASE;
			if (cell != KEEP && at >= 0 && at < out.length) {
				out[at] = cell;
				if (Material.of(cell) != Material.AIR) {
					top = ground + y;
				}
			}
		}
		return top;
	}

	/** Writes the building's cells in one column into a column of the board. */
	boolean into(int lx, int lz, int[] out) {
		if (!covers(lx, lz)) {
			return false;
		}
		boolean any = false;
		int base = index(lx, 0, lz);
		for (int y = 0; y < height; y++) {
			int cell = cells[base + y];
			int at = BoardModel.LAND + y - BoardModel.BASE;
			if (cell != KEEP && at >= 0 && at < out.length) {
				out[at] = cell;
				any = true;
			}
		}
		return any;
	}
}
