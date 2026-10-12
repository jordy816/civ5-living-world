package nl.jordythomassen.livingworld.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import nl.jordythomassen.livingworld.link.BootstrapChunks;
import org.junit.jupiter.api.Test;

class WireTest {
	@Test
	void fieldsSurviveTabsNewlinesAndBackslashes() {
		String nasty = "New\tYork\nCity\\ \r end";
		assertEquals(nasty, Wire.unescape(Wire.escape(nasty)));
		List<String> fields = Wire.parse(Wire.PREFIX + "T\t" + Wire.escape(nasty) + "\t5");
		assertEquals(List.of("T", nasty, "5"), fields);
	}

	@Test
	void linesThatAreNotOursAreIgnored() {
		assertNull(Wire.parse("Runtime Error: something else printed this"));
		assertNull(Wire.parse(""));
		assertNull(Wire.parse(null));
		assertNull(Wire.parse(Wire.PREFIX));
		assertNull(Wire.parse(Wire.PREFIX + "x".repeat(Wire.MAX_LINE)));
	}

	@Test
	void aPrefixInTheMiddleOfALineStillCounts() {
		// The game may put its own text (a state name, a timestamp) in front of printed output.
		assertEquals(List.of("B", "1"), Wire.parse("InGame: LW\tB\t1\r"));
	}

	@Test
	void luaStringsCannotBreakOutOfTheirQuotes() {
		assertEquals("\"a\\\"b\\\\c\\nd\"", Wire.luaString("a\"b\\c\nd"));
		assertEquals("\"J?rdy\"", Wire.luaString("Jördy"));
	}

	@Test
	void longStringsPickABracketLevelTheTextCannotClose() {
		assertEquals("[=[\nplain]=]", BootstrapChunks.longString("plain"));
		assertEquals("[==[\na]=]b]==]", BootstrapChunks.longString("a]=]b"));
		assertEquals("[==[\nends with ]=]==]", BootstrapChunks.longString("ends with ]="));
		String source = "x".repeat(7001);
		List<String> parts = BootstrapChunks.split(source, 3000);
		assertEquals(3, parts.size());
		assertEquals(source, String.join("", parts));
	}

	@Test
	void malformedRecordsAreRefusedNotGuessed() {
		assertThrows(Records.BadRecord.class, () -> Records.unit(List.of("U", "0", "1")));
		assertThrows(Records.BadRecord.class, () -> Records.heartbeat(List.of("S", "1", "b", "g", "x", "0", "0", "40", "25", "0", "0", "0", "0", "0", "1")));
		assertThrows(Records.BadRecord.class, () -> Records.heartbeat(List.of("S", "1", "b", "g", "1", "0", "0", "9999", "25", "0", "0", "0", "0", "0", "1")));
		assertThrows(Records.BadRecord.class, () -> Records.plotRow(List.of("P", "0", "0", "1,2,3")));
		assertThrows(Records.BadRecord.class, () -> Records.plotRow(List.of("P", "0", "0", "0,-1,7,-1,-1,-1,-1,0,0")));
		assertThrows(Records.BadRecord.class, () -> Records.geometry(List.of("G", "0", "0", "NaN", "0", "0", "0", "0", "0")));
	}

	@Test
	void geometryTellsWhichWayOddRowsShift() {
		assertEquals(0.5, new Geometry(0, 0, 64, 0, 32, 55.4256, 0, 110.8512).oddRowShift());
		assertEquals(-0.5, new Geometry(0, 0, 64, 0, -32, 55.4256, 0, 110.8512).oddRowShift());
		assertThrows(IllegalStateException.class, () -> new Geometry(0, 0, 64, 0, 10, 55, 0, 110).oddRowShift());
	}
}
