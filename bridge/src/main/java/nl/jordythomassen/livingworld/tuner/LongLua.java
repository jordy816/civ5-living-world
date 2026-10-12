package nl.jordythomassen.livingworld.tuner;

import java.io.IOException;
import java.util.List;
import nl.jordythomassen.livingworld.link.BootstrapChunks;

/** Runs a piece of Lua that is too long for one message: send it in parts, then run the whole. */
public final class LongLua {
	private LongLua() {
	}

	/**
	 * Sends {@code source} to a state in parts and runs it there. The printed lines are returned;
	 * the last one is {@code marker ok} or {@code marker <error>}.
	 */
	public static List<String> run(TunerTransport tuner, String state, String source, String marker, long timeoutMs) throws IOException {
		List<String> parts = BootstrapChunks.split(source, BootstrapChunks.CHUNK);
		tuner.callIn(state, "__lwx={}", null, 5_000);
		for (int i = 0; i < parts.size(); i++) {
			tuner.callIn(state, "__lwx[" + (i + 1) + "]=" + BootstrapChunks.longString(parts.get(i)), null, 5_000);
		}
		String run = "local f,e=loadstring(table.concat(__lwx),\"lwx\");__lwx=nil;"
			+ "if f then local ok,err=pcall(f);print(\"" + marker + " \"..(ok and \"ok\" or tostring(err))) else print(\"" + marker + " \"..tostring(e)) end";
		return tuner.callIn(state, run, marker, timeoutMs);
	}
}
