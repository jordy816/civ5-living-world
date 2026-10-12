package nl.jordythomassen.livingworld.mc;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import nl.jordythomassen.livingworld.board.BoardModel;
import nl.jordythomassen.livingworld.commands.CommandOp;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.gameplay.TechGate;
import nl.jordythomassen.livingworld.protocol.Records;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.Empire;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.Legend;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.Relation;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * Ruling and working the land from inside Minecraft: choosing what cities build and what is
 * researched, dealing with city-states and other leaders, exploring by walking, clearing forest
 * by felling it, and building improvements with gathered materials.
 *
 * <p>Every choice shown comes from a list Civilization V itself produced a moment ago, and every
 * action is one of the checked commands. Nothing here decides a rule of the Civ game.
 */
final class Gameplay {
	/** Logs to fell in one hex, within one Civ turn, before the forest is cleared for good. */
	static final int CHOP_LOGS = 24;
	/** Improvements one player may build per Civ turn. */
	static final int WORKS_PER_TURN = 3;

	/** What an improvement costs in things gathered in Minecraft. */
	record Cost(Predicate<ItemStack> item, int count, String words) {
	}

	private final LivingWorldServer session;
	private final Map<PlotCoord, Integer> felled = new HashMap<>();
	private final Map<UUID, Integer> works = new HashMap<>();
	private final Map<UUID, PlotCoord> lastPlot = new HashMap<>();

	Gameplay(LivingWorldServer session) {
		this.session = session;
	}

	/** A new Civ turn: forests have grown back, and everyone may work the land again. */
	void newTurn() {
		felled.clear();
		works.clear();
	}

	void forget(ServerPlayer player) {
		lastPlot.remove(player.getUUID());
		works.remove(player.getUUID());
	}

	static String words(String key) {
		return TechGate.words(key);
	}

	private static EntityKey self(WorldState state) {
		return new EntityKey(state.session().viewer(), -1);
	}

	// ---- cities, city-states and leaders ----

	void cityMenu(ServerPlayer player, EntityKey key) {
		WorldState state = session.state();
		CityState city = state.cities().get(key);
		if (city == null) {
			return;
		}
		CivInfo civ = state.civ(key.owner());
		String civName = civ == null ? "Unknown" : civ.name();
		boolean mine = key.owner() == state.session().viewer();
		Relation relation = state.relations().get(key.owner());
		Menus.Builder menu = session.menus.menu(player, city.name() + (city.capital() ? " · capital of " : " · city of ") + civName, civ == null ? 0xFFFFFF : civ.color());
		// Few lines, so that what can be done here is on the page without scrolling, also on a small screen.
		menu.line("Population " + city.population() + "   Defence " + city.strength() + "   Health " + city.hp() + "/" + city.maxHp()
			+ (city.details() ? "   Food " + city.food() + "/" + city.foodNeeded() : ""));
		if (city.details()) {
			menu.line(city.productionName().isEmpty() ? "Building nothing at the moment."
				: city.productionTurns() <= 0 ? "Its work goes to " + city.productionName().toLowerCase(Locale.ROOT) + "."
				: "Now building " + city.productionName() + ", " + city.productionTurns() + (city.productionTurns() == 1 ? " turn" : " turns") + " left");
			if (city.buildings().size() + city.wonders().size() > 0) {
				menu.line("Has: " + listed(city.buildings(), 6) + (city.wonders().isEmpty() ? "" : "   Wonders: " + listed(city.wonders(), 4)));
			}
		}
		boolean puppet = (city.flags() & CityState.F_PUPPET) != 0;
		boolean razing = (city.flags() & CityState.F_RAZING) != 0;
		if (mine) {
			Empire empire = state.empire();
			menu.columns(2);
			if (city.capital() && empire != null && !session.builtin) {
				menu.line("Treasury " + empire.gold() + " gold (" + signed(empire.goldPerTurn()) + " a turn)   Science " + empire.science()
					+ "   Happiness " + empire.happiness() + "   Culture " + signed(empire.culturePerTurn()) + " a turn");
				menu.line(empire.researchName().isEmpty() ? "Your scholars have nothing to research."
					: "Researching " + empire.researchName() + ", " + empire.researchTurns() + " turns to go");
			}
			if (puppet) {
				menu.line("A puppet city: it pays into your treasury and decides for itself what to build.");
			}
			if (razing) {
				menu.line(Component.literal("It is being burned down, a citizen a turn.").withStyle(ChatFormatting.RED));
			}
			if (session.builtin) {
				menu.button(city.name() + " in detail", "What it makes, when it grows, what stands in it; its line of builds, faith purchases and selling", p -> page(p, "cityinfo", key.id(),
					city.name() + " in detail", civ == null ? 0xE0B040 : civ.color()));
			}
			if (!puppet) {
				menu.button("What " + city.name() + " builds", "Choose from what this city can build right now", p -> productionMenu(p, key));
				if (session.builtin) {
					Empire treasury = state.empire();
					menu.button("Buy with gold", "Pay from the treasury (" + (treasury == null ? "?" : treasury.gold()) + " gold) and have it at once", p -> buyMenu(p, key));
				}
			}
			if (session.builtin) {
				menu.button("Deliver what you carry", "Wood and ore: Production. Wheat and fruit: Food. Gold and gems: Gold. " + city.name()
					+ " takes no more a turn than it makes itself", p -> session.deliver(p, key));
			} else {
				menu.button("Deliver logs", "1 log = " + LivingWorldServer.HAMMERS_PER_LOG + " hammers, up to " + LivingWorldServer.MAX_LOGS
					+ ". Uses logs from your inventory and adds production in Civilization V", p -> session.deliver(p, key));
			}
			if (city.capital()) {
				menu.button("Choose what to research", "From the technologies open to your scholars right now", this::researchMenu);
			}
			if (session.builtin && (puppet || razing || !city.capital())) {
				menu.button("What becomes of " + city.name(), puppet ? "Annex it, give it back to its founder, raze it, or leave it a puppet" : razing ? "Spare it, or let it burn"
					: "Give it back to its founder, or raze it", p -> fateMenu(p, key, false));
			}
		} else if (civ != null && civ.atWarWithViewer()) {
			menu.line(Component.literal("You are at war with " + civName + ". The governor has you watched.").withStyle(ChatFormatting.RED));
			if (session.builtin) {
				menu.button("Offer peace", "They agree once the war has gone on long enough for them", p -> diplomacy(p, key.owner(), "peace"));
			}
		} else if (relation != null && civ != null) {
			menu.kind("leader", key.owner());
			dealings(menu, state, key.owner());
		} else {
			menu.line("\"Welcome, traveller. You have no say in how this city is run.\"");
		}
		menu.open();
	}

	/** What can be done with another player: the same lines and buttons at their city gate and in the Codex. */
	private void dealings(Menus.Builder menu, WorldState state, int other) {
		CivInfo civ = state.civ(other);
		Relation relation = state.relations().get(other);
		if (civ == null || relation == null) {
			menu.line("Your people have not met them.");
			return;
		}
		String civName = civ.name();
		if (civ.atWarWithViewer()) {
			menu.line(Component.literal("You are at war with " + civName + ".").withStyle(ChatFormatting.RED));
			if (session.builtin) {
				menu.button("Offer peace", "They agree once the war has gone on long enough for them", p -> diplomacy(p, other, "peace"));
				if (!civ.minor()) {
					menu.button("Negotiate a peace", "A treaty with something added to it, for them or for you", p -> tradeMenu(p, other));
				}
			}
			return;
		}
		if (civ.minor()) {
			String standing = relation.standing() == 2 ? "your ally" : relation.standing() == 1 ? "a friend" : "neutral";
			menu.line("A " + words(relation.word()).toLowerCase(Locale.ROOT) + " city-state. Influence " + relation.influence() + ": " + standing + ".");
			if (!relation.quests().isEmpty()) {
				menu.line("They ask you to: " + String.join("; ", relation.quests().stream().map(Gameplay::quest).toList()) + ".");
				if (session.builtin) {
					menu.button("What they ask, in full", "What exactly, how long it stands, what it is worth", p -> page(p, "quests", other, "What " + civName + " asks of you", civ.color()));
				}
			}
			Empire empire = state.empire();
			menu.line("Your treasury holds " + (empire == null ? "?" : empire.gold()) + " gold.");
			for (int amount : new int[] { relation.giftSmall(), relation.giftMedium(), relation.giftLarge() }) {
				if (amount > 0) {
					menu.button("Gift " + amount + " gold", "A gift from your treasury raises their influence", p -> gift(p, other, amount));
				}
			}
			if (session.builtin) {
				menu.button("Pledge to protect them", "Raises their influence a little every turn; breaking the pledge costs it", p -> diplomacy(p, other, "protect"));
				menu.button("Declare war on " + civName, "Asks you to confirm first", p -> warMenu(p, other));
			}
			return;
		}
		String leader = civ.leader().isEmpty() ? civName : civ.leader();
		boolean friends = (relation.standing() & Relation.F_FRIENDSHIP) != 0;
		boolean denounced = (relation.standing() & Relation.F_DENOUNCED) != 0;
		menu.line("\"" + greeting(relation.word()) + "\"");
		menu.line(leader + " seems " + (relation.word().isEmpty() ? "hard to read" : words(relation.word()).toLowerCase(Locale.ROOT)) + " towards you."
			+ (friends ? " You have declared friendship." : "") + (denounced ? " You have denounced them." : ""));
		if (session.builtin) {
			menu.button("Propose a deal", "Gold, resources, open borders, embassies, research and defence, as they will have it", p -> tradeMenu(p, other));
			if (!friends) {
				menu.button("Declare friendship", "They agree only if they think well of you", p -> diplomacy(p, other, "friendship"));
			}
			if (!denounced) {
				menu.button("Denounce " + civName, "Tells the world they are not to be trusted. They will remember.", p -> diplomacy(p, other, "denounce"));
			}
			if (session.builtin) {
				menu.button("Ask them: no cities near us", "A demand. They agree when they think well enough of you; breaking their word costs them with everybody", p -> diplomacy(p, other, "demandSettle"));
				menu.button("Ask them: keep your religion out", "A demand not to spread their religion to your cities", p -> diplomacy(p, other, "demandReligion"));
				menu.button("Ask them: no spying on us", "A demand to keep their spies out of your cities", p -> diplomacy(p, other, "demandSpies"));
			}
		}
		if (relation.canDeclareWar()) {
			menu.button("Declare war on " + civName, "Asks you to confirm first", p -> warMenu(p, other));
		}
	}

	/** Dealing with another civilization or city-state from the Codex, without standing at its gate. */
	void civMenu(ServerPlayer player, int other) {
		WorldState state = session.state();
		CivInfo civ = state == null ? null : state.civ(other);
		if (civ == null) {
			return;
		}
		Menus.Builder menu = session.menus.menu(player, civ.name() + (civ.minor() || civ.leader().isEmpty() ? "" : " · " + civ.leader()), civ.color()).kind("leader", other);
		dealings(menu, state, other);
		menu.open();
	}

	void diplomacy(ServerPlayer player, int other, String what) {
		WorldState state = session.state();
		CivInfo civ = state.civ(other);
		String name = civ == null ? "them" : civ.name();
		session.submit(player, CommandOp.DIPLOMACY, new EntityKey(state.session().viewer(), other), null, 0, null, switch (what) {
			case "peace" -> "Your offer of peace to " + name;
			case "friendship" -> "Your offer of friendship to " + name;
			case "denounce" -> "Denouncing " + name;
			case "demandSettle", "demandReligion", "demandSpies", "demandAttack" -> "Your demand of " + name;
			default -> "Your pledge to " + name;
		}, null, what, false);
	}

	/** What the city could have at once for gold, with the price of each. */
	void buyMenu(ServerPlayer player, EntityKey key) {
		session.ask(player, "buy", key.id(), 0, (p, options) -> {
			WorldState state = session.state();
			CityState city = state == null ? null : state.cities().get(key);
			if (city == null) {
				return;
			}
			CivInfo civ = state.civ(key.owner());
			Empire empire = state.empire();
			int gold = empire == null ? 0 : empire.gold();
			Menus.Builder menu = session.menus.menu(p, "What should " + city.name() + " buy?", civ == null ? 0xFFFFFF : civ.color()).columns(2);
			menu.line("Your treasury holds " + gold + " gold.");
			List<Records.Option> affordable = options.stream().filter(o -> o.turns() > 0 && o.turns() <= gold).toList();
			if (affordable.isEmpty()) {
				int cheapest = options.stream().filter(o -> o.turns() > 0).mapToInt(Records.Option::turns).min().orElse(0);
				menu.line(cheapest == 0 ? "Nothing here can be bought with gold." : "That is not enough for anything here; the cheapest costs " + cheapest + " gold.");
			}
			for (Records.Option option : affordable) {
				menu.button(option.name() + "  ·  " + option.turns() + " gold", null, who -> session.submit(who, CommandOp.CITY_BUY, key, null, 0, null,
					"Buying " + option.name() + " in " + city.name(), null, option.type(), false));
			}
			menu.open();
		});
	}

	/**
	 * A page the game itself tells: faith and religion, the ways to win, happiness, a city in detail.
	 * Each line comes with how it reads (a heading, plain, an aside, good news, bad news).
	 */
	void page(ServerPlayer player, String kind, int a, String title, int color) {
		session.ask(player, kind, a, 0, (who, lines) -> {
			Menus.Builder menu = session.menus.menu(who, title, color);
			if (lines.isEmpty()) {
				menu.line("There is nothing to tell yet.");
			}
			for (Records.Option line : lines) {
				switch (line.group()) {
					case 0 -> menu.line(Component.literal(line.name()).withStyle(ChatFormatting.GOLD));
					case 2 -> menu.line(Component.literal(line.name()).withStyle(ChatFormatting.GRAY));
					case 3 -> menu.line(Component.literal(line.name()).withStyle(ChatFormatting.GREEN));
					case 4 -> menu.line(Component.literal(line.name()).withStyle(ChatFormatting.RED));
					default -> menu.line(line.name());
				}
			}
			if (kind.equals("cityinfo")) {
				WorldState state = session.state();
				EntityKey key = state == null ? null : new EntityKey(state.session().viewer(), a);
				if (key != null && state.cities().containsKey(key)) {
					menu.columns(2);
					menu.button("Line up what it builds next", "Something to start on by itself when what it builds now is done", p -> queueMenu(p, key));
					menu.button("Buy with faith", "Missionaries, prophets and the buildings of your religion", p -> faithMenu(p, key));
					menu.button("Sell a building", "One a turn, for a part of what it cost", p -> sellMenu(p, key));
					menu.button("Back to the city", null, p -> cityMenu(p, key));
				}
			} else if (kind.equals("religion")) {
				menu.button("Choose a belief", "When your people have faith enough, or a Great Prophet has founded a religion", p -> beliefMenu(p, true));
			}
			menu.open();
		});
	}

	/** What the game says of each thing in one of its lists, by the list's own keys. */
	private void about(ServerPlayer player, String kind, int a, java.util.function.BiConsumer<ServerPlayer, Map<String, String>> then) {
		session.ask(player, "about:" + kind, a, 0, (who, about) -> {
			Map<String, String> texts = new HashMap<>();
			for (Records.Option one : about) {
				texts.put(one.type(), one.name().replace("\n", "; "));
			}
			then.accept(who, texts);
		});
	}

	/** What a city has lined up behind what it builds now, and what could be added. */
	void queueMenu(ServerPlayer player, EntityKey key) {
		session.ask(player, "queue", key.id(), 0, (asker, queue) -> session.ask(asker, "build", key.id(), 0, (again, options) -> about(again, "build", key.id(), (p, texts) -> {
			WorldState state = session.state();
			CityState city = state == null ? null : state.cities().get(key);
			if (city == null) {
				return;
			}
			CivInfo civ = state.civ(key.owner());
			Menus.Builder menu = session.menus.menu(p, "What " + city.name() + " builds next", civ == null ? 0xFFFFFF : civ.color()).columns(2);
			if (queue.isEmpty()) {
				menu.line("It builds nothing now. Choose what it builds first.");
			}
			for (Records.Option waiting : queue) {
				String turns = waiting.turns() > 0 ? " (" + waiting.turns() + (waiting.turns() == 1 ? " turn" : " turns") + ")" : "";
				if (waiting.id() == 0) {
					menu.line("Now: " + words(waiting.name()) + turns);
				} else {
					menu.line(Component.literal("Then: " + words(waiting.name()) + turns).withStyle(ChatFormatting.GRAY));
				}
			}
			for (Records.Option waiting : queue) {
				if (waiting.id() > 0) {
					menu.button("Take out: " + words(waiting.name()), "It leaves the line", who -> {
						session.submit(who, CommandOp.CITY_BUILD, key, null, 0, null, city.name(), null, "unqueue:" + waiting.id(), false);
						session.later(() -> session.as(who, () -> queueMenu(who, key)));
					});
				}
			}
			for (Records.Option option : options) {
				if (option.group() == 3 || queue.stream().anyMatch(q -> q.name().equals(option.name()))) {
					continue;
				}
				menu.button("Add: " + words(option.name()) + (option.turns() > 0 ? "  ·  " + option.turns() + " turns" : ""), texts.get(option.type()), who -> {
					session.submit(who, CommandOp.CITY_BUILD, key, null, 0, null, city.name(), null, "queue:" + option.type(), false);
					session.later(() -> session.as(who, () -> queueMenu(who, key)));
				});
			}
			menu.open();
		})));
	}

	/** What a city can buy with faith. */
	void faithMenu(ServerPlayer player, EntityKey key) {
		session.ask(player, "faith", key.id(), 0, (asker, options) -> about(asker, "faith", key.id(), (p, texts) -> {
			WorldState state = session.state();
			CityState city = state == null ? null : state.cities().get(key);
			if (city == null) {
				return;
			}
			CivInfo civ = state.civ(key.owner());
			Menus.Builder menu = session.menus.menu(p, "What " + city.name() + " buys with faith", civ == null ? 0xFFFFFF : civ.color()).columns(2);
			if (options.isEmpty()) {
				menu.line("Nothing here can be bought with faith yet. Missionaries, Inquisitors and Great Prophets come to a city whose people follow a founded religion; "
					+ "some beliefs add buildings.");
			} else {
				menu.line("Faith buys what work and gold cannot. The price is in faith; the top of your screen says how much your people have.");
			}
			for (Records.Option option : options) {
				menu.button(words(option.name()) + "  ·  " + option.turns() + " faith", texts.get(option.type()), who -> session.submit(who, CommandOp.CITY_BUY, key, null, 0, null,
					"Buying " + option.name() + " in " + city.name(), null, "faith:" + option.type(), false));
			}
			menu.button("Back to the city", null, who -> cityMenu(who, key));
			menu.open();
		}));
	}

	/** The buildings a city could sell. */
	void sellMenu(ServerPlayer player, EntityKey key) {
		session.ask(player, "sellable", key.id(), 0, (p, options) -> {
			WorldState state = session.state();
			CityState city = state == null ? null : state.cities().get(key);
			if (city == null) {
				return;
			}
			CivInfo civ = state.civ(key.owner());
			Menus.Builder menu = session.menus.menu(p, "Sell a building of " + city.name(), civ == null ? 0xFFFFFF : civ.color()).columns(2);
			menu.line(options.isEmpty() ? "Nothing can be sold here this turn (a city sells one building a turn; wonders and the palace never)."
				: "A city sells one building a turn. What it gave is gone with it; its upkeep too.");
			for (Records.Option option : options) {
				menu.button(words(option.name()) + "  ·  +" + option.turns() + " gold", "Sold for good", who -> session.submit(who, CommandOp.CHOICE, key, null, 0, null,
					"Selling the " + option.name() + " of " + city.name(), null, "sell:" + option.type(), false));
			}
			menu.button("Back to the city", null, who -> cityMenu(who, key));
			menu.open();
		});
	}

	/** The player's spies: who is where and at what, and where each could go. */
	void spiesMenu(ServerPlayer player) {
		session.ask(player, "spies", 0, 0, (p, spies) -> {
			WorldState state = session.state();
			if (state == null) {
				return;
			}
			Menus.Builder menu = session.menus.menu(p, "Your spies", 0x9A7FD0).columns(2);
			if (spies.isEmpty()) {
				menu.line("You have no spies. The first comes with the Renaissance, another with every age after it (in a game set up with spies).");
			} else {
				menu.line("A spy in a city of another civilization steals what they know; in a city-state it rigs the elections for your influence; in a city of your own it guards "
					+ "against theirs. Moving and settling in take a few turns.");
			}
			for (Records.Option spy : spies) {
				menu.line(spy.type().equals("dead") ? Component.literal(spy.name()).withStyle(ChatFormatting.RED) : Component.literal(spy.name()));
			}
			EntityKey self = new EntityKey(state.session().viewer(), -1);
			for (Records.Option spy : spies) {
				if (spy.type().equals("dead")) {
					continue;
				}
				String who = spy.name().contains(",") ? spy.name().substring(0, spy.name().indexOf(',')) : spy.name();
				menu.button("Send " + who + "...", "Choose the city it goes to", q -> spyTargets(q, spy.id(), who));
				if (spy.turns() == 1) {
					menu.button(who + ": stage a coup", "Overthrow the city-state's ally in your favour; a failure costs the spy's life", q -> {
						session.submit(q, CommandOp.CHOICE, self, null, 0, null, who, null, "spy:" + spy.id() + ":coup", false);
						session.later(() -> session.as(q, () -> spiesMenu(q)));
					});
				}
			}
			menu.open();
		});
	}

	private void spyTargets(ServerPlayer player, int index, String who) {
		session.ask(player, "spyTargets", index, 0, (p, cities) -> {
			WorldState state = session.state();
			if (state == null) {
				return;
			}
			Menus.Builder menu = session.menus.menu(p, "Where does " + who + " go?", 0x9A7FD0).columns(2);
			if (cities.isEmpty()) {
				menu.line("There is no city it could go to. Explore, and meet others.");
			}
			EntityKey self = new EntityKey(state.session().viewer(), -1);
			for (Records.Option city : cities) {
				int owner;
				try {
					owner = Integer.parseInt(city.type());
				} catch (NumberFormatException e) {
					continue;
				}
				String label = city.name().contains(" (") ? city.name().substring(0, city.name().indexOf(" (")) : city.name();
				menu.button(label, city.name(), q -> {
					session.submit(q, CommandOp.CHOICE, new EntityKey(owner, city.id()), null, 0, null, who, null, "spy:" + index + ":move", false);
					session.later(() -> session.as(q, () -> spiesMenu(q)));
				});
			}
			menu.button("Back to the hideout", "It waits there, safe, for other orders", q -> {
				session.submit(q, CommandOp.CHOICE, self, null, 0, null, who, null, "spy:" + index + ":home", false);
				session.later(() -> session.as(q, () -> spiesMenu(q)));
			});
			menu.open();
		});
	}

	/** A Great Person given freely (by a wonder, a policy) is the player's to name. */
	void greatMenu(ServerPlayer player) {
		session.ask(player, "greatPeople", 0, 0, (asker, options) -> about(asker, "greatPeople", 0, (p, texts) -> {
			WorldState state = session.state();
			if (state == null) {
				return;
			}
			Menus.Builder menu = session.menus.menu(p, "A Great Person of your choosing", 0xE0B040).columns(2);
			menu.line(options.isEmpty() ? "No Great Person is yours for the choosing now." : "Your people are owed a Great Person. Who shall it be? They come to your capital.");
			for (Records.Option option : options) {
				menu.button(words(option.name()), texts.get(option.type()), who -> session.submit(who, CommandOp.CHOICE, new EntityKey(state.session().viewer(), -1), null, 0, null,
					"Choosing a Great Person", null, "great:" + option.type(), false));
			}
			menu.open();
		}));
	}

	/** A label and its explanation from one line of the game's ("Annex it: you govern it yourself"). */
	private static String[] labelled(String text) {
		int colon = text.indexOf(": ");
		return colon < 0 ? new String[] { text, null } : new String[] { text.substring(0, colon), text.substring(colon + 2) };
	}

	/**
	 * What becomes of one of the player's cities: annexed, given back to its founder, razed,
	 * spared, or left a puppet. {@code fallen}: it was taken a moment ago, and this is the question
	 * a conqueror is asked.
	 */
	void fateMenu(ServerPlayer player, EntityKey key, boolean fallen) {
		session.ask(player, "fate", key.id(), 0, (p, options) -> {
			WorldState state = session.state();
			CityState city = state == null ? null : state.cities().get(key);
			if (city == null) {
				return;
			}
			boolean heldAsPuppet = (city.flags() & CityState.F_PUPPET) != 0;
			boolean burning = (city.flags() & CityState.F_RAZING) != 0;
			Menus.Builder menu = session.menus.menu(p, fallen ? city.name() + " is yours" : "What becomes of " + city.name() + "?", 0xD03030);
			if (fallen) {
				menu.line(city.name() + " is yours, " + city.population() + " citizens strong. " + (burning ? "It is small, and has been set to burn."
					: heldAsPuppet ? "For now it is held as a puppet." : "You govern it yourself.") + " What becomes of it?");
			}
			if (options.isEmpty()) {
				menu.line("Nothing is to be decided about " + city.name() + ".");
			}
			for (Records.Option option : options) {
				String[] text = labelled(option.name());
				menu.button(text[0], text[1], who -> fate(who, key, option.type()));
			}
			menu.open();
		});
	}

	void fate(ServerPlayer player, EntityKey key, String what) {
		CityState city = session.state().cities().get(key);
		session.submit(player, CommandOp.CITY_FATE, key, null, 0, null, "Your word on " + (city == null ? "the city" : city.name()), null, what, false);
	}

	/** What the player's people believe. The game says what is to be chosen and from what; the player picks, one belief at a time. */
	void beliefMenu(ServerPlayer player, boolean sayWhenNone) {
		session.ask(player, "beliefs", 0, 0, (p, options) -> {
			WorldState state = session.state();
			if (state == null || (options.isEmpty() && !sayWhenNone)) {
				return;
			}
			CivInfo civ = state.civ(state.session().viewer());
			Menus.Builder menu = session.menus.menu(p, "What your people believe", civ == null ? 0xFFFFFF : civ.color()).columns(2);
			if (options.isEmpty()) {
				menu.line("Nothing is to be chosen in matters of faith right now.");
			} else {
				menu.line(options.get(0).name());
				menu.line("Point at a belief to read what it gives.");
			}
			for (Records.Option option : options.subList(Math.min(1, options.size()), options.size())) {
				String[] text = labelled(option.name());
				menu.button(text[0], text[1], who -> {
					session.submit(who, CommandOp.BELIEF, self(session.state()), null, 0, null, "Your people's belief", null, option.type(), false);
					// Asked after the choice: a religion takes more than one belief, and the next is offered at once.
					beliefMenu(who, false);
				});
			}
			menu.open();
		});
	}

	/** The vote for a world leader, when one is called. */
	void voteMenu(ServerPlayer player) {
		session.ask(player, "vote", 0, 0, (p, options) -> {
			WorldState state = session.state();
			if (state == null) {
				return;
			}
			Menus.Builder menu = session.menus.menu(p, "A vote for a world leader", 0x40C0E0).columns(2);
			if (options.isEmpty()) {
				menu.line("No vote is being held.");
			} else {
				menu.line("The nations vote for a leader of the world. Whoever gathers enough votes wins the game. You cannot vote for yourself.");
			}
			for (Records.Option option : options) {
				menu.button(option.name(), null, who -> session.submit(who, CommandOp.DIPLOMACY, new EntityKey(session.state().session().viewer(), option.id()), null, 0, null,
					"Your vote", null, option.type(), false));
			}
			menu.open();
		});
	}

	/** The table at which the player puts a deal together with another civilization, and what that civilization thinks of it. */
	void tradeMenu(ServerPlayer player, int other) {
		session.ask(player, "tradeTable", other, 0, (p, table) -> {
			WorldState state = session.state();
			CivInfo civ = state == null ? null : state.civ(other);
			if (civ == null || table.isEmpty()) {
				return;
			}
			List<String> give = table.stream().filter(o -> o.group() == 14).map(Records.Option::name).toList();
			List<String> get = table.stream().filter(o -> o.group() == 15).map(Records.Option::name).toList();
			Records.Option verdict = table.get(table.size() - 1);
			Menus.Builder menu = session.menus.menu(p, "A deal with " + civ.name(), civ.color()).columns(2).kind("deal", other).table(give, get);
			menu.line("You give: " + (give.isEmpty() ? "nothing" : String.join(", ", give)));
			menu.line("You get: " + (get.isEmpty() ? "nothing" : String.join(", ", get)));
			menu.line(Component.literal(verdict.name()).withStyle(verdict.turns() == 1 ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
			menu.button("Offer something", "Add to what you give", who -> tradeList(who, other, true));
			menu.button("Ask for something", "Add to what you get", who -> tradeList(who, other, false));
			if (!give.isEmpty() || !get.isEmpty()) {
				menu.button("Propose this deal", verdict.turns() == 1 ? "They would agree" : "They would refuse it as it stands", who -> trade(who, other, "propose", false));
				menu.button("Start over", "Clears the table", who -> trade(who, other, "clear", true));
			}
			menu.open();
		});
	}

	private void tradeList(ServerPlayer player, int other, boolean ours) {
		session.ask(player, ours ? "tradeGive" : "tradeTake", other, 0, (p, options) -> {
			WorldState state = session.state();
			CivInfo civ = state == null ? null : state.civ(other);
			if (civ == null) {
				return;
			}
			Menus.Builder menu = session.menus.menu(p, ours ? "What do you offer " + civ.name() + "?" : "What do you ask of " + civ.name() + "?", civ.color()).columns(2);
			if (options.isEmpty()) {
				menu.line(ours ? "You have nothing they could take in a deal right now." : "They have nothing to give in a deal right now.");
			}
			for (Records.Option option : options) {
				menu.button(option.name(), null, who -> trade(who, other, option.type(), true));
			}
			menu.button("Back to the table", null, who -> tradeMenu(who, other));
			menu.open();
		});
	}

	private void trade(ServerPlayer player, int other, String what, boolean back) {
		WorldState state = session.state();
		CivInfo civ = state.civ(other);
		session.submit(player, CommandOp.TRADE, new EntityKey(state.session().viewer(), other), null, 0, null, "Your deal with " + (civ == null ? "them" : civ.name()), null,
			what, false);
		if (back) {
			// Asked after the step, so the table shows it.
			tradeMenu(player, other);
		}
	}

	private static String signed(int value) {
		return value >= 0 ? "+" + value : Integer.toString(value);
	}

	private static String listed(List<String> keys, int most) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < keys.size() && i < most; i++) {
			out.append(i == 0 ? "" : ", ").append(words(keys.get(i)));
		}
		return keys.size() > most ? out + " and " + (keys.size() - most) + " more" : out.toString();
	}

	/** A city-state's request in plain words. */
	static String quest(String key) {
		return switch (key) {
			case "KILL_CAMP" -> "clear a barbarian camp";
			case "ROUTE" -> "connect a road to them";
			case "CONNECT_RESOURCE" -> "connect a resource they want";
			case "CONSTRUCT_WONDER" -> "build a wonder";
			case "GREAT_PERSON" -> "bring forth a great person";
			case "KILL_CITY_STATE" -> "conquer a rival city-state";
			case "FIND_PLAYER" -> "find another civilization";
			case "FIND_NATURAL_WONDER" -> "find a natural wonder";
			case "GIVE_GOLD" -> "help them with gold";
			case "PLEDGE_TO_PROTECT" -> "pledge to protect them";
			case "BULLY_CITY_STATE" -> "bully a rival city-state";
			case "DENOUNCE_MAJOR" -> "denounce a civilization that wronged them";
			case "SPREAD_RELIGION" -> "spread your religion to them";
			case "TRADE_ROUTE" -> "send them a trade route";
			default -> words(key).toLowerCase(Locale.ROOT);
		};
	}

	private static String greeting(String attitude) {
		return switch (attitude) {
			case "FRIENDLY" -> "It is good to see a friend at my court.";
			case "HOSTILE" -> "Say what you came to say, and leave.";
			case "GUARDED" -> "We are watching your borders closely.";
			case "AFRAID" -> "We want no quarrel with you.";
			case "WAR" -> "My generals speak of nothing but you.";
			case "DECEPTIVE" -> "How delightful that you came all this way.";
			default -> "You stand before the throne. Speak.";
		};
	}

	void productionMenu(ServerPlayer player, EntityKey key) {
		session.ask(player, "build", key.id(), 0, (p, options) -> {
			WorldState state = session.state();
			CityState city = state == null ? null : state.cities().get(key);
			if (city == null) {
				return;
			}
			CivInfo civ = state.civ(key.owner());
			Menus.Builder menu = session.menus.menu(p, "What should " + city.name() + " build?", civ == null ? 0xFFFFFF : civ.color()).columns(2);
			if (options.isEmpty()) {
				menu.line("This city can build nothing right now.");
			}
			for (Records.Option option : options) {
				String kind = option.group() == 0 ? "Unit" : option.group() == 1 ? "Building" : option.group() == 2 ? "Project" : "Ongoing";
				menu.button(option.name() + (option.turns() > 0 ? "  (" + option.turns() + " turns)" : ""), kind, who -> chooseProduction(who, key, option));
			}
			menu.open();
		});
	}

	void chooseProduction(ServerPlayer player, EntityKey key, Records.Option option) {
		CityState city = session.state().cities().get(key);
		session.submit(player, CommandOp.CITY_BUILD, key, new PlotCoord(option.group(), option.id()), 0, null,
			(city == null ? "The city" : city.name()) + " building " + option.name(), null, option.type(), false);
	}

	void researchMenu(ServerPlayer player) {
		session.ask(player, "research", 0, 0, (p, options) -> {
			WorldState state = session.state();
			if (state == null) {
				return;
			}
			CivInfo civ = state.civ(state.session().viewer());
			Menus.Builder menu = session.menus.menu(p, "What should your scholars research?", civ == null ? 0xFFFFFF : civ.color()).columns(2);
			if (options.isEmpty()) {
				menu.line("There is nothing to research right now.");
			}
			for (Records.Option option : options) {
				String key = option.type().startsWith("TECH_") ? option.type().substring(5) : option.type();
				String unlocks = TechGate.unlocks(key);
				menu.button(option.name() + "  (" + option.turns() + " turns)", unlocks == null ? null : "In Minecraft it lets you craft " + unlocks,
					who -> chooseResearch(who, option));
			}
			menu.open();
		});
	}

	void chooseResearch(ServerPlayer player, Records.Option option) {
		session.submit(player, CommandOp.RESEARCH, self(session.state()), new PlotCoord(0, option.id()), 0, null, "Researching " + option.name(), null,
			option.type(), false);
	}

	/** What Civilization V lets the player adopt right now: a branch of the policy tree to open, or a policy in an open one. */
	void policyMenu(ServerPlayer player) {
		session.ask(player, "policy", 0, 0, (p, options) -> {
			WorldState state = session.state();
			if (state == null) {
				return;
			}
			CivInfo civ = state.civ(state.session().viewer());
			Empire empire = state.empire();
			Menus.Builder menu = session.menus.menu(p, "Which policy should your people follow?", civ == null ? 0xFFFFFF : civ.color()).columns(2);
			if (options.isEmpty()) {
				menu.line("Nothing can be adopted right now." + (empire == null ? "" : " Culture " + empire.culture() + " of the " + empire.policyCost()
					+ " the next policy takes (" + signed(empire.culturePerTurn()) + " a turn)."));
			}
			for (Records.Option option : options) {
				menu.button(option.group() == 5 ? "Open " + option.name() : option.name(), option.group() == 5 ? "A new branch of the policy tree" : "A policy",
					who -> choosePolicy(who, option));
			}
			menu.open();
		});
	}

	void choosePolicy(ServerPlayer player, Records.Option option) {
		session.submit(player, CommandOp.POLICY, self(session.state()), new PlotCoord(option.group(), option.id()), 0, null,
			(option.group() == 5 ? "Opening " : "Adopting ") + option.name(), null, option.type(), false);
	}

	void gift(ServerPlayer player, int minor, int amount) {
		WorldState state = session.state();
		CivInfo civ = state.civ(minor);
		session.submit(player, CommandOp.GIFT, new EntityKey(state.session().viewer(), minor), null, amount, null,
			"The gift of " + amount + " gold to " + (civ == null ? "the city-state" : civ.name()), null, "", false);
	}

	void warMenu(ServerPlayer player, int other) {
		WorldState state = session.state();
		CivInfo civ = state.civ(other);
		if (civ == null) {
			return;
		}
		Menus.Builder menu = session.menus.menu(player, "Declare war on " + civ.name() + "?", 0xD03030);
		if (session.builtin) {
			menu.line("Your units may then attack theirs, and theirs yours. Others will think less of you for it.");
			menu.line("Peace can be offered later, once the war has gone on for some turns.");
		} else {
			menu.line("This is a real declaration of war in your Civilization V game.");
			menu.line("Peace cannot be made from here: that is negotiated in Civilization V itself.");
		}
		menu.button("Yes, declare war", null, p -> declareWar(p, other));
		menu.open();
	}

	void declareWar(ServerPlayer player, int other) {
		WorldState state = session.state();
		CivInfo civ = state.civ(other);
		session.submit(player, CommandOp.DECLARE_WAR, new EntityKey(state.session().viewer(), other), null, 0, null,
			"The declaration of war on " + (civ == null ? "them" : civ.name()), null, "", false);
	}

	// ---- the land ----

	/**
	 * Called as a player moves: stepping into another hex explores the land around it, one hex
	 * far, two from a hilltop, like a scout would in Civ.
	 */
	void explore(ServerPlayer player) {
		BoardModel model = session.model();
		WorldState state = session.state();
		if (!session.explore || model == null || state == null) {
			return;
		}
		PlotCoord plot = model.plotAt(player.getBlockX(), player.getBlockZ());
		if (plot == null || plot.equals(lastPlot.put(player.getUUID(), plot))) {
			return;
		}
		PlotState here = state.plot(plot);
		if (!here.revealed()) {
			return;
		}
		int radius = here.elevation() == PlotState.HILLS ? 2 : 1;
		boolean unknown = false;
		for (PlotCoord near : state.session().grid().neighbors(plot)) {
			unknown |= !state.plot(near).revealed();
			if (radius == 2) {
				for (PlotCoord far : state.session().grid().neighbors(near)) {
					unknown |= !state.plot(far).revealed();
				}
			}
		}
		if (unknown) {
			session.submit(player, CommandOp.REVEAL, self(state), plot, radius, null, "Exploring",
				"reveal-" + state.session().bootId() + "-" + plot + "-" + radius, "", true);
		}
	}

	/** A player felled a log. Enough of them in one forest hex, within one turn, clears the forest in Civ for good. */
	void logBroken(ServerPlayer player, BlockPos pos) {
		BoardModel model = session.model();
		WorldState state = session.state();
		if (model == null || state == null) {
			return;
		}
		PlotCoord plot = model.plotAt(pos.getX(), pos.getZ());
		if (plot == null) {
			return;
		}
		String feature = state.legend().type(Legend.Kind.FEATURE, state.plot(plot).feature());
		if (!feature.equals("FEATURE_FOREST") && !feature.equals("FEATURE_JUNGLE")) {
			return;
		}
		String what = feature.equals("FEATURE_JUNGLE") ? "jungle" : "forest";
		int count = felled.merge(plot, 1, Integer::sum);
		if (count == CHOP_LOGS / 2) {
			player.sendOverlayMessage(Component.literal("Halfway to clearing this " + what + " for good"));
		} else if (count == CHOP_LOGS) {
			session.ask(player, "works", plot.x(), plot.y(), (p, options) -> {
				Records.Option clear = options.stream().filter(o -> o.type().startsWith("BUILD_REMOVE_")).findFirst().orElse(null);
				if (clear == null) {
					session.say(p, "You felled the trees, but this " + what + " will grow back next turn. Clearing it for good needs the land to be inside "
						+ "your borders and your civilization to know how (Mining for forest, Bronze Working for jungle).");
					return;
				}
				WorldState now = session.state();
				session.submit(p, CommandOp.IMPROVE, self(now), plot, clear.id(), null, "Clearing the " + what,
					"clear-" + now.session().bootId() + "-" + plot + "-" + now.turn(), "", false);
			});
		}
	}

	/** What building something on the land costs in gathered materials. */
	static Cost costOf(String build) {
		// Before the road rule: "RAILROAD" also contains "ROAD".
		if (build.contains("RAILROAD")) {
			return new Cost(s -> s.is(Items.IRON_INGOT), 16, "16 iron ingots");
		}
		if (build.contains("FARM") || build.contains("PLANTATION")) {
			return new Cost(s -> s.is(Items.WHEAT_SEEDS), 16, "16 wheat seeds");
		}
		if (build.contains("MINE") || build.contains("QUARRY") || build.contains("ROAD") || build.contains("MARSH")) {
			return new Cost(s -> s.is(Items.COBBLESTONE), build.contains("ROAD") || build.contains("MARSH") ? 16 : 32,
				(build.contains("ROAD") || build.contains("MARSH") ? 16 : 32) + " cobblestone");
		}
		if (build.contains("PASTURE")) {
			return new Cost(s -> s.is(ItemTags.WOODEN_FENCES), 16, "16 wooden fences");
		}
		if (build.contains("CAMP")) {
			return new Cost(s -> s.is(ItemTags.LOGS), 16, "16 logs");
		}
		if (build.contains("FORT") || build.contains("CITADEL")) {
			return new Cost(s -> s.is(Items.COBBLESTONE), 64, "64 cobblestone");
		}
		return new Cost(s -> s.is(ItemTags.PLANKS), 32, "32 planks");
	}

	/** The menu of what can be built on the hex the player stands in, from Civ's own list. */
	void workMenu(ServerPlayer player) {
		BoardModel model = session.model();
		if (model == null) {
			return;
		}
		PlotCoord plot = model.plotAt(player.getBlockX(), player.getBlockZ());
		if (plot == null) {
			session.say(player, "You are off the map.");
			return;
		}
		workMenu(player, plot);
	}

	/** The same for a hex the player points at. */
	void workMenu(ServerPlayer player, PlotCoord plot) {
		session.ask(player, "works", plot.x(), plot.y(), (p, options) -> {
			WorldState state = session.state();
			CivInfo civ = state == null ? null : state.civ(state.session().viewer());
			Menus.Builder menu = session.menus.menu(p, "Work the land at " + plot, civ == null ? 0xFFFFFF : civ.color());
			// Forest and jungle are cleared by felling them, not from this menu.
			List<Records.Option> shown = options.stream().filter(o -> !o.type().equals("BUILD_REMOVE_FOREST") && !o.type().equals("BUILD_REMOVE_JUNGLE")).toList();
			if (shown.isEmpty() && session.builtin && state != null && state.plot(plot).revealed() && state.plot(plot).owner() < 0) {
				// Nobody's land: a city next to it can buy it.
				landMenu(p, plot);
				return;
			}
			if (shown.isEmpty()) {
				menu.line("Nothing can be built here right now. The land must be inside your borders, and your civilization must know how.");
			} else {
				menu.line("You bring the materials; the improvement is then real" + (session.builtin ? "" : " in Civilization V") + ". " + works.getOrDefault(p.getUUID(), 0) + " of "
					+ WORKS_PER_TURN + " works done this turn.");
			}
			for (Records.Option option : shown) {
				Cost cost = costOf(option.type());
				menu.button(option.name() + "  ·  " + cost.words(), null, who -> work(who, plot, option));
			}
			menu.open();
		});
	}

	/** Land that belongs to nobody: which of the player's cities could buy it, and for how much. */
	private void landMenu(ServerPlayer player, PlotCoord plot) {
		session.ask(player, "land", plot.x(), plot.y(), (p, options) -> {
			WorldState state = session.state();
			if (state == null) {
				return;
			}
			CivInfo civ = state.civ(state.session().viewer());
			Empire empire = state.empire();
			Menus.Builder menu = session.menus.menu(p, "The land at " + plot, civ == null ? 0xFFFFFF : civ.color());
			if (options.isEmpty()) {
				menu.line("This land belongs to nobody, and none of your cities can buy it: it must lie next to land a city of yours holds, within its reach.");
			} else {
				menu.line("This land belongs to nobody. Your treasury holds " + (empire == null ? "?" : empire.gold()) + " gold.");
			}
			for (Records.Option option : options) {
				EntityKey city = new EntityKey(state.session().viewer(), option.id());
				menu.button("Buy it for " + option.name() + "  ·  " + option.turns() + " gold", "It becomes part of the city's land at once", who -> session.submit(who,
					CommandOp.CITY_BUY, city, plot, 0, null, "Buying the land at " + plot, null, "land", false));
			}
			menu.open();
		});
	}

	void work(ServerPlayer player, PlotCoord plot, Records.Option option) {
		if (works.getOrDefault(player.getUUID(), 0) >= WORKS_PER_TURN) {
			session.say(player, "You have done " + WORKS_PER_TURN + " works this turn. The land can take more next turn.");
			return;
		}
		Cost cost = costOf(option.type());
		ItemStack taken = LivingWorldServer.take(player, cost.item(), cost.count());
		if (taken.getCount() < cost.count()) {
			if (!taken.isEmpty()) {
				player.getInventory().add(taken);
			}
			session.say(player, "That takes " + cost.words() + ".");
			return;
		}
		works.merge(player.getUUID(), 1, Integer::sum);
		session.submit(player, CommandOp.IMPROVE, self(session.state()), plot, option.id(), taken, option.name() + " at " + plot, null, "", false);
	}
}
