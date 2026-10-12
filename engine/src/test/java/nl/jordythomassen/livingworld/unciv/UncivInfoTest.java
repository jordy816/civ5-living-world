package nl.jordythomassen.livingworld.unciv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import nl.jordythomassen.livingworld.state.WorldState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the game says of its own things: every list a player chooses from comes with words for each line of it. */
class UncivInfoTest {
	private int counter;

	private UncivLink link(Path dir) throws IOException {
		UncivLink link = new UncivLink(dir.resolve("data"), dir.resolve("game.json"), new UncivLink.Setup("Tiny", "pangaea", 2, 2, "Prince", "Rome", 21L, "Quick"), System.err::println);
		link.start();
		return link;
	}

	private CommandTicket send(UncivLink link, CommandOp op, EntityKey target, PlotCoord plot, int amount, String item) {
		WorldState state = link.state();
		String id = "i" + (++counter);
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

	private static Map<String, String> byType(List<Records.Option> options) {
		Map<String, String> out = new HashMap<>();
		for (Records.Option option : options) {
			out.put(option.type(), option.name());
		}
		return out;
	}

	/** Every line of a list has words that say what it is; a few are shown in the test's output. */
	private void described(String what, List<Records.Option> list, Map<String, String> about) {
		assertFalse(list.isEmpty(), what + ": there is something to choose");
		int shown = 0;
		for (Records.Option option : list) {
			String text = about.get(option.type());
			assertTrue(text != null && !text.isBlank(), what + ": " + option.name() + " (" + option.type() + ") is described");
			assertFalse(text.contains("[") || text.contains("{"), what + ": plain words for " + option.name() + ": " + text);
			if (shown++ < 3) {
				System.out.println("about " + what + " | " + option.name() + " | " + text.replace("\n", " / "));
			}
		}
	}

	@Test
	void everythingAPlayerChoosesFromSaysWhatItDoes(@TempDir Path dir) throws IOException {
		UncivLink link = link(dir);
		int me = link.state().session().viewer();
		UnitState settler = link.state().units().values().stream().filter(u -> u.type().equals("UNIT_SETTLER")).findFirst().orElseThrow();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.UNIT_ACTION, settler.key(), null, 0, "FoundCity").status());
		CityState rome = link.state().cities().values().stream().filter(c -> c.key().owner() == me).findFirst().orElseThrow();

		Map<String, String> techs = byType(ask(link, "about:research", 0, 0));
		described("research", ask(link, "research", 0, 0), techs);
		// A technology says what it opens a thing a line, each named by its kind: the screen draws those as pictures.
		assertTrue(techs.get("TECH_ARCHERY").contains("Unit: Archer   5 Strength   7 Ranged Strength"), techs.get("TECH_ARCHERY"));
		assertTrue(techs.get("TECH_ARCHERY").contains("Wonder: Temple of Artemis"), techs.get("TECH_ARCHERY"));
		assertTrue(techs.get("TECH_ANIMAL_HUSBANDRY").contains("Improvement: Pasture") && techs.get("TECH_ANIMAL_HUSBANDRY").contains("Reveals: Horses"), techs.get("TECH_ANIMAL_HUSBANDRY"));
		Map<String, String> builds = byType(ask(link, "about:build", rome.key().id(), 0));
		described("build", ask(link, "build", rome.key().id(), 0), builds);
		// What gives a number comes first, the number before its measure; a unit is its numbers.
		assertTrue(builds.get("BUILDING_MONUMENT").startsWith("+2 Culture"), builds.get("BUILDING_MONUMENT"));
		assertTrue(builds.get("UNIT_WARRIOR").startsWith("8 Strength   2 Movement"), builds.get("UNIT_WARRIOR"));
		// Culture enough for a policy: every branch and policy says what it gives.
		link.onGameThread(() -> link.viewer().getPolicies().addCulture(500));
		link.pump();
		Map<String, String> policies = byType(ask(link, "about:policy", 0, 0));
		described("policy", ask(link, "policy", 0, 0), policies);
		for (Map.Entry<String, String> policy : policies.entrySet()) {
			String name = policy.getKey().substring("POLICY_".length()).replace('_', ' ');
			assertFalse(java.util.Arrays.stream(policy.getValue().split("\n")).anyMatch(line -> line.equalsIgnoreCase(name)), "a policy's text does not repeat its name: " + policy.getValue());
		}
		List<Records.Option> tree = ask(link, "policytree", 0, 0);
		assertTrue(tree.stream().anyMatch(o -> o.type().contains("|open|")), "a branch can be opened now");
		assertTrue(tree.stream().filter(o -> o.id() == 0).count() >= 9, "the branches of the tree");
		Records.Option tradition = tree.stream().filter(o -> o.name().equals("Tradition")).findFirst().orElseThrow();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.POLICY, new EntityKey(me, -1), null, 0, tradition.type().split("\\|")[0]).status());
		tree = ask(link, "policytree", 0, 0);
		assertTrue(tree.stream().anyMatch(o -> o.name().equals("Tradition") && o.type().contains("|adopted|")), "Tradition is adopted");
		System.out.println("policy tree: " + tree.stream().filter(o -> o.group() == 0).map(o -> o.id() + " " + o.type()).toList());

		// A soldier with experience: what each promotion gives, and what it has once taken.
		UnitState warrior = link.state().units().values().stream().filter(u -> u.key().owner() == me && u.strength() > 0).findFirst().orElseThrow();
		link.onGameThread(() -> link.game().getTileMap().getTileList().stream().flatMap(t -> Kt.list(t.getUnits()).stream())
			.filter(u -> u.getCiv() == link.viewer() && u.getId() == warrior.key().id()).forEach(u -> u.getPromotions().setXP(30)));
		link.pump();
		List<Records.Option> promotions = ask(link, "promote", warrior.key().id(), 0);
		described("promote", promotions, byType(ask(link, "about:promote", warrior.key().id(), 0)));
		// (Healing at once is offered among them, and is used up rather than kept.)
		Records.Option taken = promotions.stream().filter(o -> !o.name().startsWith("Heal")).findFirst().orElseThrow();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.UNIT_PROMOTE, warrior.key(), null, 0, taken.type()).status());
		List<Records.Option> view = ask(link, "view", warrior.key().id(), 0);
		Records.Option has = view.stream().filter(o -> o.group() == 32).findFirst().orElseThrow(() -> new AssertionError(view.toString()));
		assertTrue(has.name().startsWith(taken.name() + "\n"), has.name());
		assertTrue(view.stream().anyMatch(o -> o.group() == 33 && o.type().equals("xp") && o.turns() > 0), "how far the next promotion is");
		assertTrue(view.stream().anyMatch(o -> o.group() == 30), "the count of proposals");
		assertTrue(view.stream().anyMatch(o -> o.group() == 31 && o.type().equals("faith")), "faith is among the empire's numbers");
		assertTrue(view.stream().anyMatch(o -> o.group() == 31 && o.type().equals("golden")), "and the golden age");
		assertTrue(view.stream().anyMatch(o -> o.group() == 7), "what the unit can do");
		System.out.println("view: " + view.stream().filter(o -> o.group() >= 30).toList());

		// A fight before it is fought.
		var rival = link.game().getCivilizations().stream().filter(c -> c.isMajorCiv() && c != link.viewer()).findFirst().orElseThrow();
		var mine = link.game().getTileMap().getTileList().stream().flatMap(t -> Kt.list(t.getUnits()).stream())
			.filter(u -> u.getCiv() == link.viewer() && u.getId() == warrior.key().id()).findFirst().orElseThrow();
		link.viewer().getDiplomacyFunctions().makeCivilizationsMeet(rival, false);
		link.viewer().getDiplomacyManager(rival).declareWar(new com.unciv.logic.civilization.diplomacy.DeclareWarReason(com.unciv.logic.civilization.diplomacy.WarType.DirectWar, null));
		com.unciv.logic.map.mapunit.MapUnit foe = null;
		for (var tile : Kt.list(mine.getTile().getNeighbors())) {
			if (tile.isLand() && !tile.isImpassible() && tile.getMilitaryUnit() == null && !tile.isCityCenter()) {
				foe = link.game().getTileMap().placeUnitNearTile(tile.getPosition(), "Warrior", rival, null);
				break;
			}
		}
		assertTrue(foe != null && foe.getTile().aerialDistanceTo(mine.getTile()) == 1, "an enemy stands next to the warrior");
		link.pump();
		var map = new UncivMap(link.game().getTileMap());
		int at = map.index(map.plot(foe.getTile()));
		List<Records.Option> fight = ask(link, "battle", warrior.key().id(), at);
		Records.Option attacker = fight.stream().filter(o -> o.group() == 40).findFirst().orElseThrow(() -> new AssertionError(fight.toString()));
		Records.Option defender = fight.stream().filter(o -> o.group() == 41).findFirst().orElseThrow();
		assertEquals(100, attacker.id());
		assertTrue(attacker.turns() > 0 && defender.turns() > 0, "both sides are hurt: " + fight);
		String[] spread = defender.type().split("\\|");
		assertTrue(Integer.parseInt(spread[1]) <= defender.turns() && defender.turns() <= Integer.parseInt(spread[2]), "the least and the most: " + defender);
		System.out.println("battle: " + fight);
		// The city can fire on them too, and says what that would do.
		List<Records.Option> realm = ask(link, "view", -1, 0);
		System.out.println("realm at war: " + realm);
		Records.Option fire = realm.stream().filter(o -> o.type().equals("bombard")).findFirst().orElse(null);
		if (fire != null) {
			String[] xy = fire.name().split(";")[0].split(",");
			PlotCoord target = new PlotCoord(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));
			List<Records.Option> shot = ask(link, "cityBattle", fire.id(), map.index(target));
			assertTrue(shot.stream().anyMatch(o -> o.group() == 41 && o.turns() > 0), "the city's fire hurts: " + shot);
			assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CHOICE, new EntityKey(me, fire.id()), target, 0, "bombard").status());
			assertTrue(ask(link, "view", -1, 0).stream().noneMatch(o -> o.type().equals("bombard") && o.id() == fire.id()), "a city fires once a turn");
		}

		// The pages.
		for (String page : List.of("religion", "victory", "happiness")) {
			List<Records.Option> lines = ask(link, page, 0, 0);
			assertTrue(lines.size() >= 3, page + ": " + lines);
			for (Records.Option line : lines) {
				assertFalse(line.name().contains("[") || line.name().isBlank(), page + ": " + line.name());
			}
			System.out.println(page + ": " + lines.stream().map(o -> o.group() + ":" + o.name()).toList());
		}
		assertTrue(ask(link, "victory", 0, 0).stream().anyMatch(o -> o.name().contains("Ends after turn " + link.game().getSpeed().numTotalTurns() + " ")), "the last turn is the pace's own");
		List<Records.Option> detail = ask(link, "cityinfo", rome.key().id(), 0);
		assertTrue(detail.stream().anyMatch(o -> o.name().contains(" Food ") && o.name().contains(" Production")), detail.toString());
		assertTrue(detail.stream().anyMatch(o -> o.name().startsWith("Palace")), "what stands in it: " + detail);
		System.out.println("city: " + detail.stream().map(o -> o.group() + ":" + o.name()).toList());

		// A line of things to build.
		List<Records.Option> buildable = ask(link, "build", rome.key().id(), 0);
		Records.Option first = buildable.stream().filter(o -> o.group() == 0).findFirst().orElseThrow();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CITY_BUILD, rome.key(), null, 0, first.type()).status());
		Records.Option second = buildable.stream().filter(o -> o.group() == 1).findFirst().orElseThrow();
		CommandTicket lined = send(link, CommandOp.CITY_BUILD, rome.key(), null, 0, "queue:" + second.type());
		assertEquals(CommandStatus.CONFIRMED, lined.status(), lined.detail());
		List<Records.Option> queue = ask(link, "queue", rome.key().id(), 0);
		assertEquals(2, queue.size(), queue.toString());
		assertEquals(second.name(), queue.get(1).name());
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CITY_BUILD, rome.key(), null, 0, "unqueue:1").status());
		assertEquals(1, ask(link, "queue", rome.key().id(), 0).size());
	}

	@Test
	void faithBuysWhatWorkCannotAndAGreatPersonGivenFreelyIsChosen(@TempDir Path dir) throws IOException {
		UncivLink link = link(dir);
		int me = link.state().session().viewer();
		UnitState settler = link.state().units().values().stream().filter(u -> u.type().equals("UNIT_SETTLER")).findFirst().orElseThrow();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.UNIT_ACTION, settler.key(), null, 0, "FoundCity").status());
		CityState rome = link.state().cities().values().stream().filter(c -> c.key().owner() == me).findFirst().orElseThrow();
		assertTrue(ask(link, "faith", rome.key().id(), 0).isEmpty(), "nothing is had for faith before there is a religion");

		// A religion of the player's own, held by the capital, and faith to spend.
		link.onGameThread(() -> {
			var faith = link.viewer().getReligionManager();
			var capital = link.viewer().getCapital(false);
			link.viewer().getUnits().addUnit("Great Prophet", capital);
		});
		link.pump();
		UnitState prophet = link.state().units().values().stream().filter(u -> u.type().equals("UNIT_GREAT_PROPHET") || u.type().equals("UNIT_PROPHET")).findFirst()
			.orElseThrow(() -> new AssertionError(link.state().units().values().toString()));
		CommandTicket founded = send(link, CommandOp.UNIT_ACTION, prophet.key(), null, 0, "FoundReligion");
		assertEquals(CommandStatus.CONFIRMED, founded.status(), founded.detail());
		for (int i = 0; i < 6; i++) {
			List<Records.Option> beliefs = ask(link, "beliefs", 0, 0);
			if (beliefs.isEmpty()) {
				break;
			}
			assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.BELIEF, new EntityKey(me, -1), null, 0, beliefs.get(1).type()).status());
		}
		link.onGameThread(() -> link.viewer().getReligionManager().setStoredFaith(5000));
		link.pump();
		List<Records.Option> holy = ask(link, "faith", rome.key().id(), 0);
		System.out.println("for faith: " + holy);
		assertTrue(holy.stream().anyMatch(o -> o.name().equals("Missionary")), "a Missionary is had for faith: " + holy);
		described("faith", holy, byType(ask(link, "about:faith", rome.key().id(), 0)));
		Records.Option missionary = holy.stream().filter(o -> o.name().equals("Missionary")).findFirst().orElseThrow();
		long before = link.state().units().values().stream().filter(u -> u.type().equals("UNIT_MISSIONARY")).count();
		CommandTicket bought = send(link, CommandOp.CITY_BUY, rome.key(), null, 0, "faith:" + missionary.type());
		assertEquals(CommandStatus.CONFIRMED, bought.status(), bought.detail());
		assertEquals(before + 1, link.state().units().values().stream().filter(u -> u.type().equals("UNIT_MISSIONARY")).count());
		List<Records.Option> religion = ask(link, "religion", 0, 0);
		assertTrue(religion.stream().anyMatch(o -> o.name().startsWith("Your religion: ")), religion.toString());
		System.out.println("religion founded: " + religion.stream().map(o -> o.group() + ":" + o.name()).toList());

		// A Great Person for the choosing.
		link.onGameThread(() -> link.viewer().getGreatPeople().setFreeGreatPeople(1));
		link.pump();
		assertTrue(ask(link, "view", -1, 0).stream().anyMatch(o -> o.type().equals("freeGreat") && o.id() == 1));
		List<Records.Option> people = ask(link, "greatPeople", 0, 0);
		described("greatPeople", people, byType(ask(link, "about:greatPeople", 0, 0)));
		int units = link.state().units().size();
		// (Not the Admiral: he needs water by the capital, which this land does not promise. The game says so, rightly.)
		Records.Option one = people.stream().filter(o -> !o.type().contains("ADMIRAL")).findFirst().orElseThrow();
		CommandTicket chosen = send(link, CommandOp.CHOICE, new EntityKey(me, -1), null, 0, "great:" + one.type());
		assertEquals(CommandStatus.CONFIRMED, chosen.status(), chosen.detail());
		assertEquals(units + 1, link.state().units().size());
		assertTrue(ask(link, "greatPeople", 0, 0).isEmpty(), "and that was the one");
	}

	@Test
	void spiesAreSentWhatACityStateAsksIsToldAndAGameIsKept(@TempDir Path dir) throws IOException {
		UncivLink link = new UncivLink(dir.resolve("data"), dir.resolve("game.json"),
			new UncivLink.Setup("Tiny", "pangaea", 2, 2, "Prince", "Rome", 21L, "Quick", List.of(), java.util.Set.of("espionage"), List.of()), System.err::println);
		link.start();
		int me = link.state().session().viewer();
		UnitState settler = link.state().units().values().stream().filter(u -> u.type().equals("UNIT_SETTLER")).findFirst().orElseThrow();
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.UNIT_ACTION, settler.key(), null, 0, "FoundCity").status());
		CityState rome = link.state().cities().values().stream().filter(c -> c.key().owner() == me).findFirst().orElseThrow();
		assertTrue(link.game().isEspionageEnabled(), "the game was set up with spies");
		assertTrue(ask(link, "spies", 0, 0).isEmpty(), "none before the age for them");

		link.onGameThread(() -> link.viewer().getEspionageManager().addSpy());
		link.pump();
		List<Records.Option> spies = ask(link, "spies", 0, 0);
		assertEquals(1, spies.size(), spies.toString());
		assertTrue(spies.get(0).name().contains("hideout"), spies.get(0).name());
		assertTrue(ask(link, "view", -1, 0).stream().anyMatch(o -> o.type().equals("spies") && o.id() == 1 && o.turns() == 1), "one spy, waiting for orders");
		List<Records.Option> where = ask(link, "spyTargets", 0, 0);
		Records.Option home = where.stream().filter(o -> o.id() == rome.key().id()).findFirst().orElseThrow(() -> new AssertionError(where.toString()));
		CommandTicket sent = send(link, CommandOp.CHOICE, new EntityKey(Integer.parseInt(home.type()), home.id()), null, 0, "spy:0:move");
		assertEquals(CommandStatus.CONFIRMED, sent.status(), sent.detail());
		String now = ask(link, "spies", 0, 0).get(0).name();
		assertTrue(now.contains("Rome"), now);
		System.out.println("spy: " + spies.get(0).name() + " -> " + now + "; could go to " + where.stream().map(Records.Option::name).toList());
		assertEquals(CommandStatus.CONFIRMED, send(link, CommandOp.CHOICE, new EntityKey(me, -1), null, 0, "spy:0:home").status());
		assertEquals(CommandStatus.REJECTED, send(link, CommandOp.CHOICE, new EntityKey(me, -1), null, 0, "spy:5:home").status(), "there is no sixth spy");

		// A unit that cannot go somewhere is told why: the sea before there are ships, a mountain.
		UnitState walker = link.state().units().values().stream().filter(u -> u.key().owner() == me && u.strength() > 0).findFirst().orElseThrow();
		var board = new UncivMap(link.game().getTileMap());
		var sea = link.game().getTileMap().getTileList().stream().filter(t -> t.isWater()).findFirst().orElseThrow();
		link.onGameThread(() -> sea.setExplored(link.viewer(), true, null));
		link.pump();
		CommandTicket wet = send(link, CommandOp.UNIT_MOVE_TO, walker.key(), board.plot(sea), 0, "");
		assertEquals(CommandStatus.REJECTED, wet.status(), wet.detail() + " (to " + sea.baseTerrain + " at " + sea.getPosition() + ", embark " + link.viewer().getTech().getUnitsCanEmbark() + ")");
		assertTrue(wet.detail().contains("Optics"), wet.detail());
		var peak = link.game().getTileMap().getTileList().stream().filter(t -> t.isImpassible() && t.isLand()).findFirst().orElse(null);
		if (peak != null) {
			link.onGameThread(() -> peak.setExplored(link.viewer(), true, null));
			link.pump();
			CommandTicket high = send(link, CommandOp.UNIT_MOVE_TO, walker.key(), board.plot(peak), 0, "");
			assertEquals(CommandStatus.REJECTED, high.status());
			assertTrue(high.detail().contains("impassable"), high.detail());
			System.out.println("no way: " + wet.detail() + " | " + high.detail());
		}

		// A demand of another civilization: put to them, answered with their turn.
		var rival = link.game().getCivilizations().stream().filter(c -> c.isMajorCiv() && c != link.viewer()).findFirst().orElseThrow();
		link.onGameThread(() -> link.viewer().getDiplomacyFunctions().makeCivilizationsMeet(rival, false));
		link.pump();
		int rivalId = link.game().getCivilizations().indexOf(rival);
		CommandTicket demand = send(link, CommandOp.DIPLOMACY, new EntityKey(me, rivalId), null, 0, "demandSettle");
		assertEquals(CommandStatus.CONFIRMED, demand.status(), demand.detail());
		assertTrue(rival.getPopupAlerts().stream().anyMatch(a -> a.getType() == com.unciv.logic.civilization.AlertType.DemandToStopSettlingCitiesNear), "the demand lies before them");
		assertEquals(CommandStatus.REJECTED, send(link, CommandOp.DIPLOMACY, new EntityKey(me, rivalId), null, 0, "demandSettle").status(), "asked once is asked");

		// What a city-state asks, in full (or that it asks nothing yet).
		var minor = link.game().getCivilizations().stream().filter(c -> c.isCityState()).findFirst().orElseThrow();
		link.onGameThread(() -> link.viewer().getDiplomacyFunctions().makeCivilizationsMeet(minor, false));
		link.pump();
		List<Records.Option> asked = ask(link, "quests", link.game().getCivilizations().indexOf(minor), 0);
		assertFalse(asked.isEmpty(), "something is said of what they ask");
		System.out.println("quests: " + asked.stream().map(o -> o.group() + ":" + o.name()).toList());

		// With their turn the computer answers the demand, and the player is told.
		drainNotices(link);
		link.simulate(1);
		link.pump();
		assertTrue(rival.getPopupAlerts().stream().noneMatch(a -> a.getType() == com.unciv.logic.civilization.AlertType.DemandToStopSettlingCitiesNear), "the demand was answered");
		List<String> told = drainNotices(link);
		System.out.println("demand answered: " + told.stream().filter(t -> t.toLowerCase().contains("settl") || t.toLowerCase().contains("promise") || t.toLowerCase().contains("agree")
			|| t.toLowerCase().contains("refus")).toList());

		// A copy of the game as it stands, to go back to.
		Path kept = dir.resolve("kept").resolve("Turn 0 Rome.json");
		java.util.concurrent.atomic.AtomicReference<Boolean> done = new java.util.concurrent.atomic.AtomicReference<>();
		link.keep(kept, done::set);
		link.pump();
		assertEquals(Boolean.TRUE, done.get());
		UncivLink again = new UncivLink(dir.resolve("data"), kept, UncivLink.Setup.standard(), System.err::println);
		again.start();
		assertEquals(link.state().session().gameId(), again.state().session().gameId());
		assertEquals(1, again.state().cities().values().stream().filter(c -> c.key().owner() == me).count(), "the kept game has the city");
	}

	private static List<String> drainNotices(UncivLink link) {
		List<String> out = new java.util.ArrayList<>();
		for (var event : link.drainEvents()) {
			if (event instanceof nl.jordythomassen.livingworld.state.WorldEvent.Notice notice) {
				out.add(notice.text());
			}
		}
		return out;
	}
}
