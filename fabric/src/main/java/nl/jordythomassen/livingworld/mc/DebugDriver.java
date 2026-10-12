package nl.jordythomassen.livingworld.mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.state.EntityKey;

/**
 * A test aid, off unless Minecraft is started with -Dlivingworld.debug=true. It reads lines from
 * config/livingworld-debug.txt and does for the first player what that player could do by hand:
 * go somewhere, pick a menu option at a unit or a city. It calls the very same code as the menu
 * buttons, so a test driven this way exercises the real path, without a keyboard.
 *
 * <pre>
 * home | goto x y | near | frontier | status | list | empire | endturn | dusk | stop
 * codex | bell | lectern | work | research            (open what a click would open)
 * options build|research|works                        (log Civ's own list)
 * options build|research|works|policy
 * build TYPE | learn TYPE | improve TYPE | adopt TYPE (choose from that list; "first" takes the first)
 * craft item-id                                       (which technology the item still needs)
 * give logs|bread|item-id n
 * unit owner id orders|escort|heal|menu        (or: unit mine orders|...)
 * city owner id deliver|menu|production        (or: city capital deliver|menu|production)
 * </pre>
 */
final class DebugDriver {
	private final LivingWorldServer session;
	private final Path file = FabricLoader.getInstance().getGameDir().resolve("config").resolve("livingworld-debug.txt");

	DebugDriver(LivingWorldServer session) {
		this.session = session;
	}

	void tick() {
		if (!Files.exists(file)) {
			return;
		}
		List<String> lines;
		try {
			lines = Files.readAllLines(file);
			Files.delete(file);
		} catch (IOException e) {
			return;
		}
		List<ServerPlayer> players = session.server.getPlayerList().getPlayers();
		if (players.isEmpty()) {
			LivingWorldMod.LOG.info("DEBUG no player in the world");
			return;
		}
		ServerPlayer player = players.get(0);
		for (String line : lines) {
			String[] w = line.trim().split("\\s+");
			if (w[0].isEmpty()) {
				continue;
			}
			try {
				run(player, w);
				LivingWorldMod.LOG.info("DEBUG did: {} (player at {} {} {}, plot {})", line.trim(), String.format("%.1f", player.getX()),
					String.format("%.1f", player.getY()), String.format("%.1f", player.getZ()),
					session.model() == null ? "?" : session.model().plotAt(player.getBlockX(), player.getBlockZ()));
			} catch (RuntimeException e) {
				LivingWorldMod.LOG.info("DEBUG failed: {} ({})", line.trim(), e.toString());
			}
		}
	}

	private EntityKey mine() {
		var state = session.state();
		return state.units().values().stream().filter(u -> u.key().owner() == state.session().viewer() && u.combat()).findFirst()
			.orElseThrow(() -> new IllegalStateException("the player has no combat unit in sight")).key();
	}

	private EntityKey capital() {
		var state = session.state();
		return state.cities().values().stream().filter(c -> c.key().owner() == state.session().viewer())
			.sorted((a, b) -> Boolean.compare(b.capital(), a.capital())).findFirst()
			.orElseThrow(() -> new IllegalStateException("the player has no city")).key();
	}

	private PlotCoord here(ServerPlayer player) {
		PlotCoord plot = session.model().plotAt(player.getBlockX(), player.getBlockZ());
		if (plot == null) {
			throw new IllegalStateException("the player is off the map");
		}
		return plot;
	}

	/** Asks Civ for one of its lists and takes the entry of that type ("first": whatever comes first), as a click on its button would. */
	private void pick(ServerPlayer player, String kind, int a, int c, String wanted,
		java.util.function.BiConsumer<ServerPlayer, nl.jordythomassen.livingworld.protocol.Records.Option> then) {
		session.ask(player, kind, a, c, (who, options) -> {
			var option = options.stream().filter(o -> wanted.equals("first") || o.type().equals(wanted)).findFirst().orElse(null);
			if (option == null) {
				LivingWorldMod.LOG.info("DEBUG {} is not among the {} {} choices Civ offers", wanted, options.size(), kind);
				return;
			}
			LivingWorldMod.LOG.info("DEBUG choosing {} '{}'", option.type(), option.name());
			then.accept(who, option);
		});
	}

	/** Asks about one hex after another until one is for sale, and takes the player there. */
	private void forSale(ServerPlayer player, java.util.Iterator<PlotCoord> candidates) {
		if (!candidates.hasNext()) {
			LivingWorldMod.LOG.info("DEBUG no land for sale within three hexes of the capital");
			return;
		}
		PlotCoord plot = candidates.next();
		session.ask(player, "land", plot.x(), plot.y(), (who, options) -> {
			if (options.isEmpty()) {
				forSale(who, candidates);
			} else {
				session.teleport(who, plot);
				LivingWorldMod.LOG.info("DEBUG land for sale at {}: {} for {} gold", plot, options.get(0).name(), options.get(0).turns());
			}
		});
	}

	private void run(ServerPlayer player, String[] w) {
		switch (w[0]) {
			case "home" -> session.sendHome(player);
			// Something to carry to a city: what a walk over one's own land would have gathered.
			case "pack" -> {
				player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 6));
				player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT, 6));
				player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.RAW_GOLD, 3));
			}
			case "goto" -> session.teleport(player, new PlotCoord(Integer.parseInt(w[1]), Integer.parseInt(w[2])));
			case "stop" -> session.server.halt(false);
			case "simulate" -> ((nl.jordythomassen.livingworld.unciv.UncivLink) session.link).simulate(Integer.parseInt(w[1]));
			case "above" -> session.lookFromAbove(player);
			// Setting a scene for the newer pages, on the engine's own thread: an introduction to everyone, a purse,
			// faith enough for a pantheon, a foreign city fallen to the player as at the end of a siege.
			// forsale: go and stand on a hex that one of the player's cities could buy.
			case "forsale" -> {
				var state = session.state();
				var grid = state.session().grid();
				PlotCoord from = state.cities().get(capital()).at();
				java.util.List<PlotCoord> candidates = new java.util.ArrayList<>();
				for (int x = 0; x < grid.width(); x++) {
					for (int y = 0; y < grid.height(); y++) {
						PlotCoord at = new PlotCoord(x, y);
						var plot = state.plot(at);
						if (plot.revealed() && plot.owner() < 0 && !plot.water() && plot.elevation() != nl.jordythomassen.livingworld.state.PlotState.MOUNTAIN
							&& grid.distance(from, at) <= 3) {
							candidates.add(at);
						}
					}
				}
				candidates.sort(java.util.Comparator.comparingInt(at -> grid.distance(from, at)));
				forSale(player, candidates.iterator());
			}
			// aimstrike: hang over the place the selected unit can strike that lies furthest from the capital, looking straight down at it.
			case "aimstrike" -> {
				EntityKey unit = session.selectedUnit(player);
				if (unit == null) {
					throw new IllegalStateException("no unit selected");
				}
				PlotCoord from = session.state().cities().get(capital()).at();
				session.ask(player, "reach", unit.id(), 0, (who, options) -> {
					var grid = session.state().session().grid();
					PlotCoord best = null;
					for (var option : options) {
						if (option.group() == 12) {
							String[] xy = option.type().split(",");
							PlotCoord at = new PlotCoord(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));
							if (best == null || grid.distance(from, at) > grid.distance(from, best)) {
								best = at;
							}
						}
					}
					if (best == null) {
						LivingWorldMod.LOG.info("DEBUG nothing in range to strike");
						return;
					}
					who.getAbilities().mayfly = true;
					who.getAbilities().flying = true;
					who.onUpdateAbilities();
					int x = session.model().centerX(best);
					int z = session.model().centerZ(best);
					who.teleportTo(session.server.overworld(), x + 0.5, session.model().standY(x, z) + 40, z + 0.5, java.util.Set.of(), 180f, 90f, false);
					LivingWorldMod.LOG.info("DEBUG over {} ({} hexes from the capital)", best, grid.distance(from, best));
				});
			}
			// aimfoe: hang over the nearest unit of somebody the player is at war with, looking straight down at it.
			case "aimfoe" -> {
				var state = session.state();
				PlotCoord from = state.cities().get(capital()).at();
				var foe = state.units().values().stream().filter(u -> state.civ(u.key().owner()) != null && state.civ(u.key().owner()).atWarWithViewer()
					&& !state.civ(u.key().owner()).barbarian()).min(java.util.Comparator.comparingInt(u -> state.session().grid().distance(from, u.at())))
					.orElseThrow(() -> new IllegalStateException("no enemy unit in sight"));
				player.getAbilities().mayfly = true;
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
				int x = session.model().centerX(foe.at());
				int z = session.model().centerZ(foe.at());
				player.teleportTo(session.server.overworld(), x + 0.5, session.model().standY(x, z) + 40, z + 0.5, java.util.Set.of(), 180f, 90f, false);
				LivingWorldMod.LOG.info("DEBUG over the {} at {}", foe.name(), foe.at());
			}
			case "seat" -> {
				// A second civilization of a person, with nobody at it: the game is then one of several people, as the
				// player's own screen shows it (the others' land hidden, the turn waiting for nobody who is away).
				var engine = (nl.jordythomassen.livingworld.unciv.UncivLink) session.link;
				for (var civ : engine.game().getCivilizations()) {
					if (civ.isMajorCiv() && civ.isAI() && !civ.isDefeated()) {
						int id = engine.game().getCivilizations().indexOf(civ);
						engine.rule(id, true, refusal -> LivingWorldMod.LOG.info("DEBUG {} is a civilization of a person now{}", civ.getCivName(), refusal.isEmpty() ? "" : ": " + refusal));
						break;
					}
				}
			}
			case "meet", "gold", "faith", "spy", "take", "siege", "prophet", "callvote", "spawn", "propose", "foe", "xp", "culture", "building" -> {
				var engine = (nl.jordythomassen.livingworld.unciv.UncivLink) session.link;
				engine.onGameThread(() -> {
					var me = engine.viewer();
					switch (w[0]) {
						case "meet" -> {
							for (var civ : engine.game().getCivilizations()) {
								if (civ != me && !civ.isBarbarian() && !civ.isSpectator() && !me.knows(civ)) {
									me.getDiplomacyFunctions().makeCivilizationsMeet(civ, false);
								}
							}
						}
						case "gold" -> me.addGold(Integer.parseInt(w[1]));
						case "culture" -> me.getPolicies().addCulture(Integer.parseInt(w[1]));
						// Faith enough for a pantheon, or as much as is said ("faith 5000").
						case "faith" -> me.getReligionManager().setStoredFaith(w.length > 1 ? Integer.parseInt(w[1]) : me.getReligionManager().faithForPantheon(0) + 5);
						case "spy" -> {
							if (engine.game().isEspionageEnabled()) {
								me.getEspionageManager().addSpy();
							}
						}
						// Another civilization puts a small offer to the player: 10 gold of theirs for nothing.
						case "propose" -> {
							for (var civ : engine.game().getCivilizations()) {
								if (civ.isMajorCiv() && civ != me && me.knows(civ) && !me.isAtWarWith(civ)) {
									civ.addGold(50);
									var trade = new com.unciv.logic.trade.Trade();
									trade.getTheirOffers().add(new com.unciv.logic.trade.TradeOffer("Gold", com.unciv.logic.trade.TradeOfferType.Gold, 10, engine.game().getSpeed()));
									me.getTradeRequests().add(new com.unciv.logic.trade.TradeRequest(civ.getCivID(), trade));
									break;
								}
							}
						}
						// War with the first other civilization, and one of its Warriors in the open two hexes from the capital.
						case "foe" -> {
							var home = me.getCapital(false).getCenterTile();
							for (var civ : engine.game().getCivilizations()) {
								if (civ.isMajorCiv() && civ != me) {
									if (!me.knows(civ)) {
										me.getDiplomacyFunctions().makeCivilizationsMeet(civ, false);
									}
									if (!me.isAtWarWith(civ)) {
										me.getDiplomacyManager(civ).declareWar(new com.unciv.logic.civilization.diplomacy.DeclareWarReason(
											com.unciv.logic.civilization.diplomacy.WarType.DirectWar, null));
									}
									for (var tile : nl.jordythomassen.livingworld.unciv.Kt.list(home.getTilesInDistanceRange(new kotlin.ranges.IntRange(2, 2)))) {
										if (tile.isLand() && !tile.isImpassible() && tile.getMilitaryUnit() == null && !tile.isCityCenter()) {
											engine.game().getTileMap().placeUnitNearTile(tile.getPosition(), "Warrior", civ, null);
											LivingWorldMod.LOG.info("DEBUG a Warrior of {} stands two hexes from the capital", civ.getCivName());
											break;
										}
									}
									break;
								}
							}
						}
						// Experience enough for a promotion, for every soldier the player has.
						case "xp" -> {
							for (var unit : nl.jordythomassen.livingworld.unciv.Kt.list(me.getUnits().getCivUnits())) {
								if (unit.isMilitary()) {
									unit.getPromotions().setXP(unit.getPromotions().getXP() + 30);
								}
							}
						}
						// A Great Prophet in the capital, ready to found a religion.
						case "prophet" -> me.getUnits().addUnit("Great Prophet", me.getCapital(false));
						// The nations are called to vote for a world leader.
						case "callvote" -> me.addFlag(com.unciv.logic.civilization.CivFlags.TurnsTillNextDiplomaticVote.name(), 0);
						// A unit of the player's by its Unciv name, next to the capital: spawn Horseman, spawn Trireme.
						// A building in the capital, as if it had been built: "building Market" gives a place for a Merchant.
						case "building" -> me.getCapital(false).getCityConstructions().addBuilding(String.join(" ", java.util.Arrays.copyOfRange(w, 1, w.length)));
						case "spawn" -> {
							String kind = String.join(" ", java.util.Arrays.copyOfRange(w, 1, w.length));
							var home = me.getCapital(false).getCenterTile();
							var at = home;
							if (engine.game().getRuleset().getUnits().get(kind).isWaterUnit()) {
								// A ship goes on the nearest water, however far from the capital that is.
								at = null;
								for (var tile : nl.jordythomassen.livingworld.unciv.Kt.list(home.getTilesInDistance(12))) {
									if (tile.isWater() && tile.getMilitaryUnit() == null && (at == null || tile.aerialDistanceTo(home) < at.aerialDistanceTo(home))) {
										at = tile;
									}
								}
								if (at == null) {
									throw new IllegalStateException("no water within twelve hexes of the capital");
								}
								for (var tile : nl.jordythomassen.livingworld.unciv.Kt.list(at.getTilesInDistance(1))) {
									tile.setExplored(me, true, null);
								}
							}
							var made = engine.game().getTileMap().placeUnitNearTile(at.getPosition(), kind, me, null);
							LivingWorldMod.LOG.info("DEBUG spawned {}", made == null ? "nothing: no room for a " + kind : kind);
						}
						// The end of a siege: war with the nearest other civilization, its nearest city down to its last
						// and without a garrison, a Swordsman of the player's at its gate, and the land around it known.
						case "siege" -> {
							com.unciv.logic.city.City nearest = null;
							for (var civ : engine.game().getCivilizations()) {
								if (civ.isMajorCiv() && civ != me) {
									for (var city : civ.getCities()) {
										if (nearest == null || city.getCenterTile().aerialDistanceTo(me.getCapital(false).getCenterTile())
											< nearest.getCenterTile().aerialDistanceTo(me.getCapital(false).getCenterTile())) {
											nearest = city;
										}
									}
								}
							}
							if (nearest == null) {
								throw new IllegalStateException("no foreign city");
							}
							var them = nearest.getCiv();
							if (!me.knows(them)) {
								me.getDiplomacyFunctions().makeCivilizationsMeet(them, false);
							}
							if (!me.isAtWarWith(them)) {
								me.getDiplomacyManager(them).declareWar(new com.unciv.logic.civilization.diplomacy.DeclareWarReason(
									com.unciv.logic.civilization.diplomacy.WarType.DirectWar, null));
							}
							nearest.setHealth(1);
							if (nearest.getCenterTile().getMilitaryUnit() != null) {
								nearest.getCenterTile().getMilitaryUnit().destroy(false);
							}
							engine.game().getTileMap().placeUnitNearTile(nearest.getCenterTile().getPosition(), "Swordsman", me, null);
							for (var tile : nl.jordythomassen.livingworld.unciv.Kt.list(nearest.getCenterTile().getTilesInDistance(3))) {
								tile.setExplored(me, true, null);
							}
							LivingWorldMod.LOG.info("DEBUG siege of {} ({})", nearest.getName(), them.getCivName());
						}
						default -> {
							for (var civ : engine.game().getCivilizations()) {
								if (civ.isMajorCiv() && civ != me && !civ.getCities().isEmpty()) {
									civ.getCities().get(civ.getCities().size() - 1).puppetCity(me);
									break;
								}
							}
						}
					}
				});
			}
			// What a right-click with the Codex in hand does, wherever the player is looking.
			case "use" -> {
				player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, Codex.item("Rome"));
				LivingWorldMod.LOG.info("DEBUG use answered: {}, selected {}", session.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND), session.selectedUnit(player));
			}
			// select: the first combat unit; select Swordsman: the first unit of that kind.
			case "select" -> {
				var state = session.state();
				EntityKey pick = w.length < 2 ? mine() : state.units().values().stream().filter(u -> u.key().owner() == state.session().viewer()
					&& u.name().toLowerCase(java.util.Locale.ROOT).contains(w[1].toLowerCase(java.util.Locale.ROOT))).findFirst()
					.orElseThrow(() -> new IllegalStateException("no such unit")).key();
				session.select(player, pick);
				LivingWorldMod.LOG.info("DEBUG selected {} at {}", state.units().get(pick).name(), state.units().get(pick).at());
			}
			// aim: hang over the nearest city of somebody the player is at war with, looking straight down at it.
			case "aim" -> {
				var state = session.state();
				var foe = state.cities().values().stream().filter(c -> state.civ(c.key().owner()) != null && state.civ(c.key().owner()).atWarWithViewer()).findFirst()
					.orElseThrow(() -> new IllegalStateException("no enemy city in sight"));
				player.getAbilities().mayfly = true;
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
				player.teleportTo(session.server.overworld(), session.model().centerX(foe.at()) + 0.5, nl.jordythomassen.livingworld.board.BoardModel.LAND + 44,
					session.model().centerZ(foe.at()) + 0.5, java.util.Set.of(), 180f, 90f, false);
				LivingWorldMod.LOG.info("DEBUG over {} at {}", foe.name(), foe.at());
			}
			// unitview Swordsman: stand above and behind the first unit of that kind, looking at it.
			case "unitview" -> {
				var state = session.state();
				var unit = state.units().values().stream().filter(u -> u.name().toLowerCase(java.util.Locale.ROOT).contains(w[1].toLowerCase(java.util.Locale.ROOT)))
					.findFirst().orElseThrow(() -> new IllegalStateException("no such unit"));
				double distance = w.length > 2 ? Double.parseDouble(w[2]) : 6;
				player.getAbilities().mayfly = true;
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
				int x = session.model().centerX(unit.at());
				int z = session.model().centerZ(unit.at());
				player.teleportTo(session.server.overworld(), x + 0.5 + distance * 0.5, session.model().standY(x, z) + distance * 0.45, z + 0.5 + distance, java.util.Set.of(),
					153f, 22f, false);
			}
			case "hold" -> player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, Codex.item("Rome"));
			case "page" -> {
				switch (w[1]) {
					case "realm" -> session.codex.realm(player);
					case "others" -> session.codex.others(player);
					case "newgame" -> session.codex.newGame(player);
					case "settings" -> session.codex.settings(player);
					case "proposals" -> session.codex.proposals(player, true);
					case "travel" -> session.codex.travel(player);
					default -> throw new IllegalArgumentException("unknown page");
				}
			}
			case "deselect" -> session.deselect(player);
			// view x y z yaw pitch: stand exactly there, looking exactly that way (for pictures).
			case "view" -> player.teleportTo(session.server.overworld(), Double.parseDouble(w[1]), Double.parseDouble(w[2]), Double.parseDouble(w[3]), java.util.Set.of(),
				Float.parseFloat(w[4]), Float.parseFloat(w[5]), false);
			case "cityview" -> {
				// South-east of a city of one's own (the n-th, capital first), above its roofs, looking back at it.
				var state = session.state();
				var mine = state.cities().values().stream().filter(c -> c.key().owner() == state.session().viewer())
					.sorted((a, b) -> Boolean.compare(b.capital(), a.capital())).toList();
				var city = mine.get(w.length > 1 ? Integer.parseInt(w[1]) % mine.size() : 0);
				double distance = w.length > 2 ? Double.parseDouble(w[2]) : 20;
				player.getAbilities().mayfly = true;
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
				player.teleportTo(session.server.overworld(), session.model().centerX(city.at()) + distance, nl.jordythomassen.livingworld.board.BoardModel.LAND + distance * 0.8,
					session.model().centerZ(city.at()) + distance, java.util.Set.of(), 135f, 32f, false);
				LivingWorldMod.LOG.info("DEBUG looking at {} ({} citizens, {})", city.name(), city.population(), city.buildings());
			}
			case "found" -> {
				var state = session.state();
				var settler = state.units().values().stream().filter(u -> u.key().owner() == state.session().viewer() && u.type().equals("UNIT_SETTLER")).findFirst()
					.orElseThrow(() -> new IllegalStateException("no Settler"));
				session.submit(player, nl.jordythomassen.livingworld.commands.CommandOp.UNIT_ACTION, settler.key(), null, 0, null, "Settler", null, "FoundCity", false);
			}
			case "endturn" -> session.endTurn(player);
			case "codex" -> session.codex.open(player);
			case "bell", "lectern" -> {
				// What a right-click on the bell or the lectern in the capital's hall does.
				int[] spot = session.model().hallFurniture(session.state().cities().get(capital()).at())[w[0].equals("bell") ? 0 : 1];
				LivingWorldMod.LOG.info("DEBUG {} answered: {}", w[0],
					session.useBlock(player, net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.core.BlockPos(spot[0], spot[1], spot[2])));
			}
			case "work" -> session.gameplay.workMenu(player);
			case "research" -> session.gameplay.researchMenu(player);
			case "status" -> LivingWorldMod.LOG.info("DEBUG status:\n{}", session.describe());
			case "give" -> player.getInventory().add(new ItemStack(w[1].equals("logs") ? Items.OAK_LOG : w[1].equals("bread") ? Items.BREAD
				: net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(w[1])), Integer.parseInt(w[2])));
			case "options" -> {
				PlotCoord here = here(player);
				int a = w[1].equals("build") ? capital().id() : w[1].equals("works") ? here.x() : 0;
				session.ask(player, w[1], a, w[1].equals("works") ? here.y() : 0, (who, options) -> {
					LivingWorldMod.LOG.info("DEBUG options {}: {} offered", w[1], options.size());
					for (var o : options) {
						LivingWorldMod.LOG.info("DEBUG   group {} id {} {} '{}' turns {}", o.group(), o.id(), o.type(), o.name(), o.turns());
					}
				});
			}
			case "build" -> pick(player, "build", capital().id(), 0, w[1], (who, o) -> session.gameplay.chooseProduction(who, capital(), o));
			case "learn" -> pick(player, "research", 0, 0, w[1], (who, o) -> session.gameplay.chooseResearch(who, o));
			case "adopt" -> pick(player, "policy", 0, 0, w[1], (who, o) -> session.gameplay.choosePolicy(who, o));
			case "policies" -> session.gameplay.policyMenu(player);
			case "improve" -> {
				PlotCoord here = here(player);
				pick(player, "works", here.x(), here.y(), w[1], (who, o) -> session.gameplay.work(who, here, o));
			}
			case "fell" -> {
				// As if the player had felled that many logs where they stand.
				for (int i = 0; i < Integer.parseInt(w[1]); i++) {
					session.gameplay.logBroken(player, player.blockPosition());
				}
			}
			case "craft" -> LivingWorldMod.LOG.info("DEBUG craft {}: {}", w[1], session.missingTech(w[1]) == null ? "allowed" : "needs " + session.missingTech(w[1]));
			case "dusk" -> session.debugDusk();
			case "frontier" -> {
				// A known piece of land with unknown land next to it, nearest to the capital: walking there explores.
				var state = session.state();
				var grid = state.session().grid();
				PlotCoord from = state.cities().get(capital()).at();
				PlotCoord best = null;
				for (int x = 0; x < grid.width(); x++) {
					for (int y = 0; y < grid.height(); y++) {
						PlotCoord at = new PlotCoord(x, y);
						var plot = state.plot(at);
						if (!plot.revealed() || plot.water() || plot.elevation() == nl.jordythomassen.livingworld.state.PlotState.MOUNTAIN) {
							continue;
						}
						boolean edge = grid.neighbors(at).stream().anyMatch(n -> !state.plot(n).revealed());
						if (edge && (best == null || grid.distance(from, at) < grid.distance(from, best))) {
							best = at;
						}
					}
				}
				if (best == null) {
					throw new IllegalStateException("no known land borders unknown land");
				}
				long unknown = grid.neighbors(best).stream().filter(n -> !state.plot(n).revealed()).count();
				LivingWorldMod.LOG.info("DEBUG frontier {} with {} unknown neighbours", best, unknown);
				session.teleport(player, best);
			}
			case "empire" -> {
				var state = session.state();
				var e = state.empire();
				LivingWorldMod.LOG.info("DEBUG waiting for '{}' ({}), policies {}", e == null ? "" : e.waitingFor(), e == null ? "" : e.blocking(), e == null ? 0 : e.policies());
				LivingWorldMod.LOG.info("DEBUG empire {}", e == null ? "none" : "gold " + e.gold() + " (" + e.goldPerTurn() + "/turn) science " + e.science() + " happiness "
					+ e.happiness() + " culture/turn " + e.culturePerTurn() + " era " + e.era() + " year " + e.yearText() + " research " + e.researchType() + " '"
					+ e.researchName() + "' in " + e.researchTurns() + " turns");
				LivingWorldMod.LOG.info("DEBUG techs {} {}", state.techs().size(), state.techs());
				state.civs().values().forEach(c -> LivingWorldMod.LOG.info("DEBUG civ {} {} era {} (tier {}) style {} war {}", c.id(), c.name(), c.era(), c.eraTier(),
					c.artStyle(), c.atWarWithViewer()));
				state.relations().values().forEach(r -> LivingWorldMod.LOG.info("DEBUG relation {}", r));
				state.cities().values().forEach(c -> LivingWorldMod.LOG.info("DEBUG city {} buildings {} wonders {} building '{}' {}", c.name(), c.buildings(), c.wonders(),
					c.productionName(), c.productionType()));
			}
			case "unit" -> {
				// "unit mine <action>" picks the player's first combat unit, so a test need not know ids.
				if (w[1].equals("mine")) {
					session.debugUnit(player, mine(), w[2]);
				} else {
					session.debugUnit(player, new EntityKey(Integer.parseInt(w[1]), Integer.parseInt(w[2])), w[3]);
				}
			}
			case "city" -> {
				if (w[1].equals("capital")) {
					session.debugCity(player, capital(), w[2]);
				} else {
					session.debugCity(player, new EntityKey(Integer.parseInt(w[1]), Integer.parseInt(w[2])), w[3]);
				}
			}
			case "near" -> {
				// Stand on a free plot next to the player's first combat unit.
				var state = session.state();
				var unit = state.units().get(mine());
				for (PlotCoord n : state.session().grid().neighbors(unit.at())) {
					var plot = state.plot(n);
					boolean taken = state.units().values().stream().anyMatch(u -> u.at().equals(n));
					if (plot.revealed() && !plot.water() && !plot.city() && plot.elevation() != nl.jordythomassen.livingworld.state.PlotState.MOUNTAIN && !taken) {
						session.teleport(player, n);
						return;
					}
				}
				throw new IllegalStateException("no free plot next to the unit");
			}
			case "list" -> {
				var state = session.state();
				state.units().values().forEach(u -> LivingWorldMod.LOG.info("DEBUG unit {} {} at {} hp {} moves {}", u.key(), u.name(), u.at(), u.hp(), u.moves()));
				state.cities().values().forEach(c -> LivingWorldMod.LOG.info("DEBUG city {} {} at {} production {}/{}", c.key(), c.name(), c.at(), c.production(), c.productionNeeded()));
				LivingWorldMod.LOG.info("DEBUG turn {} viewerTurn {}", state.turn(), session.link.viewerTurn());
			}
			default -> throw new IllegalArgumentException("unknown debug command");
		}
	}
}
