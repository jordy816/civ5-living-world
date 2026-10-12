package nl.jordythomassen.livingworld.mc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;

/** Creates an entity from its data, the same way the game's own /summon command does. */
final class Summon {
	/** Every entity of ours carries this tag, so leftovers can be recognised and removed. */
	static final String TAG = "livingworld";

	private Summon() {
	}

	/** Builds the entity (not yet in the world); null when the data is not accepted. */
	static Entity create(ServerLevel level, String snbt) {
		try {
			CompoundTag tag = TagParser.parseCompoundFully(snbt);
			return EntityType.loadEntityRecursive(tag, level, new EntitySpawnRequest(EntitySpawnReason.COMMAND, false), EntityProcessor.NOP);
		} catch (Exception e) {
			LivingWorldMod.LOG.warn("Living World: could not create an entity from {}", snbt, e);
			return null;
		}
	}

	/** Text safe to put between double quotes in entity data. */
	static String quote(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
	}

	static String hex(int rgb) {
		return String.format("\"#%06X\"", rgb & 0xFFFFFF);
	}

	/** A colour light enough to read as text on the game's dark name plates. */
	static int readable(int rgb) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		int brightness = (r * 3 + g * 6 + b) / 10;
		if (brightness >= 110) {
			return rgb;
		}
		// Mix with white until it is.
		double mix = Math.min(0.75, (110 - brightness) / 160.0 + 0.25);
		r = (int) (r + (255 - r) * mix);
		g = (int) (g + (255 - g) * mix);
		b = (int) (b + (255 - b) * mix);
		return (r << 16) | (g << 8) | b;
	}
}
