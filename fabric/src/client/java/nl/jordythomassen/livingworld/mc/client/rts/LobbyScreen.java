package nl.jordythomassen.livingworld.mc.client.rts;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import nl.jordythomassen.livingworld.protocol.Records;

/**
 * Before the game, and at its door. With no game in the world it is the table a game is set up
 * at: everybody picks a civilization, whoever keeps the world sets the map and the rules and
 * begins. In a game that runs it is the door: continue, begin another, or (for a newcomer) take
 * over a civilization, rule along with somebody, or look on. Everything on it is clicked;
 * nothing is typed.
 */
public final class LobbyScreen extends Screen {
	private static final int PANEL = 0xF0121218;
	private static final int PANEL_LIGHT = 0xFF2A2A34;
	private static final int BORDER = 0xFF75757F;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int DIM = 0xFFA8A8B4;
	private static final int GOLD = 0xFFFFD75E;
	private static final int GREEN = 0xFF58B048;

	/** The civilizations a game can be played with (see the engine's "nations"). */
	static List<Records.Option> nations = List.of();
	/** What the server last said of the table or the door, and which of the two it is: 0 the table, 1 the door for a newcomer, 2 for somebody who rules. */
	static List<Records.Option> rows = List.of();
	static int mode;
	/** Whether the server wants this screen up; it comes back when another screen that was put over it closes. */
	static boolean wanted;
	private static int nationScroll;
	private static int settingScroll;
	private static int seatScroll;
	/** The civilization the pointer last rested on in the list; what is written about it is shown beside the list. */
	private static String looking = "";

	private record Zone(int x, int y, int w, int h, String tip, Runnable click) {
		boolean has(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final List<Zone> zones = new ArrayList<>();
	private final int[] nationBox = new int[4];
	private final int[] settingBox = new int[4];
	private final int[] seatBox = new int[4];
	private float k = 1f;
	private int w;
	private int h;
	private double mx;
	private double my;

	public LobbyScreen() {
		super(Component.literal("Living World"));
	}

	/** The server put the table or the door before the player. */
	static void show(int which, List<Records.Option> what) {
		mode = which;
		rows = what;
		wanted = true;
		Minecraft minecraft = Minecraft.getInstance();
		if (!(minecraft.gui.screen() instanceof LobbyScreen)) {
			minecraft.gui.setScreen(new LobbyScreen());
		}
	}

	static void close() {
		wanted = false;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gui.screen() instanceof LobbyScreen) {
			minecraft.gui.setScreen(null);
		}
	}

	static void forget() {
		wanted = false;
		rows = List.of();
		nations = List.of();
		looking = "";
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		// The world behind it, dimmed by the picture itself.
	}

	private Records.Option row(int group, String type) {
		for (Records.Option row : rows) {
			if (row.group() == group && row.type().equals(type)) {
				return row;
			}
		}
		return null;
	}

	private void panel(GuiGraphicsExtractor g, int x, int y, int width, int height, String title) {
		g.fill(x, y, x + width, y + height, PANEL);
		g.outline(x, y, width, height, BORDER);
		if (title != null) {
			g.fill(x + 1, y + 1, x + width - 1, y + 14, PANEL_LIGHT);
			g.text(font, title, x + 6, y + 4, GOLD, true);
		}
	}

	private boolean button(GuiGraphicsExtractor g, int x, int y, int width, int height, String label, String tip, int fill, boolean enabled, Runnable click) {
		boolean over = enabled && mx >= x && mx < x + width && my >= y && my < y + height;
		int ground = !enabled ? 0xFF3A3A42 : fill;
		g.fill(x, y, x + width, y + height, 0xFF101010);
		g.fill(x + 1, y + 1, x + width - 1, y + height - 1, over ? lighter(ground) : ground);
		g.centeredText(font, label, x + width / 2, y + (height - 8) / 2, !enabled ? 0xFF8A8A92 : fill == GREEN || fill == GOLD ? 0xFF1A1206 : WHITE);
		if (enabled) {
			zones.add(new Zone(x, y, width, height, tip, click));
		} else if (tip != null) {
			zones.add(new Zone(x, y, width, height, tip, () -> { }));
		}
		return over;
	}

	private static int lighter(int color) {
		int r = Math.min(255, ((color >> 16) & 0xFF) + 34);
		int gr = Math.min(255, ((color >> 8) & 0xFF) + 34);
		int b = Math.min(255, (color & 0xFF) + 34);
		return 0xFF000000 | (r << 16) | (gr << 8) | b;
	}

	private List<String> wrap(String text, int width) {
		List<String> out = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String word : text.split(" ")) {
			if (current.length() > 0 && font.width(current + " " + word) > width) {
				out.add(current.toString());
				current.setLength(0);
			}
			current.append(current.length() == 0 ? "" : " ").append(word);
		}
		if (current.length() > 0) {
			out.add(current.toString());
		}
		return out;
	}

	private static void act(String text, int a) {
		RtsClient.act("lobby", a, 0, 0, 0, text);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		Minecraft mc = this.minecraft;
		int guiScale = Math.max(1, mc.getWindow().getGuiScale());
		int panelScale = Math.max(1, Math.round(mc.getWindow().getHeight() / 480f));
		k = panelScale / (float) guiScale;
		w = Math.round(width / k);
		h = Math.round(height / k);
		mx = mc.mouseHandler.getScaledXPos(mc.getWindow()) / k;
		my = mc.mouseHandler.getScaledYPos(mc.getWindow()) / k;
		zones.clear();
		nationBox[2] = 0;
		settingBox[2] = 0;
		seatBox[2] = 0;
		g.pose().pushMatrix();
		try {
			g.pose().scale(k, k);
			g.fill(0, 0, w, h, 0xD8080810);
			g.centeredText(font, "L I V I N G   W O R L D", w / 2, 10, GOLD);
			g.centeredText(font, "Civilization, played inside Minecraft", w / 2, 22, DIM);
			if (rows.isEmpty()) {
				g.centeredText(font, "The game is being set up...", w / 2, h / 2 - 4, WHITE);
			} else if (mode == 0) {
				table(g);
			} else {
				door(g);
			}
			for (int i = zones.size() - 1; i >= 0; i--) {
				Zone zone = zones.get(i);
				if (zone.has(mx, my) && zone.tip != null && !zone.tip.isEmpty()) {
					tip(g, zone.tip);
					break;
				}
			}
		} finally {
			g.pose().popMatrix();
		}
	}

	private void tip(GuiGraphicsExtractor g, String text) {
		List<String> lines = wrap(text, 220);
		int width = 0;
		for (String line : lines) {
			width = Math.max(width, font.width(line));
		}
		int height = lines.size() * 10 + 4;
		int x = (int) Math.min(mx + 12, w - width - 8);
		int y = (int) Math.min(my + 12, h - height - 2);
		g.fill(x - 3, y - 2, x + width + 3, y + height - 2, 0xF8100818);
		g.outline(x - 3, y - 2, width + 6, height, 0xFF5028A0);
		for (int i = 0; i < lines.size(); i++) {
			g.text(font, lines.get(i), x, y + i * 10, WHITE, false);
		}
	}

	// ---- the table a game is set up at ----

	private void table(GuiGraphicsExtractor g) {
		Records.Option me = row(2, "me");
		boolean keeps = me != null && me.id() == 1;
		boolean starting = me != null && me.turns() == 1;
		String mine = me == null ? "" : me.name();
		Records.Option running = row(2, "game");
		int top = 38;
		int bottom = h - 8;
		int margin = Math.max(8, (w - 640) / 2);
		int total = w - margin * 2;
		int listWidth = Math.min(150, total / 4);
		int rightWidth = Math.min(210, total / 3);
		int midWidth = total - listWidth - rightWidth - 8;
		int x = margin;

		// The civilizations.
		panel(g, x, top, listWidth, bottom - top, "YOUR CIVILIZATION");
		int rowHeight = 13;
		int room = Math.max(3, (bottom - top - 20) / rowHeight);
		int count = nations.size() + 1;
		nationScroll = Math.max(0, Math.min(count - room, nationScroll));
		nationBox[0] = x;
		nationBox[1] = top;
		nationBox[2] = listWidth;
		nationBox[3] = bottom - top;
		String pointed = "";
		for (int i = 0; i < Math.min(room, count); i++) {
			int index = nationScroll + i;
			Records.Option nation = index == 0 ? null : nations.get(index - 1);
			String name = nation == null ? "" : nation.type();
			int ry = top + 17 + i * rowHeight;
			boolean over = mx >= x + 2 && mx < x + listWidth - 2 && my >= ry && my < ry + rowHeight;
			boolean chosen = name.equals(mine);
			if (chosen) {
				g.fill(x + 2, ry, x + listWidth - 2, ry + rowHeight - 1, 0xFF3A5E2E);
			} else if (over) {
				g.fill(x + 2, ry, x + listWidth - 2, ry + rowHeight - 1, PANEL_LIGHT);
			}
			if (nation != null) {
				g.fill(x + 5, ry + 2, x + 13, ry + 10, 0xFF000000 | nation.group());
				g.fill(x + 7, ry + 4, x + 11, ry + 8, 0xFF000000 | nation.id());
			} else {
				g.text(font, "?", x + 7, ry + 2, GOLD, false);
			}
			g.text(font, nation == null ? "Any (a surprise)" : name, x + 18, ry + 2, chosen ? WHITE : over ? WHITE : 0xFFD8D8E0, false);
			if (over) {
				pointed = nation == null ? "?" : name;
			}
			String wantedNation = name;
			zones.add(new Zone(x + 2, ry, listWidth - 4, rowHeight, null, () -> act("nation:" + wantedNation, 0)));
		}
		if (nationScroll > 0) {
			g.text(font, "▲", x + listWidth - 11, top + 17, GOLD, false);
		}
		if (nationScroll + room < count) {
			g.text(font, "▼", x + listWidth - 11, bottom - 11, GOLD, false);
		}
		if (!pointed.isEmpty()) {
			looking = pointed.equals("?") ? "" : pointed;
		} else if (mx < x || mx >= x + listWidth) {
			looking = mine;
		}

		// What is written about the one looked at.
		int mxLeft = x + listWidth + 4;
		panel(g, mxLeft, top, midWidth, bottom - top, null);
		Records.Option shown = null;
		for (Records.Option nation : nations) {
			if (nation.type().equals(looking)) {
				shown = nation;
			}
		}
		int ty = top + 8;
		if (shown == null) {
			g.text(font, "ANY CIVILIZATION", mxLeft + 10, ty, GOLD, true);
			ty += 14;
			for (String line : wrap("The game picks one for you. Every civilization has a gift of its own, and things only its people build or train: "
				+ "point at one in the list to read what they are, and click to lead it.", midWidth - 20)) {
				g.text(font, line, mxLeft + 10, ty, 0xFFD8D8E0, false);
				ty += 10;
			}
		} else {
			String[] lines = shown.name().split("\n");
			g.fill(mxLeft + 10, ty, mxLeft + 34, ty + 24, 0xFF000000 | shown.group());
			g.fill(mxLeft + 16, ty + 6, mxLeft + 28, ty + 18, 0xFF000000 | shown.id());
			g.text(font, shown.type().toUpperCase(Locale.ROOT), mxLeft + 40, ty + 2, GOLD, true);
			g.text(font, "Led by " + lines[0], mxLeft + 40, ty + 14, WHITE, false);
			ty += 32;
			if (lines.length > 1) {
				g.text(font, "THEIR GIFT", mxLeft + 10, ty, DIM, false);
				ty += 11;
				for (String line : wrap(lines[1], midWidth - 20)) {
					g.text(font, line, mxLeft + 10, ty, 0xFFE8E8F0, false);
					ty += 10;
				}
				ty += 6;
			}
			if (lines.length > 2) {
				g.text(font, "ONLY THEIRS", mxLeft + 10, ty, DIM, false);
				ty += 11;
				for (int i = 2; i < lines.length; i++) {
					for (String line : wrap(lines[i], midWidth - 20)) {
						g.text(font, line, mxLeft + 10, ty, 0xFFE8E8F0, false);
						ty += 10;
					}
				}
			}
			if (shown.type().equals(mine)) {
				g.text(font, "You lead them in the next game.", mxLeft + 10, bottom - 14, 0xFF7ED06A, false);
			}
		}
		if (running != null) {
			g.text(font, "Now playing: " + running.name(), mxLeft + 10, bottom - 26, DIM, false);
		}

		// The game, and who is at the table.
		int rx = mxLeft + midWidth + 4;
		panel(g, rx, top, rightWidth, bottom - top, "THE GAME");
		List<Records.Option> settings = new ArrayList<>();
		List<Records.Option> people = new ArrayList<>();
		for (Records.Option row : rows) {
			if (row.group() == 0) {
				settings.add(row);
			} else if (row.group() == 1) {
				people.add(row);
			}
		}
		int peopleHeight = 16 + people.size() * 11;
		int buttons = running != null ? 46 : 26;
		int settingsBottom = bottom - peopleHeight - buttons - 6;
		int settingRoom = Math.max(2, (settingsBottom - top - 18) / 14);
		settingScroll = Math.max(0, Math.min(settings.size() - settingRoom, settingScroll));
		settingBox[0] = rx;
		settingBox[1] = top;
		settingBox[2] = rightWidth;
		settingBox[3] = settingsBottom - top;
		for (int i = 0; i < Math.min(settingRoom, settings.size()); i++) {
			Records.Option setting = settings.get(settingScroll + i);
			String[] parts = setting.name().split("\\|", -1);
			int sy = top + 18 + i * 14;
			g.text(font, parts[0], rx + 6, sy + 3, 0xFFD8D8E0, false);
			int valueWidth = 84;
			int vx = rx + rightWidth - valueWidth - 6;
			String key = setting.type();
			if (keeps) {
				button(g, vx, sy, 12, 12, "<", null, 0xFF34343E, true, () -> act("prev:" + key, 0));
				button(g, vx + valueWidth - 12, sy, 12, 12, ">", null, 0xFF34343E, true, () -> act("next:" + key, 0));
			}
			String value = parts.length > 1 ? parts[1] : "";
			while (font.width(value) > valueWidth - 28 && value.length() > 3) {
				value = value.substring(0, value.length() - 1);
			}
			g.centeredText(font, value, vx + valueWidth / 2, sy + 3, GOLD);
			zones.add(new Zone(rx + 2, sy, rightWidth - valueWidth - 10, 13, parts.length > 2 ? parts[2] : null, () -> { }));
		}
		if (settingScroll > 0) {
			g.text(font, "▲", rx + rightWidth - 11, top + 3, GOLD, false);
		}
		if (settingScroll + settingRoom < settings.size()) {
			g.text(font, "▼ more", rx + 6, settingsBottom - 9, GOLD, false);
		}
		int py = settingsBottom + 4;
		g.fill(rx + 1, py, rx + rightWidth - 1, py + 12, PANEL_LIGHT);
		g.text(font, "AT THE TABLE", rx + 6, py + 2, GOLD, true);
		String keeper = "";
		for (int i = 0; i < people.size(); i++) {
			Records.Option person = people.get(i);
			String leads = person.name().isEmpty() ? "any civilization" : person.name();
			g.text(font, person.type() + (person.turns() == 1 ? " ★" : ""), rx + 6, py + 15 + i * 11, person.id() == 1 ? WHITE : 0xFFD8D8E0, false);
			g.text(font, leads, rx + rightWidth - 6 - font.width(leads), py + 15 + i * 11, DIM, false);
			if (person.turns() == 1 && keeper.isEmpty()) {
				keeper = person.type();
			}
		}
		int by = bottom - buttons + 2;
		if (starting) {
			button(g, rx + 6, by, rightWidth - 12, 20, "Making the world...", null, GREEN, false, () -> { });
		} else if (keeps) {
			button(g, rx + 6, by, rightWidth - 12, 20, "BEGIN THE GAME", "A new world is made; that takes a moment. Everybody at the table gets a civilization of their own.", GREEN, true,
				() -> act("start", 0));
		} else {
			button(g, rx + 6, by, rightWidth - 12, 20, "Waiting for " + (keeper.isEmpty() ? "the keeper" : keeper), "Whoever keeps this world (★) sets the game up and begins it", GREEN,
				false, () -> { });
		}
		if (running != null) {
			button(g, rx + 6, by + 23, rightWidth - 12, 16, "Back to the game being played", "Nothing changes; the game that runs goes on", 0xFF34343E, true, () -> act("back", 0));
		}
	}

	// ---- the door of a game that runs ----

	private void door(GuiGraphicsExtractor g) {
		Records.Option me = row(2, "me");
		boolean keeps = me != null && me.id() == 1;
		Records.Option running = row(2, "game");
		List<Records.Option> civs = new ArrayList<>();
		boolean seated = mode == 2;
		for (Records.Option row : rows) {
			if (row.group() == 3) {
				civs.add(row);
			}
		}
		int width = Math.min(460, w - 24);
		int x = (w - width) / 2;
		int top = 40;
		int bottom = h - 10;
		panel(g, x, top, width, bottom - top, seated ? "YOUR GAME" : "A GAME IS BEING PLAYED");
		g.text(font, running == null ? "" : running.name(), x + 8, top + 19, WHITE, false);
		g.text(font, seated ? "Go on with it, or look at who plays. Whoever keeps the world can begin another game."
			: "Take over a civilization the computer plays, rule along with somebody, or just look on.", x + 8, top + 31, DIM, false);
		int listTop = top + 46;
		int buttons = 30;
		int rowHeight = 18;
		int room = Math.max(2, (bottom - buttons - listTop) / rowHeight);
		seatScroll = Math.max(0, Math.min(civs.size() - room, seatScroll));
		seatBox[0] = x;
		seatBox[1] = listTop;
		seatBox[2] = width;
		seatBox[3] = room * rowHeight;
		for (int i = 0; i < Math.min(room, civs.size()); i++) {
			Records.Option civ = civs.get(seatScroll + i);
			// type: leader | 1 when they ended their turn | score | 1 when it is this player's | names of who rules it
			String[] parts = civ.type().split("\\|", -1);
			boolean mineNow = parts.length > 3 && parts[3].equals("1");
			String rulers = parts.length > 4 ? parts[4] : "";
			int ry = listTop + i * rowHeight;
			if (mineNow) {
				g.fill(x + 3, ry, x + width - 3, ry + rowHeight - 2, 0xFF2E4A26);
			} else if (i % 2 == 0) {
				g.fill(x + 3, ry, x + width - 3, ry + rowHeight - 2, 0x18FFFFFF);
			}
			g.text(font, civ.name(), x + 8, ry + 4, WHITE, true);
			String who = civ.turns() == 0 ? "the computer" : rulers.isEmpty() ? "somebody" : rulers;
			g.text(font, parts[0] + "  ·  " + who, x + 8 + Math.max(84, font.width(civ.name()) + 10), ry + 4, civ.turns() == 0 ? DIM : 0xFF9FE08A, false);
			int bx = x + width - 8;
			int civId = civ.id();
			if (!mineNow) {
				if (civ.turns() == 0) {
					bx -= 70;
					button(g, bx, ry + 1, 70, 14, "Take over", "You rule " + civ.name() + " from now on, as it stands. The civilization you had goes to the computer.", 0xFF3D5A80, true,
						() -> act("take", civId));
				} else {
					bx -= 70;
					button(g, bx, ry + 1, 70, 14, "Rule along", "You and " + who + " rule " + civ.name() + " together: one civilization, one turn", 0xFF3D5A80, true, () -> act("share", civId));
				}
			} else {
				String yours = "yours";
				g.text(font, yours, bx - font.width(yours), ry + 4, GOLD, false);
			}
		}
		if (seatScroll > 0) {
			g.text(font, "▲", x + width - 11, listTop - 9, GOLD, false);
		}
		if (seatScroll + room < civs.size()) {
			g.text(font, "▼", x + width - 11, listTop + room * rowHeight - 4, GOLD, false);
		}
		int by = bottom - 26;
		if (seated) {
			int third = (width - 24) / 3;
			button(g, x + 8, by, third, 20, "CONTINUE", "Back to your civilization (Enter)", GREEN, true, () -> {
				act("continue", 0);
				close();
			});
			button(g, x + 12 + third, by, third, 20, "A new game...", keeps ? "Choose a civilization, a map and the rules. The game being played is set aside, not lost."
				: "Only whoever keeps this world can begin another game", 0xFF806A2A, keeps, () -> act("new", 0));
			button(g, x + 16 + third * 2, by, third, 20, "Just look on", "Give up your civilization to the computer (or to whoever rules it with you) and watch", 0xFF34343E, true,
				() -> act("watch", 0));
		} else {
			button(g, x + 8, by, width / 2 - 12, 20, "Just look on", "Watch the game without ruling anything; you can still take a civilization later", 0xFF34343E, true, () -> {
				act("watch", 0);
				close();
			});
			if (keeps) {
				button(g, x + width / 2 + 4, by, width / 2 - 12, 20, "A new game...", "Choose a civilization, a map and the rules. The game being played is set aside, not lost.", 0xFF806A2A,
					true, () -> act("new", 0));
			}
		}
	}

	// ---- the mouse and the keys ----

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return true;
		}
		double x = event.x() / k;
		double y = event.y() / k;
		for (int i = zones.size() - 1; i >= 0; i--) {
			if (zones.get(i).has(x, y)) {
				zones.get(i).click.run();
				return true;
			}
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		double px = x / k;
		double py = y / k;
		int step = scrollY > 0 ? -2 : scrollY < 0 ? 2 : 0;
		if (nationBox[2] > 0 && px >= nationBox[0] && px < nationBox[0] + nationBox[2]) {
			nationScroll += step;
		} else if (settingBox[2] > 0 && px >= settingBox[0] && px < settingBox[0] + settingBox[2] && py < settingBox[1] + settingBox[3]) {
			settingScroll += step;
		} else if (seatBox[2] > 0) {
			seatScroll += step;
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == InputConstants.KEY_ESCAPE) {
			if (mode == 2) {
				// Somebody who rules already just goes back to their game.
				act("continue", 0);
				close();
			} else {
				// The game's own menu (options, leaving the world); this screen comes back when it closes.
				Minecraft mc = this.minecraft;
				mc.gui.setScreen(null);
				mc.pauseGame(false);
			}
			return true;
		}
		if ((key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) && mode == 2) {
			act("continue", 0);
			close();
			return true;
		}
		return true;
	}
}
