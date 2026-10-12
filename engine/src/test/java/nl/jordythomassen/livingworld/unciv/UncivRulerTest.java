package nl.jordythomassen.livingworld.unciv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import nl.jordythomassen.livingworld.commands.CommandOp;
import nl.jordythomassen.livingworld.commands.CommandStatus;
import nl.jordythomassen.livingworld.commands.CommandTicket;
import nl.jordythomassen.livingworld.commands.GameCommand;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.protocol.Records;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldEvent;
import nl.jordythomassen.livingworld.state.WorldState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What this mod adds to the game as Unciv has it: a civilization of its own, a city as a sheet of
 * numbers, news that says where it happened, and what a ruler does in person.
 */
class UncivRulerTest {
	private int counter;

	private UncivLink link(Path dir, String nation, long seed) throws IOException {
		UncivLink link = new UncivLink(dir.resolve("data"), dir.resolve("game.json"), new UncivLink.Setup("Tiny", "pangaea", 2, 0, "Prince", nation, seed, "Quick"), System.err::println);
		link.start();
		return link;
	}

	private CommandTicket send(UncivLink link, CommandOp op, EntityKey target, PlotCoord plot, int amount, String item) {
		WorldState state = link.state();
		String id = "r" + (++counter);
		CommandTicket ticket = link.submit(new GameCommand(id, id, state.session().bootId(), state.session().gameId(), "Steve", state.turn(), op, target, plot, amount, item));
		link.pump();
		return ticket;
	}

	private List<Records.Option> ask(UncivLink link, String kind, int a, int c) {
		AtomicReference<List<Records.Option>> answer = new AtomicReference<>();
		link.ask(kind, a, c, answer::set);
		link.pump();
		return answer.get();
	}

	private static EntityKey self(WorldState state) {
		return new EntityKey(state.session().viewer(), -1);
	}

	private CityState found(UncivLink link) {
		UnitState settler = link.state().units().values().stream().filter(u -> u.type().equals("UNIT_SETTLER")).findFirst().orElseThrow();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.UNIT_ACTION, settler.key(), null, 0, "FoundCity").status());
		int me = link.state().session().viewer();
		return link.state().cities().values().stream().filter(c -> c.key().owner() == me).findFirst().orElseThrow();
	}

	@Test
	void theShoshoneAreInTheGameWithTheirWideLand(@TempDir Path dir) throws IOException {
		UncivLink link = link(dir, "The Shoshone", 21L);
		assertEquals("The Shoshone", link.viewer().getCivName());
		CityState city = found(link);
		WorldState state = link.state();
		int land = 0;
		for (int index = 0; index < state.session().grid().width() * state.session().grid().height(); index++) {
			if (state.plot(new PlotCoord(index % state.session().grid().width(), index / state.session().grid().width())).owner() == state.session().viewer()) {
				land++;
			}
		}
		assertEquals(15, land, "a city of theirs begins with eight hexes more than the seven any city has");

		List<Records.Option> builds = ask(link, "build", city.key().id(), 0);
		assertTrue(builds.stream().anyMatch(o -> o.type().equals("UNIT_PATHFINDER")), "they train Pathfinders: " + builds);
		assertTrue(builds.stream().noneMatch(o -> o.type().equals("UNIT_SCOUT")), "in place of Scouts");

		Records.Option told = ask(link, "nations", 0, 0).stream().filter(o -> o.type().equals("The Shoshone")).findFirst().orElseThrow();
		assertTrue(told.name().startsWith("Pocatello\nGift: Great Expanse\n- "), told.name());
		assertTrue(told.name().contains("\nUnit: Pathfinder|Scout|8 Strength, 2 Movement"), told.name());
		assertTrue(told.name().contains("\nUnit: Comanche Riders|Cavalry|34 Strength, 5 Movement"), told.name());

		// And the game goes on with them in it: a few turns by the computer's own hand.
		link.simulate(8);
		link.pump();
		assertTrue(link.state().turn() >= 8, "turn " + link.state().turn());
	}

	@Test
	void civilizationsAreDescribedInPartsWithoutNotesForRuleWriters(@TempDir Path dir) throws IOException {
		UncivLink link = link(dir, "Rome", 21L);
		List<Records.Option> nations = ask(link, "nations", 0, 0);
		assertTrue(nations.size() >= 27, "Unciv's civilizations and one more: " + nations.size());
		for (Records.Option nation : nations) {
			String[] lines = nation.name().split("\n");
			assertTrue(lines.length >= 3, nation.type() + " has a leader, a gift and what it does: " + nation.name());
			assertTrue(lines[1].startsWith("Gift: "), nation.name());
			assertTrue(lines[2].startsWith("- "), nation.name());
			assertFalse(nation.name().contains("Suppress warning"), nation.name());
			assertFalse(nation.name().contains("[") || nation.name().contains("<"), nation.name());
			for (int i = 3; i < lines.length; i++) {
				assertTrue(lines[i].startsWith("- ") || lines[i].matches("(Unit|Building|Improvement): [^|]+\\|[^|]*\\|.*"), nation.type() + ": " + lines[i]);
			}
		}
		String polynesia = nations.stream().filter(o -> o.type().equals("Polynesia")).findFirst().orElseThrow().name();
		assertTrue(polynesia.contains("Gift: Wayfinding"), polynesia);
		assertTrue(polynesia.contains("\nUnit: Maori Warrior|Warrior|"), polynesia);
		assertTrue(polynesia.contains("\nImprovement: Moai||"), polynesia);
	}

	@Test
	void aCitySheetCountsDownWhatIsBuilt(@TempDir Path dir) throws IOException {
		UncivLink link = link(dir, "Rome", 21L);
		CityState city = found(link);
		send(link, CommandOp.RESEARCH, self(link.state()), null, 0, ask(link, "research", 0, 0).get(0).type());
		// The thing that takes longest, so that it is still being built a turn later.
		Records.Option slow = ask(link, "build", city.key().id(), 0).stream().max(java.util.Comparator.comparingInt(Records.Option::turns)).orElseThrow();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CITY_BUILD, city.key(), new PlotCoord(slow.group(), slow.id()), 0, slow.type()).status());
		int first = link.state().cities().get(city.key()).productionTurns();
		assertTrue(first >= 2, "it takes a few turns: " + first);

		List<Records.Option> sheet = ask(link, "citysheet", city.key().id(), 0);
		assertEquals(6, sheet.stream().filter(o -> o.type().equals("YIELD")).count(), "food, production, gold, science, culture, faith: " + sheet);
		Records.Option build = sheet.stream().filter(o -> o.type().equals("BUILD")).findFirst().orElseThrow();
		assertEquals(first, build.turns(), "the sheet and the banner say the same");
		assertTrue(build.group() > 0 && build.id() >= 0 && build.id() < build.group(), "work done of work needed: " + build);
		Records.Option grow = sheet.stream().filter(o -> o.type().equals("GROW")).findFirst().orElseThrow();
		assertTrue(grow.group() > 0, "food needed for the next citizen: " + grow);
		assertTrue(sheet.stream().anyMatch(o -> o.type().equals("DEFENCE") && o.id() > 0 && o.id() <= o.group() && o.turns() > 0), "its walls: " + sheet);
		assertTrue(sheet.stream().anyMatch(o -> o.type().equals("BUILDING") && o.name().startsWith("Palace|")), "the palace stands in it: " + sheet);

		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.END_TURN, self(link.state()), null, 0, "").status());
		CityState later = link.state().cities().get(city.key());
		assertEquals(slow.type(), later.productionType(), "still the same thing");
		assertTrue(later.productionTurns() < first, "a turn later it takes a turn less: " + first + " then " + later.productionTurns());
		Records.Option after = ask(link, "citysheet", city.key().id(), 0).stream().filter(o -> o.type().equals("BUILD")).findFirst().orElseThrow();
		assertEquals(later.productionTurns(), after.turns());
		assertTrue(after.id() > build.id(), "and more of the work is done: " + build.id() + " then " + after.id());
	}

	@Test
	void aRulerOnFootWoundsAnEnemyButAnArmyWinsTheWar(@TempDir Path dir) throws IOException {
		UncivLink link = link(dir, "Rome", 21L);
		int me = link.state().session().viewer();
		CityState city = found(link);
		var capital = link.viewer().getCities().get(0);
		var rival = link.game().getCivilizations().stream().filter(c -> c.isMajorCiv() && c != link.viewer()).findFirst().orElseThrow();
		link.viewer().getDiplomacyFunctions().makeCivilizationsMeet(rival, false);
		send(link, CommandOp.REVEAL, self(link.state()), city.at(), 1, "");
		int them = link.state().civs().values().stream().filter(c -> c.name().equals(rival.getCivName()) || c.type().contains(rival.getCivName().toUpperCase(java.util.Locale.ROOT))).findFirst()
			.orElseThrow(() -> new AssertionError(rival.getCivName() + " not among " + link.state().civs().values())).id();

		// At peace their soldiers are not to be touched.
		var peaceful = rival.getUnits().getCivUnits().iterator().next();
		EntityKey untouched = new EntityKey(them, peaceful.getId());
		assertTrue(ask(link, "foe", untouched.id(), untouched.owner()).isEmpty(), "no enemy yet");
		assertEquals(CommandStatus.REJECTED, send(link, CommandOp.CHOICE, untouched, null, 20, "strike:").status());
		assertEquals(100, peaceful.getHealth());

		link.viewer().getDiplomacyManager(rival).declareWar(new com.unciv.logic.civilization.diplomacy.DeclareWarReason(com.unciv.logic.civilization.diplomacy.WarType.DirectWar, null));
		var foe = link.game().getTileMap().placeUnitNearTile(capital.getCenterTile().getPosition(), "Warrior", rival, null);
		assertNotNull(foe);
		link.pump();
		send(link, CommandOp.REVEAL, self(link.state()), city.at(), 1, "");
		UnitState seen = link.state().units().values().stream().filter(u -> u.key().owner() != me && u.key().id() == foe.getId()).findFirst().orElseThrow();
		assertEquals(UncivActions.STRIKE_PER_UNIT, ask(link, "foe", seen.key().id(), seen.key().owner()).get(0).id());
		CommandTicket first = send(link, CommandOp.CHOICE, seen.key(), null, 20, "strike:");
		assertEquals(CommandStatus.CONFIRMED, first.status(), first.detail());
		assertEquals(80, foe.getHealth());
		assertEquals(80, link.state().units().get(seen.key()).hp(), "and everybody sees it");
		// More than a ruler's share is cut down to it; after that the unit keeps out of reach for the turn.
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CHOICE, seen.key(), null, 90, "strike:").status());
		assertEquals(100 - UncivActions.STRIKE_PER_UNIT, foe.getHealth());
		CommandTicket third = send(link, CommandOp.CHOICE, seen.key(), null, 20, "strike:");
		assertEquals(CommandStatus.REJECTED, third.status(), third.detail());
		assertEquals(0, ask(link, "foe", seen.key().id(), seen.key().owner()).get(0).id());

		// A unit at its last falls, and what it carried is the ruler's.
		var weak = link.game().getTileMap().placeUnitNearTile(capital.getCenterTile().getPosition(), "Warrior", rival, null);
		assertNotNull(weak);
		weak.setHealth(15);
		link.pump();
		send(link, CommandOp.REVEAL, self(link.state()), city.at(), 1, "");
		EntityKey weakKey = link.state().units().values().stream().filter(u -> u.key().owner() != me && u.key().id() == weak.getId()).findFirst().orElseThrow().key();
		int purse = link.state().empire().gold();
		CommandTicket kill = send(link, CommandOp.CHOICE, weakKey, null, 20, "strike:");
		assertEquals(CommandStatus.CONFIRMED, kill.status(), kill.detail());
		assertFalse(link.state().units().containsKey(weakKey), "it is gone");
		assertEquals(purse + 9, link.state().empire().gold(), "a Warrior of strength 8 carried 9 gold");

		// Falling costs a tenth of the treasury, never more than a hundred.
		link.viewer().addGold(500 - link.viewer().getGold());
		link.pump();
		CommandTicket fell = send(link, CommandOp.CHOICE, self(link.state()), null, 0, "ruler:fell");
		assertEquals(CommandStatus.CONFIRMED, fell.status(), fell.detail());
		assertEquals(450, link.state().empire().gold());
		link.viewer().addGold(5000);
		link.pump();
		send(link, CommandOp.CHOICE, self(link.state()), null, 0, "ruler:fell");
		assertEquals(5350, link.state().empire().gold());

		// What a ruler buys for themselves is paid from the treasury, when it holds that much.
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CHOICE, self(link.state()), null, 350, "pay:Iron arms").status());
		assertEquals(5000, link.state().empire().gold());
		assertEquals(CommandStatus.REJECTED, send(link, CommandOp.CHOICE, self(link.state()), null, 5000 + 1, "pay:The moon").status());
		link.viewer().addGold(-4990);
		link.pump();
		assertEquals(CommandStatus.REJECTED, send(link, CommandOp.CHOICE, self(link.state()), null, 50, "pay:Iron arms").status());
		assertEquals(10, link.state().empire().gold());
	}

	@Test
	void newsSaysWhereItHappenedAndWhatKindItIs(@TempDir Path dir) throws IOException {
		UncivLink link = link(dir, "Rome", 21L);
		CityState city = found(link);
		link.drainEvents();
		link.viewer().addNotification("[Rome] is under attack!", link.viewer().getCities().get(0).getCenterTile().getPosition(),
			com.unciv.logic.civilization.Notification.NotificationCategory.War, "OtherIcons/Pillage");
		link.viewer().addNotification("A trade has ended", com.unciv.logic.civilization.Notification.NotificationCategory.Trade);
		link.pump();
		send(link, CommandOp.REVEAL, self(link.state()), city.at(), 1, "");
		WorldEvent.Notice war = null;
		WorldEvent.Notice trade = null;
		for (WorldEvent event : link.drainEvents()) {
			if (event instanceof WorldEvent.Notice notice && notice.text().contains("under attack")) {
				war = notice;
			}
			if (event instanceof WorldEvent.Notice notice && notice.text().contains("trade has ended")) {
				trade = notice;
			}
		}
		assertNotNull(war, "the news is told");
		assertEquals("Rome is under attack!", war.text());
		assertEquals("war", war.kind());
		assertEquals(city.at(), war.at(), "at the city it is about");
		assertNotNull(trade);
		assertEquals("trade", trade.kind());
		assertEquals(null, trade.at(), "news without a place has none");
	}
}
