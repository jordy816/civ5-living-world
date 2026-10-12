package nl.jordythomassen.livingworld.coords;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class HexGridTest {
	private static final double[] SHIFTS = { 0.5, -0.5 };

	private static double realDistance(GridPos a, GridPos b) {
		double dx = a.u() - b.u();
		double dy = (a.v() - b.v()) * Math.sqrt(3.0) / 2.0;
		return Math.sqrt(dx * dx + dy * dy);
	}

	@Test
	void centreRoundTripsForEveryPlotIncludingNegativeAndEdgeCoordinates() {
		for (double shift : SHIFTS) {
			HexGrid grid = new HexGrid(40, 25, false, shift);
			for (int y = -7; y < 32; y++) {
				for (int x = -9; x < 49; x++) {
					PlotCoord plot = new PlotCoord(x, y);
					assertEquals(plot, grid.plotAt(grid.center(plot)), "shift " + shift);
				}
			}
		}
	}

	@Test
	void anyPointMapsToTheNearestCentre() {
		Random random = new Random(42);
		for (double shift : SHIFTS) {
			HexGrid grid = new HexGrid(40, 25, false, shift);
			for (int i = 0; i < 20000; i++) {
				GridPos pos = new GridPos(random.nextDouble() * 50 - 5, random.nextDouble() * 35 - 5);
				PlotCoord found = grid.plotAt(pos);
				double best = realDistance(pos, grid.center(found));
				for (int dy = -2; dy <= 2; dy++) {
					for (int dx = -2; dx <= 2; dx++) {
						PlotCoord other = new PlotCoord(found.x() + dx, found.y() + dy);
						assertTrue(realDistance(pos, grid.center(other)) >= best - 1e-9,
							"shift " + shift + ": " + pos + " is closer to " + other + " than to " + found);
					}
				}
			}
		}
	}

	@Test
	void neighboursAreTheSixPlotsExactlyOneHexAway() {
		for (double shift : SHIFTS) {
			HexGrid grid = new HexGrid(40, 25, false, shift);
			for (PlotCoord plot : List.of(new PlotCoord(10, 10), new PlotCoord(10, 11))) {
				List<PlotCoord> neighbors = grid.neighbors(plot);
				assertEquals(6, neighbors.size());
				for (PlotCoord n : neighbors) {
					assertEquals(1.0, realDistance(grid.center(plot), grid.center(n)), 1e-9, "shift " + shift + " " + plot + "->" + n);
					assertEquals(1, grid.distance(plot, n));
				}
			}
		}
	}

	@Test
	void cornerPlotsHaveFewerNeighboursWithoutWrap() {
		HexGrid grid = new HexGrid(40, 25, false, 0.5);
		assertEquals(2, grid.neighbors(new PlotCoord(0, 0)).size());
		assertEquals(3, grid.neighbors(new PlotCoord(39, 0)).size());
		// On the west border an unshifted row loses three neighbours, a shifted row only one.
		assertEquals(3, grid.neighbors(new PlotCoord(0, 10)).size());
		assertEquals(5, grid.neighbors(new PlotCoord(0, 11)).size());
	}

	@Test
	void wrappingMapsJoinEastAndWest() {
		HexGrid grid = new HexGrid(40, 25, true, 0.5);
		assertEquals(6, grid.neighbors(new PlotCoord(0, 10)).size());
		assertTrue(grid.neighbors(new PlotCoord(0, 10)).contains(new PlotCoord(39, 10)));
		assertTrue(grid.neighbors(new PlotCoord(39, 11)).contains(new PlotCoord(0, 12)));
		assertEquals(1, grid.distance(new PlotCoord(0, 10), new PlotCoord(39, 10)));
		assertEquals(4, grid.distance(new PlotCoord(2, 3), new PlotCoord(38, 3)));
		assertEquals(new PlotCoord(39, 4), grid.normalize(new PlotCoord(-1, 4)));
		assertEquals(new PlotCoord(1, 4), grid.normalize(new PlotCoord(41, 4)));
		assertNull(grid.normalize(new PlotCoord(3, 25)));
	}

	@Test
	void nonWrappingMapsEndAtTheirBorders() {
		HexGrid grid = new HexGrid(40, 25, false, 0.5);
		assertNull(grid.normalize(new PlotCoord(-1, 4)));
		assertNull(grid.normalize(new PlotCoord(40, 4)));
		assertFalse(grid.contains(new PlotCoord(0, -1)));
		assertEquals(39, grid.distance(new PlotCoord(0, 10), new PlotCoord(39, 10)));
	}

	@Test
	void distanceMatchesStepsAlongRowsAndDiagonals() {
		for (double shift : SHIFTS) {
			HexGrid grid = new HexGrid(80, 52, false, shift);
			assertEquals(0, grid.distance(new PlotCoord(5, 5), new PlotCoord(5, 5)));
			assertEquals(7, grid.distance(new PlotCoord(5, 5), new PlotCoord(12, 5)));
			// Walk a path of neighbours and check the distance never exceeds the step count.
			PlotCoord start = new PlotCoord(20, 20);
			PlotCoord at = start;
			for (int step = 1; step <= 10; step++) {
				at = grid.neighbors(at).get(step % 6);
				assertTrue(grid.distance(start, at) <= step);
			}
		}
	}

	@Test
	void differentMapSizesKeepTheSameGeometry() {
		for (int[] size : new int[][] { { 40, 25 }, { 56, 36 }, { 66, 42 }, { 80, 52 }, { 104, 64 }, { 128, 80 } }) {
			HexGrid grid = new HexGrid(size[0], size[1], true, 0.5);
			PlotCoord last = new PlotCoord(size[0] - 1, size[1] - 1);
			assertEquals(last, grid.plotAt(grid.center(last)));
			assertEquals(size[0] * size[1] - 1, grid.index(last));
		}
	}

	@Test
	void rimFractionIsZeroAtTheCentreAndOneOnEveryEdge() {
		HexGrid grid = new HexGrid(40, 25, false, 0.5);
		PlotCoord plot = new PlotCoord(10, 11);
		GridPos centre = grid.center(plot);
		assertEquals(0.0, HexMetric.rimFraction(centre, centre), 1e-12);
		for (HexEdge edge : HexEdge.values()) {
			GridPos mid = new GridPos(centre.u() + edge.du / 2.0, centre.v() + edge.dv / 2.0);
			assertEquals(1.0, HexMetric.rimFraction(mid, centre), 1e-9, edge.name());
			GridPos inside = new GridPos(centre.u() + edge.du * 0.4, centre.v() + edge.dv * 0.4);
			assertEquals(edge, HexMetric.nearestEdge(inside, centre));
			assertEquals(plot, grid.plotAt(inside));
			GridPos beyond = new GridPos(centre.u() + edge.du * 0.6, centre.v() + edge.dv * 0.6);
			assertEquals(1, grid.distance(plot, grid.plotAt(beyond)), edge.name());
		}
	}

	@Test
	void minecraftLayoutRoundTripsAndKeepsNorthNorth() {
		McLayout layout = new McLayout(32.0, 100.0, -50.0);
		GridPos pos = new GridPos(12.25, 7.5);
		GridPos back = layout.toGrid(layout.x(pos), layout.z(pos));
		assertEquals(pos.u(), back.u(), 1e-9);
		assertEquals(pos.v(), back.v(), 1e-9);
		assertTrue(layout.z(new GridPos(0, 5)) < layout.z(new GridPos(0, 4)), "a higher Civ row is further north (smaller z)");
		assertEquals(32.0, layout.x(new GridPos(1, 0)) - layout.x(new GridPos(0, 0)), 1e-9);
		// Regular hexagons: neighbours in the next row are exactly one hex width away.
		HexGrid grid = new HexGrid(40, 25, false, 0.5);
		GridPos a = grid.center(new PlotCoord(3, 3));
		GridPos b = grid.center(new PlotCoord(3, 4));
		assertEquals(32.0, Math.hypot(layout.x(a) - layout.x(b), layout.z(a) - layout.z(b)), 1e-9);
	}

	@Test
	void changingTheScaleMovesBlocksButNotPlots() {
		HexGrid grid = new HexGrid(40, 25, false, 0.5);
		PlotCoord plot = new PlotCoord(17, 9);
		for (double width : new double[] { 16, 32, 64 }) {
			McLayout layout = new McLayout(width, 0, 0);
			GridPos centre = grid.center(plot);
			assertEquals(plot, grid.plotAt(layout.toGrid(layout.x(centre) + width * 0.3, layout.z(centre) + width * 0.2)));
		}
	}

	@Test
	void civWorldLayoutIsAScaleAndOffsetOfTheSameSpace() {
		CivWorldLayout civ = new CivWorldLayout(-1234.5, 800.0, 64.0, 55.4256);
		GridPos pos = new GridPos(4.75, 9.1);
		GridPos back = civ.toGrid(civ.x(pos), civ.y(pos));
		assertEquals(pos.u(), back.u(), 1e-9);
		assertEquals(pos.v(), back.v(), 1e-9);
	}
}
