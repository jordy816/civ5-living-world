package nl.jordythomassen.livingworld.mc.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import nl.jordythomassen.livingworld.mc.client.rts.RtsCamera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No sky in the view from above: it is drawn as a dome around the eye, which a flat projection
 * turns into a sun lying on the land and a dark disc under it. What is not land is plain colour.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true)
	private void livingworld$noSky(CallbackInfo ci) {
		if (RtsCamera.active()) {
			ci.cancel();
		}
	}
}
