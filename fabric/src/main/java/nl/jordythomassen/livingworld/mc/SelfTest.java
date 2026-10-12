package nl.jordythomassen.livingworld.mc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import nl.jordythomassen.livingworld.board.BoardModel;
import nl.jordythomassen.livingworld.commands.CommandOp;
import nl.jordythomassen.livingworld.commands.CommandStatus;
import nl.jordythomassen.livingworld.commands.CommandTicket;
import nl.jordythomassen.livingworld.commands.GameCommand;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * A check of the parts that depend on the exact Minecraft version, run on a server without
 * players (start it with -Dlivingworld.selftest=true): are the blocks real, do the characters
 * come into being from their data, does a command make the round trip. It logs one line per
 * check and then stops the server.
 */
final class SelfTest {
	private final LivingWorldServer session;
	private int stage;
	private int waited;
	private int failures;
	private CommandTicket ticket;

	SelfTest(LivingWorldServer session) {
		this.session = session;
	}

	private void check(String what, boolean ok, String detail) {
		if (!ok) {
			failures++;
		}
		LivingWorldMod.LOG.info("SELFTEST {} {}{}", ok ? "ok  " : "FAIL", what, detail.isEmpty() ? "" : " (" + detail + ")");
	}

	void tick() {
		WorldState state = session.state();
		BoardModel model = session.model();
		if (state == null || model == null) {
			if (++waited > 600) {
				check("a Civ game is shown within 30 seconds", false, session.link.status().text);
				finish();
			}
			return;
		}
		ServerLevel level = session.server.overworld();
		switch (stage) {
			case 0 -> {
				CityState rome = state.cities().values().stream().filter(c -> c.key().owner() == state.session().viewer()).findFirst().orElse(null);
				check("the viewer has a city", rome != null, "");
				if (rome == null) {
					finish();
					return;
				}
				int cx = model.centerX(rome.at());
				int cz = model.centerZ(rome.at());
				int painted = 0;
				for (int dx = -3; dx <= 3; dx++) {
					for (int dz = -3; dz <= 3; dz++) {
						LevelChunk chunk = level.getChunk((cx >> 4) + dx, (cz >> 4) + dz);
						session.painter.paintNow(level, chunk);
						painted++;
					}
				}
				check("painted " + painted + " chunks around the capital", session.painter.changedBlocks() > 10_000, session.painter.changedBlocks() + " blocks changed");
				BlockState floor = level.getBlockState(new BlockPos(cx, BoardModel.LAND, cz));
				check("the keep has a plank floor", floor.is(Blocks.OAK_PLANKS), floor.toString());
				int[][] furniture = model.hallFurniture(rome.at());
				BlockState bell = level.getBlockState(new BlockPos(furniture[0][0], furniture[0][1], furniture[0][2]));
				BlockState lectern = level.getBlockState(new BlockPos(furniture[1][0], furniture[1][1], furniture[1][2]));
				check("the ruler's bell stands in the hall of the capital", bell.is(Blocks.BELL), bell.toString());
				check("and the lectern beside it", lectern.is(Blocks.LECTERN), lectern.toString());
				boolean roof = false;
				boolean flag = false;
				for (int y = BoardModel.LAND + 2; y < BoardModel.LAND + 20; y++) {
					roof |= level.getBlockState(new BlockPos(cx + 2, y, cz + 1)).is(Blocks.CONCRETE.purple());
					flag |= level.getBlockState(new BlockPos(cx + 5, y, cz - 3)).is(Blocks.WOOL.purple());
				}
				check("the roof is concrete in the owner's colour", roof, "");
				check("the owner's flag flies from the tower", flag, "");
				BlockState wall = level.getBlockState(new BlockPos(cx + 4, BoardModel.LAND + 2, cz + 3));
				check("an ancient Roman hall has sandstone walls", wall.is(Blocks.SMOOTH_SANDSTONE), wall.toString());
				PlotCoord ironPlot = new PlotCoord(rome.at().x() + 2, rome.at().y());
				boolean ore = false;
				for (int dx = -12; dx <= 0 && !ore; dx++) {
					for (int dz = -12; dz <= 0 && !ore; dz++) {
						for (int y = BoardModel.LAND; y <= BoardModel.LAND + 3 && !ore; y++) {
							ore = level.getBlockState(new BlockPos(model.centerX(ironPlot) + dx, y, model.centerZ(ironPlot) + dz)).is(Blocks.IRON_ORE);
						}
					}
				}
				check("iron ore crops out on the iron plot", ore, "");
				long before = session.painter.changedBlocks();
				session.painter.paintNow(level, level.getChunk(cx >> 4, cz >> 4));
				check("painting again changes nothing", session.painter.changedBlocks() == before, (session.painter.changedBlocks() - before) + " blocks");
				// Every material must be a real block of this game version.
				int kinds = 0;
				for (nl.jordythomassen.livingworld.board.Material material : nl.jordythomassen.livingworld.board.Material.values()) {
					if (material != nl.jordythomassen.livingworld.board.Material.AIR && !BlockPalette.of(nl.jordythomassen.livingworld.board.Material.cell(material, 10)).isAir()) {
						kinds++;
					}
				}
				check("every board material maps to a block", kinds == nl.jordythomassen.livingworld.board.Material.values().length - 1, kinds + " kinds");
				// Farm, road and forest near the capital in the mock game.
				PlotCoord farm = new PlotCoord(rome.at().x() + 1, rome.at().y() - 1);
				boolean wheat = false;
				for (int dx = -6; dx <= 6 && !wheat; dx++) {
					for (int dz = -6; dz <= 6 && !wheat; dz++) {
						wheat = level.getBlockState(new BlockPos(model.centerX(farm) + dx, BoardModel.LAND + 1, model.centerZ(farm) + dz)).is(Blocks.WHEAT);
					}
				}
				check("wheat grows on the farm next to the capital", wheat || state.plot(farm).improvement() < 0, "");
				stage = 1;
			}
			case 1 -> {
				session.proxies.showAll(level);
				int mannequins = 0;
				int named = 0;
				int titles = 0;
				for (Proxies.Proxy proxy : session.proxies.all()) {
					Entity entity = proxy.entity == null ? null : level.getEntity(proxy.entity);
					if (entity instanceof Mannequin) {
						mannequins++;
						if (entity.getCustomName() != null && !entity.getCustomName().getString().isEmpty()) {
							named++;
						}
					}
					if (proxy.title != null && level.getEntity(proxy.title) != null) {
						titles++;
					}
				}
				int nearby = 0;
				for (Proxies.Proxy proxy : session.proxies.all()) {
					if (level.getChunkSource().hasChunk((int) Math.floor(proxy.x) >> 4, (int) Math.floor(proxy.z) >> 4)) {
						nearby++;
					}
				}
				check("characters exist for the units and cities in loaded chunks", mannequins > 0 && mannequins == nearby, mannequins + " of " + nearby);
				check("every character has a name plate", named == mannequins, named + " named");
				check("cities have a floating title", titles > 0, titles + " titles");
				Proxies.Proxy governor = session.proxies.all().stream().filter(p -> p.city && p.entity != null).findFirst().orElse(null);
				check("a governor stands in the keep", governor != null && level.getEntity(governor.entity) != null
					&& Math.abs(level.getEntity(governor.entity).getY() - (BoardModel.LAND + 1)) < 0.01, "");
				stage = 2;
			}
			case 2 -> {
				CityState rome = state.cities().values().stream().filter(c -> c.key().owner() == state.session().viewer()).findFirst().orElseThrow();
				ticket = session.link.submit(new GameCommand("selftest", "selftest", state.session().bootId(), state.session().gameId(), "selftest",
					state.turn(), CommandOp.CITY_ADD_PRODUCTION, rome.key(), null, 6));
				waited = 0;
				stage = 3;
			}
			case 3 -> {
				if (ticket.status().finished() || ++waited > 200) {
					check("a delivery makes the round trip and is confirmed", ticket.status() == CommandStatus.CONFIRMED,
						ticket.status() + " " + ticket.detail() + " before " + ticket.before() + " after " + ticket.after());
					check("the link is live", session.link.status() == nl.jordythomassen.livingworld.link.LinkStatus.LIVE, session.link.status().text);
					stage = 4;
					waited = 0;
				}
			}
			default -> player(level, state, model);
		}
	}

	// ---- the same checks a person would do by hand, with a stand-in player ----

	private net.minecraft.server.level.ServerPlayer fake;
	private nl.jordythomassen.livingworld.state.EntityKey romeKey;
	private nl.jordythomassen.livingworld.state.EntityKey warriorKey;
	private int productionBefore;
	private PlotCoord escortTo;
	private int turnBefore;

	private static int count(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.item.Item item) {
		int n = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			if (player.getInventory().getItem(i).is(item)) {
				n += player.getInventory().getItem(i).getCount();
			}
		}
		return n;
	}

	private void player(ServerLevel level, WorldState state, BoardModel model) {
		nl.jordythomassen.livingworld.mock.MockCiv mock = session.mock();
		if (mock == null) {
			finish();
			return;
		}
		int viewer = state.session().viewer();
		switch (stage) {
			case 4 -> {
				fake = net.fabricmc.fabric.api.entity.FakePlayer.get(level);
				double[] home = session.home();
				fake.setPos(home[0], home[1], home[2]);
				romeKey = state.cities().values().stream().filter(c -> c.key().owner() == viewer).findFirst().orElseThrow().key();
				warriorKey = state.units().values().stream().filter(u -> u.key().owner() == viewer && u.combat()).findFirst().orElseThrow().key();
				check("a player is handed the Codex", session.codex.give(fake) && Codex.carries(fake), "");
				check("and never a second one", !session.codex.give(fake), "");
				try {
					session.codex.open(fake);
					session.codex.travel(fake);
					session.codex.settings(fake);
					check("the pages of the Codex build and open", true, "");
				} catch (RuntimeException e) {
					check("the pages of the Codex build and open", false, e.toString());
				}
				try {
					session.debugCity(fake, romeKey, "menu");
					session.debugUnit(fake, warriorKey, "menu");
					state.cities().values().stream().filter(c -> c.key().owner() != viewer).findFirst().ifPresent(c -> session.debugCity(fake, c.key(), "menu"));
					state.units().values().stream().filter(u -> u.key().owner() != viewer).findFirst().ifPresent(u -> session.debugUnit(fake, u.key(), "menu"));
					check("the unit and governor menus build and open", true, "");
				} catch (RuntimeException e) {
					check("the unit and governor menus build and open", false, e.toString());
				}
				session.debugUnit(fake, warriorKey, "orders");
				fake.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 10));
				synchronized (mock) {
					productionBefore = mock.cityNamed("Rome").production;
				}
				session.debugCity(fake, romeKey, "deliver");
				check("logs leave the inventory when handed over", count(fake, net.minecraft.world.item.Items.OAK_LOG) == 0, "");
				stage = 5;
				waited = 0;
			}
			case 5 -> {
				int now;
				synchronized (mock) {
					now = mock.cityNamed("Rome").production;
				}
				if (now == productionBefore + 20 || ++waited > 100) {
					check("ten logs through the governor add twenty hammers in the Civ game", now == productionBefore + 20, productionBefore + " -> " + now);
					var foreign = state.cities().values().stream().filter(c -> c.key().owner() != viewer).findFirst().orElse(null);
					if (foreign != null) {
						fake.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 4));
						session.debugCity(fake, foreign.key(), "deliver");
						check("a delivery to someone else's city is refused and the logs come back", count(fake, net.minecraft.world.item.Items.OAK_LOG) == 4,
							count(fake, net.minecraft.world.item.Items.OAK_LOG) + " logs");
					}
					synchronized (mock) {
						mock.unit(warriorKey.owner(), warriorKey.id()).hp = 40;
						mock.unitsRev++;
					}
					stage = 6;
					waited = 0;
				}
			}
			case 6 -> {
				var warrior = state.units().get(warriorKey);
				if ((warrior != null && warrior.hp() == 40) || ++waited > 100) {
					check("a wound dealt in Civ shows on this side", warrior != null && warrior.hp() == 40, warrior == null ? "gone" : warrior.hp() + " hp");
					fake.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 8));
					session.debugUnit(fake, warriorKey, "heal");
					stage = 7;
					waited = 0;
				}
			}
			case 7 -> {
				int hp;
				synchronized (mock) {
					hp = mock.unit(warriorKey.owner(), warriorKey.id()).hp;
				}
				if (hp == 90 || ++waited > 100) {
					check("five bread heal the real unit by fifty", hp == 90, hp + " hp");
					check("only the bread that was needed is used", count(fake, net.minecraft.world.item.Items.BREAD) == 3, count(fake, net.minecraft.world.item.Items.BREAD) + " left");
					var warrior = state.units().get(warriorKey);
					escortTo = null;
					for (PlotCoord n : state.session().grid().neighbors(warrior.at())) {
						var plot = state.plot(n);
						boolean taken = state.units().values().stream().anyMatch(u -> u.at().equals(n));
						if (plot.revealed() && !plot.water() && !plot.city() && plot.elevation() != nl.jordythomassen.livingworld.state.PlotState.MOUNTAIN && !taken) {
							escortTo = n;
							break;
						}
					}
					if (escortTo == null) {
						check("a free plot next to the unit for the escort check", false, "");
						finish();
						return;
					}
					int x = model.centerX(escortTo);
					int z = model.centerZ(escortTo);
					fake.setPos(x + 0.5, model.standY(x, z), z + 0.5);
					session.debugUnit(fake, warriorKey, "escort");
					stage = 8;
					waited = 0;
				}
			}
			case 8 -> {
				PlotCoord at;
				synchronized (mock) {
					var unit = mock.unit(warriorKey.owner(), warriorKey.id());
					at = new PlotCoord(unit.x, unit.y);
				}
				var shown = state.units().get(warriorKey);
				if ((at.equals(escortTo) && shown != null && shown.at().equals(escortTo)) || ++waited > 100) {
					check("'escort me' moves the real unit to the player's hex", at.equals(escortTo), at + " wanted " + escortTo);
					Proxies.Proxy proxy = session.proxies.unit(warriorKey);
					check("its character is headed for the new plot", proxy != null && Math.abs(proxy.x - (model.centerX(escortTo) + 0.5)) < 4.0, "");
					turnBefore = state.turn();
					// The bell in the hall, and then the Codex's button as well: still one turn.
					int[] bell = model.hallFurniture(state.cities().get(romeKey).at())[0];
					rung = session.useBlock(fake, net.minecraft.world.InteractionHand.MAIN_HAND, new BlockPos(bell[0], bell[1], bell[2]));
					session.endTurn(fake);
					session.gameplay.declareWar(fake, nl.jordythomassen.livingworld.mock.MockCiv.EGYPT);
					stage = 9;
					waited = 0;
				}
			}
			case 9 -> {
				var egypt = state.civ(nl.jordythomassen.livingworld.mock.MockCiv.EGYPT);
				if ((state.turn() == turnBefore + 1 && egypt != null && egypt.atWarWithViewer()) || ++waited > 100) {
					check("ringing the bell in the hall ends exactly one turn in Civ, however often it is asked", rung && state.turn() == turnBefore + 1,
						turnBefore + " -> " + state.turn());
					int[] lectern = model.hallFurniture(state.cities().get(romeKey).at())[1];
					check("the lectern in the hall opens the choice of research",
						session.useBlock(fake, net.minecraft.world.InteractionHand.MAIN_HAND, new BlockPos(lectern[0], lectern[1], lectern[2])), "");
					// A bell hung somewhere else in the same hall, rung with an empty hand: none of our business.
					fake.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
					BlockPos stray = new BlockPos(lectern[0], lectern[1] + 2, lectern[2]);
					level.setBlock(stray, Blocks.BELL.defaultBlockState(), 3);
					check("a bell someone hung elsewhere is just a bell", !session.useBlock(fake, net.minecraft.world.InteractionHand.MAIN_HAND, stray), "");
					level.setBlock(stray, Blocks.AIR.defaultBlockState(), 3);
					check("a Codex that was lost comes back", session.codex.give(fake) && Codex.carries(fake), "");
					check("a declaration of war made in Minecraft is real in Civ", egypt != null && egypt.atWarWithViewer(), "");
					check("characters still match the units in sight", session.proxies.unitCount() == state.units().size(),
						session.proxies.unitCount() + " characters, " + state.units().size() + " units");
					stage = 10;
					waited = 0;
				}
			}
			default -> ruling(level, state, model, mock);
		}
	}

	// ---- ruling and working the land ----

	private final java.util.concurrent.atomic.AtomicReference<java.util.List<nl.jordythomassen.livingworld.protocol.Records.Option>> answer =
		new java.util.concurrent.atomic.AtomicReference<>();
	private boolean asked;
	private boolean rung;
	private PlotCoord workPlot;
	private PlotCoord frontier;
	private int goldBefore;
	private BlockPos keepsake;
	private BlockPos crop;

	/** Asks Civ for a list once and returns it when it has arrived. */
	private java.util.List<nl.jordythomassen.livingworld.protocol.Records.Option> choices(String kind, int a, int c) {
		if (!asked) {
			asked = true;
			answer.set(null);
			session.link.ask(kind, a, c, options -> answer.set(options == null ? java.util.List.of() : options));
		}
		java.util.List<nl.jordythomassen.livingworld.protocol.Records.Option> got = answer.get();
		if (got != null) {
			asked = false;
		}
		return got;
	}

	private void next() {
		stage++;
		waited = 0;
	}

	private void ruling(ServerLevel level, WorldState state, BoardModel model, nl.jordythomassen.livingworld.mock.MockCiv mock) {
		int viewer = state.session().viewer();
		switch (stage) {
			case 10 -> {
				var options = choices("build", romeKey.id(), 0);
				if (options != null) {
					var granary = options.stream().filter(o -> o.type().equals("BUILDING_GRANARY")).findFirst().orElse(null);
					check("Civ's own list of what the capital can build arrives", granary != null && options.size() >= 3, options.size() + " choices");
					session.gameplay.productionMenu(fake, romeKey);
					if (granary == null) {
						finish();
						return;
					}
					session.gameplay.chooseProduction(fake, romeKey, granary);
					next();
				}
			}
			case 11 -> {
				String building;
				synchronized (mock) {
					building = mock.cityNamed("Rome").productionType;
				}
				var shown = state.cities().get(romeKey);
				if ((building.equals("BUILDING_GRANARY") && shown != null && shown.productionType().equals("BUILDING_GRANARY")) || ++waited > 100) {
					check("choosing a Granary at the governor changes what the real city builds", building.equals("BUILDING_GRANARY"), building);
					next();
				}
			}
			case 12 -> {
				var options = choices("research", 0, 0);
				if (options != null) {
					var bronze = options.stream().filter(o -> o.type().equals("TECH_BRONZE_WORKING")).findFirst().orElse(null);
					check("Civ's own list of what can be researched arrives", bronze != null, options.size() + " choices");
					session.gameplay.researchMenu(fake);
					if (bronze != null) {
						session.gameplay.chooseResearch(fake, bronze);
					}
					next();
				}
			}
			case 13 -> {
				String research;
				synchronized (mock) {
					research = mock.researchType;
				}
				if (research.equals("TECH_BRONZE_WORKING") || ++waited > 100) {
					check("choosing research in Minecraft sets it in Civ", research.equals("TECH_BRONZE_WORKING"), research);
					check("iron tools cannot be crafted before Iron Working", "IRON_WORKING".equals(session.missingTech("minecraft:iron_pickaxe")),
						String.valueOf(session.missingTech("minecraft:iron_pickaxe")));
					check("stone tools can always be crafted", session.missingTech("minecraft:stone_pickaxe") == null, "");
					session.setRule("techGate", false);
					boolean free = session.missingTech("minecraft:iron_pickaxe") == null;
					session.setRule("techGate", true);
					check("the rule can be switched off and on again from the Codex", free && "IRON_WORKING".equals(session.missingTech("minecraft:iron_pickaxe")), "");
					// Exploring: stand on known land that borders the unknown.
					frontier = null;
					var grid = state.session().grid();
					for (int i = 0; i < state.plotCount() && frontier == null; i++) {
						PlotCoord at = new PlotCoord(i % grid.width(), i / grid.width());
						var plot = state.plot(i);
						if (plot.revealed() && !plot.water() && plot.elevation() == nl.jordythomassen.livingworld.state.PlotState.FLAT
							&& grid.neighbors(at).stream().anyMatch(n -> !state.plot(n).revealed())) {
							frontier = at;
						}
					}
					check("there is a frontier to explore", frontier != null, "");
					if (frontier == null) {
						finish();
						return;
					}
					int x = model.centerX(frontier);
					int z = model.centerZ(frontier);
					fake.setPos(x + 0.5, model.standY(x, z), z + 0.5);
					session.gameplay.explore(fake);
					next();
				}
			}
			case 14 -> {
				boolean known = state.session().grid().neighbors(frontier).stream().allMatch(n -> state.plot(n).revealed());
				if (known || ++waited > 100) {
					check("walking to the edge of the known world explores the land around", known, "at " + frontier);
					// Working the land: a farm on open ground inside Rome's borders.
					CityState rome = state.cities().get(romeKey);
					workPlot = new PlotCoord(rome.at().x() - 1, rome.at().y());
					synchronized (mock) {
						mock.setPlot(workPlot.x(), workPlot.y(), nl.jordythomassen.livingworld.mock.MockCiv.ROME, false);
					}
					next();
				}
			}
			case 15 -> {
				if (state.plot(workPlot).owner() != viewer && ++waited < 100) {
					return;
				}
				var options = choices("works", workPlot.x(), workPlot.y());
				if (options != null) {
					var farm = options.stream().filter(o -> o.type().equals("BUILD_FARM")).findFirst().orElse(null);
					check("Civ's own list of what this land can take arrives", farm != null, options.size() + " choices");
					int x = model.centerX(workPlot);
					int z = model.centerZ(workPlot);
					fake.setPos(x + 0.5, model.standY(x, z), z + 0.5);
					session.gameplay.workMenu(fake);
					if (farm == null) {
						finish();
						return;
					}
					session.gameplay.work(fake, workPlot, farm);
					check("a farm cannot be had for nothing", count(fake, net.minecraft.world.item.Items.WHEAT_SEEDS) == 0
						&& state.plot(workPlot).improvement() < 0, "");
					fake.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT_SEEDS, 20));
					session.gameplay.work(fake, workPlot, farm);
					next();
				}
			}
			case 16 -> {
				String type = state.legend().type(nl.jordythomassen.livingworld.state.Legend.Kind.IMPROVEMENT, state.plot(workPlot).improvement());
				if (type.equals("IMPROVEMENT_FARM") || ++waited > 100) {
					check("sixteen seeds and a request build a real farm in Civ", type.equals("IMPROVEMENT_FARM"), type);
					check("the seeds are used up", count(fake, net.minecraft.world.item.Items.WHEAT_SEEDS) == 4, count(fake, net.minecraft.world.item.Items.WHEAT_SEEDS) + " left");
					synchronized (mock) {
						goldBefore = mock.gold;
					}
					session.gameplay.cityMenu(fake, state.cities().values().stream().filter(c -> c.key().owner() == nl.jordythomassen.livingworld.mock.MockCiv.GENEVA)
						.findFirst().map(CityState::key).orElse(romeKey));
					session.gameplay.gift(fake, nl.jordythomassen.livingworld.mock.MockCiv.GENEVA, 250);
					next();
				}
			}
			case 17 -> {
				var geneva = state.relations().get(nl.jordythomassen.livingworld.mock.MockCiv.GENEVA);
				int gold;
				synchronized (mock) {
					gold = mock.gold;
				}
				if ((geneva != null && geneva.influence() >= 35) || ++waited > 100) {
					check("a gift of gold to a city-state leaves the treasury and raises influence", gold == goldBefore - 250 && geneva != null && geneva.influence() >= 35,
						"gold " + goldBefore + " -> " + gold + ", influence " + (geneva == null ? "?" : geneva.influence()));
					// What a player builds stays; what was harvested grows back with the next turn.
					int x = model.centerX(workPlot);
					int z = model.centerZ(workPlot);
					LevelChunk chunk = level.getChunk(x >> 4, z >> 4);
					session.painter.paintNow(level, chunk);
					crop = null;
					for (int dx = -8; dx <= 8 && crop == null; dx++) {
						for (int dz = -8; dz <= 8 && crop == null; dz++) {
							BlockPos at = new BlockPos(x + dx, BoardModel.LAND + 1, z + dz);
							if (level.getChunk(at.getX() >> 4, at.getZ() >> 4) == chunk && level.getBlockState(at).is(Blocks.WHEAT)) {
								crop = at;
							}
						}
					}
					check("the new farm is painted as a wheat field", crop != null, "");
					if (crop == null) {
						finish();
						return;
					}
					level.setBlock(crop, Blocks.AIR.defaultBlockState(), 2);
					keepsake = crop.above(6);
					level.setBlock(keepsake, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
					session.painter.paintNow(level, chunk);
					check("within the same turn the harvested wheat stays harvested", level.getBlockState(crop).isAir(), "");
					check("a block the player placed is left alone", level.getBlockState(keepsake).is(Blocks.GOLD_BLOCK), "");
					turnBefore = state.turn();
					session.endTurn(fake);
					next();
				}
			}
			case 18 -> {
				if (state.turn() > turnBefore || ++waited > 100) {
					session.painter.paintNow(level, level.getChunk(crop.getX() >> 4, crop.getZ() >> 4));
					check("with the next Civ turn the wheat has grown back", level.getBlockState(crop).is(Blocks.WHEAT), level.getBlockState(crop).toString());
					check("and the player's block is still there", level.getBlockState(keepsake).is(Blocks.GOLD_BLOCK), "");
					fake.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, Codex.item("Rome"));
					fake.setShiftKeyDown(true);
					check("sneaking with the Codex on the ground asks what that land can take",
						session.useBlock(fake, net.minecraft.world.InteractionHand.MAIN_HAND, crop.below()), "");
					fake.setShiftKeyDown(false);
					check("the click that follows it in the same moment opens nothing more", session.useItem(fake, net.minecraft.world.InteractionHand.MAIN_HAND), "");
					fake.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_AXE));
					check("an ordinary tool on the ground is left to the game", !session.useBlock(fake, net.minecraft.world.InteractionHand.MAIN_HAND, crop.below())
						&& !session.useItem(fake, net.minecraft.world.InteractionHand.MAIN_HAND), "");
					// A social policy comes due: Civ will not end the turn until one is adopted.
					synchronized (mock) {
						mock.policyDue = true;
						mock.civsRev++;
					}
					next();
				}
			}
			case 19 -> {
				boolean due = state.empire() != null && state.empire().policyDue();
				if (due || ++waited > 100) {
					check("that Civ is waiting for a policy shows on this side", due, state.empire() == null ? "no empire" : state.empire().blocking());
					java.util.List<nl.jordythomassen.livingworld.protocol.Records.Option> offered = choices("policy", 0, 0);
					if (offered == null) {
						return;
					}
					var tradition = offered.stream().filter(o -> o.type().equals("POLICY_BRANCH_TRADITION")).findFirst().orElse(null);
					check("Civ's own list of policies arrives", tradition != null, offered.size() + " choices");
					session.gameplay.policyMenu(fake);
					if (tradition == null) {
						finish();
						return;
					}
					session.gameplay.choosePolicy(fake, tradition);
					next();
				}
			}
			case 20 -> {
				var empire = state.empire();
				boolean adopted = empire != null && empire.policies() == 1 && !empire.policyDue();
				if (adopted || ++waited > 100) {
					check("a policy chosen in Minecraft is adopted in Civ, and Civ stops waiting", adopted, empire == null ? "no empire" : empire.policies() + " policies, waiting for '"
						+ empire.blocking() + "'");
					finish();
				}
			}
			default -> {
			}
		}
	}

	private void finish() {
		LivingWorldMod.LOG.info("SELFTEST finished: {}", failures == 0 ? "all checks passed" : failures + " checks FAILED");
		stage = 99;
		session.server.halt(false);
	}
}
