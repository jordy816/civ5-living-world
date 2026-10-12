package nl.jordythomassen.livingworld.mock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nl.jordythomassen.livingworld.link.CivTransport;
import nl.jordythomassen.livingworld.protocol.Wire;

/**
 * Plays the part of the game's debug socket in front of a {@link MockCiv}: it recognises the
 * handful of Lua snippets this side sends and answers as the real game would.
 */
public final class MockTransport implements CivTransport {
	private static final Pattern RUN_BOOT = Pattern.compile("LW\\\\t>\\\\t(\\d+)");
	private static final Pattern TICK = Pattern.compile("then LivingWorld\\.tick\\((\\d+),(-?[\\d.]+),(-?[\\d.]+),(-?\\d+),(-?\\d+),(true|false),(true|false),\"(.*?)\"\\) else ");
	private static final Pattern SNAP = Pattern.compile("^LivingWorld\\.snap\\((\\d+),(\\d+)\\)$");
	private static final Pattern OPTS = Pattern.compile("^LivingWorld\\.opts\\((\\d+),\"([a-z]+)\",(-?\\d+),(-?\\d+)\\)$");
	private static final Pattern CMD = Pattern.compile("^LivingWorld\\.cmd\\((\\d+),\"([^\"]*)\",\"([^\"]*)\",(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)\\)$");

	private final MockCiv civ;
	/** Set to make every call fail, as if the game was closed. */
	public volatile boolean down;
	/** Lines to slip into the next reply, to test that foreign output is ignored. */
	public final List<String> noise = new ArrayList<>();
	public final List<String> sent = new ArrayList<>();
	private StringBuilder boot;
	public String lastBootSource = "";

	public MockTransport(MockCiv civ) {
		this.civ = civ;
	}

	@Override
	public synchronized List<String> call(String lua, String endMarker, long timeoutMs) throws IOException {
		if (down) {
			throw new IOException("mock game is closed");
		}
		sent.add(lua);
		if (lua.equals("LivingWorldBoot={}")) {
			boot = new StringBuilder();
			return List.of();
		}
		if (lua.startsWith("LivingWorldBoot[")) {
			// LivingWorldBoot[n]=[=*[\n ... ]=*]
			int open = lua.indexOf("[\n", lua.indexOf('='));
			int close = lua.lastIndexOf(']', lua.length() - 2);
			boot.append(lua, open + 2, close);
			return List.of();
		}
		Matcher m;
		if (lua.contains("loadstring(table.concat(LivingWorldBoot)") && (m = RUN_BOOT.matcher(lua)).find()) {
			String seq = m.group(1);
			lastBootSource = boot == null ? "" : boot.toString();
			List<String> out = new ArrayList<>();
			out.add(Wire.PREFIX + ">\t" + seq);
			if (!civ.inMatch) {
				out.add(Wire.PREFIX + "J\tNOGAME");
			} else {
				civ.loaded = true;
				out.add(Wire.PREFIX + "J\tok");
			}
			out.add(Wire.PREFIX + "<\t" + seq + "\t1");
			return out;
		}
		if ((m = TICK.matcher(lua)).find()) {
			if (!civ.loaded) {
				return framed(m.group(1), List.of(Wire.PREFIX + "B\t0"));
			}
			civ.markerPlotX = Integer.parseInt(m.group(4));
			civ.markerPlotY = Integer.parseInt(m.group(5));
			return framed(m.group(1), civ.tick(Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)),
				Boolean.parseBoolean(m.group(6)), Boolean.parseBoolean(m.group(7)), m.group(8)));
		}
		if (!civ.loaded) {
			// The real game answers a call to a missing function with a Lua error, not our markers.
			throw new IOException("no reply (script not loaded)");
		}
		if ((m = SNAP.matcher(lua)).matches()) {
			return framed(m.group(1), civ.snap(Integer.parseInt(m.group(2))));
		}
		if ((m = OPTS.matcher(lua)).matches()) {
			return framed(m.group(1), civ.options(m.group(2), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))));
		}
		if ((m = CMD.matcher(lua)).matches()) {
			return framed(m.group(1), civ.command(m.group(2), m.group(3), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
				Integer.parseInt(m.group(6)), Integer.parseInt(m.group(7)), Integer.parseInt(m.group(8))));
		}
		throw new IOException("mock game does not understand: " + lua);
	}

	private List<String> framed(String seq, List<String> body) {
		List<String> out = new ArrayList<>(body.size() + 2 + noise.size());
		out.addAll(noise);
		noise.clear();
		out.add(Wire.PREFIX + ">\t" + seq);
		out.addAll(body);
		out.add(Wire.PREFIX + "<\t" + seq + "\t" + body.size());
		return out;
	}

	@Override
	public String describe() {
		return "mock Civ game " + civ.width + "x" + civ.height;
	}

	@Override
	public void close() {
	}
}
