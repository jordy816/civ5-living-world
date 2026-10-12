package nl.jordythomassen.livingworld.mc.client.rts;

/** Added to the game's projection of the world: whether it is the flat, map-like one of the view from above. */
public interface RtsProjection {
	void livingworld$setFromAbove(boolean on);
}
