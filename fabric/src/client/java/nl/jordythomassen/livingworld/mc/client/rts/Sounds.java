package nl.jordythomassen.livingworld.mc.client.rts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import nl.jordythomassen.livingworld.board.BoardModel;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.mc.Looks;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldDiff;
import nl.jordythomassen.livingworld.state.WorldEvent;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * What the game sounds like: a unit that walks, one that is hurt, one that falls; a city
 * founded, something built, a wonder, a discovery, the horn of war. They are Minecraft's own
 * sounds, of the creature a unit is shown as, and there are few of them: the nearest things that
 * happened, never a row of twenty when the other civilizations move.
 *
 * <p>From above a sound is louder the nearer its place is to what the ruler looks at; on foot it
 * comes from its place in the world, and is heard only from close by.
 */
final class Sounds {
	private static final Map<String, SoundEvent> KNOWN = new HashMap<>();
	private static final SoundEvent NONE = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("livingworld", "none"));

	private Sounds() {
	}

	private static SoundEvent sound(String id) {
		SoundEvent found = KNOWN.computeIfAbsent(id, key -> BuiltInRegistries.SOUND_EVENT.getOptional(Identifier.withDefaultNamespace(key)).orElse(NONE));
		return found == NONE ? null : found;
	}

	private static SoundEvent first(String... ids) {
		for (String id : ids) {
			SoundEvent found = sound(id);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static final java.util.Set<String> MISSED = new java.util.HashSet<>();

	/** A sound of the screen itself: a click that did something, news that came in. */
	static void ui(String id, float volume, float pitch) {
		SoundEvent sound = sound(id);
		if (sound == null && MISSED.add(id)) {
			nl.jordythomassen.livingworld.mc.LivingWorldMod.LOG.warn("Living World: this game has no sound called {}", id);
		}
		if (sound != null && volume > 0.02f) {
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
		}
	}

	/** A sound at a hex: as loud as it is near, for whoever looks down; from its place in the world for whoever walks. */
	private static void at(BoardModel model, PlotCoord plot, SoundEvent sound, float volume, float pitch) {
		Minecraft mc = Minecraft.getInstance();
		if (sound == null || mc.level == null || mc.player == null) {
			return;
		}
		double x = model.centerX(plot) + 0.5;
		double z = model.centerZ(plot) + 0.5;
		if (RtsClient.enabled()) {
			double far = Math.hypot(x - RtsCamera.focusX, z - RtsCamera.focusZ) / (model.layout().hexWidth() * 7.0);
			float heard = (float) (volume * Math.max(0.0, 1.0 - far));
			if (heard > 0.04f) {
				mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, heard));
			}
		} else {
			mc.level.playLocalSound(x, BoardModel.LAND + 2, z, sound, SoundSource.NEUTRAL, volume * 2.5f, pitch, false);
		}
	}

	private static String creature(WorldState state, UnitState unit) {
		CivInfo civ = state.civ(unit.key().owner());
		Looks.Figure figure = Looks.figure(unit, civ != null && civ.barbarian());
		return figure.mount().isEmpty() ? figure.id() : figure.mount();
	}

	private static double far(BoardModel model, PlotCoord plot) {
		Minecraft mc = Minecraft.getInstance();
		double x = model.centerX(plot) + 0.5;
		double z = model.centerZ(plot) + 0.5;
		return RtsClient.enabled() || mc.player == null ? Math.hypot(x - RtsCamera.focusX, z - RtsCamera.focusZ) : Math.hypot(x - mc.player.getX(), z - mc.player.getZ());
	}

	/** The game went from one picture to the next: what happened in between is heard, the nearest first and not much of it. */
	static void changed(WorldState before, WorldState after, BoardModel model) {
		if (before == null || after == null || model == null || !before.session().gameId().equals(after.session().gameId()) || after.turn() - before.turn() > 1
			|| after.turn() < before.turn()) {
			return;
		}
		List<WorldEvent> events;
		try {
			events = WorldDiff.between(before, after);
		} catch (RuntimeException e) {
			return;
		}
		int me = after.session().viewer();
		UnitState step = null;
		UnitState hurt = null;
		UnitState fell = null;
		boolean built = false;
		for (WorldEvent event : events) {
			switch (event) {
				case WorldEvent.UnitMoved e -> {
					if (step == null || far(model, e.unit().at()) < far(model, step.at())) {
						step = e.unit();
					}
				}
				case WorldEvent.UnitChanged e -> {
					if (e.unit().hp() < e.before().hp() && (hurt == null || far(model, e.unit().at()) < far(model, hurt.at()))) {
						hurt = e.unit();
					}
				}
				case WorldEvent.UnitGone e -> {
					// Gone and it was wounded, or it was the player's own: fallen, as far as can be told.
					if ((e.last().hp() < e.last().maxHp() || e.last().key().owner() == me) && (fell == null || far(model, e.last().at()) < far(model, fell.at()))) {
						fell = e.last();
					}
				}
				case WorldEvent.CityAppeared e -> {
					if (e.city().key().owner() == me) {
						at(model, e.city().at(), sound("block.bell.use"), 0.9f, 0.8f);
					}
				}
				case WorldEvent.CityChanged e -> {
					CityState city = e.city();
					if (city.key().owner() != me) {
						continue;
					}
					if (!city.wonders().equals(e.before().wonders()) && city.wonders().size() > e.before().wonders().size()) {
						ui("ui.toast.challenge_complete", 0.5f, 1.0f);
					} else if (!built && city.buildings().size() > e.before().buildings().size()) {
						built = true;
						at(model, city.at(), sound("block.anvil.use"), 0.45f, 1.1f);
					} else if (city.population() > e.before().population()) {
						at(model, city.at(), first("entity.villager.celebrate", "entity.villager.yes"), 0.5f, 1.0f);
					}
				}
				case WorldEvent.CityChangedHands e -> at(model, e.city().at(), sound("entity.generic.explode"), 0.5f, 0.8f);
				case WorldEvent.WarChanged e -> ui(e.atWar() ? "event.raid.horn" : "block.note_block.chime", e.atWar() ? 0.7f : 0.6f, 1.0f);
				case WorldEvent.TechsLearned e -> ui("block.enchantment_table.use", 0.6f, 1.2f);
				case WorldEvent.EraChanged e -> ui("ui.toast.challenge_complete", 0.5f, 0.8f);
				case WorldEvent.TurnChanged e -> ui("block.note_block.bell", 0.25f, 0.7f);
				default -> {
				}
			}
		}
		if (step != null) {
			String id = creature(after, step);
			at(model, step.at(), first("entity." + id + ".step", id.equals("boat") || id.endsWith("_boat") ? "entity.boat.paddle_water" : "entity." + id + ".ambient", "block.grass.step"),
				0.5f, 1.0f);
		}
		if (hurt != null) {
			String id = creature(after, hurt);
			at(model, hurt.at(), sound("entity.player.attack.strong"), 0.6f, 1.0f);
			at(model, hurt.at(), first("entity." + id + ".hurt", "entity.player.hurt"), 0.6f, 1.0f);
		}
		if (fell != null) {
			String id = creature(before, fell);
			at(model, fell.at(), first("entity." + id + ".death", "entity.player.death"), 0.7f, 1.0f);
		}
	}

	/** News came in: a soft sound by what kind it is, and none for what is said every turn. */
	static void news(String kind) {
		switch (kind) {
			case "war" -> ui("block.note_block.bass", 0.5f, 0.6f);
			case "wonder", "era" -> {
				// The picture of the game brings these with a sound of their own.
			}
			case "turn", "news" -> {
			}
			default -> ui("block.note_block.hat", 0.35f, 1.4f);
		}
	}
}
