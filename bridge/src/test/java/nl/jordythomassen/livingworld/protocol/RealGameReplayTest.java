package nl.jordythomassen.livingworld.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.Legend;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.WorldState;
import org.junit.jupiter.api.Test;

/**
 * Replays what the real Civilization V (Brave New World, 1.0.3.279, Windows) printed for one full
 * snapshot: a duel-size Pangaea, Rome, turn 0, just after founding the capital. Captured through
 * the debug socket on 2026-10-09; nothing in it is hand-written.
 */
class RealGameReplayTest {
	@Test
	void aCaptureFromTheRealGameBuildsACompletePicture() throws IOException {
		StateAssembler assembler = new StateAssembler();
		int ours = 0;
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			RealGameReplayTest.class.getResourceAsStream("/nl/jordythomassen/livingworld/fixtures/real-duel-rome-turn0.txt"), StandardCharsets.UTF_8))) {
			for (String line; (line = in.readLine()) != null; ) {
				List<String> fields = Wire.parse(line);
				if (fields != null) {
					ours++;
					assembler.accept(fields);
				}
			}
		}
		assertTrue(ours > 30);
		assertEquals(List.of(), assembler.drainProblems());
		WorldState state = assembler.build();
		assertNotNull(state);
		assertEquals(40, state.session().grid().width());
		assertEquals(24, state.session().grid().height());
		assertTrue(state.session().grid().wrapX());
		assertEquals(0.5, state.session().grid().oddRowShift());
		assertEquals(64.0, state.session().civLayout().columnStep(), 0.01);
		assertEquals(55.4256, state.session().civLayout().rowStep(), 0.01);
		assertEquals("Rome", state.civ(0).name());
		assertEquals("Augustus Caesar", state.civ(0).leader());
		assertEquals(0x460076, state.civ(0).color());
		CityState rome = state.cities().values().iterator().next();
		assertEquals("Rome", rome.name());
		assertEquals(new PlotCoord(17, 15), rome.at());
		assertTrue(rome.capital());
		PlotState plot = state.plot(rome.at());
		assertTrue(plot.city());
		assertEquals(0, plot.owner());
		assertEquals("TERRAIN_DESERT", state.legend().type(Legend.Kind.TERRAIN, plot.terrain()));
		assertEquals(1, state.units().size(), "the Warrior; the Settler became the city");
		int revealed = 0;
		for (int i = 0; i < state.plotCount(); i++) {
			if (state.plot(i).revealed()) {
				revealed++;
			}
		}
		assertTrue(revealed > 20 && revealed < 200, "only what Rome has explored: " + revealed);
	}
}
