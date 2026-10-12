package nl.jordythomassen.livingworld.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import nl.jordythomassen.livingworld.link.CivLink;
import nl.jordythomassen.livingworld.link.LinkStatus;
import nl.jordythomassen.livingworld.link.PlayerMarker;
import nl.jordythomassen.livingworld.protocol.Wire;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.Legend;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldEvent;
import nl.jordythomassen.livingworld.state.WorldState;
import org.junit.jupiter.api.Test;

/**
 * The real Lua script, run in a Lua 5.1 interpreter against a fake Civ API, with the real bridge
 * on the other end. This is the contract between the two halves: what the Lua prints is exactly
 * what the Java side has to understand.
 */
class CivScriptTest {
	private final List<String> log = new ArrayList<>();
	private int commandNumber;

	private CivLink link(LuaGame game) {
		CivLink link = new CivLink(() -> game, CivScript::source, log::add);
		link.setRetryMs(0);
		return link;
	}

	private WorldState live(CivLink link) throws IOException {
		for (int i = 0; i < 3; i++) {
			link.pumpOnce();
		}
		assertEquals(LinkStatus.LIVE, link.status(), String.join("\n", log));
		link.drainEvents();
		return link.state();
	}

	private GameCommand command(WorldState state, CommandOp op, EntityKey target, PlotCoord plot, int amount) {
		String id = "c" + (++commandNumber);
		return new GameCommand(id, id, state.session().bootId(), state.session().gameId(), "Steve", state.turn(), op, target, plot, amount);
	}

	private GameCommand choice(WorldState state, CommandOp op, EntityKey target, PlotCoord plot, int amount, String item) {
		String id = "c" + (++commandNumber);
		return new GameCommand(id, id, state.session().bootId(), state.session().gameId(), "Steve", state.turn(), op, target, plot, amount, item);
	}

	private List<nl.jordythomassen.livingworld.protocol.Records.Option> ask(CivLink link, String kind, int a, int c) throws IOException {
		java.util.concurrent.atomic.AtomicReference<List<nl.jordythomassen.livingworld.protocol.Records.Option>> answer = new java.util.concurrent.atomic.AtomicReference<>();
		link.ask(kind, a, c, answer::set);
		link.pumpOnce();
		return answer.get();
	}

	@Test
	void theEmpireItsAgeAndItsNeighboursComeAcross() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		WorldState state = live(link(game));
		assertEquals(List.of(), log.stream().filter(l -> l.contains("Civ reported") || l.contains("dropped")).toList());
		var empire = state.empire();
		assertEquals(300, empire.gold());
		assertEquals(3, empire.goldPerTurn());
		assertEquals("ERA_ANCIENT", empire.era());
		assertEquals("3800 BC", empire.yearText());
		assertEquals("TECH_POTTERY", empire.researchType());
		assertEquals("Pottery", empire.researchName());
		assertEquals(7, empire.researchTurns());
		assertEquals(java.util.Set.of("AGRICULTURE"), state.techs());
		assertTrue(state.knows("AGRICULTURE"));
		assertEquals("ERA_ANCIENT", state.civ(0).era());
		assertEquals(0, state.civ(0).eraTier());
		assertEquals("ARTSTYLE_GRECO_ROMAN", state.civ(0).artStyle());
		CityState rome = state.cities().get(new EntityKey(0, 8192));
		assertEquals(List.of("MONUMENT"), rome.buildings());
		assertEquals(List.of("PYRAMID"), rome.wonders(), "a world wonder is told apart from an ordinary building");
		var egypt = state.relations().get(1);
		assertFalse(egypt.minor());
		assertEquals("GUARDED", egypt.word());
		assertTrue(egypt.canDeclareWar());
		var geneva = state.relations().get(22);
		assertTrue(geneva.minor());
		assertEquals(35, geneva.influence());
		assertEquals(1, geneva.standing(), "friends");
		assertEquals("CULTURED", geneva.word());
		assertEquals(List.of("KILL_CAMP"), geneva.quests());
		assertEquals(250, geneva.giftSmall());
		assertEquals(1000, geneva.giftLarge());
		assertNull(state.relations().get(2), "someone not met yet is not listed");
	}

	@Test
	void aNewEraAndNewTechnologiesAreEvents() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		live(link);
		game.run("F.techs[1] = true; F.research = 2; F.era = 1; F.turn = 6");
		link.pumpOnce();
		List<WorldEvent> events = link.drainEvents();
		assertTrue(events.stream().anyMatch(e -> e instanceof WorldEvent.TechsLearned t && t.techs().equals(java.util.Set.of("POTTERY"))), events.toString());
		assertTrue(events.stream().anyMatch(e -> e instanceof WorldEvent.EraChanged c && c.to().equals("ERA_CLASSICAL")));
		assertEquals(1, link.state().civ(0).eraTier());
	}

	@Test
	void aCitysProductionIsChosenFromTheGamesOwnList() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		WorldState state = live(link);
		var options = ask(link, "build", 8192, 0);
		assertEquals(List.of("BUILDING_GRANARY", "UNIT_WARRIOR", "UNIT_WORKER"), options.stream().map(o -> o.type()).toList());
		var granary = options.get(0);
		assertEquals(1, granary.group());
		assertEquals("Granary", granary.name());
		assertEquals(6, granary.turns());
		CommandTicket ticket = link.submit(choice(state, CommandOp.CITY_BUILD, new EntityKey(0, 8192), new PlotCoord(granary.group(), granary.id()), 0, granary.type()));
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(CommandStatus.CONFIRMED, ticket.status(), ticket.detail());
		assertEquals(1, game.eval("#F.orders").toint());
		assertEquals(1, game.eval("F.orders[1].order").toint(), "a construct order");
		assertTrue(game.eval("F.orders[1].replace").toboolean(), "it replaces what was being built, as the production screen does");
		assertEquals("BUILDING_GRANARY", link.state().cities().get(new EntityKey(0, 8192)).productionType());
		// Something the game would not offer is refused by the game, whatever this side sends.
		List<String> raw = game.call("LivingWorld.cmd(601,\"b1\",\"build\",0,8192,1,2,0)", "LW\t<\t601\t", 1000);
		assertTrue(raw.stream().anyMatch(l -> l.contains("CANNOT")));
		assertEquals(1, game.eval("#F.orders").toint());
	}

	@Test
	void researchIsChosenFromTheGamesOwnList() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		WorldState state = live(link);
		var options = ask(link, "research", 0, 0);
		assertTrue(options.stream().anyMatch(o -> o.type().equals("TECH_BRONZE_WORKING") && o.turns() == 7));
		assertTrue(options.stream().noneMatch(o -> o.type().equals("TECH_AGRICULTURE")), "what is known is not offered");
		var bronze = options.stream().filter(o -> o.type().equals("TECH_BRONZE_WORKING")).findFirst().orElseThrow();
		CommandTicket ticket = link.submit(choice(state, CommandOp.RESEARCH, new EntityKey(0, -1), new PlotCoord(0, bronze.id()), 0, bronze.type()));
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(CommandStatus.CONFIRMED, ticket.status(), ticket.detail());
		assertEquals(2, game.eval("F.researchSent[1].tech").toint());
		assertEquals(-1, game.eval("F.researchSent[1].steal").toint());
		assertEquals("TECH_BRONZE_WORKING", link.state().empire().researchType());
	}

	@Test
	void walkingSomewhereExploresTheLandAround() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		WorldState state = live(link);
		assertFalse(state.plot(new PlotCoord(6, 2)).revealed());
		CommandTicket ticket = link.submit(command(state, CommandOp.REVEAL, new EntityKey(0, -1), new PlotCoord(5, 2), 1));
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(CommandStatus.CONFIRMED, ticket.status(), ticket.detail());
		assertTrue(ticket.after() >= 1, "Civ says how many plots were new");
		assertTrue(link.state().plot(new PlotCoord(6, 2)).revealed());
		assertFalse(link.state().plot(new PlotCoord(7, 2)).revealed(), "one hex far, not two");
		assertTrue(link.drainEvents().stream().anyMatch(e -> e instanceof WorldEvent.PlotChanged p && p.at().equals(new PlotCoord(6, 2))));
		// From a place the civ does not know, nothing can be explored.
		CommandTicket blind = link.submit(command(link.state(), CommandOp.REVEAL, new EntityKey(0, -1), new PlotCoord(7, 4), 1));
		assertEquals(CommandStatus.REJECTED, blind.status());
		// Standing in the same spot again finds nothing new and is still fine.
		CommandTicket again = link.submit(command(link.state(), CommandOp.REVEAL, new EntityKey(0, -1), new PlotCoord(5, 2), 1));
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(CommandStatus.CONFIRMED, again.status(), again.detail());
		assertEquals(0, again.after());
	}

	@Test
	void aWorkersJobsFollowTheGamesRulesForTechAndTerritory() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		// A forested hill inside Rome's borders, next to the capital.
		game.run("local p = Map.GetPlot(3, 3); p.owner = 0");
		WorldState state = live(link);
		assertEquals(List.of(), ask(link, "works", 3, 3), "no Bronze Working: the forest cannot be touched, and no Wheel: no road");
		game.run("F.techs[2] = true; F.turn = 6");
		link.pumpOnce();
		var options = ask(link, "works", 3, 3);
		assertEquals(List.of("BUILD_FARM", "BUILD_REMOVE_FOREST"), options.stream().map(o -> o.type()).toList());
		var clear = options.get(1);
		CommandTicket ticket = link.submit(command(link.state(), CommandOp.IMPROVE, new EntityKey(0, -1), new PlotCoord(3, 3), clear.id()));
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(CommandStatus.CONFIRMED, ticket.status(), ticket.detail());
		assertEquals(-1, game.eval("Map.GetPlot(3, 3).feature").toint());
		assertEquals(32, game.eval("F.rome.production").toint(), "the timber goes to the nearest city, as in the game");
		assertEquals(-1, link.state().plot(new PlotCoord(3, 3)).feature());
		// Now a farm fits.
		var farm = ask(link, "works", 3, 3).stream().filter(o -> o.type().equals("BUILD_FARM")).findFirst().orElseThrow();
		assertEquals("Construct a Farm", farm.name(), "the game's link markup is taken out of its names");
		CommandTicket built = link.submit(command(link.state(), CommandOp.IMPROVE, new EntityKey(0, -1), new PlotCoord(3, 3), farm.id()));
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(CommandStatus.CONFIRMED, built.status(), built.detail());
		assertEquals(3, game.eval("Map.GetPlot(3, 3).improvement").toint());
		assertEquals("IMPROVEMENT_FARM", link.state().legend().type(Legend.Kind.IMPROVEMENT, link.state().plot(new PlotCoord(3, 3)).improvement()));
		// Outside the borders nothing is built: refused here, and refused by the game if it got that far.
		CommandTicket outside = link.submit(command(link.state(), CommandOp.IMPROVE, new EntityKey(0, -1), new PlotCoord(4, 3), farm.id()));
		assertEquals(CommandStatus.REJECTED, outside.status());
		List<String> raw = game.call("LivingWorld.cmd(602,\"w1\",\"improve\",0,-1,4,3,0)", "LW\t<\t602\t", 1000);
		assertTrue(raw.stream().anyMatch(l -> l.contains("CANNOT")));
		assertEquals(-1, game.eval("Map.GetPlot(4, 3).improvement").toint());
	}

	@Test
	void aGreatPersonsImprovementAndAnotherCivsOwnAreNeverOnOffer() throws IOException {
		for (boolean workerTable : new boolean[] { true, false }) {
			LuaGame game = new LuaGame(8, 6);
			if (!workerTable) {
				// A game whose table of Worker builds cannot be read: the improvement's own flags still hold.
				game.run("GameInfo.Unit_Builds = nil");
			}
			CivLink link = link(game);
			// Open grassland inside Rome's borders, every technology known.
			game.run("local p = Map.GetPlot(3, 3); p.owner = 0; p.feature = -1; F.techs[2] = true; F.techs[3] = true");
			live(link);
			List<String> offered = ask(link, "works", 3, 3).stream().map(o -> o.type()).toList();
			assertEquals(List.of("BUILD_FARM", "BUILD_ROAD"), offered, "worker table readable: " + workerTable);
			// Asked for by id all the same (4 = Academy, 5 = Moai): the game side refuses and builds nothing.
			for (int build : new int[] { 4, 5 }) {
				List<String> raw = game.call("LivingWorld.cmd(" + (610 + build) + ",\"g" + build + "\",\"improve\",0,-1,3,3," + build + ")", "LW\t<\t" + (610 + build) + "\t", 1000);
				assertTrue(raw.stream().anyMatch(l -> l.contains("CANNOT")), raw.toString());
				assertEquals(-1, game.eval("Map.GetPlot(3, 3).improvement").toint());
			}
		}
	}

	@Test
	void aGiftToACityStateIsOneOfTheGamesThreeSizesAndRaisesInfluence() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		WorldState state = live(link);
		CommandTicket ticket = link.submit(command(state, CommandOp.GIFT, new EntityKey(0, 22), null, 250));
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(CommandStatus.CONFIRMED, ticket.status(), ticket.detail());
		assertEquals(250, game.eval("F.gifts[1].amount").toint());
		assertEquals(60, link.state().relations().get(22).influence());
		assertEquals(2, link.state().relations().get(22).standing(), "allies now");
		assertEquals(50, link.state().empire().gold());
		CommandTicket poor = link.submit(command(link.state(), CommandOp.GIFT, new EntityKey(0, 22), null, 250));
		assertEquals(CommandStatus.REJECTED, poor.status());
		assertTrue(poor.detail().contains("treasury"));
		CommandTicket odd = link.submit(command(link.state(), CommandOp.GIFT, new EntityKey(0, 22), null, 7));
		assertEquals(CommandStatus.REJECTED, odd.status());
		assertEquals(1, game.eval("#F.gifts").toint());
	}

	@Test
	void warIsDeclaredThroughTheGamesOwnCallAndOnlyOnce() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		WorldState state = live(link);
		CommandTicket ticket = link.submit(command(state, CommandOp.DECLARE_WAR, new EntityKey(0, 1), null, 0));
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(CommandStatus.CONFIRMED, ticket.status(), ticket.detail());
		assertTrue(game.eval("F.war[1]").toboolean());
		assertTrue(link.state().civ(1).atWarWithViewer());
		CommandTicket again = link.submit(command(link.state(), CommandOp.DECLARE_WAR, new EntityKey(0, 1), null, 0));
		assertEquals(CommandStatus.REJECTED, again.status());
		CommandTicket cityState = link.submit(command(link.state(), CommandOp.DECLARE_WAR, new EntityKey(0, 22), null, 0));
		assertEquals(CommandStatus.REJECTED, cityState.status());
	}

	@Test
	void theScriptIsValidLua51BothAsWrittenAndAsSent() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		game.run(CivScript.raw());
		assertEquals(Wire.PROTOCOL, game.eval("LivingWorld.VERSION").toint());
		LuaGame second = new LuaGame(8, 6);
		second.run(CivScript.source());
		assertEquals(Wire.PROTOCOL, second.eval("LivingWorld.VERSION").toint());
		assertTrue(CivScript.source().length() < CivScript.raw().length());
	}

	@Test
	void outsideAGameTheScriptRefusesToLoad() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		game.run("Game = nil");
		CivLink link = link(game);
		link.pumpOnce();
		assertEquals(LinkStatus.NO_MATCH, link.status());
		assertNull(link.state());
	}

	@Test
	void theBridgeLoadsTheScriptInPiecesAndReadsTheWholeGame() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		WorldState state = live(link);
		assertTrue(game.longestCommand <= 1400, "longest command " + game.longestCommand);

		assertEquals(8, state.session().grid().width());
		assertEquals(6, state.session().grid().height());
		assertEquals(0.5, state.session().grid().oddRowShift());
		assertEquals(64.0, state.session().civLayout().columnStep(), 1e-6);
		assertEquals(55.4256, state.session().civLayout().rowStep(), 1e-6);
		assertEquals(5, state.turn());
		assertEquals(0, state.session().viewer());

		CivInfo rome = state.civ(0);
		assertEquals("Rome", rome.name());
		assertEquals("Augustus Caesar", rome.leader());
		assertEquals("CIVILIZATION_ROME", rome.type());
		assertEquals(0x460076, rome.color());
		assertTrue(rome.human());
		assertEquals("Egypt", state.civ(1).name());
		assertEquals("Unmet civilization", state.civ(2).name(), "a civ not met yet stays anonymous");
		assertFalse(state.civ(2).metByViewer());
		assertTrue(state.civ(63).barbarian());

		assertEquals("TERRAIN_PLAINS", state.legend().type(Legend.Kind.TERRAIN, 1));
		assertEquals("Forest", state.legend().name(Legend.Kind.FEATURE, 5));

		PlotState hill = state.plot(new PlotCoord(3, 3));
		assertEquals(PlotState.HILLS, hill.elevation());
		assertEquals("FEATURE_FOREST", state.legend().type(Legend.Kind.FEATURE, hill.feature()));
		assertEquals(PlotState.MOUNTAIN, state.plot(new PlotCoord(1, 3)).elevation());
		PlotState farm = state.plot(new PlotCoord(4, 2));
		assertEquals("IMPROVEMENT_FARM", state.legend().type(Legend.Kind.IMPROVEMENT, farm.improvement()));
		assertEquals("ROUTE_ROAD", state.legend().type(Legend.Kind.ROUTE, farm.route()));
		assertEquals(PlotState.RIVER_EAST | PlotState.RIVER_SOUTH_WEST, state.plot(new PlotCoord(3, 1)).rivers());
		assertTrue(state.plot(new PlotCoord(0, 0)).water());
		assertTrue(state.plot(new PlotCoord(2, 2)).city());
		assertEquals(0, state.plot(new PlotCoord(2, 2)).owner());
		assertTrue(state.plot(new PlotCoord(4, 2)).visible());
		assertFalse(state.plot(new PlotCoord(5, 2)).visible());
		assertTrue(state.plot(new PlotCoord(5, 2)).revealed());

		CityState city = state.cities().get(new EntityKey(0, 8192));
		assertEquals("Rome", city.name());
		assertEquals(new PlotCoord(2, 2), city.at());
		assertEquals(3, city.population());
		assertTrue(city.capital());
		assertTrue(city.details());
		assertEquals("UNIT_WARRIOR", city.productionType());
		assertEquals("Warrior", city.productionName());
		assertEquals(12, city.production());
		assertEquals(40, city.productionNeeded());
		assertEquals(6, city.productionTurns());
		assertEquals(3, city.food());
		assertEquals(15, city.foodNeeded());
		assertEquals(180, city.hp());
		assertEquals(9, city.strength());

		UnitState warrior = state.units().get(new EntityKey(0, 8193));
		assertEquals("UNIT_WARRIOR", warrior.type());
		assertEquals(new PlotCoord(3, 2), warrior.at());
		assertEquals(100, warrior.hp());
		assertEquals(120, warrior.moves());
		assertEquals(8, warrior.strength());
		assertEquals(0, warrior.rangedStrength(), "a method this game version lacks falls back to a default");
		assertEquals("DOMAIN_LAND", warrior.domain());
		assertTrue(warrior.combat());
		assertEquals(2, warrior.level());
		assertEquals("Wor\tker", state.units().get(new EntityKey(0, 8194)).name(), "names with odd characters survive the trip");
	}

	@Test
	void fogOfWarIsEnforcedInsideCiv() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		WorldState state = live(link(game));
		assertEquals(PlotState.UNKNOWN, state.plot(new PlotCoord(6, 2)));
		assertEquals(PlotState.UNKNOWN, state.plot(new PlotCoord(7, 4)));
		assertTrue(state.units().containsKey(new EntityKey(1, 8195)), "an enemy in sight is shown");
		assertFalse(state.units().containsKey(new EntityKey(1, 8196)), "an enemy on a plot seen earlier but not now is not");
		assertFalse(state.cities().containsKey(new EntityKey(1, 8200)), "a city on a plot never seen is not");
		// Not even the raw text mentions it.
		for (String line : game.call("LivingWorld.snap(999,15)", "LW\t<\t999\t", 1000)) {
			assertFalse(line.contains("Thebes"));
			assertFalse(line.contains("8196"));
		}
	}

	@Test
	void civsOwnHooksTriggerAFreshLookAndArePassedAlong() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		live(link);
		assertTrue(game.eval("F.handlerCount(Events, 'SerialEventUnitCreated')").toint() == 1);
		game.run("F.newUnit = F.addUnit(0, 9000, 10, 'Warrior', 2, 2, 8); F.fire(Events, 'SerialEventUnitCreated', 0, 9000)");
		link.pumpOnce();
		List<WorldEvent> events = link.drainEvents();
		assertTrue(events.stream().anyMatch(e -> e instanceof WorldEvent.UnitAppeared a && a.unit().key().equals(new EntityKey(0, 9000))));
		assertEquals(1, link.hintsSeen());

		game.run("F.warrior.x = 4; F.fire(GameEvents, 'UnitSetXY', 0, 8193, 4, 2)");
		link.pumpOnce();
		assertTrue(link.drainEvents().stream().anyMatch(e -> e instanceof WorldEvent.UnitMoved m
			&& m.from().equals(new PlotCoord(3, 2)) && m.unit().at().equals(new PlotCoord(4, 2))));

		game.run("F.war[1] = true; F.fire(Events, 'WarStateChanged', 0, 1, true)");
		link.pumpOnce();
		assertTrue(link.drainEvents().stream().anyMatch(e -> e instanceof WorldEvent.WarChanged w && w.atWar() && w.civ().id() == 1));

		game.run("F.turn = 6; F.fire(Events, 'ActivePlayerTurnStart')");
		link.pumpOnce();
		assertTrue(link.drainEvents().stream().anyMatch(e -> e instanceof WorldEvent.TurnChanged t && t.to() == 6));
	}

	@Test
	void aChangeWithoutAnyHookIsStillNoticed() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		live(link);
		// No event fired at all: the regular re-read has to catch it.
		game.run("F.rome.pop = 4; F.turn = 6");
		link.pumpOnce();
		assertTrue(link.drainEvents().stream().anyMatch(e -> e instanceof WorldEvent.CityChanged c && c.city().population() == 4));
	}

	@Test
	void theMarkerSitsAtTheExactWorldPositionAndReusesAStockIcon() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		live(link);
		assertEquals(0, game.eval("F.built").toint(), "no marker until a player is on the board");
		link.setMarker(new PlayerMarker(new GridPos(3.25, 2.4), true, false, "Jordy (Minecraft)"));
		link.pumpOnce();
		assertEquals("SettlerReccomendation", game.eval("F.instanceName").tojstring());
		assertEquals(3.25 * 64.0, game.eval("F.marker.Anchor.x").todouble(), 1e-3);
		assertEquals(2.4 * 55.4256, game.eval("F.marker.Anchor.y").todouble(), 1e-3);
		assertEquals(7 + 3, game.eval("F.marker.Anchor.z").todouble(), 1e-6, "height of the plot under the player");
		assertFalse(game.eval("F.marker.Anchor.hidden").toboolean());
		assertEquals("Jordy (Minecraft)", game.eval("F.marker.Icon.tooltip").tojstring());
		assertEquals("LEADER_ATLAS", game.eval("F.icons[1].atlas").tojstring());
		assertEquals(0, game.eval("#F.lookAt").toint(), "the camera stays put unless asked to follow");

		link.setMarker(new PlayerMarker(new GridPos(3.4, 2.4), true, true, "Jordy (Minecraft)"));
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(1, game.eval("#F.lookAt").toint(), "following looks at a plot once, not every beat");
		link.setMarker(new PlayerMarker(new GridPos(4.4, 2.0), true, true, "Jordy (Minecraft)"));
		link.pumpOnce();
		assertEquals(2, game.eval("#F.lookAt").toint());
		assertEquals(1, game.eval("F.built").toint(), "one marker, moved around");

		link.setMarker(PlayerMarker.HIDDEN);
		link.pumpOnce();
		assertTrue(game.eval("F.marker.Anchor.hidden").toboolean());
	}

	@Test
	void theNameFloatsUpWhenThePlayerStepsIntoAnotherHex() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		game.run("F.popups = {}; Events.AddPopupTextEvent = function(pos, text, delay) table.insert(F.popups, { x = pos.x, text = text }) end");
		CivLink link = link(game);
		live(link);
		link.setMarker(new PlayerMarker(new GridPos(3.1, 2.0), true, false, "Jordy"));
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(1, game.eval("#F.popups").toint(), "once on arriving, not on every beat");
		assertEquals("Jordy", game.eval("F.popups[1].text").tojstring());
		link.setMarker(new PlayerMarker(new GridPos(4.1, 2.0), true, false, "Jordy"));
		link.pumpOnce();
		assertEquals(2, game.eval("#F.popups").toint());
	}

	@Test
	void aChangedScriptReplacesAnOlderCopyStillLoadedInTheGame() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		live(link(game));
		String firstBoot = game.eval("LivingWorld.boot").tojstring();
		// Another build of this side, with a script that differs by one comment-free line.
		List<String> log2 = new ArrayList<>();
		CivLink newer = new CivLink(() -> game, () -> CivScript.source() + "LivingWorld.extra = 1\n", log2::add);
		newer.setRetryMs(0);
		for (int i = 0; i < 3; i++) {
			newer.pumpOnce();
		}
		assertEquals(LinkStatus.LIVE, newer.status(), String.join("\n", log2));
		assertEquals(1, game.eval("LivingWorld.extra").toint());
		assertFalse(firstBoot.equals(game.eval("LivingWorld.boot").tojstring()));
		assertEquals(1, game.eval("F.handlerCount(Events, 'SerialEventUnitCreated')").toint(), "the old copy unhooked itself");
	}

	@Test
	void aDeliveryChangesTheRealCityOnceAndIsConfirmed() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		WorldState state = live(link);
		GameCommand delivery = command(state, CommandOp.CITY_ADD_PRODUCTION, new EntityKey(0, 8192), null, 10);
		CommandTicket ticket = link.submit(delivery);
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(CommandStatus.CONFIRMED, ticket.status(), ticket.detail());
		assertEquals(22, game.eval("F.rome.production").toint());
		assertEquals(12, ticket.before());
		assertEquals(22, ticket.after());
		assertTrue(game.eval("F.dirty").toint() > 0, "the game's own screens are told to refresh");
		// The very same request reaching Civ a second time is answered from memory.
		List<String> again = game.call(nl.jordythomassen.livingworld.link.LuaCalls.command(777, delivery), "LW\t<\t777\t", 1000);
		assertEquals(22, game.eval("F.rome.production").toint());
		assertTrue(again.stream().anyMatch(l -> l.contains("before=12") && l.contains("after=22")));
	}

	@Test
	void anEscortOrderGoesThroughTheGamesOwnMovePath() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		WorldState state = live(link);
		CommandTicket ticket = link.submit(command(state, CommandOp.UNIT_MOVE_TO, new EntityKey(0, 8193), new PlotCoord(5, 2), 0));
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(8193, game.eval("F.moves[1].unit.id").toint());
		assertEquals(5, game.eval("F.moves[1].x").toint());
		assertEquals(CommandStatus.CONFIRMED, ticket.status(), ticket.detail());
		assertEquals(new PlotCoord(4, 2), link.state().units().get(new EntityKey(0, 8193)).at());
	}

	@Test
	void healingAWoundedUnitIsReadBackFromTheGame() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		live(link);
		game.run("F.warrior.damage = 60; F.fire(Events, 'SerialEventUnitSetDamage', 0, 8193)");
		link.pumpOnce();
		CommandTicket ticket = link.submit(command(link.state(), CommandOp.UNIT_HEAL, new EntityKey(0, 8193), null, 25));
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(CommandStatus.CONFIRMED, ticket.status(), ticket.detail());
		assertEquals(35, game.eval("F.warrior.damage").toint());
		assertEquals(65, link.state().units().get(new EntityKey(0, 8193)).hp());
	}

	@Test
	void theTurnEndsThroughTheGamesOwnButtonPathAndOnlyWhenItWould() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		WorldState state = live(link);
		game.run("F.canEndTurn = false");
		CommandTicket blocked = link.submit(command(state, CommandOp.END_TURN, new EntityKey(0, -1), null, 0));
		link.pumpOnce();
		assertEquals(CommandStatus.REJECTED, blocked.status());
		assertTrue(blocked.detail().contains("needs a decision"), blocked.detail());
		assertEquals(0, game.eval("F.endTurns").toint());
		game.run("F.canEndTurn = true");
		CommandTicket ticket = link.submit(command(state, CommandOp.END_TURN, new EntityKey(0, -1), null, 0));
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(1, game.eval("F.endTurns").toint());
		assertEquals(CommandStatus.CONFIRMED, ticket.status(), ticket.detail());
		assertEquals(6, link.state().turn());
	}

	@Test
	void aTurnCivRefusedToEndCanBeEndedOnceTheDecisionIsMade() throws IOException {
		// Found on the real game: Minecraft asks with one key per turn, so that pressing twice ends one
		// turn. A refusal must not use that key up, or the turn can never be ended from Minecraft.
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		WorldState state = live(link);
		String key = "endturn-" + state.session().bootId() + "-" + state.turn();
		java.util.function.Supplier<GameCommand> ask = () -> new GameCommand("e" + (++commandNumber), key, state.session().bootId(), state.session().gameId(),
			"Steve", state.turn(), CommandOp.END_TURN, new EntityKey(0, -1), null, 0);
		game.run("F.canEndTurn = false");
		CommandTicket refused = link.submit(ask.get());
		link.pumpOnce();
		assertEquals(CommandStatus.REJECTED, refused.status());
		game.run("F.canEndTurn = true");
		CommandTicket again = link.submit(ask.get());
		assertTrue(again != refused, "a new attempt, not the refused one handed back");
		// Pressed twice now: still one attempt, and one turn.
		assertTrue(link.submit(ask.get()) == again);
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(CommandStatus.CONFIRMED, again.status(), again.detail());
		assertEquals(1, game.eval("F.endTurns").toint());
		assertTrue(link.submit(ask.get()) == again, "and once it is done, asking again does nothing");
	}

	@Test
	void aPolicyIsChosenFromWhatCivOffersAndTheTurnCanThenEnd() throws IOException {
		// Found on the real game: with a policy due, Civ refuses to end the turn, and Minecraft had no way to adopt one.
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		WorldState state = live(link);
		assertEquals(List.of(), ask(link, "policy", 0, 0), "nothing to adopt while no policy is due");
		assertEquals("", state.empire().blocking());
		game.run("F.policyDue = true; F.turn = 6");
		link.pumpOnce();
		assertEquals("POLICY", link.state().empire().blocking());
		assertTrue(link.state().empire().policyDue());
		CommandTicket turn = link.submit(command(link.state(), CommandOp.END_TURN, new EntityKey(0, -1), null, 0));
		link.pumpOnce();
		assertEquals(CommandStatus.REJECTED, turn.status());
		assertTrue(turn.detail().contains("adopt a social policy"), turn.detail());
		// At first only branches can be opened.
		var branches = ask(link, "policy", 0, 0);
		assertEquals(List.of("POLICY_BRANCH_TRADITION", "POLICY_BRANCH_LIBERTY"), branches.stream().map(o -> o.type()).toList());
		assertTrue(branches.stream().allMatch(o -> o.group() == 5));
		CommandTicket opened = link.submit(choice(link.state(), CommandOp.POLICY, new EntityKey(0, -1), new PlotCoord(5, branches.get(0).id()), 0, branches.get(0).type()));
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(CommandStatus.CONFIRMED, opened.status(), opened.detail());
		assertEquals(1, link.state().empire().policies());
		assertEquals("", link.state().empire().blocking());
		// The next time one is due, a policy inside the open branch is on offer next to the other branch.
		game.run("F.policyDue = true; F.turn = 7");
		link.pumpOnce();
		var next = ask(link, "policy", 0, 0);
		assertEquals(List.of("POLICY_BRANCH_LIBERTY", "POLICY_ARISTOCRACY"), next.stream().map(o -> o.type()).toList());
		CommandTicket adopted = link.submit(choice(link.state(), CommandOp.POLICY, new EntityKey(0, -1), new PlotCoord(6, next.get(1).id()), 0, next.get(1).type()));
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(CommandStatus.CONFIRMED, adopted.status(), adopted.detail());
		assertEquals(2, link.state().empire().policies());
		// The branch that is open already cannot be "opened" again, whatever the game's own check says.
		game.run("F.policyDue = true");
		List<String> again = game.call("LivingWorld.cmd(639,\"p8\",\"policy\",0,-1,5,0,0)", "LW\t<\t639\t", 1000);
		assertTrue(again.stream().anyMatch(l -> l.contains("CANNOT")), again.toString());
		// One that is not on offer is refused by the game.
		List<String> raw = game.call("LivingWorld.cmd(640,\"p9\",\"policy\",0,-1,6,9,0)", "LW\t<\t640\t", 1000);
		assertTrue(raw.stream().anyMatch(l -> l.contains("CANNOT")), raw.toString());
	}

	@Test
	void newsOnCivsScreenIsPutAwayWhenThePlayerAsksForTheEndOfTheTurn() throws IOException {
		// Found on the real game: "You have researched Mining - Continue" stays up until someone presses
		// the button, and until then the turn cannot end. A player in Minecraft is not there to press it.
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		WorldState state = live(link);
		game.run("F.news = true; F.turn = 6");
		link.pumpOnce();
		assertEquals("NEWS", link.state().empire().blocking());
		assertEquals("", link.state().empire().waitingFor(), "news is not a decision the player has to make");
		CommandTicket first = link.submit(command(link.state(), CommandOp.END_TURN, new EntityKey(0, -1), null, 0));
		link.pumpOnce();
		assertEquals(CommandStatus.REJECTED, first.status());
		assertTrue(first.detail().contains("put away"), first.detail());
		assertEquals(1, game.eval("F.dequeued").toint(), "closed the way its own button does it");
		assertEquals(0, game.eval("F.endTurns").toint());
		// Asked again, the turn ends.
		CommandTicket second = link.submit(command(link.state(), CommandOp.END_TURN, new EntityKey(0, -1), null, 0));
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(CommandStatus.CONFIRMED, second.status(), second.detail());
		assertEquals(1, game.eval("F.endTurns").toint());
		assertEquals(1, game.eval("F.dequeued").toint(), "and nothing is closed when nothing is up");
	}

	@Test
	void unitsWithoutOrdersWaitWhenThePlayerEndsTheTurnFromMinecraft() throws IOException {
		// Found on the real game: with a unit waiting for orders, UI.CanEndTurn says yes but the end-turn
		// control does nothing, so the turn seemed to end and never did.
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		WorldState state = live(link);
		game.run("F.warrior.idle = true; F.turn = 6");
		link.pumpOnce();
		assertEquals("UNITS", link.state().empire().blocking());
		CommandTicket first = link.submit(command(link.state(), CommandOp.END_TURN, new EntityKey(0, -1), null, 0));
		link.pumpOnce();
		assertEquals(CommandStatus.REJECTED, first.status(), "not reported as ended while Civ did nothing");
		assertTrue(first.detail().contains("ask again"), first.detail());
		assertEquals(1, game.eval("F.skipped").toint(), "the idle unit was told to wait this turn");
		assertEquals(0, game.eval("F.endTurns").toint());
		CommandTicket second = link.submit(command(link.state(), CommandOp.END_TURN, new EntityKey(0, -1), null, 0));
		link.pumpOnce();
		link.pumpOnce();
		assertEquals(CommandStatus.CONFIRMED, second.status(), second.detail());
		assertEquals(1, game.eval("F.endTurns").toint());
	}

	@Test
	void civHasTheLastWordOnEveryCommand() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		WorldState state = live(link);
		// Things this side cannot know in advance are refused by the script itself.
		game.run("F.multiplayer = true");
		CommandTicket a = link.submit(command(state, CommandOp.CITY_ADD_PRODUCTION, new EntityKey(0, 8192), null, 10));
		link.pumpOnce();
		assertEquals(CommandStatus.REJECTED, a.status());
		assertTrue(a.detail().contains("multiplayer"), a.detail());
		assertEquals(12, game.eval("F.rome.production").toint());
		game.run("F.multiplayer = false");
		// A request for something that is not the player's never changes anything, even if sent raw.
		List<String> raw = game.call("LivingWorld.cmd(555,\"x1\",\"prod\",1,8200,-1,-1,10)", "LW\t<\t555\t", 1000);
		assertTrue(raw.stream().anyMatch(l -> l.contains("NOT_YOURS")));
		raw = game.call("LivingWorld.cmd(556,\"x2\",\"prod\",0,8192,-1,-1,5000)", "LW\t<\t556\t", 1000);
		assertTrue(raw.stream().anyMatch(l -> l.contains("BAD_AMOUNT")));
		raw = game.call("LivingWorld.cmd(557,\"x3\",\"nuke\",0,8192,-1,-1,1)", "LW\t<\t557\t", 1000);
		assertTrue(raw.stream().anyMatch(l -> l.contains("UNKNOWN_OP")));
		assertEquals(12, game.eval("F.rome.production").toint());
	}

	@Test
	void wideMapsAreSentInPiecesThatFitOnePrint() throws IOException {
		LuaGame game = new LuaGame(300, 4);
		game.run("for i = 0, 300 * 4 - 1 do F.plots[i].revealed = true; F.plots[i].city = false end");
		CivLink link = link(game);
		WorldState state = live(link);
		assertTrue(game.longestLine <= LuaGame.MAX_PRINT, "longest printed line " + game.longestLine);
		assertEquals(300, state.session().grid().width());
		for (int x = 0; x < 300; x++) {
			assertTrue(state.plot(new PlotCoord(x, 2)).revealed(), "plot " + x);
		}
		assertEquals(1, state.plot(new PlotCoord(297, 2)).terrain());
	}

	@Test
	void reloadingTheScriptLeavesNoOldHooksBehind() throws IOException {
		LuaGame game = new LuaGame(8, 6);
		CivLink link = link(game);
		live(link);
		link.setMarker(new PlayerMarker(new GridPos(3, 2), true, false, "x"));
		link.pumpOnce();
		String firstBoot = link.state().session().bootId();
		game.run(CivScript.source());
		assertEquals(1, game.eval("F.handlerCount(Events, 'SerialEventUnitCreated')").toint());
		assertEquals(1, game.eval("F.released").toint(), "the old marker was given back");
		link.pumpOnce();
		link.pumpOnce();
		assertNotNull(link.state());
		assertFalse(firstBoot.equals(link.state().session().bootId()), "a fresh load is a fresh session");
	}
}
