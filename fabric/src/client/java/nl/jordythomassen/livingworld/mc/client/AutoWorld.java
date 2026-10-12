package nl.jordythomassen.livingworld.mc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import nl.jordythomassen.livingworld.mc.LivingWorldMod;

/**
 * Opens the Living World as soon as Minecraft reaches its title screen, creating it the first
 * time: an empty world that the mod paints the Civ map into. Once per start of the game, so
 * leaving the world afterwards brings you to the normal menu.
 *
 * <p>The approach follows SkyCraft's mirror world (MIT, github.com/chasmlol/SkyCraft).
 */
final class AutoWorld {
	private static final ResourceKey<WorldPreset> PRESET =
		ResourceKey.create(Registries.WORLD_PRESET, Identifier.fromNamespaceAndPath(LivingWorldMod.MOD_ID, "board"));
	private static boolean attempted;

	private AutoWorld() {
	}

	static void openWhenReady(Minecraft minecraft) {
		if (attempted || minecraft.level != null || minecraft.gui.overlay() != null) {
			return;
		}
		if (!(minecraft.gui.screen() instanceof TitleScreen title)) {
			return;
		}
		attempted = true;
		if (minecraft.getLevelSource().levelExists(LivingWorldMod.WORLD_NAME)) {
			LivingWorldMod.LOG.info("Living World: opening the world");
			minecraft.createWorldOpenFlows().openWorld(LivingWorldMod.WORLD_NAME, () -> minecraft.gui.setScreen(title));
			return;
		}
		LivingWorldMod.LOG.info("Living World: creating the world");
		LevelSettings settings = new LevelSettings(LivingWorldMod.WORLD_NAME, GameType.SURVIVAL,
			new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
		minecraft.createWorldOpenFlows().createFreshLevel(LivingWorldMod.WORLD_NAME, settings, new WorldOptions(0L, false, false),
			registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(PRESET).value().createWorldDimensions(), title);
	}
}
