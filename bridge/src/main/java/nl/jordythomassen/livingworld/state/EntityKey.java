package nl.jordythomassen.livingworld.state;

/**
 * Identity of a Civ unit or city: the owning player plus Civ's own id for it. Names are never
 * identities ("Warrior" and even "Rome" can occur more than once). Civ's ids carry a generation
 * counter, so a new unit never reuses the id of a dead one within a game.
 */
public record EntityKey(int owner, int id) implements Comparable<EntityKey> {
	@Override
	public int compareTo(EntityKey other) {
		int c = Integer.compare(owner, other.owner);
		return c != 0 ? c : Integer.compare(id, other.id);
	}

	@Override
	public String toString() {
		return owner + ":" + id;
	}
}
