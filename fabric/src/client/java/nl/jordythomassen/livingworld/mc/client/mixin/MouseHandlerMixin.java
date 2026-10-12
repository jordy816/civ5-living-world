package nl.jordythomassen.livingworld.mc.client.mixin;

import net.minecraft.client.MouseHandler;
import nl.jordythomassen.livingworld.mc.client.rts.RtsCamera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In the view from above the mouse is a pointer, always: the game must not take it to look around with. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void livingworld$keepThePointer(CallbackInfo ci) {
		if (RtsCamera.active()) {
			ci.cancel();
		}
	}
}
