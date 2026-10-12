package nl.jordythomassen.livingworld.link;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * The raw channel into a running Civilization V: run a piece of Lua inside the game and get back
 * whatever it printed. Everything above this interface is the same for the real game and for the
 * mock used in tests.
 */
public interface CivTransport extends Closeable {
	/**
	 * Runs {@code lua} in the game and returns the lines printed until a line containing
	 * {@code endMarker} arrives (that line included).
	 *
	 * @throws IOException when the game is not reachable or does not answer in time
	 */
	List<String> call(String lua, String endMarker, long timeoutMs) throws IOException;

	/** A short description for logs, for example "tuner 127.0.0.1:4318". */
	String describe();

	@Override
	void close();
}
