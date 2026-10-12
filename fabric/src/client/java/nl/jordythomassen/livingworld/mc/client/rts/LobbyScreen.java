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
 * at, in two steps shown along the top: first the game (the land with a sketch of its shape, and
 * the rules), set by whoever keeps the world; then the lobby, where everybody picks a civilization
 * and says when they are ready, and the keeper begins. In a game that runs it is the door: continue, begin another, or (for a newcomer) take
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

	private final java.util.Map<String, net.minecraft.world.item.ItemStack> icons = new java.util.HashMap<>();

	private net.minecraft.world.item.ItemStack icon(String id) {
		return icons.computeIfAbsent(id, key -> new net.minecraft.world.item.ItemStack(
			net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(key))));
	}

	private void item(GuiGraphicsExtractor g, String id, float x, float y, float scale) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.item(icon(id), 0, 0);
		g.pose().popMatrix();
	}

	/** Words and small pictures on one line; returns where it ends. */
	private int rich(GuiGraphicsExtractor g, List<Rich.Piece> pieces, int x, int y) {
		for (Rich.Piece piece : pieces) {
			if (piece.picture() != null) {
				item(g, piece.picture(), x + 1, y, 0.5f);
				x += Rich.PICTURE;
			} else {
				g.text(font, piece.text(), x, y, piece.color(), false);
				x += font.width(piece.text());
			}
		}
		return x;
	}

	/** A sentence with its measures as pictures, on as many lines as it takes (at most {@code most}); returns the y below it. */
	private int richLines(GuiGraphicsExtractor g, String text, int x, int y, int width, int most, int color) {
		for (List<String> line : Rich.flow(font, Rich.words(text, false), width, most, true)) {
			rich(g, Rich.pieces(line, true, color, true), x, y);
			y += 10;
		}
		return y;
	}

	/** The sketch of a kind of map: {@code cell} pixels a hex, every other row shifted half a hex. */
	private void mapSketch(GuiGraphicsExtractor g, String type, int x, int y, int cell) {
		int[][] cells = MapPreview.cells(type);
		int width = MapPreview.COLS * cell + cell / 2;
		int height = MapPreview.ROWS * cell;
		g.fill(x - 2, y - 2, x + width + 2, y + height + 2, 0xFF05070C);
		g.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF1B3A66);
		for (int row = 0; row < MapPreview.ROWS; row++) {
			int shift = row % 2 == 0 ? 0 : cell / 2;
			for (int col = 0; col < MapPreview.COLS; col++) {
				int kind = cells[row][col];
				if (kind == MapPreview.WATER) {
					continue;
				}
				int color = kind == MapPreview.SHORE ? 0xFF2C5C96 : kind == MapPreview.ROUGH ? 0xFF4E8A3A : 0xFF79B555;
				g.fill(x + col * cell + shift, y + row * cell, x + col * cell + shift + cell, y + row * cell + cell, color);
			}
		}
		g.outline(x - 2, y - 2, width + 4, height + 4, BORDER);
	}

	/** Where the table is: three steps in a row, the one being taken lit, those done ticked. */
	private void steps(GuiGraphicsExtractor g, int stage, int seconds) {
		String[] names = { "1  THE GAME", "2  WHO PLAYS", "3  BEGIN" };
		int now = seconds >= 0 ? 3 : stage;
		int each = 118;
		int gap = 22;
		int x = (w - each * 3 - gap * 2) / 2;
		int y = 36;
		for (int i = 0; i < 3; i++) {
			int step = i + 1;
			boolean lit = step == now;
			boolean done = step < now;
			g.fill(x, y, x + each, y + 15, lit ? 0xFF5A4A16 : done ? 0xFF24402A : 0xFF20202A);
			g.outline(x, y, each, 15, lit ? GOLD : done ? 0xFF4E9A52 : 0xFF45454F);
			g.centeredText(font, (done ? "✔ " : "") + names[i].substring(done ? 3 : 0), x + each / 2, y + 4, lit ? GOLD : done ? 0xFF9FE08A : DIM);
			if (i < 2) {
				g.centeredText(font, "›", x + each + gap / 2, y + 4, step < now ? 0xFF9FE08A : DIM);
			}
			x += each + gap;
		}
	}

	private void table(GuiGraphicsExtractor g) {
		Records.Option me = row(2, "me");
		Records.Option stageRow = row(2, "stage");
		boolean keeps = me != null && me.id() == 1;
		boolean starting = me != null && me.turns() == 1;
		Records.Option running = row(2, "game");
		String[] about = stageRow == null ? new String[0] : stageRow.name().split("\\|", -1);
		// A game set up while another runs is one person's doing: nobody to wait for, so no lobby.
		int stage = stageRow == null ? 1 : stageRow.id();
		String keeper = about.length > 0 ? about[0] : "";
		String access = about.length > 1 ? about[1] : "alone";
		boolean ready = about.length > 2 && about[2].equals("1");
		int seconds = stageRow == null ? -1 : stageRow.turns();
		List<Records.Option> settings = new ArrayList<>();
		List<Records.Option> people = new ArrayList<>();
		for (Records.Option row : rows) {
			if (row.group() == 0) {
				settings.add(row);
			} else if (row.group() == 1) {
				people.add(row);
			}
		}
		steps(g, stage, seconds);
		int top = 58;
		int bottom = h - 8;
		int margin = Math.max(8, (w - 660) / 2);
		int total = w - margin * 2;
		if (stage == 1) {
			gameStep(g, margin, top, total, bottom, settings, people, keeps, starting, keeper, running);
		} else {
			lobbyStep(g, margin, top, total, bottom, settings, people, keeps, starting, keeper, access, ready, me == null ? "" : me.name(), running);
		}
		if (seconds >= 0) {
			String count = "The game begins in " + seconds;
			int width = font.width(count) * 2 + 40;
			g.fill((w - width) / 2, h / 2 - 22, (w + width) / 2, h / 2 + 22, 0xF0101018);
			g.outline((w - width) / 2, h / 2 - 22, width, 44, GOLD);
			g.pose().pushMatrix();
			g.pose().translate(w / 2f, h / 2f - 8);
			g.pose().scale(2f, 2f);
			g.centeredText(font, count, 0, 0, GOLD);
			g.pose().popMatrix();
		}
	}

	private static String value(Records.Option setting) {
		String[] parts = setting.name().split("\\|", -1);
		return parts.length > 1 ? parts[1] : "";
	}

	/** One thing about the game on a line: what it is, and what it is set to between two arrows (for whoever keeps the world). */
	private void settingRow(GuiGraphicsExtractor g, Records.Option setting, int x, int y, int width, int valueWidth, boolean keeps) {
		String[] parts = setting.name().split("\\|", -1);
		String key = setting.type();
		g.text(font, parts[0], x + 6, y + 3, 0xFFD8D8E0, false);
		int vx = x + width - valueWidth - 6;
		g.fill(vx + 13, y, vx + valueWidth - 13, y + 12, 0xFF1A1A22);
		if (keeps) {
			button(g, vx, y, 12, 12, "<", null, 0xFF34343E, true, () -> act("prev:" + key, 0));
			button(g, vx + valueWidth - 12, y, 12, 12, ">", null, 0xFF34343E, true, () -> act("next:" + key, 0));
		}
		g.centeredText(font, value(setting), vx + valueWidth / 2, y + 2, GOLD);
		zones.add(new Zone(x + 2, y, width - valueWidth - 10, 13, parts.length > 2 ? parts[2] : null, () -> { }));
	}

	/** The first step: what game it is to be. The land with a sketch of its shape, then the rules. */
	private void gameStep(GuiGraphicsExtractor g, int x, int top, int total, int bottom, List<Records.Option> settings, List<Records.Option> people, boolean keeps,
		boolean starting, String keeper, Records.Option running) {
		int cell = 4;
		int sketchWidth = MapPreview.COLS * cell + cell / 2;
		int leftWidth = sketchWidth + 28;
		int rightWidth = total - leftWidth - 4;
		panel(g, x, top, leftWidth, bottom - top, "THE WORLD");
		Records.Option land = null;
		Records.Option size = null;
		List<Records.Option> rules = new ArrayList<>();
		for (Records.Option setting : settings) {
			if (setting.type().equals("mapType")) {
				land = setting;
			} else if (setting.type().equals("mapSize")) {
				size = setting;
			} else {
				rules.add(setting);
			}
		}
		int sx = x + (leftWidth - sketchWidth) / 2;
		int sy = top + 22;
		String type = land == null ? "Pangaea" : value(land);
		mapSketch(g, type, sx, sy, cell);
		int ly = sy + MapPreview.ROWS * cell + 8;
		// The name of the land in full, between its two arrows, as wide as the sketch.
		if (keeps) {
			button(g, sx - 2, ly, 16, 16, "<", "Another shape of world", 0xFF34343E, true, () -> act("prev:mapType", 0));
			button(g, sx + sketchWidth - 14, ly, 16, 16, ">", "Another shape of world", 0xFF34343E, true, () -> act("next:mapType", 0));
		}
		g.fill(sx + 16, ly, sx + sketchWidth - 16, ly + 16, 0xFF1A1A22);
		g.centeredText(font, type.toUpperCase(Locale.ROOT), sx + sketchWidth / 2, ly + 4, GOLD);
		int ty = ly + 22;
		for (String line : wrap(MapPreview.says(type), leftWidth - 20)) {
			g.text(font, line, x + 10, ty, 0xFFD8D8E0, false);
			ty += 10;
		}
		ty += 2;
		for (String line : wrap("A sketch of the shape. The map itself is new every game.", leftWidth - 20)) {
			g.text(font, line, x + 10, ty, 0xFF7C7C88, false);
			ty += 10;
		}
		ty += 6;
		if (size != null) {
			settingRow(g, size, x + 2, ty, leftWidth - 4, 96, keeps);
			ty += 18;
		}
		// Who is here already, under the world they will play in.
		g.fill(x + 1, ty + 2, x + leftWidth - 1, ty + 14, PANEL_LIGHT);
		g.text(font, "AT THE TABLE  ·  " + people.size(), x + 6, ty + 4, GOLD, true);
		ty += 17;
		for (int i = 0; i < people.size() && ty + 10 < bottom - 4; i++) {
			Records.Option person = people.get(i);
			g.text(font, ((person.turns() & 1) != 0 ? "★ " : "   ") + person.type(), x + 6, ty, person.id() == 1 ? WHITE : 0xFFD8D8E0, false);
			ty += 11;
		}

		int rx = x + leftWidth + 4;
		panel(g, rx, top, rightWidth, bottom - top, "THE RULES");
		int buttons = running != null ? 50 : 30;
		int rulesBottom = bottom - buttons - 4;
		int rowHeight = 16;
		int room = Math.max(2, (rulesBottom - top - 20) / rowHeight);
		settingScroll = Math.max(0, Math.min(rules.size() - room, settingScroll));
		settingBox[0] = rx;
		settingBox[1] = top;
		settingBox[2] = rightWidth;
		settingBox[3] = rulesBottom - top;
		for (int i = 0; i < Math.min(room, rules.size()); i++) {
			if (i % 2 == 1) {
				g.fill(rx + 2, top + 18 + i * rowHeight - 2, rx + rightWidth - 2, top + 18 + i * rowHeight + 14, 0x10FFFFFF);
			}
			settingRow(g, rules.get(settingScroll + i), rx, top + 18 + i * rowHeight, rightWidth, 110, keeps);
		}
		if (settingScroll > 0) {
			g.text(font, "▲", rx + rightWidth - 11, top + 3, GOLD, false);
		}
		if (settingScroll + room < rules.size()) {
			g.text(font, "▼ more", rx + 6, rulesBottom - 9, GOLD, false);
		}
		int by = bottom - buttons + 2;
		if (starting) {
			button(g, rx + 6, by, rightWidth - 12, 22, "Making the world...", null, GREEN, false, () -> { });
		} else if (keeps) {
			button(g, rx + 6, by, rightWidth - 12, 22, "NEXT: WHO PLAYS  ›", "The game is set. On to the lobby: everybody picks a civilization and says when they are ready.", GREEN, true,
				() -> act("stage:2", 0));
		} else {
			button(g, rx + 6, by, rightWidth - 12, 22, (keeper.isEmpty() ? "The keeper" : keeper) + " is setting up the game", "Whoever keeps this world (★) chooses the map and the rules. "
				+ "You pick your civilization in the next step.", GREEN, false, () -> { });
		}
		if (running != null) {
			button(g, rx + 6, by + 26, rightWidth - 12, 16, "Back to the game being played", "Nothing changes; the game that runs goes on", 0xFF34343E, true, () -> act("back", 0));
		}
	}

	/** The second step: who plays. Everybody picks a civilization and says when they are ready; the keeper begins. */
	private void lobbyStep(GuiGraphicsExtractor g, int x, int top, int total, int bottom, List<Records.Option> settings, List<Records.Option> people, boolean keeps,
		boolean starting, String keeper, String access, boolean ready, String mine, Records.Option running) {
		int listWidth = Math.min(150, total / 4);
		int rightWidth = Math.min(200, total / 3);
		int midWidth = total - listWidth - rightWidth - 8;

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
			String takenBy = "";
			for (Records.Option person : people) {
				if (!name.isEmpty() && person.name().equals(name) && person.id() != 1) {
					takenBy = person.type();
				}
			}
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
			g.text(font, nation == null ? "Any (a surprise)" : name, x + 18, ry + 2, !takenBy.isEmpty() ? 0xFF7C7C88 : chosen || over ? WHITE : 0xFFD8D8E0, false);
			if (over) {
				pointed = nation == null ? "?" : name;
			}
			String wantedNation = name;
			zones.add(new Zone(x + 2, ry, listWidth - 4, rowHeight, takenBy.isEmpty() ? null : takenBy + " leads them", () -> act("nation:" + wantedNation, 0)));
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

		// The one looked at: who leads it, its gift in points, and what only its people have.
		int ml = x + listWidth + 4;
		panel(g, ml, top, midWidth, bottom - top, null);
		Records.Option shown = null;
		for (Records.Option nation : nations) {
			if (nation.type().equals(looking)) {
				shown = nation;
			}
		}
		int ty = top + 8;
		int inner = midWidth - 20;
		if (shown == null) {
			g.text(font, "ANY CIVILIZATION", ml + 10, ty, GOLD, true);
			ty += 14;
			for (String line : wrap("The game picks one for you. Point at a civilization in the list to see its gift and what only its people have; click to lead it.", inner)) {
				g.text(font, line, ml + 10, ty, 0xFFD8D8E0, false);
				ty += 10;
			}
		} else {
			String[] lines = shown.name().split("\n");
			g.fill(ml + 10, ty, ml + 34, ty + 24, 0xFF000000 | shown.group());
			g.fill(ml + 16, ty + 6, ml + 28, ty + 18, 0xFF000000 | shown.id());
			g.text(font, shown.type().toUpperCase(Locale.ROOT), ml + 40, ty + 2, GOLD, true);
			g.text(font, "Led by " + lines[0], ml + 40, ty + 14, WHITE, false);
			ty += 32;
			boolean theirs = false;
			for (int i = 1; i < lines.length && ty < bottom - 30; i++) {
				String line = lines[i];
				if (line.startsWith("Gift: ")) {
					g.fill(ml + 6, ty - 2, ml + midWidth - 6, ty + 11, 0xFF23232D);
					item(g, "nether_star", ml + 9, ty - 1, 0.6f);
					g.text(font, "THEIR GIFT", ml + 22, ty + 1, DIM, false);
					g.text(font, line.substring(6), ml + 28 + font.width("THEIR GIFT"), ty + 1, GOLD, true);
					ty += 15;
				} else if (line.startsWith("- ")) {
					g.fill(ml + 12, ty + 3, ml + 15, ty + 6, GOLD);
					ty = richLines(g, line.substring(2), ml + 20, ty, inner - 12, 3, 0xFFE8E8F0) + 2;
				} else {
					int colon = line.indexOf(": ");
					String[] parts = colon < 0 ? new String[0] : line.substring(colon + 2).split("\\|", -1);
					if (parts.length < 3) {
						continue;
					}
					if (!theirs) {
						theirs = true;
						ty += 4;
						g.fill(ml + 6, ty - 2, ml + midWidth - 6, ty + 11, 0xFF23232D);
						g.text(font, "ONLY THEIRS", ml + 10, ty + 1, DIM, false);
						ty += 15;
					}
					String picture = Rich.picture(line.substring(0, colon + 2) + parts[0]);
					if (picture != null) {
						item(g, picture, ml + 9, ty - 1, 0.75f);
					}
					int nx = ml + 26;
					g.text(font, parts[0], nx, ty, WHITE, true);
					String kind = line.substring(0, colon);
					String instead = parts[1].isEmpty() ? kind.toLowerCase(Locale.ROOT) + " only they have" : "their " + parts[1];
					g.text(font, instead, nx + font.width(parts[0]) + 8, ty, DIM, false);
					ty += 11;
					// What it is in numbers and what it does, each thing after the other.
					ty = richLines(g, parts[2].replace("; ", "   ").replace(", ", "   "), nx, ty, inner - 18, 2, 0xFFC8C8D4) + 4;
				}
			}
			if (shown.type().equals(mine)) {
				g.text(font, "✔ You lead them in the next game.", ml + 10, bottom - 14, 0xFF7ED06A, false);
			}
		}
		if (running != null) {
			g.text(font, "Now playing: " + running.name(), ml + 10, bottom - 26, DIM, false);
		}

		// The game in short, who is at the table and whether they are ready, and the way on.
		int rx = ml + midWidth + 4;
		panel(g, rx, top, rightWidth, bottom - top, "THE GAME");
		String type = "Pangaea";
		List<String> facts = new ArrayList<>();
		for (Records.Option setting : settings) {
			String label = setting.name().split("\\|", -1)[0];
			String set = value(setting);
			switch (setting.type()) {
				case "mapType" -> type = set;
				case "mapSize", "speed", "difficulty" -> facts.add(set);
				case "rivals" -> facts.add(set + " computer civ" + (set.equals("1") ? "" : "s"));
				case "cityStates" -> facts.add(set + " city-states");
				case "barbarians" -> {
					if (!set.equals("As usual")) {
						facts.add(set.equals("None") ? "no barbarians" : "raging barbarians");
					}
				}
				case "oneCity" -> {
					if (set.equals("Yes")) {
						facts.add("one city each");
					}
				}
				default -> {
					// What is as usual goes unsaid; what was switched off is worth a word.
					if (set.equals("No")) {
						facts.add("no " + label.toLowerCase(Locale.ROOT));
					}
				}
			}
		}
		int cell = 2;
		int sketchWidth = MapPreview.COLS * cell + cell / 2;
		mapSketch(g, type, rx + 8, top + 21, cell);
		int fx = rx + 8 + sketchWidth + 8;
		int fy = top + 19;
		g.text(font, type, fx, fy, GOLD, false);
		fy += 11;
		StringBuilder said = new StringBuilder();
		for (String fact : facts) {
			said.append(said.length() == 0 ? "" : " · ").append(fact);
		}
		int belowSketch = top + 21 + MapPreview.ROWS * cell + 6;
		for (String line : wrap(said.toString(), rightWidth - (fx - rx) - 6)) {
			if (fy + 9 > belowSketch + 30) {
				break;
			}
			g.text(font, line, fy < belowSketch ? fx : rx + 8, fy, 0xFFD8D8E0, false);
			fy += 10;
		}
		int py = Math.max(fy, belowSketch) + 4;
		g.fill(rx + 1, py, rx + rightWidth - 1, py + 12, PANEL_LIGHT);
		g.text(font, "AT THE TABLE", rx + 6, py + 2, GOLD, true);
		int waiting = 0;
		for (int i = 0; i < people.size(); i++) {
			Records.Option person = people.get(i);
			boolean isReady = (person.turns() & 2) != 0;
			boolean isKeeper = (person.turns() & 1) != 0;
			int ry = py + 15 + i * 12;
			if (people.size() > 1 && !isKeeper) {
				// (Whoever keeps the world says they are ready by beginning.)
				g.text(font, isReady ? "✔" : "·", rx + 7, ry, isReady ? 0xFF7ED06A : DIM, false);
				if (!isReady) {
					waiting++;
				}
			}
			g.text(font, person.type() + (isKeeper ? " ★" : ""), rx + 18, ry, person.id() == 1 ? WHITE : 0xFFD8D8E0, false);
			String leads = person.name().isEmpty() ? "any" : person.name();
			g.text(font, leads, rx + rightWidth - 6 - font.width(leads), ry, DIM, false);
		}
		int hy = py + 17 + people.size() * 12;
		if (people.size() == 1) {
			String how = access.equals("server") ? "Friends join through Multiplayer, with this server's address." : access.equals("lan") ? "Open to LAN: friends in the house see this world under Multiplayer."
				: "Playing with others? Esc › Open to LAN, or run the server. Alone: just begin.";
			for (String line : wrap(how, rightWidth - 14)) {
				g.text(font, line, rx + 7, hy, 0xFF7C7C88, false);
				hy += 10;
			}
		}
		boolean says = people.size() > 1 && !keeps;
		int buttons = (keeps ? 48 : 26) + (says ? 24 : 0) + (running != null ? 20 : 0);
		int by = bottom - buttons;
		if (says) {
			button(g, rx + 6, by, rightWidth - 12, 20, ready ? "✔ READY  (click to wait)" : "I AM READY", ready ? "You are ready. Click again if you want to change something first."
				: "Say you are ready with the civilization you picked", ready ? 0xFF2E5A30 : 0xFF3D5A80, !starting, () -> act("ready", 0));
			by += 24;
		}
		if (starting) {
			button(g, rx + 6, by, rightWidth - 12, 20, "Making the world...", null, GREEN, false, () -> { });
			by += 24;
		} else if (keeps) {
			button(g, rx + 6, by, rightWidth - 12, 20, waiting > 0 ? "BEGIN  (" + waiting + " not ready)" : "BEGIN THE GAME",
				"A new world is made; that takes a moment. Everybody at the table gets a civilization of their own." + (waiting > 0 ? " Somebody is not ready yet: you can begin anyway." : ""),
				GREEN, true, () -> act("start", 0));
			by += 24;
			button(g, rx + 6, by, rightWidth - 12, 16, "‹  Change the game", "Back to the map and the rules. Whoever was ready says so again.", 0xFF34343E, true, () -> act("stage:1", 0));
			by += 20;
		} else {
			button(g, rx + 6, by, rightWidth - 12, 20, (keeper.isEmpty() ? "The keeper" : keeper) + " begins the game", "Whoever keeps this world (★) begins when everybody is ready", GREEN, false,
				() -> { });
			by += 24;
		}
		if (running != null) {
			button(g, rx + 6, by, rightWidth - 12, 16, "Back to the game being played", "Nothing changes; the game that runs goes on", 0xFF34343E, true, () -> act("back", 0));
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
