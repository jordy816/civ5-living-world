package nl.jordythomassen.livingworld.mc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import nl.jordythomassen.livingworld.link.LinkStatus;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.commands.CommandOp;
import nl.jordythomassen.livingworld.protocol.Records;
import nl.jordythomassen.livingworld.state.Empire;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.Relation;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * The ruler's Codex: the one item a player carries, and the way to everything that is not a
 * place in the world. Using it opens the realm at a glance, with research, land work, travel,
 * the end of the turn and the few settings behind it. Nothing has to be typed.
 */
final class Codex {
	private static final String MARK = "livingworld_codex";
	private static final int MAX_DESTINATIONS = 22;

	private final LivingWorldServer session;

	Codex(LivingWorldServer session) {
		this.session = session;
	}

	/**
	 * A clock underneath, because a clock does nothing when used and no recipe takes one, so the
	 * Codex cannot be eaten, thrown or crafted away. It is shown as a book.
	 */
	static ItemStack item(String civ) {
		ItemStack stack = new ItemStack(Items.CLOCK);
		stack.set(DataComponents.ITEM_MODEL, Identifier.withDefaultNamespace("knowledge_book"));
		stack.set(DataComponents.MAX_STACK_SIZE, 1);
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Codex of " + civ).withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GOLD)));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
			lore("Use it to rule: your realm, research,"),
			lore("travel and the end of your turn."),
			lore("Sneak and use it on the ground"),
			lore("to work that land."))));
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(MARK, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}

	private static Component lore(String text) {
		return Component.literal(text).withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY));
	}

	static boolean is(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(MARK);
	}

	static boolean carries(ServerPlayer player) {
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (is(inventory.getItem(i))) {
				return true;
			}
		}
		return false;
	}

	/** Makes sure the player carries a Codex of the civilization being led; a lost one comes back. True when one was handed out. */
	boolean give(ServerPlayer player) {
		WorldState state = session.state();
		CivInfo civ = state == null ? null : state.civ(state.session().viewer());
		ItemStack fresh = item(civ == null ? "your realm" : civ.name());
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (is(inventory.getItem(i))) {
				// The same book, under the name of whoever is led now (a new game may have changed that).
				inventory.getItem(i).set(DataComponents.CUSTOM_NAME, fresh.get(DataComponents.CUSTOM_NAME));
				return false;
			}
		}
		inventory.add(fresh);
		return true;
	}

	/** The first page: the realm at a glance and the ways on from here. */
	void open(ServerPlayer player) {
		WorldState state = session.state();
		if (state == null || session.model() == null) {
			Menus.Builder waiting = session.menus.menu(player, "Codex", 0xE0B040);
			if (session.builtin) {
				waiting.line(session.link.status() == LinkStatus.NO_GAME ? "The game stopped: " + session.link.statusDetail() + "." : "The game is being set up. One moment.");
			} else {
				waiting.line(session.link.status() == LinkStatus.LIVE ? "Civilization V is at its main menu."
					: "Civilization V is not connected: " + session.link.status().text + ".");
				waiting.line("Start or load a single-player game there, and this world follows.");
			}
			waiting.button("Settings", null, this::settings);
			waiting.open();
			return;
		}
		if (session.builtin) {
			// Offers of other civilizations are counted first, so the page can say they are waiting.
			session.ask(player, "proposals", 0, 0, (who, proposals) -> page(who, proposals.size()));
		} else {
			page(player, 0);
		}
	}

	private void page(ServerPlayer player, int proposals) {
		WorldState state = session.state();
		if (state == null || session.model() == null) {
			return;
		}
		CivInfo civ = state.civ(state.session().viewer());
		Empire empire = state.empire();
		Menus.Builder menu = session.menus.menu(player, "Codex of " + (civ == null ? "your realm" : civ.name()), civ == null ? 0xE0B040 : civ.color()).columns(2);
		menu.line("Turn " + state.turn() + (empire == null ? "" : "   " + empire.yearText() + "   " + Gameplay.words(empire.era().replace("ERA_", "")) + " era"));
		if (empire != null) {
			menu.line("Treasury " + empire.gold() + " gold (" + signed(empire.goldPerTurn()) + " a turn)   Science " + empire.science()
				+ "   Happiness " + empire.happiness() + "   Culture " + signed(empire.culturePerTurn()) + " a turn");
			menu.line(empire.researchName().isEmpty() ? "Your scholars have nothing to research."
				: "Researching " + empire.researchName() + ", " + empire.researchTurns() + " turns to go");
		}
		boolean myTurn = session.link.viewerTurn();
		String waiting = empire == null ? "" : empire.waitingFor();
		int idle = 0;
		for (UnitState unit : state.units().values()) {
			idle += unit.key().owner() == state.session().viewer() && unit.waiting() ? 1 : 0;
		}
		if (myTurn && !waiting.isEmpty() && !(session.builtin && empire.blocking().equals("UNITS"))) {
			// The turn will not end until this is settled: say so before the player tries.
			menu.line(Component.literal(session.builtin && !empire.toDo().isEmpty() ? "Before this turn can end: " + empire.toDo() + "."
				: "Before this turn can end, " + session.civ() + " wants " + waiting + ".").withStyle(ChatFormatting.YELLOW));
		} else if (myTurn && session.builtin && idle > 0) {
			menu.line(Component.literal("It is your turn. " + (idle == 1 ? "One unit has" : idle + " units have") + " nothing to do yet; the turn can end without.")
				.withStyle(ChatFormatting.GREEN));
		} else {
			menu.line(Component.literal(myTurn ? "It is your turn." : "The other players are moving.").withStyle(myTurn ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		}
		if (proposals > 0) {
			menu.line(Component.literal(proposals == 1 ? "Another civilization has put something to you." : proposals + " proposals wait for your answer.")
				.withStyle(ChatFormatting.AQUA));
		}
		if (empire != null && empire.policyDue()) {
			menu.button("Adopt a policy", "Your culture has earned one", session.gameplay::policyMenu);
		}
		if (empire != null && empire.blocking().equals("RELIGION")) {
			menu.button("Choose your beliefs", "Your people are ready to say what they believe", who -> session.gameplay.beliefMenu(who, true));
		}
		if (empire != null && empire.blocking().equals("VOTE")) {
			menu.button("Cast your vote", "The nations choose a world leader", session.gameplay::voteMenu);
		}
		if (proposals > 0) {
			menu.button("Proposals (" + proposals + ")", "What other civilizations ask or offer", who -> proposals(who, true));
		}
		menu.button("End the turn", "The bell in the hall of each of your cities does the same", session::endTurn);
		menu.button("Research", "Choose what your scholars work on. The lectern in your halls does the same.", session.gameplay::researchMenu);
		if (session.builtin) {
			menu.button("Your cities and units", "What each is doing, and a way to reach them", this::realm);
			menu.button("Other civilizations", "Friendship, war and peace, gifts to city-states", this::others);
			menu.button(session.aloft(player) ? "Back to the ground" : "Look from above", "The land as on a map. Point at a unit of yours and use the Codex to select it.",
				session::lookFromAbove);
		}
		menu.button("Work the land here", "Build on the hex you stand in, with what you gathered", session.gameplay::workMenu);
		menu.button("Travel", "To your capital, or to any city you know", this::travel);
		if (empire == null || !empire.policyDue()) {
			menu.button("Policies", empire == null ? null : "Culture " + empire.culture() + " of " + empire.policyCost() + " for the next one", session.gameplay::policyMenu);
		}
		menu.button("Settings", null, this::settings);
		menu.open();
	}

	/** The player's own cities and units, each with what it is doing; choosing one opens its menu. */
	void realm(ServerPlayer player) {
		WorldState state = session.state();
		if (state == null) {
			return;
		}
		int viewer = state.session().viewer();
		CivInfo mine = state.civ(viewer);
		Menus.Builder menu = session.menus.menu(player, "Your cities and units", mine == null ? 0xE0B040 : mine.color()).columns(2);
		List<CityState> cities = new ArrayList<>(state.cities().values());
		cities.removeIf(city -> city.key().owner() != viewer);
		cities.sort(Comparator.comparing((CityState city) -> !city.capital()).thenComparing(CityState::name));
		List<UnitState> units = new ArrayList<>(state.units().values());
		units.removeIf(unit -> unit.key().owner() != viewer);
		units.sort(Comparator.comparing((UnitState unit) -> !unit.waiting()).thenComparing(UnitState::name));
		menu.line(cities.isEmpty() ? "You have no city yet. A Settler founds one: select it and choose Found city."
			: cities.size() + (cities.size() == 1 ? " city, " : " cities, ") + units.size() + (units.size() == 1 ? " unit." : " units."));
		int shown = 0;
		for (CityState city : cities) {
			if (shown++ >= MAX_DESTINATIONS) {
				break;
			}
			// Turning production into science or gold has no end, so no number of turns.
			String doing = (city.flags() & CityState.F_PUPPET) != 0 ? "puppet" : city.productionName().isEmpty() ? "builds nothing"
				: city.productionTurns() <= 0 ? city.productionName()
				: city.productionName() + ", " + city.productionTurns() + (city.productionTurns() == 1 ? " turn" : " turns");
			menu.button(city.name() + "  ·  " + doing, "Size " + city.population() + (city.capital() ? ", your capital" : ""), who -> session.gameplay.cityMenu(who, city.key()));
		}
		shown = 0;
		for (UnitState unit : units) {
			if (shown++ >= 2 * MAX_DESTINATIONS) {
				menu.line("And " + (units.size() - 2 * MAX_DESTINATIONS) + " more units.");
				break;
			}
			String doing = unit.waiting() ? "waits for orders" : unit.fortified() ? "fortified" : unit.moves() == 0 ? "has moved" : "has orders";
			menu.button(unit.name() + "  ·  " + doing, "At " + unit.at() + ", health " + unit.hp(), who -> session.unitFromAfar(who, unit.key()));
		}
		menu.open();
	}

	/** Everyone the player's civilization has met. */
	void others(ServerPlayer player) {
		WorldState state = session.state();
		if (state == null) {
			return;
		}
		CivInfo mine = state.civ(state.session().viewer());
		Menus.Builder menu = session.menus.menu(player, "Other civilizations", mine == null ? 0xE0B040 : mine.color()).columns(2);
		List<Relation> known = new ArrayList<>(state.relations().values());
		known.removeIf(relation -> state.civ(relation.id()) == null || !state.civ(relation.id()).alive());
		known.sort(Comparator.comparing(Relation::minor).thenComparing(relation -> state.civ(relation.id()).name()));
		if (known.isEmpty()) {
			menu.line("Your people have met no one yet. Send a unit out to explore: whoever it finds is listed here.");
		}
		for (Relation relation : known) {
			CivInfo civ = state.civ(relation.id());
			String how = civ.atWarWithViewer() ? "at war" : relation.minor()
				? (relation.standing() == 2 ? "your ally" : relation.standing() == 1 ? "a friend" : "influence " + relation.influence())
				: (relation.word().isEmpty() ? "unknown" : Gameplay.words(relation.word()).toLowerCase(Locale.ROOT));
			menu.button(civ.name() + "  ·  " + how, relation.minor() ? "A city-state" : civ.leader().isEmpty() ? null : "Led by " + civ.leader(),
				who -> session.gameplay.civMenu(who, relation.id()));
		}
		menu.open();
	}

	/** What other civilizations have put to the player. Each is answered on its own; the page comes back while more wait. */
	void proposals(ServerPlayer player, boolean sayWhenNone) {
		session.ask(player, "proposals", 0, 0, (who, options) -> {
			WorldState state = session.state();
			if (state == null || (options.isEmpty() && !sayWhenNone)) {
				return;
			}
			Menus.Builder menu = session.menus.menu(who, "Proposals", 0x40C0E0).columns(2);
			if (options.isEmpty()) {
				menu.line("Nobody is asking anything of you right now.");
			}
			int number = 1;
			for (Records.Option option : options) {
				int n = number++;
				menu.line(n + ".  " + option.name() + ".");
				menu.button(option.group() == 10 ? "Give your word (" + n + ")" : "Accept (" + n + ")", null, p -> answer(p, option, true));
				menu.button(option.group() == 10 ? "Refuse (" + n + ")" : "Decline (" + n + ")", null, p -> answer(p, option, false));
			}
			menu.open();
		});
	}

	private void answer(ServerPlayer player, Records.Option option, boolean accept) {
		WorldState state = session.state();
		session.submit(player, CommandOp.ANSWER, new EntityKey(state.session().viewer(), -1), null, accept ? 1 : 0, null, "Your answer", null, option.type(), false);
		// Asked after the answer, so it lists what is still open.
		proposals(player, false);
	}

	private static String signed(int value) {
		return value >= 0 ? "+" + value : Integer.toString(value);
	}

	/** Every city the player's civilization knows, their own first. */
	void travel(ServerPlayer player) {
		WorldState state = session.state();
		if (state == null || session.model() == null) {
			return;
		}
		int viewer = state.session().viewer();
		CivInfo mine = state.civ(viewer);
		Menus.Builder menu = session.menus.menu(player, "Where to?", mine == null ? 0xE0B040 : mine.color()).columns(2);
		List<CityState> cities = new ArrayList<>(state.cities().values());
		cities.sort(Comparator.comparing((CityState city) -> city.key().owner() != viewer).thenComparing(city -> !city.capital()).thenComparing(CityState::name));
		if (cities.isEmpty()) {
			menu.line(session.builtin ? "Your civilization knows no city yet." : "Your civilization knows no city yet. Found one in Civilization V.");
		}
		int shown = 0;
		for (CityState city : cities) {
			if (shown++ >= MAX_DESTINATIONS) {
				menu.line("And " + (cities.size() - MAX_DESTINATIONS) + " more, further than this page reaches.");
				break;
			}
			boolean own = city.key().owner() == viewer;
			CivInfo owner = state.civ(city.key().owner());
			String label = city.name() + (own ? (city.capital() ? "  (your capital)" : "") : "  ·  " + (owner == null ? "unknown" : owner.name()));
			menu.button(label, null, traveller -> {
				if (session.model() != null) {
					session.teleport(traveller, city.at());
				}
			});
		}
		menu.open();
	}

	void settings(ServerPlayer player) {
		Menus.Builder menu = session.menus.menu(player, "Settings", 0xC0C0C0);
		if (!session.builtin) {
			toggle(menu, "follow", "Civilization V's camera follows you", "Its view moves along as you walk into another hex");
		}
		toggle(menu, "dayIsTurn", "A day is a turn", "The sun is the turn timer: your turn ends at sunset, ten minutes after it began");
		toggle(menu, "techGate", "Crafting follows your technologies", "No iron tools before Iron Working");
		if (!session.builtin) {
			// In the game that runs inside Minecraft the land is found by the units, turn by turn: a ruler on foot finds nothing.
			toggle(menu, "explore", "Walking explores the land", "Stepping into a hex reveals the land around it in Civilization V");
		}
		if (session.builtin) {
			toggle(menu, "governors", "Cities choose their own work", "A city with nothing to build picks something itself, so no turn waits for it");
		}
		menu.button("Put the land around you back", "As " + session.civ() + " has it. Removes what was built or dug nearby.", this::confirmRestore);
		if (session.builtin) {
			menu.button("Your spies", "Who is where, and where each could go", session.gameplay::spiesMenu);
			menu.button("Keep a copy of this game", "As it stands now, under the turn and the time, beside the game itself. To go back to later.", session::keepGame);
			menu.button("Go back to a kept game", "The copies kept in this world. The game being played is set aside, not lost.", this::keptGames);
			menu.button("Start a new game", "Another map, another people. Asks you to confirm first.", this::newGame);
		} else {
			menu.button("The connection", "How the link to Civilization V is doing", this::connection);
		}
		menu.open();
	}

	/** The copies of games kept in this world: a click goes back to one. */
	private void keptGames(ServerPlayer player) {
		Menus.Builder menu = session.menus.menu(player, "Kept games", 0xE0B040);
		List<java.nio.file.Path> kept = session.keptGames();
		if (!session.lobby.keeps(player)) {
			menu.line("Only whoever keeps this world can go back to a kept game.");
		} else if (kept.isEmpty()) {
			menu.line("No game has been kept yet. 'Keep a copy of this game' in the settings makes one.");
		} else {
			menu.line("A click goes back to that game as it stood. The game being played now is set aside in the world's folder, not lost.");
			for (java.nio.file.Path file : kept) {
				String name = file.getFileName().toString().replace(".json", "");
				menu.button(name, "Go back to this game", who -> session.loadKept(who, file));
			}
		}
		menu.button("Back to the settings", null, this::settings);
		menu.open();
	}

	// ---- a new game ----

	/** What a player has picked for the next game; it starts as the game being played was set up. */
	private static final class Draft {
		String nation;
		String mapSize;
		String mapType;
		int rivals;
		int cityStates;
		String difficulty;
		String speed;
	}

	private static final List<String> SIZES = List.of("Tiny", "Small", "Medium", "Large", "Huge");
	private static final List<String> DIFFICULTIES = List.of("Settler", "Chieftain", "Warlord", "Prince", "King", "Emperor", "Immortal", "Deity");
	private final Map<UUID, Draft> drafts = new HashMap<>();

	private Draft draft(ServerPlayer player) {
		return drafts.computeIfAbsent(player.getUUID(), id -> {
			Draft draft = new Draft();
			draft.nation = session.config.nation;
			draft.mapSize = SIZES.contains(session.config.mapSize) ? session.config.mapSize : "Small";
			draft.mapType = nl.jordythomassen.livingworld.unciv.UncivLink.MAP_TYPES.stream()
				.filter(type -> type.replace(" ", "").equalsIgnoreCase(session.config.mapType.replace(" ", ""))).findFirst().orElse("Pangaea");
			draft.rivals = session.config.rivals;
			draft.cityStates = session.config.cityStates;
			draft.difficulty = DIFFICULTIES.contains(session.config.difficulty) ? session.config.difficulty : "Prince";
			draft.speed = nl.jordythomassen.livingworld.unciv.UncivLink.SPEEDS.contains(session.config.speed) ? session.config.speed : "Quick";
			return draft;
		});
	}

	private static <T> T next(List<T> all, T now) {
		return all.get((all.indexOf(now) + 1) % all.size());
	}

	void newGame(ServerPlayer player) {
		Draft draft = draft(player);
		Menus.Builder menu = session.menus.menu(player, "A new game", 0xE0B040).columns(2);
		menu.line("Press a line to change it. The game you are playing now is set aside when the new one begins.");
		menu.button("You lead: " + (draft.nation.isEmpty() ? "a random people" : draft.nation), "Choose your civilization", this::nations);
		menu.button("Map size: " + draft.mapSize, "A larger map takes longer to walk and longer between turns", who -> {
			draft.mapSize = next(SIZES, draft.mapSize);
			newGame(who);
		});
		menu.button("Land: " + draft.mapType, "The shape of the world", who -> {
			draft.mapType = next(nl.jordythomassen.livingworld.unciv.UncivLink.MAP_TYPES, draft.mapType);
			newGame(who);
		});
		menu.button("Other civilizations: " + draft.rivals, "One to seven", who -> {
			draft.rivals = draft.rivals % 7 + 1;
			newGame(who);
		});
		menu.button("City-states: " + draft.cityStates, "None to twelve", who -> {
			draft.cityStates = draft.cityStates >= 12 ? 0 : draft.cityStates + 2 - draft.cityStates % 2;
			newGame(who);
		});
		menu.button("Difficulty: " + draft.difficulty, "Prince is even; above it the others get help", who -> {
			draft.difficulty = next(DIFFICULTIES, draft.difficulty);
			newGame(who);
		});
		menu.button("Length: " + draft.speed, "Quick is some 330 turns, Standard 500, Epic 750, Marathon 1500", who -> {
			draft.speed = next(nl.jordythomassen.livingworld.unciv.UncivLink.SPEEDS, draft.speed);
			newGame(who);
		});
		menu.button("Begin", "Asks you to confirm first", this::confirmNewGame);
		menu.open();
	}

	private void nations(ServerPlayer player) {
		Draft draft = draft(player);
		Menus.Builder menu = session.menus.menu(player, "Whom do you lead?", 0xE0B040).columns(2);
		menu.button("A random people", null, who -> {
			draft.nation = "";
			newGame(who);
		});
		for (String nation : nl.jordythomassen.livingworld.unciv.UncivBoot.nations()) {
			menu.button(nation, null, who -> {
				draft.nation = nation;
				newGame(who);
			});
		}
		menu.open();
	}

	private void confirmNewGame(ServerPlayer player) {
		Draft draft = draft(player);
		WorldState state = session.state();
		Menus.Builder menu = session.menus.menu(player, "Begin a new game?", 0xD03030);
		menu.line((draft.nation.isEmpty() ? "A random people" : draft.nation) + " on a " + draft.mapSize.toLowerCase(Locale.ROOT) + " map (" + draft.mapType + "), with "
			+ draft.rivals + (draft.rivals == 1 ? " other civilization and " : " other civilizations and ") + draft.cityStates + " city-states, at " + draft.difficulty + ", " + draft.speed.toLowerCase(Locale.ROOT) + " length.");
		menu.line(state == null ? "It starts at once." : "The game you are playing (turn " + state.turn() + ") ends here. It is kept in the world's folder as "
			+ "livingworld-civ.previous.json, and what you built in Minecraft on its land is cleared.");
		menu.button("Yes, begin", null, who -> {
			java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
			ModConfig.save(dir, "game.nation", draft.nation);
			ModConfig.save(dir, "game.mapSize", draft.mapSize);
			ModConfig.save(dir, "game.mapType", draft.mapType);
			ModConfig.save(dir, "game.rivals", Integer.toString(draft.rivals));
			ModConfig.save(dir, "game.cityStates", Integer.toString(draft.cityStates));
			ModConfig.save(dir, "game.difficulty", draft.difficulty);
			ModConfig.save(dir, "game.speed", draft.speed);
			session.startNewGame(new nl.jordythomassen.livingworld.unciv.UncivLink.Setup(draft.mapSize, draft.mapType, draft.rivals, draft.cityStates, draft.difficulty,
				draft.nation, 0L, draft.speed));
		});
		menu.open();
	}

	private void toggle(Menus.Builder menu, String rule, String label, String tooltip) {
		boolean on = session.rule(rule);
		menu.button(label + ": " + (on ? "yes" : "no"), tooltip + ". Press to switch.", who -> {
			session.setRule(rule, !on);
			settings(who);
		});
	}

	private void confirmRestore(ServerPlayer player) {
		Menus.Builder menu = session.menus.menu(player, "Put the land back?", 0xD03030);
		menu.line("Everything within three chunks of you goes back to how " + session.civ() + " has it.");
		menu.line("Blocks that were placed or broken there are lost.");
		menu.button("Yes, put it back", null, who -> {
			session.painter.forgetAround(who.chunkPosition().x(), who.chunkPosition().z(), 3);
			LivingWorldServer.say(who, "Putting the land around you back as " + session.civ() + " has it.");
		});
		menu.open();
	}

	private void connection(ServerPlayer player) {
		Menus.Builder menu = session.menus.menu(player, "The connection", 0xC0C0C0);
		for (String line : session.describe().split("\n")) {
			menu.line(line);
		}
		menu.open();
	}
}
