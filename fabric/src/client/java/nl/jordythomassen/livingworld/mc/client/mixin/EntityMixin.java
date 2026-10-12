package nl.jordythomassen.livingworld.mc.client.mixin;

import net.minecraft.world.entity.Entity;
import nl.jordythomassen.livingworld.mc.client.rts.RtsCamera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The game leaves out creatures that are far from the eye, by a distance that goes with their
 * size. In the view from above "far from the eye" means nothing: everything on the screen is
 * drawn the same size, wherever the eye hangs. So from above nothing is left out for distance.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
	private void livingworld$nothingTooFar(double distance, CallbackInfoReturnable<Boolean> cir) {
		if (RtsCamera.active()) {
			cir.setReturnValue(true);
		}
	}
}
