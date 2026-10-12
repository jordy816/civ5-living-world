package nl.jordythomassen.livingworld.commands;

public enum CommandStatus {
	/** Made, not yet checked. */
	CREATED,
	/** Passed the checks against the last known Civ state. */
	VALIDATED,
	/** Waiting for a moment Civ can take it (the viewer's own turn). */
	QUEUED,
	/** Sent to Civ; no answer yet. */
	SUBMITTED,
	/** Civ says it carried the command out. */
	APPLIED,
	/** The effect has been seen in Civ's own state afterwards. */
	CONFIRMED,
	/** Refused, by our checks or by Civ. Nothing changed. */
	REJECTED,
	/** Gave up waiting: no answer, no visible effect, or the game was reloaded in between. */
	EXPIRED;

	public boolean finished() {
		return this == CONFIRMED || this == REJECTED || this == EXPIRED;
	}
}
