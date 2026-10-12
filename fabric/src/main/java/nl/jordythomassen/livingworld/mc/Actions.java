package nl.jordythomassen.livingworld.mc;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The buttons of the interaction menus. A button carries a one-time token; pressing it sends
 * "/civ act &lt;token&gt;" and the action stored for that token and that player runs. Nothing a
 * client sends is interpreted as an instruction: an unknown or expired token does nothing.
 */
final class Actions {
	private record Pending(UUID player, long expiresAt, Consumer<ServerPlayer> action) {
	}

	/** How long a button stays good. A page beside the map may well stay open for a few turns. */
	private static final long LIFETIME_MS = 900_000;
	private final Map<String, Pending> pending = new HashMap<>();
	private long counter;

	String register(ServerPlayer player, Consumer<ServerPlayer> action) {
		long now = System.currentTimeMillis();
		for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
			if (it.next().expiresAt < now) {
				it.remove();
			}
		}
		String token = Long.toString(++counter, 36) + Long.toString(UUID.randomUUID().getLeastSignificantBits() & 0xFFFFFFFFFFL, 36);
		pending.put(token, new Pending(player.getUUID(), now + LIFETIME_MS, action));
		return token;
	}

	boolean run(ServerPlayer player, String token) {
		Pending p = pending.remove(token);
		if (p == null || !p.player.equals(player.getUUID()) || p.expiresAt < System.currentTimeMillis()) {
			return false;
		}
		p.action.accept(player);
		return true;
	}
}
