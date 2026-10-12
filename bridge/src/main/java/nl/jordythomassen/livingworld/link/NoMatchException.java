package nl.jordythomassen.livingworld.link;

import java.io.IOException;

/** Civilization V is running and reachable, but no game is being played (main menu, loading). */
public final class NoMatchException extends IOException {
	public NoMatchException(String message) {
		super(message);
	}
}
