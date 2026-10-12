package nl.jordythomassen.livingworld.commands;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import nl.jordythomassen.livingworld.protocol.CommandResult;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * Carries each command through its life: checked, queued, sent, applied by Civ, and finally
 * confirmed by seeing the effect in Civ's own state. An answer from Civ alone is not proof; a
 * command only counts as done once the state shows it.
 *
 * <p>Used from the link thread only, except {@link #submit}, which may be called from anywhere.
 */
public final class CommandTracker {
	/** No answer from Civ within this time: give up (the request id makes a late answer harmless). */
	public static final long ANSWER_TIMEOUT_MS = 8_000;
	/** Applied but no visible effect within this time. */
	public static final long CONFIRM_TIMEOUT_MS = 12_000;
	private static final int MAX_QUEUE = 32;
	private static final int REMEMBER = 256;

	private final LongSupplier clock;
	private final Consumer<CommandTicket> listener;
	private final Deque<CommandTicket> queue = new ArrayDeque<>();
	private final Map<String, CommandTicket> inFlight = new HashMap<>();
	private final Map<String, CommandTicket> byKey = new HashMap<>();
	private final Deque<String> keyOrder = new ArrayDeque<>();

	public CommandTracker(LongSupplier clock, Consumer<CommandTicket> listener) {
		this.clock = clock;
		this.listener = listener;
	}

	/**
	 * Takes a new command. If one with the same idempotency key was already taken and is under
	 * way or has been carried out, that one's ticket is returned and nothing new happens. One
	 * that was refused or ran out of time did nothing, so asking again is a new attempt: a turn
	 * Civ would not end because a decision was missing must be endable once the decision is made.
	 */
	public synchronized CommandTicket submit(GameCommand command, WorldState state) {
		CommandTicket existing = byKey.get(command.idempotencyKey());
		if (existing != null && existing.status() != CommandStatus.REJECTED && existing.status() != CommandStatus.EXPIRED) {
			return existing;
		}
		CommandTicket ticket = new CommandTicket(command);
		remember(command.idempotencyKey(), ticket);
		String refusal = CommandRules.refuse(command, state);
		if (refusal == null && queue.size() + inFlight.size() >= MAX_QUEUE) {
			refusal = "Too many orders are waiting already.";
		}
		if (refusal != null) {
			finish(ticket, CommandStatus.REJECTED, refusal);
			return ticket;
		}
		ticket.set(CommandStatus.VALIDATED, "");
		ticket.set(CommandStatus.QUEUED, "Waiting for Civilization V.");
		queue.add(ticket);
		listener.accept(ticket);
		return ticket;
	}

	private void remember(String key, CommandTicket ticket) {
		byKey.put(key, ticket);
		keyOrder.add(key);
		while (keyOrder.size() > REMEMBER) {
			String oldest = keyOrder.poll();
			CommandTicket old = byKey.get(oldest);
			if (old != null && old.status().finished()) {
				byKey.remove(oldest);
			} else {
				keyOrder.add(oldest);
				break;
			}
		}
	}

	/**
	 * The next command to send to Civ now, or null. Commands only go out during the viewer's own
	 * turn: that is the point where Civ's rules expect the player to act.
	 */
	public synchronized CommandTicket nextToSend(WorldState state, boolean viewerTurn) {
		if (state == null || !viewerTurn) {
			return null;
		}
		while (!queue.isEmpty()) {
			CommandTicket ticket = queue.poll();
			// State may have moved on while the command waited: check again right before sending.
			String refusal = CommandRules.refuse(ticket.command(), state);
			if (refusal != null) {
				finish(ticket, CommandStatus.REJECTED, refusal);
				continue;
			}
			if (ticket.command().op() == CommandOp.UNIT_MOVE_TO) {
				UnitState unit = state.units().get(ticket.command().target());
				ticket.baseline(state.session().grid().distance(unit.at(), ticket.command().plot()));
			} else if (ticket.command().op() == CommandOp.IMPROVE) {
				ticket.baseline(state.plot(ticket.command().plot()).hashCode());
			}
			ticket.submitted(clock.getAsLong());
			inFlight.put(ticket.command().id(), ticket);
			listener.accept(ticket);
			return ticket;
		}
		return null;
	}

	/** Civ's answer to a command we sent. Answers to commands we no longer track are ignored. */
	public synchronized void onResult(CommandResult result, int turn) {
		CommandTicket ticket = inFlight.get(result.commandId());
		if (ticket == null || ticket.status() != CommandStatus.SUBMITTED) {
			return;
		}
		if (!result.applied()) {
			inFlight.remove(result.commandId());
			finish(ticket, CommandStatus.REJECTED, result.message().isEmpty() ? "Civilization V refused (" + result.code() + ")." : result.message());
			return;
		}
		// Moves and plot works are compared with what this side measured before sending.
		boolean measuredHere = ticket.command().op() == CommandOp.UNIT_MOVE_TO || ticket.command().op() == CommandOp.IMPROVE;
		long before = measuredHere ? ticket.before() : result.number("before", Long.MIN_VALUE);
		ticket.applied(clock.getAsLong(), before, result.number("after", Long.MIN_VALUE), turn, result.message());
		listener.accept(ticket);
	}

	/** A fresh picture of Civ: confirm what can be confirmed, expire what has waited too long. */
	public synchronized void onState(WorldState state) {
		long now = clock.getAsLong();
		List<CommandTicket> done = new ArrayList<>();
		for (CommandTicket ticket : inFlight.values()) {
			if (!ticket.command().bootId().equals(state.session().bootId())) {
				ticket.set(CommandStatus.EXPIRED, "Civilization V was reloaded before this could be confirmed.");
				done.add(ticket);
			} else if (ticket.status() == CommandStatus.APPLIED && CommandRules.confirmed(ticket, state)) {
				ticket.set(CommandStatus.CONFIRMED, ticket.detail());
				done.add(ticket);
			} else if (ticket.status() == CommandStatus.APPLIED && now - ticket.appliedAt()
				> (ticket.command().op() == CommandOp.END_TURN ? CommandRules.END_TURN_CONFIRM_MS : CONFIRM_TIMEOUT_MS)) {
				ticket.set(CommandStatus.EXPIRED, "Civilization V accepted this, but no effect showed up in the game.");
				done.add(ticket);
			} else if (ticket.status() == CommandStatus.SUBMITTED && now - ticket.submittedAt() > ANSWER_TIMEOUT_MS) {
				ticket.set(CommandStatus.EXPIRED, "Civilization V did not answer.");
				done.add(ticket);
			}
		}
		for (CommandTicket ticket : done) {
			inFlight.remove(ticket.command().id());
			listener.accept(ticket);
		}
	}

	/** The link to Civ dropped: nothing in flight can be trusted to arrive. Queued commands stay. */
	public synchronized void onDisconnect() {
		for (CommandTicket ticket : new ArrayList<>(inFlight.values())) {
			if (ticket.status() == CommandStatus.SUBMITTED) {
				inFlight.remove(ticket.command().id());
				finish(ticket, CommandStatus.EXPIRED, "Lost the connection to Civilization V before it answered.");
			}
		}
	}

	public synchronized int waiting() {
		return queue.size() + inFlight.size();
	}

	private void finish(CommandTicket ticket, CommandStatus status, String detail) {
		ticket.set(status, detail);
		listener.accept(ticket);
	}
}
