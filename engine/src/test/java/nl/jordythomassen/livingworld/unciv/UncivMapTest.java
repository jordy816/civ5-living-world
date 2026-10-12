package nl.jordythomassen.livingworld.unciv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.unciv.logic.GameInfo;
import com.unciv.logic.map.MapShape;
import com.unciv.logic.map.tile.Tile;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import nl.jordythomassen.livingworld.coords.HexGrid;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import org.junit.jupiter.api.Test;

class UncivMapTest {
	@Test
	void everyUncivNeighbourIsANeighbourOnTheBoardAndTheOtherWayRound() throws IOException {
		for (String shape : new String[] { MapShape.rectangular, MapShape.hexagonal }) {
			GameInfo game = TestGames.start(7L, shape);
			UncivMap map = new UncivMap(game.getTileMap());
			HexGrid grid = map.grid();
			int tiles = 0;
			for (Tile tile : game.getTileMap().getTileList()) {
				tiles++;
				PlotCoord plot = map.plot(tile);
				assertNotNull(plot);
				assertEquals(tile, map.tile(plot), "a plot leads back to its tile");
				Set<PlotCoord> expected = new HashSet<>();
				for (Iterator<Tile> it = tile.getNeighbors().iterator(); it.hasNext(); ) {
					expected.add(map.plot(it.next()));
				}
				Set<PlotCoord> onBoard = new HashSet<>();
				for (PlotCoord near : grid.neighbors(plot)) {
					if (map.tile(near) != null) {
						onBoard.add(near);
					}
				}
				assertEquals(expected, onBoard, shape + " map, tile " + tile.getPosition() + " at " + plot);
			}
			if (shape.equals(MapShape.rectangular)) {
				assertEquals(map.width() * map.height(), tiles, "a rectangular map fills the board: " + map.width() + "x" + map.height());
			}
			assertTrue(tiles > 100);
		}
	}
}
