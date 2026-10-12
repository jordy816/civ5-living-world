package nl.jordythomassen.livingworld.tuner;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nl.jordythomassen.livingworld.link.CivTransport;
import nl.jordythomassen.livingworld.link.NoMatchException;

/**
 * Talks to Civilization V's own debug socket, the one Firaxis' FireTuner tool uses. The game
 * opens it on TCP 4318 when {@code EnableTuner = 1} is set in its config.ini; nothing has to be
 * installed into the game.
 *
 * <pre>
 * frame   := uint32 LE payload length | int32 LE tag | payload, NUL terminated
 * tag 4   "APP:"                 -> the game identifies itself
 * tag 4   "LSQ:"                 -> the Lua states, as NUL separated (id, name) pairs
 * tag 3   "CMD:&lt;state id&gt;:&lt;lua&gt;"  -> run Lua in that state
 * replies tag -1 "O\0&lt;text&gt;"     one per print() line, prefixed "&lt;Context&gt;: "
 *         tag 3  ""              the command finished
 *         tag 3  "ERR:..."       the command raised an error
 * </pre>
 *
 * <p>Each screen of the game is its own Lua state with its own id; the ids change on every load,
 * so the state is looked up by name. Output arrives on its own schedule (the "finished" frame can
 * overtake it), which is why every call of ours ends with a marker line and we read until we see
 * it. The game accepts one client at a time and disables the socket in multiplayer.
 */
public final class TunerTransport implements CivTransport {
	public static final int DEFAULT_PORT = 4318;
	/** Commands longer than this are silently dropped by the game (measured between 1 and 2 KB). */
	public static final int MAX_COMMAND = 1400;
	/** Default time to wait for the game to take a new connection. */
	public static final long DEFAULT_PATIENCE_MS = 90_000;

	private static final int TAG_COMMAND = 3;
	private static final int TAG_HANDSHAKE = 4;
	private static final int TAG_OUTPUT = -1;
	private static final int MAX_FRAME = 4 * 1024 * 1024;

	public record Frame(int tag, String payload) {
	}

	private final String host;
	private final int port;
	private final String stateName;
	private final Socket socket;
	private final DataInputStream in;
	private final OutputStream out;
	private Map<Integer, String> states = new LinkedHashMap<>();
	private int stateId = -1;
	private String app = "";
	private long emptySince;

	private TunerTransport(String host, int port, String stateName, Socket socket) throws IOException {
		this.host = host;
		this.port = port;
		this.stateName = stateName;
		this.socket = socket;
		this.in = new DataInputStream(socket.getInputStream());
		this.out = socket.getOutputStream();
	}

	/**
	 * Connects and shakes hands. Succeeds as soon as the game is there, also in the main menu;
	 * calls then fail with {@link NoMatchException} until a game is being played.
	 */
	public static TunerTransport connect(String host, int port, String stateName, int connectTimeoutMs) throws IOException {
		return connect(host, port, stateName, connectTimeoutMs, DEFAULT_PATIENCE_MS);
	}

	/**
	 * @param patienceMs how long to wait, connection held open, for the game to answer the
	 *     handshake. The game serves one client at a time. Measured on the real game: a first
	 *     client is answered in about 0.1 s; a second one that connects while the first is still
	 *     there is not answered, not even after the first has left. Quick retries make it worse,
	 *     so wait once, generously, and leave the reconnect pause to the caller.
	 */
	public static TunerTransport connect(String host, int port, String stateName, int connectTimeoutMs, long patienceMs) throws IOException {
		Socket socket = new Socket();
		try {
			socket.setTcpNoDelay(true);
			if (!Boolean.getBoolean("livingworld.tuner.politeClose")) {
				// Set from the start, so the connection is reset however it ends: closed here, or by the
				// system when this process is killed or crashes. See close() for why.
				socket.setSoLinger(true, 0);
			}
			socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
			TunerTransport transport = new TunerTransport(host, port, stateName, socket);
			transport.handshake(patienceMs);
			return transport;
		} catch (IOException e) {
			try {
				socket.close();
			} catch (IOException ignored) {
				// already failing
			}
			throw new IOException("Civilization V's debug socket is not reachable on " + host + ":" + port
				+ " (is the game running with EnableTuner = 1?): " + e.getMessage(), e);
		}
	}

	private void send(int tag, String payload) throws IOException {
		byte[] body = payload.getBytes(StandardCharsets.UTF_8);
		ByteBuffer frame = ByteBuffer.allocate(8 + body.length + 1).order(ByteOrder.LITTLE_ENDIAN);
		frame.putInt(body.length + 1).putInt(tag).put(body).put((byte) 0);
		out.write(frame.array());
		out.flush();
	}

	/** The next frame, or null when none arrives within the timeout. */
	private Frame receive(int timeoutMs) throws IOException {
		socket.setSoTimeout(Math.max(1, timeoutMs));
		byte[] header = new byte[8];
		try {
			int first = in.read();
			if (first < 0) {
				throw new IOException("Civilization V closed the debug socket");
			}
			header[0] = (byte) first;
		} catch (SocketTimeoutException e) {
			return null;
		}
		// The rest of a frame that has started is given a generous fixed time.
		socket.setSoTimeout(5_000);
		in.readFully(header, 1, 7);
		ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
		int length = h.getInt();
		int tag = h.getInt();
		if (length < 0 || length > MAX_FRAME) {
			throw new IOException("the debug socket sent a frame of " + length + " bytes; out of step");
		}
		byte[] body = new byte[length];
		in.readFully(body);
		int end = length;
		while (end > 0 && body[end - 1] == 0) {
			end--;
		}
		return new Frame(tag, new String(body, 0, end, StandardCharsets.UTF_8));
	}

	private void handshake(long patienceMs) throws IOException {
		while (receive(150) != null) {
			// anything the game sent on connect
		}
		send(TAG_HANDSHAKE, "APP:");
		long deadline = System.currentTimeMillis() + patienceMs;
		Frame reply = null;
		while (reply == null && System.currentTimeMillis() < deadline) {
			reply = receive(1_000);
			if (reply != null && reply.tag() != TAG_HANDSHAKE) {
				reply = null;
			}
		}
		if (reply == null) {
			throw new IOException("the game has not taken the connection within " + patienceMs / 1000
				+ " s. It serves one tool at a time: close any other tool using its debug socket; if none is, restart Civilization V");
		}
		app = reply.payload().replace('\0', ' ').trim();
		refreshStates();
	}

	/** Asks the game for its Lua states and looks up ours by name (the newest one wins). */
	public Map<Integer, String> refreshStates() throws IOException {
		send(TAG_HANDSHAKE, "LSQ:");
		StringBuilder payload = new StringBuilder();
		boolean got = false;
		long deadline = System.currentTimeMillis() + 5_000;
		while (System.currentTimeMillis() < deadline) {
			// The list can span several frames, and stray output of an earlier command can precede it.
			Frame frame = receive(got ? 150 : 2_000);
			if (frame == null) {
				if (got) {
					break;
				}
				continue;
			}
			if (frame.tag() == TAG_HANDSHAKE) {
				if (payload.length() > 0 && payload.charAt(payload.length() - 1) != '\0') {
					payload.append('\0');
				}
				payload.append(frame.payload());
				got = true;
			}
		}
		Map<Integer, String> found = new LinkedHashMap<>();
		String[] parts = payload.toString().split("\0", -1);
		for (int i = 0; i + 1 < parts.length; i += 2) {
			try {
				found.put(Integer.parseInt(parts[i].trim()), parts[i + 1]);
			} catch (NumberFormatException e) {
				// not an (id, name) pair
			}
		}
		states = found;
		stateId = -1;
		for (Map.Entry<Integer, String> entry : found.entrySet()) {
			if (entry.getValue().equals(stateName) && entry.getKey() > stateId) {
				stateId = entry.getKey();
			}
		}
		return found;
	}

	public Map<Integer, String> states() {
		return states;
	}

	public String app() {
		return app;
	}

	private void requireState() throws IOException {
		if (stateId >= 0) {
			return;
		}
		refreshStates();
		if (stateId >= 0) {
			emptySince = 0;
			return;
		}
		if (states.isEmpty()) {
			// A connection made before the game's front end exists never gets a list: start over.
			if (emptySince == 0) {
				emptySince = System.currentTimeMillis();
			} else if (System.currentTimeMillis() - emptySince > 20_000) {
				throw new IOException("Civilization V lists no Lua states; reconnecting");
			}
		} else {
			emptySince = 0;
		}
		throw new NoMatchException("Civilization V is not in a game");
	}

	@Override
	public List<String> call(String lua, String endMarker, long timeoutMs) throws IOException {
		return callIn(null, lua, endMarker, timeoutMs);
	}

	/**
	 * Runs Lua in a named state (null: our own). Returns the printed lines; with an end marker,
	 * up to and including the line that contains it.
	 */
	public List<String> callIn(String otherState, String lua, String endMarker, long timeoutMs) throws IOException {
		if (lua.getBytes(StandardCharsets.UTF_8).length > MAX_COMMAND) {
			throw new IllegalArgumentException("command of " + lua.length() + " characters is too long for the debug socket");
		}
		for (int attempt = 0; ; attempt++) {
			int target;
			if (otherState == null) {
				requireState();
				target = stateId;
			} else {
				target = -1;
				for (Map.Entry<Integer, String> entry : states.entrySet()) {
					if (entry.getValue().equals(otherState) && entry.getKey() > target) {
						target = entry.getKey();
					}
				}
				if (target < 0) {
					refreshStates();
					for (Map.Entry<Integer, String> entry : states.entrySet()) {
						if (entry.getValue().equals(otherState) && entry.getKey() > target) {
							target = entry.getKey();
						}
					}
				}
				if (target < 0) {
					throw new NoMatchException("Civilization V has no Lua state named " + otherState);
				}
			}
			send(TAG_COMMAND, "CMD:" + target + ":" + lua);
			List<String> lines = new ArrayList<>();
			long deadline = System.currentTimeMillis() + timeoutMs;
			boolean acked = false;
			String error = null;
			while (true) {
				long left = deadline - System.currentTimeMillis();
				if (left <= 0) {
					if (acked && endMarker == null) {
						return lines;
					}
					throw new IOException("Civilization V did not answer within " + timeoutMs + " ms");
				}
				// Once the command is acknowledged, a call without a marker only waits briefly for
				// output that is still on its way.
				Frame frame = receive((int) Math.min(left, acked && endMarker == null ? 30 : 1_000));
				if (frame == null) {
					if (acked && endMarker == null) {
						return lines;
					}
					continue;
				}
				if (frame.tag() == TAG_OUTPUT) {
					String text = frame.payload();
					int nul = text.indexOf('\0');
					if (nul >= 0) {
						text = text.substring(nul + 1);
					}
					lines.add(text);
					if (endMarker != null && text.contains(endMarker)) {
						return lines;
					}
				} else if (frame.tag() == TAG_COMMAND) {
					if (frame.payload().startsWith("ERR:")) {
						error = frame.payload();
						break;
					}
					acked = true;
				}
			}
			if (error.contains("Invalid Lua State") && attempt == 0) {
				// The state was replaced (a load, or leaving to the menu): look it up again, once.
				refreshStates();
				continue;
			}
			throw new IOException("Lua error in Civilization V: " + error);
		}
	}

	@Override
	public String describe() {
		return "debug socket " + host + ":" + port + ", state " + stateName + (app.isEmpty() ? "" : ", " + app);
	}

	/**
	 * Leaves abruptly: the connection is reset, not closed politely. Measured on the real game
	 * (2026-10-10): five clients in a row that each left with a reset were all answered within half
	 * a second; after one that closed politely (FIN) the next client got no answer in 90 seconds,
	 * and in other runs not in five minutes: the game then has to be restarted. Every "the socket
	 * is stuck" seen before came from a polite close. (-Dlivingworld.tuner.politeClose=true brings
	 * the polite close back, for comparing the two.)
	 */
	@Override
	public void close() {
		try {
			if (!Boolean.getBoolean("livingworld.tuner.politeClose")) {
				socket.setSoLinger(true, 0);
			}
		} catch (IOException ignored) {
			// closing anyway
		}
		try {
			socket.close();
		} catch (IOException ignored) {
			// closing anyway
		}
	}
}
