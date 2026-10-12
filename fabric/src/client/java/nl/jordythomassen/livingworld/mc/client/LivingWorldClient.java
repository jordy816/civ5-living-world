package nl.jordythomassen.livingworld.mc.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import nl.jordythomassen.livingworld.mc.LivingWorldMod;
import nl.jordythomassen.livingworld.mc.ModConfig;

public final class LivingWorldClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModConfig config = LivingWorldMod.config();
		nl.jordythomassen.livingworld.mc.client.rts.RtsClient.init();
		if (config.autoOpen) {
			ClientTickEvents.END_CLIENT_TICK.register(AutoWorld::openWhenReady);
		}
	}
}
