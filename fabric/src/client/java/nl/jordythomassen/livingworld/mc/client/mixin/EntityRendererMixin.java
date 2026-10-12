package nl.jordythomassen.livingworld.mc.client.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import nl.jordythomassen.livingworld.mc.client.rts.RtsCamera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** From above the units carry a flag on the screen; their name tags would only crowd the picture. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "submitNameDisplay", at = @At("HEAD"), cancellable = true)
	private void livingworld$noNameTags(CallbackInfo ci) {
		if (RtsCamera.active()) {
			ci.cancel();
		}
	}
}
