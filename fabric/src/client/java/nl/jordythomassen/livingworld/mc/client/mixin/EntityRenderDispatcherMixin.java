package nl.jordythomassen.livingworld.mc.client.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import nl.jordythomassen.livingworld.mc.client.rts.RtsCamera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The names that float over the cities are for whoever walks the land. From above every city
 * has a banner on the screen instead, which stays readable at any zoom.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void livingworld$noFloatingText(Entity entity, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z, float partialTick,
		CallbackInfoReturnable<Boolean> cir) {
		if (RtsCamera.active() && entity instanceof Display.TextDisplay) {
			cir.setReturnValue(false);
		} else if (nl.jordythomassen.livingworld.mc.client.rts.RtsClient.hidden(entity)) {
			// Several people, each with their own civilization: what stands where this player's people do not see is not shown.
			cir.setReturnValue(false);
		}
	}
}
