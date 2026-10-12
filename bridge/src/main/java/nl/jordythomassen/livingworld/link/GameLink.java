package nl.jordythomassen.livingworld.link;

import java.util.List;
import java.util.function.Consumer;
import nl.jordythomassen.livingworld.commands.CommandTicket;
import nl.jordythomassen.livingworld.commands.GameCommand;
import nl.jordythomassen.livingworld.protocol.Records;
import nl.jordythomassen.livingworld.state.WorldEvent;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * Where the game lives, as the Minecraft side sees it: a picture of the world for one player,
 * what changed, and a way to ask for things. Behind it is either the engine built into the mod
 * or a running Civilization V; the Minecraft side does not care which.
 *
 * <p>Runs on a thread of its own. Everything else is called from the Minecraft server thread.
 */
public interface GameLink extends Runnable {
	/** The latest picture, or null while there is none. Immutable. */
	WorldState state();

	LinkStatus status();

	/** A few words on why the status is what it is, or empty. */
	String statusDetail();

	/** Whether the player may act now (false while the other civilizations move). */
	boolean viewerTurn();

	long roundTripMicros();

	long hintsSeen();

	int commandsWaiting();

	/** Where the player is, for a game that shows them on its own map. */
	void setMarker(PlayerMarker marker);

	/** What changed since the last call, in order. */
	List<WorldEvent> drainEvents();

	/** Commands whose fate changed since the last call. */
	List<CommandTicket> drainTicketUpdates();

	CommandTicket submit(GameCommand command);

	/** Asks what the game offers right now; the answer arrives on the link's thread. */
	void ask(String kind, int a, int c, Consumer<List<Records.Option>> answer);

	void stop();

	// ---- several people, each ruling a civilization of their own (the built-in game) ----

	/** The civilizations ruled by people, by their numbers in the picture; the one {@link #state()} is drawn for comes first. */
	default List<Integer> seats() {
		WorldState state = state();
		return state == null ? List.of() : List.of(state.session().viewer());
	}

	/** The picture as one of those civilizations sees it. */
	default WorldState state(int civ) {
		return state();
	}

	/** Whether whoever rules that civilization may act now. */
	default boolean mayAct(int civ) {
		return viewerTurn();
	}

	/**
	 * Why they may not, when they may not: empty while the computer's civilizations move, "ended"
	 * when they ended their turn and others are still at theirs, else the name of the civilization
	 * they are at war with and that moves before them.
	 */
	default String waitsFor(int civ) {
		return "";
	}

	/** What changed for one of those civilizations alone since the last call: what it is told, whom it is at war with. Empty when there is one. */
	default List<WorldEvent> drainEvents(int civ) {
		return List.of();
	}

	default CommandTicket submit(int civ, GameCommand command) {
		return submit(command);
	}

	default void ask(int civ, String kind, int a, int c, Consumer<List<Records.Option>> answer) {
		ask(kind, a, c, answer);
	}

	/**
	 * A person takes over a civilization the computer played (true), or leaves theirs to the
	 * computer again (false). The answer is empty when that is done, else why not.
	 */
	default void rule(int civ, boolean person, Consumer<String> answer) {
		answer.accept("This game is played by one civilization.");
	}

	/** Whether whoever rules a civilization is there. For somebody away the computer plays, so that the others need not wait. */
	default void present(int civ, boolean here) {
	}
}
