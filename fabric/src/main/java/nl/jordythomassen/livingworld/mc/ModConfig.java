package nl.jordythomassen.livingworld.mc;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** The mod's few settings, in config/livingworld.properties next to the game's other configs. */
public final class ModConfig {
	private static final String TEMPLATE = """
		# Civ V Living World
		# Where the game is played. builtin: inside Minecraft itself, nothing else needs to run.
		# civ5: a running Civilization V is shown and steered through its debug socket (the older way).
		engine=builtin
		# A new built-in game: map size (Tiny, Small, Medium, Large, Huge), map type (pangaea, continentAndIslands,
		# twoContinents, archipelago, innerSea, fractal), the number of other civilizations and of city-states,
		# difficulty (Settler, Chieftain, Warlord, Prince, King, Emperor, Immortal, Deity), and your nation (empty: random).
		game.mapSize=Small
		game.mapType=pangaea
		game.rivals=3
		game.cityStates=4
		game.difficulty=Prince
		game.nation=
		# How long a game takes: Quick (some 330 turns), Standard (500), Epic (750) or Marathon (1500).
		game.speed=Quick
		# true: your cities choose their own work when they have none. false: they wait until you tell them.
		governors=false
		# true: you rule from above (a top-down view with the mouse, like a strategy game). false: you walk the land.
		# F12 switches between the two while you play.
		rts=true
		# For engine=civ5: where Civilization V's debug socket is. It only exists while Civ runs with EnableTuner = 1
		# (the launcher sets that for the length of one session).
		civ.host=127.0.0.1
		civ.port=4318
		# true: a small pretend game on the civ5 path, for its tests.
		civ.mock=false
		# Width of one Civ hex in blocks. 32 gives room for a small town per hex; 16 and 64 also work.
		hexWidth=32
		# true: Civ's camera follows you as you walk into another hex.
		follow=true
		# true: open (or create) the Living World automatically when Minecraft starts.
		autoOpen=true
		# true: one Minecraft day (ten minutes) is one Civ turn. At dusk your turn ends by itself,
		# if Civilization V has nothing left to ask you. false: always noon, turns end only when you say.
		dayIsTurn=true
		# true: Minecraft crafting follows your civilization's technologies (no iron tools before Iron Working).
		techGate=true
		# true: walking into a hex explores the land around it in Civilization V, like a scout.
		explore=true
		""";

	public final boolean builtin;
	public final String mapSize;
	public final String mapType;
	public final int rivals;
	public final int cityStates;
	public final String difficulty;
	public final String nation;
	public final String speed;
	public final boolean governors;
	public final boolean rts;
	public final long seed;
	public final String civHost;
	public final int civPort;
	public final boolean mock;
	public final double hexWidth;
	public final boolean follow;
	public final boolean autoOpen;
	/** A world without a game starts one by the settings at once (test runs), instead of asking whoever comes in. */
	public final boolean autoStart;
	public final boolean dayIsTurn;
	public final boolean techGate;
	public final boolean explore;

	private ModConfig(Properties p) {
		builtin = !p.getProperty("engine", "builtin").trim().equalsIgnoreCase("civ5");
		mapSize = p.getProperty("game.mapSize", "Small").trim();
		mapType = p.getProperty("game.mapType", "pangaea").trim();
		rivals = number(p, "game.rivals", 3, 1, 12);
		cityStates = number(p, "game.cityStates", 4, 0, 20);
		difficulty = p.getProperty("game.difficulty", "Prince").trim();
		nation = p.getProperty("game.nation", "").trim();
		speed = p.getProperty("game.speed", "Quick").trim();
		governors = Boolean.parseBoolean(p.getProperty("governors", "false").trim());
		rts = Boolean.parseBoolean(p.getProperty("rts", "true").trim());
		long fixedSeed = 0;
		try {
			fixedSeed = Long.parseLong(p.getProperty("game.seed", "0").trim());
		} catch (NumberFormatException e) {
			// a random map
		}
		seed = fixedSeed;
		civHost = p.getProperty("civ.host", "127.0.0.1").trim();
		civPort = number(p, "civ.port", 4318, 1, 65535);
		mock = Boolean.parseBoolean(p.getProperty("civ.mock", "false").trim());
		hexWidth = number(p, "hexWidth", 32, 12, 96);
		follow = Boolean.parseBoolean(p.getProperty("follow", "true").trim());
		autoOpen = Boolean.parseBoolean(p.getProperty("autoOpen", "true").trim());
		autoStart = Boolean.parseBoolean(p.getProperty("game.autoStart", "false").trim()) || Boolean.getBoolean("livingworld.selftest") || Boolean.getBoolean("livingworld.autostart");
		dayIsTurn = Boolean.parseBoolean(p.getProperty("dayIsTurn", "true").trim());
		techGate = Boolean.parseBoolean(p.getProperty("techGate", "true").trim());
		explore = Boolean.parseBoolean(p.getProperty("explore", "true").trim());
	}

	private static int number(Properties p, String key, int fallback, int min, int max) {
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(p.getProperty(key, Integer.toString(fallback)).trim())));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** Changes one setting in the file, leaving every other line and comment as it is. */
	static void save(Path gameDir, String key, String value) {
		Path file = gameDir.resolve("config").resolve("livingworld.properties");
		try {
			java.util.List<String> lines = Files.exists(file) ? new java.util.ArrayList<>(Files.readAllLines(file)) : new java.util.ArrayList<>();
			boolean found = false;
			for (int i = 0; i < lines.size(); i++) {
				if (lines.get(i).startsWith(key + "=")) {
					lines.set(i, key + "=" + value);
					found = true;
				}
			}
			if (!found) {
				lines.add(key + "=" + value);
			}
			Files.createDirectories(file.getParent());
			Files.writeString(file, String.join("\n", lines) + "\n");
		} catch (IOException e) {
			LivingWorldMod.LOG.warn("Living World: could not write {}", file, e);
		}
	}

	/** Reads the settings, writing the commented template first when there is no file yet. */
	public static ModConfig load(Path gameDir) {
		Path file = gameDir.resolve("config").resolve("livingworld.properties");
		Properties p = new Properties();
		try {
			if (!Files.exists(file)) {
				Files.createDirectories(file.getParent());
				Files.writeString(file, TEMPLATE);
			}
			try (Reader in = Files.newBufferedReader(file)) {
				p.load(in);
			}
			// A file written by an older build: add the settings that came later, with their explanation.
			StringBuilder added = new StringBuilder();
			String[] lines = TEMPLATE.split("\n");
			int comment = 0;
			for (int i = 0; i < lines.length; i++) {
				if (lines[i].startsWith("#")) {
					continue;
				}
				int eq = lines[i].indexOf('=');
				if (eq > 0 && !p.containsKey(lines[i].substring(0, eq))) {
					for (int j = comment; j <= i; j++) {
						added.append(lines[j]).append('\n');
					}
					p.setProperty(lines[i].substring(0, eq), lines[i].substring(eq + 1));
				}
				comment = i + 1;
			}
			if (added.length() > 0) {
				String now = Files.readString(file);
				Files.writeString(file, now + (now.endsWith("\n") ? "" : "\n") + added);
			}
		} catch (IOException e) {
			LivingWorldMod.LOG.warn("Living World: could not read {}, using defaults", file, e);
		}
		// A system property wins, so a test run can point at the mock without editing files.
		for (String key : new String[] { "engine", "game.mapSize", "game.mapType", "game.rivals", "game.cityStates", "game.difficulty", "game.nation", "game.seed", "game.speed", "governors", "rts",
			"civ.host", "civ.port", "civ.mock", "hexWidth", "follow", "autoOpen", "dayIsTurn", "techGate", "explore" }) {
			String override = System.getProperty("livingworld." + key);
			if (override != null) {
				p.setProperty(key, override);
			}
		}
		return new ModConfig(p);
	}
}
