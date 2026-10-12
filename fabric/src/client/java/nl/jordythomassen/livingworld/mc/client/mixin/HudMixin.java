package nl.jordythomassen.livingworld.mc.client.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import nl.jordythomassen.livingworld.mc.client.rts.FootHud;
import nl.jordythomassen.livingworld.mc.client.rts.RtsCamera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The view from above has its own panels; hearts, hotbar and crosshair belong to walking. And
 * walking has a corner of its own: whose land one stands on and what one's hands are good for.
 */
@Mixin(Hud.class)
public abstract class HudMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void livingworld$noWalkingHud(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		if (RtsCamera.active()) {
			FootHud.above();
			ci.cancel();
		}
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void livingworld$onFoot(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		if (!RtsCamera.active() && !((Hud) (Object) this).isHidden()) {
			FootHud.draw(graphics);
		}
	}
}
