package nl.jordythomassen.livingworld.mc;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import nl.jordythomassen.livingworld.board.BoardModel;
import nl.jordythomassen.livingworld.commands.CommandOp;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.protocol.Records;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * The built-in game on a real Minecraft server without players (-Dlivingworld.selftest=true): a
 * new game is started, drawn, and played for a few turns through the same code a player's clicks
 * reach. One line per check in the log, then the server stops.
 */
final class EngineSelfTest {
	private final LivingWorldServer session;
	private int stage;
	private int waited;
	private int failures;
	private ServerPlayer fake;
	private EntityKey settler;
	private EntityKey warrior;
	private EntityKey capital;
	private PlotCoord goal;
	private int turnBefore;
	private int turnsPlayed;
	private final AtomicReference<List<Records.Option>> answer = new AtomicReference<>();
	private boolean asked;
	private Records.Option bought;
	private int goldBefore;
	private String oldGame;
	private boolean rung;
	private int rungAt;
	private String chosenBelief = "";
	private int dealStep;
	private int lastAsked;
	private int ticks;

	EngineSelfTest(LivingWorldServer session) {
		this.session = session;
	}

	private void check(String what, boolean ok, String detail) {
		if (!ok) {
			failures++;
		}
		LivingWorldMod.LOG.info("SELFTEST {} {}{}", ok ? "ok  " : "FAIL", what, detail.isEmpty() ? "" : " (" + detail + ")");
	}

	/** The second of two people in a game for two. */
	private ServerPlayer other;

	private int handStep;
	private int handWait;
	private int handFood;
	private int handGold;
	private int handWork;

	private static BlockPos ground(BoardModel model, PlotCoord plot) {
		int x = model.centerX(plot) + 5;
		int z = model.centerZ(plot) + 5;
		return new BlockPos(x, model.standY(x, z) - 1, z);
	}

	/**
	 * What a ruler's own hands may and may not do down on the land: they dig their own land and
	 * nobody else's, carry what it yields to a city, and leave cities and wonders standing.
	 * Takes a few ticks; true when it is done.
	 */
	private boolean hands(WorldState state, BoardModel model, ServerLevel level, int me) {
		CityState city = state.cities().get(capital);
		if (city == null) {
			return true;
		}
		switch (handStep) {
			case 0 -> {
				PlotCoord own = null;
				PlotCoord wild = null;
				var grid = state.session().grid();
				for (PlotCoord near : grid.neighbors(city.at())) {
					PlotState there = state.plot(near);
					if (own == null && there.owner() == me && !there.water() && there.elevation() != PlotState.MOUNTAIN) {
						own = near;
					}
				}
				for (int x = 0; x < grid.width() && wild == null; x++) {
					for (int y = 0; y < grid.height() && wild == null; y++) {
						PlotState there = state.plot(new PlotCoord(x, y));
						if (there.revealed() && there.owner() < 0 && !there.water() && there.elevation() != PlotState.MOUNTAIN && !there.city()) {
							wild = new PlotCoord(x, y);
						}
					}
				}
				if (own == null || wild == null) {
					check("on foot a ruler digs their own land, and nobody else's, and no city", false, "no land to try it on: " + own + " / " + wild);
					handStep = 3;
					return true;
				}
				BlockPos mine = ground(model, own);
				BlockPos theirs = ground(model, wild);
				BlockPos town = new BlockPos(model.centerX(city.at()), BoardModel.LAND, model.centerZ(city.at()));
				session.painter.paintNow(level, level.getChunk(mine.getX() >> 4, mine.getZ() >> 4));
				session.painter.paintNow(level, level.getChunk(theirs.getX() >> 4, theirs.getZ() >> 4));
				BlockState wildBefore = level.getBlockState(theirs);
				BlockState hall = level.getBlockState(town);
				boolean kept = !fake.gameMode.destroyBlock(theirs) && level.getBlockState(theirs) == wildBefore;
				boolean stands = !fake.gameMode.destroyBlock(town) && level.getBlockState(town) == hall;
				boolean dug = !level.getBlockState(mine).isAir() && fake.gameMode.destroyBlock(mine) && level.getBlockState(mine).isAir();
				check("on foot a ruler digs their own land, and nobody else's, and no city", kept && stands && dug,
					"nobody's: " + session.handsTied(fake, theirs) + " / the city: " + session.handsTied(fake, town) + " / their own: " + (dug ? "dug" : session.handsTied(fake, mine)));
				fake.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT, 2));
				fake.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 2));
				fake.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.RAW_GOLD, 1));
				handFood = city.food();
				handGold = state.empire().gold();
				handWork = city.production();
				session.as(fake, () -> session.deliver(fake, capital));
				handStep = 1;
				handWait = 0;
				return false;
			}
			case 1 -> {
				if (++handWait < 30) {
					return false;
				}
				boolean idle = city.productionType().isEmpty() || city.productionType().startsWith("PROCESS_");
				boolean fed = city.food() == handFood + 2;
				boolean paid = state.empire().gold() == handGold + 4;
				boolean helped = idle ? fake.getInventory().countItem(net.minecraft.world.item.Items.OAK_LOG) == 2 : city.production() == handWork + 4;
				check("what the land yields is carried to a city: wheat feeds it, gold fills the treasury, wood goes into what it builds", fed && paid && helped,
					"food " + handFood + " -> " + city.food() + ", gold " + handGold + " -> " + state.empire().gold() + ", "
						+ (idle ? "the city builds nothing, the logs came back" : "work " + handWork + " -> " + city.production()));
				var engine = (nl.jordythomassen.livingworld.unciv.UncivLink) session.link;
				engine.onGameThread(() -> engine.viewer().getCapital(false).getCityConstructions().addBuilding("The Pyramids"));
				handStep = 2;
				handWait = 0;
				return false;
			}
			case 2 -> {
				if (++handWait < 40) {
					return false;
				}
				BoardModel.Site site = model.wonderSites().stream().filter(s -> s.city().equals(capital)).findFirst().orElse(null);
				boolean built = false;
				String detail = "the Pyramids have no hex of their own: " + city.wonders();
				if (site != null) {
					int x = model.centerX(site.plot());
					int z = model.centerZ(site.plot());
					session.painter.paintNow(level, level.getChunk(x >> 4, z >> 4));
					BlockPos cap = new BlockPos(x, model.standY(x, z) - 1, z);
					built = level.getBlockState(cap).is(Blocks.GOLD_BLOCK) && session.handsTied(fake, cap) != null && cap.getY() >= BoardModel.LAND + 9
						&& state.session().grid().distance(site.plot(), city.at()) <= 2;
					detail = "on " + site.plot().x() + "," + site.plot().y() + ", " + (cap.getY() - BoardModel.LAND) + " high, capped with " + level.getBlockState(cap).getBlock().getName().getString()
						+ "; breaking it: " + session.handsTied(fake, cap);
				}
				check("a wonder of the world stands on a hex of its own beside its city, and stays standing", built, detail);
				handStep = 3;
				return true;
			}
			default -> {
				return true;
			}
		}
	}

	private void next() {
		stage++;
		waited = 0;
	}

	/** Asks the game for one of its lists once and returns it when it has arrived. */
	private List<Records.Option> choices(String kind, int a, int c) {
		if (!asked) {
			asked = true;
			answer.set(null);
			session.link.ask(kind, a, c, options -> answer.set(options == null ? List.of() : options));
		}
		List<Records.Option> got = answer.get();
		if (got != null) {
			asked = false;
		}
		return got;
	}

	void tick() {
		if (stage < 99 && ++ticks > 9000) {
			// Whatever happens, the test ends: a server that waits for ever tells nobody anything.
			check("the self-test finishes within a few minutes", false, "stopped in stage " + stage);
			finish();
			return;
		}
		WorldState state = session.state();
		BoardModel model = session.model();
		if (state == null || model == null) {
			if (++waited > 1200) {
				check("a game is set up and shown within a minute", false, session.link.status().text + " " + session.link.statusDetail());
				finish();
			}
			return;
		}
		ServerLevel level = session.server.overworld();
		int me = state.session().viewer();
		switch (stage) {
			case 0 -> {
				check("the game runs inside the mod", session.builtin, "");
				check("a new game: the player's civilization has a Settler and a Warrior and no city yet",
					state.units().values().stream().filter(u -> u.key().owner() == me).count() == 2 && state.cities().isEmpty(), state.units().values().toString());
				settler = state.units().values().stream().filter(u -> u.type().equals("UNIT_SETTLER")).findFirst().map(UnitState::key).orElse(null);
				warrior = state.units().values().stream().filter(u -> u.type().equals("UNIT_WARRIOR")).findFirst().map(UnitState::key).orElse(null);
				if (settler == null || warrior == null) {
					finish();
					return;
				}
				PlotCoord at = state.units().get(settler).at();
				int cx = model.centerX(at);
				int cz = model.centerZ(at);
				for (int dx = -3; dx <= 3; dx++) {
					for (int dz = -3; dz <= 3; dz++) {
						LevelChunk chunk = level.getChunk((cx >> 4) + dx, (cz >> 4) + dz);
						session.painter.paintNow(level, chunk);
					}
				}
				check("the land around the Settler is painted", session.painter.changedBlocks() > 10_000, session.painter.changedBlocks() + " blocks changed");
				next();
			}
			case 1 -> {
				// Characters only come into being near a player, in loaded chunks; with nobody on the server both are arranged by hand.
				for (Proxies.Proxy proxy : session.proxies.all()) {
					level.getChunk((int) Math.floor(proxy.x) >> 4, (int) Math.floor(proxy.z) >> 4);
				}
				session.proxies.showAll(level);
				Proxies.Proxy shown = session.proxies.unit(settler);
				// Really in the world, not only asked for: with no player near, a chunk that is still settling in can hold a new
				// entity back for a tick or two, and the character is then made anew. Seen once in dozens of runs, on a busy machine.
				Proxies.Proxy other = session.proxies.unit(warrior);
				boolean there = shown != null && shown.entity != null && level.getEntity(shown.entity) != null
					&& other != null && other.entity != null && level.getEntity(other.entity) != null;
				if (++waited < 40 || (!there && waited < 240)) {
					return;
				}
				check("both units stand in the world as characters", shown != null && session.proxies.unit(warrior) != null && shown.entity != null,
					shown == null ? "no character for the Settler at all; " + session.proxies.unitCount() + " units known" : "at " + shown.x + "," + shown.y + "," + shown.z
						+ " chunk loaded " + level.getChunkSource().hasChunk((int) Math.floor(shown.x) >> 4, (int) Math.floor(shown.z) >> 4) + " entity " + shown.entity);
				fake = net.fabricmc.fabric.api.entity.FakePlayer.get(level);
				double[] home = session.home();
				fake.setPos(home[0], home[1], home[2]);
				{
					// Every kind of unit the rules know has a creature of this game to stand for it, and the game takes each of them.
					var engine = (nl.jordythomassen.livingworld.unciv.UncivLink) session.link;
					java.util.Map<String, Integer> cast = new java.util.TreeMap<>();
					List<String> refused = new java.util.ArrayList<>();
					int id = 900_000;
					for (var base : engine.game().getRuleset().getUnits().values()) {
						String domain = base.isWaterUnit() ? "DOMAIN_SEA" : base.isAirUnit() ? "DOMAIN_AIR" : "DOMAIN_LAND";
						UnitState one = new UnitState(new EntityKey(0, id++), "UNIT_" + base.getName().toUpperCase(java.util.Locale.ROOT).replace(' ', '_'), base.getName(),
							new PlotCoord(0, 0), 100, 100, 120, 120, base.getStrength(), base.getRangedStrength(), domain, 0, 0, base.isCivilian() ? 0 : UnitState.F_COMBAT);
						String made = session.proxies.tryFigure(level, one);
						if (made.startsWith("REFUSED")) {
							refused.add(base.getName() + ": " + made);
						} else {
							cast.merge(made, 1, Integer::sum);
						}
					}
					check("every kind of unit is one of Minecraft's creatures, and the game takes them all", refused.isEmpty() && cast.size() >= 15,
						refused.isEmpty() ? cast.toString() : refused.toString());
				}
				check("a player is handed the Codex", session.codex.give(fake) && Codex.carries(fake), "");
				try {
					session.codex.open(fake);
					session.codex.travel(fake);
					session.codex.settings(fake);
					check("the pages of the Codex build and open", true, "");
				} catch (RuntimeException e) {
					check("the pages of the Codex build and open", false, e.toString());
				}
				// Right-clicking a unit of one's own selects it and opens its orders.
				var entity = level.getEntity(session.proxies.unit(settler).entity);
				boolean answered = entity != null && session.interact(fake, entity);
				check("right-clicking the Settler selects it", answered && settler.equals(session.selectedUnit(fake)),
					entity == null ? "its character is not in the world" : !answered ? "the click was not taken for a click on a unit" : "selected: " + session.selectedUnit(fake));
				// The same click also arrives as "used the item in hand", and again if the button is held a moment: neither may order the unit away.
				fake.setItemInHand(InteractionHand.MAIN_HAND, Codex.item("Rome"));
				long given = session.commandsGiven();
				boolean swallowed = session.useItem(fake, InteractionHand.MAIN_HAND) && session.useItem(fake, InteractionHand.MAIN_HAND);
				check("one click on a unit is one click: it selects, and orders nothing", swallowed && session.commandsGiven() == given && settler.equals(session.selectedUnit(fake)),
					(session.commandsGiven() - given) + " orders given");
				fake.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
				// A second player in the same world rules the same people, with a selection of their own.
				var second = net.fabricmc.fabric.api.entity.FakePlayer.get(level, new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("second".getBytes()), "Second"));
				second.setPos(fake.getX(), fake.getY(), fake.getZ());
				var theirs = level.getEntity(session.proxies.unit(warrior).entity);
				boolean both = theirs != null && session.interact(second, theirs) && warrior.equals(session.selectedUnit(second)) && settler.equals(session.selectedUnit(fake));
				session.deselect(second);
				check("two players each have their own selected unit, and letting one go leaves the other's", both && session.selectedUnit(second) == null
					&& settler.equals(session.selectedUnit(fake)), "first: " + session.selectedUnit(fake) + ", second: " + session.selectedUnit(second));
				session.left(second);
				next();
			}
			case 2 -> {
				List<Records.Option> can = choices("unit", settler.id(), 0);
				if (can == null) {
					return;
				}
				Records.Option found = can.stream().filter(o -> o.type().equals("FoundCity")).findFirst().orElse(null);
				check("the Settler's orders include founding a city, here and now", found != null && found.turns() == 1, can.stream().map(Records.Option::name).toList().toString());
				session.submit(fake, CommandOp.UNIT_ACTION, settler, null, 0, null, "Settler", null, "FoundCity", false);
				next();
			}
			case 3 -> {
				CityState city = state.cities().values().stream().filter(c -> c.key().owner() == me).findFirst().orElse(null);
				if (city == null && ++waited < 100) {
					return;
				}
				check("the Settler founded the capital, from Minecraft", city != null && city.capital(), city == null ? "no city" : city.name());
				if (city == null) {
					finish();
					return;
				}
				capital = city.key();
				check("the Settler is gone and no unit is selected any more", !state.units().containsKey(settler) && session.selectedUnit(fake) == null, "");
				next();
			}
			case 4 -> {
				if (++waited < 30) {
					return;
				}
				// The hall of the new city, with the ruler's bell and lectern.
				CityState city = state.cities().get(capital);
				int cx = model.centerX(city.at());
				int cz = model.centerZ(city.at());
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						session.painter.paintNow(level, level.getChunk((cx >> 4) + dx, (cz >> 4) + dz));
					}
				}
				int[][] furniture = model.hallFurniture(city.at());
				BlockState bell = level.getBlockState(new BlockPos(furniture[0][0], furniture[0][1], furniture[0][2]));
				BlockState floor = level.getBlockState(new BlockPos(cx, BoardModel.LAND, cz));
				check("a hall stands where the city was founded, with the bell in it", floor.is(Blocks.OAK_PLANKS) && bell.is(Blocks.BELL), floor + " / " + bell);
				check("a governor stands in the hall", session.proxies.city(capital) != null, "");
				check("the game now asks what to research", state.empire().blocking().equals("RESEARCH"), state.empire().blocking());
				next();
			}
			case 5 -> {
				List<Records.Option> techs = choices("research", 0, 0);
				if (techs == null) {
					return;
				}
				Records.Option pottery = techs.stream().filter(o -> o.type().equals("TECH_POTTERY")).findFirst().orElse(null);
				check("the list of technologies arrives", pottery != null, techs.size() + " choices");
				session.gameplay.researchMenu(fake);
				if (pottery != null) {
					session.gameplay.chooseResearch(fake, pottery);
				}
				next();
			}
			case 6 -> {
				if (!state.empire().researchType().equals("TECH_POTTERY") && ++waited < 100) {
					return;
				}
				check("research chosen at the lectern is the game's research", state.empire().researchType().equals("TECH_POTTERY"), state.empire().researchType());
				check("crafting follows the game's technologies", "IRON_WORKING".equals(session.missingTech("minecraft:iron_pickaxe"))
					&& session.missingTech("minecraft:stone_pickaxe") == null, String.valueOf(session.missingTech("minecraft:iron_pickaxe")));
				next();
			}
			case 7 -> {
				List<Records.Option> builds = choices("build", capital.id(), 0);
				if (builds == null) {
					return;
				}
				Records.Option monument = builds.stream().filter(o -> o.type().equals("BUILDING_MONUMENT")).findFirst().orElse(null);
				check("the list of what the capital can build arrives", monument != null && builds.size() >= 3, builds.size() + " choices");
				session.gameplay.cityMenu(fake, capital);
				if (monument != null) {
					session.gameplay.chooseProduction(fake, capital, monument);
				}
				next();
			}
			case 8 -> {
				CityState city = state.cities().get(capital);
				if (!city.productionType().equals("BUILDING_MONUMENT") && ++waited < 100) {
					return;
				}
				check("the governor's choice is what the city builds", city.productionType().equals("BUILDING_MONUMENT"), city.productionType());
				// The Warrior is selected and sent to a neighbouring hex by pointing at it.
				UnitState unit = state.units().get(warrior);
				goal = null;
				for (PlotCoord near : state.session().grid().neighbors(unit.at())) {
					PlotState plot = state.plot(near);
					if (plot.revealed() && !plot.water() && plot.elevation() != PlotState.MOUNTAIN && !plot.city()
						&& state.units().values().stream().noneMatch(u -> u.at().equals(near))) {
						goal = near;
						break;
					}
				}
				check("a free hex next to the Warrior", goal != null, "");
				if (goal == null) {
					finish();
					return;
				}
				session.select(fake, warrior);
				fake.setItemInHand(InteractionHand.MAIN_HAND, Codex.item("Rome"));
				int x = model.centerX(goal);
				int z = model.centerZ(goal);
				boolean answered = session.useBlock(fake, InteractionHand.MAIN_HAND, new BlockPos(x, model.standY(x, z) - 1, z));
				check("using the Codex on a hex with a unit selected is an order", answered, "");
				next();
			}
			case 9 -> {
				UnitState unit = state.units().get(warrior);
				// A few ticks at least: two clicks in the same instant count as one.
				if (++waited < 8 || (unit != null && !unit.at().equals(goal) && waited < 100)) {
					return;
				}
				check("the Warrior walked to the hex that was pointed at", unit != null && unit.at().equals(goal), unit == null ? "gone" : unit.at() + " wanted " + goal);
				turnBefore = state.turn();
				rung = false;
				next();
			}
			case 10 -> {
				// The bell in the hall ends the turn, once whatever the game still asks has been answered
				// (a ruin the Warrior walked into may have brought a second choice of research).
				if (rung && ticks == rungAt + 4) {
					// A button held a moment too long: the game repeats the click four ticks later, by which time the next turn
					// has begun. It must not end that one as well.
					int[] bell = model.hallFurniture(state.cities().get(capital).at())[0];
					session.useBlock(fake, InteractionHand.MAIN_HAND, new BlockPos(bell[0], bell[1], bell[2]));
				}
				if (!session.link.viewerTurn() || session.link.commandsWaiting() > 0) {
					return;
				}
				if (state.turn() > turnBefore) {
					if (ticks < rungAt + 30) {
						return;
					}
					check("ringing the bell ends the turn, and one ring ends one turn however long the button is held", state.turn() == turnBefore + 1,
						turnBefore + " -> " + state.turn());
					lastAsked = state.turn() - 1;
					next();
					return;
				}
				if (++waited > 600) {
					check("ringing the bell ends the turn: the other civilizations move and it is the player's turn again", false, "still turn " + state.turn() + ", the game waits for '"
						+ state.empire().blocking() + "'");
					finish();
					return;
				}
				if (answerWhatIsAsked(state)) {
					return;
				}
				if (!rung && waited > 8) {
					rung = true;
					int[] bell = model.hallFurniture(state.cities().get(capital).at())[0];
					fake.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
					check("the bell answers", session.useBlock(fake, InteractionHand.MAIN_HAND, new BlockPos(bell[0], bell[1], bell[2])), "");
					rungAt = ticks;
				}
			}
			case 11 -> {
				// Ten more turns, answering what the game asks with its first offer.
				if (!session.link.viewerTurn() || session.link.commandsWaiting() > 0) {
					return;
				}
				if (turnsPlayed >= 10 && state.turn() > lastAsked) {
					check("ten more turns played", true, "turn " + state.turn() + ", " + state.techs().size() + " technologies, " + state.cities().size() + " cities in sight");
					next();
					return;
				}
				if (++waited > 1200) {
					check("ten more turns played", false, "stuck at turn " + state.turn() + ", the game waits for '" + state.empire().blocking() + "'");
					finish();
					return;
				}
				if (answerWhatIsAsked(state)) {
					return;
				}
				if (state.turn() > lastAsked) {
					lastAsked = state.turn();
					waited = 0;
					session.endTurn(fake);
					turnsPlayed++;
				}
			}
			case 12 -> {
				if (++waited < 20) {
					return;
				}
				if (!hands(state, model, level, me)) {
					return;
				}
				CityState city = state.cities().get(capital);
				check("the capital grew or built something in that time", city != null && (city.population() > 1 || city.buildings().size() > 1), city == null ? "gone"
					: "population " + city.population() + ", " + city.buildings());
				java.nio.file.Path save = session.server.getWorldPath(LevelResource.ROOT).resolve("livingworld-civ.json");
				check("the game is kept in the world folder", java.nio.file.Files.exists(save), save.getFileName().toString());
				try {
					session.codex.open(fake);
					session.codex.realm(fake);
					session.codex.others(fake);
					session.codex.proposals(fake, true);
					session.codex.newGame(fake);
					for (var relation : state.relations().values()) {
						session.gameplay.civMenu(fake, relation.id());
						if (!relation.minor()) {
							session.gameplay.tradeMenu(fake, relation.id());
						}
					}
					session.gameplay.beliefMenu(fake, true);
					session.gameplay.voteMenu(fake);
					session.gameplay.fateMenu(fake, capital, false);
					check("the Codex pages of the built-in game build and open (cities and units, other civilizations, proposals, a new game)", true,
						state.relations().size() + " civilizations met");
				} catch (RuntimeException e) {
					check("the Codex pages of the built-in game build and open (cities and units, other civilizations, proposals, a new game)", false, e.toString());
				}
				// A purse for the next check; the game thread is idle between requests.
				((nl.jordythomassen.livingworld.unciv.UncivLink) session.link).viewer().addGold(600);
				next();
			}
			case 13 -> {
				List<Records.Option> wares = choices("buy", capital.id(), 0);
				if (wares == null) {
					return;
				}
				Records.Option pick = wares.stream().filter(o -> o.turns() > 0).min(java.util.Comparator.comparingInt(Records.Option::turns)).orElse(null);
				check("the capital can buy things with gold, each with its price", pick != null, wares.size() + " wares");
				if (pick == null) {
					finish();
					return;
				}
				bought = pick;
				goldBefore = -1;
				session.gameplay.buyMenu(fake, capital);
				session.submit(fake, CommandOp.CITY_BUY, capital, null, 0, null, "Buying " + pick.name(), null, pick.type(), false);
				next();
			}
			case 14 -> {
				// The first picture after the purchase shows the treasury without the price.
				if (++waited < 20) {
					return;
				}
				boolean have = bought.group() == 0 ? state.units().values().stream().anyMatch(u -> u.key().owner() == me && u.type().equals(bought.type()))
					: state.cities().get(capital).buildings().stream().anyMatch(b -> bought.type().endsWith(b));
				check("what was bought is there at once: " + bought.name() + " for " + bought.turns() + " gold", have, state.cities().get(capital).buildings() + " / "
					+ state.units().values().stream().filter(u -> u.key().owner() == me).map(UnitState::type).toList());
				// From above: the player is lifted over the land and can fly.
				double[] home = session.home();
				fake.setPos(home[0], home[1], home[2]);
				session.lookFromAbove(fake);
				check("looking from above lifts the player over the land", session.aloft(fake) && fake.getY() > home[1] + 40, "y " + fake.getY() + " from " + home[1]);
				// Pointing straight down at a unit of one's own, with the Codex in hand, selects it.
				UnitState unit = state.units().get(warrior);
				session.deselect(fake);
				fake.setItemInHand(InteractionHand.MAIN_HAND, Codex.item("Rome"));
				fake.snapTo(model.centerX(unit.at()) + 0.5, model.standY(model.centerX(unit.at()), model.centerZ(unit.at())) + 40, model.centerZ(unit.at()) + 0.5, 0f, 90f);
				boolean answered = session.useItem(fake, InteractionHand.MAIN_HAND);
				EntityKey chosen = session.selectedUnit(fake);
				check("pointing at a unit from afar with the Codex selects it", answered && chosen != null && state.units().get(chosen).at().equals(unit.at()),
					chosen == null ? "nothing selected" : state.units().get(chosen).name());
				next();
			}
			case 15 -> {
				// Faith enough for a pantheon: the turn waits for the player, who chooses from the game's list.
				if (waited == 0) {
					var engine = (nl.jordythomassen.livingworld.unciv.UncivLink) session.link;
					engine.viewer().getReligionManager().setStoredFaith(engine.viewer().getReligionManager().faithForPantheon(0) + 5);
					engine.viewer().addGold(200);
					// And an introduction, so that there is somebody to make a deal with: a scout would get there in time.
					for (var civ : engine.game().getCivilizations()) {
						if (civ.isMajorCiv() && civ != engine.viewer() && !engine.viewer().knows(civ)) {
							engine.viewer().getDiplomacyFunctions().makeCivilizationsMeet(civ, false);
							break;
						}
					}
					// A job on the game's own thread, even an empty one, is followed by a fresh picture of the game (a mere question is not).
					engine.onGameThread(() -> { });
				}
				if (++waited < 10) {
					return;
				}
				// Anything else the game asks first is answered; the choice of beliefs is made here, by hand.
				if (!state.empire().blocking().equals("RELIGION") && answerWhatIsAsked(state)) {
					return;
				}
				if (!state.empire().blocking().equals("RELIGION")) {
					var faith = ((nl.jordythomassen.livingworld.unciv.UncivLink) session.link).viewer().getReligionManager();
					// A ruin can give faith early: then the question came up during the turns played above, and was answered there by the same means.
					boolean earlier = faith.getReligionState() != com.unciv.logic.civilization.managers.ReligionState.None;
					check("with faith enough, the game asks what the player's people believe", earlier, earlier ? "asked and answered earlier in this game"
						: "the game waits for '" + state.empire().blocking() + "'; faith " + faith.getStoredFaith() + " of " + faith.faithForPantheon(0) + ", state "
							+ faith.getReligionState() + ", turn " + state.turn());
					next();
					return;
				}
				List<Records.Option> beliefs = choices("beliefs", 0, 0);
				if (beliefs == null) {
					return;
				}
				check("with faith enough, the game asks what the player's people believe, and offers its pantheons", beliefs.size() > 5, beliefs.size() - 1 + " to choose from");
				session.gameplay.beliefMenu(fake, true);
				session.submit(fake, CommandOp.BELIEF, new EntityKey(me, -1), null, 0, null, "Belief", null, beliefs.get(1).type(), false);
				chosenBelief = beliefs.get(1).name();
				next();
			}
			case 16 -> {
				if (++waited < 20) {
					return;
				}
				check("the chosen pantheon is adopted and the turn no longer waits for it", !state.empire().blocking().equals("RELIGION"), chosenBelief);
				// A deal: a gift of gold to a civilization the player has met, put on the table and proposed.
				var met = state.relations().values().stream().filter(r -> !r.minor() && !state.civ(r.id()).atWarWithViewer()).findFirst().orElse(null);
				if (met == null) {
					LivingWorldMod.LOG.info("SELFTEST note no other civilization met yet: no deal tried");
					next();
					return;
				}
				if (dealStep == 0) {
					goldBefore = state.empire().gold();
					session.submit(fake, CommandOp.TRADE, new EntityKey(me, met.id()), null, 0, null, "Deal", null, "give|Gold|Gold|25", false);
					session.submit(fake, CommandOp.TRADE, new EntityKey(me, met.id()), null, 0, null, "Deal", null, "propose", false);
					session.gameplay.tradeMenu(fake, met.id());
					dealStep = 1;
					waited = 0;
					return;
				}
				check("a deal put to another civilization is made when it agrees: 25 gold given to " + state.civ(met.id()).name(), state.empire().gold() == goldBefore - 25,
					goldBefore + " -> " + state.empire().gold());
				next();
			}
			case 17 -> {
				// Riders ride: a Horseman is put next to the capital, and its character must sit on a horse.
				if (waited == 0) {
					var engine = (nl.jordythomassen.livingworld.unciv.UncivLink) session.link;
					engine.onGameThread(() -> {
						var home = engine.viewer().getCapital(false).getCenterTile();
						engine.game().getTileMap().placeUnitNearTile(home.getPosition(), "Horseman", engine.viewer(), null);
						// And a ship on the nearest water, when there is any within reach.
						com.unciv.logic.map.tile.Tile water = null;
						for (var tile : nl.jordythomassen.livingworld.unciv.Kt.list(home.getTilesInDistance(12))) {
							if (tile.isWater() && tile.getMilitaryUnit() == null && (water == null || tile.aerialDistanceTo(home) < water.aerialDistanceTo(home))) {
								water = tile;
							}
						}
						if (water != null) {
							for (var tile : nl.jordythomassen.livingworld.unciv.Kt.list(water.getTilesInDistance(1))) {
								tile.setExplored(engine.viewer(), true, null);
							}
							engine.game().getTileMap().placeUnitNearTile(water.getPosition(), "Trireme", engine.viewer(), null);
						}
					});
				}
				if (++waited < 30) {
					return;
				}
				UnitState rider = state.units().values().stream().filter(u -> u.key().owner() == me && u.type().equals("UNIT_HORSEMAN")).findFirst().orElse(null);
				UnitState ship = state.units().values().stream().filter(u -> u.key().owner() == me && u.type().equals("UNIT_TRIREME")).findFirst().orElse(null);
				for (UnitState unit : new UnitState[] { rider, ship }) {
					Proxies.Proxy shown = unit == null ? null : session.proxies.unit(unit.key());
					if (shown != null) {
						int cx = (int) Math.floor(shown.x) >> 4;
						int cz = (int) Math.floor(shown.z) >> 4;
						session.painter.paintNow(level, level.getChunk(cx, cz));
					}
				}
				session.proxies.showAll(level);
				if (waited < 45) {
					return;
				}
				Proxies.Proxy shown = rider == null ? null : session.proxies.unit(rider.key());
				var horse = shown == null || shown.mount == null ? null : level.getEntity(shown.mount);
				var figure = shown == null || shown.entity == null ? null : level.getEntity(shown.entity);
				check("a Horseman sits on a horse, and a click on the horse is a click on the rider", horse != null && figure != null && figure.getVehicle() == horse
					&& net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(horse.getType()).getPath().contains("horse") && session.proxies.byEntity(horse.getUUID()) == shown,
					rider == null ? "no Horseman in the picture" : horse == null ? "no horse" : horse.getType() + " carrying " + horse.getPassengers().size());
				if (ship == null) {
					LivingWorldMod.LOG.info("SELFTEST note no water within twelve hexes of the capital: no ship tried");
				} else {
					Proxies.Proxy afloat = session.proxies.unit(ship.key());
					var hull = afloat == null || afloat.mount == null ? null : level.getEntity(afloat.mount);
					var sailor = afloat == null || afloat.entity == null ? null : level.getEntity(afloat.entity);
					check("a Trireme is a boat on the water with its crew in it", hull != null && sailor != null && sailor.getVehicle() == hull
						&& net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(hull.getType()).getPath().endsWith("boat"),
						hull == null ? "no boat" : hull.getType() + " at y " + hull.getY() + " carrying " + hull.getPassengers().size());
				}
				next();
			}
			case 18 -> {
				// With a unit selected and the Codex in hand, the hexes it can reach are marked for the player, and the one pointed at outlined.
				if (++waited < 30) {
					session.debugPointer(fake);
					return;
				}
				int marks = session.marksShown(fake);
				check("the hexes the selected unit can reach are marked, and the hex pointed at outlined", marks > 40, marks + " marks");
				session.deselect(fake);
				session.debugPointer(fake);
				check("the marks go when the unit is let go", session.marksShown(fake) == 0, session.marksShown(fake) + " marks left");
				oldGame = state.session().gameId();
				session.startNewGame(new nl.jordythomassen.livingworld.unciv.UncivLink.Setup("Tiny", "Inner Sea", 2, 2, "Prince", "Egypt", 77L, "Quick"));
				next();
			}
			case 19 -> {
				if (state.session().gameId().equals(oldGame)) {
					if (++waited > 600) {
						check("a new game starts in the same world", false, "still the old game after half a minute");
						finish();
					}
					return;
				}
				var civ = state.civ(me);
				check("a new game starts in the same world, as another people", civ != null && civ.name().equals("Egypt") && state.cities().isEmpty() && state.turn() == 0,
					(civ == null ? "?" : civ.name()) + ", turn " + state.turn());
				check("the game that was being played is set aside, not lost",
					java.nio.file.Files.exists(session.server.getWorldPath(LevelResource.ROOT).resolve("livingworld-civ.previous.json")), "");
				check("no unit of the old game is still selected", session.selectedUnit(fake) == null, "");
				session.codex.give(fake);
				String title = "";
				for (int i = 0; i < fake.getInventory().getContainerSize(); i++) {
					if (Codex.is(fake.getInventory().getItem(i))) {
						title = fake.getInventory().getItem(i).getHoverName().getString();
					}
				}
				check("the Codex carries the new people's name", title.equals("Codex of Egypt"), title);
				UnitState first = state.units().values().stream().filter(u -> u.key().owner() == me).findFirst().orElse(null);
				long before = session.painter.changedBlocks();
				if (first != null) {
					int cx = model.centerX(first.at());
					int cz = model.centerZ(first.at());
					for (int dx = -2; dx <= 2; dx++) {
						for (int dz = -2; dz <= 2; dz++) {
							session.painter.paintNow(level, level.getChunk((cx >> 4) + dx, (cz >> 4) + dz));
						}
					}
				}
				check("the land is painted anew for the new map", first != null && session.painter.changedBlocks() > before + 2_000,
					(session.painter.changedBlocks() - before) + " blocks changed");
				// And a game for two: each of them a civilization of their own.
				other = net.fabricmc.fabric.api.entity.FakePlayer.get(level, new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("second".getBytes()), "Second"));
				oldGame = state.session().gameId();
				session.startNewGame(new nl.jordythomassen.livingworld.unciv.UncivLink.Setup("Tiny", "pangaea", 1, 0, "Prince", "Rome", 5L, "Quick", List.of("Greece"),
					java.util.Set.of(), List.of()));
				session.seats.promise(List.of(fake.getUUID(), other.getUUID()));
				// Neither is on the server's list of players; both count as being there.
				session.seats.standIn(fake.getUUID());
				session.seats.standIn(other.getUUID());
				next();
			}
			case 20 -> {
				if (state.session().gameId().equals(oldGame) || session.link.seats().size() < 2) {
					if (++waited > 800) {
						check("a game for two starts in the same world", false, "seats: " + session.link.seats());
						finish();
					}
					return;
				}
				int mine = session.seats.of(fake);
				int theirs = session.seats.of(other);
				WorldState a = session.stateOf(fake);
				WorldState b = session.stateOf(other);
				check("two people in one game each rule a civilization of their own", mine >= 0 && theirs >= 0 && mine != theirs && a.session().viewer() == mine
					&& b.session().viewer() == theirs && a.civ(mine).name().equals("Rome") && b.civ(theirs).name().equals("Greece"),
					"seats " + session.link.seats() + ", theirs " + mine + " and " + theirs + ", pictures for " + a.session().viewer() + " and " + b.session().viewer());
				check("each sees their own people and not the other's", a.units().keySet().stream().anyMatch(k -> k.owner() == mine) && a.units().keySet().stream().noneMatch(k -> k.owner() == theirs)
					&& b.units().keySet().stream().anyMatch(k -> k.owner() == theirs) && b.units().keySet().stream().noneMatch(k -> k.owner() == mine)
					&& session.board().units().keySet().stream().anyMatch(k -> k.owner() == mine) && session.board().units().keySet().stream().anyMatch(k -> k.owner() == theirs),
					a.units().size() + " and " + b.units().size() + " units, " + session.board().units().size() + " in the world as it is built");
				for (ServerPlayer who : List.of(fake, other)) {
					int civ = session.seats.of(who);
					UnitState settler = session.stateOf(who).units().values().stream().filter(u -> u.key().owner() == civ && u.type().equals("UNIT_SETTLER")).findFirst().orElse(null);
					if (settler != null) {
						session.as(who, () -> session.submit(who, CommandOp.UNIT_ACTION, settler.key(), null, 0, null, "Settler", null, "FoundCity", false));
					}
				}
				next();
			}
			case 21 -> {
				if (++waited < 30) {
					return;
				}
				int mine = session.seats.of(fake);
				int theirs = session.seats.of(other);
				boolean both = session.stateOf(fake).cities().values().stream().anyMatch(c -> c.key().owner() == mine)
					&& session.stateOf(other).cities().values().stream().anyMatch(c -> c.key().owner() == theirs);
				check("both found their capital in the same turn, each by their own order", both, session.stateOf(fake).cities().size() + " and " + session.stateOf(other).cities().size());
				// Two rulers in one world: each digs their own land, and neither the other's.
				if (both) {
					CityState home = session.stateOf(fake).cities().values().stream().filter(c -> c.key().owner() == mine).findFirst().orElseThrow();
					CityState abroad = session.stateOf(other).cities().values().stream().filter(c -> c.key().owner() == theirs).findFirst().orElseThrow();
					PlotCoord here = null;
					PlotCoord there = null;
					for (PlotCoord near : state.session().grid().neighbors(home.at())) {
						PlotState plot = session.stateOf(fake).plot(near);
						if (here == null && plot.owner() == mine && !plot.water() && plot.elevation() != PlotState.MOUNTAIN) {
							here = near;
						}
					}
					for (PlotCoord near : state.session().grid().neighbors(abroad.at())) {
						PlotState plot = session.stateOf(other).plot(near);
						if (there == null && plot.owner() == theirs && !plot.water() && plot.elevation() != PlotState.MOUNTAIN) {
							there = near;
						}
					}
					if (here == null || there == null) {
						check("each of two rulers digs their own land and not the other's", false, "no land to try it on: " + here + " / " + there);
					} else {
						BlockPos mineHere = ground(model, here);
						BlockPos theirsThere = ground(model, there);
						session.painter.paintNow(level, level.getChunk(mineHere.getX() >> 4, mineHere.getZ() >> 4));
						session.painter.paintNow(level, level.getChunk(theirsThere.getX() >> 4, theirsThere.getZ() >> 4));
						BlockState before = level.getBlockState(theirsThere);
						boolean kept = !fake.gameMode.destroyBlock(theirsThere) && level.getBlockState(theirsThere) == before;
						BlockState mineBefore = level.getBlockState(mineHere);
						boolean keptToo = !other.gameMode.destroyBlock(mineHere) && level.getBlockState(mineHere) == mineBefore;
						boolean own = session.handsTied(fake, mineHere) == null && session.handsTied(other, theirsThere) == null;
						boolean dug = other.gameMode.destroyBlock(theirsThere) && level.getBlockState(theirsThere).isAir();
						check("each of two rulers digs their own land and not the other's", kept && keptToo && own && dug,
							"the first on the second's land: " + session.handsTied(fake, theirsThere) + " / the second on the first's: " + session.handsTied(other, mineHere)
								+ " / each on their own: " + (own && dug ? "free" : session.handsTied(fake, mineHere) + ", " + session.handsTied(other, theirsThere)));
					}
				}
				// What each must decide before a turn can end is settled here, the way the two would on their own screens.
				var engine = (nl.jordythomassen.livingworld.unciv.UncivLink) session.link;
				engine.onGameThread(() -> {
					for (var civ : engine.game().getCivilizations()) {
						if (!civ.isHuman()) {
							continue;
						}
						for (var tech : engine.game().getRuleset().getTechnologies().values()) {
							if (civ.getTech().canBeResearched(tech.getName())) {
								civ.getTech().getTechsToResearch().add(tech.getName());
								break;
							}
						}
						for (var city : civ.getCities()) {
							city.getCityConstructions().setCurrentConstruction("Warrior");
						}
					}
				});
				turnBefore = state.turn();
				next();
			}
			case 22 -> {
				if (++waited < 15) {
					return;
				}
				session.as(fake, () -> session.endTurn(fake));
				next();
			}
			case 23 -> {
				if (++waited < 30) {
					return;
				}
				int mine = session.seats.of(fake);
				int theirs = session.seats.of(other);
				check("one of the two ends their turn: it waits for the other, who can still move", state.turn() == turnBefore && !session.link.mayAct(mine)
					&& session.link.waitsFor(mine).equals("ended") && session.link.mayAct(theirs),
					"turn " + state.turn() + ", first may act " + session.link.mayAct(mine) + " (" + session.link.waitsFor(mine) + "), second may act " + session.link.mayAct(theirs));
				session.as(other, () -> session.endTurn(other));
				next();
			}
			case 24 -> {
				if (state.turn() == turnBefore && ++waited < 400) {
					return;
				}
				int mine = session.seats.of(fake);
				int theirs = session.seats.of(other);
				check("the other ends theirs: the turn passes, and both may move again", state.turn() == turnBefore + 1 && session.link.mayAct(mine) && session.link.mayAct(theirs)
					&& session.stateOf(other).turn() == turnBefore + 1, "turn " + state.turn() + " after " + turnBefore);
				finish();
			}
			default -> {
			}
		}
	}

	/** Answers one thing the game wants decided before the turn can end, with its first offer. True while there was something. */
	private boolean answerWhatIsAsked(WorldState state) {
		String waiting = state.empire().blocking();
		if (waiting.equals("VOTE")) {
			session.submit(fake, CommandOp.DIPLOMACY, new EntityKey(state.session().viewer(), -1), null, 0, null, "Vote", null, "abstain", false);
			return true;
		}
		if (waiting.equals("RELIGION")) {
			List<Records.Option> beliefs = choices("beliefs", 0, 0);
			if (beliefs != null && beliefs.size() > 1) {
				session.submit(fake, CommandOp.BELIEF, new EntityKey(state.session().viewer(), -1), null, 0, null, "Belief", null, beliefs.get(1).type(), false);
			}
			return true;
		}
		if (!waiting.equals("RESEARCH") && !waiting.equals("POLICY") && !waiting.equals("PRODUCTION")) {
			return false;
		}
		String kind = waiting.equals("RESEARCH") ? "research" : waiting.equals("POLICY") ? "policy" : "build";
		List<Records.Option> offered = choices(kind, kind.equals("build") ? capital.id() : 0, 0);
		if (offered == null) {
			return true;
		}
		if (offered.isEmpty()) {
			check("the game offers a choice when it asks for one (" + waiting + ")", false, "");
			finish();
			return true;
		}
		Records.Option pick = offered.get(0);
		if (waiting.equals("RESEARCH")) {
			session.gameplay.chooseResearch(fake, pick);
		} else if (waiting.equals("POLICY")) {
			session.gameplay.choosePolicy(fake, pick);
		} else {
			session.gameplay.chooseProduction(fake, capital, pick);
		}
		return true;
	}

	private void finish() {
		LivingWorldMod.LOG.info("SELFTEST finished: {}", failures == 0 ? "all checks passed" : failures + " checks FAILED");
		stage = 99;
		session.server.halt(false);
	}
}
