package nl.jordythomassen.livingworld.mc.mixin;

import net.minecraft.world.entity.Entity;
import nl.jordythomassen.livingworld.mc.LivingWorldMod;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The game tells a player of a creature only within a distance that goes with its kind: some
 * ten chunks for the figures this mod uses. Whoever rules from above sees further than that,
 * and a unit's flag over empty land is a lie. The mod's own figures are told of as far as the
 * player's view reaches (the game still caps it at that).
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {
	@Shadow
	@Final
	private Entity entity;

	@Inject(method = "getEffectiveRange", at = @At("RETURN"), cancellable = true)
	private void livingworld$figuresFromAfar(CallbackInfoReturnable<Integer> cir) {
		if (cir.getReturnValue() < 1024 && entity.entityTags().contains(LivingWorldMod.FIGURE_TAG)) {
			cir.setReturnValue(1024);
		}
	}
}
