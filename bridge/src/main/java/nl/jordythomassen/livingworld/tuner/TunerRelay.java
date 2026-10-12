package nl.jordythomassen.livingworld.tuner;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Holds the game's single debug-socket connection open and lets local tools come and go.
 *
 * <p>The game takes one client at a time and does not like clients reconnecting in quick
 * succession. During development several short-lived tools want to talk to it; they connect to
 * this relay (on loopback) instead, one after another. Keeping the game's one slot taken also
 * means nothing else can connect to the socket in the meantime.
 */
public final class TunerRelay {
	private final String host;
	private final int port;
	private final int listenPort;
	private volatile Socket client;

	public TunerRelay(String host, int port, int listenPort) {
		this.host = host;
		this.port = port;
		this.listenPort = listenPort;
	}

	private static byte[] readFrame(DataInputStream in) throws IOException {
		byte[] header = new byte[8];
		in.readFully(header);
		int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
		if (length < 0 || length > 4 * 1024 * 1024) {
			throw new IOException("frame of " + length + " bytes");
		}
		byte[] frame = new byte[8 + length];
		System.arraycopy(header, 0, frame, 0, 8);
		in.readFully(frame, 8, length);
		return frame;
	}

	public void run() throws IOException, InterruptedException {
		try (ServerSocket server = new ServerSocket()) {
			server.setReuseAddress(true);
			server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), listenPort));
			System.out.println("relay: listening on 127.0.0.1:" + listenPort);
			while (true) {
				Socket upstream = new Socket();
				try {
					upstream.setTcpNoDelay(true);
					// The game only recovers from a client that leaves with a reset; see TunerTransport.close().
					upstream.setSoLinger(true, 0);
					upstream.connect(new InetSocketAddress(host, port), 3_000);
				} catch (IOException e) {
					upstream.close();
					Thread.sleep(1_000);
					continue;
				}
				System.out.println("relay: holding the game's debug socket at " + host + ":" + port);
				Thread down = new Thread(() -> {
					try {
						DataInputStream in = new DataInputStream(upstream.getInputStream());
						while (true) {
							byte[] frame = readFrame(in);
							Socket current = client;
							if (current != null) {
								try {
									OutputStream out = current.getOutputStream();
									out.write(frame);
									out.flush();
								} catch (IOException e) {
									// the tool went away; drop what the game had for it
								}
							}
						}
					} catch (IOException e) {
						System.out.println("relay: the game closed the socket (" + e.getMessage() + ")");
						try {
							upstream.close();
						} catch (IOException ignored) {
							// closing anyway
						}
					}
				}, "relay-down");
				down.setDaemon(true);
				down.start();
				server.setSoTimeout(1_000);
				OutputStream up = upstream.getOutputStream();
				while (!upstream.isClosed()) {
					Socket accepted;
					try {
						accepted = server.accept();
					} catch (java.net.SocketTimeoutException e) {
						continue;
					}
					accepted.setTcpNoDelay(true);
					client = accepted;
					try {
						DataInputStream in = new DataInputStream(accepted.getInputStream());
						while (true) {
							byte[] frame = readFrame(in);
							up.write(frame);
							up.flush();
						}
					} catch (EOFException e) {
						// the tool is done
					} catch (IOException e) {
						if (upstream.isClosed()) {
							break;
						}
					} finally {
						client = null;
						accepted.close();
					}
				}
			}
		}
	}
}
