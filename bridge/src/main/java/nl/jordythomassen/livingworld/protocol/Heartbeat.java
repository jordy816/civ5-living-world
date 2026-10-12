package nl.jordythomassen.livingworld.protocol;

/**
 * The small status record Civ sends back on every exchange. The revision counters are bumped by
 * our Lua whenever one of Civ's own hooks says something of that kind may have changed; they are
 * hints for when to fetch a fresh list, never the data itself.
 */
public record Heartbeat(
	int protocol,
	String bootId,
	String gameId,
	int turn,
	int viewer,
	int viewerTeam,
	int width,
	int height,
	boolean wrapX,
	long unitsRev,
	long citiesRev,
	long plotsRev,
	long civsRev,
	boolean viewerTurn
) {
}
