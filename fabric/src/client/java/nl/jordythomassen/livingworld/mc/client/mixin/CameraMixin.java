package nl.jordythomassen.livingworld.mc.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import nl.jordythomassen.livingworld.mc.client.rts.RtsCamera;
import nl.jordythomassen.livingworld.mc.client.rts.RtsProjection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In the view from above the world is drawn with a flat projection instead of perspective. The
 * game works its projection out in two places: once to decide which parts of the world are in
 * view at all, and once for the drawing itself. Both get the flat one.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	@Final
	private Projection projection;

	@Inject(method = "createProjectionMatrixForCulling", at = @At("RETURN"), cancellable = true)
	private void livingworld$flatForCulling(CallbackInfoReturnable<Matrix4f> cir) {
		if (RtsCamera.flat()) {
			cir.setReturnValue(RtsCamera.matrix(cir.getReturnValue(), false));
		}
	}

	@Inject(method = "setupPerspective", at = @At("HEAD"))
	private void livingworld$flatForDrawing(float zNear, float zFar, float fov, float width, float height, CallbackInfo ci) {
		((RtsProjection) this.projection).livingworld$setFromAbove(RtsCamera.flat());
	}

	/**
	 * The game leaves out parts of the world it reckons are hidden behind others, and reckons that
	 * along lines to the eye. With a flat projection the lines of sight are parallel instead, so
	 * that reckoning leaves holes in the land; from above next to nothing hides anything anyway.
	 */
	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void livingworld$nothingHidden(CameraRenderState state, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (RtsCamera.active()) {
			state.smartCull = false;
		}
	}
}
