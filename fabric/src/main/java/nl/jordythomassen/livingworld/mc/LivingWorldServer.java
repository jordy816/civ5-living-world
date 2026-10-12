package nl.jordythomassen.livingworld.mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import nl.jordythomassen.livingworld.board.BoardModel;
import nl.jordythomassen.livingworld.commands.CommandOp;
import nl.jordythomassen.livingworld.commands.CommandRules;
import nl.jordythomassen.livingworld.commands.CommandStatus;
import nl.jordythomassen.livingworld.commands.CommandTicket;
import nl.jordythomassen.livingworld.commands.GameCommand;
import nl.jordythomassen.livingworld.coords.GridPos;
import nl.jordythomassen.livingworld.coords.McLayout;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.link.CivLink;
import nl.jordythomassen.livingworld.link.GameLink;
import nl.jordythomassen.livingworld.link.LinkStatus;
import nl.jordythomassen.livingworld.link.PlayerMarker;
import nl.jordythomassen.livingworld.mock.MockCiv;
import nl.jordythomassen.livingworld.mock.MockTransport;
import nl.jordythomassen.livingworld.script.CivScript;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.Legend;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldEvent;
import nl.jordythomassen.livingworld.state.WorldState;
import nl.jordythomassen.livingworld.tuner.TunerTransport;

/**
 * The Minecraft half of one play session: keeps the board, the characters and the status display
 * in line with what the link reports from Civilization V, and turns what the player does at a
 * unit or a city into validated commands.
 *
 * <p>Everything here runs on the server thread. The link to Civ runs on its own thread and is
 * only read from here, so neither game waits on the other.
 */
final class LivingWorldServer {
	/** Hammers a city gets per log delivered, and the most logs one delivery takes. */
	static final int HAMMERS_PER_LOG = 2;
	static final int MAX_LOGS = CommandRules.MAX_PRODUCTION / HAMMERS_PER_LOG;
	/** Hit points a unit regains per bread, and the most bread one treatment takes. */
	static final int HP_PER_BREAD = 10;
	static final int MAX_BREAD = CommandRules.MAX_HEAL / HP_PER_BREAD;

	/** @param quiet no chat about the outcome (things that happen by themselves as the player walks) */
	private record Refund(UUID player, ServerPlayer asked, ItemStack items, String what, boolean quiet) {
	}

	/** One Civ turn as a Minecraft day: ten minutes from sunrise to sunset. */
	static final int DAY_TICKS = 12_000;

	final MinecraftServer server;
	final ModConfig config;
	GameLink link;
	/** The game runs inside this mod (true), or a running Civilization V is shown and steered (false). */
	final boolean builtin;
	final BoardPainter painter = new BoardPainter();
	final Proxies proxies = new Proxies();
	final Actions actions = new Actions();
	final Menus menus = new Menus(actions);
	private Thread thread;
	private final MockCiv mock;
	private final ServerBossEvent bar;
	private final Map<String, Refund> refunds = new HashMap<>();
	private final Map<UUID, String> lastPlotLine = new HashMap<>();
	private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> inbox = new java.util.concurrent.ConcurrentLinkedQueue<>();
	final Gameplay gameplay = new Gameplay(this);
	final Codex codex = new Codex(this);
	final Rts rts = new Rts(this);
	/** Who rules which civilization, when several people each have their own. */
	final Seats seats = new Seats(this);
	/** The table a game is set up at, and the door of one that runs. */
	final Lobby lobby;
	/** The player on whose behalf the server is doing something right now: what they see is their own civilization's picture. */
	private ServerPlayer acting;
	/** The unit each player has picked to command: the next hex they point at with the Codex is where it goes. */
	private final Map<UUID, EntityKey> selected = new HashMap<>();
	/** The tick in which a player's click was last answered, so one click is not answered twice. */
	private final Map<UUID, Integer> lastUse = new HashMap<>();
	private int dayTicks;
	private boolean clockRuns;
	/** The sun stands at noon because somebody rules from above. */
	private boolean noonHeld;
	private WorldState applied;
	private BoardModel model;
	private boolean follow;
	// The rules of this world a player may switch in the Codex; they start as the settings file has them.
	private boolean dayIsTurn;
	private boolean techGate;
	private boolean governors;
	boolean explore;
	private long commandCounter;
	private int tick;
	private LinkStatus shownStatus;
	private final SelfTest selfTest;
	private final EngineSelfTest engineSelfTest;
	private final DebugDriver debug = Boolean.getBoolean("livingworld.debug") ? new DebugDriver(this) : null;

	LivingWorldServer(MinecraftServer server, ModConfig config) {
		this.server = server;
		this.config = config;
		this.lobby = new Lobby(this);
		this.follow = config.follow;
		this.dayIsTurn = config.dayIsTurn;
		this.techGate = config.techGate;
		// In the game that runs inside Minecraft the land is found by the units, turn by turn; a ruler on foot finds nothing.
		this.explore = config.explore && !(config.builtin && !config.mock);
		this.governors = config.governors;
		builtin = config.builtin && !config.mock;
		boolean testing = Boolean.getBoolean("livingworld.selftest");
		selfTest = testing && !builtin ? new SelfTest(this) : null;
		engineSelfTest = testing && builtin ? new EngineSelfTest(this) : null;
		if (builtin) {
			mock = null;
			// A world without a game waits for whoever comes in to say what it should be; test runs start one at once.
			link = engine(config.autoStart || Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("livingworld-civ.json"))
				? configured() : null);
		} else if (config.mock) {
			mock = new MockCiv(40, 25, 7);
			MockTransport transport = new MockTransport(mock);
			link = new CivLink(() -> transport, CivScript::source, LivingWorldMod.LOG::info);
		} else {
			mock = null;
			link = new CivLink(() -> TunerTransport.connect(config.civHost, config.civPort, CivScript.STATE, 2_000), CivScript::source,
				LivingWorldMod.LOG::info);
		}
		thread = new Thread(link, "living-world-link");
		thread.setDaemon(true);
		// Seen from above a figure of man's height is a speck: the pieces of the built-in game stand as tall as a house.
		proxies.unitScale = builtin && config.rts ? Looks.UNIT_SCALE : 1.0;
		proxies.creatures = builtin;
		menus.panels = player -> rts.isAbove(player) && rts.speaks(player);
		proxies.above = rts::isAbove;
		bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("Living World"), BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS);
		bar.setProgress(1.0f);
	}

	/** The game the settings file describes. */
	nl.jordythomassen.livingworld.unciv.UncivLink.Setup configured() {
		// With spies, as a game set up at the table has them unless told otherwise.
		return new nl.jordythomassen.livingworld.unciv.UncivLink.Setup(config.mapSize, config.mapType, config.rivals, config.cityStates, config.difficulty, config.nation,
			config.seed, config.speed, List.of(), java.util.Set.of("espionage"), List.of());
	}

	/** Whether the built-in game waits to be told what it should be. */
	boolean inLobby() {
		return builtin && link instanceof nl.jordythomassen.livingworld.unciv.UncivLink engine && engine.waiting();
	}

	/** Starts the game that was waited for. */
	void begin(nl.jordythomassen.livingworld.unciv.UncivLink.Setup setup) {
		if (link instanceof nl.jordythomassen.livingworld.unciv.UncivLink engine) {
			engine.begin(setup);
		}
	}

	private Path gameFile() {
		return server.getWorldPath(LevelResource.ROOT).resolve("livingworld-civ.json");
	}

	private GameLink engine(nl.jordythomassen.livingworld.unciv.UncivLink.Setup setup) {
		Path world = server.getWorldPath(LevelResource.ROOT);
		nl.jordythomassen.livingworld.unciv.UncivLink engine =
			new nl.jordythomassen.livingworld.unciv.UncivLink(world.resolve("livingworld-engine"), gameFile(), setup, LivingWorldMod.LOG::info);
		engine.setGovernors(governors);
		return engine;
	}

	/**
	 * Ends the game being played and starts another in this world. The old game is kept beside it
	 * (livingworld-civ.previous.json) in case that was a mistake.
	 */
	void startNewGame(nl.jordythomassen.livingworld.unciv.UncivLink.Setup setup) {
		if (!builtin) {
			return;
		}
		link.stop();
		try {
			thread.join(10_000);
			if (Files.exists(gameFile())) {
				Files.move(gameFile(), gameFile().resolveSibling("livingworld-civ.previous.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			LivingWorldMod.LOG.warn("Living World: could not set the old game aside", e);
		}
		selected.clear();
		reach.clear();
		outline.clear();
		outlined.clear();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			showMarks(player, Map.of());
		}
		refunds.clear();
		seats.clear();
		link = engine(setup);
		thread = new Thread(link, "living-world-link");
		thread.setDaemon(true);
		thread.start();
		announce("A new game begins.", ChatFormatting.GOLD);
	}

	private Path keptDir() {
		return server.getWorldPath(LevelResource.ROOT).resolve("livingworld-kept");
	}

	/** Keeps a copy of the game as it stands, named by its turn, its civilization and the time; who rules what goes with it. */
	void keepGame(ServerPlayer player) {
		WorldState state = applied;
		if (!(link instanceof nl.jordythomassen.livingworld.unciv.UncivLink engine) || state == null) {
			say(player, "There is no game to keep.");
			return;
		}
		CivInfo civ = state.civ(state.session().viewer());
		String name = ("Turn " + state.turn() + " " + (civ == null ? "" : civ.name()) + " " + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm")))
			.replaceAll("[^A-Za-z0-9 .\\-]", "").trim();
		Path target = keptDir().resolve(name + ".json");
		UUID id = player.getUUID();
		engine.keep(target, done -> later(() -> {
			try {
				Path seatsFile = server.getWorldPath(LevelResource.ROOT).resolve("livingworld-seats.txt");
				if (done && Files.exists(seatsFile)) {
					Files.copy(seatsFile, keptDir().resolve(name + ".seats.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException e) {
				LivingWorldMod.LOG.warn("Living World: could not keep who rules what", e);
			}
			ServerPlayer who = server.getPlayerList().getPlayer(id);
			if (who != null) {
				say(who, done ? "Kept as \"" + name + "\". The settings have the way back to it." : "The game could not be kept.");
				rts.tell(who, done ? "Kept: " + name : "The game could not be kept.");
			}
			LivingWorldMod.LOG.info("Living World: {} a copy of the game as {}", done ? "kept" : "could not keep", target.getFileName());
		}));
	}

	/** The games kept in this world, the latest first. */
	List<Path> keptGames() {
		List<Path> out = new java.util.ArrayList<>();
		if (Files.isDirectory(keptDir())) {
			try (var files = Files.list(keptDir())) {
				files.filter(f -> f.getFileName().toString().endsWith(".json")).forEach(out::add);
			} catch (IOException e) {
				LivingWorldMod.LOG.warn("Living World: could not list the kept games", e);
			}
		}
		out.sort((one, other) -> Long.compare(other.toFile().lastModified(), one.toFile().lastModified()));
		return out;
	}

	/** Goes back to a kept game: the one being played is set aside, the kept one takes its place, and everybody finds their civilization again. */
	void loadKept(ServerPlayer player, Path file) {
		if (!builtin || !lobby.keeps(player) || !Files.exists(file) || !file.toAbsolutePath().startsWith(keptDir().toAbsolutePath())) {
			say(player, "That game cannot be gone back to.");
			return;
		}
		link.stop();
		try {
			thread.join(10_000);
			Path world = server.getWorldPath(LevelResource.ROOT);
			if (Files.exists(gameFile())) {
				Files.move(gameFile(), gameFile().resolveSibling("livingworld-civ.previous.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			Files.copy(file, gameFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			Path seatsKept = file.resolveSibling(file.getFileName().toString().replace(".json", ".seats.txt"));
			if (Files.exists(seatsKept)) {
				Files.copy(seatsKept, world.resolve("livingworld-seats.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			LivingWorldMod.LOG.warn("Living World: could not put the kept game in place", e);
		}
		selected.clear();
		reach.clear();
		outline.clear();
		outlined.clear();
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			showMarks(other, Map.of());
		}
		refunds.clear();
		seats.clear();
		link = engine(configured());
		thread = new Thread(link, "living-world-link");
		thread.setDaemon(true);
		thread.start();
		announce("Back to a kept game: " + file.getFileName().toString().replace(".json", "") + ".", ChatFormatting.GOLD);
	}

	void start() {
		// The board is a display of Civ's world, not a survival world: nothing grows, melts, burns or spawns.
		GameRules rules = server.overworld().getGameRules();
		rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
		rules.set(GameRules.ADVANCE_TIME, false, server);
		clockRuns = false;
		rules.set(GameRules.ADVANCE_WEATHER, false, server);
		rules.set(GameRules.SPAWN_MOBS, false, server);
		rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
		rules.set(GameRules.SPAWN_PATROLS, false, server);
		rules.set(GameRules.KEEP_INVENTORY, true, server);
		rules.set(GameRules.FALL_DAMAGE, false, server);
		rules.set(GameRules.SPAWN_MONSTERS, false, server);
		rules.set(GameRules.SPAWN_PHANTOMS, false, server);
		rules.set(GameRules.SPAWN_WARDENS, false, server);
		rules.set(GameRules.RAIDS, false, server);
		// Nothing that walks the board changes it: no creature picks up, tramples or leaves anything.
		rules.set(GameRules.MOB_GRIEFING, false, server);
		// The units of the built-in game are Minecraft's own creatures, zombies and creepers among them, and those cannot
		// exist in a peaceful world. Nothing spawns by itself (the rules above) and none of them has a will of its own.
		server.setDifficulty(builtin ? Difficulty.EASY : Difficulty.PEACEFUL, true);
		run("time set noon");
		run("weather clear");
		thread.start();
		LivingWorldMod.LOG.info("Living World: started ({}, hex width {} blocks)", builtin ? "the game runs inside Minecraft" : config.mock ? "pretend Civilization V"
			: "Civilization V at " + config.civHost + ":" + config.civPort, config.hexWidth);
	}

	void stop() {
		savePainted();
		link.stop();
		if (builtin) {
			// The game is written to the world folder as its thread ends: wait for that.
			try {
				thread.join(10_000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		} else {
			thread.interrupt();
		}
		bar.removeAllPlayers();
	}

	private void run(String command) {
		try {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
		} catch (RuntimeException e) {
			LivingWorldMod.LOG.warn("Living World: '{}' failed", command, e);
		}
	}

	BoardModel model() {
		return model;
	}

	/** The picture of the game: that of the player the server is acting for, else the world as it is built. */
	WorldState state() {
		return acting == null ? applied : stateOf(acting);
	}

	/** The world as it is built in Minecraft: with several people, what any of them knows of it. */
	WorldState board() {
		return applied;
	}

	/** The picture of one player's own civilization. With one civilization for everybody that is the built world itself. */
	WorldState stateOf(ServerPlayer player) {
		if (applied == null || !seats.several()) {
			return applied;
		}
		int civ = seats.of(player);
		WorldState own = civ < 0 ? null : link.state(civ);
		return own == null || !own.session().gameId().equals(applied.session().gameId()) ? applied : own;
	}

	/** Runs something on the server thread with the next tick: for answers that arrive on the game's own thread. */
	void later(Runnable job) {
		inbox.add(job);
	}

	void announceAll(String text) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.sendSystemMessage(Component.literal("[Civ] " + text).withStyle(ChatFormatting.GOLD));
			rts.tell(player, text);
		}
	}

	/** A player's place in the game changed (a civilization taken, given up, shared): their screen shows the game as theirs now. */
	void seated(ServerPlayer player) {
		if (Rts.speaks(player)) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, new Net.Choices("lobby:close", 0, List.of()));
		}
		deselect(player);
		welcome(player, stateOf(player));
		rts.seated(player);
	}

	/** Does something on a player's behalf: while it runs, {@link #state()} is their picture. */
	void as(ServerPlayer player, Runnable job) {
		ServerPlayer before = acting;
		acting = player;
		try {
			job.run();
		} finally {
			acting = before;
		}
	}

	boolean asking(ServerPlayer player, java.util.function.BooleanSupplier job) {
		ServerPlayer before = acting;
		acting = player;
		try {
			return job.getAsBoolean();
		} finally {
			acting = before;
		}
	}

	boolean follow() {
		return follow;
	}

	void setFollow(boolean value) {
		follow = value;
	}

	boolean rule(String key) {
		return switch (key) {
			case "follow" -> follow;
			case "dayIsTurn" -> dayIsTurn;
			case "techGate" -> techGate;
			case "explore" -> explore;
			case "governors" -> governors;
			default -> false;
		};
	}

	/** Switches one of the world's rules, now and (through the settings file) for next time. */
	void setRule(String key, boolean value) {
		switch (key) {
			case "follow" -> follow = value;
			case "techGate" -> techGate = value;
			case "explore" -> explore = value;
			case "governors" -> {
				governors = value;
				if (link instanceof nl.jordythomassen.livingworld.unciv.UncivLink engine) {
					engine.setGovernors(value);
				}
			}
			case "dayIsTurn" -> {
				dayIsTurn = value;
				dayTicks = 0;
				if (value) {
					// The day starts now.
					dawn();
				} else {
					setClock(false);
					run("time set noon");
				}
			}
			default -> {
				return;
			}
		}
		ModConfig.save(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir(), key, Boolean.toString(value));
	}

	MockCiv mock() {
		return mock;
	}

	// ---- every tick ----

	void tick() {
		tick++;
		ServerLevel level = server.overworld();
		for (Runnable job; (job = inbox.poll()) != null; ) {
			job.run();
		}
		if (newsRetryAt >= 0 && tick >= newsRetryAt) {
			newsRetryAt = -1;
			ServerPlayer again = server.getPlayerList().getPlayer(newsRetryFor);
			if (again != null) {
				as(again, () -> endTurn(again, newsRetryWhat));
			}
		}
		if (mock != null && !dayIsTurn && tick % 600 == 0 && selfTest == null) {
			// The pretend game plays a turn every half minute so there is something to watch.
			synchronized (mock) {
				mock.endTurn();
			}
		}
		if (tick % 6000 == 0) {
			savePainted();
		}
		WorldState state = link.state();
		List<WorldEvent> events = link.drainEvents();
		if (state != null && state != applied) {
			boolean fresh = applied == null || !applied.session().gameId().equals(state.session().gameId())
				|| !applied.session().bootId().equals(state.session().bootId());
			boolean otherMap = applied == null || !applied.session().gameId().equals(state.session().gameId());
			model = new BoardModel(state, layoutFor(state));
			painter.setModel(model, otherMap, level);
			if (otherMap) {
				proxies.clear(level);
			}
			boolean several = link.seats().size() > 1;
			// With several civilizations of people the built world changes for all, and the news of it is each one's own (below).
			audience = several ? List.of() : null;
			audienceCiv = -1;
			for (WorldEvent event : events) {
				react(level, event, fresh);
			}
			audience = null;
			proxies.sync(level, state, model);
			WorldState before = applied;
			applied = state;
			if (fresh) {
				// Also the same game opened again (a kept copy of it): who rules what is read back.
				seats.gameChanged(state);
				lobby.gameStarted();
			}
			if (otherMap) {
				newGame(level, state, before == null);
			} else if (fresh && before != null) {
				// The same map as it stood at another time: everybody gets the game as it is theirs now.
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					inbox.add(() -> seated(player));
				}
			}
			if (before != null && before.turn() != state.turn()) {
				newTurn(level, state);
			}
			rts.stateChanged();
		}
		if (applied != null) {
			for (int civ : link.seats()) {
				List<WorldEvent> own = link.drainEvents(civ);
				if (own.isEmpty()) {
					continue;
				}
				audience = seats.rulersOf(civ);
				audienceCiv = civ;
				painting = false;
				try {
					for (WorldEvent event : own) {
						if (!(event instanceof WorldEvent.SessionStarted)) {
							react(level, event, false);
						}
					}
				} finally {
					audience = null;
					audienceCiv = -1;
					painting = true;
				}
			}
		}
		seats.tick();
		rts.tick();
		dayCycle(level);
		painter.hurry = rts.anyoneAbove();
		painter.tick(level);
		proxies.tick(level);
		tickets();
		players(level);
		status();
		if (engineSelfTest != null) {
			engineSelfTest.tick();
		}
		if (selfTest != null) {
			selfTest.tick();
		}
		if (debug != null && tick % 10 == 0) {
			debug.tick();
		}
	}

	/** For the debug driver: the same actions the menu buttons run. */
	void debugUnit(ServerPlayer player, EntityKey key, String action) {
		switch (action) {
			case "orders" -> orders(player, key);
			case "escort" -> escort(player, key);
			case "heal" -> heal(player, key);
			case "menu" -> unitMenu(player, key);
			default -> throw new IllegalArgumentException("unknown unit action");
		}
	}

	void debugCity(ServerPlayer player, EntityKey key, String action) {
		switch (action) {
			case "deliver" -> deliver(player, key);
			case "menu" -> gameplay.cityMenu(player, key);
			case "production" -> gameplay.productionMenu(player, key);
			default -> throw new IllegalArgumentException("unknown city action");
		}
	}

	private McLayout layoutFor(WorldState state) {
		double width = config.hexWidth;
		double rowSpacing = width * Math.sqrt(3.0) / 2.0;
		// The middle of the Civ map sits on the block origin.
		return new McLayout(width, -state.session().grid().width() * width / 2.0, state.session().grid().height() * rowSpacing / 2.0);
	}

	private void react(ServerLevel level, WorldEvent event, boolean quiet) {
		switch (event) {
			case WorldEvent.PlotChanged e -> mark(e.at());
			case WorldEvent.CityAppeared e -> {
				mark(e.city().at());
				if (!quiet) {
					// A city new to the picture was founded where the land was known, has fallen to the player
					// unseen until now, or was simply come upon by somebody exploring.
					WorldState now = told();
					boolean mine = now != null && e.city().key().owner() == now.session().viewer();
					boolean taken = builtin && mine && (e.city().flags() & CityState.F_OCCUPIED) != 0;
					boolean landKnown = applied != null && applied.session().grid().contains(e.city().at()) && applied.plot(e.city().at()).revealed();
					if (taken) {
						announce(e.city().name() + " is yours now.", ChatFormatting.GOLD);
						for (ServerPlayer player : listeners()) {
							ServerPlayer who = player;
							inbox.add(() -> as(who, () -> gameplay.fateMenu(who, e.city().key(), true)));
						}
					} else if (mine || landKnown) {
						announce(civName(e.city().key().owner()) + " founded " + e.city().name() + ".", ChatFormatting.GOLD);
					} else {
						announce("Your people have come upon " + e.city().name() + ", a city of " + civName(e.city().key().owner()) + ".", ChatFormatting.GOLD);
					}
				}
			}
			case WorldEvent.CityChanged e -> {
				if (e.city().population() != e.before().population() || !e.city().buildings().equals(e.before().buildings())
					|| !e.city().wonders().equals(e.before().wonders())) {
					mark(e.city().at());
					// A wonder stands on a hex of its own beside the city.
					if (model != null) {
						for (BoardModel.Site site : model.wonderSites()) {
							if (site.city().equals(e.city().key())) {
								mark(site.plot());
							}
						}
					}
				}
				if (!quiet) {
					for (String wonder : e.city().wonders()) {
						if (!e.before().wonders().contains(wonder)) {
							announce(e.city().name() + " completed a wonder: " + Gameplay.words(wonder) + ".", ChatFormatting.GOLD);
						}
					}
				}
			}
			case WorldEvent.CityChangedHands e -> {
				mark(e.city().at());
				WorldState now = told();
				boolean taken = builtin && now != null && e.city().key().owner() == now.session().viewer();
				announce(e.city().name() + " now belongs to " + civName(e.city().key().owner()) + " (was " + civName(e.before().key().owner()) + ").",
					taken ? ChatFormatting.GOLD : ChatFormatting.RED);
				if (taken && !quiet) {
					// The question every conqueror is asked, put at once; it can be answered later at the city's governor as well.
					for (ServerPlayer player : listeners()) {
						ServerPlayer who = player;
						inbox.add(() -> as(who, () -> gameplay.fateMenu(who, e.city().key(), true)));
					}
				}
			}
			case WorldEvent.CityGone e -> {
				mark(e.last().at());
				announce(e.last().name() + " is gone.", ChatFormatting.RED);
			}
			case WorldEvent.WarChanged e -> announce(e.atWar() ? "War! " + e.civ().name() + " and you are now at war." : "Peace with " + e.civ().name() + ".",
				e.atWar() ? ChatFormatting.RED : ChatFormatting.GREEN);
			case WorldEvent.TurnChanged e -> {
				WorldState now = told();
				announce("Turn " + e.to() + (now != null && now.empire() != null ? ", " + now.empire().yearText() : "") + ".", ChatFormatting.GRAY);
			}
			case WorldEvent.CivChanged e -> {
				// A civ's era or colours changed: its cities are built differently now.
				WorldState now = told();
				if (now != null) {
					for (CityState city : now.cities().values()) {
						if (city.key().owner() == e.civ().id()) {
							mark(city.at());
						}
					}
				}
			}
			case WorldEvent.TechsLearned e -> {
				for (String tech : e.techs()) {
					String unlocks = techGate ? nl.jordythomassen.livingworld.gameplay.TechGate.unlocks(tech) : null;
					announce("Your civilization discovered " + Gameplay.words(tech) + "." + (unlocks == null ? "" : " You can now craft " + unlocks + "."),
						ChatFormatting.AQUA);
				}
			}
			case WorldEvent.Notice e -> {
				if (!quiet) {
					announce(e.text(), ChatFormatting.WHITE);
				}
			}
			case WorldEvent.EraChanged e -> announce("A new age: your civilization enters the " + Gameplay.words(e.to().replace("ERA_", "")) + " era.",
				ChatFormatting.GOLD);
			default -> {
			}
		}
	}

	/** Whom the news being told right now is for: everybody, or the people of one civilization. */
	private List<ServerPlayer> audience;
	private int audienceCiv = -1;
	/** Whether what changed is also to be built (the world as it is built), or only told (one civilization's own news). */
	private boolean painting = true;

	private List<ServerPlayer> listeners() {
		return audience == null ? server.getPlayerList().getPlayers() : audience;
	}

	/** The picture the news being told belongs to. */
	private WorldState told() {
		return audienceCiv < 0 ? link.state() : link.state(audienceCiv);
	}

	private void mark(PlotCoord plot) {
		if (painting) {
			painter.markPlot(plot);
		}
	}

	private String civName(int id) {
		WorldState state = told();
		CivInfo civ = state == null ? null : state.civ(id);
		return civ == null ? "someone" : civ.name();
	}

	private void announce(String text, ChatFormatting color) {
		for (ServerPlayer player : listeners()) {
			player.sendSystemMessage(Component.literal("[Civ] " + text).withStyle(color));
			rts.tell(player, text);
		}
	}

	// ---- players ----

	/** Where a player belongs when there is no better idea: by the viewer's capital, else a unit, else mid-map. */
	double[] home() {
		if (model == null) {
			return new double[] { 0.5, BoardModel.LAND + 1, 0.5 };
		}
		WorldState state = model.state();
		PlotCoord at = null;
		boolean city = false;
		for (CityState c : state.cities().values()) {
			if (c.key().owner() == state.session().viewer() && (at == null || c.capital())) {
				at = c.at();
				city = true;
			}
		}
		if (at == null) {
			for (UnitState u : state.units().values()) {
				if (u.key().owner() == state.session().viewer()) {
					at = u.at();
					break;
				}
			}
		}
		if (at == null) {
			at = new PlotCoord(state.session().grid().width() / 2, state.session().grid().height() / 2);
		}
		// In front of the south door of the keep, or a few steps south of a unit.
		int x = model.centerX(at);
		int z = model.centerZ(at) + (city ? 7 : 3);
		return new double[] { x + 0.5, model.standY(x, z), z + 0.5 };
	}

	void sendHome(ServerPlayer player) {
		double[] home = home();
		player.teleportTo(server.overworld(), home[0], home[1], home[2], Set.of(), 180f, 0f, false);
	}

	void teleport(ServerPlayer player, PlotCoord plot) {
		int x = model.centerX(plot);
		int z = model.centerZ(plot) + 3;
		player.teleportTo(server.overworld(), x + 0.5, model.standY(x, z), z + 0.5, Set.of(), 180f, 0f, false);
	}

	private Path sessionFile() {
		return server.getWorldPath(LevelResource.ROOT).resolve("livingworld-game.txt");
	}

	/** Another Civ game than this world last showed: old positions mean nothing, start at home. */
	private void newGame(ServerLevel level, WorldState state, boolean firstSinceStart) {
		String last = "";
		try {
			if (Files.exists(sessionFile())) {
				last = Files.readString(sessionFile()).trim();
			}
		} catch (IOException e) {
			LivingWorldMod.LOG.warn("Living World: could not read {}", sessionFile(), e);
		}
		boolean sameAsSaved = last.equals(state.session().gameId());
		// What was painted for this game last time is remembered, so what players built on it stays.
		painter.load(paintedFile(), state.session().gameId() + LOOK, config.hexWidth);
		painter.markAround(level);
		dayTicks = 0;
		if (dayIsTurn) {
			dawn();
		}
		if (!sameAsSaved) {
			try {
				Files.writeString(sessionFile(), state.session().gameId());
			} catch (IOException e) {
				LivingWorldMod.LOG.warn("Living World: could not write {}", sessionFile(), e);
			}
		}
		for (ServerPlayer player : level.players()) {
			if (player.entityTags().contains(Rts.ABOVE_TAG) && !rts.isAbove(player)) {
				// Kept an onlooker while there was no land to stand on: on their feet now.
				player.removeTag(Rts.ABOVE_TAG);
				net.minecraft.world.level.GameType own = server.getDefaultGameType();
				player.setGameMode(own == net.minecraft.world.level.GameType.SPECTATOR ? net.minecraft.world.level.GameType.SURVIVAL : own);
			}
			if (!sameAsSaved || model.plotAt(player.getBlockX(), player.getBlockZ()) == null) {
				sendHome(player);
				welcome(player, stateOf(player));
			}
		}
		LivingWorldMod.LOG.info("Living World: showing Civ game {} ({}x{}, turn {}){}", state.session().gameId(), state.session().grid().width(),
			state.session().grid().height(), state.turn(), sameAsSaved ? ", same game as last time" : ", a new game for this world");
	}

	/**
	 * Which look of the land the record of what was painted belongs to. Raised when the land itself is drawn
	 * differently (walls round every city, beaches): a world painted by an older look is then painted anew once.
	 */
	private static final String LOOK = "/look2";

	private Path paintedFile() {
		return server.getWorldPath(LevelResource.ROOT).resolve("livingworld-painted.bin");
	}

	private void savePainted() {
		if (applied != null) {
			painter.save(paintedFile(), applied.session().gameId() + LOOK, config.hexWidth);
		}
	}

	/** A turn has passed in Civ: the land yields again, and a new day begins. */
	private void newTurn(ServerLevel level, WorldState state) {
		painter.setSeason(state.turn(), level);
		gameplay.newTurn();
		for (ServerPlayer player : level.players()) {
			// A Codex that was lost comes back with the new day.
			codex.give(player);
		}
		dayTicks = 0;
		if (dayIsTurn) {
			dawn();
		}
	}

	/** A new day begins. Whoever rules from above gets their noon back at the next tick. */
	private void dawn() {
		run("time set 0");
		noonHeld = false;
	}

	private void setClock(boolean runs) {
		if (clockRuns != runs) {
			clockRuns = runs;
			server.overworld().getGameRules().set(GameRules.ADVANCE_TIME, runs, server);
		}
	}

	/**
	 * With dayIsTurn on, the sun is the turn timer: it rises when a Civ turn starts and the turn
	 * ends at sunset, if Civ has nothing left to ask. While the other players move, or Civ is not
	 * there, the sun stands still.
	 */
	private void dayCycle(ServerLevel level) {
		if (!dayIsTurn || rts.anyoneAbove()) {
			// Seen from above it is always day, and a turn ends when the player says so.
			if (rts.anyoneAbove() && !noonHeld) {
				setClock(false);
				run("time set noon");
			}
			noonHeld = rts.anyoneAbove();
			return;
		}
		if (noonHeld) {
			// The last ruler came down. The day went on being counted at noon's standstill only in name: put the
			// sun where this turn's day has got to, or it would set halfway through and the turn end at midnight.
			noonHeld = false;
			run("time set " + Math.max(0, Math.min(DAY_TICKS, dayTicks)));
		}
		List<ServerPlayer> players = level.players();
		boolean playing = applied != null && link.status() == LinkStatus.LIVE && link.viewerTurn() && !players.isEmpty();
		if (!playing) {
			setClock(false);
			return;
		}
		if (dayTicks < DAY_TICKS) {
			dayTicks++;
			setClock(true);
			if (dayTicks == DAY_TICKS - 1200) {
				announce("The sun is low: this turn ends in a minute. Ring the bell in one of your halls to end it now.", ChatFormatting.GRAY);
			}
			return;
		}
		// Sunset. Hold the sun there and end the turn; if Civ still wants a decision, ask again every minute.
		setClock(false);
		if ((dayTicks - DAY_TICKS) % 1200 == 0) {
			endTurn(players.get(0), "Ending the turn at sunset");
		}
		dayTicks++;
	}

	/** Test aid: five seconds before sunset. */
	void debugDusk() {
		dayTicks = DAY_TICKS - 100;
	}

	/** How far the day (the turn) has run, 0 to 1; -1 when days are not turns. */
	float dayProgress() {
		return dayIsTurn ? Math.min(1f, dayTicks / (float) DAY_TICKS) : -1f;
	}

	private void welcome(ServerPlayer player, WorldState state) {
		CivInfo civ = state.civ(state.session().viewer());
		player.sendSystemMessage(Component.literal(builtin
			? "You rule " + (civ == null ? "a civilization" : civ.name()) + (config.rts && Rts.speaks(player)
				? ". On foot: right-click one of your units to select it, then use your Codex on the land where it should go. F12 lifts you back over the land."
				: ". Right-click one of your units to select it, then use your Codex on the land where it should go. "
					+ "Its menu has the rest: a Settler founds your first city. Double-tap jump to fly.")
			: "You are walking through your Civilization V game" + (civ == null ? "" : " as " + civ.name())
				+ ". Use your Codex to rule; right-click a unit or a governor to talk.").withStyle(ChatFormatting.GOLD));
		if (builtin && !player.getAbilities().mayfly) {
			// A ruler looks at the land from above.
			player.getAbilities().mayfly = true;
			player.onUpdateAbilities();
		}
		codex.give(player);
		if (!player.getInventory().contains(new ItemStack(Items.STONE_AXE))) {
			player.getInventory().add(new ItemStack(Items.STONE_AXE));
			player.getInventory().add(new ItemStack(Items.BREAD, 8));
		}
	}

	void joined(ServerPlayer player) {
		bar.addPlayer(player);
		if (player.entityTags().contains(Rts.ABOVE_TAG) && !rts.isAbove(player)) {
			// Saved as the onlooker the view from above made of them (the game ended before they came down): back on their
			// feet. Whoever rules from above is lifted again a moment later. Somebody who chose to be an onlooker is left one.
			player.removeTag(Rts.ABOVE_TAG);
			if (player.gameMode() == net.minecraft.world.level.GameType.SPECTATOR) {
				net.minecraft.world.level.GameType own = server.getDefaultGameType();
				player.setGameMode(own == net.minecraft.world.level.GameType.SPECTATOR ? net.minecraft.world.level.GameType.SURVIVAL : own);
			}
		}
		if (model != null && (model.plotAt(player.getBlockX(), player.getBlockZ()) == null || player.getY() < BoardModel.BASE)) {
			sendHome(player);
		}
		seats.joined(player);
		if (applied != null) {
			welcome(player, stateOf(player));
		} else if (builtin && player.gameMode() != net.minecraft.world.level.GameType.SPECTATOR) {
			// No game yet, so no land to stand on: an onlooker over the empty world until there is one.
			player.addTag(Rts.ABOVE_TAG);
			player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
		}
	}

	void left(ServerPlayer player) {
		seats.left(player);
		lobby.forget(player);
		rts.forget(player);
		bar.removePlayer(player);
		lastPlotLine.remove(player.getUUID());
		lastUse.remove(player.getUUID());
		selected.remove(player.getUUID());
		reach.remove(player.getUUID());
		ghosts.remove(player.getUUID());
		outline.remove(player.getUUID());
		outlined.remove(player.getUUID());
		gameplay.forget(player);
	}

	private void players(ServerLevel level) {
		List<ServerPlayer> players = level.players();
		if (players.isEmpty() || model == null) {
			link.setMarker(PlayerMarker.HIDDEN);
			return;
		}
		// The first player is the one shown on Civ's map.
		ServerPlayer host = players.get(0);
		GridPos pos = model.layout().toGrid(host.getX(), host.getZ());
		link.setMarker(new PlayerMarker(pos, true, follow, host.getName().getString() + " (in Minecraft)"));
		for (ServerPlayer player : players) {
			acting = player;
			if (rts.isAbove(player)) {
				// Ruling from above: no body in the world, and the player's own screen shows where things can go.
				if (marksShown(player) > 0) {
					showMarks(player, Map.of());
				}
				continue;
			}
			if (player.getY() < BoardModel.BASE - 8) {
				// Fell off the edge of the board.
				sendHome(player);
			}
			if (tick % 5 == 0) {
				gameplay.explore(player);
			}
			boolean pointing = builtin && selectedUnit(player) != null && (Codex.is(player.getMainHandItem()) || Codex.is(player.getOffhandItem()));
			if (pointing && tick % 8 == 0) {
				pointer(player);
			} else if (!pointing && tick % 8 == 0 && marksShown(player) > 0) {
				// The Codex was put away or the unit let go: the marks go with it.
				showMarks(player, Map.of());
				outlined.remove(player.getUUID());
			} else if (!pointing && tick % 10 == 0) {
				String line = plotLine(player);
				if (!line.equals(lastPlotLine.put(player.getUUID(), line)) || tick % 40 == 0) {
					player.sendOverlayMessage(Component.literal(line));
				}
			}
		}
		acting = null;
	}

	private String plotLine(ServerPlayer player) {
		PlotCoord plot = model.plotAt(player.getBlockX(), player.getBlockZ());
		if (plot == null) {
			return "Off the map";
		}
		WorldState state = model.state();
		PlotState p = state.plot(plot);
		if (!p.revealed()) {
			return plot + " · Unexplored";
		}
		Legend legend = state.legend();
		StringBuilder line = new StringBuilder(plot.toString()).append(" · ").append(legend.name(Legend.Kind.TERRAIN, p.terrain()));
		if (p.elevation() == PlotState.HILLS) {
			line.append(" (Hills)");
		} else if (p.elevation() == PlotState.MOUNTAIN) {
			line.append(" (Mountain)");
		}
		if (p.feature() >= 0) {
			line.append(" · ").append(legend.name(Legend.Kind.FEATURE, p.feature()));
		}
		if (p.improvement() >= 0) {
			line.append(" · ").append(legend.name(Legend.Kind.IMPROVEMENT, p.improvement()));
		}
		if (p.resource() >= 0) {
			line.append(" · ").append(legend.name(Legend.Kind.RESOURCE, p.resource()));
		}
		CityState city = state.cityAt(plot);
		if (city != null) {
			line.append(" · City of ").append(city.name());
		} else if (p.owner() >= 0) {
			line.append(" · ").append(civName(p.owner()));
		}
		return line.toString();
	}

	private void status() {
		LinkStatus status = link.status();
		WorldState state = link.state();
		String text;
		BossEvent.BossBarColor color;
		switch (status) {
			case LIVE -> {
				CivInfo civ = state == null ? null : state.civ(state.session().viewer());
				nl.jordythomassen.livingworld.state.Empire empire = state == null ? null : state.empire();
				text = (builtin ? "" : "Civilization V · ") + (empire == null ? "" : empire.yearText() + " · ") + "Turn " + (state == null ? "?" : state.turn())
					+ (civ == null ? "" : " · " + civ.name()) + (empire == null || empire.era().isEmpty() ? "" : " · " + Gameplay.words(empire.era().replace("ERA_", "")) + " era")
					+ (link.viewerTurn() ? "" : " · the others are moving") + (config.mock ? " · mock game" : "");
				color = BossEvent.BossBarColor.GREEN;
			}
			case NO_MATCH -> {
				text = builtin ? "No game yet: choose a civilization to begin" : "Civilization V is in its menus: start or load a single-player game";
				color = BossEvent.BossBarColor.YELLOW;
			}
			case SYNCING -> {
				text = builtin ? "Setting up the game..." : "Syncing with Civilization V...";
				color = BossEvent.BossBarColor.BLUE;
			}
			default -> {
				text = builtin ? "The game stopped: " + link.statusDetail() : "Civilization V not found: start it with the Living World launcher";
				color = BossEvent.BossBarColor.RED;
			}
		}
		if (tick % 20 == 0 || status != shownStatus) {
			shownStatus = status;
			bar.setName(Component.literal(text));
			bar.setColor(color);
			// With days as turns the bar runs down like the day does.
			float progress = dayProgress();
			bar.setProgress(status == LinkStatus.LIVE && progress >= 0 ? Math.max(0.02f, 1f - progress) : 1f);
		}
	}

	// ---- talking to units and cities ----

	boolean interact(ServerPlayer player, Entity entity) {
		Proxies.Proxy proxy = proxies.byEntity(entity.getUUID());
		if (proxy == null || state() == null) {
			return false;
		}
		if (sameClick(player)) {
			return true;
		}
		boolean mine = proxy.key.owner() == state().session().viewer();
		EntityKey commanding = selectedUnit(player);
		if (builtin && commanding != null && !mine) {
			// Pointing a selected unit at someone else's unit or city: go there, which is an attack when they are an enemy.
			PlotCoord at = proxy.city ? state().cities().get(proxy.key).at() : state().units().get(proxy.key).at();
			send(player, commanding, at);
			return true;
		}
		if (proxy.city) {
			gameplay.cityMenu(player, proxy.key);
		} else {
			if (builtin && mine) {
				select(player, proxy.key);
			}
			unitMenu(player, proxy.key);
		}
		return true;
	}

	// ---- commanding units: select one, then point where it should go ----

	/** The unit this player has selected, or null when there is none (any more). */
	EntityKey selectedUnit(ServerPlayer player) {
		EntityKey key = selected.get(player.getUUID());
		WorldState own = stateOf(player);
		if (key != null && (own == null || !own.units().containsKey(key) || (seats.several() && key.owner() != seats.of(player)))) {
			selected.remove(player.getUUID());
			return null;
		}
		return key;
	}

	void select(ServerPlayer player, EntityKey key) {
		EntityKey before = selected.put(player.getUUID(), key);
		glow(before, false);
		glow(key, true);
	}

	void deselect(ServerPlayer player) {
		glow(selected.remove(player.getUUID()), false);
	}

	private void glow(EntityKey key, boolean on) {
		Proxies.Proxy proxy = key == null ? null : proxies.unit(key);
		Entity entity = proxy == null || proxy.entity == null ? null : server.overworld().getEntity(proxy.entity);
		if (entity != null) {
			entity.setGlowingTag(on);
		}
	}

	/** Orders a unit to a hex: a walk, or a fight when an enemy stands there. */
	void send(ServerPlayer player, EntityKey unit, PlotCoord plot) {
		UnitState state = state() == null ? null : state().units().get(unit);
		if (state == null || plot == null) {
			return;
		}
		submit(player, CommandOp.UNIT_MOVE_TO, unit, plot, 0, null, state.name() + " to " + plot);
	}

	/** What the game is called in what players read: nothing but "the game" when it runs inside Minecraft. */
	String civ() {
		return builtin ? "the game" : "Civilization V";
	}

	/** How high above the land the ruler's view from above is. */
	private static final int ALOFT = 56;

	boolean aloft(ServerPlayer player) {
		return model != null && player.getAbilities().flying && player.getY() > model.standY(player.getBlockX(), player.getBlockZ()) + 20;
	}

	/** Lifts the player high above where they stand, looking down at the land like at a map; or sets them down again. */
	void lookFromAbove(ServerPlayer player) {
		if (model == null) {
			return;
		}
		int x = player.getBlockX();
		int z = player.getBlockZ();
		boolean down = aloft(player);
		player.getAbilities().mayfly = true;
		player.getAbilities().flying = !down;
		player.onUpdateAbilities();
		if (down) {
			player.teleportTo(server.overworld(), x + 0.5, model.standY(x, z), z + 0.5, Set.of(), player.getYRot(), 0f, false);
		} else {
			// A little south of the spot, looking north and down, so what was underfoot is in the middle of the view.
			player.teleportTo(server.overworld(), x + 0.5, model.standY(x, z) + ALOFT, z + 0.5 + ALOFT * 0.45, Set.of(), 180f, 62f, false);
			player.sendOverlayMessage(Component.literal("Point at one of your units and use the Codex to select it; then at the land where it should go"));
		}
	}

	/** A unit chosen from a list: it is selected, and the player may go to it. */
	void unitFromAfar(ServerPlayer player, EntityKey key) {
		if (state() == null || !state().units().containsKey(key)) {
			return;
		}
		if (builtin) {
			select(player, key);
		}
		unitMenu(player, key);
	}

	/** One of the viewer's own units on the hex the player points at from a distance, or null. Units that wait for orders come first. */
	private EntityKey ownUnitPointedAt(ServerPlayer player) {
		net.minecraft.core.BlockPos pos = pointedAt(player);
		WorldState state = state();
		PlotCoord plot = pos == null || model == null || state == null ? null : model.plotAt(pos.getX(), pos.getZ());
		if (plot == null || pos.distSqr(player.blockPosition()) < 8 * 8) {
			return null;
		}
		UnitState found = null;
		for (UnitState unit : state.units().values()) {
			if (unit.key().owner() == state.session().viewer() && unit.at().equals(plot) && (found == null || (unit.waiting() && !found.waiting()))) {
				found = unit;
			}
		}
		return found == null ? null : found.key();
	}

	/** Where a player's selected unit can go this turn and whom it could attack, as the game said when last asked, and the marks for it. */
	private record Reach(EntityKey unit, PlotCoord at, int moves, int turn, Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> marks,
		Set<PlotCoord> strikes) {
	}

	private final Map<UUID, Reach> reach = new HashMap<>();
	/** Marks on the land that exist only on one player's screen: what was sent, so it can be taken away again. */
	private final Map<UUID, Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState>> ghosts = new HashMap<>();
	private final Map<UUID, PlotCoord> outlined = new HashMap<>();
	private final Map<UUID, Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState>> outline = new HashMap<>();
	private static final net.minecraft.world.level.block.state.BlockState CAN_GO = net.minecraft.world.level.block.Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.LIME).defaultBlockState();
	private static final net.minecraft.world.level.block.state.BlockState CAN_ATTACK = net.minecraft.world.level.block.Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState();
	private static final net.minecraft.world.level.block.state.BlockState POINTED = net.minecraft.world.level.block.Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.YELLOW).defaultBlockState();

	/** The first free block over whatever the board has in a column: on the ground, on water, or on top of a tree. */
	private int markY(int x, int z, int[] scratch) {
		if (model.column(x, z, scratch) == BoardModel.NOTHING) {
			return BoardModel.LAND + 1;
		}
		for (int y = BoardModel.TOP - 1; y >= BoardModel.BASE; y--) {
			if (nl.jordythomassen.livingworld.board.Material.of(scratch[y - BoardModel.BASE]) != nl.jordythomassen.livingworld.board.Material.AIR) {
				return y + 1;
			}
		}
		return BoardModel.LAND + 1;
	}

	/** A ring of marks around the middle of a hex. */
	private void ring(Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> marks, PlotCoord plot, double radius,
		net.minecraft.world.level.block.state.BlockState mark, int[] scratch) {
		int cx = model.centerX(plot);
		int cz = model.centerZ(plot);
		int steps = (int) Math.ceil(radius * 7);
		for (int i = 0; i < steps; i++) {
			double angle = 2 * Math.PI * i / steps;
			int x = cx + (int) Math.round(radius * Math.cos(angle));
			int z = cz + (int) Math.round(radius * Math.sin(angle));
			marks.put(new net.minecraft.core.BlockPos(x, markY(x, z, scratch), z), mark);
		}
	}

	/**
	 * Shows a player exactly these marks: blocks that only that player's game is told about. What
	 * was shown before and is not wanted any more is put back as the world has it.
	 */
	private void showMarks(ServerPlayer player, Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> wanted) {
		Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> shown = ghosts.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
		if (shown.isEmpty() && wanted.isEmpty()) {
			return;
		}
		ServerLevel level = server.overworld();
		for (var it = shown.entrySet().iterator(); it.hasNext(); ) {
			var was = it.next();
			if (!wanted.containsKey(was.getKey())) {
				player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(level, was.getKey()));
				it.remove();
			}
		}
		for (var mark : wanted.entrySet()) {
			if (shown.put(mark.getKey(), mark.getValue()) != mark.getValue()) {
				player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(mark.getKey(), mark.getValue()));
			}
		}
	}

	/** For the self-test, which has no player in the world's list: what the tick does for a player with a unit selected. */
	void debugPointer(ServerPlayer player) {
		pointer(player);
	}

	/** How many commands players have given so far (for the self-test). */
	long commandsGiven() {
		return commandCounter;
	}

	/** How many marks a player is being shown (for the self-test). */
	int marksShown(ServerPlayer player) {
		Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> shown = ghosts.get(player.getUUID());
		return shown == null ? 0 : shown.size();
	}

	/** The marks for where the selected unit can go (green rings) and whom it can attack (red), asked again whenever the unit has moved. */
	private Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> reachMarks(ServerPlayer player, UnitState unit) {
		Reach known = reach.get(player.getUUID());
		if (known == null || !known.unit().equals(unit.key()) || !known.at().equals(unit.at()) || known.moves() != unit.moves() || known.turn() != state().turn()) {
			// Remember at once what was asked for, so the question is put once.
			Reach asked = new Reach(unit.key(), unit.at(), unit.moves(), state().turn(), Map.of(), Set.of());
			reach.put(player.getUUID(), asked);
			ask(player, "reach", unit.key().id(), 0, (who, options) -> {
				if (reach.get(who.getUUID()) != asked || model == null) {
					return;
				}
				Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> marks = new HashMap<>();
				Set<PlotCoord> strikes = new java.util.HashSet<>();
				int[] scratch = new int[BoardModel.TOP - BoardModel.BASE];
				for (nl.jordythomassen.livingworld.protocol.Records.Option option : options) {
					String[] xy = option.type().split(",");
					PlotCoord plot = new PlotCoord(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));
					if (option.group() == 12) {
						strikes.add(plot);
						ring(marks, plot, 5.0, CAN_ATTACK, scratch);
						ring(marks, plot, 6.0, CAN_ATTACK, scratch);
					} else {
						ring(marks, plot, 4.0, CAN_GO, scratch);
					}
				}
				reach.put(who.getUUID(), new Reach(asked.unit(), asked.at(), asked.moves(), asked.turn(), marks, strikes));
			});
			return known == null ? Map.of() : known.marks();
		}
		return known.marks();
	}

	/** Shows a player with a selected unit where it can go, which hex they point at, and what sending the unit there would mean. */
	private void pointer(ServerPlayer player) {
		EntityKey commanding = selectedUnit(player);
		WorldState state = state();
		UnitState unit = commanding == null ? null : state.units().get(commanding);
		if (unit == null) {
			showMarks(player, Map.of());
			return;
		}
		Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> marks = new HashMap<>(reachMarks(player, unit));
		net.minecraft.core.BlockPos pos = pointedAt(player);
		PlotCoord plot = pos == null ? null : model.plotAt(pos.getX(), pos.getZ());
		if (plot == null) {
			outlined.remove(player.getUUID());
			showMarks(player, marks);
			return;
		}
		// The hex pointed at, drawn along its six sides; worked out again only when it is another hex.
		if (!plot.equals(outlined.put(player.getUUID(), plot)) || !outline.containsKey(player.getUUID())) {
			Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> sides = new HashMap<>();
			int[] scratch = new int[BoardModel.TOP - BoardModel.BASE];
			int cx = model.centerX(plot);
			int cz = model.centerZ(plot);
			double radius = config.hexWidth / Math.sqrt(3.0) * 0.9;
			for (int corner = 0; corner < 6; corner++) {
				double a0 = Math.toRadians(60 * corner + 30);
				double a1 = Math.toRadians(60 * corner + 90);
				int steps = (int) Math.ceil(radius);
				for (int step = 0; step < steps; step++) {
					double t = step / (double) steps;
					int x = cx + (int) Math.round(radius * (Math.cos(a0) * (1 - t) + Math.cos(a1) * t));
					int z = cz + (int) Math.round(radius * (Math.sin(a0) * (1 - t) + Math.sin(a1) * t));
					sides.put(new net.minecraft.core.BlockPos(x, markY(x, z, scratch), z), POINTED);
				}
			}
			outline.put(player.getUUID(), sides);
		}
		marks.putAll(outline.get(player.getUUID()));
		showMarks(player, marks);
		String what;
		if (plot.equals(unit.at())) {
			what = unit.name() + " stands here";
		} else if (!state.plot(plot).revealed()) {
			what = "Unexplored land";
		} else {
			UnitState enemy = null;
			for (UnitState other : state.units().values()) {
				CivInfo owner = state.civ(other.key().owner());
				if (other.at().equals(plot) && owner != null && (owner.atWarWithViewer() || owner.barbarian())) {
					enemy = other;
				}
			}
			CityState city = state.cityAt(plot);
			CivInfo cityOwner = city == null ? null : state.civ(city.key().owner());
			Reach known = reach.get(player.getUUID());
			// A red ring with nobody in it is somewhere an aircraft or a bomb can strike all the same.
			boolean strike = known != null && known.strikes().contains(plot);
			what = enemy != null ? "Attack the " + enemy.name() : cityOwner != null && cityOwner.atWarWithViewer() ? "Attack " + city.name()
				: strike ? "Strike here with the " + unit.name()
				: unit.name() + " to " + (city != null ? city.name() : state.legend().name(Legend.Kind.TERRAIN, state.plot(plot).terrain()).toLowerCase(java.util.Locale.ROOT)
					+ " " + plot);
		}
		player.sendOverlayMessage(Component.literal(what).withStyle(ChatFormatting.YELLOW));
	}

	/** What the player is looking at, however far: the block under the crosshair within 250 blocks, or null. */
	private net.minecraft.core.BlockPos pointedAt(ServerPlayer player) {
		net.minecraft.world.phys.HitResult hit = player.pick(250.0, 1.0f, true);
		return hit instanceof net.minecraft.world.phys.BlockHitResult block && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? block.getBlockPos() : null;
	}

	private void unitMenu(ServerPlayer player, EntityKey key) {
		WorldState state = state();
		UnitState unit = state.units().get(key);
		if (unit == null) {
			return;
		}
		CivInfo civ = state.civ(key.owner());
		boolean mine = key.owner() == state.session().viewer();
		Menus.Builder menu = menus.menu(player, unit.name() + " · " + (civ == null ? "Unknown" : civ.name()), civ == null ? 0xFFFFFF : civ.color());
		menu.line("Health " + unit.hp() + "/" + unit.maxHp() + "   Moves " + moves(unit.moves()) + "/" + moves(unit.maxMoves())
			+ (unit.strength() > 0 ? "   Strength " + unit.strength() : "") + (unit.rangedStrength() > 0 ? "   Ranged " + unit.rangedStrength() : ""));
		menu.line("Level " + unit.level() + " (" + unit.experience() + " XP)" + (unit.fortified() ? "   Fortified" : "") + (unit.embarked() ? "   Embarked" : "")
			+ "   At " + unit.at());
		if (civ != null && civ.atWarWithViewer()) {
			menu.line(Component.literal("You are at war with " + civ.name() + ". This unit will not take orders from you.").withStyle(ChatFormatting.RED));
		} else if (!mine) {
			menu.line(civ != null && civ.minor() ? "A unit of a city-state. It nods and keeps to its post."
				: civ != null && civ.barbarian() ? "It growls something you would rather not have translated." : "A foreign unit. It salutes, warily.");
		}
		if (builtin && mine) {
			// Everything this unit can do right here, from the game's own list.
			menu.line(Component.literal("Selected. With the Codex in hand, use it on the land where this unit should go; on an enemy, to attack.")
				.withStyle(ChatFormatting.YELLOW));
			menu.columns(2);
			ask(player, "unit", key.id(), 0, (p, options) -> {
				for (nl.jordythomassen.livingworld.protocol.Records.Option option : options) {
					if (option.type().equals("Promote")) {
						menu.button("Choose a promotion", "It has earned one", who -> promotions(who, key));
					} else if (option.turns() == 1) {
						menu.button(option.name(), null, who -> submit(who, CommandOp.UNIT_ACTION, key, null, 0, null, unit.name(), null, option.type(), false));
					}
				}
				PlotCoord here = model == null ? null : model.plotAt(p.getBlockX(), p.getBlockZ());
				if (!unit.at().equals(here)) {
					menu.button("Go to it", "Takes you to where it stands", who -> teleport(who, unit.at()));
					menu.button("Come to me", "It walks to the hex you stand in", who -> escort(who, key));
				}
				if (unit.hp() < unit.maxHp()) {
					menu.button("Treat its wounds (1 bread = " + HP_PER_BREAD + " health)", "Uses bread from your inventory", who -> heal(who, key));
				}
				menu.open();
			});
			return;
		}
		menu.button("Ask about its orders", null, p -> orders(p, key));
		if (mine) {
			menu.button("Escort me: come to where I stand", "Orders the real unit in Civilization V to move to your hex", p -> escort(p, key));
			if (unit.hp() < unit.maxHp()) {
				menu.button("Treat its wounds (1 bread = " + HP_PER_BREAD + " health)", "Uses bread from your inventory and heals the real unit", p -> heal(p, key));
			}
		}
		menu.open();
	}

	void promotions(ServerPlayer player, EntityKey key) {
		ask(player, "promote", key.id(), 0, (asker, options) -> ask(asker, "about:promote", key.id(), 0, (p, about) -> {
			UnitState unit = state() == null ? null : state().units().get(key);
			if (unit == null) {
				return;
			}
			Map<String, String> texts = new HashMap<>();
			for (nl.jordythomassen.livingworld.protocol.Records.Option one : about) {
				texts.put(one.type(), one.name().replace("\n", "; "));
			}
			Menus.Builder menu = menus.menu(p, "A promotion for " + unit.name(), 0xE0B040);
			if (options.isEmpty()) {
				menu.line("It has not earned a promotion yet.");
			} else {
				menu.line("It has earned a promotion. Each one stays with it for good; what it gives is written beside it.");
			}
			for (nl.jordythomassen.livingworld.protocol.Records.Option option : options) {
				String gives = texts.getOrDefault(option.type(), "");
				menu.button(option.name() + (gives.isEmpty() ? "" : ":  " + gives), gives, who -> submit(who, CommandOp.UNIT_PROMOTE, key, null, 0, null, unit.name(), null, option.type(), false));
			}
			menu.open();
		}));
	}

	private static String moves(int sixtieths) {
		return sixtieths % 60 == 0 ? Integer.toString(sixtieths / 60) : String.format(java.util.Locale.ROOT, "%.1f", sixtieths / 60.0);
	}

	private void orders(ServerPlayer player, EntityKey key) {
		WorldState state = state();
		UnitState unit = state == null ? null : state.units().get(key);
		if (unit == null) {
			say(player, "The unit is no longer there.");
			return;
		}
		CivInfo civ = state.civ(key.owner());
		String who = unit.name() + (civ == null ? "" : " of " + civ.name());
		String what;
		if (civ != null && civ.atWarWithViewer()) {
			what = "\"My orders are none of your business.\"";
		} else if (unit.fortified()) {
			what = "\"Holding this position at " + unit.at() + ".\"";
		} else if (!unit.combat()) {
			what = "\"Working, as always. " + (unit.moves() > 0 ? "I still have " + moves(unit.moves()) + " moves this turn." : "Done for this turn.") + "\"";
		} else if (unit.hp() < unit.maxHp()) {
			what = "\"Wounded (" + unit.hp() + "/" + unit.maxHp() + "), but standing.\"";
		} else if (unit.moves() == 0) {
			what = "\"We have marched all we can this turn.\"";
		} else {
			what = "\"Awaiting orders. " + moves(unit.moves()) + " moves left this turn.\"";
		}
		say(player, who + ": " + what);
	}

	private void escort(ServerPlayer player, EntityKey key) {
		if (model == null || state() == null) {
			return;
		}
		PlotCoord plot = model.plotAt(player.getBlockX(), player.getBlockZ());
		if (plot == null) {
			say(player, "You are off the map.");
			return;
		}
		submit(player, CommandOp.UNIT_MOVE_TO, key, plot, 0, null, "Escort order");
	}

	private void heal(ServerPlayer player, EntityKey key) {
		UnitState unit = state() == null ? null : state().units().get(key);
		if (unit == null) {
			return;
		}
		int wanted = Math.min(MAX_BREAD, (unit.maxHp() - unit.hp() + HP_PER_BREAD - 1) / HP_PER_BREAD);
		// Take the bread now; it comes back if Civilization V does not carry the treatment out.
		ItemStack taken = take(player, stack -> stack.is(Items.BREAD), wanted);
		if (taken.isEmpty()) {
			say(player, "You have no bread. Farms in your territory grow wheat; three wheat make a bread.");
			return;
		}
		submit(player, CommandOp.UNIT_HEAL, key, null, taken.getCount() * HP_PER_BREAD, taken, "Treatment");
	}

	void deliver(ServerPlayer player, EntityKey key) {
		if (builtin) {
			deliverAll(player, key);
			return;
		}
		// Take the logs now; they come back if Civilization V does not take the delivery.
		ItemStack taken = take(player, stack -> stack.is(ItemTags.LOGS), MAX_LOGS);
		if (taken.isEmpty()) {
			say(player, "You carry no logs. Forest hexes have trees; the axe in your pack will do.");
			return;
		}
		submit(player, CommandOp.CITY_ADD_PRODUCTION, key, null, taken.getCount() * HAMMERS_PER_LOG, taken, "Delivery");
	}

	/**
	 * Everything the player carries that a city has a use for, as far as the city can take it this
	 * turn: wood and ore for what it builds, the harvest for its people, gold for the treasury.
	 * What it cannot take stays in the pack.
	 */
	private void deliverAll(ServerPlayer player, EntityKey key) {
		ask(player, "hands", key.id(), 0, (who, rows) -> {
			int[] room = new int[3];
			for (nl.jordythomassen.livingworld.protocol.Records.Option row : rows) {
				if (row.group() >= 0 && row.group() < 3) {
					room[row.group()] = row.id();
				}
			}
			int[] brought = new int[3];
			boolean carried = false;
			for (Hands.Good good : Hands.GOODS) {
				// One kind of thing at a time (oak, then birch ...): what comes back when a load is refused is that very thing.
				for (int rounds = 0; rounds < 12; rounds++) {
					boolean has = false;
					for (int slot = 0; slot < who.getInventory().getContainerSize() && !has; slot++) {
						has = good.is().test(who.getInventory().getItem(slot));
					}
					carried |= has;
					int most = room[good.kind()] / good.worth();
					if (!has || most <= 0) {
						break;
					}
					ItemStack taken = take(who, good.is(), most);
					if (taken.isEmpty()) {
						break;
					}
					int worth = taken.getCount() * good.worth();
					room[good.kind()] -= worth;
					brought[good.kind()] += worth;
					submit(who, CommandOp.CITY_ADD_PRODUCTION, key, null, worth, taken, "Delivery", null, Hands.ITEM[good.kind()], false);
				}
			}
			WorldState now = stateOf(who);
			CityState city = now == null ? null : now.cities().get(key);
			String name = city == null ? "The city" : city.name();
			if (brought[0] + brought[1] + brought[2] == 0) {
				say(who, carried ? name + " can take no more from your hands this turn." : "You carry nothing " + name + " can use. Wood and ore become production, the harvest food, gold and gems gold.");
				return;
			}
			StringBuilder told = new StringBuilder(name + ":");
			for (int kind = 0; kind < 3; kind++) {
				if (brought[kind] > 0) {
					told.append("  +").append(brought[kind]).append(' ').append(Hands.MEASURE[kind]);
				}
			}
			who.sendOverlayMessage(Component.literal(told.toString()).withStyle(ChatFormatting.GREEN));
			rts.tell(who, told.toString());
		});
	}

	/**
	 * Why somebody on foot may not break or place a block there, or null when they may. Hands work
	 * one's own land only: not a neighbour's, not land that is nobody's yet; and neither a city
	 * nor a wonder of the world is taken apart by hand, one's own no more than another's.
	 */
	String handsTied(ServerPlayer player, net.minecraft.core.BlockPos pos) {
		BoardModel board = model;
		WorldState state = stateOf(player);
		if (player.isCreative() || board == null || state == null) {
			return null;
		}
		PlotCoord plot = board.plotAt(pos.getX(), pos.getZ());
		if (plot == null || !state.session().grid().contains(plot)) {
			return "There is nothing out here for your hands.";
		}
		int civ = seats.of(player);
		if (civ < 0) {
			return "You rule no land in this game.";
		}
		PlotState there = state.plot(plot);
		if (there.city()) {
			return "A city is not taken apart by hand.";
		}
		if (board.siteAt(plot) != null) {
			// The wonder itself stays; the field or the wood around it is worked like any other of one's own (what grows back may be taken).
			int[] column = new int[BoardModel.TOP - BoardModel.BASE];
			board.column(pos.getX(), pos.getZ(), column);
			int at = pos.getY() - BoardModel.BASE;
			boolean yield = there.owner() == civ && at >= 0 && at < column.length && nl.jordythomassen.livingworld.board.Material.of(column[at]).regrows()
				&& !server.overworld().getBlockState(pos).isAir();
			if (!yield) {
				return "A wonder of the world stays as it was built.";
			}
			return null;
		}
		if (there.owner() != civ) {
			return there.owner() < 0 ? "Nobody's land: your hands work inside your own borders only." : "This is the land of " + civName(there.owner()) + ".";
		}
		return null;
	}

	/** The same, said to the player when their hands are tied; true when they may. */
	boolean handsFree(ServerPlayer player, net.minecraft.core.BlockPos pos) {
		String why = handsTied(player, pos);
		if (why != null) {
			player.sendOverlayMessage(Component.literal(why).withStyle(ChatFormatting.YELLOW));
		}
		return why == null;
	}

	/** Removes up to {@code most} matching items of one kind from the player's inventory and returns them. */
	static ItemStack take(ServerPlayer player, java.util.function.Predicate<ItemStack> wanted, int most) {
		ItemStack taken = ItemStack.EMPTY;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			int need = most - (taken.isEmpty() ? 0 : taken.getCount());
			if (need <= 0) {
				break;
			}
			ItemStack stack = player.getInventory().getItem(i);
			if (stack.isEmpty() || !wanted.test(stack)) {
				continue;
			}
			if (taken.isEmpty()) {
				taken = stack.split(Math.min(need, stack.getCount()));
			} else if (ItemStack.isSameItemSameComponents(taken, stack)) {
				int move = Math.min(need, stack.getCount());
				stack.shrink(move);
				taken.grow(move);
			}
		}
		if (!taken.isEmpty()) {
			player.getInventory().setChanged();
		}
		return taken;
	}

	private void submit(ServerPlayer player, CommandOp op, EntityKey target, PlotCoord plot, int amount, ItemStack paid, String what) {
		submit(player, op, target, plot, amount, paid, what, null, "", false);
	}

	/**
	 * Hands a request to the link and remembers who asked, to tell them how it ended.
	 *
	 * @param paid what the player handed over for it; given back if Civ does not carry it out
	 * @param once when not null, a second request with the same value does nothing new
	 * @param item for a choice from one of Civ's lists, the type key of what was chosen
	 * @param quiet no chat about it
	 */
	void submit(ServerPlayer player, CommandOp op, EntityKey target, PlotCoord plot, int amount, ItemStack paid, String what, String once, String item,
		boolean quiet) {
		WorldState state = stateOf(player);
		int civ = seats.of(player);
		if (state == null || civ < 0) {
			if (!quiet) {
				say(player, "You rule no civilization in this game yet. Choose one first (the Players button, or P).");
			}
			return;
		}
		String id = Long.toString(System.currentTimeMillis(), 36) + "-" + (++commandCounter);
		GameCommand command = new GameCommand(id, once == null ? id : once, state.session().bootId(), state.session().gameId(), player.getName().getString(),
			state.turn(), op, target, plot, amount, item);
		CommandTicket ticket = link.submit(civ, command);
		if (!ticket.command().id().equals(id)) {
			// Already asked: nothing new was started.
			if (!quiet) {
				say(player, what + " is already under way.");
			}
			return;
		}
		refunds.put(id, new Refund(player.getUUID(), player, paid == null ? ItemStack.EMPTY : paid, what, quiet));
		if (ticket.status().finished()) {
			finished(ticket);
		}
	}

	/**
	 * Asks Civ for one of its lists of choices and continues on the server thread when it has
	 * answered, with the player who asked (if still there).
	 */
	void ask(ServerPlayer player, String kind, int a, int c, java.util.function.BiConsumer<ServerPlayer, List<nl.jordythomassen.livingworld.protocol.Records.Option>> then) {
		link.ask(seats.of(player), kind, a, c, options -> inbox.add(() -> {
			ServerPlayer found = server.getPlayerList().getPlayer(player.getUUID());
			if (found == null && !player.isRemoved() && !player.hasDisconnected()) {
				found = player;
			}
			if (found == null) {
				return;
			}
			ServerPlayer who = found;
			if (options == null || applied == null) {
				say(who, (builtin ? "The game" : "Civilization V") + " did not answer. Try again in a moment.");
				return;
			}
			as(who, () -> then.accept(who, options));
		}));
	}

	/** Ends the player's turn in Civilization V, as the Next Turn button there does. */
	private static final String END_TURN_ASKED = "Ending the turn";
	private int newsRetryTurn = -1;
	private int newsRetries;
	private int newsRetryAt = -1;
	private UUID newsRetryFor;
	private String newsRetryWhat;

	/** The player asks for the end of the turn (the bell, the Codex). */
	void endTurn(ServerPlayer player) {
		endTurn(player, END_TURN_ASKED);
	}

	private void endTurn(ServerPlayer player, String what) {
		WorldState state = state();
		if (state == null) {
			say(player, builtin ? "The game is still being set up." : "No Civilization V game is being shown yet.");
			return;
		}
		int civ = seats.of(player);
		if (civ >= 0 && !link.mayAct(civ)) {
			String why = link.waitsFor(civ);
			say(player, !builtin ? "It is not your turn in Civilization V right now." : why.equals("ended") ? "You have ended your turn; the others are still at theirs."
				: why.isEmpty() ? "The others are still moving." : why + " moves first: you are at war with them.");
			return;
		}
		submit(player, CommandOp.END_TURN, new EntityKey(state.session().viewer(), -1), null, 0, null, what,
			"endturn-" + state.session().bootId() + "-" + state.turn() + (seats.several() ? "-" + civ : ""), "", false);
	}

	// ---- clicks: the Codex in hand, and the bell and lectern in the player's own halls ----

	/** How long after one answered click further click events are taken to be the same click. */
	private static final int SAME_CLICK_TICKS = 6;

	/**
	 * One click of the mouse reaches the server as more than one event: on the unit or block, then
	 * with the item in hand; and a button held a moment too long is repeated by the game every four
	 * ticks. Only the first of them is the click: otherwise selecting a unit would order it away in
	 * the same breath, the Codex would open over a governor, and one ring of the bell would end two
	 * turns. Every event counts from the last one, so a button held down stays one click.
	 */
	private boolean sameClick(ServerPlayer player) {
		Integer last = lastUse.put(player.getUUID(), tick);
		return last != null && tick - last <= SAME_CLICK_TICKS;
	}

	/** A click with something in hand, at nothing in particular. True when it was ours to answer. */
	boolean useItem(ServerPlayer player, net.minecraft.world.InteractionHand hand) {
		if (!Codex.is(player.getItemInHand(hand))) {
			return false;
		}
		if (!sameClick(player)) {
			EntityKey commanding = builtin ? selectedUnit(player) : null;
			if (commanding != null && player.isShiftKeyDown()) {
				deselect(player);
				player.sendOverlayMessage(Component.literal("No unit selected"));
			} else if (commanding != null) {
				net.minecraft.core.BlockPos pos = pointedAt(player);
				PlotCoord plot = pos == null || model == null ? null : model.plotAt(pos.getX(), pos.getZ());
				if (plot == null) {
					player.sendOverlayMessage(Component.literal("Point at the land where it should go (sneak and use to let it be)"));
				} else {
					send(player, commanding, plot);
				}
			} else {
				// From a distance the Codex is the ruler's hand: pointing at one of your own units selects it.
				EntityKey far = builtin ? ownUnitPointedAt(player) : null;
				if (far != null) {
					select(player, far);
					player.sendOverlayMessage(Component.literal(state().units().get(far).name() + " selected: use the Codex on the land where it should go"));
				} else {
					codex.open(player);
				}
			}
		}
		return true;
	}

	/** A click on a block. True when it was ours to answer, and the game then does nothing else with it. */
	boolean useBlock(ServerPlayer player, net.minecraft.world.InteractionHand hand, net.minecraft.core.BlockPos pos) {
		ServerLevel level = server.overworld();
		WorldState state = state();
		net.minecraft.world.level.block.state.BlockState block = level.getBlockState(pos);
		boolean bell = block.is(net.minecraft.world.level.block.Blocks.BELL);
		if (hand == net.minecraft.world.InteractionHand.MAIN_HAND && model != null && state != null
			&& (bell || block.is(net.minecraft.world.level.block.Blocks.LECTERN))) {
			PlotCoord plot = model.plotAt(pos.getX(), pos.getZ());
			CityState city = plot == null ? null : state.cityAt(plot);
			// Only the furniture of the player's own halls; a bell someone placed elsewhere is just a bell.
			if (city != null && city.key().owner() == state.session().viewer()) {
				int[] spot = model.hallFurniture(city.at())[bell ? 0 : 1];
				if (spot[0] == pos.getX() && spot[1] == pos.getY() && spot[2] == pos.getZ()) {
					if (!sameClick(player)) {
						if (bell) {
							((net.minecraft.world.level.block.BellBlock) net.minecraft.world.level.block.Blocks.BELL)
								.attemptToRing(level, pos, player.getDirection().getOpposite());
							endTurn(player);
						} else {
							gameplay.researchMenu(player);
						}
					}
					return true;
				}
			}
		}
		if (!Codex.is(player.getItemInHand(hand))) {
			return false;
		}
		if (!sameClick(player)) {
			PlotCoord plot = model == null ? null : model.plotAt(pos.getX(), pos.getZ());
			EntityKey commanding = builtin ? selectedUnit(player) : null;
			if (commanding != null && player.isShiftKeyDown()) {
				deselect(player);
				player.sendOverlayMessage(Component.literal("No unit selected"));
			} else if (commanding != null && plot != null) {
				send(player, commanding, plot);
			} else if (player.isShiftKeyDown() && plot != null) {
				gameplay.workMenu(player, plot);
			} else {
				codex.open(player);
			}
		}
		return true;
	}

	/** Reports the fate of commands to whoever gave them, and gives back what was paid for a failed one. */
	private void tickets() {
		for (CommandTicket ticket : link.drainTicketUpdates()) {
			if (ticket.status().finished()) {
				finished(ticket);
			}
		}
	}

	/**
	 * The player asked to end the turn and Civ wants a decision first: open the page where that
	 * decision is made, so the bell never leaves the player wondering what to do next.
	 */
	void openWhatCivWaitsFor(ServerPlayer player) {
		WorldState state = state();
		nl.jordythomassen.livingworld.state.Empire empire = state == null ? null : state.empire();
		if (empire == null) {
			return;
		}
		if (empire.policyDue()) {
			gameplay.policyMenu(player);
		} else if (empire.blocking().equals("RESEARCH") || empire.blocking().equals("FREE_TECH")) {
			gameplay.researchMenu(player);
		} else if (empire.blocking().equals("RELIGION")) {
			gameplay.beliefMenu(player, true);
		} else if (empire.blocking().equals("VOTE")) {
			gameplay.voteMenu(player);
		} else if (empire.blocking().equals("PRODUCTION")) {
			for (CityState city : state.cities().values()) {
				if (city.key().owner() == state.session().viewer() && city.details() && city.productionType().isEmpty()) {
					gameplay.productionMenu(player, city.key());
					return;
				}
			}
		}
	}

	private void finished(CommandTicket ticket) {
		Refund asked = refunds.get(ticket.command().id());
		ServerPlayer who = asked == null ? null : server.getPlayerList().getPlayer(asked.player());
		ServerPlayer before = acting;
		acting = who;
		try {
			settle(ticket);
		} finally {
			acting = before;
		}
	}

	private void settle(CommandTicket ticket) {
		Refund refund = refunds.remove(ticket.command().id());
		if (refund == null) {
			return;
		}
		ServerPlayer player = server.getPlayerList().getPlayer(refund.player());
		if (player == null && !refund.asked().isRemoved() && !refund.asked().hasDisconnected()) {
			player = refund.asked();
		}
		if (refund.quiet()) {
			if (player != null && ticket.status() == CommandStatus.CONFIRMED && ticket.after() > 0) {
				player.sendOverlayMessage(Component.literal("You explored " + ticket.after() + (ticket.after() == 1 ? " new hex" : " new hexes")));
				LivingWorldMod.LOG.info("Living World: {} explored {} new hexes", player.getName().getString(), ticket.after());
			}
			return;
		}
		if (ticket.status() == CommandStatus.CONFIRMED) {
			if (player != null) {
				String result = builtin ? ticket.detail() : switch (ticket.command().op()) {
					case UNIT_MOVE_TO -> "The unit is on its way in Civilization V.";
					case CITY_ADD_PRODUCTION -> "Civilization V confirms: production went from " + ticket.before() + " to " + ticket.after() + " hammers.";
					case UNIT_HEAL -> "Civilization V confirms: health went from " + ticket.before() + " to " + ticket.after() + ".";
					case END_TURN -> "The next turn has begun in Civilization V.";
					case CITY_BUILD -> "Civilization V shows the city working on it.";
					case RESEARCH -> "Civilization V shows it as your research.";
					case REVEAL -> "The land around you is on Civilization V's map now.";
					case IMPROVE -> "Civilization V shows it on its map.";
					case GIFT -> {
						WorldState now = state();
						nl.jordythomassen.livingworld.state.Relation relation = now == null ? null : now.relations().get(ticket.command().target().id());
						yield "Their influence is now " + (relation == null ? "higher" : relation.influence()) + ".";
					}
					case DECLARE_WAR -> "Civilization V shows you at war.";
					case POLICY -> "Civilization V counts it among your policies.";
					default -> ticket.detail();
				};
				if (builtin && ticket.command().op() == CommandOp.UNIT_ACTION) {
					// A Great Prophet's deed opens a choice of beliefs: the page for it comes by itself (and only then).
					gameplay.beliefMenu(player, false);
				}
				if (builtin) {
					// The game's own words say it all ("Arrived.", "Rome has new orders."); the end of a turn is announced as the new turn.
					if (rts.isAbove(player)) {
						// From above the map itself shows a unit arriving; only what cannot be seen there is told.
						switch (ticket.command().op()) {
							case CITY_BUY, DIPLOMACY, MAKE_PEACE, DECLARE_WAR, GIFT, CITY_FATE, TRADE, BELIEF, ANSWER, UNIT_PROMOTE, POLICY, CHOICE -> rts.tell(player, result);
							default -> {
							}
						}
					}
					// A delivery was told as a whole when it was handed over (see deliverAll).
					if (ticket.command().op() != CommandOp.END_TURN && ticket.command().op() != CommandOp.CITY_ADD_PRODUCTION) {
						String numbers = ticket.command().op() == CommandOp.UNIT_HEAL ? " Health went from " + ticket.before() + " to " + ticket.after() + "." : "";
						player.sendOverlayMessage(Component.literal(refund.what() + ": " + result + numbers));
					}
					LivingWorldMod.LOG.info("Living World: {}: {}", refund.what(), result);
					return;
				}
				player.sendSystemMessage(Component.literal("[Civ] " + refund.what() + " done. " + result).withStyle(ChatFormatting.GREEN));
			}
			LivingWorldMod.LOG.info("Living World: {} confirmed by Civilization V{}", refund.what(),
				ticket.after() == Long.MIN_VALUE ? "" : " (" + ticket.before() + " -> " + ticket.after() + ")");
			return;
		}
		LivingWorldMod.LOG.info("Living World: {} not carried out ({}): {}", refund.what(), ticket.status(), ticket.detail());
		if (player != null && ticket.command().op() == CommandOp.END_TURN && ticket.detail().contains("ask again")) {
			if (newsRetryTurn != ticket.command().turn()) {
				newsRetryTurn = ticket.command().turn();
				newsRetries = 0;
			}
		}
		if (player != null && ticket.command().op() == CommandOp.END_TURN && ticket.detail().contains("ask again") && newsRetries++ < 3) {
			// Civ cleared something out of the way (a "Continue" screen, units without orders) and needs a
			// moment: ask again by itself, so one ring of the bell is enough.
			newsRetryAt = tick + 30;
			newsRetryFor = player.getUUID();
			newsRetryWhat = refund.what();
			return;
		}
		if (player != null) {
			player.sendSystemMessage(Component.literal("[Civ] " + refund.what() + " not carried out: " + ticket.detail()).withStyle(ChatFormatting.YELLOW));
			rts.tell(player, ticket.detail());
			if (ticket.command().op() == CommandOp.END_TURN && refund.what().equals(END_TURN_ASKED)) {
				openWhatCivWaitsFor(player);
			}
			if (!refund.items().isEmpty()) {
				ItemStack back = refund.items().copy();
				if (!player.getInventory().add(back) || !back.isEmpty()) {
					player.drop(back, false, net.minecraft.util.Prediction.SERVER_ONLY);
				}
			}
		}
	}

	/** A player broke a block; felled logs count towards clearing a forest. */
	void blockBroken(ServerPlayer player, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		if (state.is(net.minecraft.tags.BlockTags.LOGS)) {
			gameplay.logBroken(player, pos);
		}
	}

	/** The technology the viewer's civilization lacks to craft this item, or null when it may be crafted. */
	String missingTech(String item) {
		WorldState state = state();
		if (!techGate || state == null) {
			return null;
		}
		return nl.jordythomassen.livingworld.gameplay.TechGate.missing(item, state.techs());
	}

	static void say(ServerPlayer player, String text) {
		player.sendSystemMessage(Component.literal(text));
		LivingWorldServer running = LivingWorldMod.session();
		if (running != null) {
			running.rts.tell(player, text);
		}
		LivingWorldMod.LOG.info("Living World: told {}: {}", player.getName().getString(), text);
	}

	String describe() {
		WorldState state = link.state();
		return "Link: " + link.status().text + (link.statusDetail().isEmpty() ? "" : " (" + link.statusDetail() + ")") + "\n"
			+ (state == null ? "No Civ game shown." : "Game " + state.session().gameId() + ", turn " + state.turn() + ", " + state.units().size() + " units and "
			+ state.cities().size() + " cities in sight") + "\n"
			+ "Round trip " + link.roundTripMicros() / 1000.0 + " ms, hints from Civ " + link.hintsSeen() + ", commands waiting " + link.commandsWaiting() + "\n"
			+ "Board: " + painter.paintedChunks() + " chunks painted, " + painter.changedBlocks() + " blocks changed, " + painter.waiting() + " waiting, "
			+ painter.rememberedChunks() + " remembered\n"
			+ "Characters: " + proxies.unitCount() + " units, " + proxies.cityCount() + " cities, " + proxies.shown() + " shown, follow camera " + (follow ? "on" : "off");
	}
}
