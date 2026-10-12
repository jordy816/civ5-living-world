package nl.jordythomassen.livingworld.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import nl.jordythomassen.livingworld.link.CivLink;
import nl.jordythomassen.livingworld.mock.MockCiv;
import nl.jordythomassen.livingworld.mock.MockTransport;
import nl.jordythomassen.livingworld.protocol.StateAssembler;
import nl.jordythomassen.livingworld.protocol.Wire;
import org.junit.jupiter.api.Test;

/** The picture of the game that goes to a player's own game for the view from above must arrive as it left. */
class StateCodecTest {
	private static WorldState realGame() throws IOException {
		StateAssembler assembler = new StateAssembler();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			StateCodecTest.class.getResourceAsStream("/nl/jordythomassen/livingworld/fixtures/real-duel-rome-turn0.txt"), StandardCharsets.UTF_8))) {
			for (String line; (line = in.readLine()) != null; ) {
				List<String> fields = Wire.parse(line);
				if (fields != null) {
					assembler.accept(fields);
				}
			}
		}
		return assembler.build();
	}

	private static WorldState mockGame() throws IOException {
		MockCiv civ = new MockCiv(40, 25, 7);
		CivLink link = new CivLink(() -> new MockTransport(civ), () -> "LivingWorld={VERSION=1}", s -> { });
		link.setRetryMs(0);
		for (int i = 0; i < 3; i++) {
			link.pumpOnce();
		}
		return link.state();
	}

	private static void same(WorldState a, WorldState b) {
		assertEquals(a.turn(), b.turn());
		assertEquals(a.session().gameId(), b.session().gameId());
		assertEquals(a.session().viewer(), b.session().viewer());
		assertEquals(a.session().grid().width(), b.session().grid().width());
		assertEquals(a.session().grid().height(), b.session().grid().height());
		assertEquals(a.civs(), b.civs());
		assertEquals(a.units(), b.units());
		assertEquals(a.cities(), b.cities());
		assertEquals(a.empire(), b.empire());
		assertEquals(a.techs(), b.techs());
		assertEquals(a.relations(), b.relations());
		assertEquals(a.plotCount(), b.plotCount());
		for (int i = 0; i < a.plotCount(); i++) {
			assertEquals(a.plot(i), b.plot(i), "plot " + i);
		}
		for (Legend.Kind kind : Legend.Kind.values()) {
			assertEquals(List.copyOf(a.legend().entries(kind)), List.copyOf(b.legend().entries(kind)), kind.name());
		}
	}

	@Test
	void aRecordedGameComesThroughUnchanged() throws IOException {
		WorldState state = realGame();
		byte[] data = StateCodec.write(state);
		same(state, StateCodec.read(data));
		// Small enough to send whenever something changes: a duel map is a few kilobytes.
		assertTrue(data.length < 60_000, data.length + " bytes");
	}

	@Test
	void aGameWithUnitsAndCitiesComesThroughUnchanged() throws IOException {
		WorldState state = mockGame();
		assertTrue(!state.units().isEmpty() && !state.cities().isEmpty());
		same(state, StateCodec.read(StateCodec.write(state)));
	}

	@Test
	void unitsStandWhereTheServerPutsThem() throws IOException {
		WorldState state = mockGame();
		double width = 32;
		var layout = new nl.jordythomassen.livingworld.coords.McLayout(width, -state.session().grid().width() * width / 2.0,
			state.session().grid().height() * (width * Math.sqrt(3.0) / 2.0) / 2.0);
		var model = new nl.jordythomassen.livingworld.board.BoardModel(state, layout);
		var spots = nl.jordythomassen.livingworld.board.UnitSpots.of(state, model);
		assertEquals(state.units().keySet(), spots.keySet());
		for (var entry : spots.entrySet()) {
			UnitState unit = state.units().get(entry.getKey());
			// Every unit stands on its own hex, within reach of its middle.
			assertEquals(unit.at(), model.plotAt((int) Math.floor(entry.getValue()[0]), (int) Math.floor(entry.getValue()[2])), unit.name());
		}
	}
}
