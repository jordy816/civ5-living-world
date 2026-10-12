package nl.jordythomassen.livingworld.unciv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import nl.jordythomassen.livingworld.commands.CommandOp;
import nl.jordythomassen.livingworld.commands.CommandStatus;
import nl.jordythomassen.livingworld.commands.CommandTicket;
import nl.jordythomassen.livingworld.commands.GameCommand;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.link.LinkStatus;
import nl.jordythomassen.livingworld.protocol.Records;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldEvent;
import nl.jordythomassen.livingworld.state.WorldState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Several people in one game, each with a civilization of their own. */
class UncivSeatsTest {
	private int counter;

	private UncivLink two(Path dir) throws IOException {
		UncivLink link = new UncivLink(dir.resolve("data"), dir.resolve("game.json"),
			new UncivLink.Setup("Tiny", "pangaea", 1, 2, "Prince", "Rome", 21L, "Quick", List.of("Greece"), Set.of(), List.of()), System.err::println);
		link.start();
		return link;
	}

	private CommandTicket send(UncivLink link, int civ, CommandOp op, EntityKey target, PlotCoord plot, int amount, String item) {
		WorldState state = link.state(civ);
		String id = "s" + (++counter);
		CommandTicket ticket = link.submit(civ, new GameCommand(id, id, state.session().bootId(), state.session().gameId(), "player" + civ, state.turn(), op, target, plot, amount, item));
		link.pump();
		return ticket;
	}

	private CommandTicket endTurn(UncivLink link, int civ) {
		return send(link, civ, CommandOp.END_TURN, new EntityKey(civ, -1), null, 0, "");
	}

	private List<Records.Option> ask(UncivLink link, int civ, String kind, int a, int c) {
		AtomicReference<List<Records.Option>> answer = new AtomicReference<>();
		link.ask(civ, kind, a, c, answer::set);
		link.pump();
		return answer.get();
	}

	private static UnitState unit(WorldState state, int civ, String type) {
		return state.units().values().stream().filter(u -> u.key().owner() == civ && u.type().equals(type)).findFirst().orElseThrow();
	}

	private static long known(WorldState state) {
		long count = 0;
		var grid = state.session().grid();
		for (int x = 0; x < grid.width(); x++) {
			for (int y = 0; y < grid.height(); y++) {
				count += state.plot(new PlotCoord(x, y)).revealed() ? 1 : 0;
			}
		}
		return count;
	}

	/** Settles every decision a turn waits for, the way a hurried player would. */
	private void decide(UncivLink link, int civ) {
		for (int guard = 0; guard < 12; guard++) {
			String blocking = link.state(civ).empire().blocking();
			if (blocking.isEmpty() || blocking.equals("UNITS")) {
				return;
			}
			switch (blocking) {
				case "RESEARCH" -> send(link, civ, CommandOp.RESEARCH, new EntityKey(civ, -1), null, 0, ask(link, civ, "research", 0, 0).get(0).type());
				case "POLICY" -> send(link, civ, CommandOp.POLICY, new EntityKey(civ, -1), null, 0, ask(link, civ, "policy", 0, 0).get(0).type());
				case "PRODUCTION" -> {
					for (var city : link.state(civ).cities().values()) {
						if (city.key().owner() == civ) {
							List<Records.Option> can = ask(link, civ, "build", city.key().id(), 0);
							send(link, civ, CommandOp.CITY_BUILD, city.key(), null, 0, can.get(0).type());
						}
					}
				}
				case "RELIGION" -> send(link, civ, CommandOp.BELIEF, new EntityKey(civ, -1), null, 0, ask(link, civ, "beliefs", 0, 0).get(1).type());
				default -> throw new AssertionError("a decision nobody makes here: " + blocking);
			}
		}
	}

	@Test
	void twoPeopleMoveAtOnceAndTheTurnPassesWhenBothHaveEndedTheirs(@TempDir Path dir) throws IOException {
		UncivLink link = two(dir);
		List<Integer> seats = link.seats();
		assertEquals(2, seats.size(), "two civilizations are ruled by people");
		int rome = seats.get(0);
		int greece = seats.get(1);
		assertEquals("Rome", link.state(rome).civ(rome).name());
		assertEquals("Greece", link.state(greece).civ(greece).name());
		assertEquals(rome, link.state(rome).session().viewer());
		assertEquals(greece, link.state(greece).session().viewer());
		// Each sees their own land and people, not the other's.
		assertTrue(link.state(rome).units().keySet().stream().noneMatch(k -> k.owner() == greece), "Rome does not see the Greeks");
		assertTrue(link.state(greece).units().keySet().stream().noneMatch(k -> k.owner() == rome), "nor Greece the Romans");
		// The world as it is built holds both.
		assertTrue(link.state().units().keySet().stream().anyMatch(k -> k.owner() == rome) && link.state().units().keySet().stream().anyMatch(k -> k.owner() == greece));
		long romeKnows = known(link.state(rome));
		long bothKnow = known(link.state());
		assertTrue(bothKnow > romeKnows, "the built world is what either has explored");
		assertTrue(link.mayAct(rome) && link.mayAct(greece), "both may act");

		// Both found their capital in the same turn.
		assertEquals(CommandStatus.CONFIRMED, send(link, rome, CommandOp.UNIT_ACTION, unit(link.state(rome), rome, "UNIT_SETTLER").key(), null, 0, "FoundCity").status());
		assertEquals(CommandStatus.CONFIRMED, send(link, greece, CommandOp.UNIT_ACTION, unit(link.state(greece), greece, "UNIT_SETTLER").key(), null, 0, "FoundCity").status());
		assertEquals(1, link.state(rome).cities().values().stream().filter(c -> c.key().owner() == rome).count());
		assertEquals(1, link.state(greece).cities().values().stream().filter(c -> c.key().owner() == greece).count());
		// Nobody commands the other's people.
		CommandTicket theft = send(link, rome, CommandOp.UNIT_ACTION, unit(link.state(greece), greece, "UNIT_WARRIOR").key(), null, 0, "Fortify");
		assertEquals(CommandStatus.REJECTED, theft.status());

		decide(link, rome);
		decide(link, greece);
		int turn = link.state().turn();
		CommandTicket first = endTurn(link, rome);
		assertEquals(CommandStatus.CONFIRMED, first.status(), first.detail());
		assertEquals(turn, link.state().turn(), "the turn waits for Greece");
		assertFalse(link.mayAct(rome));
		assertEquals("ended", link.waitsFor(rome));
		assertTrue(link.mayAct(greece));
		CommandTicket late = send(link, rome, CommandOp.UNIT_ACTION, unit(link.state(rome), rome, "UNIT_WARRIOR").key(), null, 0, "Fortify");
		assertEquals(CommandStatus.REJECTED, late.status(), "who ended their turn waits");
		// Greece still moves, then ends too: the turn passes for everybody.
		assertEquals(CommandStatus.CONFIRMED, send(link, greece, CommandOp.UNIT_ACTION, unit(link.state(greece), greece, "UNIT_WARRIOR").key(), null, 0, "Fortify").status());
		CommandTicket second = endTurn(link, greece);
		assertEquals(CommandStatus.CONFIRMED, second.status(), second.detail());
		assertEquals(turn + 1, link.state().turn());
		assertEquals(turn + 1, link.state(rome).turn());
		assertTrue(link.mayAct(rome) && link.mayAct(greece), "and both may act again");
		assertTrue(unit(link.state(rome), rome, "UNIT_WARRIOR").moves() > 0, "with fresh moves");

		// Ten more turns the same way: the game goes on as any other.
		for (int i = 0; i < 10; i++) {
			decide(link, rome);
			decide(link, greece);
			assertEquals(CommandStatus.CONFIRMED, endTurn(link, greece).status());
			CommandTicket end = endTurn(link, rome);
			assertEquals(CommandStatus.CONFIRMED, end.status(), end.detail());
		}
		assertEquals(turn + 11, link.state().turn());
		assertTrue(link.state(rome).cities().values().stream().anyMatch(c -> c.key().owner() == rome && c.population() >= 1));
		// What each is told is their own.
		long romeTold = link.drainEvents(rome).stream().filter(e -> e instanceof WorldEvent.Notice).count();
		long greeceTold = link.drainEvents(greece).stream().filter(e -> e instanceof WorldEvent.Notice).count();
		assertTrue(link.drainEvents().stream().noneMatch(e -> e instanceof WorldEvent.Notice), "the built world carries no news");
		System.out.println("two people: turn " + link.state().turn() + ", Rome was told " + romeTold + " things, Greece " + greeceTold);

		// Kept and read back: both are still theirs.
		link.stop();
		UncivLink again = new UncivLink(dir.resolve("data"), dir.resolve("game.json"), UncivLink.Setup.standard(), System.err::println);
		again.start();
		assertEquals(List.of(rome, greece), again.seats());
		assertEquals(turn + 11, again.state(greece).turn());
	}

	@Test
	void twoPeopleAtWarMoveOneAfterTheOther(@TempDir Path dir) throws IOException {
		UncivLink link = two(dir);
		int rome = link.seats().get(0);
		int greece = link.seats().get(1);
		send(link, rome, CommandOp.UNIT_ACTION, unit(link.state(rome), rome, "UNIT_SETTLER").key(), null, 0, "FoundCity");
		send(link, greece, CommandOp.UNIT_ACTION, unit(link.state(greece), greece, "UNIT_SETTLER").key(), null, 0, "FoundCity");
		var romans = link.game().getCivilizations().get(rome);
		var greeks = link.game().getCivilizations().get(greece);
		link.onGameThread(() -> romans.getDiplomacyFunctions().makeCivilizationsMeet(greeks, false));
		link.pump();
		assertTrue(link.mayAct(rome) && link.mayAct(greece), "at peace both move at once");
		// A third party is not held up by their war, were there one; the two are.
		CommandTicket war = send(link, greece, CommandOp.DECLARE_WAR, new EntityKey(greece, rome), null, 0, "");
		assertEquals(CommandStatus.CONFIRMED, war.status(), war.detail());
		assertTrue(link.mayAct(rome), "Rome comes first in the game's order and moves first");
		assertFalse(link.mayAct(greece), "Greece waits for Rome");
		assertEquals("Rome", link.waitsFor(greece));
		CommandTicket early = send(link, greece, CommandOp.UNIT_ACTION, unit(link.state(greece), greece, "UNIT_WARRIOR").key(), null, 0, "Fortify");
		assertEquals(CommandStatus.REJECTED, early.status());
		assertTrue(early.detail().contains("Rome moves first"), early.detail());
		assertEquals(CommandStatus.REJECTED, endTurn(link, greece).status(), "nor can Greece end a turn it has not had");
		assertTrue(link.drainEvents(rome).stream().anyMatch(e -> e instanceof WorldEvent.WarChanged w && w.atWar()), "Rome is told of the war");

		decide(link, rome);
		int turn = link.state().turn();
		assertEquals(CommandStatus.CONFIRMED, endTurn(link, rome).status());
		assertEquals(turn, link.state().turn());
		assertTrue(link.mayAct(greece), "now Greece moves");
		assertEquals(CommandStatus.CONFIRMED, send(link, greece, CommandOp.UNIT_ACTION, unit(link.state(greece), greece, "UNIT_WARRIOR").key(), null, 0, "Fortify").status());
		decide(link, greece);
		assertEquals(CommandStatus.CONFIRMED, endTurn(link, greece).status());
		assertEquals(turn + 1, link.state().turn());
		assertTrue(link.mayAct(rome));
		assertFalse(link.mayAct(greece), "and the next turn again Rome first");

		// Ten turns on (peace cannot be spoken of at once) Rome offers it; it is the Greek player's to accept, not the computer's.
		for (int i = 0; i < 11; i++) {
			decide(link, rome);
			assertEquals(CommandStatus.CONFIRMED, endTurn(link, rome).status());
			decide(link, greece);
			assertEquals(CommandStatus.CONFIRMED, endTurn(link, greece).status());
		}
		CommandTicket offer = send(link, rome, CommandOp.MAKE_PEACE, new EntityKey(rome, greece), null, 0, "");
		assertEquals(CommandStatus.CONFIRMED, offer.status(), offer.detail());
		assertTrue(offer.detail().contains("theirs to accept"), offer.detail());
		assertTrue(romans.isAtWarWith(greeks), "an offer is not peace yet");
		List<Records.Option> put = ask(link, greece, "proposals", 0, 0);
		assertEquals(1, put.size(), put.toString());
		assertTrue(put.get(0).name().startsWith("From Rome"), put.get(0).name());
		assertTrue(ask(link, greece, "view", -1, 0).stream().anyMatch(o -> o.group() == 30 && o.id() == 1), "and it is counted on their screen");
		decide(link, rome);
		assertEquals(CommandStatus.CONFIRMED, endTurn(link, rome).status());
		CommandTicket yes = send(link, greece, CommandOp.ANSWER, new EntityKey(greece, -1), null, 1, put.get(0).type());
		assertEquals(CommandStatus.CONFIRMED, yes.status(), yes.detail());
		assertFalse(romans.isAtWarWith(greeks), "peace");
		decide(link, greece);
		assertEquals(CommandStatus.CONFIRMED, endTurn(link, greece).status());
		assertTrue(link.mayAct(rome) && link.mayAct(greece), "at peace both move at once again");
		assertTrue(link.drainEvents(rome).stream().anyMatch(e -> e instanceof WorldEvent.Notice n && n.text().contains("accepted")), "Rome hears of it");
		// Friendship between the two is offered and answered the same way.
		CommandTicket friends = send(link, greece, CommandOp.DIPLOMACY, new EntityKey(greece, rome), null, 0, "friendship");
		if (friends.status() == CommandStatus.CONFIRMED) {
			List<Records.Option> asked = ask(link, rome, "proposals", 0, 0);
			assertTrue(asked.stream().anyMatch(o -> o.type().startsWith("friend:")), asked.toString());
			Records.Option hand = asked.stream().filter(o -> o.type().startsWith("friend:")).findFirst().orElseThrow();
			assertEquals(CommandStatus.CONFIRMED, send(link, rome, CommandOp.ANSWER, new EntityKey(rome, -1), null, 1, hand.type()).status());
			assertTrue(romans.getDiplomacyManager(greeks).hasFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.DeclarationOfFriendship));
		} else {
			System.out.println("friendship so soon after a war: " + friends.detail());
		}
	}

	@Test
	void somebodyAwayDoesNotHoldTheOthersUpAndANewcomerTakesOverACivilization(@TempDir Path dir) throws IOException {
		UncivLink link = two(dir);
		int rome = link.seats().get(0);
		int greece = link.seats().get(1);
		send(link, rome, CommandOp.UNIT_ACTION, unit(link.state(rome), rome, "UNIT_SETTLER").key(), null, 0, "FoundCity");
		// The Greek player leaves before doing anything: the computer founds their city and plays on.
		link.present(greece, false);
		link.pump();
		int turn = link.state().turn();
		for (int i = 0; i < 6; i++) {
			decide(link, rome);
			CommandTicket end = endTurn(link, rome);
			assertEquals(CommandStatus.CONFIRMED, end.status(), end.detail());
		}
		assertEquals(turn + 6, link.state().turn(), "Rome plays on alone");
		assertTrue(link.state(greece).cities().values().stream().anyMatch(c -> c.key().owner() == greece), "the computer founded the Greek capital");
		// Back again: the turn waits for them once more.
		link.present(greece, true);
		link.pump();
		decide(link, rome);
		assertEquals(CommandStatus.CONFIRMED, endTurn(link, rome).status());
		assertEquals(turn + 6, link.state().turn());
		decide(link, greece);
		assertEquals(CommandStatus.CONFIRMED, endTurn(link, greece).status());
		assertEquals(turn + 7, link.state().turn());

		// A third person arrives and takes over the civilization the computer played.
		List<Records.Option> who = ask(link, rome, "seats", 0, 0);
		Records.Option free = who.stream().filter(o -> o.group() == 0).findFirst().orElseThrow(() -> new AssertionError(who.toString()));
		assertEquals(2, who.stream().filter(o -> o.group() == 1).count(), who.toString());
		AtomicReference<String> answer = new AtomicReference<>();
		link.rule(free.id(), true, answer::set);
		link.pump();
		assertEquals("", answer.get());
		assertEquals(3, link.seats().size());
		int third = free.id();
		assertEquals(third, link.state(third).session().viewer());
		assertTrue(link.mayAct(third));
		UnitState theirs = link.state(third).units().values().stream().filter(u -> u.key().owner() == third && u.moves() > 0).findFirst()
			.orElseThrow(() -> new AssertionError("the newcomer has somebody who can move"));
		assertEquals(CommandStatus.CONFIRMED, send(link, third, CommandOp.UNIT_ACTION, theirs.key(), null, 0, "Skip").status());
		for (int civ : link.seats()) {
			decide(link, civ);
			assertEquals(CommandStatus.CONFIRMED, endTurn(link, civ).status());
		}
		assertEquals(turn + 8, link.state().turn());
		// And leaves it to the computer again: after the round it is the computer's.
		link.rule(third, false, answer::set);
		link.pump();
		assertEquals("", answer.get());
		for (int civ : List.of(rome, greece)) {
			decide(link, civ);
			assertEquals(CommandStatus.CONFIRMED, endTurn(link, civ).status());
		}
		assertEquals(turn + 9, link.state().turn());
		assertEquals(List.of(rome, greece), link.seats());
		assertTrue(link.game().getCivilizations().get(third).isAI());
		// The last person cannot leave the game without a ruler.
		link.rule(greece, false, answer::set);
		link.pump();
		assertEquals("", answer.get());
		link.rule(rome, false, answer::set);
		link.pump();
		assertNotEquals("", answer.get());
	}

	@Test
	void theEngineWaitsToBeToldWhatGameToMake(@TempDir Path dir) throws Exception {
		UncivLink link = new UncivLink(dir.resolve("data"), dir.resolve("game.json"), null, System.err::println);
		Thread thread = new Thread(link, "test-link");
		thread.setDaemon(true);
		thread.start();
		long until = System.currentTimeMillis() + 60_000;
		while (!link.waiting() && System.currentTimeMillis() < until) {
			Thread.sleep(50);
		}
		assertEquals(LinkStatus.NO_MATCH, link.status());
		assertEquals(null, link.state());
		AtomicReference<List<Records.Option>> nations = new AtomicReference<>();
		link.ask("nations", 0, 0, nations::set);
		while (nations.get() == null && System.currentTimeMillis() < until) {
			Thread.sleep(50);
		}
		assertTrue(nations.get().size() >= 20, "the civilizations to choose from: " + nations.get().size());
		Records.Option rome = nations.get().stream().filter(o -> o.type().equals("Rome")).findFirst().orElseThrow();
		assertTrue(rome.name().startsWith("Augustus Caesar\n"), rome.name());
		assertTrue(rome.name().contains("Legion"), rome.name());
		System.out.println("nations: " + nations.get().size() + ", Rome: " + rome.name().replace("\n", " / ") + " colours " + Integer.toHexString(rome.group()) + "/" + Integer.toHexString(rome.id()));
		link.begin(new UncivLink.Setup("Tiny", "pangaea", 1, 0, "Prince", "Rome", 5L, "Quick", List.of(), Set.of("noBarbarians"), List.of("Domination")));
		while (link.status() != LinkStatus.LIVE && System.currentTimeMillis() < until) {
			Thread.sleep(50);
		}
		assertEquals(LinkStatus.LIVE, link.status(), link.statusDetail());
		assertEquals("Rome", link.state().civ(link.state().session().viewer()).name());
		assertEquals(List.of("Domination"), link.game().getGameParameters().getVictoryTypes());
		assertTrue(link.game().getGameParameters().getNoBarbarians());
		link.stop();
		thread.join(10_000);
	}
}
