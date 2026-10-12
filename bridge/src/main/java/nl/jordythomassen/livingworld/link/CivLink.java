package nl.jordythomassen.livingworld.link;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;
import nl.jordythomassen.livingworld.commands.CommandTicket;
import nl.jordythomassen.livingworld.commands.CommandTracker;
import nl.jordythomassen.livingworld.commands.GameCommand;
import nl.jordythomassen.livingworld.protocol.CommandResult;
import nl.jordythomassen.livingworld.protocol.Heartbeat;
import nl.jordythomassen.livingworld.protocol.Hint;
import nl.jordythomassen.livingworld.protocol.StateAssembler;
import nl.jordythomassen.livingworld.protocol.Wire;
import nl.jordythomassen.livingworld.state.WorldDiff;
import nl.jordythomassen.livingworld.state.WorldEvent;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * Keeps one Civilization V game and this side in step.
 *
 * <p>Runs on its own thread so neither game ever waits on the other: it connects, loads our Lua
 * into the game when it is missing, exchanges a small heartbeat several times a second (which
 * also carries the player's position for the on-map marker), fetches fresh lists when Civ hints
 * that something changed and at a slow regular beat regardless, and turns the difference between
 * two pictures into events. Reconnects on its own; neither game has to be restarted.
 */
public final class CivLink implements GameLink {
	public static final long TICK_MS = 100;
	/** Units and cities are re-read at least this often even without a hint from Civ. */
	public static final long UNITS_EVERY_MS = 2_000;
	/** Plots (terrain, borders, improvements, fog) are re-read at least this often. */
	public static final long PLOTS_EVERY_MS = 60_000;
	/** However often Civ hints at it, plots are not re-read more often than this within a turn. */
	public static final long PLOTS_AT_MOST_EVERY_MS = 1_500;
	private static final long DEFAULT_RETRY_MS = 3_000;
	/**
	 * After losing a connection that worked, wait this long before making a new one. The game
	 * takes one client at a time and needs a while to notice that the last one has gone; a
	 * connection that arrives before that is never answered and, on the real game, left its
	 * debug socket unusable until the game was restarted. Reconnecting 16 to 22 seconds after a
	 * disconnect worked every time it was tried.
	 */
	public static final long RECONNECT_GRACE_MS = 20_000;
	private static final long CALL_TIMEOUT_MS = 5_000;
	private static final long SNAP_TIMEOUT_MS = 20_000;

	/** Opens the channel into the game; throws when the game is not there. */
	public interface Connector {
		CivTransport connect() throws IOException;
	}

	private final Connector connector;
	private final Supplier<String> bootstrap;
	private final Consumer<String> log;
	private final CommandTracker commands;
	private final ConcurrentLinkedQueue<WorldEvent> events = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<CommandTicket> ticketUpdates = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Question> questions = new ConcurrentLinkedQueue<>();

	private record Question(String kind, int a, int c, Consumer<List<nl.jordythomassen.livingworld.protocol.Records.Option>> answer) {
	}
	private final StateAssembler assembler = new StateAssembler();

	private volatile WorldState state;
	private volatile LinkStatus status = LinkStatus.NO_GAME;
	private volatile String statusDetail = "";
	private volatile PlayerMarker marker = PlayerMarker.HIDDEN;
	private volatile boolean running = true;
	private volatile boolean viewerTurn;
	private volatile long roundTripMicros;
	private volatile long hintsSeen;

	private volatile long retryMs = DEFAULT_RETRY_MS;
	private volatile long reconnectGraceMs = RECONNECT_GRACE_MS;
	private volatile long notBefore;
	private volatile long plotsAtMostEveryMs = PLOTS_AT_MOST_EVERY_MS;
	private CivTransport transport;
	private long seq;
	private long lastUnitsRev = -1;
	private long lastCitiesRev = -1;
	private long lastPlotsRev = -1;
	private long lastCivsRev = -1;
	private int lastTurn = Integer.MIN_VALUE;
	private long lastUnitsAt;
	private long lastPlotsAt;
	private int builtVersion = -1;
	private String loggedStatus = "";

	public CivLink(Connector connector, Supplier<String> bootstrap, Consumer<String> log) {
		this.connector = connector;
		this.bootstrap = bootstrap;
		this.log = log;
		this.commands = new CommandTracker(System::currentTimeMillis, ticketUpdates::add);
	}

	// ---- for the other thread ----

	/** The latest complete picture of the Civ game, or null while there is none. */
	public WorldState state() {
		return state;
	}

	public LinkStatus status() {
		return status;
	}

	public String statusDetail() {
		return statusDetail;
	}

	public boolean viewerTurn() {
		return viewerTurn;
	}

	public long roundTripMicros() {
		return roundTripMicros;
	}

	public long hintsSeen() {
		return hintsSeen;
	}

	public int commandsWaiting() {
		return commands.waiting();
	}

	public void setMarker(PlayerMarker newMarker) {
		marker = newMarker;
	}

	/** Changes since the last call, oldest first. */
	public List<WorldEvent> drainEvents() {
		List<WorldEvent> out = new ArrayList<>();
		for (WorldEvent e; (e = events.poll()) != null; ) {
			out.add(e);
		}
		return out;
	}

	/** Commands whose status changed since the last call. */
	public List<CommandTicket> drainTicketUpdates() {
		List<CommandTicket> out = new ArrayList<>();
		for (CommandTicket t; (t = ticketUpdates.poll()) != null; ) {
			out.add(t);
		}
		return out;
	}

	public CommandTicket submit(GameCommand command) {
		return commands.submit(command, state);
	}

	/**
	 * Asks Civ what it offers right now: kind "build" (what city {@code a} can start on),
	 * "research", or "works" (what can be built on plot {@code a},{@code c}). The answer arrives
	 * later, on the link thread: the list, or null when Civ could not be asked.
	 */
	public void ask(String kind, int a, int c, Consumer<List<nl.jordythomassen.livingworld.protocol.Records.Option>> answer) {
		if (status != LinkStatus.LIVE || questions.size() > 16) {
			answer.accept(null);
			return;
		}
		questions.add(new Question(kind, a, c, answer));
	}

	public void stop() {
		running = false;
	}

	/** How long to wait before trying again when the game is not there (tests set this to 0). */
	public void setRetryMs(long ms) {
		retryMs = ms;
		plotsAtMostEveryMs = ms == 0 ? 0 : PLOTS_AT_MOST_EVERY_MS;
		reconnectGraceMs = ms == 0 ? 0 : RECONNECT_GRACE_MS;
	}

	/**
	 * One beat of the link on the calling thread, connecting first when needed. The thread loop
	 * is this in a loop; tests call it directly.
	 */
	public void pumpOnce() throws IOException {
		if (transport == null) {
			transport = connector.connect();
			log.accept("Living World: connected to Civilization V (" + transport.describe() + ")");
			forget();
		}
		step();
	}

	/** Drops the connection as the thread loop would after an error (tests use this too). */
	public void failed(String why) {
		drop(LinkStatus.NO_GAME, why);
	}

	/** The transport in use, for diagnostics; null while disconnected. Link thread only. */
	public CivTransport transport() {
		return transport;
	}

	// ---- the link thread ----

	@Override
	public void run() {
		while (running) {
			try {
				if (System.currentTimeMillis() < notBefore) {
					Thread.sleep(200);
					continue;
				}
				long started = System.currentTimeMillis();
				pumpOnce();
				long left = TICK_MS - (System.currentTimeMillis() - started);
				if (left > 0) {
					Thread.sleep(left);
				}
			} catch (NoMatchException e) {
				// The game is there but in its menus: keep the connection (it takes one client at a
				// time) and look again shortly.
				state = null;
				commands.onDisconnect();
				setStatus(LinkStatus.NO_MATCH, "");
				pause(retryMs);
			} catch (IOException e) {
				drop(LinkStatus.NO_GAME, e.getMessage());
				pause(retryMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (RuntimeException e) {
				// A bug on our side must not end the link for the rest of the session.
				log.accept("Living World: link error, reconnecting: " + e);
				drop(LinkStatus.NO_GAME, e.toString());
				pause(retryMs);
			}
		}
		drop(LinkStatus.OFF, "");
	}

	private void pause(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			running = false;
		}
	}

	private void setStatus(LinkStatus newStatus, String detail) {
		status = newStatus;
		statusDetail = detail == null ? "" : detail;
		String line = newStatus + " " + statusDetail;
		if (!line.equals(loggedStatus)) {
			loggedStatus = line;
			log.accept("Living World: " + newStatus.text + (statusDetail.isEmpty() ? "" : " (" + statusDetail + ")"));
		}
	}

	private void drop(LinkStatus newStatus, String detail) {
		if (transport != null) {
			transport.close();
			transport = null;
			notBefore = System.currentTimeMillis() + reconnectGraceMs;
		}
		commands.onDisconnect();
		for (Question q; (q = questions.poll()) != null; ) {
			q.answer().accept(null);
		}
		setStatus(newStatus, detail);
	}

	/** Identifies this exact copy of the script, so a changed script replaces a loaded older one. */
	private String revision() {
		return Integer.toHexString(bootstrap.get().hashCode());
	}

	private void forget() {
		assembler.reset();
		lastUnitsRev = lastCitiesRev = lastPlotsRev = lastCivsRev = -1;
		lastTurn = Integer.MIN_VALUE;
		lastUnitsAt = lastPlotsAt = 0;
		builtVersion = -1;
	}

	private List<List<String>> call(java.util.function.LongFunction<String> lua, long timeoutMs) throws IOException {
		long mySeq = ++seq;
		String code = lua.apply(mySeq);
		long t0 = System.nanoTime();
		List<String> lines = transport.call(code, LuaCalls.endMarker(mySeq), timeoutMs);
		roundTripMicros = (System.nanoTime() - t0) / 1000;
		List<List<String>> records = new ArrayList<>(lines.size());
		boolean mine = false;
		for (String line : lines) {
			List<String> f = Wire.parse(line);
			if (f == null) {
				continue;
			}
			// Only lines between our own begin and end markers belong to this call.
			if (f.get(0).equals(">")) {
				mine = f.size() > 1 && f.get(1).equals(Long.toString(mySeq));
				continue;
			}
			if (f.get(0).equals("<")) {
				mine = false;
				continue;
			}
			if (mine) {
				records.add(f);
			}
		}
		return records;
	}

	private void feed(List<List<String>> records) {
		for (List<String> f : records) {
			assembler.accept(f);
		}
		for (String problem : assembler.drainProblems()) {
			log.accept("Living World: dropped a line from Civ: " + problem);
		}
	}

	private void step() throws IOException {
		// 1. Heartbeat + marker. The same call tells us when our Lua is missing from the game
		//    (it is gone after every load, and absent in the main menu).
		PlayerMarker m = marker;
		WorldState known = state;
		nl.jordythomassen.livingworld.coords.PlotCoord under = known == null ? null
			: known.session().grid().normalize(known.session().grid().plotAt(m.pos()));
		int plotX = under == null ? -1 : under.x();
		int plotY = under == null ? -1 : under.y();
		String revision = revision();
		List<List<String>> reply = call(s -> LuaCalls.tick(s, revision, m.pos().u(), m.pos().v(), plotX, plotY, m.show() && under != null, m.follow(), m.label()), CALL_TIMEOUT_MS);
		boolean loaded = true;
		for (List<String> f : reply) {
			if (f.get(0).equals("B")) {
				loaded = false;
			}
		}
		if (!loaded) {
			setStatus(LinkStatus.SYNCING, "loading the Living World script into the game");
			forget();
			state = null;
			if (!inject()) {
				pause(retryMs);
			}
			return;
		}
		feed(reply);
		Heartbeat hb = assembler.heartbeat();
		if (hb == null) {
			throw new IOException("Civilization V answered without a heartbeat");
		}
		viewerTurn = hb.viewerTurn();
		List<Hint> hints = assembler.drainHints();
		hintsSeen += hints.size();

		// 3. Fetch what is missing, hinted at, or simply due.
		long now = System.currentTimeMillis();
		int mask = 0;
		if (!assembler.hasStatic()) {
			mask |= LuaCalls.SNAP_STATIC;
		}
		boolean turnChanged = hb.turn() != lastTurn;
		if (!assembler.hasPlots() || turnChanged || now - lastPlotsAt > PLOTS_EVERY_MS
			|| (hb.plotsRev() != lastPlotsRev && now - lastPlotsAt >= plotsAtMostEveryMs)) {
			mask |= LuaCalls.SNAP_PLOTS;
		}
		// Everything that changes with play is small and fetched together: units, cities, the
		// civs (era, war), and the viewer's empire, technologies and relations.
		if (hb.unitsRev() != lastUnitsRev || hb.citiesRev() != lastCitiesRev || hb.civsRev() != lastCivsRev || turnChanged
			|| now - lastUnitsAt > UNITS_EVERY_MS || (mask & LuaCalls.SNAP_PLOTS) != 0) {
			mask |= LuaCalls.SNAP_UNITS | LuaCalls.SNAP_CITIES | LuaCalls.SNAP_EMPIRE | LuaCalls.SNAP_CIVS;
		}
		if (mask != 0) {
			int wanted = mask;
			feed(call(s -> LuaCalls.snap(s, wanted), SNAP_TIMEOUT_MS));
			lastUnitsRev = hb.unitsRev();
			lastCitiesRev = hb.citiesRev();
			lastCivsRev = hb.civsRev();
			lastUnitsAt = now;
			if ((mask & LuaCalls.SNAP_PLOTS) != 0) {
				lastPlotsRev = hb.plotsRev();
				lastPlotsAt = now;
			}
			lastTurn = hb.turn();
		}

		// 4. A new complete picture: publish it and say what changed.
		if (assembler.version() != builtVersion) {
			WorldState built = assembler.build();
			if (built != null) {
				builtVersion = assembler.version();
				WorldState previous = state;
				List<WorldEvent> changes = WorldDiff.between(previous, built);
				state = built;
				events.addAll(changes);
				commands.onState(built);
				setStatus(LinkStatus.LIVE, "");
			} else {
				setStatus(LinkStatus.SYNCING, "waiting for the first complete picture");
			}
		} else if (state != null) {
			commands.onState(state);
		}

		// 5. Commands: results of earlier ones, then at most one new one per beat.
		for (CommandResult result : assembler.drainResults()) {
			commands.onResult(result, hb.turn());
		}
		// 6. One question per beat, if a menu is waiting for its choices.
		Question question = questions.poll();
		if (question != null) {
			List<nl.jordythomassen.livingworld.protocol.Records.Option> options = null;
			try {
				feed(call(s -> LuaCalls.options(s, question.kind(), question.a(), question.c()), CALL_TIMEOUT_MS));
				options = assembler.takeOptions();
			} finally {
				question.answer().accept(options);
			}
		}

		CommandTicket next = commands.nextToSend(state, viewerTurn);
		if (next != null) {
			feed(call(s -> LuaCalls.command(s, next.command()), CALL_TIMEOUT_MS));
			for (CommandResult result : assembler.drainResults()) {
				commands.onResult(result, hb.turn());
			}
		}
	}

	/** Sends our Lua into the game. False when the game has no match running to load it into. */
	private boolean inject() throws IOException {
		String source = bootstrap.get();
		List<String> chunks = BootstrapChunks.split(source, BootstrapChunks.CHUNK);
		long mySeq = ++seq;
		transport.call("LivingWorldBoot={}", null, CALL_TIMEOUT_MS);
		for (int i = 0; i < chunks.size(); i++) {
			transport.call("LivingWorldBoot[" + (i + 1) + "]=" + BootstrapChunks.longString(chunks.get(i)), null, CALL_TIMEOUT_MS);
		}
		String run = "local f,e=loadstring(table.concat(LivingWorldBoot),\"LivingWorld\");LivingWorldBoot=nil;"
			+ "print(\"LW\\t>\\t" + mySeq + "\");"
			+ "if f then local ok,err=pcall(f);if ok then LivingWorld.REV=" + Wire.luaString(revision()) + " end;"
			+ "print(\"LW\\tJ\\t\"..(ok and \"ok\" or tostring(err))) else print(\"LW\\tJ\\t\"..tostring(e)) end;"
			+ "print(\"LW\\t<\\t" + mySeq + "\\t1\")";
		List<String> lines = transport.call(run, LuaCalls.endMarker(mySeq), SNAP_TIMEOUT_MS);
		for (String line : lines) {
			List<String> f = Wire.parse(line);
			if (f != null && f.get(0).equals("J")) {
				String outcome = f.size() > 1 ? f.get(1) : "";
				if (outcome.equals("ok")) {
					log.accept("Living World: script loaded into Civilization V (" + chunks.size() + " parts)");
					return true;
				}
				if (outcome.contains("NOGAME")) {
					setStatus(LinkStatus.NO_MATCH, "");
				} else {
					setStatus(LinkStatus.SYNCING, "the script failed to load: " + outcome);
				}
				return false;
			}
		}
		setStatus(LinkStatus.SYNCING, "no answer when loading the script");
		return false;
	}
}
