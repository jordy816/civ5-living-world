package nl.jordythomassen.livingworld.mc.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import nl.jordythomassen.livingworld.mc.LivingWorldMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The board's own dimension type makes Minecraft treat the world as "experimental" and ask for a
 * backup every time it is opened, which would stop the automatic start at a prompt. The world
 * holds nothing that is not repainted from Civilization V, so skip the prompt, for this world
 * only. (Same remedy as SkyCraft's mirror world, MIT, github.com/chasmlol/SkyCraft.)
 */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {
	@WrapOperation(
		method = "openWorldCheckWorldStemCompatibility",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/worldselection/WorldOpenFlows;askForBackup(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;ZLjava/lang/Runnable;Ljava/lang/Runnable;)V"
		)
	)
	private void livingworld$skipBackupPrompt(
		WorldOpenFlows self, LevelStorageSource.LevelStorageAccess access, boolean oldCustomized, Runnable proceed, Runnable cancel, Operation<Void> original
	) {
		if (LivingWorldMod.WORLD_NAME.equals(access.getLevelId())) {
			proceed.run();
		} else {
			original.call(self, access, oldCustomized, proceed, cancel);
		}
	}
}
