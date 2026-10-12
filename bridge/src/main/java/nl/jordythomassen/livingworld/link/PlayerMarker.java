package nl.jordythomassen.livingworld.link;

import nl.jordythomassen.livingworld.coords.GridPos;

/** Where the Minecraft player is on the board, for the marker on the Civ map. */
public record PlayerMarker(GridPos pos, boolean show, boolean follow, String label) {
	public static final PlayerMarker HIDDEN = new PlayerMarker(new GridPos(0, 0), false, false, "");
}
