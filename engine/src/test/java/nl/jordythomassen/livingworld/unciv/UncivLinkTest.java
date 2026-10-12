package nl.jordythomassen.livingworld.unciv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import nl.jordythomassen.livingworld.commands.CommandOp;
import nl.jordythomassen.livingworld.commands.CommandStatus;
import nl.jordythomassen.livingworld.commands.CommandTicket;
import nl.jordythomassen.livingworld.commands.GameCommand;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.link.LinkStatus;
import nl.jordythomassen.livingworld.protocol.Records;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldEvent;
import nl.jordythomassen.livingworld.state.WorldState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A real game of Unciv, played through the same interface the Minecraft side uses. */
class UncivLinkTest {
	private int counter;

	private UncivLink link(Path dir, long seed) throws IOException {
		UncivLink link = new UncivLink(dir.resolve("data"), dir.resolve("game.json"), new UncivLink.Setup("Tiny", "pangaea", 2, 2, "Prince", "Rome", seed, "Quick"), System.err::println);
		link.start();
		return link;
	}

	private CommandTicket send(UncivLink link, CommandOp op, EntityKey target, PlotCoord plot, int amount, String item) {
		WorldState state = link.state();
		String id = "t" + (++counter);
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

	@Test
	void aGameIsPlayedFromSettlerToEmpire(@TempDir Path dir) throws IOException {
		UncivLink link = link(dir, 21L);
		assertEquals(LinkStatus.LIVE, link.status());
		WorldState state = link.state();
		int me = state.session().viewer();
		assertEquals("Rome", state.civ(me).name());
		assertEquals("ARTSTYLE_GRECO_ROMAN", state.civ(me).artStyle());
		assertTrue(link.viewerTurn());

		// The Settler founds the capital where it stands.
		UnitState settler = state.units().values().stream().filter(u -> u.type().equals("UNIT_SETTLER")).findFirst().orElseThrow();
		List<Records.Option> can = ask(link, "unit", settler.key().id(), 0);
		Records.Option found = can.stream().filter(o -> o.type().equals("FoundCity")).findFirst().orElseThrow(() -> new AssertionError(can.toString()));
		assertEquals(1, found.turns(), "it can be founded right here");
		CommandTicket founding = send(link, CommandOp.UNIT_ACTION, settler.key(), null, 0, found.type());
		assertEquals(CommandStatus.CONFIRMED, founding.status(), founding.detail());
		state = link.state();
		CityState rome = state.cities().values().stream().filter(c -> c.key().owner() == me).findFirst().orElseThrow();
		assertTrue(rome.capital());
		assertEquals(settler.at(), rome.at());
		assertTrue(rome.buildings().contains("PALACE"), rome.buildings().toString());
		assertFalse(state.units().containsKey(settler.key()), "the Settler became the city");
		assertTrue(link.drainEvents().stream().anyMatch(e -> e instanceof WorldEvent.CityAppeared));

		// The game now wants to know what the city builds and what is researched.
		assertEquals("RESEARCH", state.empire().blocking());
		CommandTicket early = send(link, CommandOp.END_TURN, self(state), null, 0, "");
		assertEquals(CommandStatus.REJECTED, early.status());
		assertTrue(early.detail().contains("research"), early.detail());
		List<Records.Option> techs = ask(link, "research", 0, 0);
		assertTrue(techs.stream().anyMatch(o -> o.type().equals("TECH_POTTERY")), techs.toString());
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.RESEARCH, self(state), null, 0, "TECH_POTTERY").status());
		assertEquals("TECH_POTTERY", link.state().empire().researchType());
		assertEquals("PRODUCTION", link.state().empire().blocking());
		List<Records.Option> builds = ask(link, "build", rome.key().id(), 0);
		assertTrue(builds.stream().anyMatch(o -> o.type().equals("UNIT_WARRIOR")) && builds.stream().anyMatch(o -> o.type().equals("BUILDING_MONUMENT")), builds.toString());
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CITY_BUILD, rome.key(), null, 0, "BUILDING_MONUMENT").status());
		assertEquals("BUILDING_MONUMENT", link.state().cities().get(rome.key()).productionType());

		// The Warrior walks to a neighbouring hex.
		state = link.state();
		UnitState warrior = state.units().values().stream().filter(u -> u.type().equals("UNIT_WARRIOR")).findFirst().orElseThrow();
		PlotCoord step = null;
		for (PlotCoord near : state.session().grid().neighbors(warrior.at())) {
			var plot = state.plot(near);
			if (plot.revealed() && !plot.water() && plot.elevation() != nl.jordythomassen.livingworld.state.PlotState.MOUNTAIN && !plot.city()
				&& state.units().values().stream().noneMatch(u -> u.at().equals(near))) {
				step = near;
				break;
			}
		}
		assertNotNull(step, "a free hex next to the Warrior");
		CommandTicket walking = send(link, CommandOp.UNIT_MOVE_TO, warrior.key(), step, 0, "");
		assertEquals(CommandStatus.CONFIRMED, walking.status(), walking.detail());
		assertEquals(step, link.state().units().get(warrior.key()).at());

		// Something to help the city along, as a player in Minecraft can.
		// Hands help a city along, they do not do its work: of twenty hammers' worth it takes what it makes itself in a turn
		// (three logs' worth at the least), and no more that turn.
		List<Records.Option> byHand = ask(link, "hands", rome.key().id(), 0);
		assertEquals(3, byHand.size(), "production, food and gold: " + byHand);
		int most = byHand.get(0).id();
		assertTrue(most >= 6 && most < 20, "what Rome takes from hands this turn: " + most);
		CommandTicket logs = send(link, CommandOp.CITY_ADD_PRODUCTION, rome.key(), null, 20, "");
		assertEquals(CommandStatus.CONFIRMED, logs.status(), logs.detail());
		assertEquals(most, logs.after() - logs.before(), logs.detail());
		CommandTicket more = send(link, CommandOp.CITY_ADD_PRODUCTION, rome.key(), null, 20, "");
		assertEquals(CommandStatus.REJECTED, more.status(), "a second load the same turn is not taken: " + more.detail());
		// Wheat from the fields feeds the city, gold from the hills fills the treasury: each within its own measure.
		int foodBefore = link.state().cities().get(rome.key()).food();
		CommandTicket wheat = send(link, CommandOp.CITY_ADD_PRODUCTION, rome.key(), null, 2, "food");
		assertEquals(CommandStatus.CONFIRMED, wheat.status(), wheat.detail());
		assertEquals(foodBefore + 2, link.state().cities().get(rome.key()).food(), wheat.detail());
		int goldBefore = link.state().empire().gold();
		CommandTicket nuggets = send(link, CommandOp.CITY_ADD_PRODUCTION, rome.key(), null, 60, "gold");
		assertEquals(CommandStatus.CONFIRMED, nuggets.status(), nuggets.detail());
		assertEquals(goldBefore + 10, link.state().empire().gold(), "a purse a turn and no more: " + nuggets.detail());
		assertEquals(0, ask(link, "hands", rome.key().id(), 0).get(2).id(), "no more gold by hand this turn");
		System.out.println("by hand: " + logs.detail() + " / " + wheat.detail() + " / " + nuggets.detail() + " / " + more.detail());

		// The turn ends, the others move, and it is our turn again.
		String left = link.state().empire().blocking();
		assertTrue(left.isEmpty() || left.equals("UNITS"), "nothing is left to decide; a unit with moves left simply waits: " + left);
		link.drainEvents();
		CommandTicket turn = send(link, CommandOp.END_TURN, self(link.state()), null, 0, "");
		assertEquals(CommandStatus.CONFIRMED, turn.status(), turn.detail());
		assertEquals(1, link.state().turn());
		assertTrue(link.viewerTurn());
		assertTrue(link.drainEvents().stream().anyMatch(e -> e instanceof WorldEvent.TurnChanged));
		assertTrue(Files.size(dir.resolve("game.json")) > 1000, "the game is kept after every turn");

		// Sixty more turns, answering whatever the game asks with its first offer.
		List<String> notices = new ArrayList<>();
		for (int i = 0; i < 60; i++) {
			for (int tries = 0; tries < 8; tries++) {
				state = link.state();
				String waiting = state.empire().blocking();
				if (waiting.equals("RESEARCH")) {
					send(link, CommandOp.RESEARCH, self(state), null, 0, ask(link, "research", 0, 0).get(0).type());
				} else if (waiting.equals("POLICY")) {
					send(link, CommandOp.POLICY, self(state), null, 0, ask(link, "policy", 0, 0).get(0).type());
				} else if (waiting.equals("PRODUCTION")) {
					for (CityState city : state.cities().values()) {
						if (city.key().owner() == me && city.productionType().isEmpty()) {
							List<Records.Option> offered = ask(link, "build", city.key().id(), 0);
							// A Settler now and then, so the empire grows.
							boolean room = state.cities().size() < 3;
							Records.Option pick = offered.stream().filter(o -> o.type().equals("UNIT_SETTLER") && room).findFirst().orElse(offered.get(0));
							send(link, CommandOp.CITY_BUILD, city.key(), null, 0, pick.type());
						}
					}
				} else if (waiting.equals("RELIGION")) {
					send(link, CommandOp.BELIEF, self(state), null, 0, ask(link, "beliefs", 0, 0).get(1).type());
				} else {
					break;
				}
			}
			// Settlers found cities where they stand when the game lets them.
			state = link.state();
			for (UnitState unit : state.units().values()) {
				if (unit.key().owner() == me && unit.type().equals("UNIT_SETTLER")) {
					for (Records.Option option : ask(link, "unit", unit.key().id(), 0)) {
						if (option.type().equals("FoundCity") && option.turns() == 1) {
							send(link, CommandOp.UNIT_ACTION, unit.key(), null, 0, option.type());
						}
					}
				}
			}
			CommandTicket next = send(link, CommandOp.END_TURN, self(link.state()), null, 0, "");
			assertEquals(CommandStatus.CONFIRMED, next.status(), "turn " + link.state().turn() + ": " + next.detail());
			for (WorldEvent event : link.drainEvents()) {
				if (event instanceof WorldEvent.Notice notice) {
					notices.add(notice.text());
				}
			}
		}
		state = link.state();
		assertEquals(61, state.turn());
		assertTrue(state.techs().size() >= 5, state.techs().toString());
		assertTrue(state.cities().get(rome.key()).buildings().contains("MONUMENT"), state.cities().get(rome.key()).buildings().toString());
		assertTrue(state.empire().policies() >= 1, "a policy or two by now");
		assertFalse(notices.isEmpty(), "the game told the player things along the way");
		assertTrue(notices.stream().noneMatch(n -> n.contains("[") || n.contains("]")), notices.toString());
		System.out.println("after 61 turns: " + state.cities().values().stream().filter(c -> c.key().owner() == me).count() + " cities, " + state.techs().size()
			+ " techs, " + state.empire().policies() + " policies, gold " + state.empire().gold() + ", " + notices.size() + " notices, e.g. " + notices.subList(0, Math.min(4, notices.size())));

		// Closed and opened again: the same game.
		link.stop();
		link.run();
		UncivLink again = link(dir, 99L);
		assertEquals(61, again.state().turn());
		assertEquals(state.session().gameId(), again.state().session().gameId());
		assertEquals(state.cities().keySet(), again.state().cities().keySet());
		assertEquals(state.techs(), again.state().techs());
	}

	@Test
	void whatTheLandYieldsAndTheTreeOfTechnologiesAreTold(@TempDir Path dir) throws IOException {
		UncivLink link = link(dir, 21L);
		WorldState state = link.state();
		// What every hex the player has seen yields: food, production, gold, and the rest.
		List<Records.Option> yields = ask(link, "yields", 0, 0);
		assertFalse(yields.isEmpty());
		for (Records.Option hex : yields) {
			String[] at = hex.type().split(",");
			assertTrue(state.plot(new PlotCoord(Integer.parseInt(at[0]), Integer.parseInt(at[1]))).revealed(), hex.type());
			assertEquals(3, hex.name().split(",").length, hex.name());
			assertTrue(hex.group() >= 0 && hex.group() < 20 && hex.id() >= 0 && hex.id() < 20, hex.toString());
		}
		assertTrue(yields.stream().anyMatch(o -> o.group() > 0), "no hex with food");
		// The tree: what is known, what is open, what is further off and what that builds on.
		List<Records.Option> tree = ask(link, "techtree", 0, 0);
		assertTrue(tree.size() > 50, tree.size() + " technologies");
		assertTrue(tree.stream().anyMatch(o -> o.name().equals("Agriculture") && o.type().contains("|known|")), tree.toString());
		assertTrue(tree.stream().anyMatch(o -> o.name().equals("Pottery") && o.type().contains("|open|")));
		Records.Option far = tree.stream().filter(o -> o.name().equals("Bronze Working")).findFirst().orElseThrow();
		assertTrue(far.type().contains("|far|") && far.type().contains("|Mining|") && far.type().endsWith(" era"), far.type());
		// A question changes nothing, so no new picture of the game is taken for it.
		assertTrue(state == link.state());
		// Research towards something further off goes by way of what it builds on.
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.RESEARCH, self(state), null, 0, far.type().split("\\|")[0]).status());
		assertEquals("TECH_MINING", link.state().empire().researchType());
		tree = ask(link, "techtree", 0, 0);
		assertTrue(tree.stream().anyMatch(o -> o.name().equals("Bronze Working") && o.type().contains("|queued|")), "Bronze Working is not next in line");
		assertTrue(tree.stream().anyMatch(o -> o.name().equals("Mining") && o.type().contains("|now|")));
	}

	@Test
	void thePlayerSaysWhichLandACitysCitizensWork(@TempDir Path dir) throws IOException {
		UncivLink link = link(dir, 21L);
		int me = link.state().session().viewer();
		var settler = link.state().units().values().stream().filter(u -> u.key().owner() == me && u.type().toUpperCase(java.util.Locale.ROOT).contains("SETTLER")).findFirst().orElseThrow();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.UNIT_ACTION, settler.key(), null, 0, "FoundCity").status());
		settle(link);
		CityState city = link.state().cities().values().stream().filter(c -> c.key().owner() == me).findFirst().orElseThrow();
		List<Records.Option> land = ask(link, "worked", city.key().id(), 0);
		Records.Option info = land.stream().filter(o -> o.type().equals("info")).findFirst().orElseThrow();
		assertEquals(city.population(), info.id(), "citizens");
		assertTrue(land.stream().anyMatch(o -> o.group() == 3), "the city itself is on its own land");
		assertTrue(land.stream().anyMatch(o -> o.group() == 1), "somebody works somewhere: " + land);
		// A free hex: the player puts the citizen there, and there the citizen stays.
		Records.Option free = land.stream().filter(o -> o.group() == 0).findFirst().orElseThrow();
		String[] at = free.type().split(",");
		PlotCoord plot = new PlotCoord(Integer.parseInt(at[0]), Integer.parseInt(at[1]));
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CITY_WORK, city.key(), plot, 0, "toggle").status());
		land = ask(link, "worked", city.key().id(), 0);
		assertTrue(land.stream().anyMatch(o -> o.type().equals(free.type()) && o.group() == 2), "the hex is not worked by the player's word: " + land);
		// With one citizen and that one placed, a second place is refused.
		if (city.population() == 1) {
			Records.Option other = land.stream().filter(o -> o.group() == 0).findFirst().orElseThrow();
			String[] there = other.type().split(",");
			assertEquals(CommandStatus.REJECTED, send(link, CommandOp.CITY_WORK, city.key(), new PlotCoord(Integer.parseInt(there[0]), Integer.parseInt(there[1])), 0, "toggle").status());
		}
		// The same click again gives the place back to the city's own choice.
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CITY_WORK, city.key(), plot, 0, "toggle").status());
		land = ask(link, "worked", city.key().id(), 0);
		assertTrue(land.stream().noneMatch(o -> o.group() == 2), land.toString());
		// What the city looks to, and back.
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CITY_WORK, city.key(), null, 0, "focus:production").status());
		assertTrue(ask(link, "worked", city.key().id(), 0).stream().anyMatch(o -> o.type().equals("info") && o.name().startsWith("production|")));
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CITY_WORK, city.key(), null, 0, "reset").status());
		assertTrue(ask(link, "worked", city.key().id(), 0).stream().anyMatch(o -> o.type().equals("info") && o.name().startsWith("none|")));
		// Land that is not the city's is refused.
		assertEquals(CommandStatus.REJECTED, send(link, CommandOp.CITY_WORK, city.key(), new PlotCoord(0, 0), 0, "toggle").status());

		// A place the player chose is kept when the turn ends: that is what choosing it is for.
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CITY_WORK, city.key(), plot, 0, "toggle").status());
		settle(link);
		int turn = link.state().turn();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.END_TURN, self(link.state()), null, 0, "").status());
		assertEquals(turn + 1, link.state().turn());
		land = ask(link, "worked", city.key().id(), 0);
		assertTrue(land.stream().anyMatch(o -> o.type().equals(free.type()) && o.group() == 2), "the chosen hex was given up at the end of the turn: " + land);
		assertEquals(1, land.stream().filter(o -> o.type().equals("info")).findFirst().orElseThrow().turns(), "places fixed by the player");
		// "Reset" with a place chosen takes the choice away, and with it what the city was told to look to.
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CITY_WORK, city.key(), null, 0, "focus:gold").status());
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CITY_WORK, city.key(), null, 0, "reset").status());
		land = ask(link, "worked", city.key().id(), 0);
		assertTrue(land.stream().noneMatch(o -> o.group() == 2), "reset left a chosen place: " + land);
		Records.Option after = land.stream().filter(o -> o.type().equals("info")).findFirst().orElseThrow();
		assertEquals(0, after.turns(), "places fixed by the player after reset");
		assertTrue(after.name().startsWith("none|"), after.name());
		assertEquals(after.id(), land.stream().filter(o -> o.group() == 1).count() + after.group(), "every citizen works somewhere or is free: " + land);

		// A hex an enemy stands on cannot be worked. The game would take the citizen away again at once, so the
		// answer has to be no, not "works that land from now on".
		var rome = link.viewer().getCapital(false);
		var rival = link.game().getCivilizations().stream().filter(c -> c.isMajorCiv() && c != link.viewer()).findFirst().orElseThrow();
		link.viewer().getDiplomacyFunctions().makeCivilizationsMeet(rival, false);
		link.viewer().getDiplomacyManager(rival).declareWar(new com.unciv.logic.civilization.diplomacy.DeclareWarReason(com.unciv.logic.civilization.diplomacy.WarType.DirectWar, null));
		com.unciv.logic.map.mapunit.MapUnit foe = null;
		for (var tile : Kt.list(rome.getWorkableTiles())) {
			if (tile.isLand() && !tile.isImpassible() && !tile.isCityCenter() && tile.getMilitaryUnit() == null && tile.getCivilianUnit() == null) {
				var placed = link.game().getTileMap().placeUnitNearTile(tile.getPosition(), "Warrior", rival, null);
				if (placed != null && placed.getTile() == tile) {
					foe = placed;
					break;
				}
				if (placed != null) {
					placed.destroy(false);
				}
			}
		}
		assertNotNull(foe, "no free land around the city to put an enemy on");
		assertTrue(foe.getTile().isBlockaded());
		link.onGameThread(() -> { });
		link.pump();
		EntityKey foeKey = link.state().units().values().stream().filter(u -> u.key().owner() != me && u.type().toUpperCase(java.util.Locale.ROOT).contains("WARRIOR")
			&& ask(link, "worked", city.key().id(), 0).stream().anyMatch(o -> o.type().equals(u.at().x() + "," + u.at().y()))).map(u -> u.key()).findFirst().orElseThrow();
		PlotCoord held = link.state().units().get(foeKey).at();
		assertEquals(CommandStatus.REJECTED, send(link, CommandOp.CITY_WORK, city.key(), held, 0, "toggle").status());
		land = ask(link, "worked", city.key().id(), 0);
		assertTrue(land.stream().noneMatch(o -> o.group() == 2), "a held hex counts as chosen: " + land);
		assertEquals(0, land.stream().filter(o -> o.type().equals("info")).findFirst().orElseThrow().turns());
	}

	@Test
	void aCitizenWorksInABuildingWhenThePlayerSaysSo(@TempDir Path dir) throws IOException {
		UncivLink link = link(dir, 21L);
		int me = link.state().session().viewer();
		var settler = link.state().units().values().stream().filter(u -> u.key().owner() == me && u.type().toUpperCase(java.util.Locale.ROOT).contains("SETTLER")).findFirst().orElseThrow();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.UNIT_ACTION, settler.key(), null, 0, "FoundCity").status());
		settle(link);
		CityState city = link.state().cities().values().stream().filter(c -> c.key().owner() == me).findFirst().orElseThrow();
		// Without a building to work in there is no such place, and asking for one is refused.
		assertTrue(ask(link, "worked", city.key().id(), 0).stream().noneMatch(o -> o.type().startsWith("specialist:")));
		assertEquals(CommandStatus.REJECTED, send(link, CommandOp.CITY_WORK, city.key(), null, 0, "specialist+:Merchant").status());
		// A Market has room for one Merchant.
		link.viewer().getCities().get(0).getCityConstructions().addBuilding("Market");
		Records.Option place = ask(link, "worked", city.key().id(), 0).stream().filter(o -> o.type().equals("specialist:Merchant")).findFirst().orElseThrow();
		assertEquals(0, place.group());
		assertEquals(1, place.id());
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CITY_WORK, city.key(), null, 0, "specialist+:Merchant").status());
		List<Records.Option> land = ask(link, "worked", city.key().id(), 0);
		assertTrue(land.stream().anyMatch(o -> o.type().equals("specialist:Merchant") && o.group() == 1), land.toString());
		// The city has one citizen: with that one in the Market nobody is left for the land, and nobody is idle.
		Records.Option info = land.stream().filter(o -> o.type().equals("info")).findFirst().orElseThrow();
		if (info.id() == 1) {
			assertTrue(land.stream().noneMatch(o -> o.group() == 1 && o.type().contains(",")), land.toString());
			assertEquals(0, info.group(), "idle citizens");
			// No second Merchant, and no place on the land either while the only citizen is in the Market.
			assertEquals(CommandStatus.REJECTED, send(link, CommandOp.CITY_WORK, city.key(), null, 0, "specialist+:Merchant").status());
			Records.Option hex = land.stream().filter(o -> o.group() == 0 && o.type().contains(",")).findFirst().orElseThrow();
			String[] at = hex.type().split(",");
			assertEquals(CommandStatus.REJECTED, send(link, CommandOp.CITY_WORK, city.key(), new PlotCoord(Integer.parseInt(at[0]), Integer.parseInt(at[1])), 0, "toggle").status());
		}
		// The Merchant stays over a turn.
		send(link, CommandOp.END_TURN, self(link.state()), null, 0, "");
		settle(link);
		assertTrue(ask(link, "worked", city.key().id(), 0).stream().anyMatch(o -> o.type().equals("specialist:Merchant") && o.group() == 1));
		// Taken out again, the citizen goes back to the land.
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CITY_WORK, city.key(), null, 0, "specialist-:Merchant").status());
		land = ask(link, "worked", city.key().id(), 0);
		assertTrue(land.stream().anyMatch(o -> o.type().equals("specialist:Merchant") && o.group() == 0), land.toString());
		assertTrue(land.stream().anyMatch(o -> o.group() == 1 && o.type().contains(",")), "nobody works the land: " + land);
		assertEquals(CommandStatus.REJECTED, send(link, CommandOp.CITY_WORK, city.key(), null, 0, "specialist-:Merchant").status());
	}

	@Test
	void theOtherCivilizationsAreDealtWithByUncivsOwnRules(@TempDir Path dir) throws IOException {
		UncivLink link = link(dir, 33L);
		int me = link.state().session().viewer();
		// The capital is founded at once: a wandering Settler and one Warrior do not always live through the war below, and
		// the games are not repeatable (the computer's dice are not seeded), so the test must hold for the unlucky ones too.
		var settler = link.state().units().values().stream().filter(u -> u.key().owner() == me && u.type().toUpperCase(java.util.Locale.ROOT).contains("SETTLER")).findFirst().orElseThrow();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.UNIT_ACTION, settler.key(), null, 0, "FoundCity").status());
		WorldState state = link.state();
		// Nobody is known at the start. A scout would meet them; here the game is told they have met.
		assertTrue(state.relations().isEmpty());
		for (var civ : link.game().getCivilizations()) {
			if (civ != link.viewer() && !civ.isBarbarian() && !civ.isSpectator()) {
				link.viewer().getDiplomacyFunctions().makeCivilizationsMeet(civ, false);
			}
		}
		link.viewer().addGold(2000);
		link.pump();
		send(link, CommandOp.REVEAL, self(state), state.units().values().iterator().next().at(), 1, "");
		state = link.state();
		assertEquals(4, state.relations().size(), state.relations().toString());
		var rival = state.relations().values().stream().filter(r -> !r.minor()).findFirst().orElseThrow();
		var cityState = state.relations().values().stream().filter(r -> r.minor()).findFirst().orElseThrow();
		assertFalse(state.civ(rival.id()).name().startsWith("Unmet"));

		// A gift to a city-state raises its opinion of us.
		// (Meeting a city-state first brings a little gold of its own, so the treasury is read, not assumed.)
		int treasury = state.empire().gold();
		CommandTicket gift = send(link, CommandOp.GIFT, new EntityKey(me, cityState.id()), null, 250, "");
		assertEquals(CommandStatus.CONFIRMED, gift.status(), gift.detail());
		assertTrue(gift.after() > gift.before(), gift.before() + " -> " + gift.after());
		assertEquals(treasury - 250, link.state().empire().gold());

		// Denouncing is ours to do; friendship is theirs to grant or not.
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.DIPLOMACY, new EntityKey(me, rival.id()), null, 0, "denounce").status());
		assertTrue((link.state().relations().get(rival.id()).standing() & nl.jordythomassen.livingworld.state.Relation.F_DENOUNCED) != 0);
		CommandTicket friends = send(link, CommandOp.DIPLOMACY, new EntityKey(me, rival.id()), null, 0, "friendship");
		assertTrue(friends.status().finished());

		// War is declared at once; peace cannot be had the same moment.
		assertTrue(rival.canDeclareWar());
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.DECLARE_WAR, new EntityKey(me, rival.id()), null, 0, "").status());
		assertTrue(link.state().civ(rival.id()).atWarWithViewer());
		CommandTicket soon = send(link, CommandOp.MAKE_PEACE, new EntityKey(me, rival.id()), null, 0, "");
		assertEquals(CommandStatus.REJECTED, soon.status());
		assertTrue(soon.detail().contains("too soon"), soon.detail());

		// The war goes on for a while without anything breaking; proposals can always be listed.
		for (int i = 0; i < 25; i++) {
			settle(link);
			state = link.state();
			for (Records.Option proposal : ask(link, "proposals", 0, 0)) {
				assertFalse(proposal.name().contains("["), proposal.name());
				assertTrue(send(link, CommandOp.ANSWER, self(link.state()), null, 0, proposal.type()).status().finished());
			}
			CommandTicket turn = send(link, CommandOp.END_TURN, self(link.state()), null, 0, "");
			if (turn.status() == CommandStatus.REJECTED && turn.detail().contains("has fallen")) {
				// The war was lost: that is a game the rules allow, said plainly, and the end of what can be tried here.
				System.out.println("the war was lost in turn " + i + ": " + turn.detail());
				return;
			}
			assertEquals(CommandStatus.CONFIRMED, turn.status(), turn.detail());
		}
		CommandTicket later = send(link, CommandOp.MAKE_PEACE, new EntityKey(me, rival.id()), null, 0, "");
		assertTrue(later.status().finished());
		System.out.println("peace after 25 turns of war: " + later.status() + " " + later.detail() + "; friendship asked: " + friends.detail());
	}

	@Test
	void aLongGameStaysPlayableAndDrawable(@TempDir Path dir) throws IOException {
		// The capital is founded at once, so no wandering Settler can be lost to barbarians; then the
		// computer plays the player's turns for a good while: cities grow, build, and more are founded.
		// The computer's players do not play the same game twice, and now and then one of them takes the
		// player's last city long before the end: that game says nothing about a long one, so another is played.
		UncivLink played = null;
		WorldState reached = null;
		for (long seed = 21; seed < 24; seed++) {
			Path folder = java.nio.file.Files.createDirectories(dir.resolve("game" + seed));
			played = link(folder, seed);
			UnitState settler = played.state().units().values().stream().filter(u -> u.type().equals("UNIT_SETTLER")).findFirst().orElseThrow();
			assertEquals(CommandStatus.CONFIRMED, send(played, CommandOp.UNIT_ACTION, settler.key(), null, 0, "FoundCity").status());
			played.simulate(150);
			played.pump();
			reached = played.state();
			if (reached.turn() == 150) {
				break;
			}
			System.out.println("the player's civilization fell at turn " + reached.turn() + " (seed " + seed + "): another game is played");
			played.stop();
		}
		UncivLink link = played;
		WorldState state = reached;
		int me = state.session().viewer();
		assertEquals(150, state.turn(), "one game of three is played to its end");
		List<nl.jordythomassen.livingworld.state.CityState> mine = state.cities().values().stream().filter(c -> c.key().owner() == me).toList();
		assertFalse(mine.isEmpty(), "the player's civilization has cities");
		assertTrue(mine.stream().anyMatch(c -> c.buildings().size() >= 4), "and they have built: " + mine.stream().map(c -> c.name() + c.buildings()).toList());
		assertTrue(state.civ(me).eraTier() >= 1, "it has left the ancient era: " + state.civ(me).era());

		// Every city of every civilization in sight can be drawn, whatever it has built, and has its floor and its flag.
		double hexWidth = 32;
		nl.jordythomassen.livingworld.board.BoardModel board = new nl.jordythomassen.livingworld.board.BoardModel(state,
			new nl.jordythomassen.livingworld.coords.McLayout(hexWidth, -state.session().grid().width() * hexWidth / 2.0,
				state.session().grid().height() * hexWidth * Math.sqrt(3) / 4.0));
		int[] column = new int[nl.jordythomassen.livingworld.board.BoardModel.TOP - nl.jordythomassen.livingworld.board.BoardModel.BASE];
		for (var city : state.cities().values()) {
			int cx = board.centerX(city.at());
			int cz = board.centerZ(city.at());
			java.util.Set<nl.jordythomassen.livingworld.board.Material> seen = java.util.EnumSet.noneOf(nl.jordythomassen.livingworld.board.Material.class);
			for (int x = cx - 14; x <= cx + 14; x++) {
				for (int z = cz - 14; z <= cz + 14; z++) {
					board.column(x, z, column);
					for (int cell : column) {
						seen.add(nl.jordythomassen.livingworld.board.Material.of(cell));
					}
				}
			}
			assertTrue(seen.contains(nl.jordythomassen.livingworld.board.Material.FLAG), city.name() + " flies a flag");
			assertTrue(seen.contains(nl.jordythomassen.livingworld.board.Material.PLANKS), city.name() + " has a hall with a floor");
		}

		// Where a unit can go this turn is listed for the map markers, and every place listed can be gone to.
		UnitState mover = state.units().values().stream().filter(u -> u.key().owner() == me && u.moves() > 0 && u.domain().equals("DOMAIN_LAND")).findFirst().orElseThrow();
		List<Records.Option> reach = ask(link, "reach", mover.key().id(), 0);
		List<Records.Option> go = reach.stream().filter(o -> o.group() == 11).toList();
		assertFalse(go.isEmpty(), mover.name() + " at " + mover.at() + " can go somewhere");
		String[] xy = go.get(0).type().split(",");
		PlotCoord goal = new PlotCoord(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));
		CommandTicket walk = send(link, CommandOp.UNIT_MOVE_TO, mover.key(), goal, 0, "");
		assertEquals(CommandStatus.CONFIRMED, walk.status(), walk.detail());
		assertEquals(goal, link.state().units().get(mover.key()).at(), "a hex marked as reachable this turn is reached this turn");

		// Deals reach further than gold: a rival's city can be asked for (and is refused for nothing), and a third civilization
		// can be brought into it, in Unciv's own words.
		var majors = link.game().getCivilizations().stream().filter(c -> c.isMajorCiv() && !c.isDefeated()).toList();
		for (var a : majors) {
			for (var b : majors) {
				if (a != b && !a.knows(b)) {
					a.getDiplomacyFunctions().makeCivilizationsMeet(b, false);
				}
			}
		}
		link.pump();
		send(link, CommandOp.REVEAL, self(link.state()), mine.get(0).at(), 1, "");
		var landed = majors.stream().filter(c -> c != link.viewer() && c.getCities().size() > 1 && !link.viewer().isAtWarWith(c)).findFirst().orElse(null);
		if (landed != null) {
			int landedId = link.game().getCivilizations().indexOf(landed);
			List<Records.Option> theirs = ask(link, "tradeTake", landedId, 0);
			Records.Option aCity = theirs.stream().filter(o -> o.type().startsWith("take|City|")).findFirst().orElseThrow(() -> new AssertionError(theirs.toString()));
			assertTrue(aCity.name().startsWith("The city of ") && aCity.name().endsWith(" citizens)"), aCity.name());
			// Asking them to go to war with a third party is on the list when there is one they know and are at peace with; the
			// computer plays every game differently, and in some there is nobody left to name.
			boolean third = majors.stream().anyMatch(c -> c != link.viewer() && c != landed && !c.isDefeated() && landed.knows(c) && link.viewer().knows(c) && !landed.isAtWarWith(c));
			if (third) {
				assertTrue(theirs.stream().anyMatch(o -> o.type().startsWith("take|WarDeclaration|") && o.name().startsWith("Declare war on ")),
					theirs.stream().map(Records.Option::name).toList().toString());
			} else {
				System.out.println("a long game: nobody left for " + landed.getCivName() + " to be asked to go to war with");
			}
			EntityKey table = new EntityKey(me, landedId);
			assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.TRADE, table, null, 0, aCity.type()).status());
			List<Records.Option> asked = ask(link, "tradeTable", landedId, 0);
			assertEquals(aCity.name(), asked.get(0).name());
			assertEquals(0, asked.get(asked.size() - 1).turns(), "a city for nothing is refused: " + asked.get(asked.size() - 1).name());
			assertEquals(CommandStatus.REJECTED, send(link, CommandOp.TRADE, table, null, 0, "propose").status());
			assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.TRADE, table, null, 0, "clear").status());
			System.out.println("deal offers of " + landed.getCivName() + ": " + theirs.stream().map(Records.Option::name).toList());
		} else {
			System.out.println("deal offers: no rival with more than one city in this game");
		}

		// A Worker can be told by hand what to build where it stands; the menu says how long each takes.
		int built = 0;
		for (UnitState worker : link.state().units().values().stream().filter(u -> u.key().owner() == me && u.type().equals("UNIT_WORKER")).toList()) {
			send(link, CommandOp.UNIT_ACTION, worker.key(), null, 0, "Stop");
			Records.Option job = ask(link, "unit", worker.key().id(), 0).stream().filter(o -> o.type().startsWith("Build:") && o.turns() == 1).findFirst().orElse(null);
			if (job != null) {
				assertTrue(job.name().matches("Build: .+ \\(\\d+ turns?\\)"), job.name());
				CommandTicket order = send(link, CommandOp.UNIT_ACTION, worker.key(), null, 0, job.type());
				assertEquals(CommandStatus.CONFIRMED, order.status(), order.detail());
				assertFalse(link.state().units().get(worker.key()).waiting(), "a Worker at work does not wait for orders");
				assertTrue(ask(link, "unit", worker.key().id(), 0).stream().anyMatch(o -> o.type().equals("StopBuilding")), "and can be told to stop");
				built++;
			}
		}
		System.out.println("workers given a job by hand: " + built);

		// With governors on, no turn waits for a city: twenty turns end without a choice of production being asked.
		link.setGovernors(true);
		link.pump();
		for (int i = 0; i < 20; i++) {
			// One decision can make room for the next (a policy, then research): answer until nothing is asked.
			assertFalse(link.state().empire().blocking().equals("PRODUCTION"), "turn " + link.state().turn());
			settle(link);
			CommandTicket turn = send(link, CommandOp.END_TURN, self(link.state()), null, 0, "");
			assertEquals(CommandStatus.CONFIRMED, turn.status(), turn.detail());
		}
		assertEquals(170, link.state().turn());
	}

	/** Answers research, policy and production with the first offer until none of them holds the turn up. */
	private void settle(UncivLink link) {
		for (int asked = 0; asked < 8; asked++) {
			WorldState state = link.state();
			String waiting = state.empire().blocking();
			if (waiting.equals("RESEARCH")) {
				send(link, CommandOp.RESEARCH, self(state), null, 0, ask(link, "research", 0, 0).get(0).type());
			} else if (waiting.equals("POLICY")) {
				Records.Option policy = ask(link, "policy", 0, 0).get(0);
				send(link, CommandOp.POLICY, self(state), new PlotCoord(policy.group(), policy.id()), 0, policy.type());
			} else if (waiting.equals("PRODUCTION")) {
				var city = state.cities().values().stream().filter(c -> c.key().owner() == state.session().viewer() && c.productionType().isEmpty()).findFirst().orElseThrow();
				Records.Option build = ask(link, "build", city.key().id(), 0).get(0);
				send(link, CommandOp.CITY_BUILD, city.key(), new PlotCoord(build.group(), build.id()), 0, build.type());
			} else if (waiting.equals("RELIGION")) {
				send(link, CommandOp.BELIEF, self(state), null, 0, ask(link, "beliefs", 0, 0).get(1).type());
			} else {
				return;
			}
		}
	}

	/** Buys the first hex that is for sale next to one of the player's cities, and checks the price and the deed. */
	private void buyLand(UncivLink link, int me) {
		link.viewer().addGold(2000);
		link.pump();
		WorldState state = link.state();
		send(link, CommandOp.REVEAL, self(state), state.cities().values().stream().filter(c -> c.key().owner() == me).findFirst().orElseThrow().at(), 1, "");
		state = link.state();
		PlotCoord forSale = null;
		Records.Option seller = null;
		for (int index = 0; index < state.session().grid().width() * state.session().grid().height() && seller == null; index++) {
			PlotCoord plot = new PlotCoord(index % state.session().grid().width(), index / state.session().grid().width());
			if (state.plot(plot).revealed() && state.plot(plot).owner() < 0) {
				List<Records.Option> offers = ask(link, "land", plot.x(), plot.y());
				if (!offers.isEmpty()) {
					forSale = plot;
					seller = offers.get(0);
				}
			}
		}
		assertTrue(seller != null, "around a city just founded, free land lies next to its own");
		assertTrue(seller.turns() > 0, "it has a price: " + seller);
		int purse = state.empire().gold();
		CommandTicket deed = send(link, CommandOp.CITY_BUY, new EntityKey(me, seller.id()), forSale, 0, "land");
		assertEquals(CommandStatus.CONFIRMED, deed.status(), deed.detail());
		assertEquals(me, link.state().plot(forSale).owner(), "the land is the player's now");
		assertEquals(purse - seller.turns(), link.state().empire().gold(), "for exactly the price that was named");
		assertTrue(ask(link, "land", forSale.x(), forSale.y()).isEmpty(), "and is not for sale twice");
	}

	@Test
	void thePlayerChoosesWhatTheirPeopleBelieve(@TempDir Path dir) throws IOException {
		UncivLink link = link(dir, 21L);
		int me = link.state().session().viewer();
		UnitState settler = link.state().units().values().stream().filter(u -> u.type().equals("UNIT_SETTLER")).findFirst().orElseThrow();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.UNIT_ACTION, settler.key(), null, 0, "FoundCity").status());
		settle(link);
		assertTrue(ask(link, "beliefs", 0, 0).isEmpty(), "nothing to choose without faith");

		// Land that is nobody's and lies next to the city's own can be bought for it, at the game's price.
		buyLand(link, me);

		// A policy is earned: only the branches of the player's own age are on offer, and a later one cannot be had by asking for it.
		link.viewer().getPolicies().setFreePolicies(1);
		link.pump();
		send(link, CommandOp.REVEAL, self(link.state()), link.state().cities().values().iterator().next().at(), 1, "");
		assertEquals("POLICY", link.state().empire().blocking());
		List<String> branches = ask(link, "policy", 0, 0).stream().map(Records.Option::name).toList();
		assertTrue(branches.containsAll(List.of("Tradition", "Liberty", "Honor")), branches.toString());
		for (String later : List.of("Rationalism", "Freedom", "Autocracy", "Order", "Commerce")) {
			assertFalse(branches.contains(later), later + " belongs to a later age: " + branches);
		}
		CommandTicket tooSoon = send(link, CommandOp.POLICY, self(link.state()), new PlotCoord(5, 0), 0, "POLICY_ORDER");
		assertEquals(CommandStatus.REJECTED, tooSoon.status(), tooSoon.detail());
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.POLICY, self(link.state()), new PlotCoord(5, 0), 0, "POLICY_TRADITION").status());
		settle(link);

		// Faith enough for a pantheon: the turn now waits for the player's choice, and the choices are the game's.
		var faith = link.viewer().getReligionManager();
		faith.setStoredFaith(faith.faithForPantheon(0) + 5);
		link.pump();
		send(link, CommandOp.REVEAL, self(link.state()), link.state().cities().values().iterator().next().at(), 1, "");
		assertEquals("RELIGION", link.state().empire().blocking());
		CommandTicket early = send(link, CommandOp.END_TURN, self(link.state()), null, 0, "");
		assertEquals(CommandStatus.REJECTED, early.status());
		assertTrue(early.detail().contains("believe"), early.detail());
		List<Records.Option> gods = ask(link, "beliefs", 0, 0);
		assertEquals(17, gods.get(0).group());
		assertTrue(gods.get(0).name().contains("gods"), gods.get(0).name());
		assertTrue(gods.size() > 10, "a good number of pantheons to choose from: " + gods.size());
		Records.Option god = gods.get(3);
		assertFalse(god.name().contains("["), god.name());
		assertTrue(god.name().contains(":"), "each says what it gives: " + god.name());
		CommandTicket prayed = send(link, CommandOp.BELIEF, self(link.state()), null, 0, god.type());
		assertEquals(CommandStatus.CONFIRMED, prayed.status(), prayed.detail());
		assertEquals(com.unciv.logic.civilization.managers.ReligionState.Pantheon, faith.getReligionState());
		assertTrue(faith.getReligion().hasBelief(god.type()), "the chosen pantheon is the people's");
		assertTrue(ask(link, "beliefs", 0, 0).isEmpty());
		assertFalse(link.state().empire().blocking().equals("RELIGION"));

		// A Great Prophet founds a religion in the capital: again the player chooses, one belief after another.
		var capital = link.viewer().getCapital(false);
		var prophet = link.viewer().getUnits().addUnit("Great Prophet", capital);
		link.pump();
		send(link, CommandOp.REVEAL, self(link.state()), link.state().cities().values().iterator().next().at(), 1, "");
		EntityKey prophetKey = new EntityKey(me, prophet.getId());
		List<Records.Option> deeds = ask(link, "unit", prophetKey.id(), 0);
		assertTrue(deeds.stream().anyMatch(o -> o.type().equals("FoundReligion") && o.turns() == 1), deeds.stream().map(Records.Option::type).toList().toString());
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.UNIT_ACTION, prophetKey, null, 0, "FoundReligion").status());
		assertEquals("RELIGION", link.state().empire().blocking());
		int chosen = 0;
		String last = "";
		for (List<Records.Option> beliefs = ask(link, "beliefs", 0, 0); !beliefs.isEmpty() && chosen < 6; beliefs = ask(link, "beliefs", 0, 0)) {
			assertTrue(beliefs.get(0).name().contains("founds a religion"), beliefs.get(0).name());
			CommandTicket pick = send(link, CommandOp.BELIEF, self(link.state()), null, 0, beliefs.get(1).type());
			assertEquals(CommandStatus.CONFIRMED, pick.status(), pick.detail());
			last = pick.detail();
			chosen++;
		}
		assertTrue(chosen >= 2, "a founder's and a follower's belief at least: " + chosen);
		assertTrue(last.startsWith("Your people have founded "), last);
		assertEquals(com.unciv.logic.civilization.managers.ReligionState.Religion, faith.getReligionState());
		settle(link);
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.END_TURN, self(link.state()), null, 0, "").status());
		System.out.println("religion: " + last + " after " + chosen + " beliefs; pantheon " + god.name());
	}

	@Test
	void thePlayerPutsDealsToOthersAndTheirOwnJudgementDecides(@TempDir Path dir) throws IOException {
		UncivLink link = link(dir, 33L);
		int me = link.state().session().viewer();
		for (var civ : link.game().getCivilizations()) {
			if (civ != link.viewer() && !civ.isBarbarian() && !civ.isSpectator()) {
				link.viewer().getDiplomacyFunctions().makeCivilizationsMeet(civ, false);
			}
		}
		link.viewer().addGold(1000);
		link.pump();
		send(link, CommandOp.REVEAL, self(link.state()), link.state().units().values().iterator().next().at(), 1, "");
		WorldState state = link.state();
		var rival = state.relations().values().stream().filter(r -> !r.minor()).findFirst().orElseThrow();
		EntityKey with = new EntityKey(me, rival.id());
		var them = link.game().getCivilizations().get(rival.id());
		them.addGold(500);

		// The table starts empty.
		List<Records.Option> table = ask(link, "tradeTable", rival.id(), 0);
		assertEquals(1, table.size());
		assertTrue(table.get(0).name().contains("Nothing is on the table"), table.get(0).name());
		assertEquals(CommandStatus.REJECTED, send(link, CommandOp.TRADE, with, null, 0, "propose").status());

		// A gift is welcome: 100 gold for nothing, and the gold changes hands.
		List<Records.Option> mine = ask(link, "tradeGive", rival.id(), 0);
		Records.Option hundred = mine.stream().filter(o -> o.name().equals("100 gold")).findFirst().orElseThrow(() -> new AssertionError(mine.toString()));
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.TRADE, with, null, 0, hundred.type()).status());
		table = ask(link, "tradeTable", rival.id(), 0);
		assertEquals("100 gold", table.get(0).name());
		assertEquals(14, table.get(0).group());
		assertEquals(1, table.get(table.size() - 1).turns(), table.get(table.size() - 1).name());
		int before = link.state().empire().gold();
		int theirs = them.getGold();
		CommandTicket gift = send(link, CommandOp.TRADE, with, null, 0, "propose");
		assertEquals(CommandStatus.CONFIRMED, gift.status(), gift.detail());
		assertEquals(before - 100, link.state().empire().gold());
		assertEquals(theirs + 100, them.getGold());
		assertEquals(1, ask(link, "tradeTable", rival.id(), 0).size(), "the table is empty again");

		// Asking for their gold and giving nothing is refused, by their judgement, and nothing changes hands.
		List<Records.Option> wanted = ask(link, "tradeTake", rival.id(), 0);
		Records.Option theirGold = wanted.stream().filter(o -> o.name().equals("250 gold")).findFirst().orElseThrow(() -> new AssertionError(wanted.toString()));
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.TRADE, with, null, 0, theirGold.type()).status());
		table = ask(link, "tradeTable", rival.id(), 0);
		assertEquals(15, table.get(0).group());
		assertEquals(0, table.get(table.size() - 1).turns());
		assertTrue(table.get(table.size() - 1).name().contains("would refuse"), table.get(table.size() - 1).name());
		CommandTicket greedy = send(link, CommandOp.TRADE, with, null, 0, "propose");
		assertEquals(CommandStatus.REJECTED, greedy.status());
		assertTrue(greedy.detail().contains("refuses"), greedy.detail());
		assertEquals(theirs + 100, them.getGold());

		// Putting enough of one's own next to it turns the refusal into a deal.
		Records.Option five = ask(link, "tradeGive", rival.id(), 0).stream().filter(o -> o.name().equals("500 gold")).findFirst().orElseThrow();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.TRADE, with, null, 0, five.type()).status());
		assertEquals(1, ask(link, "tradeTable", rival.id(), 0).stream().filter(o -> o.group() == 18).findFirst().orElseThrow().turns(), "500 for 250 is acceptable");
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.TRADE, with, null, 0, "clear").status());
		assertEquals(1, ask(link, "tradeTable", rival.id(), 0).size());

		// What they put to the player reads plainly, with no brackets or picture signs left in it, and accepting it is the deal.
		var offer = new com.unciv.logic.trade.Trade();
		offer.getTheirOffers().add(new com.unciv.logic.trade.TradeOffer("Gold", com.unciv.logic.trade.TradeOfferType.Gold, 10, link.game().getSpeed()));
		link.viewer().getTradeRequests().add(new com.unciv.logic.trade.TradeRequest(them.getCivID(), offer));
		List<Records.Option> put = ask(link, "proposals", 0, 0);
		assertEquals(1, put.size());
		assertEquals("From " + them.getCivName() + ": they give 10 gold; nothing from you", put.get(0).name());
		int purse = link.state().empire().gold();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.ANSWER, self(link.state()), null, 1, put.get(0).type()).status());
		assertEquals(purse + 10, link.state().empire().gold());
		assertTrue(ask(link, "proposals", 0, 0).isEmpty());
		assertEquals("+30 Gold from them", UncivLink.words("+[30] \u00A4{Gold} from them"));

		// Something that is not on the game's list cannot be put on the table.
		assertEquals(CommandStatus.REJECTED, send(link, CommandOp.TRADE, with, null, 0, "give|Gold|Gold|999999").status());
		assertEquals(CommandStatus.REJECTED, send(link, CommandOp.TRADE, with, null, 0, "take|City|whatever|1").status());
		System.out.println("trade: offers " + mine.stream().map(Records.Option::name).toList() + " / asks " + wanted.stream().map(Records.Option::name).toList());
	}

	@Test
	void aTakenCityIsThePlayersToDecideAndTakingEveryCapitalWinsTheGame(@TempDir Path dir) throws IOException {
		UncivLink link = new UncivLink(dir.resolve("data"), dir.resolve("game.json"), new UncivLink.Setup("Tiny", "pangaea", 1, 0, "Prince", "Rome", 21L, "Quick"), System.err::println);
		link.start();
		int me = link.state().session().viewer();
		assertFalse(link.game().getGameParameters().getVictoryTypes().isEmpty(), "a game has ways to be won");
		UnitState settler = link.state().units().values().stream().filter(u -> u.type().equals("UNIT_SETTLER")).findFirst().orElseThrow();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.UNIT_ACTION, settler.key(), null, 0, "FoundCity").status());
		link.simulate(12);
		link.pump();
		var rival = link.game().getCivilizations().stream().filter(c -> c.isMajorCiv() && c != link.viewer()).findFirst().orElseThrow();
		var theirCapital = rival.getCapital(false);
		assertTrue(theirCapital != null, "the rival has founded a capital by now");
		assertTrue(drain(link).stream().noneMatch(t -> t.contains("won the game")), "nobody has won yet");

		// The city falls to the player (as the last blow of a siege would have it): held as a puppet, and theirs to decide.
		theirCapital.puppetCity(link.viewer());
		link.pump();
		send(link, CommandOp.REVEAL, self(link.state()), link.state().cities().values().iterator().next().at(), 1, "");
		WorldState state = link.state();
		var taken = state.cities().values().stream().filter(c -> c.key().owner() == me && c.name().equals(theirCapital.getName())).findFirst().orElseThrow();
		assertTrue((taken.flags() & nl.jordythomassen.livingworld.state.CityState.F_PUPPET) != 0);
		List<Records.Option> captured = ask(link, "captured", 0, 0);
		assertEquals(1, captured.size(), captured.toString());
		assertEquals(taken.key().id(), captured.get(0).id());
		List<String> fates = ask(link, "fate", taken.key().id(), 0).stream().map(Records.Option::type).toList();
		assertTrue(fates.containsAll(List.of("puppet", "annex", "liberate")), fates.toString());
		assertFalse(fates.contains("raze"), "a capital cannot be burned down: " + fates);
		assertEquals(CommandStatus.REJECTED, send(link, CommandOp.CITY_FATE, taken.key(), null, 0, "raze").status());
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CITY_FATE, taken.key(), null, 0, "annex").status());
		assertEquals(0, link.state().cities().get(taken.key()).flags() & nl.jordythomassen.livingworld.state.CityState.F_PUPPET);
		assertFalse(ask(link, "fate", taken.key().id(), 0).stream().anyMatch(o -> o.type().equals("annex")), "it is annexed now");

		// Holding every capital is a domination victory: the game says so when the turn ends, once, and can be played on.
		settle(link);
		CommandTicket turn = send(link, CommandOp.END_TURN, self(link.state()), null, 0, "");
		assertEquals(CommandStatus.CONFIRMED, turn.status(), turn.detail());
		List<String> told = drain(link);
		assertTrue(told.stream().anyMatch(t -> t.startsWith("You have won the game")), told.toString());
		settle(link);
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.END_TURN, self(link.state()), null, 0, "").status(), "the game can be played on");
		assertTrue(drain(link).stream().noneMatch(t -> t.contains("won the game")), "and the victory is announced once");
	}

	@Test
	void aPlayerWhoLosesEverythingIsToldAndCanOnlyStartAgain(@TempDir Path dir) throws IOException {
		UncivLink link = new UncivLink(dir.resolve("data"), dir.resolve("game.json"), new UncivLink.Setup("Tiny", "pangaea", 1, 0, "Prince", "Rome", 21L, "Quick"), System.err::println);
		link.start();
		drain(link);
		// The player loses everything: the Settler never founded a city, and both units are gone.
		for (var unit : new java.util.ArrayList<>(Kt.list(link.viewer().getUnits().getCivUnits()))) {
			unit.destroy(false);
		}
		link.pump();
		assertTrue(link.viewer().isDefeated());
		CommandTicket turn = send(link, CommandOp.END_TURN, self(link.state()), null, 0, "");
		assertEquals(CommandStatus.REJECTED, turn.status());
		assertTrue(turn.detail().contains("has fallen") && turn.detail().contains("new game"), turn.detail());
	}

	@Test
	void aCityIsTakenBySendingASoldierIntoItAndAVoteIsThePlayersToCast(@TempDir Path dir) throws IOException {
		UncivLink link = new UncivLink(dir.resolve("data"), dir.resolve("game.json"), new UncivLink.Setup("Tiny", "pangaea", 2, 0, "Prince", "Rome", 21L, "Quick"), System.err::println);
		link.start();
		int me = link.state().session().viewer();
		UnitState settler = link.state().units().values().stream().filter(u -> u.type().equals("UNIT_SETTLER")).findFirst().orElseThrow();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.UNIT_ACTION, settler.key(), null, 0, "FoundCity").status());
		link.simulate(10);
		link.pump();
		var rival = link.game().getCivilizations().stream().filter(c -> c.isMajorCiv() && c != link.viewer() && !c.getCities().isEmpty()).findFirst().orElseThrow();
		var target = rival.getCities().get(0);
		// A siege at its end: the player is at war with them, the city is down to its last, and a Swordsman stands at its gate.
		link.viewer().getDiplomacyFunctions().makeCivilizationsMeet(rival, false);
		link.viewer().getDiplomacyManager(rival).declareWar(new com.unciv.logic.civilization.diplomacy.DeclareWarReason(com.unciv.logic.civilization.diplomacy.WarType.DirectWar, null));
		target.setHealth(1);
		var garrison = target.getCenterTile().getMilitaryUnit();
		if (garrison != null) {
			garrison.destroy(false);
		}
		var soldier = link.game().getTileMap().placeUnitNearTile(target.getCenterTile().getPosition(), "Swordsman", link.viewer(), null);
		assertTrue(soldier != null && soldier.getTile().aerialDistanceTo(target.getCenterTile()) == 1, "the Swordsman stands next to the city");
		for (var tile : Kt.list(target.getCenterTile().getTilesInDistance(3))) {
			tile.setExplored(link.viewer(), true, null);
		}
		link.pump();
		send(link, CommandOp.REVEAL, self(link.state()), link.state().cities().values().stream().filter(c -> c.key().owner() == me).findFirst().orElseThrow().at(), 1, "");
		drain(link);
		WorldState state = link.state();
		var theirs = state.cities().values().stream().filter(c -> c.name().equals(target.getName())).findFirst().orElseThrow();
		assertNotEquals(me, theirs.key().owner());
		assertTrue(ask(link, "reach", soldier.getId(), 0).stream().anyMatch(o -> o.group() == 12 && o.type().equals(theirs.at().x() + "," + theirs.at().y())),
			"the city is marked as something the Swordsman can attack");

		// Sending the soldier onto the city is the attack; the city falls and is the player's, a puppet, to decide.
		CommandTicket assault = send(link, CommandOp.UNIT_MOVE_TO, new EntityKey(me, soldier.getId()), theirs.at(), 0, "");
		assertEquals(CommandStatus.CONFIRMED, assault.status(), assault.detail());
		assertTrue(assault.detail().startsWith("Attacked "), assault.detail());
		state = link.state();
		var taken = state.cities().values().stream().filter(c -> c.name().equals(target.getName())).findFirst().orElseThrow();
		assertEquals(me, taken.key().owner(), "the city changed hands");
		assertTrue((taken.flags() & nl.jordythomassen.livingworld.state.CityState.F_OCCUPIED) != 0, "and is marked as taken this turn");
		boolean told = false;
		for (var event : link.drainEvents()) {
			told |= event instanceof nl.jordythomassen.livingworld.state.WorldEvent.CityChangedHands hands && hands.city().name().equals(target.getName());
		}
		assertTrue(told, "and the Minecraft side is told so, which is where the question of its fate is put");
		List<Records.Option> captured = ask(link, "captured", 0, 0);
		assertTrue(captured.stream().anyMatch(o -> o.id() == taken.key().id()), captured.toString());
		List<String> fates = ask(link, "fate", taken.key().id(), 0).stream().map(Records.Option::type).toList();
		assertFalse(fates.isEmpty(), "there is something to decide about it");
		System.out.println("taken by assault: " + assault.detail() + " fates " + fates);

		// A vote for a world leader is called: the turn waits for the player's vote, which is theirs to give or withhold.
		// (The civilization that just lost its only city is out of the game; the player has met the other one by now.)
		for (var civ : link.game().getCivilizations()) {
			if (civ.isMajorCiv() && civ != link.viewer() && !civ.isDefeated() && !link.viewer().knows(civ)) {
				link.viewer().getDiplomacyFunctions().makeCivilizationsMeet(civ, false);
			}
		}
		link.viewer().addFlag(com.unciv.logic.civilization.CivFlags.TurnsTillNextDiplomaticVote.name(), 0);
		link.pump();
		send(link, CommandOp.REVEAL, self(link.state()), taken.at(), 1, "");
		settle(link);
		assertEquals("VOTE", link.state().empire().blocking());
		List<Records.Option> candidates = ask(link, "vote", 0, 0);
		assertTrue(candidates.size() >= 2 && candidates.get(candidates.size() - 1).type().equals("abstain"), candidates.toString());
		assertEquals(CommandStatus.REJECTED, send(link, CommandOp.END_TURN, self(link.state()), null, 0, "").status(), "the turn waits for the vote");
		Records.Option choice = candidates.get(0);
		CommandTicket vote = send(link, CommandOp.DIPLOMACY, new EntityKey(me, choice.id()), null, 0, choice.type());
		assertEquals(CommandStatus.CONFIRMED, vote.status(), vote.detail());
		assertEquals("Your vote goes to " + choice.name() + ".", vote.detail());
		assertFalse(link.state().empire().blocking().equals("VOTE"));
		assertTrue(ask(link, "vote", 0, 0).isEmpty(), "one vote each");
		settle(link);
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.END_TURN, self(link.state()), null, 0, "").status());
	}

	@Test
	void aircraftStrikeFromTheirCityAtWhatIsInRange(@TempDir Path dir) throws IOException {
		UncivLink link = new UncivLink(dir.resolve("data"), dir.resolve("game.json"), new UncivLink.Setup("Tiny", "pangaea", 1, 0, "Prince", "Rome", 21L, "Quick"), System.err::println);
		link.start();
		int me = link.state().session().viewer();
		UnitState settler = link.state().units().values().stream().filter(u -> u.type().equals("UNIT_SETTLER")).findFirst().orElseThrow();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.UNIT_ACTION, settler.key(), null, 0, "FoundCity").status());
		var rival = link.game().getCivilizations().stream().filter(c -> c.isMajorCiv() && c != link.viewer()).findFirst().orElseThrow();
		link.viewer().getDiplomacyFunctions().makeCivilizationsMeet(rival, false);
		link.viewer().getDiplomacyManager(rival).declareWar(new com.unciv.logic.civilization.diplomacy.DeclareWarReason(com.unciv.logic.civilization.diplomacy.WarType.DirectWar, null));
		var capital = link.viewer().getCapital(false);
		// A Bomber in the capital, and an enemy two hexes off, in the open and in sight of the city.
		var bomber = link.viewer().getUnits().addUnit("Bomber", capital);
		assertTrue(bomber != null && bomber.getTile() == capital.getCenterTile(), "the Bomber is based in the city");
		com.unciv.logic.map.tile.Tile field = null;
		for (var tile : Kt.list(capital.getCenterTile().getTilesInDistanceRange(new kotlin.ranges.IntRange(2, 2)))) {
			if (tile.isLand() && !tile.isImpassible() && tile.getMilitaryUnit() == null && !tile.isCityCenter()) {
				field = tile;
				break;
			}
		}
		assertTrue(field != null, "open land two hexes from the capital");
		var foe = link.game().getTileMap().placeUnitNearTile(field.getPosition(), "Warrior", rival, null);
		assertTrue(foe != null && foe.getTile().aerialDistanceTo(capital.getCenterTile()) <= 2, "the enemy stands within sight of the city");
		for (var tile : Kt.list(capital.getCenterTile().getTilesInDistance(5))) {
			tile.setExplored(link.viewer(), true, null);
		}
		link.pump();
		send(link, CommandOp.REVEAL, self(link.state()), link.state().cities().values().iterator().next().at(), 1, "");
		WorldState state = link.state();
		EntityKey plane = new EntityKey(me, bomber.getId());
		assertTrue(state.units().containsKey(plane), "the Bomber is in the picture: " + state.units().keySet());
		UnitState target = state.units().values().stream().filter(u -> u.key().owner() != me && u.key().id() == foe.getId()).findFirst().orElseThrow();

		// What it can strike is marked like any other attack, and pointing it there is the strike.
		List<Records.Option> reach = ask(link, "reach", plane.id(), 0);
		assertTrue(reach.stream().anyMatch(o -> o.group() == 12 && o.type().equals(target.at().x() + "," + target.at().y())), reach.toString());
		int health = foe.getHealth();
		CommandTicket strike = send(link, CommandOp.UNIT_MOVE_TO, plane, target.at(), 0, "");
		assertEquals(CommandStatus.CONFIRMED, strike.status(), strike.detail());
		assertTrue(strike.detail().startsWith("Attacked "), strike.detail());
		assertTrue(foe.isDestroyed() || foe.getHealth() < health, "the enemy is hit: " + health + " -> " + foe.getHealth());
		assertEquals(capital.getCenterTile(), bomber.getTile(), "and the Bomber is back at its base");
		System.out.println("air strike: " + strike.detail() + " enemy " + health + " -> " + (foe.isDestroyed() ? "destroyed" : foe.getHealth()) + ", bomber " + bomber.getHealth());
	}

	@Test
	void anAtomicBombIsDroppedLikeAnyOtherStrike(@TempDir Path dir) throws IOException {
		UncivLink link = new UncivLink(dir.resolve("data"), dir.resolve("game.json"), new UncivLink.Setup("Tiny", "pangaea", 1, 0, "Prince", "Rome", 21L, "Quick"), System.err::println);
		link.start();
		int me = link.state().session().viewer();
		UnitState settler = link.state().units().values().stream().filter(u -> u.type().equals("UNIT_SETTLER")).findFirst().orElseThrow();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.UNIT_ACTION, settler.key(), null, 0, "FoundCity").status());
		link.simulate(8);
		link.pump();
		var rival = link.game().getCivilizations().stream().filter(c -> c.isMajorCiv() && c != link.viewer() && !c.getCities().isEmpty()).findFirst().orElseThrow();
		link.viewer().getDiplomacyFunctions().makeCivilizationsMeet(rival, false);
		if (!link.viewer().isAtWarWith(rival)) {
			link.viewer().getDiplomacyManager(rival).declareWar(new com.unciv.logic.civilization.diplomacy.DeclareWarReason(com.unciv.logic.civilization.diplomacy.WarType.DirectWar, null));
		}
		var capital = link.viewer().getCapital(false);
		var bomb = link.viewer().getUnits().addUnit("Atomic Bomb", capital);
		assertTrue(bomb != null, "the bomb is in the capital");
		var theirCity = rival.getCities().get(0);
		int distance = capital.getCenterTile().aerialDistanceTo(theirCity.getCenterTile());
		for (var tile : Kt.list(theirCity.getCenterTile().getTilesInDistance(3))) {
			tile.setExplored(link.viewer(), true, null);
		}
		link.pump();
		send(link, CommandOp.REVEAL, self(link.state()), link.state().cities().values().stream().filter(c -> c.key().owner() == me).findFirst().orElseThrow().at(), 1, "");
		// What it can be dropped on is marked like any other attack: their city when that is within range, else the furthest
		// place that is (far from the player's own capital, which the blast would reach too).
		UncivMap map = new UncivMap(link.game().getTileMap());
		PlotCoord city = map.plot(theirCity.getCenterTile());
		List<Records.Option> targets = ask(link, "reach", bomb.getId(), 0).stream().filter(o -> o.group() == 12).toList();
		assertFalse(targets.isEmpty(), "the bomb has something in range");
		PlotCoord ground = null;
		for (Records.Option option : targets) {
			String[] xy = option.type().split(",");
			PlotCoord plot = new PlotCoord(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));
			if (plot.equals(city)) {
				ground = plot;
				break;
			}
			if (ground == null || map.tile(plot).aerialDistanceTo(capital.getCenterTile()) > map.tile(ground).aerialDistanceTo(capital.getCenterTile())) {
				ground = plot;
			}
		}
		var struck = map.tile(ground);
		int people = theirCity.getPopulation().getPopulation();
		CommandTicket drop = send(link, CommandOp.UNIT_MOVE_TO, new EntityKey(me, bomb.getId()), ground, 0, "");
		assertEquals(CommandStatus.CONFIRMED, drop.status(), drop.detail());
		assertTrue(bomb.isDestroyed(), "the bomb is spent");
		boolean fallout = false;
		for (var tile : Kt.list(struck.getTilesInDistance(2))) {
			fallout |= tile.getTerrainFeatures().contains("Fallout");
		}
		boolean cityHit = ground.equals(city) && (!rival.getCities().contains(theirCity) || theirCity.getPopulation().getPopulation() < people);
		assertTrue(fallout || cityHit, "the land is poisoned or the city struck");
		System.out.println("atomic bomb: " + drop.detail() + " on " + ground + (ground.equals(city) ? " (their city, " + distance + " hexes away)" : " (their city is " + distance
			+ " hexes away, out of range)") + ", fallout " + fallout + ", " + targets.size() + " targets in range");
	}

	/** The plain notices the engine has sent since the last call. */
	private static List<String> drain(UncivLink link) {
		List<String> out = new java.util.ArrayList<>();
		for (var event : link.drainEvents()) {
			if (event instanceof nl.jordythomassen.livingworld.state.WorldEvent.Notice notice) {
				out.add(notice.text());
			}
		}
		return out;
	}
}
