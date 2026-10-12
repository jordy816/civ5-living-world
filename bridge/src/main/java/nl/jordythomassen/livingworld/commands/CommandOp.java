package nl.jordythomassen.livingworld.commands;

/**
 * Everything the Minecraft side is allowed to ask Civ V to do. This list is the whole write
 * surface: there is no way to send Lua, a function name or free text from Minecraft.
 */
public enum CommandOp {
	/** Order one of the viewer's units to walk to a plot (Civ's own move-to mission). */
	UNIT_MOVE_TO("move"),
	/** Add hammers to what one of the viewer's cities is building (a completed supply mission). */
	CITY_ADD_PRODUCTION("prod"),
	/** Heal one of the viewer's units by a number of hit points (a completed medic mission). */
	UNIT_HEAL("heal"),
	/** End the viewer's turn, exactly as the Next Turn button does, if Civ has nothing left to ask. */
	END_TURN("turn"),
	/** Set what a city builds. {@code plot} carries (order kind, item id) from the game's own list; {@code item} its type. */
	CITY_BUILD("build"),
	/** Choose what to research. {@code plot.y} carries the technology id; {@code item} its type. */
	RESEARCH("research"),
	/** The player walked somewhere: the land around {@code plot} becomes explored, {@code amount} hexes far. */
	REVEAL("reveal"),
	/** Do a Worker's job on {@code plot}: {@code amount} is one of the game's builds (farm, mine, road, clear forest). */
	IMPROVE("improve"),
	/** Give gold to a city-state. {@code target.id} is its player id, {@code amount} one of the game's three gift sizes. */
	GIFT("gift"),
	/** Declare war on another civilization. {@code target.id} is its player id. */
	DECLARE_WAR("war"),
	/** Open a branch of the social policy tree (plot x = 5) or adopt a policy (x = 6); plot y says which. */
	POLICY("policy"),
	/** One of the things a unit can do on the spot (found a city, fortify, sleep, pillage ...); the item names it. */
	UNIT_ACTION("unit"),
	/** A unit takes a promotion it has earned; the item names it. */
	UNIT_PROMOTE("promote"),
	/** A city buys something outright with gold; the item names it. */
	CITY_BUY("buy"),
	/** Offers peace to a civilization the player is at war with. */
	MAKE_PEACE("peace"),
	/** A step towards another civilization; the item says which: "peace", "friendship", "denounce", "protect". */
	DIPLOMACY("diplo"),
	/** What becomes of a city the player holds: the item is "annex", "puppet", "raze", "spare" or "liberate". */
	CITY_FATE("fate"),
	/** Answers a proposal another civilization made; amount 1 accepts, 0 declines. */
	ANSWER("answer"),
	/** Chooses a belief for the player's pantheon or religion; the item names it. */
	BELIEF("belief"),
	/** A step in a deal the player puts to another civilization: the item puts something on the table, clears it, or proposes. */
	TRADE("trade"),
	/**
	 * Which land a city's citizens work. The item is "toggle" (the plot: put a citizen there for good, or leave that
	 * place to the city's own choice again), "focus:food" (or production, gold, science, culture, faith, none) or "reset".
	 */
	CITY_WORK("work"),
	/**
	 * The smaller decisions of the built-in game. The item says which: "bombard" (the target city fires on the plot),
	 * "sell:BUILDING_X" (the target city sells it), "great:GREAT_X" (a Great Person given freely is named).
	 */
	CHOICE("choice");

	/** The name our Lua knows this operation by. */
	public final String wireName;

	CommandOp(String wireName) {
		this.wireName = wireName;
	}
}
