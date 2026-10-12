package nl.jordythomassen.livingworld.state;

/** The viewer's own empire at a glance, as Civ's top bar shows it. */
public record Empire(
	int gold,
	int goldPerTurn,
	int science,
	int happiness,
	int culture,
	int culturePerTurn,
	int policyCost,
	int goldenAgeTurns,
	String era,
	int year,
	String researchType,
	String researchName,
	int researchTurns,
	/** Policies adopted plus branches of the policy tree opened. */
	int policies,
	/** What stops the Next Turn button right now ("POLICY", "PRODUCTION", ...), or "" when nothing does. */
	String blocking
) {
	/** Whether Civ is waiting for the player to adopt a social policy. */
	public boolean policyDue() {
		return blocking.equals("POLICY") || blocking.equals("FREE_POLICY");
	}

	/** What Civ is waiting for, in words; "" when it waits for nothing. */
	public String waitingFor() {
		return switch (blocking) {
			// News on Civ's screen is put away by asking for the end of the turn; it is not a decision.
			case "", "NEWS" -> "";
			case "POLICY", "FREE_POLICY" -> "a social policy to adopt";
			case "RESEARCH", "FREE_TECH" -> "a choice of research";
			case "PRODUCTION" -> "a city that needs something to build";
			case "RELIGION" -> "a choice of what your people believe";
			case "VOTE" -> "your vote for a world leader";
			case "UNITS", "UNIT_NEEDS_ORDERS" -> "a unit waiting for orders";
			case "STACKED_UNITS" -> "two units sharing a hex";
			case "UNIT_PROMOTION" -> "a unit that can be promoted";
			default -> blocking.toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + " (in its own window)";
		};
	}

	/** What the player has to do before the turn can end, as an instruction; empty when nothing. */
	public String toDo() {
		return switch (blocking) {
			case "POLICY", "FREE_POLICY" -> "adopt a social policy";
			case "RESEARCH", "FREE_TECH" -> "choose what to research";
			case "PRODUCTION" -> "give a city something to build";
			case "RELIGION" -> "choose what your people believe";
			case "VOTE" -> "cast your vote for a world leader";
			default -> "";
		};
	}

	/** The game year the way Civ writes it: "4000 BC", "1250 AD". */
	public String yearText() {
		return year < 0 ? (-year) + " BC" : year + " AD";
	}
}
