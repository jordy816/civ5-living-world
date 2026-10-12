package nl.jordythomassen.livingworld.unciv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.unciv.logic.GameInfo;
import com.unciv.logic.civilization.Civilization;
import com.unciv.logic.map.MapShape;
import java.io.IOException;
import nl.jordythomassen.livingworld.board.BoardModel;
import nl.jordythomassen.livingworld.coords.McLayout;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldState;
import org.junit.jupiter.api.Test;

class UncivSnapshotTest {
	private static Civilization human(GameInfo game) {
		return game.getCivilizations().stream().filter(Civilization::isHuman).findFirst().orElseThrow();
	}

	@Test
	void aNewGameIsSeenThroughTheFogOfItsOnePlayer() throws IOException {
		GameInfo game = TestGames.start(11L, MapShape.rectangular);
		UncivSnapshot snapshot = new UncivSnapshot(game, new UncivMap(game.getTileMap()), "test");
		Civilization me = human(game);
		WorldState state = snapshot.take(me);
		assertEquals(0, state.turn());
		assertEquals(snapshot.civId(me), state.session().viewer());
		CivInfo mine = state.civ(state.session().viewer());
		assertEquals(me.getCivName(), mine.name());
		assertTrue(mine.human());
		assertEquals("ERA_ANCIENT", mine.era());
		// Two units to start with, both ours, both where the fog has lifted.
		assertEquals(2, state.units().size(), state.units().toString());
		int known = 0;
		int unknown = 0;
		for (int i = 0; i < state.plotCount(); i++) {
			if (state.plot(i).revealed()) {
				known++;
			} else {
				unknown++;
			}
		}
		assertTrue(known > 6 && unknown > known, known + " known, " + unknown + " unknown");
		for (UnitState unit : state.units().values()) {
			assertEquals(state.session().viewer(), unit.key().owner());
			assertTrue(state.plot(unit.at()).visible());
			assertNotNull(snapshot.unit(unit.key()), "a unit can be found again by its key");
		}
		// Nobody has been met, so nobody's name is given away.
		for (CivInfo civ : state.civs().values()) {
			if (civ.id() != mine.id() && !civ.barbarian()) {
				assertFalse(civ.metByViewer(), civ.name());
				assertEquals("Unmet civilization", civ.name());
			}
		}
		assertTrue(state.knows("AGRICULTURE"));
		assertEquals("UNITS", state.empire().blocking(), "the two units wait for their first orders; nothing else is to decide yet");
	}

	@Test
	void theBoardCanBeDrawnFromAnUncivGameAtEveryColumn() throws IOException {
		GameInfo game = TestGames.start(12L, MapShape.rectangular);
		// Let the AI play, so there are cities, borders, roads and improvements to draw.
		for (int i = 0; i < 40; i++) {
			game.nextTurn(null, false);
		}
		UncivSnapshot snapshot = new UncivSnapshot(game, new UncivMap(game.getTileMap()), "test");
		// Seen by an AI that has a city, so the picture has one.
		Civilization viewer = game.getCivilizations().stream().filter(c -> c.isMajorCiv() && !c.getCities().isEmpty()).findFirst().orElseThrow();
		WorldState state = snapshot.take(viewer);
		assertFalse(state.cities().isEmpty());
		assertTrue(state.cities().values().stream().anyMatch(c -> c.details() && c.buildings().contains("PALACE")), state.cities().toString());
		double hexWidth = 32;
		BoardModel board = new BoardModel(state, new McLayout(hexWidth, -state.session().grid().width() * hexWidth / 2.0,
			-state.session().grid().height() * hexWidth * Math.sqrt(3) / 4.0));
		int[] bounds = board.bounds();
		int[] column = new int[BoardModel.TOP - BoardModel.BASE];
		int drawn = 0;
		for (int x = bounds[0]; x <= bounds[2]; x += 3) {
			for (int z = bounds[1]; z <= bounds[3]; z += 3) {
				if (board.column(x, z, column) != BoardModel.NOTHING) {
					drawn++;
				}
			}
		}
		assertTrue(drawn > 5000, drawn + " columns drawn");
		// Rivers of the Unciv map arrive on the board.
		int rivers = 0;
		int owned = 0;
		for (int i = 0; i < state.plotCount(); i++) {
			PlotState plot = state.plot(i);
			rivers += Integer.bitCount(plot.rivers());
			owned += plot.owner() >= 0 ? 1 : 0;
		}
		assertTrue(owned > 0, "borders");
		System.out.println("river edges in view: " + rivers + ", owned plots: " + owned + ", cities: " + state.cities().size() + ", units: " + state.units().size());
	}
}
