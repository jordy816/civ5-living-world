package nl.jordythomassen.livingworld.unciv;

import com.unciv.logic.map.HexCoord;
import com.unciv.logic.map.TileMap;
import com.unciv.logic.map.tile.Tile;
import java.util.HashMap;
import java.util.Map;
import nl.jordythomassen.livingworld.coords.HexGrid;
import nl.jordythomassen.livingworld.coords.PlotCoord;

/**
 * Unciv's hexes on our board. Unciv numbers its hexes along two slanted axes and draws them with a
 * flat side up; the board is rows of hexes with a point up, odd rows shifted half a hex. Turning
 * the map a quarter makes one into the other without changing who borders whom: an Unciv column
 * becomes a board row. For a rectangular Unciv map that fills the board's rectangle exactly.
 */
public final class UncivMap {
	private final int width;
	private final int height;
	private final int columnOffset;
	private final int rowOffset;
	private final int parity;
	private final boolean mirrored;
	private final Tile[] byIndex;
	private final Map<Tile, PlotCoord> plots = new HashMap<>();

	public UncivMap(TileMap tileMap) {
		int minRow = Integer.MAX_VALUE;
		int maxRow = Integer.MIN_VALUE;
		for (Tile tile : tileMap.getTileList()) {
			int row = tile.getPosition().getY() - tile.getPosition().getX();
			minRow = Math.min(minRow, row);
			maxRow = Math.max(maxRow, row);
		}
		rowOffset = minRow;
		// Board rows count from the lowest Unciv column. When that one is odd, odd and even swap,
		// and the half-hex shift has to go to the other set of rows.
		parity = Math.floorMod(minRow, 2);
		// The map may be laid out as it is or mirrored left to right; both keep every border. One of
		// the two has its rows staggered the way the board staggers them, and fits without gaps.
		int[] plain = columnRange(tileMap, false);
		int[] flipped = columnRange(tileMap, true);
		mirrored = flipped[1] - flipped[0] < plain[1] - plain[0];
		int[] range = mirrored ? flipped : plain;
		columnOffset = range[0];
		width = range[1] - range[0] + 1;
		height = maxRow - rowOffset + 1;
		byIndex = new Tile[width * height];
		for (Tile tile : tileMap.getTileList()) {
			PlotCoord plot = toPlot(tile.getPosition().getX(), tile.getPosition().getY());
			byIndex[plot.y() * width + plot.x()] = tile;
			plots.put(tile, plot);
		}
	}

	private int[] columnRange(TileMap tileMap, boolean mirror) {
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for (Tile tile : tileMap.getTileList()) {
			int column = rawColumn(tile.getPosition().getX(), tile.getPosition().getY(), mirror);
			min = Math.min(min, column);
			max = Math.max(max, column);
		}
		return new int[] { min, max };
	}

	private int rawColumn(int x, int y, boolean mirror) {
		return (mirror ? -y : x) + Math.floorDiv(y - x + parity, 2);
	}

	private PlotCoord toPlot(int x, int y) {
		return new PlotCoord(rawColumn(x, y, mirrored) - columnOffset, y - x - rowOffset);
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public HexGrid grid() {
		return new HexGrid(width, height, false, 0.5);
	}

	/** Where a tile lies on the board. */
	public PlotCoord plot(Tile tile) {
		return plots.get(tile);
	}

	/** The tile on a board plot, or null where the map has none (the corners of a round map). */
	public Tile tile(PlotCoord plot) {
		if (plot == null || plot.x() < 0 || plot.y() < 0 || plot.x() >= width || plot.y() >= height) {
			return null;
		}
		return byIndex[plot.y() * width + plot.x()];
	}

	public Tile tile(int index) {
		return byIndex[index];
	}

	public int index(PlotCoord plot) {
		return plot.y() * width + plot.x();
	}
}
