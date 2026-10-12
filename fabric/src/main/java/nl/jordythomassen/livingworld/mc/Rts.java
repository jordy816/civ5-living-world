package nl.jordythomassen.livingworld.mc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import nl.jordythomassen.livingworld.board.BoardModel;
import nl.jordythomassen.livingworld.commands.CommandOp;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.protocol.Records;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.StateCodec;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * Ruling from above: the server's side of the top-down view. A player in it has no body in the
 * world (a spectator high over the land, moved about by their own game as they pan); everything
 * they see on their screen comes from the picture of the game sent here, and everything they
 * click arrives here as a wish and goes through the same checked commands as before.
 */
final class Rts {
	private final LivingWorldServer session;
	private final Set<UUID> above = new HashSet<>();
	private final Map<UUID, GameType> modeBefore = new HashMap<>();
	private final Map<UUID, List<String>> news = new HashMap<>();
	private final Map<UUID, PlotCoord> lookAt = new HashMap<>();
	private final Set<UUID> stale = new HashSet<>();
	/** The pictures last sent, as bytes: one for everybody, or one per civilization of people. */
	private final Map<WorldState, byte[]> encoded = new java.util.IdentityHashMap<>();

	Rts(LivingWorldServer session) {
		this.session = session;
	}

	/** Whether this player's game understands the messages at all (it runs this mod). */
	static boolean speaks(ServerPlayer player) {
		return ServerPlayNetworking.canSend(player, Net.State.TYPE);
	}

	boolean isAbove(ServerPlayer player) {
		return above.contains(player.getUUID());
	}

	boolean anyoneAbove() {
		return !above.isEmpty();
	}

	/** Lifts a player out of the world to rule from above. */
	void enter(ServerPlayer player) {
		if (!speaks(player) || !above.add(player.getUUID())) {
			return;
		}
		modeBefore.put(player.getUUID(), player.gameMode());
		// Marked on the player, and saved with them: should the game end before they come down, the next join knows this
		// onlooker was made one by the view, and is not somebody who chose to be one.
		player.addTag(ABOVE_TAG);
		player.setGameMode(GameType.SPECTATOR);
		flags(player);
		sendState(player);
		refresh(player);
	}

	/** The mode a player is in when they are not ruling from above: what they had before, or the world's own. */
	private GameType modeBelow(GameType before) {
		GameType own = session.server.getDefaultGameType();
		return before != null && before != GameType.SPECTATOR ? before : own != GameType.SPECTATOR ? own : GameType.SURVIVAL;
	}

	/**
	 * Tells a player's game at once which view they are in and whose turn it is, with what was
	 * last known of their selection. The full picture follows when the game has answered; that
	 * can take a while (the other civilizations may be moving), and the player's own game must
	 * not go on steering a view the player has left.
	 */
	private void flags(ServerPlayer player) {
		if (!speaks(player)) {
			return;
		}
		RtsView last = sent.get(player.getUUID());
		RtsView view = new RtsView();
		if (last != null && isAbove(player)) {
			view.selected = last.selected;
			view.actions = last.actions;
			view.reach = last.reach;
			view.attack = last.attack;
			view.proposals = last.proposals;
			view.extra = last.extra;
		}
		send(player, view);
	}

	/** Sends a view, saying which mode and whose turn it is as of this very moment. */
	private void send(ServerPlayer player, RtsView view) {
		view.rts = isAbove(player);
		view.me = view.rts ? session.ruler.figureAt(player) : null;
		int civ = session.seats.of(player);
		view.seat = civ;
		view.several = session.seats.several();
		view.myTurn = civ >= 0 && session.link.mayAct(civ);
		view.wait = civ < 0 ? "watching" : session.link.waitsFor(civ);
		turnTold.put(player.getUUID(), view.myTurn + "|" + view.wait + "|" + civ);
		view.painting = session.painter.missing();
		// What was said is what the next check compares with: a count that went to nothing in between is then told as nothing.
		paintingTold = view.painting;
		ServerPlayNetworking.send(player, new Net.View(view.write()));
		RtsView kept = new RtsView();
		kept.selected = view.selected;
		kept.actions = view.actions;
		kept.reach = view.reach;
		kept.attack = view.attack;
		kept.proposals = view.proposals;
		kept.extra = view.extra;
		sent.put(player.getUUID(), kept);
	}

	/** Sets a player down on the land again, where they were looking. */
	/** Back down to walking: on the land in the middle of the view, or the nearest land one can stand on. */
	void leave(ServerPlayer player, PlotCoord wanted) {
		if (!above.remove(player.getUUID())) {
			return;
		}
		player.removeTag(ABOVE_TAG);
		player.setGameMode(modeBelow(modeBefore.remove(player.getUUID())));
		if (session.builtin && !player.getAbilities().mayfly) {
			// Changing mode took the gift of flight away again; a ruler keeps it.
			player.getAbilities().mayfly = true;
			player.onUpdateAbilities();
		}
		BoardModel model = session.model();
		WorldState state = session.stateOf(player);
		if (model != null && state != null) {
			// On the hex they pointed at when that is their own land, else the nearest of their own; with nothing pointed at, where their figure stands.
			PlotCoord best = session.ruler.landing(player, wanted);
			if (best != null) {
				session.place(player, best);
				if (wanted != null && !best.equals(wanted) && session.seats.of(player) >= 0) {
					player.sendSystemMessage(net.minecraft.network.chat.Component.literal("You are set down on your own land: the nearest of it to where you pointed. Beyond it you walk.")
						.withStyle(net.minecraft.ChatFormatting.YELLOW));
				}
			} else {
				session.sendHome(player);
			}
		}
		session.ruler.cameDown(player);
		flags(player);
		refresh(player);
	}

	void forget(ServerPlayer player) {
		GameType before = modeBefore.remove(player.getUUID());
		if (above.remove(player.getUUID())) {
			// Leaving the world while above: saved as what they were, not as the onlooker the view made of them.
			player.removeTag(ABOVE_TAG);
			player.setGameMode(modeBelow(before));
		}
		sent.remove(player.getUUID());
		news.remove(player.getUUID());
		lookAt.remove(player.getUUID());
		stale.remove(player.getUUID());
		turnTold.remove(player.getUUID());
	}

	/** A player's place in the game changed: they get the game as it is theirs now, and rule from above when that is how it is played. */
	void seated(ServerPlayer player) {
		if (!speaks(player)) {
			return;
		}
		sent.remove(player.getUUID());
		sendState(player);
		if (session.builtin && session.config.rts) {
			enter(player);
		}
		flags(player);
		refresh(player);
	}

	/** A line of news for one player: shown at the side of their screen. */
	void tell(ServerPlayer player, String text) {
		tell(player, text, "news", null);
	}

	/** News that says what kind it is (the picture beside it) and where it happened (where a click on it leads). */
	void tell(ServerPlayer player, String text, String kind, PlotCoord at) {
		if (speaks(player)) {
			news.computeIfAbsent(player.getUUID(), id -> new ArrayList<>()).add(kind + "|" + (at == null ? "-1|-1" : at.x() + "|" + at.y()) + "|" + text);
			stale.add(player.getUUID());
		}
	}

	/** Turns a player's view to a hex with their next picture. */
	void lookAt(ServerPlayer player, PlotCoord plot) {
		lookAt.put(player.getUUID(), plot);
		stale.add(player.getUUID());
	}

	/** The game changed: everyone gets the new picture, and what their selection can do now. */
	void stateChanged() {
		encoded.clear();
		for (ServerPlayer player : session.server.getPlayerList().getPlayers()) {
			if (speaks(player)) {
				sendState(player);
				stale.add(player.getUUID());
			}
		}
	}

	private void sendState(ServerPlayer player) {
		WorldState state = session.stateOf(player);
		if (state == null) {
			return;
		}
		ServerPlayNetworking.send(player, new Net.State(encoded.computeIfAbsent(state, StateCodec::write), session.config.hexWidth));
	}

	/** Once a tick: whoever has something new gets one view with all of it. */
	/** Carried by a player while the view from above has made an onlooker of them. */
	static final String ABOVE_TAG = "livingworld_above";

	/** The number of chunks still to paint that players above were last told. */
	private int paintingTold;
	/** What each player was last told of whether they may act, and why not. */
	private final Map<UUID, String> turnTold = new HashMap<>();
	/** What each player was last sent of their selection, for the quick word in between. */
	private final Map<UUID, RtsView> sent = new HashMap<>();

	void tick() {
		// Whoever looks from above (also at the table, before there is a game) hangs at the height their own game steers
		// them at. Left where they came in, their game would leap them up there itself, and a server that is not the
		// player's own puts such a leap back, ten times a second, for as long as it lasts.
		for (ServerPlayer player : session.server.getPlayerList().getPlayers()) {
			if (player.isSpectator() && (above.contains(player.getUUID()) || player.entityTags().contains(ABOVE_TAG))
				&& Math.abs(player.getY() - nl.jordythomassen.livingworld.board.Overlook.HANG_Y) > 8.0) {
				player.teleportTo(session.server.overworld(), player.getX(), nl.jordythomassen.livingworld.board.Overlook.HANG_Y, player.getZ(), java.util.Set.of(),
					player.getYRot(), player.getXRot(), false);
			}
		}
		// While the land is still being painted, whoever looks down at it is told how far that is, once a second.
		if (!above.isEmpty() && session.server.getTickCount() % 20 == 0) {
			int waiting = session.painter.missing();
			if (waiting != paintingTold) {
				stale.addAll(above);
			}
		}
		// The moment the turn passes to the others, or comes back: said at once, so the turn button never looks dead.
		for (UUID id : above) {
			ServerPlayer player = session.server.getPlayerList().getPlayer(id);
			if (player == null) {
				continue;
			}
			int civ = session.seats.of(player);
			String now = (civ >= 0 && session.link.mayAct(civ)) + "|" + (civ < 0 ? "watching" : session.link.waitsFor(civ)) + "|" + civ;
			if (!now.equals(turnTold.get(id))) {
				flags(player);
			}
		}
		if (stale.isEmpty()) {
			return;
		}
		List<UUID> now = new ArrayList<>(stale);
		stale.clear();
		for (UUID id : now) {
			ServerPlayer player = session.server.getPlayerList().getPlayer(id);
			if (player != null) {
				refresh(player);
			}
		}
	}

	/** Sends a player their view: at once when nothing is selected, else when the game has said what the selection can do. */
	void refresh(ServerPlayer player) {
		if (!speaks(player)) {
			return;
		}
		RtsView view = new RtsView();
		List<String> lines = news.remove(player.getUUID());
		if (lines != null) {
			view.notices.addAll(lines);
		}
		view.lookAt = lookAt.remove(player.getUUID());
		WorldState state = session.stateOf(player);
		EntityKey unit = state == null ? null : session.selectedUnit(player);
		if (state == null || !session.builtin || session.seats.of(player) < 0) {
			send(player, view);
			return;
		}
		view.selected = unit;
		// One question for all of it: what the unit in hand can do and reach, how many proposals wait, the empire's other numbers.
		session.ask(player, "view", unit == null ? -1 : unit.id(), 0, (who, rows) -> {
			for (Records.Option row : rows) {
				switch (row.group()) {
					case 7 -> view.actions.add(row);
					case 11, 12 -> {
						String[] xy = row.type().split(",");
						(row.group() == 12 ? view.attack : view.reach).add(new PlotCoord(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])));
					}
					case 30 -> view.proposals = row.id();
					default -> view.extra.add(row);
				}
			}
			send(who, view);
		});
	}

	/** Something a player did on their screen. */
	void act(ServerPlayer player, Net.Act act) {
		WorldState state = session.stateOf(player);
		switch (act.verb()) {
			case "probe" -> {
				// Development aid: what the server holds for a column of blocks, next to the client's own answer in its log.
				if (Boolean.getBoolean("livingworld.debug")) {
					var level = session.server.overworld();
					var chunk = level.getChunkSource().getChunkNow(act.a() >> 4, act.b() >> 4);
					StringBuilder out = new StringBuilder("PROBE server " + act.a() + "," + act.b() + (chunk == null ? " NOT LOADED" : " status " + chunk.getFullStatus()));
					if (chunk != null) {
						for (int y : new int[] { 50, 60, 64, 67, 70 }) {
							var pos = new net.minecraft.core.BlockPos(act.a(), y, act.b());
							out.append(" y").append(y).append('=').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos).getBlock()).getPath())
								.append("/sky").append(level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos));
						}
						out.append(" tracked ").append(level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false).size());
					}
					out.append(" player ").append(player.position()).append(" view ").append(session.server.getPlayerList().getViewDistance());
					LivingWorldMod.LOG.info(out.toString());
				}
				return;
			}
			case "goto" -> {
				// The view leapt: the player is put where their own game already has them, so the land there is loaded for them.
				BoardModel model = session.model();
				if (isAbove(player) && model != null) {
					int[] bounds = model.bounds();
					double x = Math.max(bounds[0] - 64, Math.min(bounds[2] + 64, act.a())) + 0.5;
					double z = Math.max(bounds[1] - 64, Math.min(bounds[3] + 64, act.c())) + 0.5;
					double y = Math.max(BoardModel.LAND, Math.min(BoardModel.TOP + 32, act.b()));
					if (player.distanceToSqr(x, y, z) > 64.0) {
						player.teleportTo(session.server.overworld(), x, y, z, java.util.Set.of(), player.getYRot(), player.getXRot(), false);
					}
				}
				return;
			}
			case "mode" -> {
				if (act.a() == 1) {
					enter(player);
				} else {
					// c, d: the hex in the middle of the player's view, when there is one.
					leave(player, state != null && act.c() >= 0 && act.d() >= 0 && act.c() < state.session().grid().width() && act.d() < state.session().grid().height()
						? new PlotCoord(act.c(), act.d()) : null);
				}
				return;
			}
			case "hello" -> {
				// The player's game is ready for pictures; whoever rules a built-in game starts above the land.
				sendState(player);
				boolean asked = session.lobby.hello(player);
				if (session.builtin && session.config.rts) {
					// Also while there is no game yet: a world without one has no land to stand on.
					enter(player);
				} else if (!asked) {
					refresh(player);
				}
				return;
			}
			case "lobby" -> {
				session.lobby.act(player, act);
				return;
			}
			default -> {
			}
		}
		if (state == null) {
			return;
		}
		int me = state.session().viewer();
		switch (act.verb()) {
			case "select" -> {
				EntityKey key = new EntityKey(act.a(), act.b());
				if (key.owner() == me && state.units().containsKey(key)) {
					session.select(player, key);
				}
				refresh(player);
			}
			case "deselect" -> {
				session.deselect(player);
				refresh(player);
			}
			case "move" -> {
				EntityKey unit = session.selectedUnit(player);
				if (unit != null) {
					session.send(player, unit, new PlotCoord(act.c(), act.d()));
				}
			}
			case "unit" -> {
				EntityKey unit = session.selectedUnit(player);
				UnitState unitState = unit == null ? null : state.units().get(unit);
				if (unitState == null) {
					return;
				}
				if (act.text().equals("Promote")) {
					session.promotions(player, unit);
				} else {
					session.submit(player, CommandOp.UNIT_ACTION, unit, null, 0, null, unitState.name(), null, act.text(), false);
				}
			}
			case "turn" -> session.endTurn(player);
			case "ask" -> {
				// A list for the player's own panels: what can be researched, adopted, or built or bought in a city.
				String kind = act.text();
				if (session.builtin && List.of("research", "policy", "build", "buy", "faith", "promote", "greatPeople").contains(kind)) {
					session.ask(player, kind, act.a(), 0, (who, options) -> {
						ServerPlayNetworking.send(who, new Net.Choices(kind, act.a(), options));
						// And what each of them is and does, for the line under its name and the card beside it.
						session.ask(who, "about:" + kind, act.a(), 0, (again, about) -> ServerPlayNetworking.send(again, new Net.Choices("about:" + kind, act.a(), about)));
					});
				}
			}
			case "choose" -> {
				// One line of such a list was clicked: kind, the option's type and its name, in that order.
				String[] parts = act.text().split("\n", 3);
				if (parts.length == 3) {
					Records.Option option = new Records.Option(act.b(), act.c(), parts[1], parts[2], act.d());
					EntityKey key = new EntityKey(me, act.a());
					CityState city = state.cities().get(key);
					switch (parts[0]) {
						case "research" -> session.gameplay.chooseResearch(player, option);
						case "policy" -> session.gameplay.choosePolicy(player, option);
						case "build" -> {
							if (city != null) {
								session.gameplay.chooseProduction(player, key, option);
							}
						}
						case "buy" -> {
							if (city != null) {
								session.submit(player, CommandOp.CITY_BUY, key, null, 0, null, "Buying " + option.name() + " in " + city.name(), null, option.type(), false);
							}
						}
						case "faith" -> {
							if (city != null) {
								session.submit(player, CommandOp.CITY_BUY, key, null, 0, null, "Buying " + option.name() + " in " + city.name(), null, "faith:" + option.type(), false);
							}
						}
						case "queue" -> {
							// Lined up behind what the city builds now.
							if (city != null) {
								session.submit(player, CommandOp.CITY_BUILD, key, null, 0, null, option.name() + " in " + city.name(), null, "queue:" + option.type(), false);
							}
						}
						case "unqueue" -> {
							if (city != null) {
								session.submit(player, CommandOp.CITY_BUILD, key, null, 0, null, option.name() + " in " + city.name(), null, "unqueue:" + act.c(), false);
							}
						}
						case "sell" -> {
							if (city != null) {
								session.submit(player, CommandOp.CHOICE, key, null, 0, null, "Selling the " + option.name() + " of " + city.name(), null, "sell:" + option.type(), false);
							}
						}
						case "promote" -> {
							EntityKey unit = new EntityKey(me, act.a());
							if (state.units().containsKey(unit)) {
								session.submit(player, CommandOp.UNIT_PROMOTE, unit, null, 0, null, "Promoting " + state.units().get(unit).name(), null, option.type(), false);
							}
						}
						case "greatPeople" -> session.submit(player, CommandOp.CHOICE, new EntityKey(me, -1), null, 0, null, "Choosing a Great Person", null, "great:" + option.type(), false);
						default -> {
						}
					}
				}
			}
			case "work" -> {
				// Which land a city's citizens work: a click on a hex around the city, or one of the buttons under its list.
				EntityKey key = new EntityKey(me, act.a());
				CityState city = state.cities().get(key);
				if (city != null) {
					session.submit(player, CommandOp.CITY_WORK, key, new PlotCoord(act.c(), act.d()), 0, null, "The citizens of " + city.name(), null, act.text(), false);
				}
			}
			case "menu" -> {
				// A button on a page of the game's menus, shown on the player's own panel.
				if (!session.actions.run(player, act.text())) {
					tell(player, "That page was out of date. Open it again.");
					refresh(player);
				}
			}
			case "ask2" -> {
				// Lists that are not choices: what the hexes yield, the tree of technologies.
				String kind = act.text();
				if (session.builtin && List.of("yields", "techtree", "worked", "policytree", "queue", "sellable", "battle", "cityBattle").contains(kind)) {
					session.ask(player, kind, act.a(), act.c(), (who, options) -> ServerPlayNetworking.send(who, new Net.Choices(kind, act.a(), options)));
					if (kind.equals("techtree") || kind.equals("policytree")) {
						String about = kind.equals("techtree") ? "about:research" : "about:policy";
						session.ask(player, about, 0, 0, (who, options) -> ServerPlayNetworking.send(who, new Net.Choices(about, 0, options)));
					}
				}
			}
			case "bombard" -> {
				// A city of the player's fires on the hex (c, d).
				EntityKey key = new EntityKey(me, act.a());
				CityState city = state.cities().get(key);
				if (city != null) {
					session.submit(player, CommandOp.CHOICE, key, new PlotCoord(act.c(), act.d()), 0, null, city.name() + " opens fire", null, "bombard", false);
				}
			}
			case "open" -> open(player, state, act);
			default -> LivingWorldMod.LOG.warn("Living World: unknown act '{}' from {}", act.verb(), player.getName().getString());
		}
	}

	/** Pages that are still the game's own menus; they open over the view from above and it comes back when they close. */
	private void open(ServerPlayer player, WorldState state, Net.Act act) {
		int me = state.session().viewer();
		switch (act.text()) {
			case "research" -> session.gameplay.researchMenu(player);
			case "policy" -> session.gameplay.policyMenu(player);
			case "realm" -> session.codex.realm(player);
			case "others" -> session.codex.others(player);
			case "proposals" -> session.codex.proposals(player, true);
			case "settings" -> session.codex.settings(player);
			case "beliefs" -> session.gameplay.beliefMenu(player, true);
			case "vote" -> session.gameplay.voteMenu(player);
			case "spies" -> session.gameplay.spiesMenu(player);
			case "religion" -> session.gameplay.page(player, "religion", 0, "Faith and religion", 0xE8E0C0);
			case "victory" -> session.gameplay.page(player, "victory", 0, "The ways to win", 0xE0B040);
			case "happiness" -> session.gameplay.page(player, "happiness", 0, "Happiness", 0xF0D060);
			case "cityinfo" -> {
				EntityKey key = new EntityKey(me, act.a());
				CityState city = state.cities().get(key);
				if (city != null) {
					session.gameplay.page(player, "cityinfo", key.id(), city.name() + " in detail", 0xE0B040);
				}
			}
			case "waiting" -> session.openWhatCivWaitsFor(player);
			case "city" -> {
				EntityKey key = new EntityKey(act.a(), act.b());
				CityState city = state.cities().get(key);
				if (city != null) {
					session.gameplay.cityMenu(player, key);
				}
			}
			case "production" -> {
				EntityKey key = new EntityKey(act.a(), act.b());
				if (key.owner() == me && state.cities().containsKey(key)) {
					session.gameplay.productionMenu(player, key);
				}
			}
			case "civ" -> session.gameplay.civMenu(player, act.a());
			case "unitmenu" -> {
				EntityKey key = new EntityKey(act.a(), act.b());
				if (state.units().containsKey(key)) {
					session.unitFromAfar(player, key);
				}
			}
			default -> {
			}
		}
	}
}
