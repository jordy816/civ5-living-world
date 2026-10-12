package nl.jordythomassen.livingworld.mc;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * Who rules which civilization. In a game for one, everybody in the world rules the same
 * civilization together, as before. When more people come, each can take a civilization of
 * their own from the computer, rule along with somebody who has one, or just watch. Kept in the
 * world folder next to the game, so that everybody finds their civilization again.
 */
final class Seats {
	private final LivingWorldServer session;
	/** The civilization each person rules, by its number in the game. */
	private final Map<UUID, Integer> rulers = new HashMap<>();
	/** People who chose to look on. */
	private final java.util.Set<UUID> watchers = new java.util.HashSet<>();
	/** The names people were last seen under, for the list of who plays. */
	private final Map<UUID, String> names = new HashMap<>();
	/** What the game was last told about who is there. */
	private final Map<Integer, Boolean> presentTold = new HashMap<>();
	/** People promised a civilization of the game that is being made, in the order of its civilizations of people. */
	private final List<UUID> promised = new ArrayList<>();
	private String gameId = "";

	Seats(LivingWorldServer session) {
		this.session = session;
	}

	private Path file() {
		return session.server.getWorldPath(LevelResource.ROOT).resolve("livingworld-seats.txt");
	}

	/** Whether several civilizations are ruled by people, each seeing their own picture of the game. */
	boolean several() {
		return session.builtin && session.link.seats().size() > 1;
	}

	/** The civilization a player rules, or -1 when they only watch or have yet to choose. */
	int of(ServerPlayer player) {
		WorldState board = session.board();
		if (!session.builtin) {
			return board == null ? -1 : board.session().viewer();
		}
		Integer civ = rulers.get(player.getUUID());
		List<Integer> seats = session.link.seats();
		if (civ == null && !seats.isEmpty() && player instanceof net.fabricmc.fabric.api.entity.FakePlayer) {
			// Not somebody who came in through the door (a stand-in the tests play with): they play the first civilization.
			// (Told by what they are, not by the server's list of players: somebody who is just coming in is not on it yet,
			// and was taken for a stand-in, so that a civilization nobody ruled was not given to them.)
			return seats.get(0);
		}
		return civ == null || !seats.contains(civ) ? -1 : civ;
	}

	/** Whether a player still has to say what they are in this game: nobody gave them a civilization and they did not choose to watch. */
	boolean undecided(ServerPlayer player) {
		return session.builtin && session.board() != null && of(player) < 0 && !watchers.contains(player.getUUID());
	}

	boolean watching(ServerPlayer player) {
		return session.builtin && of(player) < 0;
	}

	/** The people who are here and rule a civilization. */
	List<ServerPlayer> rulersOf(int civ) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer player : session.server.getPlayerList().getPlayers()) {
			if (of(player) == civ) {
				out.add(player);
			}
		}
		return out;
	}

	/** The names of everybody who rules a civilization, here or not, for the list of who plays. */
	String rulerNames(int civ) {
		List<String> out = new ArrayList<>();
		for (Map.Entry<UUID, Integer> entry : rulers.entrySet()) {
			if (entry.getValue() == civ) {
				ServerPlayer here = session.server.getPlayerList().getPlayer(entry.getKey());
				out.add(here != null ? here.getName().getString() : names.getOrDefault(entry.getKey(), "somebody") + " (away)");
			}
		}
		return String.join(", ", out);
	}

	void clear() {
		rulers.clear();
		watchers.clear();
		presentTold.clear();
		promised.clear();
		gameId = "";
	}

	/** Another game is being shown: who ruled what in it is read back, when it was played here before. */
	void gameChanged(WorldState state) {
		String id = state.session().gameId();
		if (id.equals(gameId)) {
			return;
		}
		rulers.clear();
		watchers.clear();
		presentTold.clear();
		Properties saved = new Properties();
		if (Files.exists(file())) {
			try (Reader in = Files.newBufferedReader(file(), StandardCharsets.UTF_8)) {
				saved.load(in);
			} catch (IOException e) {
				LivingWorldMod.LOG.warn("Living World: could not read who rules what", e);
			}
		}
		if (id.equals(saved.getProperty("game"))) {
			for (String key : saved.stringPropertyNames()) {
				try {
					if (key.startsWith("name.")) {
						names.put(UUID.fromString(key.substring(5)), saved.getProperty(key));
					} else if (!key.equals("game")) {
						rulers.put(UUID.fromString(key), Integer.parseInt(saved.getProperty(key).trim()));
					}
				} catch (IllegalArgumentException e) {
					// A line somebody wrote by hand and got wrong is left out.
				}
			}
		} else {
			// Civilizations handed out before the game was there (by whoever set it up) are for this game.
			List<Integer> seats = session.link.seats();
			for (int i = 0; i < promised.size() && i < seats.size(); i++) {
				rulers.put(promised.get(i), seats.get(i));
			}
		}
		promised.clear();
		gameId = id;
		for (ServerPlayer player : session.server.getPlayerList().getPlayers()) {
			joined(player);
		}
		save();
	}

	/** Players that are not on the server's list and count as being there all the same: the stand-ins the self-test plays with. */
	private final java.util.Set<UUID> standIns = new java.util.HashSet<>();

	void standIn(UUID player) {
		standIns.add(player);
	}

	/** Says before the game is there who gets its civilizations of people, in the order they are set up. */
	void promise(List<UUID> people) {
		promised.clear();
		promised.addAll(people);
	}

	private void save() {
		if (gameId.isEmpty()) {
			return;
		}
		Properties out = new Properties();
		out.setProperty("game", gameId);
		for (Map.Entry<UUID, Integer> entry : rulers.entrySet()) {
			out.setProperty(entry.getKey().toString(), Integer.toString(entry.getValue()));
			String name = names.get(entry.getKey());
			if (name != null) {
				out.setProperty("name." + entry.getKey(), name);
			}
		}
		try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
			out.store(writer, "Living World: which civilization each player rules (player id = number of the civilization in the game)");
		} catch (IOException e) {
			LivingWorldMod.LOG.warn("Living World: could not write who rules what", e);
		}
	}

	/**
	 * Somebody came into the world. Whoever ruled a civilization before has it again. A
	 * civilization of a person that nobody rules (a game for one, or one from before this was
	 * kept) goes to the first who comes. Everybody else is asked what they want to be.
	 */
	void joined(ServerPlayer player) {
		if (!session.builtin || session.board() == null) {
			return;
		}
		names.put(player.getUUID(), player.getName().getString());
		if (of(player) >= 0) {
			return;
		}
		for (int civ : session.link.seats()) {
			if (!rulers.containsValue(civ)) {
				rulers.put(player.getUUID(), civ);
				watchers.remove(player.getUUID());
				save();
				LivingWorldMod.LOG.info("Living World: {} rules {}", player.getName().getString(), name(civ));
				return;
			}
		}
	}

	void left(ServerPlayer player) {
		// Their civilization stays theirs; the game is told below that nobody is there for it.
	}

	private String name(int civ) {
		WorldState board = session.board();
		CivInfo info = board == null ? null : session.link.state(civ).civ(civ);
		return info == null ? "civilization " + civ : info.name();
	}

	/** Tells the game who is there, so that nobody waits for somebody who left. */
	void tick() {
		if (!session.builtin || session.board() == null) {
			return;
		}
		for (int civ : session.link.seats()) {
			boolean here = !rulersOf(civ).isEmpty();
			for (UUID id : standIns) {
				here |= Integer.valueOf(civ).equals(rulers.get(id));
			}
			Boolean told = presentTold.put(civ, here);
			if (told == null || told != here) {
				session.link.present(civ, here);
			}
		}
	}

	/** A player takes over a civilization the computer plays. */
	void take(ServerPlayer player, int civ) {
		if (!session.builtin || session.board() == null) {
			return;
		}
		UUID id = player.getUUID();
		session.link.rule(civ, true, refusal -> session.later(() -> {
			ServerPlayer who = session.server.getPlayerList().getPlayer(id);
			if (!refusal.isEmpty()) {
				if (who != null) {
					session.say(who, refusal);
				}
				return;
			}
			Integer before = rulers.put(id, civ);
			watchers.remove(id);
			save();
			if (before != null && before != civ && !rulers.containsValue(before)) {
				// The civilization they ruled until now has nobody left: the computer takes it back.
				session.link.rule(before, false, ignored -> { });
			}
			session.announceAll((who == null ? "Somebody" : who.getName().getString()) + " now rules " + name(civ) + ".");
			if (who != null) {
				session.seated(who);
			}
		}));
	}

	/** A player rules along with whoever has a civilization already. */
	void share(ServerPlayer player, int civ) {
		if (!session.builtin || !session.link.seats().contains(civ)) {
			return;
		}
		Integer before = rulers.put(player.getUUID(), civ);
		watchers.remove(player.getUUID());
		save();
		if (before != null && before != civ && !rulers.containsValue(before)) {
			session.link.rule(before, false, ignored -> { });
		}
		session.announceAll(player.getName().getString() + " now rules " + name(civ) + " as well.");
		session.seated(player);
	}

	/** A player gives up ruling and looks on. A civilization nobody rules any more goes back to the computer. */
	void watch(ServerPlayer player) {
		Integer before = rulers.remove(player.getUUID());
		watchers.add(player.getUUID());
		save();
		if (before != null && !rulers.containsValue(before)) {
			session.link.rule(before, false, refusal -> session.later(() -> {
				if (!refusal.isEmpty()) {
					// The last civilization of a person stays theirs.
					rulers.put(player.getUUID(), before);
					watchers.remove(player.getUUID());
					save();
					session.say(player, refusal);
				}
				session.seated(player);
			}));
		} else {
			session.seated(player);
		}
	}
}
