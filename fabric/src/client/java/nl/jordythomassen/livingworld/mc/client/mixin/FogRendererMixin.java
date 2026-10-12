package nl.jordythomassen.livingworld.mc.client.mixin;

import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import nl.jordythomassen.livingworld.mc.client.rts.RtsCamera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A map has no haze: seen from above, distance fog would grey out the far side of the screen. */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	@Inject(method = "setupFog", at = @At("RETURN"))
	private void livingworld$noHaze(CallbackInfoReturnable<FogData> cir) {
		FogData fog = cir.getReturnValue();
		if (RtsCamera.active() && fog != null) {
			fog.environmentalStart = 1.0e6f;
			fog.environmentalEnd = 1.0e6f + 1f;
			fog.renderDistanceStart = 1.0e6f;
			fog.renderDistanceEnd = 1.0e6f + 1f;
		}
	}
}
