package nl.jordythomassen.livingworld.commands;

import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.state.EntityKey;

/**
 * One request from a Minecraft player to change something in Civ V.
 *
 * @param id unique per request; Civ remembers it, so sending the same request twice does it once
 * @param idempotencyKey names the thing being paid for (for example a mission turn-in); a second
 *     command with the same key is answered with the first one's outcome instead of running again
 * @param bootId the Civ session the request was made in; it is never applied to another one
 */
public record GameCommand(
	String id,
	String idempotencyKey,
	String bootId,
	String gameId,
	String actor,
	int turn,
	CommandOp op,
	EntityKey target,
	PlotCoord plot,
	int amount,
	/** For choices from one of the game's lists: the type key of what was chosen, to recognise it in the state afterwards. */
	String item
) {
	public GameCommand(String id, String idempotencyKey, String bootId, String gameId, String actor, int turn, CommandOp op,
		EntityKey target, PlotCoord plot, int amount) {
		this(id, idempotencyKey, bootId, gameId, actor, turn, op, target, plot, amount, "");
	}
}
