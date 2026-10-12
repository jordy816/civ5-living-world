package nl.jordythomassen.livingworld.link;

/** Where the connection to Civilization V stands, for the status display and logs. */
public enum LinkStatus {
	/** Not trying (switched off). */
	OFF("off"),
	/** Civ is not running, or its debug socket is not enabled. */
	NO_GAME("Civilization V not found"),
	/** Connected to Civ, but no game is being played (main menu or loading). */
	NO_MATCH("Civilization V is not in a game"),
	/** Loading our Lua into the game or fetching the first picture. */
	SYNCING("syncing with Civilization V"),
	LIVE("live");

	public final String text;

	LinkStatus(String text) {
		this.text = text;
	}
}
