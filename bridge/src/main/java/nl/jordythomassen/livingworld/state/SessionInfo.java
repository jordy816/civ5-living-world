package nl.jordythomassen.livingworld.state;

import nl.jordythomassen.livingworld.coords.CivWorldLayout;
import nl.jordythomassen.livingworld.coords.HexGrid;

/**
 * Which Civ game we are attached to.
 *
 * <p>{@code gameId} identifies the game itself (map and players) and survives save/load;
 * {@code bootId} changes every time our Lua is loaded into the game, so anything still in flight
 * from before a reload can be told apart and dropped.
 */
public record SessionInfo(
	int protocol,
	String bootId,
	String gameId,
	int viewer,
	int viewerTeam,
	HexGrid grid,
	CivWorldLayout civLayout
) {
}
