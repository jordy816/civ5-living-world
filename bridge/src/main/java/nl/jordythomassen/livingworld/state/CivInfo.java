package nl.jordythomassen.livingworld.state;

/** One player slot in the Civ game: a major civilization, a city-state or the barbarians. */
public record CivInfo(
	int id,
	int team,
	String type,
	String name,
	String adjective,
	String leader,
	boolean alive,
	boolean minor,
	boolean barbarian,
	boolean human,
	int color,
	int accentColor,
	boolean atWarWithViewer,
	boolean metByViewer,
	/** The civ's current era as Civ names it (ERA_ANCIENT ...), or empty when unknown. */
	String era,
	/** The civ's building style as Civ names it (ARTSTYLE_EUROPEAN ...), or empty. */
	String artStyle
) {
	/** 0 ancient, 1 classical, 2 medieval, 3 renaissance, 4 industrial, 5 modern and later. */
	public int eraTier() {
		return eraTier(era);
	}

	public static int eraTier(String era) {
		return switch (era == null ? "" : era) {
			case "ERA_CLASSICAL" -> 1;
			case "ERA_MEDIEVAL" -> 2;
			case "ERA_RENAISSANCE" -> 3;
			case "ERA_INDUSTRIAL" -> 4;
			case "ERA_MODERN", "ERA_POSTMODERN", "ERA_FUTURE" -> 5;
			default -> 0;
		};
	}
}
