package nl.jordythomassen.livingworld.mc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import nl.jordythomassen.livingworld.protocol.Records;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.WorldState;
import nl.jordythomassen.livingworld.unciv.UncivLink;

/**
 * Before the game, and at its door: what game is played and who leads whom. A world without a
 * game shows everybody the same table: each picks a civilization, whoever keeps the world sets
 * the map and the rules and begins. In a game that runs, a newcomer chooses a civilization to
 * take over, somebody to rule along with, or to look on.
 */
final class Lobby {
	private static final List<String> SIZES = List.of("Tiny", "Small", "Medium", "Large", "Huge");
	private static final List<String> DIFFICULTIES = List.of("Settler", "Chieftain", "Warlord", "Prince", "King", "Emperor", "Immortal", "Deity");
	private static final List<String> COUNTS = List.of("0", "1", "2", "3", "4", "5", "6", "7");
	private static final List<String> STATES = List.of("0", "2", "4", "6", "8", "10", "12");
	private static final List<String> BARBARIANS = List.of("As usual", "None", "Raging");
	private static final List<String> YES_NO = List.of("Yes", "No");
	private static final List<String> WAYS = List.of("Scientific", "Cultural", "Domination", "Diplomatic", "Time");

	/** One thing about the next game that can be set: what it is called, what it can be, and a word on it. */
	private record Setting(String key, String label, List<String> choices, String tip) {
	}

	private final List<Setting> settings = new ArrayList<>();
	private final LivingWorldServer session;
	private final Map<String, String> values = new LinkedHashMap<>();
	/** The civilization each person picked for the next game; none is "any". */
	private final Map<UUID, String> nations = new HashMap<>();
	/** People who have the table for a new game open while a game runs. */
	private final Set<UUID> setting = new HashSet<>();
	/** People who were shown the door of the running game since they came in. */
	private final Set<UUID> greeted = new HashSet<>();
	private List<Records.Option> nationList = List.of();
	private boolean asked;
	private boolean starting;

	Lobby(LivingWorldServer session) {
		this.session = session;
		ModConfig config = session.config;
		settings.add(new Setting("mapType", "Land", UncivLink.MAP_TYPES, "The shape of the world"));
		settings.add(new Setting("mapSize", "Map size", SIZES, "A larger world takes longer between turns"));
		settings.add(new Setting("speed", "Length", UncivLink.SPEEDS, "Quick is some 330 turns, Standard 500, Epic 750, Marathon 1500"));
		settings.add(new Setting("difficulty", "Difficulty", DIFFICULTIES, "Prince is even; above it the computer's civilizations get help"));
		settings.add(new Setting("rivals", "Computer civilizations", COUNTS, "Besides the people who play"));
		settings.add(new Setting("cityStates", "City-states", STATES, "Small neutral cities to befriend or conquer"));
		settings.add(new Setting("barbarians", "Barbarians", BARBARIANS, "Raging: more camps, sooner"));
		settings.add(new Setting("ruins", "Ancient ruins", YES_NO, "Gifts for whoever explores first"));
		settings.add(new Setting("spies", "Spies", YES_NO, "From the Renaissance on: steal technologies, rig the elections of city-states, guard your own cities"));
		settings.add(new Setting("nukes", "Nuclear weapons", YES_NO, "Whether atomic bombs can be built at all"));
		settings.add(new Setting("oneCity", "One city each", List.of("No", "Yes"), "Nobody may found or keep a second city"));
		for (String way : WAYS) {
			settings.add(new Setting("win:" + way, way + " victory", YES_NO, "Whether the game can be won this way"));
		}
		values.put("mapType", UncivLink.MAP_TYPES.stream().filter(t -> t.replace(" ", "").equalsIgnoreCase(config.mapType.replace(" ", ""))).findFirst().orElse("Pangaea"));
		values.put("mapSize", SIZES.contains(config.mapSize) ? config.mapSize : "Small");
		values.put("speed", UncivLink.SPEEDS.contains(config.speed) ? config.speed : "Quick");
		values.put("difficulty", DIFFICULTIES.contains(config.difficulty) ? config.difficulty : "Prince");
		values.put("rivals", Integer.toString(Math.max(0, Math.min(7, config.rivals))));
		values.put("cityStates", Integer.toString(Math.max(0, Math.min(12, config.cityStates - config.cityStates % 2))));
		for (Setting setting : settings) {
			values.putIfAbsent(setting.key(), setting.choices().get(0));
		}
	}

	/** Whether this player may set the game up and begin it: whoever keeps the world, or the first one in when nobody who does is here. */
	boolean keeps(ServerPlayer player) {
		if (session.server.isSingleplayerOwner(player.nameAndId()) || session.server.getPlayerList().isOp(player.nameAndId())) {
			return true;
		}
		List<ServerPlayer> all = session.server.getPlayerList().getPlayers();
		for (ServerPlayer other : all) {
			if (session.server.isSingleplayerOwner(other.nameAndId()) || session.server.getPlayerList().isOp(other.nameAndId())) {
				return false;
			}
		}
		return !all.isEmpty() && all.get(0) == player;
	}

	void forget(ServerPlayer player) {
		setting.remove(player.getUUID());
		greeted.remove(player.getUUID());
		if (session.inLobby()) {
			nations.remove(player.getUUID());
			showAll();
		}
	}

	/** A new game began (or was loaded): the table is cleared. */
	void gameStarted() {
		starting = false;
		setting.clear();
		for (ServerPlayer player : session.server.getPlayerList().getPlayers()) {
			if (Rts.speaks(player)) {
				ServerPlayNetworking.send(player, new Net.Choices("lobby:close", 0, List.of()));
			}
		}
	}

	/** A player's game says it is ready. Returns whether the table or the door was put before them (so that the view from above waits). */
	boolean hello(ServerPlayer player) {
		if (!session.builtin || !Rts.speaks(player)) {
			return false;
		}
		if (session.inLobby()) {
			show(player);
			return true;
		}
		if (session.board() == null) {
			return false;
		}
		if (session.seats.undecided(player)) {
			show(player);
			return true;
		}
		if (greeted.add(player.getUUID()) && !session.config.autoStart) {
			// Back in a game that was played before: continue it, or begin another.
			show(player);
			return true;
		}
		return false;
	}

	/** Puts the table (or the door of the running game) before everybody who looks at one. */
	void showAll() {
		for (ServerPlayer player : session.server.getPlayerList().getPlayers()) {
			if (Rts.speaks(player) && (session.inLobby() || setting.contains(player.getUUID()))) {
				show(player);
			}
		}
	}

	void show(ServerPlayer player) {
		if (!Rts.speaks(player)) {
			return;
		}
		if (nationList.isEmpty() && !asked) {
			asked = true;
			session.link.ask("nations", 0, 0, list -> session.later(() -> {
				nationList = list == null ? List.of() : list;
				asked = false;
				for (ServerPlayer other : session.server.getPlayerList().getPlayers()) {
					if (Rts.speaks(other) && !nationList.isEmpty()) {
						ServerPlayNetworking.send(other, new Net.Choices("lobby:nations", 0, nationList));
					}
				}
			}));
		} else if (!nationList.isEmpty()) {
			ServerPlayNetworking.send(player, new Net.Choices("lobby:nations", 0, nationList));
		}
		boolean table = session.inLobby() || setting.contains(player.getUUID());
		if (table) {
			table(player);
		} else {
			door(player);
		}
	}

	/** The table a game is set up at (mode 0): the settings, who is there with which civilization, and what this player may do. */
	private void table(ServerPlayer player) {
		List<Records.Option> rows = new ArrayList<>();
		boolean keeps = keeps(player);
		int index = 0;
		for (Setting setting : settings) {
			rows.add(new Records.Option(0, index++, setting.key(), setting.label() + "|" + values.get(setting.key()) + "|" + setting.tip(), keeps ? 1 : 0));
		}
		for (ServerPlayer other : session.server.getPlayerList().getPlayers()) {
			rows.add(new Records.Option(1, other == player ? 1 : 0, other.getName().getString(), nations.getOrDefault(other.getUUID(), ""), keeps(other) ? 1 : 0));
		}
		rows.add(new Records.Option(2, keeps ? 1 : 0, "me", nations.getOrDefault(player.getUUID(), ""), starting ? 1 : 0));
		WorldState board = session.board();
		if (board != null && !session.inLobby()) {
			rows.add(new Records.Option(2, 0, "game", running(board), 0));
		}
		ServerPlayNetworking.send(player, new Net.Choices("lobby", 0, rows));
	}

	private static String running(WorldState board) {
		CivInfo civ = board.civ(board.session().viewer());
		return "Turn " + board.turn() + (board.empire() == null ? "" : ", " + board.empire().yearText()) + (civ == null ? "" : " · " + civ.name());
	}

	/**
	 * The door of a game that runs: mode 1 for somebody who has yet to say what they are in it,
	 * mode 2 for somebody who rules already (continue, or begin another). The civilizations come
	 * with who rules them.
	 */
	private void door(ServerPlayer player) {
		WorldState board = session.board();
		if (board == null) {
			return;
		}
		UUID id = player.getUUID();
		session.link.ask("seats", 0, 0, list -> session.later(() -> {
			ServerPlayer who = session.server.getPlayerList().getPlayer(id);
			if (who == null || list == null) {
				return;
			}
			int mine = session.seats.of(who);
			List<Records.Option> rows = new ArrayList<>();
			for (Records.Option civ : list) {
				// Who rules it, for the list: the names of its people, or nothing for the computer's.
				rows.add(new Records.Option(3, civ.id(), civ.type() + "|" + (civ.id() == mine ? 1 : 0) + "|" + session.seats.rulerNames(civ.id()), civ.name(), civ.group()));
			}
			rows.add(new Records.Option(2, keeps(who) ? 1 : 0, "me", "", 0));
			rows.add(new Records.Option(2, 0, "game", running(session.board()), 0));
			ServerPlayNetworking.send(who, new Net.Choices("lobby", mine >= 0 ? 2 : 1, rows));
		}));
	}

	/** Something a player did at the table or the door. */
	void act(ServerPlayer player, Net.Act act) {
		String text = act.text();
		UUID id = player.getUUID();
		boolean table = session.inLobby() || setting.contains(id);
		if (text.equals("open")) {
			// Asked for from the menu of a running game: who plays, and the way to another game.
			show(player);
			return;
		}
		if (text.equals("new") && keeps(player) && !session.inLobby()) {
			setting.add(id);
			show(player);
			return;
		}
		if (text.equals("back")) {
			setting.remove(id);
			show(player);
			return;
		}
		if (table) {
			if (text.startsWith("nation:")) {
				String wanted = text.substring(7);
				if (wanted.isEmpty() || nationList.stream().anyMatch(n -> n.type().equals(wanted))) {
					// Two people cannot lead the same civilization from the start; the later one is told.
					if (!wanted.isEmpty() && session.inLobby() && nations.entrySet().stream().anyMatch(e -> !e.getKey().equals(id) && e.getValue().equals(wanted))) {
						LivingWorldServer.say(player, wanted + " is taken already.");
					} else {
						nations.put(id, wanted);
					}
				}
				showAll();
				show(player);
				return;
			}
			if ((text.startsWith("next:") || text.startsWith("prev:")) && keeps(player)) {
				String key = text.substring(5);
				for (Setting setting : settings) {
					if (setting.key().equals(key)) {
						int at = setting.choices().indexOf(values.get(key));
						int size = setting.choices().size();
						values.put(key, setting.choices().get(((at + (text.startsWith("next:") ? 1 : -1)) % size + size) % size));
					}
				}
				showAll();
				show(player);
				return;
			}
			if (text.equals("start") && keeps(player) && !starting) {
				start(player);
			}
			return;
		}
		if (session.board() == null) {
			return;
		}
		switch (text) {
			case "take" -> session.seats.take(player, act.a());
			case "share" -> session.seats.share(player, act.a());
			case "watch" -> session.seats.watch(player);
			case "continue" -> session.seated(player);
			default -> {
			}
		}
	}

	private void start(ServerPlayer keeper) {
		// Whoever sets it up leads the first civilization; everybody else who is here gets one of their own, in the order they came.
		List<ServerPlayer> people = new ArrayList<>(session.server.getPlayerList().getPlayers());
		people.remove(keeper);
		people.add(0, keeper);
		List<String> others = new ArrayList<>();
		for (int i = 1; i < people.size(); i++) {
			others.add(nations.getOrDefault(people.get(i).getUUID(), ""));
		}
		int rivals = Integer.parseInt(values.get("rivals"));
		if (people.size() + rivals < 2) {
			rivals = 1;
		}
		Set<String> options = new HashSet<>();
		switch (values.get("barbarians")) {
			case "None" -> options.add("noBarbarians");
			case "Raging" -> options.add("ragingBarbarians");
			default -> {
			}
		}
		if (values.get("ruins").equals("No")) {
			options.add("noRuins");
		}
		if (values.get("spies").equals("Yes")) {
			options.add("espionage");
		}
		if (values.get("nukes").equals("No")) {
			options.add("noNukes");
		}
		if (values.get("oneCity").equals("Yes")) {
			options.add("oneCity");
		}
		List<String> victories = new ArrayList<>();
		for (String way : WAYS) {
			if (values.get("win:" + way).equals("Yes")) {
				victories.add(way);
			}
		}
		if (victories.isEmpty()) {
			victories.add("Domination");
		}
		UncivLink.Setup setup = new UncivLink.Setup(values.get("mapSize"), values.get("mapType"), rivals, Integer.parseInt(values.get("cityStates")), values.get("difficulty"),
			nations.getOrDefault(keeper.getUUID(), ""), 0L, values.get("speed"), others, options, victories);
		starting = true;
		boolean fresh = session.inLobby();
		if (fresh) {
			session.begin(setup);
		} else {
			session.startNewGame(setup);
		}
		// The people get the civilizations in the order they were set up.
		List<UUID> order = new ArrayList<>();
		for (ServerPlayer one : people) {
			order.add(one.getUUID());
		}
		session.seats.promise(order);
		showAll();
		LivingWorldMod.LOG.info("Living World: {} begins a game ({} {}, {} people, {} computer civilizations)", keeper.getName().getString(), setup.mapSize(), setup.mapType(),
			people.size(), rivals);
	}
}
