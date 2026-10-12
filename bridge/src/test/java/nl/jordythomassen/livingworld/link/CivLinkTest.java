package nl.jordythomassen.livingworld.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import nl.jordythomassen.livingworld.commands.CommandOp;
import nl.jordythomassen.livingworld.commands.CommandStatus;
import nl.jordythomassen.livingworld.commands.CommandTicket;
import nl.jordythomassen.livingworld.commands.GameCommand;
import nl.jordythomassen.livingworld.coords.GridPos;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.mock.MockCiv;
import nl.jordythomassen.livingworld.mock.MockTransport;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldEvent;
import nl.jordythomassen.livingworld.state.WorldState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The whole bridge core against the mock game, driven one beat at a time. */
class CivLinkTest {
	private MockCiv civ;
	private MockTransport transport;
	private CivLink link;
	private final List<String> log = new ArrayList<>();
	private int commandNumber;

	@BeforeEach
	void setUp() {
		civ = new MockCiv(40, 25, 7);
		transport = new MockTransport(civ);
		link = new CivLink(() -> {
			if (transport.down) {
				throw new IOException("mock game is closed");
			}
			return transport;
		}, () -> "LivingWorld = { VERSION = 1 }\n", log::add);
		link.setRetryMs(0);
	}

	private void pump(int beats) {
		for (int i = 0; i < beats; i++) {
			try {
				link.pumpOnce();
			} catch (IOException e) {
				link.failed(e.getMessage());
			}
		}
	}

	private WorldState live() {
		pump(3);
		assertEquals(LinkStatus.LIVE, link.status(), String.join("\n", log));
		link.drainEvents();
		return link.state();
	}

	private GameCommand command(CommandOp op, EntityKey target, PlotCoord plot, int amount, String key) {
		WorldState state = link.state();
		return new GameCommand("c" + (++commandNumber), key, state.session().bootId(), state.session().gameId(), "Steve", state.turn(), op, target, plot, amount);
	}

	private UnitState romanWarrior(WorldState state) {
		return state.units().values().stream().filter(u -> u.key().owner() == MockCiv.ROME && u.type().equals("UNIT_WARRIOR")).findFirst().orElseThrow();
	}

	private CityState rome(WorldState state) {
		return state.cities().values().stream().filter(c -> c.name().equals("Rome")).findFirst().orElseThrow();
	}

	@Test
	void loadsItsScriptIntoTheGameAndGoesLive() {
		pump(1);
		assertEquals(LinkStatus.SYNCING, link.status());
		assertTrue(civ.loaded, "the script was sent");
		assertTrue(transport.lastBootSource.contains("LivingWorld = { VERSION = 1 }"));
		pump(1);
		assertEquals(LinkStatus.LIVE, link.status());
		WorldState state = link.state();
		assertEquals(40, state.session().grid().width());
		assertEquals(0.5, state.session().grid().oddRowShift());
		assertEquals(MockCiv.ROME, state.session().viewer());
		assertEquals("Rome", state.civ(MockCiv.ROME).name());
		assertEquals("Rome", rome(state).name());
		assertTrue(rome(state).details());
		assertEquals("TERRAIN_GRASS", state.legend().type(nl.jordythomassen.livingworld.state.Legend.Kind.TERRAIN, state.plot(rome(state).at()).terrain()));
		assertTrue(state.plot(rome(state).at()).city());
		List<WorldEvent> events = link.drainEvents();
		assertEquals(1, events.size());
		assertTrue(events.get(0) instanceof WorldEvent.SessionStarted);
	}

	@Test
	void whatTheViewerHasNotSeenNeverLeavesCiv() {
		WorldState state = live();
		assertFalse(state.plot(new PlotCoord(0, 0)).revealed());
		assertEquals(PlotState.UNKNOWN, state.plot(new PlotCoord(0, 0)));
		// Egypt's city and units are out of Rome's sight in the mock.
		assertTrue(state.cities().values().stream().noneMatch(c -> c.name().equals("Thebes")));
		assertTrue(state.units().values().stream().noneMatch(u -> u.key().owner() == MockCiv.EGYPT));
		// A foreign city shows no production details even when seen.
		assertTrue(state.cities().values().stream().filter(c -> c.key().owner() != MockCiv.ROME).noneMatch(CityState::details));
	}

	@Test
	void aUnitMovingInCivBecomesAnEventWithItsOldPlot() {
		WorldState state = live();
		UnitState warrior = romanWarrior(state);
		MockCiv.Unit unit = civ.unit(MockCiv.ROME, warrior.key().id());
		civ.moveUnit(unit, unit.x + 1, unit.y);
		pump(1);
		List<WorldEvent> events = link.drainEvents();
		WorldEvent.UnitMoved moved = events.stream().filter(e -> e instanceof WorldEvent.UnitMoved).map(e -> (WorldEvent.UnitMoved) e).findFirst().orElseThrow();
		assertEquals(warrior.at(), moved.from());
		assertEquals(new PlotCoord(warrior.at().x() + 1, warrior.at().y()), moved.unit().at());
		assertEquals(warrior.key(), moved.unit().key());
		assertTrue(link.hintsSeen() > 0, "Civ's own hook was passed along");
	}

	@Test
	void aUnitThatDiesIsReportedGone() {
		WorldState state = live();
		UnitState warrior = romanWarrior(state);
		civ.killUnit(civ.unit(MockCiv.ROME, warrior.key().id()));
		pump(1);
		assertTrue(link.drainEvents().stream().anyMatch(e -> e instanceof WorldEvent.UnitGone gone && gone.last().key().equals(warrior.key())));
		assertFalse(link.state().units().containsKey(warrior.key()));
	}

	@Test
	void aCapturedCityKeepsItsPlotAndChangesHands() {
		WorldState state = live();
		CityState geneva = state.cities().values().stream().filter(c -> c.name().equals("Geneva")).findFirst().orElseThrow();
		civ.captureCity(civ.cityNamed("Geneva"), MockCiv.ROME);
		pump(1);
		List<WorldEvent> events = link.drainEvents();
		WorldEvent.CityChangedHands change = events.stream().filter(e -> e instanceof WorldEvent.CityChangedHands).map(e -> (WorldEvent.CityChangedHands) e).findFirst().orElseThrow();
		assertEquals(geneva.key(), change.before().key());
		assertEquals(MockCiv.ROME, change.city().key().owner());
		assertEquals(geneva.at(), change.city().at());
		assertTrue(events.stream().anyMatch(e -> e instanceof WorldEvent.PlotChanged p && p.plot().owner() == MockCiv.ROME && p.before().owner() == MockCiv.GENEVA));
	}

	@Test
	void warAndTurnChangesAreEvents() {
		live();
		civ.declareWar(true);
		pump(1);
		assertTrue(link.drainEvents().stream().anyMatch(e -> e instanceof WorldEvent.WarChanged w && w.atWar() && w.civ().id() == MockCiv.EGYPT));
		civ.endTurn();
		pump(1);
		assertTrue(link.drainEvents().stream().anyMatch(e -> e instanceof WorldEvent.TurnChanged t && t.from() == 0 && t.to() == 1));
		civ.declareWar(false);
		pump(1);
		assertTrue(link.drainEvents().stream().anyMatch(e -> e instanceof WorldEvent.WarChanged w && !w.atWar()));
	}

	@Test
	void terrainChangesArriveAsPlotEvents() {
		WorldState state = live();
		PlotCoord at = rome(state).at();
		civ.setImprovement(at.x() - 1, at.y(), 4);
		pump(1);
		assertTrue(link.drainEvents().stream().anyMatch(e -> e instanceof WorldEvent.PlotChanged p
			&& p.at().equals(new PlotCoord(at.x() - 1, at.y())) && p.plot().improvement() == 4));
	}

	@Test
	void thePlayerPositionReachesTheCivMap() {
		live();
		link.setMarker(new PlayerMarker(new GridPos(12.25, 7.4), true, true, "Jordy"));
		pump(1);
		assertEquals(12.25, civ.markerU, 1e-3);
		assertEquals(7.4, civ.markerV, 1e-3);
		assertEquals(12, civ.markerPlotX, "the plot under the player goes along, for height and camera");
		assertEquals(7, civ.markerPlotY);
		assertTrue(civ.markerShown);
		assertTrue(civ.markerFollow);
		assertEquals("Jordy", civ.markerLabel);
		// Off the map there is nothing to show.
		link.setMarker(new PlayerMarker(new GridPos(-30, 7.4), true, false, "Jordy"));
		pump(1);
		assertFalse(civ.markerShown);
	}

	@Test
	void aDeliveryIsAppliedOnceAndConfirmedFromCivsOwnState() {
		WorldState state = live();
		CityState city = rome(state);
		int before = civ.cityNamed("Rome").production;
		CommandTicket ticket = link.submit(command(CommandOp.CITY_ADD_PRODUCTION, city.key(), null, 12, "mission-1"));
		assertEquals(CommandStatus.QUEUED, ticket.status());
		pump(1);
		assertEquals(before + 12, civ.cityNamed("Rome").production);
		pump(1);
		assertEquals(CommandStatus.CONFIRMED, ticket.status(), ticket.detail());
		assertEquals(before, ticket.before());
		assertEquals(before + 12, ticket.after());
		assertEquals(before + 12, rome(link.state()).production());
		// The same turn-in again: same ticket back, nothing sent, nothing added.
		CommandTicket again = link.submit(command(CommandOp.CITY_ADD_PRODUCTION, city.key(), null, 12, "mission-1"));
		assertSame(ticket, again);
		pump(2);
		assertEquals(before + 12, civ.cityNamed("Rome").production);
		List<CommandStatus> seen = link.drainTicketUpdates().stream().map(CommandTicket::status).toList();
		assertFalse(seen.isEmpty());
	}

	@Test
	void anEscortOrderMovesTheRealUnit() {
		WorldState state = live();
		UnitState warrior = romanWarrior(state);
		PlotCoord goal = new PlotCoord(warrior.at().x() + 2, warrior.at().y());
		CommandTicket ticket = link.submit(command(CommandOp.UNIT_MOVE_TO, warrior.key(), goal, 0, "escort-1"));
		pump(2);
		assertEquals(CommandStatus.CONFIRMED, ticket.status(), ticket.detail());
		assertEquals(1, link.state().session().grid().distance(romanWarrior(link.state()).at(), goal));
		assertTrue(link.drainEvents().stream().anyMatch(e -> e instanceof WorldEvent.UnitMoved));
	}

	@Test
	void hopelessRequestsAreRefusedWithAReasonBeforeReachingCiv() {
		WorldState state = live();
		int sentBefore = transport.sent.size();
		CityState foreign = state.cities().values().stream().filter(c -> c.key().owner() != MockCiv.ROME).findFirst().orElseThrow();
		CommandTicket a = link.submit(command(CommandOp.CITY_ADD_PRODUCTION, foreign.key(), null, 5, "k1"));
		assertEquals(CommandStatus.REJECTED, a.status());
		assertTrue(a.detail().contains("does not belong"));
		CommandTicket b = link.submit(command(CommandOp.CITY_ADD_PRODUCTION, rome(state).key(), null, 5000, "k2"));
		assertEquals(CommandStatus.REJECTED, b.status());
		CommandTicket c = link.submit(command(CommandOp.UNIT_HEAL, romanWarrior(state).key(), null, 10, "k3"));
		assertEquals(CommandStatus.REJECTED, c.status());
		assertTrue(c.detail().contains("not wounded"));
		CommandTicket d = link.submit(command(CommandOp.UNIT_MOVE_TO, romanWarrior(state).key(), new PlotCoord(0, 0), 0, "k4"));
		assertEquals(CommandStatus.REJECTED, d.status());
		assertTrue(d.detail().contains("not explored"));
		CommandTicket e = link.submit(new GameCommand("cx", "k5", "another-boot", state.session().gameId(), "Steve", 0,
			CommandOp.CITY_ADD_PRODUCTION, rome(state).key(), null, 5));
		assertEquals(CommandStatus.REJECTED, e.status());
		assertTrue(e.detail().contains("another Civ session"));
		assertTrue(transport.sent.subList(sentBefore, transport.sent.size()).stream().noneMatch(s -> s.contains("LivingWorld.cmd")));
	}

	@Test
	void ordersWaitForThePlayersOwnTurn() {
		WorldState state = live();
		civ.viewerTurn = false;
		pump(1);
		CommandTicket ticket = link.submit(command(CommandOp.CITY_ADD_PRODUCTION, rome(state).key(), null, 5, "wait-1"));
		int before = civ.cityNamed("Rome").production;
		pump(3);
		assertEquals(CommandStatus.QUEUED, ticket.status());
		assertEquals(before, civ.cityNamed("Rome").production);
		civ.viewerTurn = true;
		pump(3);
		assertEquals(CommandStatus.CONFIRMED, ticket.status(), ticket.detail());
		assertEquals(before + 5, civ.cityNamed("Rome").production);
	}

	@Test
	void endingTheTurnIsConfirmedByTheNextTurnBeginning() {
		WorldState state = live();
		int turn = state.turn();
		CommandTicket ticket = link.submit(command(CommandOp.END_TURN, new EntityKey(MockCiv.ROME, -1), null, 0, "turn-" + turn));
		pump(2);
		assertEquals(CommandStatus.CONFIRMED, ticket.status(), ticket.detail());
		assertEquals(turn + 1, link.state().turn());
		assertTrue(link.drainEvents().stream().anyMatch(e -> e instanceof WorldEvent.TurnChanged));
		// A request left over from that turn cannot end the next one.
		CommandTicket late = link.submit(new GameCommand("late", "late", state.session().bootId(), state.session().gameId(), "Steve", turn,
			CommandOp.END_TURN, new EntityKey(MockCiv.ROME, -1), null, 0));
		assertEquals(CommandStatus.REJECTED, late.status());
		pump(2);
		assertEquals(turn + 1, civ.turn);
	}

	@Test
	void aWoundedUnitCanBeHealedAndIsConfirmed() {
		WorldState state = live();
		UnitState warrior = romanWarrior(state);
		civ.unit(MockCiv.ROME, warrior.key().id()).hp = 40;
		civ.unitsRev++;
		pump(1);
		CommandTicket ticket = link.submit(command(CommandOp.UNIT_HEAL, warrior.key(), null, 25, "heal-1"));
		pump(2);
		assertEquals(CommandStatus.CONFIRMED, ticket.status(), ticket.detail());
		assertEquals(65, romanWarrior(link.state()).hp());
	}

	@Test
	void reloadingTheGameReloadsTheScriptAndStartsANewSession() {
		WorldState state = live();
		String firstBoot = state.session().bootId();
		civ.reload("mock-boot-2");
		pump(3);
		assertEquals(LinkStatus.LIVE, link.status());
		assertEquals("mock-boot-2", link.state().session().bootId());
		assertTrue(link.drainEvents().stream().anyMatch(e -> e instanceof WorldEvent.SessionStarted));
		// A request made in the old session is not applied to the new one.
		CommandTicket stale = link.submit(new GameCommand("old", "old-key", firstBoot, state.session().gameId(), "Steve", 0,
			CommandOp.CITY_ADD_PRODUCTION, rome(link.state()).key(), null, 5));
		assertEquals(CommandStatus.REJECTED, stale.status());
	}

	@Test
	void closingAndReopeningCivNeedsNoRestartOnThisSide() {
		live();
		transport.down = true;
		pump(2);
		assertEquals(LinkStatus.NO_GAME, link.status());
		assertNotNull(link.state(), "the last picture stays available while the game is away");
		transport.down = false;
		civ.reload("mock-boot-3");
		pump(3);
		assertEquals(LinkStatus.LIVE, link.status());
		assertEquals("mock-boot-3", link.state().session().bootId());
	}

	@Test
	void inTheMainMenuItWaitsForAGame() {
		civ.inMatch = false;
		pump(3);
		assertEquals(LinkStatus.NO_MATCH, link.status());
		assertNull(link.state());
		civ.inMatch = true;
		pump(3);
		assertEquals(LinkStatus.LIVE, link.status());
	}

	@Test
	void foreignOutputAndBrokenLinesDoNotCorruptThePicture() {
		WorldState state = live();
		transport.noise.add("Runtime Error: [string \"other mod\"]:12: attempt to index a nil value");
		transport.noise.add("LW\tU\t0\tnot-a-number");
		transport.noise.add("LW\tT\tgarbage");
		civ.unitsRev++;
		pump(2);
		assertEquals(LinkStatus.LIVE, link.status());
		assertEquals(state.units().keySet(), link.state().units().keySet());
		assertEquals(state.cities().keySet(), link.state().cities().keySet());
	}

	@Test
	void turnsPassingKeepTheTwoSidesInStep() {
		live();
		for (int turn = 0; turn < 30; turn++) {
			civ.endTurn();
			pump(1);
		}
		WorldState state = link.state();
		assertEquals(30, state.turn());
		long alive = civ.units.stream().filter(u -> !u.dead).count();
		assertTrue(state.units().size() <= alive);
		for (UnitState unit : state.units().values()) {
			MockCiv.Unit real = civ.unit(unit.key().owner(), unit.key().id());
			assertNotNull(real);
			assertEquals(new PlotCoord(real.x, real.y), unit.at());
		}
		assertEquals(civ.cityNamed("Rome").production, rome(state).production());
	}
}
