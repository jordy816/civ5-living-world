package nl.jordythomassen.livingworld.coords;

import java.util.ArrayList;
import java.util.List;

/**
 * The Civ V map as a grid of pointy-top hexes in offset rows.
 *
 * <p>Civ stores plots as (column, row). Every other row is shifted half a hex sideways; which rows
 * (odd or even) and in which direction is measured from the running game, not assumed, and passed
 * in as {@code oddRowShift} (in hex widths, normally +0.5).
 */
public final class HexGrid {
	private final int width;
	private final int height;
	private final boolean wrapX;
	private final double oddRowShift;

	public HexGrid(int width, int height, boolean wrapX, double oddRowShift) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("map size " + width + "x" + height);
		}
		if (Math.abs(Math.abs(oddRowShift) - 0.5) > 1e-6) {
			throw new IllegalArgumentException("odd rows must be shifted by half a hex, got " + oddRowShift);
		}
		this.width = width;
		this.height = height;
		this.wrapX = wrapX;
		this.oddRowShift = oddRowShift;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public boolean wrapX() {
		return wrapX;
	}

	public double oddRowShift() {
		return oddRowShift;
	}

	/** Sideways shift of a row, in hex widths. */
	public double rowShift(int row) {
		return (row & 1) == 0 ? 0.0 : oddRowShift;
	}

	public boolean contains(PlotCoord plot) {
		return plot.y() >= 0 && plot.y() < height && (wrapX || (plot.x() >= 0 && plot.x() < width));
	}

	/** Brings the column back into range on maps that wrap east-west; null when the plot is off the map. */
	public PlotCoord normalize(PlotCoord plot) {
		if (plot.y() < 0 || plot.y() >= height) {
			return null;
		}
		if (plot.x() >= 0 && plot.x() < width) {
			return plot;
		}
		return wrapX ? new PlotCoord(Math.floorMod(plot.x(), width), plot.y()) : null;
	}

	public int index(PlotCoord plot) {
		return plot.y() * width + plot.x();
	}

	public GridPos center(PlotCoord plot) {
		return new GridPos(plot.x() + rowShift(plot.y()), plot.y());
	}

	/**
	 * The plot whose hexagon contains this position. The column is not wrapped or range-checked:
	 * use {@link #normalize} to learn whether it is on the map.
	 */
	public PlotCoord plotAt(GridPos pos) {
		// Fractional axial coordinates of a pointy-top hex grid whose row r is shifted r/2 east.
		// Rows shifted west instead are the same grid mirrored, so mirror u and mirror back after.
		double sign = oddRowShift > 0 ? 1.0 : -1.0;
		double r = pos.v();
		double q = sign * pos.u() - r / 2.0;
		// Cube rounding.
		double x = q;
		double z = r;
		double y = -x - z;
		long rx = Math.round(x);
		long ry = Math.round(y);
		long rz = Math.round(z);
		double dx = Math.abs(rx - x);
		double dy = Math.abs(ry - y);
		double dz = Math.abs(rz - z);
		if (dx > dy && dx > dz) {
			rx = -ry - rz;
		} else if (dy <= dz) {
			rz = -rx - ry;
		}
		int row = (int) rz;
		int axialQ = (int) rx;
		// Back to offset columns: u = q + r/2 at a centre, and u = col + shift(row).
		int col = axialQ + Math.floorDiv(row - (row & 1), 2);
		return new PlotCoord((int) (sign * col), row);
	}

	/** The up to six plots that share an edge with this one, wrapped where the map wraps. */
	public List<PlotCoord> neighbors(PlotCoord plot) {
		int x = plot.x();
		int y = plot.y();
		// The diagonal neighbours sit half a hex to each side: in a shifted row they are at
		// columns x and x+dir, in an unshifted row at x-dir and x.
		int dir = oddRowShift > 0 ? 1 : -1;
		boolean shifted = (y & 1) != 0;
		int a = shifted ? x : x - dir;
		int b = shifted ? x + dir : x;
		int[][] candidates = {
			{ x - 1, y }, { x + 1, y },
			{ a, y + 1 }, { b, y + 1 },
			{ a, y - 1 }, { b, y - 1 },
		};
		List<PlotCoord> out = new ArrayList<>(6);
		for (int[] c : candidates) {
			PlotCoord n = normalize(new PlotCoord(c[0], c[1]));
			if (n != null && !out.contains(n)) {
				out.add(n);
			}
		}
		return out;
	}

	/** Steps between two plots, taking the short way round on maps that wrap. */
	public int distance(PlotCoord a, PlotCoord b) {
		int best = rawDistance(a, b);
		if (wrapX) {
			best = Math.min(best, rawDistance(a, new PlotCoord(b.x() + width, b.y())));
			best = Math.min(best, rawDistance(a, new PlotCoord(b.x() - width, b.y())));
		}
		return best;
	}

	private int rawDistance(PlotCoord a, PlotCoord b) {
		int sign = oddRowShift > 0 ? 1 : -1;
		int aq = sign * a.x() - Math.floorDiv(a.y() - (a.y() & 1), 2);
		int bq = sign * b.x() - Math.floorDiv(b.y() - (b.y() & 1), 2);
		int dq = aq - bq;
		int dr = a.y() - b.y();
		return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
	}
}
