package nl.jordythomassen.livingworld.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import nl.jordythomassen.livingworld.link.CivLink;
import nl.jordythomassen.livingworld.link.LinkStatus;
import nl.jordythomassen.livingworld.mock.MockCiv;
import nl.jordythomassen.livingworld.mock.MockTransport;
import nl.jordythomassen.livingworld.script.CivScript;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.Legend;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldEvent;
import nl.jordythomassen.livingworld.state.WorldState;
import nl.jordythomassen.livingworld.tuner.LongLua;
import nl.jordythomassen.livingworld.tuner.TunerTransport;

/**
 * Command-line diagnostics for the bridge core, without Minecraft.
 *
 * <pre>
 * states                    list the game's Lua states
 * exec &lt;state&gt; &lt;lua&gt;        run one line of Lua in a state and print what it prints
 * run &lt;state&gt; &lt;file.lua&gt;    run a Lua file in a state
 * watch [seconds]           sync with the game and print status, a summary and every change
 * mock [seconds]            the same against the built-in mock game
 * cmd move &lt;owner&gt; &lt;id&gt; &lt;x&gt; &lt;y&gt; | cmd prod|heal &lt;owner&gt; &lt;id&gt; &lt;amount&gt;
 *                           send one allowlisted command, as Minecraft would, and report its fate
 * relay [port]              hold the game's one debug-socket slot and let local tools connect in turn
 * </pre>
 * Options: {@code --host}, {@code --port}, {@code --state}. The raw Lua commands exist only here,
 * for the person at the keyboard; the Minecraft mod has no such path.
 */
public final class Main {
	private static nl.jordythomassen.livingworld.link.PlayerMarker demoMarker;
	private static boolean demoWalk;

	private Main() {
	}

	public static void main(String[] argv) throws Exception {
		List<String> args = new ArrayList<>(Arrays.asList(argv));
		String host = option(args, "--host", "127.0.0.1");
		int port = Integer.parseInt(option(args, "--port", Integer.toString(TunerTransport.DEFAULT_PORT)));
		String state = option(args, "--state", CivScript.STATE);
		String markerAt = option(args, "--marker", null);
		boolean follow = args.remove("--follow");
		boolean walk = args.remove("--walk");
		if (markerAt != null) {
			String[] uv = markerAt.split(",");
			demoMarker = new nl.jordythomassen.livingworld.link.PlayerMarker(
				new nl.jordythomassen.livingworld.coords.GridPos(Double.parseDouble(uv[0]), Double.parseDouble(uv[1])), true, follow, "Minecraft player (test)");
			demoWalk = walk;
		}
		if (args.isEmpty()) {
			System.out.println("usage: states | exec <state> <lua> | run <state> <file> | watch [seconds] | mock [seconds]");
			return;
		}
		switch (args.get(0)) {
			case "states" -> {
				try (TunerTransport tuner = TunerTransport.connect(host, port, state, 3_000)) {
					System.out.println(tuner.app());
					for (Map.Entry<Integer, String> e : tuner.states().entrySet()) {
						System.out.println(e.getKey() + "\t" + e.getValue());
					}
					System.out.println(tuner.states().size() + " states");
				}
			}
			case "exec" -> {
				try (TunerTransport tuner = TunerTransport.connect(host, port, state, 3_000)) {
					String marker = "__lwdone";
					String lua = String.join(" ", args.subList(2, args.size())) + ";print(\"" + marker + "\")";
					for (String line : tuner.callIn(args.get(1), lua, marker, 15_000)) {
						if (!line.contains(marker)) {
							System.out.println(line);
						}
					}
				}
			}
			case "run" -> {
				try (TunerTransport tuner = TunerTransport.connect(host, port, state, 3_000)) {
					for (String line : LongLua.run(tuner, args.get(1), Files.readString(Path.of(args.get(2))), "__lwdone", 30_000)) {
						System.out.println(line);
					}
				}
			}
			case "cmd" -> command(new CivLink(() -> TunerTransport.connect(host, port, state, 3_000), CivScript::source, System.out::println), args);
			case "relay" -> new nl.jordythomassen.livingworld.tuner.TunerRelay(host, port, args.size() > 1 ? Integer.parseInt(args.get(1)) : 4319).run();
			case "watch" -> watch(new CivLink(() -> TunerTransport.connect(host, port, state, 3_000), CivScript::source, System.out::println),
				args.size() > 1 ? Integer.parseInt(args.get(1)) : 30, null);
			case "mock" -> {
				MockCiv civ = new MockCiv(40, 25, 7);
				MockTransport transport = new MockTransport(civ);
				watch(new CivLink(() -> transport, CivScript::source, System.out::println), args.size() > 1 ? Integer.parseInt(args.get(1)) : 10, civ);
			}
			default -> System.out.println("unknown command " + args.get(0));
		}
	}

	/** cmd move|prod|heal <owner> <id> [x y | amount]: one command through the same path Minecraft uses. */
	private static void command(CivLink link, List<String> args) throws InterruptedException {
		Thread thread = new Thread(link, "living-world-link");
		thread.setDaemon(true);
		thread.start();
		long deadline = System.currentTimeMillis() + 15_000;
		while (link.status() != LinkStatus.LIVE && System.currentTimeMillis() < deadline) {
			Thread.sleep(100);
		}
		WorldState state = link.state();
		if (state == null) {
			System.out.println("not live: " + link.status().text + " " + link.statusDetail());
			return;
		}
		nl.jordythomassen.livingworld.commands.CommandOp op = switch (args.get(1)) {
			case "move" -> nl.jordythomassen.livingworld.commands.CommandOp.UNIT_MOVE_TO;
			case "prod" -> nl.jordythomassen.livingworld.commands.CommandOp.CITY_ADD_PRODUCTION;
			default -> nl.jordythomassen.livingworld.commands.CommandOp.UNIT_HEAL;
		};
		var target = new nl.jordythomassen.livingworld.state.EntityKey(Integer.parseInt(args.get(2)), Integer.parseInt(args.get(3)));
		var plot = op == nl.jordythomassen.livingworld.commands.CommandOp.UNIT_MOVE_TO
			? new nl.jordythomassen.livingworld.coords.PlotCoord(Integer.parseInt(args.get(4)), Integer.parseInt(args.get(5))) : null;
		int amount = plot == null ? Integer.parseInt(args.get(4)) : 0;
		String id = "cli-" + Long.toHexString(System.nanoTime());
		var ticket = link.submit(new nl.jordythomassen.livingworld.commands.GameCommand(id, id, state.session().bootId(), state.session().gameId(),
			"cli", state.turn(), op, target, plot, amount));
		nl.jordythomassen.livingworld.commands.CommandStatus last = null;
		long end = System.currentTimeMillis() + 25_000;
		while (System.currentTimeMillis() < end) {
			if (ticket.status() != last) {
				last = ticket.status();
				System.out.println("command " + last + (ticket.detail().isEmpty() ? "" : ": " + ticket.detail())
					+ (ticket.after() != Long.MIN_VALUE ? " (before " + ticket.before() + ", after " + ticket.after() + ")" : ""));
			}
			if (last.finished()) {
				break;
			}
			Thread.sleep(50);
		}
		for (WorldEvent event : link.drainEvents()) {
			if (!(event instanceof WorldEvent.SessionStarted)) {
				System.out.println("event: " + describe(event));
			}
		}
		link.stop();
	}

	private static String option(List<String> args, String name, String fallback) {
		int at = args.indexOf(name);
		if (at < 0 || at + 1 >= args.size()) {
			return fallback;
		}
		String value = args.get(at + 1);
		args.remove(at + 1);
		args.remove(at);
		return value;
	}

	private static void watch(CivLink link, int seconds, MockCiv mock) throws InterruptedException {
		Thread thread = new Thread(link, "living-world-link");
		thread.setDaemon(true);
		thread.start();
		long end = System.currentTimeMillis() + seconds * 1000L;
		boolean summarised = false;
		long nextTurn = System.currentTimeMillis() + 2_000;
		while (System.currentTimeMillis() < end) {
			Thread.sleep(demoWalk ? 50 : 200);
			if (mock != null && System.currentTimeMillis() > nextTurn) {
				synchronized (mock) {
					mock.endTurn();
				}
				nextTurn += 2_000;
			}
			if (demoMarker != null) {
				// --marker u,v shows the player marker there; --walk moves it in a slow circle.
				double t = (System.currentTimeMillis() % 20_000) / 20_000.0 * 2 * Math.PI;
				link.setMarker(demoWalk ? new nl.jordythomassen.livingworld.link.PlayerMarker(
					new nl.jordythomassen.livingworld.coords.GridPos(demoMarker.pos().u() + Math.cos(t) * 1.5, demoMarker.pos().v() + Math.sin(t) * 1.5),
					true, demoMarker.follow(), demoMarker.label()) : demoMarker);
			}
			WorldState state = link.state();
			if (state != null && link.status() == LinkStatus.LIVE && !summarised) {
				summarised = true;
				summary(state);
				System.out.println("round trip " + link.roundTripMicros() / 1000.0 + " ms");
			}
			for (WorldEvent event : link.drainEvents()) {
				System.out.println("event: " + describe(event));
			}
		}
		System.out.println("status: " + link.status().text + " " + link.statusDetail() + ", last round trip " + link.roundTripMicros() / 1000.0
			+ " ms, hints from Civ " + link.hintsSeen());
		link.stop();
	}

	private static String describe(WorldEvent event) {
		return switch (event) {
			case WorldEvent.SessionStarted e -> "session " + e.session().gameId() + " boot " + e.session().bootId();
			case WorldEvent.TurnChanged e -> "turn " + e.from() + " -> " + e.to();
			case WorldEvent.UnitAppeared e -> "unit appeared " + e.unit().name() + " " + e.unit().key() + " at " + e.unit().at();
			case WorldEvent.UnitMoved e -> "unit moved " + e.unit().name() + " " + e.unit().key() + " " + e.from() + " -> " + e.unit().at();
			case WorldEvent.UnitChanged e -> "unit changed " + e.unit().name() + " " + e.unit().key() + " hp " + e.unit().hp() + " moves " + e.unit().moves();
			case WorldEvent.UnitGone e -> "unit gone " + e.last().name() + " " + e.last().key();
			case WorldEvent.CityAppeared e -> "city appeared " + e.city().name() + " at " + e.city().at();
			case WorldEvent.CityChanged e -> "city changed " + e.city().name() + " pop " + e.city().population() + " production " + e.city().production() + "/" + e.city().productionNeeded();
			case WorldEvent.CityChangedHands e -> "city changed hands " + e.city().name() + " " + e.before().key().owner() + " -> " + e.city().key().owner();
			case WorldEvent.CityGone e -> "city gone " + e.last().name();
			case WorldEvent.PlotChanged e -> "plot changed " + e.at();
			case WorldEvent.WarChanged e -> (e.atWar() ? "war with " : "peace with ") + e.civ().name();
			case WorldEvent.CivChanged e -> "civ changed " + e.civ().name();
			case WorldEvent.TechsLearned e -> "learned " + e.techs();
			case WorldEvent.Notice e -> "notice: " + e.text();
			case WorldEvent.EraChanged e -> "era " + e.from() + " -> " + e.to();
		};
	}

	private static void summary(WorldState state) {
		var grid = state.session().grid();
		System.out.println("game " + state.session().gameId() + ", turn " + state.turn() + ", map " + grid.width() + "x" + grid.height()
			+ (grid.wrapX() ? " (wraps)" : "") + ", odd rows shifted " + grid.oddRowShift() + ", viewer player " + state.session().viewer());
		System.out.println("civ world layout " + state.session().civLayout());
		int revealed = 0;
		for (int i = 0; i < state.plotCount(); i++) {
			if (state.plot(i).revealed()) {
				revealed++;
			}
		}
		System.out.println("plots revealed " + revealed + " of " + state.plotCount() + ", legend entries " + state.legend().size());
		if (state.empire() != null) {
			var e = state.empire();
			System.out.println("empire: " + e.yearText() + ", " + e.era() + ", gold " + e.gold() + " (" + e.goldPerTurn() + "/turn), science " + e.science()
				+ ", happiness " + e.happiness() + ", researching " + (e.researchName().isEmpty() ? "nothing" : e.researchName() + " (" + e.researchTurns() + " turns)"));
			System.out.println("techs known: " + state.techs());
			state.relations().values().forEach(r -> System.out.println("  relation " + r));
		}
		for (CivInfo civ : state.civs().values()) {
			System.out.println("  civ " + civ.id() + " " + civ.name() + " (" + civ.leader() + ")" + (civ.minor() ? " city-state" : "")
				+ (civ.barbarian() ? " barbarians" : "") + (civ.atWarWithViewer() ? " AT WAR" : "") + String.format(" #%06X", civ.color()));
		}
		for (CityState city : state.cities().values()) {
			PlotState plot = state.plot(city.at());
			System.out.println("  city " + city.key() + " " + city.name() + " at " + city.at() + " pop " + city.population()
				+ (city.details() ? " building " + city.productionName() + " " + city.production() + "/" + city.productionNeeded() : "")
				+ " on " + state.legend().type(Legend.Kind.TERRAIN, plot.terrain()));
		}
		for (UnitState unit : state.units().values()) {
			System.out.println("  unit " + unit.key() + " " + unit.name() + " (" + unit.type() + ") at " + unit.at() + " hp " + unit.hp() + "/" + unit.maxHp()
				+ " moves " + unit.moves() + "/" + unit.maxMoves());
		}
	}
}
