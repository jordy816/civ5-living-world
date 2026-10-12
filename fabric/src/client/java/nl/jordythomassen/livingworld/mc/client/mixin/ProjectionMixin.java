package nl.jordythomassen.livingworld.mc.client.mixin;

import net.minecraft.client.renderer.Projection;
import nl.jordythomassen.livingworld.mc.client.rts.RtsCamera;
import nl.jordythomassen.livingworld.mc.client.rts.RtsProjection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The world's projection hands out the flat matrix of the view from above while that view is on. */
@Mixin(Projection.class)
public abstract class ProjectionMixin implements RtsProjection {
	@Shadow
	private boolean isMatrixDirty;

	@Unique
	private boolean livingworld$fromAbove;

	@Override
	public void livingworld$setFromAbove(boolean on) {
		if (on || this.livingworld$fromAbove) {
			// The zoom may have changed: whoever keeps a copy must take a new one.
			this.isMatrixDirty = true;
		}
		this.livingworld$fromAbove = on;
	}

	@Inject(method = "getMatrix", at = @At("RETURN"))
	private void livingworld$flat(Matrix4f dest, CallbackInfoReturnable<Matrix4f> cir) {
		if (this.livingworld$fromAbove) {
			RtsCamera.matrix(cir.getReturnValue(), true);
		}
	}
}
