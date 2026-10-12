package nl.jordythomassen.livingworld.unciv;

import com.unciv.UncivGame;
import com.unciv.logic.GameInfo;
import com.unciv.models.ruleset.RulesetCache;
import com.unciv.logic.GameStarter;
import com.unciv.logic.civilization.Civilization;
import com.unciv.logic.civilization.Notification;
import com.unciv.logic.civilization.PlayerType;
import com.unciv.logic.civilization.managers.TurnManager;
import com.unciv.logic.files.UncivFiles;
import com.unciv.logic.map.MapParameters;
import com.unciv.logic.map.MapShape;
import com.unciv.logic.map.MapSize;
import com.unciv.models.metadata.GameParameters;
import com.unciv.models.metadata.GameSetupInfo;
import com.unciv.models.metadata.Player;
import com.unciv.models.translations.TranslationsKt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import nl.jordythomassen.livingworld.commands.CommandStatus;
import nl.jordythomassen.livingworld.commands.CommandTicket;
import nl.jordythomassen.livingworld.commands.GameCommand;
import nl.jordythomassen.livingworld.link.GameLink;
import nl.jordythomassen.livingworld.link.LinkStatus;
import nl.jordythomassen.livingworld.link.PlayerMarker;
import nl.jordythomassen.livingworld.protocol.Records;
import nl.jordythomassen.livingworld.state.WorldDiff;
import nl.jordythomassen.livingworld.state.WorldEvent;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * The game, inside this process: one Unciv game, played through the same interface the Minecraft
 * side used for a running Civilization V. One thread owns the game; requests are queued to it and
 * answered there, and after each one a fresh picture is published.
 *
 * <p>One person rules one civilization, and several people can each rule their own. Then they all
 * move at the same time and the turn passes when each of them has ended theirs; two of them who
 * are at war move one after the other, the one that comes first in the game's order first. (Unciv
 * itself only knows turns taken strictly in order; the round is made here from the same three
 * calls Unciv's own next-turn makes for every civilization.)
 */
public final class UncivLink implements GameLink {
	/** How a new game is set up. */
	/**
	 * How a new game is set up.
	 *
	 * @param nation the civilization of the one who sets the game up; empty for any
	 * @param others the civilizations of the other people who play from the start, one each; empty names for any
	 * @param options what differs from the usual: "noBarbarians", "ragingBarbarians", "oneCity", "noNukes", "espionage", "noRuins", "noRazing"
	 * @param victories the ways to win that count; empty for all of them
	 */
	public record Setup(String mapSize, String mapType, int rivals, int cityStates, String difficulty, String nation, long seed, String speed, List<String> others,
		java.util.Set<String> options, List<String> victories) {
		public Setup(String mapSize, String mapType, int rivals, int cityStates, String difficulty, String nation, long seed, String speed) {
			this(mapSize, mapType, rivals, cityStates, difficulty, nation, seed, speed, List.of(), java.util.Set.of(), List.of());
		}

		public static Setup standard() {
			return new Setup("Small", "pangaea", 3, 4, "Prince", "", 0L, "Quick");
		}
	}

	/** One civilization ruled by a person. */
	private final class Seat {
		final Civilization civ;
		final int id;
		final UncivActions actions;
		final IdentityHashMap<Notification, Boolean> told = new IdentityHashMap<>();
		/** News of the engine's own making for this civilization, told with the next picture. */
		final List<String> news = new ArrayList<>();
		final List<WorldEvent> events = new ArrayList<>();
		volatile WorldState state;
		/** They ended their turn and wait for the others. */
		volatile boolean ended;
		/** Their person is there. For somebody away the computer plays. */
		volatile boolean present = true;
		/** After this round the computer has the civilization again. */
		volatile boolean leaving;
		volatile boolean mayAct = true;
		volatile String waitsFor = "";
		boolean outcomeTold;

		Seat(Civilization civ) {
			this.civ = civ;
			this.id = snapshot.civId(civ);
			this.actions = new UncivActions(game, snapshot, civ, log);
			for (Notification old : civ.getNotifications()) {
				told.put(old, true);
			}
			outcomeTold = game.getVictoryData() != null || civ.isDefeated();
		}
	}

	private final Path dataDir;
	private final Path saveFile;
	private volatile Setup setup;
	private final Consumer<String> log;
	private final String bootId = "unciv-" + Long.toString(System.currentTimeMillis(), 36);
	private final BlockingQueue<Runnable> work = new LinkedBlockingQueue<>();
	private final List<WorldEvent> events = new ArrayList<>();
	private final List<CommandTicket> ticketUpdates = new ArrayList<>();
	private final Map<String, CommandTicket> byKey = new HashMap<>();
	/** The civilizations ruled by people, in the game's order. Replaced as a whole when one is taken or given up. */
	private volatile List<Seat> seats = List.of();

	private volatile WorldState state;
	private volatile LinkStatus status = LinkStatus.OFF;
	private volatile String detail = "";
	private volatile boolean governors;
	private volatile boolean running = true;
	private volatile long lastWorkMicros;

	private GameInfo game;
	private UncivSnapshot snapshot;
	private Civilization viewer;
	private UncivActions actions;

	/**
	 * @param dataDir where Unciv's rule files are unpacked
	 * @param saveFile where the game is kept; loaded when it exists, written after every turn
	 * @param setup how a new game is made when there is none to load; null to wait until {@link #begin} says how
	 */
	public UncivLink(Path dataDir, Path saveFile, Setup setup, Consumer<String> log) {
		this.dataDir = dataDir;
		this.saveFile = saveFile;
		this.setup = setup;
		this.log = log;
	}

	// ---- seen from the Minecraft server thread ----

	@Override
	public WorldState state() {
		return state;
	}

	@Override
	public LinkStatus status() {
		return status;
	}

	@Override
	public String statusDetail() {
		return detail;
	}

	@Override
	public boolean viewerTurn() {
		List<Seat> all = seats;
		return !all.isEmpty() && all.get(0).mayAct;
	}

	private Seat seat(int civ) {
		for (Seat seat : seats) {
			if (seat.id == civ) {
				return seat;
			}
		}
		return null;
	}

	@Override
	public List<Integer> seats() {
		List<Integer> out = new ArrayList<>();
		for (Seat seat : seats) {
			out.add(seat.id);
		}
		return out;
	}

	@Override
	public WorldState state(int civ) {
		Seat seat = seat(civ);
		return seat == null || seat.state == null ? state : seat.state;
	}

	@Override
	public boolean mayAct(int civ) {
		Seat seat = seat(civ);
		return seat != null && seat.mayAct;
	}

	@Override
	public String waitsFor(int civ) {
		Seat seat = seat(civ);
		return seat == null ? "" : seat.waitsFor;
	}

	@Override
	public List<WorldEvent> drainEvents(int civ) {
		Seat seat = seat(civ);
		if (seat == null) {
			return List.of();
		}
		synchronized (seat.events) {
			List<WorldEvent> out = new ArrayList<>(seat.events);
			seat.events.clear();
			return out;
		}
	}

	/** Says how the game that is waited for is to be made. Only heard while there is none. */
	public void begin(Setup how) {
		if (setup == null) {
			setup = how;
			work.add(() -> { });
		}
	}

	/**
	 * Writes the game as it stands this moment to a file of its own, beside the one it is kept in
	 * turn by turn. The answer says whether that worked.
	 */
	public void keep(Path target, Consumer<Boolean> answer) {
		work.add(new Question(() -> {
			boolean done = false;
			try {
				if (game != null) {
					save();
					Files.createDirectories(target.toAbsolutePath().getParent());
					Files.copy(saveFile, target, StandardCopyOption.REPLACE_EXISTING);
					done = true;
				}
			} catch (IOException | RuntimeException e) {
				log.accept("Living World: could not keep a copy of the game: " + e);
			}
			answer.accept(done);
		}));
	}

	/** Whether the engine waits to be told how its game is to be made. */
	public boolean waiting() {
		return status == LinkStatus.NO_MATCH;
	}

	@Override
	public long roundTripMicros() {
		return lastWorkMicros;
	}

	@Override
	public long hintsSeen() {
		return 0;
	}

	@Override
	public int commandsWaiting() {
		return work.size();
	}

	@Override
	public void setMarker(PlayerMarker marker) {
		// There is no second window to show the player in.
	}

	@Override
	public List<WorldEvent> drainEvents() {
		synchronized (events) {
			List<WorldEvent> out = new ArrayList<>(events);
			events.clear();
			return out;
		}
	}

	@Override
	public List<CommandTicket> drainTicketUpdates() {
		synchronized (ticketUpdates) {
			List<CommandTicket> out = new ArrayList<>(ticketUpdates);
			ticketUpdates.clear();
			return out;
		}
	}

	@Override
	public CommandTicket submit(GameCommand command) {
		List<Seat> all = seats;
		return submit(all.isEmpty() ? -1 : all.get(0).id, command);
	}

	@Override
	public CommandTicket submit(int civ, GameCommand command) {
		synchronized (byKey) {
			CommandTicket existing = byKey.get(command.idempotencyKey());
			if (existing != null && existing.status() != CommandStatus.REJECTED && existing.status() != CommandStatus.EXPIRED) {
				return existing;
			}
			CommandTicket ticket = CommandTicket.open(command);
			byKey.put(command.idempotencyKey(), ticket);
			if (byKey.size() > 400) {
				byKey.values().removeIf(t -> t.status().finished() && t != ticket);
			}
			work.add(() -> execute(civ, ticket));
			return ticket;
		}
	}

	@Override
	public void ask(String kind, int a, int c, Consumer<List<Records.Option>> answer) {
		ask(-1, kind, a, c, answer);
	}

	@Override
	public void ask(int civ, String kind, int a, int c, Consumer<List<Records.Option>> answer) {
		work.add(new Question(() -> {
			List<Records.Option> options;
			try {
				List<Seat> all = seats;
				Seat seat = civ < 0 ? (all.isEmpty() ? null : all.get(0)) : seat(civ);
				options = switch (kind) {
					case "nations" -> nations();
					case "seats" -> seatList();
					default -> seat == null ? List.of() : seat.actions.options(kind, a, c);
				};
			} catch (RuntimeException e) {
				log.accept("Living World: listing " + kind + " failed: " + e + where(e));
				options = List.of();
			}
			answer.accept(options);
		}));
	}

	/**
	 * A job that only looks: it changes nothing in the game, so no new picture of the game is
	 * taken after it. (Whoever asks often, as the view from above does for the selected unit,
	 * would otherwise have every answer followed by a new picture, and every picture by a question.)
	 */
	private record Question(Runnable body) implements Runnable {
		@Override
		public void run() {
			body.run();
		}
	}

	@Override
	public void stop() {
		running = false;
		work.add(() -> { });
	}

	// ---- the game thread ----

	@Override
	public void run() {
		try {
			if (setup == null && !Files.exists(saveFile)) {
				// No game yet, and nobody has said what it should be: the rules are loaded, and the people are asked.
				status = LinkStatus.SYNCING;
				detail = "starting the game engine";
				UncivBoot.ensure(dataDir, log);
				status = LinkStatus.NO_MATCH;
				detail = "waiting for a game to be set up";
				while (running && setup == null) {
					Runnable job = work.poll(250, TimeUnit.MILLISECONDS);
					if (job != null) {
						try {
							job.run();
						} catch (Throwable e) {
							log.accept("Living World: a request failed in the game engine: " + e);
						}
					}
				}
				if (!running) {
					return;
				}
			}
			start();
			while (running) {
				Runnable job = work.poll(250, TimeUnit.MILLISECONDS);
				if (job != null) {
					runJob(job);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Throwable e) {
			status = LinkStatus.NO_GAME;
			detail = e.toString();
			log.accept("Living World: the game engine stopped: " + e);
		} finally {
			save();
			status = LinkStatus.OFF;
		}
	}

	/** Brings the engine up and loads or starts the game. Called by {@link #run}; tests call it directly. */
	public void start() throws IOException {
		status = LinkStatus.SYNCING;
		detail = "starting the game engine";
		UncivBoot.ensure(dataDir, log);
		if (Files.exists(saveFile)) {
			game = UncivFiles.Companion.gameInfoFromString(Files.readString(saveFile, StandardCharsets.UTF_8));
			if (game.getGameParameters().getVictoryTypes().isEmpty()) {
				// A game begun by an earlier build, which left out the ways to win.
				game.getGameParameters().setVictoryTypes(new ArrayList<>(game.getRuleset().getVictories().keySet()));
			}
			log.accept("Living World: loaded the game from " + saveFile.getFileName() + " (turn " + game.getTurns() + ")");
		} else {
			game = newGame();
			log.accept("Living World: started a new game (" + setup.mapSize() + " " + setup.mapType() + ", " + (setup.rivals() + 1 + setup.others().size()) + " civilizations, "
				+ setup.cityStates() + " city-states)");
		}
		UncivGame.Companion.getCurrent().setGameInfo(game);
		// Unciv calls a game on score after turn 500 whatever its pace, which on the slower paces is the middle of the game:
		// the last turn is the one its own calendar ends on.
		int lastTurn = game.getSpeed().numTotalTurns();
		if (game.getGameParameters().getMaxTurns() == 500 && lastTurn > 0 && lastTurn != 500) {
			game.getGameParameters().setMaxTurns(lastTurn);
		}
		snapshot = new UncivSnapshot(game, new UncivMap(game.getTileMap()), bootId);
		List<Seat> found = new ArrayList<>();
		for (Civilization civ : game.getCivilizations()) {
			if (civ.isHuman() && !civ.isSpectator()) {
				found.add(new Seat(civ));
			}
		}
		if (found.isEmpty()) {
			throw new IOException("the game has no human player");
		}
		seats = List.copyOf(found);
		viewer = found.get(0).civ;
		actions = found.get(0).actions;
		UncivGame.Companion.getCurrent().getSettings().setAutoAssignCityProduction(governors);
		if (governors) {
			actions.governorsChoose();
		}
		publish();
		status = LinkStatus.LIVE;
		detail = "";
	}

	private GameInfo newGame() {
		GameParameters parameters = new GameParameters();
		parameters.setDifficulty(setup.difficulty());
		parameters.setSpeed(SPEEDS.contains(setup.speed()) ? setup.speed() : "Quick");
		parameters.setNumberOfCityStates(setup.cityStates());
		ArrayList<Player> players = new ArrayList<>();
		players.add(new Player(setup.nation().isEmpty() ? "Random" : setup.nation(), PlayerType.Human, ""));
		for (String other : setup.others()) {
			players.add(new Player(other.isEmpty() || other.equals(setup.nation()) ? "Random" : other, PlayerType.Human, ""));
		}
		for (int i = 0; i < setup.rivals(); i++) {
			players.add(new Player());
		}
		parameters.setPlayers(players);
		parameters.setNoBarbarians(setup.options().contains("noBarbarians"));
		parameters.setRagingBarbarians(setup.options().contains("ragingBarbarians"));
		parameters.setOneCityChallenge(setup.options().contains("oneCity"));
		parameters.setNuclearWeaponsEnabled(!setup.options().contains("noNukes"));
		parameters.setEspionageEnabled(setup.options().contains("espionage"));
		parameters.setNoCityRazing(setup.options().contains("noRazing"));
		MapParameters map = new MapParameters();
		map.setShape(MapShape.rectangular);
		map.setType(mapType(setup.mapType()));
		map.setMapSize(switch (setup.mapSize()) {
			case "Tiny" -> MapSize.Companion.getTiny();
			case "Medium" -> MapSize.Companion.getMedium();
			case "Large" -> MapSize.Companion.getLarge();
			case "Huge" -> MapSize.Companion.getHuge();
			default -> MapSize.Companion.getSmall();
		});
		if (setup.seed() != 0) {
			map.setSeed(setup.seed());
		}
		// Every way to win that the rules know. Unciv leaves this list empty unless its own new-game screen
		// fills it, and with an empty list nobody can ever win.
		ArrayList<String> victories = new ArrayList<>(RulesetCache.INSTANCE.get(parameters.getBaseRuleset()).getVictories().keySet());
		if (!setup.victories().isEmpty()) {
			victories.retainAll(setup.victories());
		}
		if (victories.isEmpty()) {
			victories.add("Domination");
		}
		parameters.setVictoryTypes(victories);
		map.setNoRuins(setup.options().contains("noRuins"));
		return GameStarter.Companion.startNewGame(new GameSetupInfo(parameters, map));
	}

	/** How long a game takes: Quick is some 330 turns from the first city to the space age, Marathon 1500. */
	public static final List<String> SPEEDS = List.of("Quick", "Standard", "Epic", "Marathon");

	/** The kinds of map a new game can have, by the names Unciv gives them. */
	public static final List<String> MAP_TYPES = List.of("Pangaea", "Continent and Islands", "Two Continents", "Three Continents", "Small Continents",
		"Archipelago", "Inner Sea", "Lakes", "Fractal", "Perlin");

	/** Unciv's name for a map type however it was written in the settings ("continentAndIslands", "inner sea"); Pangaea when unknown. */
	static String mapType(String wanted) {
		String key = wanted.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
		for (String type : MAP_TYPES) {
			if (type.replace(" ", "").toLowerCase(java.util.Locale.ROOT).equals(key)) {
				return type;
			}
		}
		return MAP_TYPES.get(0);
	}

	/** Runs everything that is waiting, on the calling thread. For tests, which have no game thread. */
	public void pump() {
		for (Runnable job; (job = work.poll()) != null; ) {
			runJob(job);
		}
	}

	private void runJob(Runnable job) {
		long began = System.nanoTime();
		boolean changed = !(job instanceof Question);
		try {
			job.run();
		} catch (Throwable e) {
			log.accept("Living World: a request failed in the game engine: " + e + where(e));
		}
		// Whatever else is waiting belongs to the same moment: one picture for all of it.
		for (Runnable more; (more = work.poll()) != null; ) {
			changed |= !(more instanceof Question);
			try {
				more.run();
			} catch (Throwable e) {
				log.accept("Living World: a request failed in the game engine: " + e);
			}
		}
		flags();
		if (changed) {
			publish();
		}
		lastWorkMicros = (System.nanoTime() - began) / 1000;
	}

	private void execute(int civ, CommandTicket ticket) {
		GameCommand command = ticket.command();
		UncivActions.Result result;
		try {
			Seat seat = seat(civ);
			if (!command.gameId().equals(game.getGameId())) {
				result = UncivActions.Result.refused("That was asked in another game.");
			} else if (seat == null) {
				result = UncivActions.Result.refused("You rule no civilization in this game.");
			} else if (command.op() == nl.jordythomassen.livingworld.commands.CommandOp.END_TURN) {
				result = endTurn(seat, command);
			} else {
				flags();
				if (!seat.mayAct) {
					result = UncivActions.Result.refused(seat.ended ? "You have ended your turn; the others are still at theirs."
						: seat.waitsFor.isEmpty() ? "The others are still moving." : seat.waitsFor + " moves first: you are at war with them.");
				} else {
					acting(seat.civ);
					result = seat.actions.apply(command);
				}
			}
		} catch (Throwable e) {
			log.accept("Living World: " + command.op() + " failed in the game engine: " + e + where(e));
			result = UncivActions.Result.refused("The game could not do that (" + e.getClass().getSimpleName() + ").");
		}
		ticket.settle(result.done(), result.message(), result.before(), result.after(), game.getTurns());
		synchronized (ticketUpdates) {
			ticketUpdates.add(ticket);
		}
	}

	/** Unciv keeps one civilization as the one whose turn it is; with several people at once it is whoever acts. */
	private void acting(Civilization civ) {
		if (seats.size() > 1 && game.getCurrentPlayerCiv() != civ) {
			game.setCurrentPlayer(civ.getCivID());
			game.setCurrentPlayerCiv(civ);
		}
	}

	/** The civilization ruled by a person that this one must let move first: one it is at war with, that comes before it and is still at its turn. */
	private Seat movesFirst(Seat seat) {
		for (Seat other : seats) {
			if (other == seat) {
				break;
			}
			if (!other.ended && other.present && !other.leaving && !other.civ.isDefeated() && seat.civ.isAtWarWith(other.civ)) {
				return other;
			}
		}
		return null;
	}

	/** Who may act now, and for those who may not, why. Told to the Minecraft side as it stands after every request. */
	private void flags() {
		for (Seat seat : seats) {
			Seat first = seat.ended ? null : movesFirst(seat);
			seat.waitsFor = seat.ended ? "ended" : first == null ? "" : first.civ.getCivName();
			seat.mayAct = !seat.ended && first == null;
		}
	}

	private UncivActions.Result endTurn(Seat seat, GameCommand command) {
		if (command.turn() != game.getTurns()) {
			return UncivActions.Result.refused("That turn is already over.");
		}
		if (seat.ended) {
			return UncivActions.Result.refused("You have ended your turn; the others are still at theirs.");
		}
		Seat first = movesFirst(seat);
		if (first != null) {
			return UncivActions.Result.refused(first.civ.getCivName() + " moves first: you are at war with them.");
		}
		String waiting = UncivRules.blocking(seat.civ);
		if (!waiting.isEmpty() && !waiting.equals("UNITS")) {
			return UncivActions.Result.refused("A decision is needed first: " + switch (waiting) {
				case "POLICY" -> "adopt a social policy";
				case "RESEARCH" -> "choose what to research";
				case "PRODUCTION" -> "choose what a city builds";
				case "RELIGION" -> "choose what your people believe";
				case "VOTE" -> "cast your vote for a world leader";
				case "GREAT" -> "choose the Great Person you were given";
				default -> waiting.toLowerCase();
			} + ".");
		}
		if (seat.civ.isDefeated()) {
			return UncivActions.Result.refused("Your civilization has fallen. A new game can be started from the Codex.");
		}
		int before = game.getTurns();
		if (seats.size() == 1) {
			seat.mayAct = false;
			seat.waitsFor = "";
			try {
				String told = seat.actions.beforeEndOfTurn();
				if (told != null) {
					seat.news.add(told);
				}
				game.nextTurn(null, false);
			} finally {
				seat.mayAct = true;
			}
			seat.actions.newTurn();
			afterTurn();
			save();
			return UncivActions.Result.done("Turn ended.", before, game.getTurns());
		}
		seat.ended = true;
		if (!roundIsDue()) {
			flags();
			return UncivActions.Result.done("Turn ended. The others are still at theirs.", before, before);
		}
		round(false);
		return UncivActions.Result.done("Turn ended.", before, game.getTurns());
	}

	/** Whether everybody who is there has ended their turn (and somebody is there at all). */
	private boolean roundIsDue() {
		boolean anybody = false;
		for (Seat seat : seats) {
			if (seat.civ.isDefeated() || !seat.present || seat.leaving) {
				continue;
			}
			if (!seat.ended) {
				return false;
			}
			anybody = true;
		}
		return anybody;
	}

	/**
	 * A turn of the whole game when several people play: their turns end, the computer's
	 * civilizations play theirs, the count goes up and everybody's next turn begins. The same calls,
	 * in the same order per civilization, as Unciv's own next-turn makes; only that there the people
	 * come one after the other and here together.
	 *
	 * @param forAll the computer plays for everybody (a test aid)
	 */
	private void round(boolean forAll) {
		List<Seat> all = seats;
		for (Seat seat : all) {
			seat.mayAct = false;
			seat.waitsFor = "";
		}
		var settings = UncivGame.Companion.getCurrent().getSettings();
		boolean governing = settings.getAutoAssignCityProduction();
		for (Seat seat : all) {
			if (seat.civ.isDefeated()) {
				continue;
			}
			acting(seat.civ);
			if (forAll || !seat.present || seat.leaving) {
				// Nobody is there to rule it this turn: the computer does, the way it rules its own.
				settings.setAutoAssignCityProduction(true);
				try {
					seat.actions.governorsChoose();
					new TurnManager(seat.civ).automateTurn();
				} catch (RuntimeException e) {
					log.accept("Living World: playing for " + seat.civ.getCivName() + " failed: " + e + where(e));
				} finally {
					settings.setAutoAssignCityProduction(governing);
				}
			}
			String told = seat.actions.beforeEndOfTurn();
			if (told != null) {
				seat.news.add(told);
			}
			new TurnManager(seat.civ).endTurn(null);
		}
		for (Civilization civ : new ArrayList<>(game.getCivilizations())) {
			Seat seat = seat(snapshot.civId(civ));
			if ((seat != null && !civ.isDefeated()) || civ.isSpectator()) {
				continue;
			}
			TurnManager turn = new TurnManager(civ);
			turn.startTurn(null);
			turn.automateTurn();
			turn.endTurn(null);
		}
		hidden("recordRankingStats");
		game.setTurns(game.getTurns() + 1);
		List<Seat> staying = new ArrayList<>();
		for (Seat seat : all) {
			if (seat.leaving) {
				// From here on the computer has it, as it has the others of its own.
				seat.civ.setPlayerType(PlayerType.AI);
				continue;
			}
			staying.add(seat);
			seat.ended = false;
			if (seat.civ.isDefeated()) {
				continue;
			}
			acting(seat.civ);
			new TurnManager(seat.civ).startTurn(null);
			hidden("notifyOfCloseEnemyUnits", seat.civ);
			seat.actions.newTurn();
		}
		if (staying.size() != all.size() && !staying.isEmpty()) {
			seats = List.copyOf(staying);
			viewer = staying.get(0).civ;
			actions = staying.get(0).actions;
		}
		acting(seats.get(0).civ);
		afterTurn();
		save();
		flags();
	}

	/** Two steps of Unciv's own next-turn that it keeps to itself. */
	private void hidden(String name, Object... arguments) {
		try {
			for (java.lang.reflect.Method method : GameInfo.class.getDeclaredMethods()) {
				if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
					method.setAccessible(true);
					method.invoke(game, arguments);
					return;
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			log.accept("Living World: " + name + " failed: " + e);
		}
	}

	/** What a turn can bring that Unciv would show on a screen of its own: the end of the game, and offers that want an answer. */
	private void afterTurn() {
		boolean won = game.getVictoryData() != null || game.checkForVictory();
		for (Seat seat : seats) {
			if (!seat.outcomeTold && seat.civ.isDefeated()) {
				seat.outcomeTold = true;
				seat.news.add("Your civilization has fallen. A new game can be started from the Codex.");
			} else if (!seat.outcomeTold && won && game.getVictoryData() != null) {
				seat.outcomeTold = true;
				Civilization winner = game.getCivilization(game.getVictoryData().getWinningCiv());
				String how = game.getVictoryData().getVictoryType().toLowerCase(java.util.Locale.ROOT);
				seat.news.add(winner == seat.civ ? "You have won the game: a " + how + " victory. You can play on, or start a new game from the Codex."
					: winner.getCivName() + " has won the game: a " + how + " victory. You can play on, or start a new game from the Codex.");
			}
			int waiting = seat.actions.diplomacy().proposals().size();
			if (waiting > 0) {
				seat.news.add(waiting == 1 ? "Another civilization has put something to you. Your Codex has it under Proposals."
					: waiting + " proposals of other civilizations wait for your answer. Your Codex has them under Proposals.");
			}
		}
	}

	// ---- who rules what ----

	@Override
	public void rule(int civId, boolean person, Consumer<String> answer) {
		work.add(() -> {
			String refusal;
			try {
				refusal = person ? take(snapshot.civ(civId)) : giveUp(seat(civId));
			} catch (RuntimeException e) {
				log.accept("Living World: changing who rules a civilization failed: " + e + where(e));
				refusal = "The game could not do that.";
			}
			answer.accept(refusal);
		});
	}

	private String take(Civilization civ) {
		if (civ == null || !civ.isMajorCiv() || civ.isDefeated() || civ.isSpectator()) {
			return "That civilization cannot be ruled.";
		}
		Seat had = seat(snapshot.civId(civ));
		if (had != null) {
			// Somebody ruled it before and was on their way out, or is back.
			had.leaving = false;
			had.present = true;
			return "";
		}
		if (!civ.isAI()) {
			return "That civilization cannot be ruled.";
		}
		civ.setPlayerType(PlayerType.Human);
		Seat seat = new Seat(civ);
		// The computer has played its turn of this round already; the newcomer's begins here.
		acting(civ);
		new TurnManager(civ).startTurn(null);
		List<Seat> all = new ArrayList<>(seats);
		all.add(seat);
		all.sort(java.util.Comparator.comparingInt(one -> one.id));
		seats = List.copyOf(all);
		viewer = all.get(0).civ;
		actions = all.get(0).actions;
		acting(viewer);
		save();
		log.accept("Living World: " + civ.getCivName() + " is ruled by a person from now on (" + all.size() + " people play)");
		return "";
	}

	private String giveUp(Seat seat) {
		if (seat == null) {
			return "Nobody rules that civilization.";
		}
		long staying = seats.stream().filter(one -> !one.leaving).count();
		if (staying <= 1) {
			return "Somebody has to rule: the last civilization of a person stays theirs.";
		}
		seat.leaving = true;
		if (roundIsDue()) {
			round(false);
		}
		return "";
	}

	@Override
	public void present(int civId, boolean here) {
		work.add(() -> {
			Seat seat = seat(civId);
			if (seat == null || seat.present == here) {
				return;
			}
			seat.present = here;
			// Somebody the others were waiting for has gone: the round need not wait for them.
			if (!here && seats.size() > 1 && roundIsDue()) {
				round(false);
			}
		});
	}

	/**
	 * Every civilization a person could rule: the group says who does now (0 the computer, 1 a
	 * person who is there, 2 a person who is away), the id is its number, the name its name, the
	 * type "leader|1 when they ended their turn|score", the last number its cities.
	 */
	private List<Records.Option> seatList() {
		List<Records.Option> out = new ArrayList<>();
		if (game == null) {
			return out;
		}
		for (Civilization civ : game.getCivilizations()) {
			if (!civ.isMajorCiv() || civ.isSpectator() || civ.isDefeated()) {
				continue;
			}
			Seat seat = seat(snapshot.civId(civ));
			int who = seat == null || seat.leaving ? 0 : seat.present ? 1 : 2;
			out.add(new Records.Option(who, snapshot.civId(civ), civ.getNation().getLeaderName() + "|" + (seat != null && seat.ended ? 1 : 0) + "|" + Math.round(civ.calculateTotalScore()),
				civ.getCivName(), civ.getCities().size()));
		}
		return out;
	}

	/**
	 * The civilizations a game can be played with, before there is one: the type is the name, the
	 * group and id its two colours, and the name what is special about it, in lines a screen can lay out:
	 * the leader first, then "Gift: name", "- " before each thing the gift does, and "Unit: ", "Building: " or
	 * "Improvement: " before what only they have (the name, a bar, what it stands in for, a bar, what it does).
	 */
	private List<Records.Option> nations() {
		List<Records.Option> out = new ArrayList<>();
		var rules = RulesetCache.INSTANCE.get(new GameParameters().getBaseRuleset());
		if (rules == null) {
			return out;
		}
		for (var nation : rules.getNations().values()) {
			if (!nation.isMajorCiv()) {
				continue;
			}
			StringBuilder text = new StringBuilder(nation.getLeaderName());
			text.append("\nGift: ").append(nation.getUniqueName().isEmpty() ? "Their own way" : words(nation.getUniqueName()));
			String gift = nation.getUniqueText().isEmpty() ? UncivInfo.rules(nation.getUniques()) : words(nation.getUniqueText());
			for (String line : gift.split("\n")) {
				if (!line.isEmpty()) {
					text.append("\n- ").append(line);
				}
			}
			for (var unit : rules.getUnits().values()) {
				if (nation.getName().equals(unit.getUniqueTo())) {
					var old = unit.getReplaces() == null ? null : rules.getUnits().get(unit.getReplaces());
					List<String> own = new ArrayList<>();
					for (String unique : unit.getUniques()) {
						if (old == null || !old.getUniques().contains(unique)) {
							own.add(unique);
						}
					}
					String does = UncivInfo.rules(own).replace("\n", "; ");
					text.append("\nUnit: ").append(words(unit.getName())).append('|').append(unit.getReplaces() == null ? "" : words(unit.getReplaces())).append('|')
						.append(UncivInfo.numbers(unit).replaceAll("\\s{2,}", ", ")).append(does.isEmpty() ? "" : "; " + does);
				}
			}
			for (var building : rules.getBuildings().values()) {
				if (nation.getName().equals(building.getUniqueTo())) {
					String does;
					try {
						does = UncivInfo.lines(building.getShortDescription(true, unique -> true)).replace("\n", "; ");
					} catch (Throwable e) {
						does = UncivInfo.rules(building.getUniques()).replace("\n", "; ");
					}
					text.append("\nBuilding: ").append(words(building.getName())).append('|').append(building.getReplaces() == null ? "" : words(building.getReplaces())).append('|').append(does);
				}
			}
			for (var improvement : rules.getTileImprovements().values()) {
				if (nation.getName().equals(improvement.getUniqueTo())) {
					String gives = words(improvement.cloneStats().toString());
					String does = UncivInfo.rules(improvement.getUniques()).replace("\n", "; ");
					text.append("\nImprovement: ").append(words(improvement.getName())).append("||").append(gives).append(gives.isEmpty() || does.isEmpty() ? "" : "; ").append(does);
				}
			}
			out.add(new Records.Option(Names.color(nation, true), Names.color(nation, false), nation.getName(), text.toString(), 0));
		}
		return out;
	}

	/**
	 * Lets the computer play the player's civilization for a number of turns, the way it plays
	 * the others. A test aid: it brings a game to a later age in seconds.
	 */
	public void simulate(int turns) {
		work.add(() -> {
			var settings = UncivGame.Companion.getCurrent().getSettings();
			boolean governors = settings.getAutoAssignCityProduction();
			settings.setAutoAssignCityProduction(true);
			try {
				for (int i = 0; i < turns && !viewer.isDefeated(); i++) {
					if (seats.size() > 1) {
						round(true);
						continue;
					}
					actions.governorsChoose();
					new TurnManager(viewer).automateTurn();
					game.nextTurn(null, false);
				}
			} finally {
				settings.setAutoAssignCityProduction(governors);
			}
			afterTurn();
			save();
			log.accept("Living World: the computer played " + turns + " turns for the player; it is turn " + game.getTurns());
		});
	}

	/** Runs something on the game's own thread, after which a fresh picture is published. For test aids that set a scene. */
	public void onGameThread(Runnable job) {
		work.add(job);
	}

	/** Whether the player's cities choose their own work when they have none (true), or wait to be told (false). */
	public void setGovernors(boolean on) {
		governors = on;
		work.add(() -> {
			UncivGame.Companion.getCurrent().getSettings().setAutoAssignCityProduction(on);
			if (on) {
				actions.governorsChoose();
			}
		});
	}

	/** A fresh picture, and what changed since the last one. */
	private void publish() {
		List<Seat> all = seats;
		for (Seat seat : all) {
			seat.actions.diplomacy().putNewsAway();
		}
		WorldState before = state;
		Seat first = all.get(0);
		List<WorldEvent> changes = new ArrayList<>();
		WorldState after;
		if (all.size() == 1) {
			after = snapshot.take(first.civ);
			first.state = after;
			changes.addAll(told(first, before, after));
		} else {
			// The world as it is built: what any of the people knows of it. What each is shown and told is their own.
			List<Civilization> eyes = new ArrayList<>();
			for (Seat seat : all) {
				eyes.add(seat.civ);
			}
			after = snapshot.take(first.civ, eyes);
			if (before == null) {
				changes.add(new WorldEvent.SessionStarted(after.session()));
			} else {
				for (WorldEvent change : WorldDiff.between(before, after)) {
					// What is built and who stands where; the news of it is each civilization's own.
					if (!(change instanceof WorldEvent.WarChanged) && !(change instanceof WorldEvent.TechsLearned) && !(change instanceof WorldEvent.EraChanged)) {
						changes.add(change);
					}
				}
			}
			for (Seat seat : all) {
				WorldState mine = snapshot.take(seat.civ);
				List<WorldEvent> own = told(seat, seat.state, mine);
				seat.state = mine;
				if (!own.isEmpty()) {
					synchronized (seat.events) {
						seat.events.addAll(own);
					}
				}
			}
		}
		state = after;
		if (!changes.isEmpty()) {
			synchronized (events) {
				events.addAll(changes);
			}
		}
	}

	/** What changed in one civilization's picture, with what the game tells it in words. */
	private List<WorldEvent> told(Seat seat, WorldState before, WorldState after) {
		List<WorldEvent> changes = new ArrayList<>();
		if (before == null) {
			changes.add(new WorldEvent.SessionStarted(after.session()));
		} else {
			changes.addAll(WorldDiff.between(before, after));
		}
		for (Notification notification : new ArrayList<>(seat.civ.getNotifications())) {
			// News of having gained nothing ("Received [0] Gold ...", as when a city is given back) is no news.
			if (seat.told.put(notification, true) == null && !notification.getText().startsWith("Received [0] ")) {
				changes.add(notice(seat.civ, notification));
			}
		}
		for (String text : seat.news) {
			changes.add(new WorldEvent.Notice(text, null));
		}
		seat.news.clear();
		if (seat.told.size() > 2000) {
			seat.told.keySet().retainAll(new java.util.HashSet<>(seat.civ.getNotifications()));
		}
		return changes;
	}

	/** A piece of the game's news, with where it happened and what sort of news it is, as far as the game says. */
	private WorldEvent.Notice notice(Civilization civ, Notification notification) {
		String kind = switch (notification.getCategory()) {
			case Production -> "build";
			case Cities -> "city";
			case War -> "war";
			case Diplomacy -> "talk";
			case Trade -> "trade";
			case Units -> "unit";
			case Religion -> "faith";
			case Espionage -> "spy";
			default -> "news";
		};
		nl.jordythomassen.livingworld.coords.PlotCoord at = null;
		try {
			for (var action : notification.getActions()) {
				String name = action.getClass().getSimpleName();
				if (name.equals("TechAction")) {
					kind = "tech";
				} else if (name.equals("PolicyAction")) {
					kind = "policy";
				} else if (name.equals("DiplomacyAction") && kind.equals("news")) {
					kind = "talk";
				}
				String field = name.equals("LocationAction") || name.equals("MapUnitAction") ? "location" : name.equals("CityAction") ? "city" : null;
				com.unciv.logic.map.HexCoord hex = field == null || at != null ? null : Kt.field(action, field, com.unciv.logic.map.HexCoord.class);
				if (hex != null) {
					var tile = civ.getGameInfo().getTileMap().getOrNull(hex.getX(), hex.getY());
					at = tile == null || snapshot == null ? null : snapshot.map().plot(tile);
				}
			}
		} catch (Throwable e) {
			// News without a place is still news.
		}
		return new WorldEvent.Notice(words(notification.getText()), at, kind);
	}

	/** Where in Unciv something went wrong: the first few frames, for the log. */
	static String where(Throwable e) {
		StringBuilder out = new StringBuilder();
		StackTraceElement[] trace = e.getStackTrace();
		for (int i = 0; i < Math.min(6, trace.length); i++) {
			out.append("\n    at ").append(trace[i]);
		}
		return out.toString();
	}

	/** Unciv's texts carry their fill-ins in square brackets ("[Warrior] was destroyed"); plain words for the player. */
	static String words(String text) {
		String out;
		try {
			// (A note for whoever writes rule files, kept in the rule itself, is not for players.)
			out = TranslationsKt.tr(text.replaceAll("\\s*<Suppress warning \\[[^\\]]*\\]>", ""), true, false);
		} catch (Throwable e) {
			out = text;
		}
		// Brackets around fill-ins, and the little pictures Unciv's own font has for gold, death and so on.
		StringBuilder plain = new StringBuilder(out.length());
		for (int i = 0; i < out.length(); i++) {
			char c = out.charAt(i);
			// (The little picture for gold is the currency sign, which sits lower in the alphabet than the others.)
			if (c == '[' || c == ']' || c == '{' || c == '}' || c == '\u00A4' || c >= 0x2000) {
				continue;
			}
			plain.append(c);
		}
		return plain.toString().replace("()", "").replaceAll("\\s+", " ").trim();
	}

	private void save() {
		if (game == null) {
			return;
		}
		try {
			String text = UncivFiles.Companion.gameInfoToString(game, Boolean.TRUE, false);
			Path temp = saveFile.resolveSibling(saveFile.getFileName() + ".tmp");
			Files.createDirectories(saveFile.toAbsolutePath().getParent());
			Files.writeString(temp, text, StandardCharsets.UTF_8);
			Files.move(temp, saveFile, StandardCopyOption.REPLACE_EXISTING);
		} catch (Throwable e) {
			log.accept("Living World: could not save the game: " + e);
		}
	}

	/** For tests and tools on the game thread: the game itself. */
	public GameInfo game() {
		return game;
	}

	public Civilization viewer() {
		return viewer;
	}
}
