package nl.jordythomassen.livingworld.script;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import nl.jordythomassen.livingworld.link.CivTransport;
import nl.jordythomassen.livingworld.tuner.TunerTransport;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/**
 * A real Lua 5.1 interpreter with a fake Civ V API loaded, standing in for the game's debug
 * socket. It enforces the limits measured on the real socket, so the tests fail when a command
 * or a printed line would be too long for the game.
 */
final class LuaGame implements CivTransport {
	/** One print() of the game carries at most about this many characters. */
	static final int MAX_PRINT = 4000;

	final LuaTable globals;
	private final List<String> printed = new ArrayList<>();
	int calls;
	int longestCommand;
	int longestLine;

	LuaGame(int width, int height) throws IOException {
		globals = JsePlatform.standardGlobals();
		globals.set("print", new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				StringBuilder line = new StringBuilder("GenericWorldAnchor: ");
				for (int i = 1; i <= args.narg(); i++) {
					if (i > 1) {
						line.append('\t');
					}
					line.append(args.arg(i).tojstring());
				}
				printed.add(line.toString());
				longestLine = Math.max(longestLine, line.length());
				return LuaValue.NONE;
			}
		});
		globals.set("FAKE_W", width);
		globals.set("FAKE_H", height);
		try (InputStream in = LuaGame.class.getResourceAsStream("fakeciv.lua")) {
			run(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		printed.clear();
	}

	/** Runs Lua and returns its first result; test code only (no size limit). */
	LuaValue run(String lua) {
		Varargs loaded = globals.get("loadstring").invoke(LuaValue.valueOf(lua));
		if (loaded.arg1().isnil()) {
			throw new LuaError("syntax error: " + loaded.arg(2).tojstring());
		}
		return loaded.arg1().invoke().arg1();
	}

	LuaValue eval(String expression) {
		return run("return " + expression);
	}

	@Override
	public List<String> call(String lua, String endMarker, long timeoutMs) throws IOException {
		calls++;
		int length = lua.getBytes(StandardCharsets.UTF_8).length;
		longestCommand = Math.max(longestCommand, length);
		if (length > TunerTransport.MAX_COMMAND) {
			throw new IOException("command of " + length + " bytes would be dropped by the game");
		}
		printed.clear();
		try {
			run(lua);
		} catch (LuaError e) {
			throw new IOException("Lua error in Civilization V: ERR:" + e.getMessage());
		}
		List<String> out = new ArrayList<>(printed);
		for (String line : out) {
			if (line.length() > MAX_PRINT) {
				throw new IOException("a printed line of " + line.length() + " characters would be cut off by the game");
			}
		}
		if (endMarker != null && out.stream().noneMatch(line -> line.contains(endMarker))) {
			throw new IOException("Civilization V did not answer within " + timeoutMs + " ms");
		}
		return out;
	}

	@Override
	public String describe() {
		return "Lua 5.1 with a fake Civ API";
	}

	@Override
	public void close() {
	}
}
