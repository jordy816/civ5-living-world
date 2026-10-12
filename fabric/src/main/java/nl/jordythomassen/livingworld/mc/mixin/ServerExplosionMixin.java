package nl.jordythomassen.livingworld.mc.mixin;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ServerExplosion;
import nl.jordythomassen.livingworld.mc.LivingWorldMod;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * A blast on the board takes only the blocks that whoever set it off may dig: their own land.
 * Breaking a block by hand is held to that rule already; without this, a box of TNT was the way
 * around it.
 */
@Mixin(ServerExplosion.class)
abstract class ServerExplosionMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@Shadow
	public abstract LivingEntity getIndirectSourceEntity();

	@ModifyVariable(method = "interactWithBlocks", at = @At("HEAD"), argsOnly = true)
	private List<BlockPos> livingworld$breakOwnLandOnly(List<BlockPos> blocks) {
		return LivingWorldMod.blastable(level, getIndirectSourceEntity(), blocks);
	}

	@ModifyVariable(method = "createFire", at = @At("HEAD"), argsOnly = true)
	private List<BlockPos> livingworld$burnOwnLandOnly(List<BlockPos> blocks) {
		return LivingWorldMod.blastable(level, getIndirectSourceEntity(), blocks);
	}
}
