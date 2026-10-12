package nl.jordythomassen.livingworld.state;

import java.util.List;

/**
 * How another player stands to the viewer. For a city-state: influence, standing (0 neutral,
 * 1 friends, 2 allies), its trait, who its ally is, the quests it shows, and the three sizes of
 * gold gift the game offers. For a civilization: the attitude the game's own screens show
 * ({@code word}: FRIENDLY, GUARDED, HOSTILE ...) and flags.
 */
public record Relation(int id, boolean minor, int influence, int standing, String word, int ally, List<String> quests,
	int giftSmall, int giftMedium, int giftLarge) {
	public static final int F_FRIENDSHIP = 1;
	public static final int F_DENOUNCED = 2;
	public static final int F_CAN_DECLARE_WAR = 4;

	public boolean canDeclareWar() {
		return !minor && (standing & F_CAN_DECLARE_WAR) != 0;
	}
}
