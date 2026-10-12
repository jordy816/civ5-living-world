package nl.jordythomassen.livingworld.unciv;

import com.badlogic.gdx.files.FileHandle;
import com.unciv.UncivGame;
import com.unciv.models.metadata.BaseRuleset;
import com.unciv.models.metadata.GameSettings;
import com.unciv.models.ruleset.Ruleset;
import com.unciv.models.ruleset.RulesetCache;
import com.unciv.utils.Log;
import com.unciv.utils.LogBackend;
import com.unciv.utils.Tag;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Brings Unciv's game logic up inside this JVM, once: no window, no sound, no network. Unciv
 * calls this its console mode; its own AI-against-AI simulations run the same way.
 */
public final class UncivBoot {
	private static boolean done;

	private UncivBoot() {
	}

	/**
	 * @param dataDir where the rule files are unpacked (they are read from disk by Unciv)
	 * @param log receives what Unciv reports as errors; its debug chatter is dropped
	 */
	public static synchronized void ensure(Path dataDir, Consumer<String> log) throws IOException {
		if (done) {
			return;
		}
		Path jsons = unpackRulesets(dataDir);
		Log.INSTANCE.setBackend(new LogBackend() {
			@Override
			public void debug(Tag tag, String thread, String message) {
				// Map generation alone writes dozens of lines; nobody reads them here.
			}

			@Override
			public void error(Tag tag, String thread, String message) {
				log.accept("Unciv: " + message);
			}

			@Override
			public boolean isRelease() {
				return true;
			}

			@Override
			public String getSystemInfo() {
				return "Civ V Living World";
			}
		});
		UncivGame game = new UncivGame(true);
		UncivGame.Companion.setCurrent(game);
		GameSettings settings = new GameSettings();
		settings.setShowTutorials(false);
		settings.setTurnsBetweenAutosaves(1_000_000);
		// A city shoots at an enemy in range by itself when the turn ends; there is no button for it in Minecraft.
		settings.setCitiesAutoBombardAtEndOfTurn(true);
		// Nothing to hear: Minecraft makes the sounds. And a file system of its own, so that when a rule says
		// "play a sound" for the human player Unciv looks, finds none, and plays on instead of stopping.
		settings.setSoundEffectsVolume(0f);
		settings.setCitySoundsVolume(0f);
		settings.setMusicVolume(0f);
		HeadlessFiles files = new HeadlessFiles(dataDir);
		com.badlogic.gdx.Gdx.files = files;
		game.setFiles(new com.unciv.logic.files.UncivFiles(files, null));
		game.setSettings(settings);
		for (BaseRuleset base : BaseRuleset.values()) {
			Ruleset ruleset = new Ruleset();
			ruleset.setName(base.getFullName());
			ruleset.load(new FileHandle(jsons.resolve(base.getFullName()).toFile()));
			if (base == BaseRuleset.Civ_V_GnK) {
				// What Unciv's remake of the game leaves out and this mod adds, in Unciv's own rule language: read
				// into the same rules, the way Unciv reads a mod. Unciv's own files are left as they are.
				ruleset.load(new FileHandle(unpackOwnRules(dataDir).toFile()));
			}
			RulesetCache.INSTANCE.put(base.getFullName(), ruleset);
		}
		done = true;
	}

	/** The civilizations a player can lead, by name, once the engine is up; empty before. */
	public static synchronized java.util.List<String> nations() {
		java.util.List<String> out = new java.util.ArrayList<>();
		Ruleset rules = done ? RulesetCache.INSTANCE.get(BaseRuleset.Civ_V_GnK.getFullName()) : null;
		if (rules != null) {
			for (com.unciv.models.ruleset.nation.Nation nation : rules.getNations().values()) {
				if (!nation.isCityState() && !nation.isBarbarian() && !nation.getName().equals("Spectator")) {
					out.add(nation.getName());
				}
			}
		}
		java.util.Collections.sort(out);
		return out;
	}

	/** The rule files this mod adds (a civilization Unciv does not have), from this jar to dataDir/jsons-livingworld. */
	private static Path unpackOwnRules(Path dataDir) throws IOException {
		Path target = dataDir.toAbsolutePath().normalize().resolve("jsons-livingworld");
		Files.createDirectories(target);
		for (String name : new String[] { "Nations.json", "Units.json" }) {
			try (InputStream raw = UncivBoot.class.getResourceAsStream("/livingworld/rules/" + name)) {
				if (raw == null) {
					throw new IOException("a rule file is missing from this build (livingworld/rules/" + name + ")");
				}
				Files.write(target.resolve(name), raw.readAllBytes());
			}
		}
		return target;
	}

	/** The rule files, from the zip inside this jar to dataDir/jsons. Rewritten each start: they are small and belong to this build. */
	private static Path unpackRulesets(Path dataDir) throws IOException {
		Path target = dataDir.toAbsolutePath().normalize();
		try (InputStream raw = UncivBoot.class.getResourceAsStream("unciv-rulesets.zip")) {
			if (raw == null) {
				throw new IOException("the rule files are missing from this build (unciv-rulesets.zip)");
			}
			try (ZipInputStream zip = new ZipInputStream(raw)) {
				for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
					Path out = target.resolve(entry.getName()).normalize();
					if (entry.isDirectory() || !out.startsWith(target)) {
						continue;
					}
					Files.createDirectories(out.getParent());
					Files.write(out, zip.readAllBytes());
				}
			}
		}
		return target.resolve("jsons");
	}
}
