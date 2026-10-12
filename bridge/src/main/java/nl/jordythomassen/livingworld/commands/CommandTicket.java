package nl.jordythomassen.livingworld.commands;

/** The life of one command: where it is now and why. Handed to whoever asked for it. */
public final class CommandTicket {
	private final GameCommand command;
	private volatile CommandStatus status = CommandStatus.CREATED;
	private volatile String detail = "";
	private volatile long submittedAt;
	private volatile long appliedAt;
	/** What Civ reported right after applying (for example the city's hammers before and after). */
	private volatile long before = Long.MIN_VALUE;
	private volatile long after = Long.MIN_VALUE;
	private volatile int appliedTurn = -1;

	CommandTicket(GameCommand command) {
		this.command = command;
	}

	/** A ticket for a game that answers at once, like the built-in engine: opened here, settled with {@link #settle}. */
	public static CommandTicket open(GameCommand command) {
		CommandTicket ticket = new CommandTicket(command);
		ticket.status = CommandStatus.QUEUED;
		return ticket;
	}

	/** The outcome, in one step: carried out and seen in the game's state, or refused with a reason. */
	public void settle(boolean done, String message, long valueBefore, long valueAfter, int turn) {
		before = valueBefore;
		after = valueAfter;
		appliedTurn = turn;
		detail = message;
		status = done ? CommandStatus.CONFIRMED : CommandStatus.REJECTED;
	}

	public GameCommand command() {
		return command;
	}

	public CommandStatus status() {
		return status;
	}

	public String detail() {
		return detail;
	}

	public long before() {
		return before;
	}

	public long after() {
		return after;
	}

	long submittedAt() {
		return submittedAt;
	}

	long appliedAt() {
		return appliedAt;
	}

	int appliedTurn() {
		return appliedTurn;
	}

	void set(CommandStatus newStatus, String newDetail) {
		status = newStatus;
		detail = newDetail;
	}

	/** A value measured just before sending, to compare against afterwards. */
	void baseline(long value) {
		before = value;
	}

	void submitted(long now) {
		submittedAt = now;
		status = CommandStatus.SUBMITTED;
	}

	void applied(long now, long valueBefore, long valueAfter, int turn, String message) {
		appliedAt = now;
		before = valueBefore;
		after = valueAfter;
		appliedTurn = turn;
		detail = message;
		status = CommandStatus.APPLIED;
	}
}
