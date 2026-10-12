package nl.jordythomassen.livingworld.mc.client.rts;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import nl.jordythomassen.livingworld.board.BoardModel;
import nl.jordythomassen.livingworld.coords.GridPos;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.mc.Looks;
import nl.jordythomassen.livingworld.mc.Net;
import nl.jordythomassen.livingworld.mc.RtsView;
import nl.jordythomassen.livingworld.protocol.Records;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.Empire;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.Legend;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * The screen of the view from above: the map is the world itself, seen through the flat camera,
 * and this draws what a strategy game puts over it. The layout is Civilization V's: the realm's
 * numbers along the top, research top left, the selected unit and what it can do bottom left,
 * the turn button over a small map bottom right, news down the right side, a banner over every
 * city and a flag over every unit. The mouse is a pointer: left selects, right sends.
 */
public final class RtsScreen extends Screen {
	private static final int PANEL = 0xE0121218;
	private static final int PANEL_LIGHT = 0xE02A2A34;
	private static final int BORDER = 0xFF75757F;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int DIM = 0xFFA8A8B4;
	private static final int GOLD = 0xFFFFD75E;
	private static final int GREEN = 0xFF60E070;
	private static final int RED = 0xFFFF5548;

	/** A place on the screen that does something when clicked, and says what when pointed at. */
	private record Zone(int x, int y, int w, int h, String tip, Runnable click) {
		boolean has(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	/** For every zone, how many panels had been drawn when it was made: a panel drawn after it, over it, takes the click. */
	private final List<Integer> zoneLayers = new ArrayList<>();
	private final List<Zone> zones = new ArrayList<>() {
		@Override
		public boolean add(Zone zone) {
			zoneLayers.add(solid.size());
			return super.add(zone);
		}

		@Override
		public void clear() {
			zoneLayers.clear();
			super.clear();
		}
	};
	/** Places that do something on a right click (a piece of news is put away). */
	private final List<Zone> rightZones = new ArrayList<>();
	/** The parts of the screen a panel covers: a click there is never a click on the land behind it. */
	private final List<int[]> solid = new ArrayList<>();
	/** Where the list of choices is on the screen, for the wheel; all zero when it is not up. */
	private final int[] choiceBox = new int[4];
	/** What choosing each line of the list that is up does, in order: the number keys press them. */
	private final List<Runnable> rowActions = new ArrayList<>();
	/** The same for the buttons of the page that is up. */
	private final List<Runnable> pageActions = new ArrayList<>();
	private final int[] menuBox = new int[4];
	private final int[] techBox = new int[4];
	private final Map<String, ItemStack> icons = new HashMap<>();
	private final float[] point = new float[2];
	private final float[] cornerX = new float[6];
	private final float[] cornerY = new float[6];
	private boolean showGrid = true;
	/** Off in the development client, where the pointer is parked wherever the last test left it. */
	private final boolean edgePan = !Boolean.getBoolean("livingworld.debug") || Boolean.getBoolean("livingworld.edgepan");
	/** Screen units of the panels per unit of the game's own interface scale. */
	private float k = 1f;
	private int w;
	private int h;
	private double mx;
	private double my;
	private PlotCoord hover;
	private PlotCoord hoverBefore;
	private long hoverSince;
	private int miniX;
	private int miniY;
	/** How large a hex is on the small map, in the panels' units; not a whole number, the map is one stretched picture. */
	private float miniCell;
	private int miniW;
	private int miniH;

	public RtsScreen() {
		super(Component.literal("Living World"));
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
		// Nothing: the world is the background.
	}

	// ---- drawing ----

	/** How many times the drawing has been moved or clipped and not yet put back: undone in one go when a fault cuts the drawing short. */
	private int poseDepth;
	private int clips;

	private void push(GuiGraphicsExtractor g) {
		g.pose().pushMatrix();
		poseDepth++;
	}

	private void pop(GuiGraphicsExtractor g) {
		g.pose().popMatrix();
		poseDepth--;
	}

	private void clipOn(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
		g.enableScissor(x0, y0, x1, y1);
		clips++;
	}

	private void clipOff(GuiGraphicsExtractor g) {
		g.disableScissor();
		clips--;
	}

	private ItemStack icon(ItemLike item) {
		return icons.computeIfAbsent(BuiltInRegistries.ITEM.getKey(item.asItem()).toString(), id -> new ItemStack(item));
	}

	private ItemStack icon(String id) {
		return icons.computeIfAbsent(id, key -> new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(key))));
	}

	private void item(GuiGraphicsExtractor g, ItemStack stack, float x, float y, float scale) {
		push(g);
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.item(stack, 0, 0);
		pop(g);
	}

	/** The zone that answers at a point: the last one made there that no later panel covers. */
	private Zone zoneAt(double x, double y) {
		for (int i = zones.size() - 1; i >= 0; i--) {
			if (!zones.get(i).has(x, y)) {
				continue;
			}
			boolean covered = false;
			for (int box = zoneLayers.get(i); box < solid.size() && !covered; box++) {
				int[] b = solid.get(box);
				covered = x >= b[0] && y >= b[1] && x < b[0] + b[2] && y < b[1] + b[3];
			}
			if (!covered) {
				return zones.get(i);
			}
		}
		return null;
	}

	private boolean onPanel(double x, double y) {
		for (int[] box : solid) {
			if (x >= box[0] && y >= box[1] && x < box[0] + box[2] && y < box[1] + box[3]) {
				return true;
			}
		}
		return false;
	}

	private void panel(GuiGraphicsExtractor g, int x, int y, int width, int height) {
		solid.add(new int[] { x, y, width, height });
		g.fill(x, y, x + width, y + height, PANEL);
		g.outline(x, y, width, height, BORDER);
	}

	private void line(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int color, int thickness) {
		float dx = x1 - x0;
		float dy = y1 - y0;
		float length = (float) Math.hypot(dx, dy);
		if (length < 0.5f) {
			return;
		}
		push(g);
		g.pose().translate(x0, y0);
		g.pose().rotate((float) Math.atan2(dy, dx));
		g.fill(0, -(thickness / 2), Math.round(length), thickness - thickness / 2, color);
		pop(g);
	}

	private boolean onScreen(float x, float y, int margin) {
		return x > -margin && y > -margin && x < w + margin && y < h + margin;
	}

	private void project(double x, double y, double z) {
		RtsCamera.project(x, y, z, w, h, point);
	}

	private static int mix(int a, int b, float share) {
		int r = (int) (((a >> 16) & 0xFF) * (1 - share) + ((b >> 16) & 0xFF) * share);
		int gr = (int) (((a >> 8) & 0xFF) * (1 - share) + ((b >> 8) & 0xFF) * share);
		int bl = (int) ((a & 0xFF) * (1 - share) + (b & 0xFF) * share);
		return 0xFF000000 | (r << 16) | (gr << 8) | bl;
	}

	/** Development aid: a fault made on purpose in the first picture, to see that the game survives one. */
	private static boolean faultDue = Boolean.getBoolean("livingworld.debug.fail");

	/**
	 * Nobody has seen this view on every machine it will run on. Should drawing it ever go wrong,
	 * that must cost the player the view, not the game: the picture is left as it was found, the
	 * fault is written down, and the player is set on foot, where the game is played as before.
	 */
	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (RtsClient.failing()) {
			return;
		}
		poseDepth = 0;
		clips = 0;
		try {
			draw(g, mouseX, mouseY);
		} catch (RuntimeException e) {
			while (clips > 0) {
				clipOff(g);
			}
			while (poseDepth > 0) {
				pop(g);
			}
			RtsClient.failed("drawing the view from above", e);
		}
	}

	private void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Minecraft mc = this.minecraft;
		int guiScale = Math.max(1, mc.getWindow().getGuiScale());
		// The panels are laid out for a screen some 480 units high, whatever the window's size: on a 1440-line screen everything is three times as large.
		int panelScale = Math.max(1, Math.round(mc.getWindow().getHeight() / 480f));
		k = panelScale / (float) guiScale;
		w = Math.round(width / k);
		h = Math.round(height / k);
		// The pointer as exactly as a click gives it, not rounded to the game's own interface scale: at a button's edge
		// what lights up must be what a click would press.
		mx = mc.mouseHandler.getScaledXPos(mc.getWindow()) / k;
		my = mc.mouseHandler.getScaledYPos(mc.getWindow()) / k;
		zones.clear();
		rightZones.clear();
		solid.clear();
		push(g);
		if (faultDue) {
			faultDue = false;
			throw new IllegalStateException("a fault made on purpose (livingworld.debug.fail)");
		}
		g.pose().scale(k, k);
		WorldState state = RtsClient.state;
		BoardModel model = RtsClient.model;
		if (state == null || model == null) {
			g.fill(0, 0, w, h, 0xA0000000);
			g.centeredText(font, "The game is being set up...", w / 2, h / 2 - 4, WHITE);
			pop(g);
			return;
		}
		// The panels first, so that what the pointer is over is known when the map is drawn.
		List<Runnable> later = new ArrayList<>();
		later.add(() -> topBar(g, state));
		later.add(() -> research(g, state));
		later.add(() -> menuButtons(g));
		later.add(() -> news(g));
		later.add(() -> unitPanel(g, state));
		later.add(() -> turnAndMap(g, state, model));
		later.add(() -> painting(g));
		later.add(() -> choicePanel(g, state));
		later.add(() -> techPanel(g));
		later.add(() -> policyPanel(g, state));
		later.add(() -> mapPanel(g, state, model));
		later.add(() -> menuPanel(g));
		later.add(() -> helpPanel(g));
		// Map layer: outlines, banners, flags.
		double[] ground = RtsCamera.ground(mx, my, w, h);
		hover = model.plotAt((int) Math.floor(ground[0]), (int) Math.floor(ground[1]));
		mapLayer(g, state, model);
		for (Runnable draw : later) {
			draw.run();
		}
		Zone pointed = zoneAt(mx, my);
		boolean overPanel = pointed != null || onPanel(mx, my);
		RtsClient.pointed = overPanel ? null : hover;
		if (pointed != null && pointed.tip != null && !pointed.tip.isEmpty()) {
			tip(g, pointed.tip);
		} else if (!overPanel && hover != null && battleCard(g, state)) {
			// A fight foretold says more than what the land is.
			hoverBefore = null;
		} else if (!overPanel && hover != null) {
			if (!hover.equals(hoverBefore)) {
				hoverBefore = hover;
				hoverSince = RtsClient.ticks;
			}
			if (RtsClient.ticks - hoverSince >= 10) {
				tip(g, String.join("\n", describe(state, hover)));
			}
		}
		pop(g);
	}

	/** Draws a line of words and small pictures; returns where it ends. */
	private int rich(GuiGraphicsExtractor g, List<Rich.Piece> pieces, int x, int y, boolean shadow) {
		for (Rich.Piece piece : pieces) {
			if (piece.picture() != null) {
				item(g, icon(piece.picture()), x + 1, y, 0.5f);
				x += Rich.PICTURE;
			} else {
				g.text(font, piece.text(), x, y, piece.color(), shadow);
				x += font.width(piece.text());
			}
		}
		return x;
	}

	/** A line of a list: the measures as their pictures alone, after their numbers. */
	private List<Rich.Piece> tight(String line, int color, boolean tinted) {
		return Rich.pieces(Rich.words(line, false), false, color, tinted);
	}

	/**
	 * What a fight would come to, by the pointer, while it rests on somebody the selected unit could
	 * attack (or a city that takes aim could fire on): who against whom, how strong, what each is
	 * expected to lose and has left, and what weighs on either side. Returns whether it was shown.
	 */
	private boolean battleCard(GuiGraphicsExtractor g, WorldState state) {
		RtsView view = RtsClient.view;
		if (hover == null) {
			return false;
		}
		int index = hover.y() * state.session().grid().width() + hover.x();
		if (RtsClient.aiming >= 0) {
			if (!aimed(RtsClient.aiming).contains(hover)) {
				return false;
			}
			RtsClient.foretell("cityBattle", RtsClient.aiming, index);
		} else if (view.selected != null && view.attack.contains(hover)) {
			RtsClient.foretell("battle", view.selected.id(), index);
		} else {
			return false;
		}
		if (!RtsClient.battleFor.equals(RtsClient.battleAsked)) {
			return false;
		}
		Records.Option attacker = null;
		Records.Option defender = null;
		List<String> ours = new ArrayList<>();
		List<String> theirs = new ArrayList<>();
		for (Records.Option row : RtsClient.battle) {
			switch (row.group()) {
				case 40 -> attacker = row;
				case 41 -> defender = row;
				case 42 -> ours.add((row.id() >= 0 ? "+" : "") + row.id() + "% " + row.name());
				case 43 -> theirs.add((row.id() >= 0 ? "+" : "") + row.id() + "% " + row.name());
				default -> {
				}
			}
		}
		if (attacker == null || defender == null) {
			return false;
		}
		String[] mine = attacker.type().split("\\|");
		String[] other = defender.type().split("\\|");
		int width = 216;
		for (String line : ours) {
			width = Math.max(width, font.width(line) + 16);
		}
		for (String line : theirs) {
			width = Math.max(width, font.width(line) + 16);
		}
		int height = 74 + (ours.size() + theirs.size()) * 9 + (ours.isEmpty() ? 0 : 11) + (theirs.isEmpty() ? 0 : 11);
		int x = (int) Math.min(mx + 14, w - width - 4);
		int y = (int) Math.max(18, Math.min(my + 14, h - height - 4));
		g.fill(x, y, x + width, y + height, 0xF4101018);
		g.outline(x, y, width, height, 0xFFC04030);
		g.fill(x + 1, y + 1, x + width - 1, y + 13, 0xFF4A1C16);
		int afterAttack = Math.max(0, attacker.id() - attacker.turns());
		int afterDefence = Math.max(0, defender.id() - defender.turns());
		String verdict = afterDefence == 0 && afterAttack > 0 ? "THEY FALL" : afterAttack == 0 && afterDefence > 0 ? "YOU FALL" : defender.turns() >= attacker.turns() * 2 ? "A CLEAR WIN"
			: defender.turns() > attacker.turns() ? "YOU HAVE THE EDGE" : defender.turns() == attacker.turns() ? "EVEN" : attacker.turns() >= defender.turns() * 2 ? "A CLEAR LOSS" : "THEY HAVE THE EDGE";
		g.text(font, "ATTACK", x + 5, y + 3, GOLD, true);
		g.text(font, verdict, x + width - 5 - font.width(verdict), y + 3, defender.turns() >= attacker.turns() ? GREEN : RED, true);
		int half = width / 2;
		side(g, x + 5, y + 17, half - 8, attacker, mine, afterAttack, 0xFF7FD4FF);
		side(g, x + half + 3, y + 17, half - 8, defender, other, afterDefence, 0xFFFF9080);
		g.fill(x + half - 1, y + 16, x + half, y + 68, 0x50FFFFFF);
		int ty = y + 72;
		if (!ours.isEmpty()) {
			g.text(font, "For and against you", x + 5, ty, DIM, false);
			ty += 10;
			for (String line : ours) {
				g.text(font, line, x + 9, ty, line.startsWith("-") ? 0xFFFF9080 : 0xFF9FE08A, false);
				ty += 9;
			}
			ty += 1;
		}
		if (!theirs.isEmpty()) {
			g.text(font, "For and against them", x + 5, ty, DIM, false);
			ty += 10;
			for (String line : theirs) {
				g.text(font, line, x + 9, ty, line.startsWith("-") ? 0xFF9FE08A : 0xFFFF9080, false);
				ty += 9;
			}
		}
		return true;
	}

	/** One side of a fight foretold: its name and strength, its health as a bar with what it would lose marked, and that in numbers. */
	private void side(GuiGraphicsExtractor g, int x, int y, int width, Records.Option who, String[] numbers, int after, int tint) {
		String name = who.name();
		while (font.width(name) > width && name.length() > 3) {
			name = name.substring(0, name.length() - 1);
		}
		g.text(font, name, x, y, tint, true);
		rich(g, tight((numbers.length > 0 ? numbers[0] : "?") + " Strength", WHITE, true), x, y + 11, false);
		int max = Math.max(100, who.id());
		g.fill(x, y + 23, x + width, y + 29, 0xFF202028);
		int now = Math.round(width * who.id() / (float) max);
		int left = Math.round(width * after / (float) max);
		g.fill(x, y + 23, x + now, y + 29, 0xFFC84030);
		g.fill(x, y + 23, x + left, y + 29, after * 3 < max ? 0xFFE0A030 : 0xFF60E070);
		g.text(font, who.id() + " \u2192 " + after, x, y + 32, WHITE, false);
		String loss = who.turns() == 0 ? "takes no harm" : "loses " + (numbers.length > 2 && !numbers[1].equals(numbers[2]) ? numbers[1] + "-" + numbers[2] : Integer.toString(who.turns()));
		g.text(font, loss, x, y + 42, who.turns() == 0 ? DIM : 0xFFFFB0A0, false);
	}

	/** The hexes a city of the player's can fire on now (see the engine's "bombard"). */
	private List<PlotCoord> aimed(int city) {
		List<PlotCoord> out = new ArrayList<>();
		for (Records.Option row : RtsClient.view.extra) {
			if (row.group() == 31 && row.type().equals("bombard") && row.id() == city) {
				for (String at : row.name().split(";")) {
					String[] xy = at.split(",");
					if (xy.length == 2) {
						out.add(new PlotCoord(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])));
					}
				}
			}
		}
		return out;
	}

	/**
	 * The card by the pointer: its heading, a rule under it, and then what there is to say, a
	 * thing a line, every measure as its picture with the word beside it.
	 */
	private void tip(GuiGraphicsExtractor g, String text) {
		List<List<Rich.Piece>> lines = new ArrayList<>();
		int heading = 0;
		String[] raw = text.split("\n");
		for (int i = 0; i < raw.length; i++) {
			if (raw[i].isEmpty()) {
				continue;
			}
			int color = i == 0 ? WHITE : raw[i].endsWith(":") ? 0xFFE8C878 : 0xFFC8C8D4;
			for (List<String> line : Rich.flow(font, Rich.words(raw[i], false), 270, 99, true)) {
				lines.add(Rich.pieces(line, true, color, true));
			}
			if (i == 0) {
				heading = lines.size();
			}
		}
		if (lines.isEmpty()) {
			return;
		}
		int width = 0;
		for (List<Rich.Piece> line : lines) {
			width = Math.max(width, Rich.width(font, line));
		}
		boolean ruled = lines.size() > heading;
		int height = lines.size() * 10 + 4 + (ruled ? 4 : 0);
		int x = (int) Math.min(mx + 12, w - width - 8);
		int y = (int) Math.max(2, Math.min(my + 12, h - height - 2));
		g.fill(x - 3, y - 2, x + width + 3, y + height - 2, 0xF0100818);
		g.outline(x - 3, y - 2, width + 6, height, 0xFF5028A0);
		int ly = y;
		for (int i = 0; i < lines.size(); i++) {
			rich(g, lines.get(i), x, ly, i < heading);
			ly += 10;
			if (ruled && i == heading - 1) {
				g.fill(x - 1, ly, x + width + 1, ly + 1, 0xFF3A2470);
				ly += 4;
			}
		}
	}

	/** What Civilization shows when the pointer rests on a hex. */
	private List<String> describe(WorldState state, PlotCoord plot) {
		List<String> out = new ArrayList<>();
		PlotState p = state.plot(plot);
		if (!p.revealed()) {
			out.add("Unexplored");
			return out;
		}
		Legend legend = state.legend();
		String land = legend.name(Legend.Kind.TERRAIN, p.terrain()) + (p.elevation() == PlotState.HILLS ? ", hills" : p.elevation() == PlotState.MOUNTAIN ? ", mountain" : "")
			+ (p.feature() >= 0 ? ", " + legend.name(Legend.Kind.FEATURE, p.feature()).toLowerCase(Locale.ROOT) : "");
		out.add(land);
		CityState city = state.cityAt(plot);
		if (city != null) {
			CivInfo owner = state.civ(city.key().owner());
			out.add("City of " + city.name() + (owner == null ? "" : " (" + owner.name() + ")"));
		} else if (p.owner() >= 0 && state.civ(p.owner()) != null) {
			out.add("Land of " + state.civ(p.owner()).name());
		}
		BoardModel.Site wonder = RtsClient.model == null ? null : RtsClient.model.siteAt(plot);
		if (wonder != null && wonder.certain()) {
			CityState builder = state.cities().get(wonder.city());
			out.add("Wonder: " + nl.jordythomassen.livingworld.gameplay.TechGate.words(wonder.wonder()) + (builder == null ? "" : "   of " + builder.name()));
		}
		if (p.resource() >= 0) {
			out.add("Resource: " + legend.name(Legend.Kind.RESOURCE, p.resource()));
		}
		if (p.improvement() >= 0) {
			out.add(legend.name(Legend.Kind.IMPROVEMENT, p.improvement()) + (p.pillaged() ? " (pillaged)" : ""));
		}
		for (UnitState unit : state.units().values()) {
			if (unit.at().equals(plot)) {
				CivInfo owner = state.civ(unit.key().owner());
				out.add(unit.name() + (owner == null ? "" : " of " + owner.name()) + "  " + unit.hp() + "/" + unit.maxHp());
			}
		}
		return out;
	}

	// ---- the map layer ----

	/** The six corners of a hex on the screen, in {@code corners}; false when the hex is nowhere near the screen. */
	private boolean corners(BoardModel model, PlotCoord plot, float shrink) {
		double cx = model.layout().x(model.state().session().grid().center(plot));
		double cz = model.layout().z(model.state().session().grid().center(plot));
		project(cx, RtsCamera.GROUND_Y, cz);
		if (!onScreen(point[0], point[1], (int) (model.layout().hexWidth() / RtsCamera.shown() * h))) {
			return false;
		}
		double radius = model.layout().hexWidth() / Math.sqrt(3.0) * shrink;
		for (int corner = 0; corner < 6; corner++) {
			double angle = Math.toRadians(60 * corner + 30);
			project(cx + radius * Math.cos(angle), RtsCamera.GROUND_Y, cz + radius * Math.sin(angle));
			cornerX[corner] = point[0];
			cornerY[corner] = point[1];
		}
		return true;
	}

	private void hexOutline(GuiGraphicsExtractor g, BoardModel model, PlotCoord plot, int color, int thickness, float shrink) {
		if (!corners(model, plot, shrink)) {
			return;
		}
		for (int corner = 0; corner < 6; corner++) {
			int next = (corner + 1) % 6;
			line(g, cornerX[corner], cornerY[corner], cornerX[next], cornerY[next], color, thickness);
		}
	}

	/** The hexes that can be on the screen: a range of columns and rows, a little wider than the screen. */
	private int[] hexesInView(WorldState state, BoardModel model) {
		double minU = Double.MAX_VALUE;
		double maxU = -Double.MAX_VALUE;
		double minV = Double.MAX_VALUE;
		double maxV = -Double.MAX_VALUE;
		double[][] screen = { { 0, 0 }, { w, 0 }, { w, h }, { 0, h } };
		for (double[] corner : screen) {
			double[] ground = RtsCamera.ground(corner[0], corner[1], w, h);
			GridPos grid = model.layout().toGrid(ground[0], ground[1]);
			minU = Math.min(minU, grid.u());
			maxU = Math.max(maxU, grid.u());
			minV = Math.min(minV, grid.v());
			maxV = Math.max(maxV, grid.v());
		}
		int cols = state.session().grid().width();
		int rows = state.session().grid().height();
		return new int[] { Math.max(0, (int) Math.floor(minU) - 2), Math.min(cols - 1, (int) Math.ceil(maxU) + 2), Math.max(0, (int) Math.floor(minV) - 2),
			Math.min(rows - 1, (int) Math.ceil(maxV) + 2) };
	}

	/**
	 * The hex grid over the land one has seen, drawn thin: Minecraft's land does not show where
	 * one hex ends and the next begins. Each hex draws half its outline and leaves the other half
	 * to its neighbours, except where the neighbour is still under cloud.
	 */
	private void grid(GuiGraphicsExtractor g, WorldState state, BoardModel model, int[] range) {
		float zoom = RtsCamera.shown();
		if (!showGrid || zoom > 125f) {
			return;
		}
		int color = ((int) (zoom < 85f ? 0x58 : 0x58 * (135f - zoom) / 50f) << 24) | 0xFFFFFF;
		double step = model.layout().hexWidth();
		for (int row = range[2]; row <= range[3]; row++) {
			for (int col = range[0]; col <= range[1]; col++) {
				PlotCoord plot = new PlotCoord(col, row);
				if (!state.plot(plot).revealed() || !corners(model, plot, 1f)) {
					continue;
				}
				double cx = model.layout().x(state.session().grid().center(plot));
				double cz = model.layout().z(state.session().grid().center(plot));
				for (int edge = 0; edge < 6; edge++) {
					if (edge >= 3) {
						// The far half: only where nobody else draws it.
						double angle = Math.toRadians(60 * edge + 60);
						PlotCoord other = model.plotAt((int) Math.floor(cx + step * Math.cos(angle)), (int) Math.floor(cz + step * Math.sin(angle)));
						if (other != null && state.plot(other).revealed()) {
							continue;
						}
					}
					int next = (edge + 1) % 6;
					line(g, cornerX[edge], cornerY[edge], cornerX[next], cornerY[next], color, 1);
				}
			}
		}
	}

	/** What the land holds, as a small picture on its hex. */
	private void resources(GuiGraphicsExtractor g, WorldState state, BoardModel model, int[] range) {
		float zoom = RtsCamera.shown();
		if (zoom > 110f) {
			return;
		}
		float size = zoom < 50f ? 1f : 0.75f;
		for (int row = range[2]; row <= range[3]; row++) {
			for (int col = range[0]; col <= range[1]; col++) {
				PlotCoord plot = new PlotCoord(col, row);
				PlotState p = state.plot(plot);
				if (!p.revealed() || p.resource() < 0 || p.city()) {
					continue;
				}
				double cx = model.layout().x(state.session().grid().center(plot));
				double cz = model.layout().z(state.session().grid().center(plot));
				project(cx, RtsCamera.GROUND_Y, cz);
				// Low in the hex, out of the way of whoever stands in the middle of it.
				float x = point[0] - 8 * size;
				float y = point[1] + (float) (model.layout().hexWidth() * 0.2 / zoom * h * 0.5) - 8 * size;
				if (!onScreen(x, y, 16)) {
					continue;
				}
				g.fill(Math.round(x) - 1, Math.round(y) - 1, Math.round(x + 16 * size) + 1, Math.round(y + 16 * size) + 1, 0x70000000);
				item(g, resourceIcon(state.legend().type(Legend.Kind.RESOURCE, p.resource())), Math.round(x), Math.round(y), size);
			}
		}
	}

	private ItemStack resourceIcon(String type) {
		String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
		String id = t.contains("IRON") ? "iron_ingot" : t.contains("HORSE") ? "saddle" : t.contains("WHEAT") ? "wheat" : t.contains("CATTLE") || t.contains("COW") ? "beef"
			: t.contains("SHEEP") ? "white_wool" : t.contains("DEER") ? "leather" : t.contains("FISH") ? "cod" : t.contains("WHALE") ? "prismarine_shard"
			: t.contains("PEARL") ? "nautilus_shell" : t.contains("CRAB") ? "tropical_fish" : t.contains("GOLD") ? "gold_ingot" : t.contains("SILVER") ? "iron_nugget"
			: t.contains("GEM") ? "diamond" : t.contains("MARBLE") ? "quartz" : t.contains("IVORY") ? "bone" : t.contains("FUR") ? "rabbit_hide" : t.contains("DYE") ? "red_dye"
			: t.contains("SPICE") ? "blaze_powder" : t.contains("SILK") ? "string" : t.contains("SUGAR") ? "sugar" : t.contains("COTTON") ? "white_carpet"
			: t.contains("WINE") ? "sweet_berries" : t.contains("INCENSE") ? "glowstone_dust" : t.contains("COAL") ? "coal" : t.contains("OIL") ? "black_dye"
			: t.contains("ALUMIN") ? "light_gray_dye" : t.contains("URANIUM") ? "slime_ball" : t.contains("STONE") ? "cobblestone" : t.contains("BANANA") ? "melon_slice"
			: t.contains("SALT") ? "bone_meal" : t.contains("TRUFFLE") ? "brown_mushroom" : t.contains("CITRUS") ? "orange_dye" : t.contains("COPPER") ? "copper_ingot"
			: t.contains("COCOA") ? "cocoa_beans" : t.contains("JEWEL") ? "emerald" : t.contains("PORCELAIN") ? "flower_pot" : "hay_block";
		return icon(id);
	}

	private void mapLayer(GuiGraphicsExtractor g, WorldState state, BoardModel model) {
		RtsView view = RtsClient.view;
		int me = state.session().viewer();
		int[] range = hexesInView(state, model);
		TerritoryTexture.draw(g, state, model, view.several, w, h);
		grid(g, state, model, range);
		resources(g, state, model, range);
		yields(g, state, model, range);
		workedLayer(g, state, model);
		UnitState selected = view.selected == null ? null : state.units().get(view.selected);
		if (selected != null) {
			for (PlotCoord plot : view.reach) {
				hexOutline(g, model, plot, 0xD050F070, 1, 0.82f);
			}
			for (PlotCoord plot : view.attack) {
				hexOutline(g, model, plot, 0xF0FF4030, 2, 0.86f);
			}
			hexOutline(g, model, selected.at(), 0xFF60E0FF, 2, 0.94f);
		}
		if (RtsClient.aiming >= 0) {
			List<PlotCoord> targets = aimed(RtsClient.aiming);
			if (targets.isEmpty()) {
				RtsClient.aiming = -1;
			}
			for (PlotCoord plot : targets) {
				hexOutline(g, model, plot, (RtsClient.ticks / 6) % 2 == 0 ? 0xFFFF8020 : 0xFFFFD040, 2, 0.86f);
			}
		}
		if (hover != null && my > 16) {
			hexOutline(g, model, hover, 0xD0FFFFFF, 1, 0.97f);
		}
		// A flag over every unit.
		for (UnitState unit : state.units().values()) {
			double[] spot = RtsClient.spots.get(unit.key());
			if (spot == null) {
				continue;
			}
			project(spot[0], spot[1] + 1.8 * Looks.UNIT_SCALE + 1.0, spot[2]);
			if (!onScreen(point[0], point[1], 20)) {
				continue;
			}
			int x = Math.round(point[0]) - 7;
			int y = Math.round(point[1]) - 14;
			CivInfo owner = state.civ(unit.key().owner());
			int color = owner == null ? 0xFF808080 : 0xFF000000 | owner.color();
			boolean mine = unit.key().owner() == me;
			boolean isSelected = unit.key().equals(view.selected);
			boolean enemy = owner != null && (owner.atWarWithViewer() || owner.barbarian());
			int edge = isSelected ? WHITE : mine && unit.waiting() && (RtsClient.ticks / 8) % 2 == 0 ? GOLD : enemy ? RED : 0xFF101010;
			g.fill(x - 1, y - 1, x + 15, y + 15, edge);
			g.fill(x, y, x + 14, y + 14, color);
			item(g, icon(Looks.weapon(unit)), x + 1, y + 1, 0.75f);
			if (unit.hp() < unit.maxHp()) {
				int bar = Math.max(1, 14 * unit.hp() / Math.max(1, unit.maxHp()));
				g.fill(x, y + 15, x + 14, y + 17, 0xFF101010);
				g.fill(x, y + 15, x + bar, y + 17, unit.hp() * 3 < unit.maxHp() ? RED : unit.hp() * 3 < unit.maxHp() * 2 ? GOLD : GREEN);
			}
			// A gold pip for every promotion it has; a blinking mark when it has earned another.
			for (int pip = 0; pip < Math.min(5, unit.level()); pip++) {
				g.fill(x + pip * 3, y - 4, x + pip * 3 + 2, y - 2, GOLD);
			}
			if (mine && unit.strength() > 0 && unit.experience() >= (unit.level() + 1) * 10 && (RtsClient.ticks / 8) % 2 == 0) {
				g.text(font, "\u25B2", x + 15, y - 5, GOLD, true);
			}
			if (mine) {
				EntityKey key = unit.key();
				zones.add(new Zone(x - 1, y - 1, 16, 16, null, () -> select(key)));
			}
		}
		// A name over every wonder of the world that stands on the land.
		for (BoardModel.Site site : model.wonderSites()) {
			if (!site.certain() || !state.plot(site.plot()).revealed()) {
				continue;
			}
			project(model.centerX(site.plot()) + 0.5, BoardModel.LAND + 30.0, model.centerZ(site.plot()) + 0.5);
			if (!onScreen(point[0], point[1], 60)) {
				continue;
			}
			String name = nl.jordythomassen.livingworld.gameplay.TechGate.words(site.wonder());
			int width = 12 + font.width(name) + 4;
			int x = Math.round(point[0]) - width / 2;
			int y = Math.round(point[1]) - 5;
			g.fill(x - 1, y - 1, x + width + 1, y + 11, 0xFF101010);
			g.fill(x, y, x + width, y + 10, 0xFF3A300E);
			item(g, icon(Items.NETHER_STAR), x + 1, y + 1, 0.5f);
			g.text(font, name, x + 11, y + 1, GOLD, false);
			CityState builder = state.cities().get(site.city());
			zones.add(new Zone(x - 1, y - 1, width + 2, 12, "Wonder: " + name + (builder == null ? "" : "\nBuilt by " + builder.name()) + "\nIt stands on a hex of its own beside its city", () -> { }));
		}
		// A banner over every city.
		for (CityState city : state.cities().values()) {
			double cx = model.centerX(city.at()) + 0.5;
			double cz = model.centerZ(city.at()) + 0.5;
			project(cx, BoardModel.LAND + 24.0, cz);
			if (!onScreen(point[0], point[1], 80)) {
				continue;
			}
			CivInfo owner = state.civ(city.key().owner());
			int color = owner == null ? 0xFF808080 : 0xFF000000 | owner.color();
			boolean mine = city.key().owner() == me;
			String name = (city.capital() ? "★ " : "") + city.name().toUpperCase(Locale.ROOT);
			String work = !mine || city.productionName().isEmpty() ? "" : city.productionTurns() > 0 ? city.productionName() + " " + city.productionTurns() : city.productionName();
			int nameWidth = font.width(name);
			int workWidth = work.isEmpty() ? 0 : font.width(work) + 8;
			int width = 16 + nameWidth + 8 + workWidth;
			int x = Math.round(point[0]) - width / 2;
			int y = Math.round(point[1]) - 7;
			boolean enemy = owner != null && owner.atWarWithViewer();
			g.fill(x - 1, y - 1, x + width + 1, y + 14, enemy ? RED : 0xFF101010);
			g.fill(x, y, x + width, y + 13, mix(color, 0xFF000000, 0.55f));
			g.fill(x, y, x + 15, y + 13, color);
			g.centeredText(font, Integer.toString(city.population()), x + 8, y + 3, WHITE);
			g.text(font, name, x + 19, y + 3, WHITE, true);
			if (!work.isEmpty()) {
				g.fill(x + 16 + nameWidth + 6, y, x + width, y + 13, 0xC0000000);
				g.text(font, work, x + 16 + nameWidth + 10, y + 3, GOLD, false);
			}
			if (mine && city.productionName().isEmpty()) {
				g.text(font, "!", x + width + 3, y + 3, (RtsClient.ticks / 8) % 2 == 0 ? GOLD : WHITE, true);
			}
			EntityKey key = city.key();
			if (mine && !aimed(key.id()).isEmpty()) {
				// An enemy is within the city's reach: it can fire once a turn.
				int fx = x + width + (city.productionName().isEmpty() ? 12 : 3);
				boolean aiming = RtsClient.aiming == key.id();
				g.fill(fx, y - 1, fx + 15, y + 14, aiming ? WHITE : (RtsClient.ticks / 8) % 2 == 0 ? 0xFFFF8020 : 0xFF101010);
				g.fill(fx + 1, y, fx + 14, y + 13, 0xFF5A1810);
				item(g, icon(Items.FIRE_CHARGE), fx + 1, y, 0.8f);
				zones.add(new Zone(fx, y - 1, 15, 15, aiming ? "Taking aim: click one of the marked hexes to fire, or here to hold fire"
					: city.name() + " can fire on an enemy in reach\nClick, then click one of the marked hexes", () -> RtsClient.aiming = aiming ? -1 : key.id()));
			}
			zones.add(new Zone(x - 1, y - 1, width + 2, 15, mine ? "What " + city.name() + " builds" : null, () -> {
				if (mine) {
					openCity(city);
				} else {
					RtsClient.act("open", key.owner(), key.id(), 0, 0, "city");
				}
			}));
		}
		// Where the player's own figure stands, and so where F12 sets them down when nothing else is pointed at.
		if (view.me != null) {
			double cx = model.centerX(view.me) + 0.5;
			double cz = model.centerZ(view.me) + 3.5;
			project(cx, BoardModel.LAND + 8.0, cz);
			if (onScreen(point[0], point[1], 30)) {
				int px = Math.round(point[0]);
				int py = Math.round(point[1]) - ((RtsClient.ticks / 10) % 2 == 0 ? 1 : 0);
				int label = font.width("YOU") + 8;
				g.fill(px - label / 2 - 1, py - 25, px + label / 2 + 1, py - 12, 0xFF1A1206);
				g.fill(px - label / 2, py - 24, px + label / 2, py - 13, GOLD);
				g.centeredText(font, "YOU", px, py - 22, 0xFF1A1206);
				for (int i = 0; i < 5; i++) {
					g.fill(px - (5 - i), py - 12 + i, px + (5 - i), py - 11 + i, i == 0 ? 0xFF1A1206 : GOLD);
				}
				PlotCoord stands = view.me;
				zones.add(new Zone(px - label / 2 - 1, py - 25, label + 2, 20, "Your own figure stands here.\nF12 sets you down where you point, on your own land; with nothing pointed at, here.",
					() -> RtsCamera.lookAt(model.centerX(stands) + 0.5, model.centerZ(stands) + 0.5)));
			}
		}
	}

	// ---- the panels ----

	private int stat(GuiGraphicsExtractor g, ItemStack stack, String text, int x, int color, String tip) {
		return stat(g, stack, text, x, color, tip, () -> { });
	}

	private int stat(GuiGraphicsExtractor g, ItemStack stack, String text, int x, int color, String tip, Runnable click) {
		item(g, stack, x, 1, 0.8f);
		g.text(font, text, x + 15, 4, color, true);
		int width = 15 + font.width(text) + 8;
		zones.add(new Zone(x, 0, width, 15, tip, click));
		return x + width;
	}

	private static String signed(int value) {
		return value >= 0 ? "+" + value : Integer.toString(value);
	}

	private void topBar(GuiGraphicsExtractor g, WorldState state) {
		solid.add(new int[] { 0, 0, w, 16 });
		g.fill(0, 0, w, 15, 0xF00C0C12);
		g.fill(0, 15, w, 16, BORDER);
		Empire empire = state.empire();
		CivInfo civ = state.civ(state.session().viewer());
		int x = 4;
		if (empire != null) {
			x = stat(g, icon(Items.EXPERIENCE_BOTTLE), "+" + empire.science(), x, 0xFF7FD4FF, "Science a turn\nClick: the tree of technologies", this::openTechTree);
			x = stat(g, icon(Items.GOLD_INGOT), empire.gold() + " (" + signed(empire.goldPerTurn()) + ")", x, GOLD, "Gold in the treasury, and what a turn brings");
			x = stat(g, icon(Items.SUNFLOWER), Integer.toString(empire.happiness()), x, empire.happiness() < 0 ? RED : 0xFFFFE860,
				"Happiness of your people" + (empire.happiness() < 0 ? ": unhappy, your cities grow slower and your soldiers fight worse" : "") + "\nClick: where it comes from",
				() -> RtsClient.act("open", 0, 0, 0, 0, "happiness"));
			Records.Option golden = RtsClient.extra("golden");
			if (golden != null) {
				int left = 0;
				try {
					left = Integer.parseInt(golden.name());
				} catch (NumberFormatException e) {
					// Nothing to count down.
				}
				x = stat(g, icon(Items.CLOCK), left > 0 ? left + " turns" : golden.id() + "/" + golden.turns(), x, left > 0 ? 0xFFFFC040 : 0xFFE8C878,
					left > 0 ? "A golden age: " + left + " more turns of extra production, gold and culture" : "Spare happiness saved up for a golden age, and what one takes",
					() -> RtsClient.act("open", 0, 0, 0, 0, "happiness"));
			}
			x = stat(g, icon(Items.AMETHYST_SHARD), empire.culture() + "/" + empire.policyCost() + " (" + signed(empire.culturePerTurn()) + ")", x, 0xFFE28BFF,
				"Culture towards the next social policy\nClick: the social policies", RtsClient::openPolicies);
			Records.Option faith = RtsClient.extra("faith");
			if (faith != null) {
				Records.Option goal = RtsClient.extra("faithGoal");
				String belief = faith.name().isEmpty() ? "Your people pray to no gods yet" : "Your people follow " + faith.name();
				String next = goal == null ? "" : goal.name().equals("pantheon") ? "\n" + goal.id() + " faith founds a pantheon" : "\nA Great Prophet is born at about " + goal.id() + " faith";
				x = stat(g, icon(Items.FEATHER), faith.id() + (goal == null ? "" : "/" + goal.id()) + " (" + signed(faith.turns()) + ")", x, 0xFFF4F0DC,
					"Faith, and what a turn brings\n" + belief + next + "\nClick: faith and religion", () -> RtsClient.act("open", 0, 0, 0, 0, "religion"));
			}
			// What the land gives that armies and buildings need, and the luxuries that keep people happy.
			List<String> luxuries = new ArrayList<>();
			for (Records.Option row : RtsClient.view.extra) {
				if (row.group() != 31 || !row.type().equals("resource")) {
					continue;
				}
				if (row.turns() == 1) {
					if (x < w - 330) {
						x = stat(g, resourceIcon(row.name()), Integer.toString(row.id()), x, row.id() < 0 ? RED : 0xFFD8D8E0,
							row.name() + ": " + row.id() + " to spare" + (row.id() < 0 ? "\nMore is in use than you have: what needs it fights at a loss" : ""));
					}
				} else {
					luxuries.add(row.name() + (row.id() > 1 ? " \u00D7" + row.id() : ""));
				}
			}
			if (!luxuries.isEmpty() && x < w - 300) {
				x = stat(g, icon(Items.DIAMOND), Integer.toString(luxuries.size()), x, 0xFFB8E8FF, "Luxuries (each kind makes your people happier; spares can be traded)\n" + String.join(", ", luxuries));
			}
		}
		String right = "Turn " + state.turn() + (empire == null ? "" : "   " + empire.yearText()) + (civ == null ? "" : "   " + civ.name());
		g.text(font, right, w - font.width(right) - 6, 4, WHITE, true);
		Records.Option score = RtsClient.extra("score");
		zones.add(new Zone(w - font.width(right) - 8, 0, font.width(right) + 8, 15, (score == null ? "" : "Your score: " + score.id() + "\n") + "Click: the ways to win, and how you compare",
			() -> RtsClient.act("open", 0, 0, 0, 0, "victory")));
	}

	private void research(GuiGraphicsExtractor g, WorldState state) {
		Empire empire = state.empire();
		if (empire == null) {
			return;
		}
		boolean none = empire.researchName().isEmpty();
		int x = 2;
		int y = 18;
		String name = none ? "Choose research" : empire.researchName();
		int width = Math.max(110, 30 + font.width(name) + 8);
		panel(g, x, y, width, 26);
		item(g, icon(Items.ENCHANTING_TABLE), x + 5, y + 5, 1f);
		g.text(font, "RESEARCH", x + 26, y + 4, DIM, false);
		g.text(font, none ? name : name + "  (" + empire.researchTurns() + ")", x + 26, y + 14, none && (RtsClient.ticks / 8) % 2 == 0 ? GOLD : WHITE, true);
		zones.add(new Zone(x, y, width, 26, "Choose what your scholars work on", () -> RtsClient.ask("research", 0)));
	}

	private int button(GuiGraphicsExtractor g, int x, int y, ItemStack stack, String tip, Runnable click) {
		boolean over = mx >= x && mx < x + 20 && my >= y && my < y + 20;
		g.fill(x, y, x + 20, y + 20, over ? PANEL_LIGHT : PANEL);
		g.outline(x, y, 20, 20, over ? WHITE : BORDER);
		item(g, stack, x + 2, y + 2, 1f);
		zones.add(new Zone(x, y, 20, 20, tip, click));
		return x - 22;
	}

	private void menuButtons(GuiGraphicsExtractor g) {
		int x = w - 22;
		int y = 18;
		x = button(g, x, y, icon(Items.KNOWLEDGE_BOOK), "How to play: the mouse and the keys (F1)", () -> RtsClient.help(!RtsClient.helpOpen));
		x = button(g, x, y, icon(Items.COMPARATOR), "Settings, and a new game", () -> RtsClient.act("open", 0, 0, 0, 0, "settings"));
		x = button(g, x, y, icon(Items.LEATHER_BOOTS), "Walk the land yourself: you are set down where your figure stands.\nF12 with the pointer on your own land sets you down there; F12 again brings you back up",
			RtsClient::walk);
		x = button(g, x, y, icon(Items.FILLED_MAP), "Your cities and units", () -> RtsClient.act("open", 0, 0, 0, 0, "realm"));
		x = button(g, x, y, icon(Items.GLOBE_BANNER_PATTERN), "Other civilizations", () -> RtsClient.act("open", 0, 0, 0, 0, "others"));
		x = button(g, x, y, icon(Items.PLAYER_HEAD), "Who plays: take a civilization, look on, or begin a new game (L)", () -> RtsClient.act("lobby", 0, 0, 0, 0, "open"));
		x = button(g, x, y, icon(Items.NETHER_STAR), "The ways to win, and how you compare (V)", () -> RtsClient.act("open", 0, 0, 0, 0, "victory"));
		if (RtsClient.extra("faith") != null) {
			x = button(g, x, y, icon(Items.FEATHER), "Faith and religion (R)", () -> RtsClient.act("open", 0, 0, 0, 0, "religion"));
		}
		x = button(g, x, y, icon(Items.WRITABLE_BOOK), "Social policies (P)", RtsClient::openPolicies);
		x = button(g, x, y, icon(Items.ENCHANTING_TABLE), "The tree of technologies (T)", this::openTechTree);
		x = button(g, x, y, icon(Items.MAP), "The known world on one map (M)", () -> RtsClient.mapOpen = !RtsClient.mapOpen);
		x = button(g, x, y, icon(Items.WHEAT), RtsClient.showYields ? "Hide what each hex yields (Y)" : "Show what each hex yields (Y)", this::toggleYields);
		Records.Option spies = RtsClient.extra("spies");
		if (spies != null) {
			int at = x;
			x = button(g, x, y, icon(Items.ENDER_EYE), "Your spies" + (spies.turns() > 0 ? ": " + spies.turns() + " wait for orders" : ""), () -> RtsClient.act("open", 0, 0, 0, 0, "spies"));
			if (spies.turns() > 0 && (RtsClient.ticks / 8) % 2 == 0) {
				g.outline(at, y, 20, 20, GOLD);
			}
		}
		if (RtsClient.extra("freeGreat") != null) {
			int at = x;
			x = button(g, x, y, icon(Items.GOLDEN_APPLE), "A Great Person is yours for the choosing", () -> RtsClient.ask("greatPeople", 0));
			if ((RtsClient.ticks / 8) % 2 == 0) {
				g.outline(at, y, 20, 20, GOLD);
			}
		}
		if (RtsClient.view.proposals > 0) {
			int at = x;
			button(g, x, y, icon(Items.PAPER), RtsClient.view.proposals == 1 ? "Another civilization has put something to you" : RtsClient.view.proposals + " proposals wait for your answer",
				() -> RtsClient.act("open", 0, 0, 0, 0, "proposals"));
			g.text(font, Integer.toString(RtsClient.view.proposals), at + 13, y + 11, GOLD, true);
		}
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

	private static String noticeIcon(String kind) {
		return switch (kind) {
			case "build" -> "copper_pickaxe";
			case "wonder" -> "nether_star";
			case "city" -> "bell";
			case "war" -> "iron_sword";
			case "peace" -> "white_banner";
			case "talk" -> "writable_book";
			case "trade" -> "emerald";
			case "unit" -> "iron_helmet";
			case "faith" -> "feather";
			case "spy" -> "ender_eye";
			case "tech" -> "experience_bottle";
			case "policy" -> "amethyst_shard";
			case "era" -> "clock";
			default -> "paper";
		};
	}

	/** What a click on a piece of news leads to: the place it is about, and the screen that deals with it. Then it is put away. */
	private void openNotice(RtsClient.Notice notice) {
		WorldState state = RtsClient.state;
		BoardModel model = RtsClient.model;
		if (notice.place() != null && model != null) {
			RtsCamera.lookAt(model.centerX(notice.place()) + 0.5, model.centerZ(notice.place()) + 0.5);
		}
		switch (notice.kind()) {
			case "tech" -> openTechTree();
			case "policy" -> RtsClient.openPolicies();
			case "talk", "trade", "peace" -> RtsClient.act("open", 0, 0, 0, 0, RtsClient.view.proposals > 0 ? "proposals" : "others");
			case "spy" -> RtsClient.act("open", 0, 0, 0, 0, "spies");
			case "faith" -> RtsClient.act("open", 0, 0, 0, 0, "religion");
			case "build", "city", "wonder" -> {
				CityState city = state == null || notice.place() == null ? null : state.cityAt(notice.place());
				if (city != null && city.key().owner() == state.session().viewer()) {
					openCity(city);
				}
			}
			default -> {
			}
		}
		RtsClient.notices.remove(notice);
		Sounds.ui("ui.button.click", 0.3f, 1.0f);
	}

	/**
	 * The news, down the right side: a small picture for each piece, by what kind it is. A new one says its words
	 * beside it for a few seconds and then keeps quiet; pointing at one says them again. A click goes to the place
	 * it is about and opens what deals with it; a right click puts it away. A word on what the player just did
	 * themselves ("Rome has new orders") is no news: it passes along the bottom and is gone.
	 */
	private void news(GuiGraphicsExtractor g) {
		WorldState state = RtsClient.state;
		int size = 18;
		int x = w - size - 4;
		int top = 44;
		int most = Math.max(3, Math.min(8, (h - 190 - top) / (size + 3)));
		int shown = 0;
		int more = 0;
		int spoken = 0;
		RtsClient.Notice passing = null;
		for (java.util.Iterator<RtsClient.Notice> it = RtsClient.notices.iterator(); it.hasNext(); ) {
			RtsClient.Notice notice = it.next();
			long age = RtsClient.ticks - notice.at();
			if (notice.kind().equals("news")) {
				if (age > 110) {
					it.remove();
				} else if (passing == null) {
					passing = notice;
				}
				continue;
			}
			if (age > 9000 || (state != null && state.turn() - notice.turn() > 3)) {
				it.remove();
				continue;
			}
			if (shown >= most) {
				more++;
				continue;
			}
			int y = top + shown * (size + 3);
			boolean over = mx >= x - 1 && mx < x + size + 1 && my >= y - 1 && my < y + size + 1;
			boolean fresh = age < 110;
			int edge = notice.kind().equals("war") ? RED : notice.kind().equals("wonder") || notice.kind().equals("era") ? GOLD : BORDER;
			g.fill(x - 1, y - 1, x + size + 1, y + size + 1, over ? WHITE : fresh && (RtsClient.ticks / 5) % 2 == 0 ? GOLD : edge);
			g.fill(x, y, x + size, y + size, over ? PANEL_LIGHT : 0xF0141420);
			item(g, icon(noticeIcon(notice.kind())), x + 1, y + 1, 1f);
			if (notice.place() != null) {
				// It happened somewhere: a click goes there.
				g.fill(x + size - 4, y + size - 4, x + size - 1, y + size - 1, GOLD);
			}
			if (over || (fresh && spoken < 2)) {
				List<String> lines = wrap(notice.text(), 176);
				if (lines.size() > 4) {
					lines = lines.subList(0, 4);
				}
				int width = 0;
				for (String line : lines) {
					width = Math.max(width, font.width(line));
				}
				String hint = notice.place() != null ? "Click: go there   Right click: put away" : "Click: open   Right click: put away";
				if (over) {
					width = Math.max(width, font.width(hint));
				}
				int height = lines.size() * 9 + 5 + (over ? 10 : 0);
				int bx = x - width - 14;
				int by = Math.max(18, y + (size - height) / 2);
				int alpha = over || age < 90 ? 0xF0 : (int) (0xF0 * (110 - age) / 20.0);
				g.fill(bx, by, bx + width + 10, by + height, (alpha << 24) | 0x101018);
				g.fill(bx, by, bx + 2, by + height, (Math.min(255, alpha + 15) << 24) | (edge & 0xFFFFFF));
				for (int i = 0; i < lines.size(); i++) {
					g.text(font, lines.get(i), bx + 6, by + 3 + i * 9, (Math.min(255, alpha + 15) << 24) | 0xFFFFFF, false);
				}
				if (over) {
					g.text(font, hint, bx + 6, by + 4 + lines.size() * 9, DIM, false);
				}
				spoken++;
			}
			zones.add(new Zone(x - 1, y - 1, size + 2, size + 2, null, () -> openNotice(notice)));
			rightZones.add(new Zone(x - 1, y - 1, size + 2, size + 2, null, () -> RtsClient.notices.remove(notice)));
			shown++;
		}
		if (more > 0 || shown > 1) {
			int y = top + shown * (size + 3);
			boolean over = mx >= x - 1 && mx < x + size + 1 && my >= y - 1 && my < y + 11;
			g.fill(x - 1, y - 1, x + size + 1, y + 11, over ? WHITE : BORDER);
			g.fill(x, y, x + size, y + 10, over ? PANEL_LIGHT : 0xF0141420);
			g.centeredText(font, more > 0 ? "+" + more : "x", x + size / 2, y + 1, more > 0 ? GOLD : DIM);
			zones.add(new Zone(x - 1, y - 1, size + 2, 12, more > 0 ? more + " more. Click: all the news so far (N). Right click: put all away" : "Put all the news away. All of it stays in the list (N)",
				more > 0 ? RtsClient::showLog : () -> RtsClient.notices.clear()));
			rightZones.add(new Zone(x - 1, y - 1, size + 2, 12, null, () -> RtsClient.notices.clear()));
		}
		if (passing != null) {
			long age = RtsClient.ticks - passing.at();
			String text = passing.text();
			while (font.width(text) > w - 420 && text.length() > 8) {
				text = text.substring(0, text.length() - 1);
			}
			if (!text.equals(passing.text())) {
				text += "..";
			}
			int width = font.width(text) + 16;
			int alpha = age < 90 ? 0xE0 : (int) (0xE0 * (110 - age) / 20.0);
			int bx = (w - width) / 2;
			int by = h - 30;
			g.fill(bx, by, bx + width, by + 14, (alpha << 24) | 0x101018);
			g.fill(bx, by + 13, bx + width, by + 14, (alpha << 24) | 0xFFD75E);
			g.centeredText(font, text, w / 2, by + 3, (Math.min(255, alpha + 31) << 24) | 0xFFFFFF);
		}
	}

	private static String moves(int sixtieths) {
		return sixtieths % 60 == 0 ? Integer.toString(sixtieths / 60) : String.format(Locale.ROOT, "%.1f", sixtieths / 60.0);
	}

	private ItemStack actionIcon(String key) {
		String k = key.toUpperCase(Locale.ROOT);
		if (k.startsWith("BUILD:")) {
			if (k.contains("FARM")) {
				return icon(Items.IRON_HOE);
			}
			if (k.contains("MINE") || k.contains("QUARRY")) {
				return icon(Items.IRON_PICKAXE);
			}
			if (k.contains("PASTURE")) {
				return icon(Items.OAK_FENCE);
			}
			if (k.contains("CAMP")) {
				return icon(Items.LEATHER);
			}
			if (k.contains("PLANTATION")) {
				return icon(Items.COCOA_BEANS);
			}
			if (k.contains("LUMBER")) {
				return icon(Items.IRON_AXE);
			}
			if (k.contains("RAILROAD")) {
				return icon(Items.RAIL);
			}
			if (k.contains("ROAD")) {
				return icon(Items.COBBLESTONE);
			}
			if (k.contains("TRADING")) {
				return icon(Items.EMERALD);
			}
			if (k.contains("FORT")) {
				return icon(Items.STONE_BRICKS);
			}
			if (k.contains("REMOVE") || k.contains("CUT")) {
				return icon(Items.STONE_AXE);
			}
			if (k.contains("FISHING") || k.contains("BOATS")) {
				return icon(Items.FISHING_ROD);
			}
			return icon(Items.IRON_SHOVEL);
		}
		if (k.startsWith("FOUNDCITY")) {
			return icon(Items.CAMPFIRE);
		}
		if (k.startsWith("FORTIFY")) {
			return icon(Items.SHIELD);
		}
		if (k.startsWith("SLEEP")) {
			return icon("red_bed");
		}
		if (k.startsWith("EXPLORE")) {
			return icon(Items.SPYGLASS);
		}
		if (k.startsWith("AUTOMATE")) {
			return icon(Items.REPEATER);
		}
		if (k.startsWith("SKIP")) {
			return icon(Items.CLOCK);
		}
		if (k.startsWith("DISBAND")) {
			return icon(Items.BARRIER);
		}
		if (k.startsWith("STOP")) {
			return icon(Items.REDSTONE_TORCH);
		}
		if (k.startsWith("PILLAGE")) {
			return icon(Items.FLINT_AND_STEEL);
		}
		if (k.startsWith("UPGRADE")) {
			return icon(Items.ANVIL);
		}
		if (k.startsWith("PROMOTE")) {
			return icon(Items.NETHER_STAR);
		}
		if (k.contains("RELIGION") || k.contains("HERESY")) {
			return icon(Items.BOOK);
		}
		if (k.startsWith("SETUP")) {
			return icon(Items.TARGET);
		}
		if (k.startsWith("HURRY") || k.startsWith("CONDUCT") || k.startsWith("TRIGGER") || k.startsWith("ADDINCAPITAL")) {
			return icon(Items.GOLD_BLOCK);
		}
		return icon(Items.PAPER);
	}

	private static String hotkey(String key) {
		return switch (key) {
			case "FoundCity" -> "B";
			case "Fortify", "Sleep" -> "F";
			case "Automate" -> "Z";
			case "Explore" -> "X";
			case "Skip" -> "_";
			default -> "";
		};
	}

	private void unitPanel(GuiGraphicsExtractor g, WorldState state) {
		RtsView view = RtsClient.view;
		UnitState unit = view.selected == null ? null : state.units().get(view.selected);
		if (unit == null) {
			return;
		}
		int x = 2;
		int y = h - 56;
		panel(g, x, y, 178, 54);
		g.fill(x + 4, y + 4, x + 44, y + 44, 0xFF202028);
		g.outline(x + 4, y + 4, 40, 40, BORDER);
		item(g, icon(Looks.weapon(unit)), x + 8, y + 8, 2f);
		g.text(font, unit.name(), x + 50, y + 5, WHITE, true);
		item(g, icon(Rich.MOVEMENT.picture()), x + 50, y + 17, 0.5f);
		g.text(font, moves(unit.moves()) + "/" + moves(unit.maxMoves()), x + 60, y + 17, unit.moves() > 0 ? GREEN : DIM, false);
		zones.add(new Zone(x + 50, y + 16, 60, 10, "Movement: how far it can still go this turn, of how far it goes in all", () -> { }));
		String strength = unit.strength() > 0 ? unit.strength() + " Strength" + (unit.rangedStrength() > 0 ? "   " + unit.rangedStrength() + " Ranged Strength" : "") : "Civilian";
		rich(g, tight(strength, 0xFFD8D8E0, true), x + 50, y + 27, false);
		if (unit.strength() > 0) {
			zones.add(new Zone(x + 50, y + 26, 80, 10, unit.strength() + " Strength in a fight hand to hand" + (unit.rangedStrength() > 0 ? "\n" + unit.rangedStrength()
				+ " Ranged Strength when it shoots from afar" : ""), () -> { }));
		}
		int bar = Math.max(1, 120 * unit.hp() / Math.max(1, unit.maxHp()));
		g.fill(x + 50, y + 39, x + 170, y + 44, 0xFF202028);
		g.fill(x + 50, y + 39, x + 50 + bar, y + 44, unit.hp() * 3 < unit.maxHp() ? RED : unit.hp() * 3 < unit.maxHp() * 2 ? GOLD : GREEN);
		g.text(font, unit.hp() + "/" + unit.maxHp(), x + 6, y + 45, DIM, false);
		// What it has earned: a mark for every promotion (point at one to read what it gives), and how far the next is.
		int px = x + 50;
		for (Records.Option row : view.extra) {
			if (row.group() == 32 && px < x + 118) {
				item(g, icon(Items.ENCHANTED_BOOK), px, y + 44, 0.5f);
				zones.add(new Zone(px, y + 44, 9, 9, row.name(), () -> { }));
				px += 9;
			} else if (row.group() == 33 && row.type().equals("xp")) {
				String xp = "XP " + row.id() + "/" + row.turns();
				g.text(font, xp, x + 172 - font.width(xp), y + 45, row.id() >= row.turns() ? GOLD : DIM, false);
				zones.add(new Zone(x + 172 - font.width(xp), y + 44, font.width(xp), 9, "Experience from fighting; at " + row.turns() + " it earns a promotion", () -> { }));
			}
		}
		// What it can do here, as a row of buttons over the panel.
		int bx = 2;
		int by = y - 22;
		for (Records.Option option : view.actions) {
			boolean now = option.turns() == 1;
			boolean over = mx >= bx && mx < bx + 20 && my >= by && my < by + 20;
			g.fill(bx, by, bx + 20, by + 20, over && now ? PANEL_LIGHT : PANEL);
			g.outline(bx, by, 20, 20, over && now ? WHITE : BORDER);
			item(g, actionIcon(option.type()), bx + 2, by + 2, 1f);
			String key = hotkey(option.type());
			if (!key.isEmpty()) {
				g.text(font, key, bx + 13, by + 11, GOLD, true);
			}
			if (!now) {
				g.fill(bx + 1, by + 1, bx + 19, by + 19, 0xA0000000);
			}
			String type = option.type();
			boolean disband = type.equals("Disband");
			boolean promote = type.equals("Promote");
			if (promote && (RtsClient.ticks / 8) % 2 == 0) {
				g.outline(bx, by, 20, 20, GOLD);
			}
			int unitId = unit.key().id();
			zones.add(new Zone(bx, by, 20, 20, option.name() + (now ? disband ? " (hold Shift and click)" : promote ? "\nIt has earned one: each says what it gives" : "" : " (not now)"), () -> {
				if (promote && now) {
					RtsClient.ask("promote", unitId);
				} else if (now && (!disband || InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT))) {
					RtsClient.act("unit", 0, 0, 0, 0, type);
				}
			}));
			bx += 22;
			if (bx + 20 > w / 2) {
				bx = 2;
				by -= 22;
			}
		}
	}

	/** One of the player's own cities: the view goes there and the list of what it can build comes up. */
	private void openCity(CityState city) {
		BoardModel model = RtsClient.model;
		if (model != null) {
			RtsCamera.lookAt(model.centerX(city.at()) + 0.5, model.centerZ(city.at()) + 0.5);
		}
		RtsClient.ask("build", city.key().id());
	}

	private boolean smallButton(GuiGraphicsExtractor g, int x, int y, int width, String label, String tip, Runnable click) {
		boolean over = mx >= x && mx < x + width && my >= y && my < y + 14;
		g.fill(x, y, x + width, y + 14, over ? PANEL_LIGHT : 0xFF1C1C24);
		g.outline(x, y, width, 14, over ? WHITE : BORDER);
		g.centeredText(font, label, x + width / 2, y + 3, over ? WHITE : DIM);
		zones.add(new Zone(x, y, width, 14, tip, click));
		return over;
	}

	/**
	 * The list of what can be chosen, down the left side as Civilization has it: what to research,
	 * which policy to adopt, or what a city builds or buys. One click on a line chooses it.
	 */
	private void choicePanel(GuiGraphicsExtractor g, WorldState state) {
		RtsClient.Choices list = RtsClient.choices;
		choiceBox[2] = 0;
		choiceBox[3] = 0;
		if (list == null) {
			rowActions.clear();
			return;
		}
		int me = state.session().viewer();
		String kind = list.kind();
		boolean buying = kind.equals("buy");
		boolean forFaith = kind.equals("faith");
		boolean ofCity = kind.equals("build") || buying || forFaith;
		CityState city = ofCity ? state.cities().get(new EntityKey(me, list.a())) : null;
		UnitState promoted = kind.equals("promote") ? state.units().get(new EntityKey(me, list.a())) : null;
		if ((ofCity && city == null) || (kind.equals("promote") && promoted == null)) {
			RtsClient.choices = null;
			return;
		}
		boolean lining = kind.equals("build") && RtsClient.lineUp;
		Empire empire = state.empire();
		Records.Option faith = RtsClient.extra("faith");
		int gold = forFaith ? (faith == null ? 0 : faith.id()) : empire == null ? 0 : empire.gold();
		String coin = forFaith ? "faith" : "gold";
		String title = switch (kind) {
			case "research" -> "CHOOSE RESEARCH";
			case "policy" -> "ADOPT A POLICY";
			case "promote" -> promoted.name().toUpperCase(Locale.ROOT) + " \u00B7 PROMOTION";
			case "greatPeople" -> "CHOOSE A GREAT PERSON";
			case "buy" -> city.name().toUpperCase(Locale.ROOT) + " \u00B7 BUY";
			case "faith" -> city.name().toUpperCase(Locale.ROOT) + " \u00B7 BUY WITH FAITH";
			default -> city.name().toUpperCase(Locale.ROOT) + (lining ? " \u00B7 BUILD NEXT" : " \u00B7 PRODUCTION");
		};
		List<String> lines = new ArrayList<>();
		if (city != null) {
			lines.add(city.population() + " Population" + (city.details() ? "   " + city.food() + "/" + city.foodNeeded() + " Food" : "") + "   " + city.strength() + " Defence");
			lines.add(buying || forFaith ? "To spend: " + gold + (forFaith ? " Faith" : " Gold") : city.productionName().isEmpty() ? "Building nothing yet"
				: "Building " + city.productionName() + (city.productionTurns() > 0 ? " (" + city.productionTurns() + " turns)" : ""));
			if (!buying && !forFaith && RtsClient.queueCity == list.a() && RtsClient.queue.size() > 1) {
				StringBuilder then = new StringBuilder("Then");
				for (int i = 1; i < RtsClient.queue.size(); i++) {
					then.append(" \u2192 ").append(RtsClient.queue.get(i).name());
				}
				String text = then.toString();
				while (font.width(text) > 188 && text.length() > 8) {
					text = text.substring(0, text.length() - 1);
				}
				lines.add(text.equals(then.toString()) ? text : text + "..");
			}
			Records.Option info = workInfo(list.a());
			if (info != null) {
				String[] parts = info.name().split("\\|");
				lines.add((info.id() - info.group()) + " at work" + (info.group() > 0 ? ", " + info.group() + " idle" : "") + (info.turns() > 0 ? ", " + info.turns() + " where you put them" : "")
					+ (city.capital() ? "" : parts.length > 1 && parts[1].equals("1") ? "   Joined to the capital" : "   No road to the capital"));
			}
		} else if (kind.equals("policy") && empire != null) {
			lines.add(empire.culture() + "/" + empire.policyCost() + " Culture   " + signed(empire.culturePerTurn()) + " a turn");
		} else if (kind.equals("research") && empire != null && !empire.researchName().isEmpty()) {
			lines.add("Now: " + empire.researchName() + " (" + empire.researchTurns() + " turns)");
		} else if (promoted != null) {
			lines.add("It keeps what it chooses for good.");
		} else if (kind.equals("greatPeople")) {
			lines.add("They come to your capital.");
		}
		List<Records.Option> options = list.options();
		// What each line is and does, when the game has said: its first words under the name, all of it on the card by the pointer.
		Map<String, String> texts = RtsClient.about.getOrDefault(kind, Map.of());
		int x = 2;
		int y = 48;
		int width = 214;
		int rowHeight = texts.isEmpty() ? 18 : 25;
		// The places in the city's buildings a citizen can work in (a Merchant in the Market), under the list.
		List<Records.Option> places = new ArrayList<>();
		if (city != null && RtsClient.workedCity == list.a()) {
			for (Records.Option option : RtsClient.worked) {
				if (option.type().startsWith("specialist:") && places.size() < 4) {
					places.add(option);
				}
			}
		}
		int footer = city != null ? 66 + places.size() * 16 : kind.equals("research") ? 18 : 0;
		int top = y + 15 + lines.size() * 10 + (lines.isEmpty() ? 0 : 3);
		int bottom = RtsClient.view.selected != null && state.units().containsKey(RtsClient.view.selected) ? h - 84 : h - 6;
		int room = Math.max(3, (bottom - top - footer - 4) / rowHeight);
		int shown = Math.min(options.size(), room);
		RtsClient.choicesScroll = Math.max(0, Math.min(options.size() - shown, RtsClient.choicesScroll));
		int scroll = RtsClient.choicesScroll;
		int height = top - y + Math.max(1, shown) * rowHeight + footer + 4;
		panel(g, x, y, width, height);
		choiceBox[0] = x;
		choiceBox[1] = y;
		choiceBox[2] = width;
		choiceBox[3] = height;
		g.fill(x + 1, y + 1, x + width - 1, y + 13, 0xFF2A2A34);
		g.text(font, title, x + 5, y + 3, GOLD, true);
		boolean overClose = mx >= x + width - 13 && mx < x + width - 1 && my >= y + 1 && my < y + 13;
		g.text(font, "x", x + width - 9, y + 2, overClose ? WHITE : DIM, false);
		zones.add(new Zone(x + width - 13, y + 1, 12, 12, "Close (Esc)", () -> RtsClient.choices = null));
		for (int i = 0; i < lines.size(); i++) {
			rich(g, tight(lines.get(i), i == 0 ? WHITE : DIM, true), x + 5, y + 17 + i * 10, false);
		}
		if (city != null) {
			zones.add(new Zone(x + 2, y + 15, width - 4, 10, city.name() + "\n" + city.population() + " Population: its citizens"
				+ (city.details() ? "\n" + city.food() + "/" + city.foodNeeded() + " Food stored towards the next citizen" : "") + "\n" + city.strength() + " Defence: how hard it is to take", () -> { }));
		}
		if (options.isEmpty()) {
			g.text(font, kind.equals("research") ? "Nothing to research right now." : kind.equals("policy") ? "Nothing can be adopted right now."
				: forFaith ? "Nothing here is had for faith yet." : buying ? "Nothing here can be bought." : promoted != null ? "It has earned no promotion."
				: kind.equals("greatPeople") ? "Nobody is on offer." : "Nothing can be built right now.", x + 5, top + 5, DIM, false);
		}
		rowActions.clear();
		for (int i = 0; i < shown; i++) {
			Records.Option option = options.get(scroll + i);
			int ry = top + i * rowHeight;
			boolean paid = buying || forFaith;
			boolean can = !paid || (option.turns() > 0 && option.turns() <= gold);
			boolean over = mx >= x + 2 && mx < x + width - 2 && my >= ry && my < ry + rowHeight;
			if (over && can) {
				g.fill(x + 2, ry, x + width - 2, ry + rowHeight - 1, PANEL_LIGHT);
			}
			if (i < 9) {
				g.text(font, Integer.toString(i + 1), x + 4, ry + (rowHeight - 8) / 2, can ? GOLD : 0xFF707078, false);
			}
			item(g, icon(Looks.choiceIcon(ofCity ? "build" : kind, option.group(), option.type(), option.name())), x + 12, ry + (rowHeight - 17) / 2, 1f);
			String right = paid ? option.turns() + (forFaith ? " Faith" : " Gold") : kind.equals("policy") ? (option.group() == 5 ? "new branch" : "")
				: option.turns() > 0 ? option.turns() + (option.turns() == 1 ? " turn" : " turns") : "";
			List<Rich.Piece> cost = tight(right, !can ? 0xFF707078 : paid ? GOLD : DIM, false);
			String name = option.name();
			int space = width - 38 - Rich.width(font, cost) - 8;
			while (font.width(name) > space && name.length() > 4) {
				name = name.substring(0, name.length() - 1);
			}
			String about = texts.getOrDefault(option.type(), "");
			g.text(font, name.equals(option.name()) ? name : name + "..", x + 32, ry + (about.isEmpty() ? (rowHeight - 8) / 2 : 3), can ? WHITE : 0xFF707078, can);
			rich(g, cost, x + width - 6 - Rich.width(font, cost), ry + (about.isEmpty() ? (rowHeight - 8) / 2 : 3), false);
			if (!about.isEmpty()) {
				// What it does in brief, numbers and pictures first; the card by the pointer has all of it.
				List<List<String>> brief = Rich.brief(font, about, width - 40, 1);
				if (!brief.isEmpty()) {
					rich(g, Rich.pieces(brief.get(0), false, can ? 0xFF9FB8D0 : 0xFF606068, can), x + 32, ry + 14, false);
				}
			}
			String what = kind.equals("research") ? "Research " + option.name() : kind.equals("policy") ? (option.group() == 5 ? "Open the branch " : "Adopt ") + option.name()
				: promoted != null || kind.equals("greatPeople") ? option.name()
				: (option.group() == 0 ? "Unit" : option.group() == 1 ? "Building" : option.group() == 2 ? "Project" : "Ongoing") + ": " + option.name()
				+ (paid && !can ? " (not enough " + coin + ")" : "") + (lining ? " (lined up after what is being built)" : "");
			int a = list.a();
			String sent = lining ? "queue" : kind;
			Runnable choose = () -> {
				if (can) {
					RtsClient.act("choose", a, option.group(), option.id(), option.turns(), sent + "\n" + option.type() + "\n" + option.name());
					if (lining) {
						// The list stays: more can be lined up.
						RtsClient.refreshLists();
					} else {
						RtsClient.choices = null;
					}
				}
			};
			rowActions.add(choose);
			zones.add(new Zone(x + 2, ry, width - 4, rowHeight, what + (i < 9 ? "  (" + (i + 1) + ")" : "") + (about.isEmpty() ? "" : "\n" + about), choose));
		}
		// More above or below: a small arrow in the corner.
		if (scroll > 0) {
			g.text(font, "\u25B2", x + width - 11, top + 1, GOLD, false);
		}
		if (scroll + shown < options.size()) {
			g.text(font, "\u25BC", x + width - 11, top + shown * rowHeight - 9, GOLD, false);
		}
		if (kind.equals("research")) {
			smallButton(g, x + 3, y + height - 17, width - 6, "Technology tree (T)", "Every technology, what it builds on, and how far off it is", this::openTechTree);
		}
		if (city != null) {
			int by = y + height - 49;
			EntityKey key = city.key();
			// A row of its own for what else the city can do: all about it, what faith buys, and lining builds up.
			int third = (width - 10) / 3;
			smallButton(g, x + 3, by - 16, third, "Details", "What " + city.name() + " makes, when it grows, what stands in it; and selling a building",
				() -> RtsClient.act("open", key.id(), 0, 0, 0, "cityinfo"));
			if (forFaith) {
				smallButton(g, x + 5 + third, by - 16, third, "Production", "Back to what " + city.name() + " builds", () -> RtsClient.ask("build", key.id()));
			} else {
				smallButton(g, x + 5 + third, by - 16, third, "Faith", faith == null ? "This game is played without religion" : "What faith buys here: missionaries, prophets, the buildings of your religion ("
					+ faith.id() + " faith)", () -> RtsClient.ask("faith", key.id()));
			}
			boolean on = RtsClient.lineUp;
			if (smallButton(g, x + 7 + third * 2, by - 16, third, on ? "Lining up" : "Line up", on ? "What you choose now is built after what the city builds, one after the other. Click to stop."
				: "Choose what " + city.name() + " builds next, after what it builds now", () -> {
					RtsClient.lineUp = !on;
					if (!kind.equals("build")) {
						RtsClient.ask("build", key.id());
					}
				}) || on) {
				g.outline(x + 7 + third * 2, by - 16, third, 14, GOLD);
			}
			for (int i = 0; i < places.size(); i++) {
				Records.Option place = places.get(i);
				int sy = by - 16 - places.size() * 16 + i * 16;
				String job = place.name();
				g.text(font, job + "  " + place.group() + "/" + place.id(), x + 6, sy + 3, place.group() > 0 ? WHITE : DIM, false);
				smallButton(g, x + width - 35, sy, 15, "-", "Take a citizen out of this work, back to the land", () -> RtsClient.act("work", key.id(), 0, 0, 0, "specialist-:" + job));
				smallButton(g, x + width - 19, sy, 15, "+", "Put a citizen to work as a " + job + " (taken from the land the city chose itself)",
					() -> RtsClient.act("work", key.id(), 0, 0, 0, "specialist+:" + job));
			}
			// What the city's citizens look to when they choose their own work, as Civilization's city screen has it.
			Records.Option info = workInfo(list.a());
			String focus = info == null ? "none" : info.name().split("\\|")[0];
			String[][] ways = { { "none", "Even" }, { "food", "Food" }, { "production", "Production" }, { "gold", "Gold" }, { "science", "Science" }, { "culture", "Culture" },
				{ "faith", "Faith" } };
			// Two rows of four, each button as wide as its word needs, so every word is written out.
			for (int row = 0; row < 2; row++) {
				int words = 0;
				for (int i = row * 4; i < row * 4 + 4; i++) {
					words += font.width(i == ways.length ? "Reset" : ways[i][1]);
				}
				float pad = (width - 6 - words) / 4f;
				float fx = x + 3;
				int fy = y + height - 33 + row * 16;
				for (int i = row * 4; i < row * 4 + 4; i++) {
					String label = i == ways.length ? "Reset" : ways[i][1];
					int wide = Math.round(font.width(label) + pad) - 1;
					int at = Math.round(fx);
					if (i == ways.length) {
						smallButton(g, at, fy, wide, label, "Every citizen chooses their own work again, also those you placed", () -> RtsClient.act("work", key.id(), 0, 0, 0, "reset"));
					} else {
						String way = ways[i][0];
						smallButton(g, at, fy, wide, label, way.equals("none") ? "The citizens weigh everything evenly" : "The citizens look to " + way + " first",
							() -> RtsClient.act("work", key.id(), 0, 0, 0, "focus:" + way));
						if (way.equals(focus)) {
							g.outline(at, fy, wide, 14, GOLD);
						}
					}
					fx += font.width(label) + pad;
				}
			}
			if (buying) {
				smallButton(g, x + 3, by, 103, "Production", "Back to what " + city.name() + " builds", () -> RtsClient.ask("build", key.id()));
			} else {
				smallButton(g, x + 3, by, 103, "Buy with gold", "Pay from the treasury (" + (empire == null ? 0 : empire.gold()) + " Gold) and have it at once",
					() -> RtsClient.ask("buy", key.id()));
			}
			smallButton(g, x + 108, by, 103, "City affairs", "Everything else about " + city.name(), () -> {
				RtsClient.choices = null;
				RtsClient.act("open", key.owner(), key.id(), 0, 0, "city");
			});
		}
	}

	/** The line about the city whose list is up: how many work, how many are idle, what it looks to. */
	private Records.Option workInfo(int city) {
		if (RtsClient.workedCity != city) {
			return null;
		}
		for (Records.Option option : RtsClient.worked) {
			if (option.type().equals("info")) {
				return option;
			}
		}
		return null;
	}

	/**
	 * While a city's list is up: a mark on every hex its citizens can work. Green is worked, a
	 * gold edge means the player put the citizen there, dark is free. A click puts a citizen
	 * there for good, or gives the place back to the city's own choice.
	 */
	private void workedLayer(GuiGraphicsExtractor g, WorldState state, BoardModel model) {
		RtsClient.Choices list = RtsClient.choices;
		if (list == null || !(list.kind().equals("build") || list.kind().equals("buy")) || RtsClient.workedCity != list.a()) {
			return;
		}
		float zoom = RtsCamera.shown();
		ItemStack head = icon(Items.PLAYER_HEAD);
		for (Records.Option hex : RtsClient.worked) {
			if (hex.type().equals("info") || hex.type().startsWith("specialist:") || hex.group() == 3) {
				continue;
			}
			String[] at = hex.type().split(",");
			PlotCoord plot = new PlotCoord(Integer.parseInt(at[0]), Integer.parseInt(at[1]));
			double cx = model.layout().x(state.session().grid().center(plot));
			double cz = model.layout().z(state.session().grid().center(plot));
			project(cx, RtsCamera.GROUND_Y, cz);
			// To the left in the hex: the middle is for whoever stands there, above are the yields, below what the land holds.
			int x = Math.round(point[0] - (float) (model.layout().hexWidth() * 0.27 / zoom * h * 0.5)) - 6;
			int y = Math.round(point[1]) - 6;
			if (!onScreen(x, y, 20)) {
				continue;
			}
			int how = hex.group();
			boolean over = mx >= x && mx < x + 13 && my >= y && my < y + 13;
			g.fill(x - 1, y - 1, x + 13, y + 13, how == 2 ? GOLD : how == 1 ? WHITE : how == 4 ? RED : over ? WHITE : 0xFF9A9AA4);
			g.fill(x, y, x + 12, y + 12, how == 1 || how == 2 ? 0xFF3FA34D : how == 4 ? 0xFF401818 : 0xFF202028);
			if (how == 1 || how == 2) {
				item(g, head, x + 2, y + 2, 0.5f);
			}
			if (how != 4) {
				String tip = how == 2 ? "A citizen works here because you said so. Click to leave the place to the city again"
					: how == 1 ? "Worked, by the city's own choice. Click to keep a citizen here whatever happens" : "Click to put a citizen to work here";
				int city = list.a();
				zones.add(new Zone(x - 1, y - 1, 14, 14, tip, () -> RtsClient.act("work", city, 0, plot.x(), plot.y(), "toggle")));
			} else {
				zones.add(new Zone(x - 1, y - 1, 14, 14, "Another of your cities works this land", () -> { }));
			}
		}
	}

	/**
	 * Everything the player's people know of the world on one flat map over the screen, as a
	 * strategy game's overview: land by its kind and its owner, cities by name, units as marks.
	 * A click goes there.
	 */
	private void mapPanel(GuiGraphicsExtractor g, WorldState state, BoardModel model) {
		if (!RtsClient.mapOpen) {
			return;
		}
		var grid = state.session().grid();
		int cols = grid.width();
		int rows = grid.height();
		int x = 12;
		int y = 32;
		int width = w - 24;
		int height = h - 44;
		panel(g, x, y, width, height);
		g.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xF00C0C12);
		g.fill(x + 1, y + 1, x + width - 1, y + 14, 0xFF2A2A34);
		g.text(font, "THE KNOWN WORLD", x + 6, y + 4, GOLD, true);
		String hint = "Click: go there. M or Esc: back.";
		g.text(font, hint, x + width - 22 - font.width(hint), y + 4, DIM, false);
		boolean overClose = mx >= x + width - 14 && mx < x + width - 1 && my >= y + 1 && my < y + 14;
		g.text(font, "x", x + width - 10, y + 3, overClose ? WHITE : DIM, false);
		float cell = Math.max(3f, Math.min((width - 16) / (cols + 0.5f), (height - 26) / (float) rows));
		int mapWidth = Math.round((cols + 0.5f) * cell);
		int mapHeight = Math.round(rows * cell);
		int left = x + (width - mapWidth) / 2;
		int top = y + 18 + (height - 22 - mapHeight) / 2;
		MapTexture.draw(g, state, plot -> plotColor(state, plot), left, top, mapWidth, mapHeight);
		if (cell >= 14) {
			// Room enough for what the land holds.
			for (int row = 0; row < rows; row++) {
				float shift = (float) grid.rowShift(row) * cell;
				float py = top + (rows - 1 - row) * cell;
				for (int col = 0; col < cols; col++) {
					PlotState p = state.plot(new PlotCoord(col, row));
					if (p.revealed() && p.resource() >= 0 && !p.city()) {
						item(g, resourceIcon(state.legend().type(Legend.Kind.RESOURCE, p.resource())), left + col * cell + shift + (cell - 8) / 2f, py + (cell - 8) / 2f, 0.5f);
					}
				}
			}
		}
		int me = state.session().viewer();
		for (UnitState unit : state.units().values()) {
			PlotCoord at = unit.at();
			int px = Math.round(left + (at.x() + (float) grid.rowShift(at.y()) + 0.5f) * cell);
			int py = Math.round(top + (rows - 1 - at.y() + 0.5f) * cell);
			CivInfo owner = state.civ(unit.key().owner());
			int mark = Math.max(2, Math.round(cell / 4));
			g.fill(px - mark - 1, py - mark - 1, px + mark + 1, py + mark + 1, unit.key().owner() == me ? WHITE : 0xFF101010);
			g.fill(px - mark, py - mark, px + mark, py + mark, owner == null ? 0xFF808080 : 0xFF000000 | owner.color());
		}
		for (CityState city : state.cities().values()) {
			PlotCoord at = city.at();
			int px = Math.round(left + (at.x() + (float) grid.rowShift(at.y()) + 0.5f) * cell);
			int py = Math.round(top + (rows - 1 - at.y()) * cell);
			CivInfo owner = state.civ(city.key().owner());
			String name = city.name();
			int nameWidth = font.width(name);
			g.fill(px - nameWidth / 2 - 2, py - 10, px + nameWidth / 2 + 2, py, mix(owner == null ? 0xFF808080 : 0xFF000000 | owner.color(), 0xFF000000, 0.5f));
			g.text(font, name, px - nameWidth / 2, py - 9, WHITE, false);
		}
		// The part of the land the view from above shows right now.
		float[][] corners = new float[4][2];
		double[][] screen = { { 0, 16 }, { w, 16 }, { w, h }, { 0, h } };
		for (int i = 0; i < 4; i++) {
			double[] ground = RtsCamera.ground(screen[i][0], screen[i][1], w, h);
			GridPos pos = model.layout().toGrid(ground[0], ground[1]);
			corners[i][0] = (float) (left + (pos.u() + 0.5) * cell);
			corners[i][1] = (float) (top + (rows - 0.5 - pos.v()) * cell);
		}
		clipOn(g, x + 2, y + 16, x + width - 2, y + height - 2);
		for (int i = 0; i < 4; i++) {
			line(g, corners[i][0], corners[i][1], corners[(i + 1) % 4][0], corners[(i + 1) % 4][1], WHITE, 1);
		}
		clipOff(g);
		int mapLeft = left;
		int mapTop = top;
		zones.add(new Zone(left, top, mapWidth, mapHeight, null, () -> {
			GridPos pos = new GridPos((mx - mapLeft) / cell - 0.5, rows - 0.5 - (my - mapTop) / cell);
			RtsCamera.lookAt(model.layout().x(pos), model.layout().z(pos));
			RtsClient.mapOpen = false;
		}));
		zones.add(new Zone(x + width - 14, y + 1, 13, 13, "Close (Esc)", () -> RtsClient.mapOpen = false));
	}

	private void toggleYields() {
		RtsClient.yieldsOn(!RtsClient.showYields);
	}

	private void openTechTree() {
		RtsClient.choices = null;
		RtsClient.menu = null;
		RtsClient.mapOpen = false;
		RtsClient.openTechs();
	}

	private static final int[] YIELD_COLORS = { 0xFF7FE060, 0xFFFFA040, 0xFFFFD75E, 0xFF7FD4FF, 0xFFE28BFF, 0xFFF0F0F0 };

	/** What each hex yields, as Civilization shows it on request: a small picture and a number for food, production, gold and the rest. */
	private void yields(GuiGraphicsExtractor g, WorldState state, BoardModel model, int[] range) {
		float zoom = RtsCamera.shown();
		if (!RtsClient.showYields || zoom > 100f || RtsClient.yields.isEmpty()) {
			return;
		}
		ItemStack[] pictures = { icon(Rich.FOOD.picture()), icon(Rich.PRODUCTION.picture()), icon(Rich.GOLD.picture()), icon(Rich.SCIENCE.picture()), icon(Rich.CULTURE.picture()),
			icon(Rich.FAITH.picture()) };
		for (int row = range[2]; row <= range[3]; row++) {
			for (int col = range[0]; col <= range[1]; col++) {
				PlotCoord plot = new PlotCoord(col, row);
				int[] values = RtsClient.yields.get(plot);
				if (values == null || !state.plot(plot).revealed() || state.plot(plot).city()) {
					continue;
				}
				int count = 0;
				for (int value : values) {
					count += value > 0 ? 1 : 0;
				}
				if (count == 0) {
					continue;
				}
				double cx = model.layout().x(state.session().grid().center(plot));
				double cz = model.layout().z(state.session().grid().center(plot));
				project(cx, RtsCamera.GROUND_Y, cz);
				// High in the hex, over whoever stands in the middle of it.
				int x = Math.round(point[0]) - count * 8;
				int y = Math.round(point[1] - (float) (model.layout().hexWidth() * 0.22 / zoom * h * 0.5)) - 5;
				if (!onScreen(x, y, 60)) {
					continue;
				}
				g.fill(x - 2, y - 2, x + count * 16 + 1, y + 10, 0x80000000);
				for (int i = 0; i < values.length; i++) {
					if (values[i] <= 0) {
						continue;
					}
					item(g, pictures[i], x, y, 0.5f);
					g.text(font, Integer.toString(values[i]), x + 9, y + 1, YIELD_COLORS[i], true);
					x += 16;
				}
			}
		}
	}

	private static final String[][] HELP = {
		{ "Left click", "select a unit or a city" },
		{ "Right click", "send the selected unit there, or attack" },
		{ "Mouse wheel", "zoom; hold it and drag to pull the land along" },
		{ "W A S D, arrows", "move the view; Q and E turn it, H goes home" },
		{ "Tab", "the next unit that waits for orders" },
		{ "B   F   Space", "found a city, fortify, skip this turn" },
		{ "Z   X", "let the unit get on by itself, or explore" },
		{ "Enter", "the turn button (with Shift: end the turn anyway)" },
		{ "1 to 9", "choose a line of a list or a button of a page" },
		{ "T   P   V   R", "technologies, policies, ways to win, religion" },
		{ "M   Y   G   O", "world map, yields, hex grid, colours of the borders" },
		{ "L   N", "who plays (take a civilization, a new game); the news so far" },
		{ "F12", "walk the land yourself, where you point on your own land; and back up" },
		{ "Esc", "close what is open, let the unit go, game menu" },
	};

	/**
	 * How the view is worked, on one page: shown by itself the first time, and with F1 or the
	 * book at the top right ever after. Whoever sees this view for the first time has no way
	 * of knowing that the mouse selects and the turn has a button.
	 */
	private void helpPanel(GuiGraphicsExtractor g) {
		if (!RtsClient.helpOpen) {
			return;
		}
		int keys = 0;
		int words = 0;
		for (String[] line : HELP) {
			keys = Math.max(keys, font.width(line[0]));
			words = Math.max(words, font.width(line[1]));
		}
		int width = Math.min(w - 20, keys + words + 34);
		int height = 22 + HELP.length * 12 + 34;
		int x = (w - width) / 2;
		int y = Math.max(20, (h - height) / 2);
		panel(g, x, y, width, height);
		g.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xF0101016);
		g.fill(x + 1, y + 1, x + width - 1, y + 14, 0xFF2A2A34);
		g.text(font, "HOW TO RULE FROM ABOVE", x + 6, y + 4, GOLD, true);
		for (int i = 0; i < HELP.length; i++) {
			int ly = y + 20 + i * 12;
			g.text(font, HELP[i][0], x + 10, ly, GOLD, false);
			g.text(font, HELP[i][1], x + 22 + keys, ly, WHITE, false);
		}
		int foot = y + 22 + HELP.length * 12;
		g.centeredText(font, "The big button at the bottom right always says what comes next.", x + width / 2, foot, 0xFFB8B8C4);
		boolean over = mx >= x + width / 2 - 50 && mx < x + width / 2 + 50 && my >= foot + 13 && my < foot + 27;
		g.fill(x + width / 2 - 50, foot + 13, x + width / 2 + 50, foot + 27, over ? 0xFF7ED06A : 0xFF58B048);
		g.centeredText(font, "Begin  (F1)", x + width / 2, foot + 16, 0xFF1A1206);
		// The whole page closes at a click: nothing on it can be pressed wrongly.
		zones.add(new Zone(x, y, width, height, null, () -> RtsClient.help(false)));
	}

	/** The frame every page shares: its ground, its title in the colour of whom it is about, and the x that closes it. */
	private void pageFrame(GuiGraphicsExtractor g, Net.Menu menu, int x, int y, int width, int height) {
		panel(g, x, y, width, height);
		g.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xE8101016);
		menuBox[0] = x;
		menuBox[1] = y;
		menuBox[2] = width;
		menuBox[3] = height;
		g.fill(x + 1, y + 1, x + width - 1, y + 14, mix(0xFF000000 | menu.color(), 0xFF000000, 0.6f));
		g.text(font, menu.title(), x + 6, y + 4, WHITE, true);
		boolean overClose = mx >= x + width - 14 && mx < x + width - 1 && my >= y + 1 && my < y + 14;
		g.text(font, "x", x + width - 10, y + 3, overClose ? WHITE : DIM, false);
		zones.add(new Zone(x + width - 14, y + 1, 13, 13, "Close (Esc)", () -> RtsClient.menu = null));
	}

	/** One button of a page: its picture, its words, its number (the first nine in sight), and what pressing it does. */
	private void pageButton(GuiGraphicsExtractor g, Net.Menu menu, Net.Button button, int bx, int by, int buttonWidth, int ground) {
		boolean over = mx >= bx && mx < bx + buttonWidth && my >= by && my < by + 16;
		g.fill(bx, by, bx + buttonWidth, by + 16, over ? mix(ground, WHITE, 0.18f) : ground);
		g.outline(bx, by, buttonWidth, 16, over ? WHITE : BORDER);
		String label = button.label();
		ItemStack picture = menuIcon(menu.title(), label);
		int inset = picture == null ? 0 : 16;
		if (picture != null) {
			item(g, picture, bx + 3, by + 2, 0.75f);
		}
		while (font.width(label) > buttonWidth - 20 - inset && label.length() > 4) {
			label = label.substring(0, label.length() - 1);
		}
		g.centeredText(font, label.equals(button.label()) ? label : label + "..", bx + inset / 2 + buttonWidth / 2, by + 4, over ? WHITE : 0xFFD0D0DA);
		String tip = label.equals(button.label()) ? button.tip() : button.label() + (button.tip().isEmpty() ? "" : ": " + button.tip());
		Runnable press = () -> {
			RtsClient.menu = null;
			RtsClient.act("menu", 0, 0, 0, 0, button.token());
		};
		int number = pageActions.size() + 1;
		if (number <= 9) {
			pageActions.add(press);
			g.text(font, Integer.toString(number), bx + buttonWidth - 9, by + 4, over ? GOLD : 0xFF8A7A40, false);
		}
		zones.add(new Zone(bx, by, buttonWidth, 16, tip, press));
	}

	/**
	 * Dealing with another people, laid out as Civilization has it: whom one is speaking to on
	 * the left (their colours, and how they look upon the player), what they say beside it, and
	 * under that what can be said back.
	 */
	private void leaderPage(GuiGraphicsExtractor g, Net.Menu menu, WorldState state) {
		CivInfo civ = state.civ(menu.a());
		var relation = state.relations().get(menu.a());
		int color = 0xFF000000 | civ.color();
		int width = Math.min(420, w - 40);
		int x = (w - width) / 2;
		int y = 36;
		int portrait = 74;
		int textX = x + portrait + 14;
		int textWidth = width - portrait - 22;
		List<String> text = new ArrayList<>();
		List<Integer> tints = new ArrayList<>();
		for (int i = 0; i < menu.lines().size(); i++) {
			Net.Line line = menu.lines().get(i);
			for (String part : wrap(line.text(), textWidth)) {
				text.add(part);
				tints.add(line.color() != 0 ? line.color() : i == 0 ? WHITE : 0xFFB8B8C4);
			}
		}
		int buttonsTop = y + 20 + text.size() * 10 + 5;
		int height = Math.max(portrait + 40, buttonsTop - y + menu.buttons().size() * 18 + 4);
		pageFrame(g, menu, x, y, width, height);
		// Whom one speaks to: a head under their banner, on their colour.
		int px = x + 7;
		int py = y + 20;
		g.fill(px, py, px + portrait, py + portrait + 14, mix(color, 0xFF000000, 0.55f));
		g.outline(px, py, portrait, portrait + 14, color);
		String dye = nl.jordythomassen.livingworld.board.Dye.NAMES[nl.jordythomassen.livingworld.board.Dye.nearest(civ.color())];
		item(g, icon(dye + "_banner"), px + 3, py + 3, 1.5f);
		item(g, icon(dye + "_banner"), px + portrait - 27, py + 3, 1.5f);
		item(g, icon(civ.minor() ? "bell" : "player_head"), px + (portrait - 48) / 2f, py + 20, 3f);
		if (!civ.minor()) {
			item(g, icon("golden_helmet"), px + (portrait - 32) / 2f, py + 4, 2f);
		}
		String mood = civ.atWarWithViewer() ? "AT WAR" : civ.minor() ? "city-state" : relation == null || relation.word().isEmpty() ? "" : relation.word().replace('_', ' ').toLowerCase(Locale.ROOT);
		String sour = mood.toLowerCase(Locale.ROOT);
		int moodColor = sour.contains("war") || sour.contains("hostile") || sour.contains("enemy") || sour.contains("unforgiv") ? RED
			: sour.contains("friend") || sour.contains("ally") || sour.contains("favorable") ? GREEN : sour.contains("guarded") || sour.contains("afraid") || sour.contains("competitor") ? GOLD : 0xFFD0D0DA;
		g.centeredText(font, mood, px + portrait / 2, py + portrait + 3, moodColor);
		for (int i = 0; i < text.size(); i++) {
			g.text(font, text.get(i), textX, y + 21 + i * 10, tints.get(i), false);
		}
		for (int i = 0; i < menu.buttons().size(); i++) {
			pageButton(g, menu, menu.buttons().get(i), textX, buttonsTop + i * 18, textWidth, 0xFF1C1C24);
		}
	}

	private ItemStack dealIcon(String thing) {
		String t = thing.toLowerCase(Locale.ROOT);
		return t.contains("gold a turn") || t.contains("gold per turn") ? icon("gold_nugget") : t.contains("gold") ? icon("gold_ingot") : t.contains("open borders") ? icon("oak_door")
			: t.contains("embassy") ? icon("lectern") : t.contains("research") ? icon("enchanting_table") : t.contains("defen") ? icon("shield") : t.contains("peace") ? icon("white_banner")
			: t.contains("war") ? icon("iron_sword") : t.contains("city") ? icon("bricks") : t.contains("technolog") ? icon("book") : resourceIcon(thing);
	}

	/**
	 * The table of a deal, as Civilization lays it out: what the player gives on the left, what
	 * they get on the right, a thing a line; under each side the button that adds to it, and at
	 * the foot what the other side thinks of it and the button that puts it to them.
	 */
	private void dealPage(GuiGraphicsExtractor g, Net.Menu menu) {
		int width = Math.min(420, w - 40);
		int x = (w - width) / 2;
		int y = 36;
		int half = (width - 18) / 2;
		int leftX = x + 6;
		int rightX = x + 12 + half;
		int rows = Math.max(1, Math.max(menu.left().size(), menu.right().size()));
		// The line that is neither side of the table is what they think of it.
		Net.Line verdict = null;
		for (Net.Line line : menu.lines()) {
			if (!line.text().startsWith("You give:") && !line.text().startsWith("You get:")) {
				verdict = line;
			}
		}
		Net.Button offer = null;
		Net.Button ask = null;
		Net.Button propose = null;
		List<Net.Button> rest = new ArrayList<>();
		for (Net.Button button : menu.buttons()) {
			if (button.label().startsWith("Offer")) {
				offer = button;
			} else if (button.label().startsWith("Ask")) {
				ask = button;
			} else if (button.label().startsWith("Propose")) {
				propose = button;
			} else {
				rest.add(button);
			}
		}
		int tableTop = y + 32;
		int addTop = tableTop + rows * 14 + 4;
		int verdictTop = addTop + 22;
		int footTop = verdictTop + (verdict == null ? 0 : 14);
		int height = footTop - y + (propose != null || !rest.isEmpty() ? 20 : 2);
		pageFrame(g, menu, x, y, width, height);
		g.text(font, "YOU GIVE", leftX + 2, y + 20, GOLD, false);
		g.text(font, "YOU GET", rightX + 2, y + 20, GOLD, false);
		g.fill(x + 6 + half + 2, y + 18, x + 6 + half + 3, addTop + 16, 0x60FFFFFF);
		for (int side = 0; side < 2; side++) {
			List<String> things = side == 0 ? menu.left() : menu.right();
			int sx = side == 0 ? leftX : rightX;
			g.fill(sx, tableTop - 2, sx + half, tableTop + rows * 14, 0xFF16161E);
			if (things.isEmpty()) {
				g.text(font, "nothing", sx + 6, tableTop + 2, 0xFF707078, false);
			}
			for (int i = 0; i < things.size(); i++) {
				item(g, dealIcon(things.get(i)), sx + 3, tableTop + i * 14 - 1, 0.75f);
				String thing = things.get(i);
				while (font.width(thing) > half - 24 && thing.length() > 4) {
					thing = thing.substring(0, thing.length() - 1);
				}
				g.text(font, thing, sx + 19, tableTop + i * 14 + 2, WHITE, false);
			}
		}
		// In the order the page has them, so that the numbers on the buttons are those of the page.
		if (offer != null) {
			pageButton(g, menu, offer, leftX, addTop, half, 0xFF1C1C24);
		}
		if (ask != null) {
			pageButton(g, menu, ask, rightX, addTop, half, 0xFF1C1C24);
		}
		boolean agreeable = verdict != null && (verdict.color() & 0xFFFFFF) == 0x55FF55;
		if (verdict != null) {
			g.centeredText(font, verdict.text(), x + width / 2, verdictTop, verdict.color() == 0 ? WHITE : verdict.color());
		}
		int fx = x + 6;
		if (propose != null) {
			int wide = rest.isEmpty() ? width - 12 : (width - 16) * 2 / 3;
			pageButton(g, menu, propose, fx, footTop, wide, agreeable ? 0xFF245C2C : 0xFF4A3C1C);
			fx += wide + 4;
		}
		for (Net.Button button : rest) {
			int wide = Math.max(60, (x + width - 6 - fx) / Math.max(1, rest.size()) - (rest.size() > 1 ? 4 : 0));
			pageButton(g, menu, button, fx, footTop, wide, 0xFF1C1C24);
			fx += wide + 4;
		}
	}

	/** A small picture for a button on a page, by what the button says: the pages are the game's own words, so the words are what there is to go by. */
	private ItemStack menuIcon(String title, String label) {
		String t = label.toLowerCase(Locale.ROOT);
		String page = title.toLowerCase(Locale.ROOT);
		String id = t.startsWith("back") || t.contains("start over") ? "arrow"
			: t.contains("declare war") || t.startsWith("war") ? "iron_sword"
			: t.contains("peace") ? "white_banner"
			: t.contains("friend") ? "poppy"
			: t.contains("denounce") ? "wither_rose"
			: t.contains("protect") || t.contains("pledge") ? "shield"
			: t.contains("accept") || t.startsWith("yes") || t.contains("propose this") ? "lime_dye"
			: t.contains("decline") || t.startsWith("no") ? "red_dye"
			: t.contains("gold a turn") ? "gold_nugget"
			: t.contains("gold") || t.contains("gift") ? "gold_ingot"
			: t.contains("deal") || t.contains("offer") || t.contains("ask for") ? "emerald"
			: t.contains("open borders") ? "oak_door"
			: t.contains("embass") ? "lectern"
			: t.contains("research") || t.contains("technolog") ? "enchanting_table"
			: t.contains("polic") ? "writable_book"
			: t.contains("annex") ? "golden_helmet"
			: t.contains("puppet") ? "armor_stand"
			: t.contains("raze") || t.contains("burn") ? "flint_and_steel"
			: t.contains("liberate") || t.contains("give it back") || t.contains("spare") ? "white_banner"
			: t.contains("new game") || t.equals("begin") || t.contains("yes, begin") ? "nether_star"
			: t.startsWith("you lead") ? "player_head"
			: t.startsWith("map size") ? "map"
			: t.startsWith("land") ? "grass_block"
			: t.startsWith("other civilizations") || t.startsWith("city-states") ? "globe_banner_pattern"
			: t.startsWith("difficulty") ? "iron_chestplate"
			: t.startsWith("length") ? "clock"
			: t.contains("abstain") || t.contains("nobody") ? "barrier"
			: t.contains("deliver logs") ? "oak_log"
			: t.contains("buy") ? "gold_ingot"
			: t.contains("builds") || t.contains("what becomes") ? "anvil"
			: t.contains(": yes") ? "lime_dye"
			: t.contains(": no") ? "gray_dye"
			: null;
		if (id == null) {
			if (page.contains("believe")) {
				id = "candle";
			} else if (page.contains("vote")) {
				id = "paper";
			} else if (page.contains("cities and units")) {
				id = t.contains("builds") || t.contains("building") || t.contains("city") ? "bricks" : Looks.weapon(label, true);
			} else if (page.contains("other civilizations") || page.contains("where to")) {
				id = "white_banner";
			} else if (page.contains("whom do you lead")) {
				id = "player_head";
			} else if (page.contains("offer") || page.contains("ask")) {
				id = "emerald";
			} else {
				return null;
			}
		}
		return icon(id);
	}

	/**
	 * A page of the game's own menus (the other civilizations, a deal, the settings ...) as a
	 * panel over the middle of the map: its lines, then its buttons. The map stays in sight
	 * around it, and a button pressed either does something or brings the next page.
	 */
	private void menuPanel(GuiGraphicsExtractor g) {
		Net.Menu menu = RtsClient.menu;
		menuBox[2] = 0;
		menuBox[3] = 0;
		pageActions.clear();
		if (menu == null) {
			return;
		}
		WorldState known = RtsClient.state;
		if (menu.kind().equals("leader") && known != null && known.civ(menu.a()) != null) {
			leaderPage(g, menu, known);
			return;
		}
		if (menu.kind().equals("deal")) {
			dealPage(g, menu);
			return;
		}
		if (menu.kind().equals("citysheet")) {
			cityPage(g, menu);
			return;
		}
		int width = Math.min(400, w - 40);
		int x = (w - width) / 2;
		int y = 36;
		List<List<Rich.Piece>> text = new ArrayList<>();
		List<Integer> down = new ArrayList<>();
		List<Boolean> headings = new ArrayList<>();
		int textHeight = 0;
		for (Net.Line line : menu.lines()) {
			int tint = line.color() == 0 ? 0xFFE4E4EC : line.color();
			// A heading (the pages write theirs in gold) stands a little apart, with a rule under it.
			boolean heading = (tint & 0xFFFFFF) == 0xFFAA00;
			if (heading && !text.isEmpty()) {
				textHeight += 4;
			}
			for (List<String> part : Rich.flow(font, Rich.words(line.text(), false), width - 16, 99, true)) {
				text.add(Rich.pieces(part, true, tint, true));
				down.add(textHeight);
				headings.add(heading);
				textHeight += 10;
			}
			if (heading) {
				textHeight += 2;
			}
		}
		int columns = Math.max(1, Math.min(2, menu.columns()));
		int rows = (menu.buttons().size() + columns - 1) / columns;
		int top = y + 17 + textHeight + (text.isEmpty() ? 0 : 4);
		int room = Math.max(2, (h - 30 - top) / 18);
		int shown = Math.min(rows, room);
		RtsClient.menuScroll = Math.max(0, Math.min(rows - shown, RtsClient.menuScroll));
		int scroll = RtsClient.menuScroll;
		int height = top - y + shown * 18 + 4;
		pageFrame(g, menu, x, y, width, height);
		for (int i = 0; i < text.size(); i++) {
			int ly = y + 19 + down.get(i);
			rich(g, text.get(i), x + 8, ly, false);
			if (headings.get(i) && (i + 1 == text.size() || !headings.get(i + 1))) {
				g.fill(x + 8, ly + 9, x + width - 8, ly + 10, 0x50FFAA00);
			}
		}
		int gap = 4;
		int buttonWidth = (width - gap * (columns + 1)) / columns;
		for (int row = 0; row < shown; row++) {
			for (int col = 0; col < columns; col++) {
				int index = (scroll + row) * columns + col;
				if (index >= menu.buttons().size()) {
					break;
				}
				pageButton(g, menu, menu.buttons().get(index), x + gap + col * (buttonWidth + gap), top + row * 18, buttonWidth, 0xFF1C1C24);
			}
		}
		if (scroll > 0) {
			g.text(font, "\u25B2", x + width - 10, top - 9, GOLD, false);
		}
		if (scroll + shown < rows) {
			g.text(font, "\u25BC", x + width - 10, y + height - 4, GOLD, false);
		}
	}

	private void bar(GuiGraphicsExtractor g, int x, int y, int width, double part, int color) {
		g.fill(x, y, x + width, y + 6, 0xFF0C0C12);
		g.fill(x + 1, y + 1, x + 1 + (int) Math.round((width - 2) * Math.max(0.0, Math.min(1.0, part))), y + 5, color);
		g.outline(x, y, width, 6, 0xFF4A4A56);
	}

	private String fit(String text, int width) {
		String out = text;
		while (font.width(out) > width && out.length() > 4) {
			out = out.substring(0, out.length() - 1);
		}
		return out.equals(text) ? out : out + "..";
	}

	/**
	 * A city as a sheet, not as a page of sentences: what it makes a turn as six numbers with their pictures; how
	 * near it is to growing, to finishing what it builds and to widening its border as three bars with the turns
	 * left; its walls; what is lined up; and what stands in it as a list with a picture and a line on each. The
	 * rows are the game's own (see the engine's "citysheet"): type, group, id, turns, name, with bars between.
	 */
	private void cityPage(GuiGraphicsExtractor g, Net.Menu menu) {
		List<String[]> yields = new ArrayList<>();
		List<String[]> queue = new ArrayList<>();
		List<String[]> great = new ArrayList<>();
		List<String[]> buildings = new ArrayList<>();
		List<String[]> happy = new ArrayList<>();
		Map<String, String[]> one = new HashMap<>();
		for (Net.Line line : menu.lines()) {
			String[] row = line.text().split("\\|", 5);
			if (row.length < 5) {
				continue;
			}
			switch (row[0]) {
				case "YIELD" -> yields.add(row);
				case "QUEUE" -> queue.add(row);
				case "GREAT" -> great.add(row);
				case "BUILDING" -> buildings.add(row);
				case "HAPPY" -> happy.add(row);
				default -> one.put(row[0], row);
			}
		}
		int width = Math.min(440, w - 40);
		int x = (w - width) / 2;
		int y = 36;
		int buttonRows = (menu.buttons().size() + 1) / 2;
		int fixed = 17 + 32 + 36 + 14 + (queue.isEmpty() ? 0 : 12) + great.size() * 11 + (happy.isEmpty() ? 0 : 12) + 14 + buttonRows * 18 + 6;
		int listRows = (buildings.size() + 1) / 2;
		int room = Math.max(1, (h - 30 - y - fixed) / 21);
		int shownRows = Math.min(listRows, room);
		RtsClient.menuScroll = Math.max(0, Math.min(listRows - shownRows, RtsClient.menuScroll));
		int height = fixed + shownRows * 21;
		pageFrame(g, menu, x, y, width, height);
		int cy = y + 18;

		// What it makes a turn.
		int chip = (width - 12) / Math.max(1, yields.size());
		for (int i = 0; i < yields.size(); i++) {
			String[] row = yields.get(i);
			int tenths = Integer.parseInt(row[2]);
			boolean net = row[4].equals("Food") || row[4].equals("Gold");
			String number = (net && tenths > 0 ? "+" : "") + (tenths % 10 == 0 ? Integer.toString(tenths / 10) : String.format(Locale.ROOT, "%.1f", tenths / 10.0));
			int color = WHITE;
			String picture = "paper";
			for (Rich.Measure measure : Rich.MEASURES) {
				if (measure.word().equals(row[4])) {
					color = measure.color();
					picture = measure.picture();
				}
			}
			int cx = x + 6 + i * chip;
			g.fill(cx, cy, cx + chip - 3, cy + 28, 0xFF1A1A22);
			item(g, icon(picture), cx + 3, cy + 1, 1f);
			g.text(font, number, cx + 22, cy + 5, tenths < 0 ? RED : color, true);
			g.text(font, fit(row[4], chip - 8), cx + 4, cy + 18, DIM, false);
		}
		cy += 32;

		// Growing, building, widening: three bars and the turns left.
		int third = (width - 12) / 3;
		String[] grow = one.get("GROW");
		String[] people = one.get("PEOPLE");
		String[] build = one.get("BUILD");
		String[] border = one.get("BORDER");
		if (grow != null) {
			int turns = Integer.parseInt(grow[3]);
			int needed = Integer.parseInt(grow[1]);
			int stored = Integer.parseInt(grow[2]);
			int cx = x + 6;
			g.text(font, turns > 0 ? "Grows in " + turns + (turns == 1 ? " turn" : " turns") : turns < 0 ? "Starves in " + -turns + (-turns == 1 ? " turn" : " turns") : "Not growing", cx, cy,
				turns > 0 ? GREEN : turns < 0 ? RED : DIM, false);
			bar(g, cx, cy + 11, third - 6, needed <= 0 ? 0 : stored / (double) needed, turns < 0 ? RED : 0xFF7FE060);
			String under = stored + "/" + needed + " food";
			if (people != null) {
				int idle = Integer.parseInt(people[1]);
				under = people[2] + (people[2].equals("1") ? " citizen" : " citizens") + (idle > 0 ? ", " + idle + " idle" : "");
			}
			g.text(font, fit(under, third - 6), cx, cy + 20, DIM, false);
		}
		if (build != null) {
			int cost = Integer.parseInt(build[1]);
			int done = Integer.parseInt(build[2]);
			int turns = Integer.parseInt(build[3]);
			int cx = x + 6 + third;
			boolean nothing = build[4].isEmpty();
			g.text(font, nothing ? "Nothing is being built" : fit(build[4], third - 6), cx, cy, nothing ? RED : WHITE, false);
			bar(g, cx, cy + 11, third - 6, cost <= 0 ? 0 : done / (double) cost, 0xFFFFA040);
			g.text(font, nothing ? "Choose under 'Back to the city'" : cost <= 0 ? "for as long as it stands" : turns + (turns == 1 ? " turn left" : " turns left") + "   " + done + "/" + cost, cx, cy + 20,
				nothing ? DIM : turns > 0 ? GOLD : DIM, false);
		}
		if (border != null) {
			int needed = Integer.parseInt(border[1]);
			int stored = Integer.parseInt(border[2]);
			int turns = Integer.parseInt(border[3]);
			int cx = x + 6 + third * 2;
			g.text(font, turns > 0 ? "Border grows in " + turns : "Border stands still", cx, cy, turns > 0 ? 0xFFE28BFF : DIM, false);
			bar(g, cx, cy + 11, third - 6, needed <= 0 ? 0 : stored / (double) needed, 0xFFE28BFF);
			g.text(font, turns > 0 ? stored + "/" + needed + " culture" : "it makes no culture", cx, cy + 20, DIM, false);
		}
		cy += 36;

		// Its walls, and what its people believe.
		String[] defence = one.get("DEFENCE");
		if (defence != null) {
			int most = Integer.parseInt(defence[1]);
			int health = Integer.parseInt(defence[2]);
			item(g, icon("shield"), x + 6, cy - 2, 0.6f);
			int tx = x + 18;
			g.text(font, defence[3] + " strength", tx, cy, 0xFFFFB0A0, false);
			tx += font.width(defence[3] + " strength") + 8;
			bar(g, tx, cy + 1, 70, most <= 0 ? 0 : health / (double) most, health * 2 < most ? RED : GREEN);
			g.text(font, health + "/" + most, tx + 74, cy, DIM, false);
			if (defence[4].equals("fire")) {
				g.text(font, "can fire", tx + 80 + font.width(health + "/" + most), cy, GOLD, false);
			}
		}
		String[] faith = one.get("FAITH");
		if (faith != null) {
			String said = faith[4].isEmpty() ? "No religion" : faith[4] + " " + faith[2] + "/" + faith[1];
			item(g, icon("feather"), x + width - 18 - font.width(said), cy - 2, 0.6f);
			g.text(font, said, x + width - 6 - font.width(said), cy, faith[4].isEmpty() ? DIM : 0xFFF4F0DC, false);
		}
		cy += 14;
		if (!queue.isEmpty()) {
			StringBuilder next = new StringBuilder();
			for (String[] row : queue) {
				next.append(next.length() == 0 ? "" : "   ").append(row[2]).append(". ").append(row[4]).append(row[3].equals("0") ? "" : " (" + row[3] + ")");
			}
			g.text(font, "NEXT", x + 6, cy, DIM, false);
			g.text(font, fit(next.toString(), width - 44), x + 34, cy, 0xFFE4E4EC, false);
			cy += 12;
		}
		for (String[] row : great) {
			int needed = Integer.parseInt(row[1]);
			int have = Integer.parseInt(row[2]);
			g.text(font, fit(row[4], 110), x + 6, cy, 0xFFE4E4EC, false);
			bar(g, x + 122, cy + 1, 120, needed <= 0 ? 0 : have / (double) needed, GOLD);
			g.text(font, "+" + row[3] + " a turn   " + have + "/" + needed, x + 248, cy, DIM, false);
			cy += 11;
		}
		if (!happy.isEmpty()) {
			int hx = x + 6;
			item(g, icon("sunflower"), hx, cy - 2, 0.6f);
			hx += 12;
			for (String[] row : happy) {
				int faces = Integer.parseInt(row[3]);
				String said = (faces > 0 ? "+" : "") + faces + " " + row[4];
				if (hx + font.width(said) > x + width - 8) {
					break;
				}
				g.text(font, said, hx, cy, faces < 0 ? RED : 0xFFFFE860, false);
				hx += font.width(said) + 10;
			}
			cy += 12;
		}

		// What stands in it.
		g.fill(x + 1, cy, x + width - 1, cy + 12, 0xFF23232D);
		g.text(font, "STANDS IN THE CITY  ·  " + buildings.size(), x + 6, cy + 2, GOLD, false);
		cy += 14;
		int cell = (width - 12) / 2;
		for (int row = 0; row < shownRows; row++) {
			for (int col = 0; col < 2; col++) {
				int index = (RtsClient.menuScroll + row) * 2 + col;
				if (index >= buildings.size()) {
					break;
				}
				String[] building = buildings.get(index);
				String[] parts = building[4].split("\\|", 2);
				boolean wonder = !building[1].equals("0");
				int cx = x + 6 + col * cell;
				int by = cy + row * 21;
				g.fill(cx, by, cx + cell - 3, by + 20, row % 2 == 0 ? 0xFF1A1A22 : 0xFF16161C);
				String picture = Rich.picture((wonder ? "Wonder: " : "Building: ") + parts[0]);
				if (picture != null) {
					item(g, icon(picture), cx + 2, by + 2, 1f);
				}
				g.text(font, fit(parts[0], cell - 50), cx + 21, by + 2, wonder ? GOLD : WHITE, false);
				int upkeep = Integer.parseInt(building[2]);
				if (upkeep > 0) {
					String costs = "-" + upkeep;
					item(g, icon("gold_ingot"), cx + cell - 14, by + 1, 0.5f);
					g.text(font, costs, cx + cell - 16 - font.width(costs), by + 2, 0xFFFF9A8A, false);
				}
				String does = parts.length > 1 ? parts[1] : "";
				if (!does.isEmpty()) {
					List<List<String>> brief = Rich.brief(font, does.replace("; ", "\n").replace(", ", "\n"), cell - 28, 1);
					if (!brief.isEmpty()) {
						rich(g, Rich.pieces(brief.get(0), false, 0xFFB8B8C4, true), cx + 21, by + 11, false);
					}
					zones.add(new Zone(cx, by, cell - 3, 20, parts[0] + "\n" + does.replace("; ", "\n") + (upkeep > 0 ? "\nUpkeep " + upkeep + " Gold" : ""), () -> { }));
				}
			}
		}
		if (RtsClient.menuScroll > 0) {
			g.text(font, "\u25B2", x + width - 10, cy - 12, GOLD, false);
		}
		if (RtsClient.menuScroll + shownRows < listRows) {
			g.text(font, "\u25BC", x + width - 10, cy + shownRows * 21 - 8, GOLD, false);
		}
		cy += shownRows * 21 + 2;
		int gap = 4;
		int buttonWidth = (width - gap * 3) / 2;
		for (int i = 0; i < menu.buttons().size(); i++) {
			pageButton(g, menu, menu.buttons().get(i), x + gap + (i % 2) * (buttonWidth + gap), cy + (i / 2) * 18, buttonWidth, 0xFF1C1C24);
		}
	}

	/**
	 * The technologies as a tree, columns from the oldest to the newest, a line from each to what
	 * it builds on. A click on one sets the scholars to work towards it, by way of everything it
	 * needs first.
	 */
	private void techPanel(GuiGraphicsExtractor g) {
		List<Records.Option> techs = RtsClient.techs;
		techBox[2] = 0;
		techBox[3] = 0;
		if (techs == null || techs.isEmpty()) {
			return;
		}
		int x = 12;
		int y = 32;
		int width = w - 24;
		int height = h - 44;
		int maxRow = 1;
		int maxColumn = 0;
		int longest = 0;
		Map<String, Records.Option> byName = new HashMap<>();
		for (Records.Option tech : techs) {
			maxRow = Math.max(maxRow, tech.id());
			maxColumn = Math.max(maxColumn, tech.group());
			longest = Math.max(longest, font.width(tech.name()));
			byName.put(tech.name(), tech);
		}
		// Every name written out in full: the boxes are as wide as the longest of them needs, with room for the turns it takes.
		int cellWidth = Math.max(104, 22 + longest + 4 + font.width("999") + 4);
		int pitchX = cellWidth + 18;
		Map<String, String> brings = RtsClient.about.getOrDefault("research", Map.of());
		int pitchY = Math.max(20, Math.min(30, (height - 36) / Math.max(1, maxRow)));
		// With room for it, a second line in every box: small pictures of what the technology opens.
		boolean tall = pitchY >= 28;
		int cellHeight = tall ? 26 : 18;
		int limit = Math.max(0, (maxColumn + 1) * pitchX + 10 - width);
		if (RtsClient.techScroll < 0) {
			// To begin with: the column of what is being researched, or of the first thing still open, a little in from the left.
			int first = maxColumn;
			for (Records.Option tech : techs) {
				String state = tech.type().split("\\|", -1)[1];
				if (!state.equals("known")) {
					first = Math.min(first, tech.group());
				}
			}
			RtsClient.techScroll = Math.max(0, first * pitchX - pitchX);
		}
		RtsClient.techScroll = Math.min(limit, RtsClient.techScroll);
		int scroll = RtsClient.techScroll;
		panel(g, x, y, width, height);
		g.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xF0101016);
		techBox[0] = x;
		techBox[1] = y;
		techBox[2] = width;
		techBox[3] = height;
		g.fill(x + 1, y + 1, x + width - 1, y + 14, 0xFF2A2A34);
		g.text(font, "TECHNOLOGIES", x + 6, y + 4, GOLD, true);
		String hint = "Wheel: older and newer. Click: research towards it.";
		g.text(font, hint, x + width - 22 - font.width(hint), y + 4, DIM, false);
		boolean overClose = mx >= x + width - 14 && mx < x + width - 1 && my >= y + 1 && my < y + 14;
		g.text(font, "x", x + width - 10, y + 3, overClose ? WHITE : DIM, false);
		zones.add(new Zone(x + width - 14, y + 1, 13, 13, "Close (Esc)", RtsClient::closeTechs));
		clipOn(g, x + 2, y + 16, x + width - 2, y + height - 2);
		int left = x + 8 - scroll;
		int topY = y + 34;
		// The name of each era over the first of its columns, and a faint line where it begins.
		String[] eras = new String[maxColumn + 1];
		for (Records.Option tech : techs) {
			String[] parts = tech.type().split("\\|", -1);
			if (parts.length > 3 && tech.group() >= 0 && tech.group() <= maxColumn) {
				eras[tech.group()] = parts[3];
			}
		}
		String before = "";
		for (int column = 0; column <= maxColumn; column++) {
			if (eras[column] == null || eras[column].equals(before)) {
				continue;
			}
			before = eras[column];
			int ex = left + column * pitchX;
			if (column > 0) {
				g.fill(ex - 10, y + 18, ex - 9, y + height - 4, 0x50FFFFFF);
			}
			g.text(font, before.toUpperCase(Locale.ROOT), ex, y + 20, GOLD, false);
		}
		// The lines first, so that the boxes lie over their ends.
		for (Records.Option tech : techs) {
			String[] parts = tech.type().split("\\|", -1);
			if (parts.length < 3 || parts[2].isEmpty()) {
				continue;
			}
			int tx = left + tech.group() * pitchX;
			int ty = topY + (tech.id() - 1) * pitchY + cellHeight / 2;
			for (String needs : parts[2].split(";")) {
				Records.Option from = byName.get(needs);
				if (from == null) {
					continue;
				}
				int fx = left + from.group() * pitchX + cellWidth;
				int fy = topY + (from.id() - 1) * pitchY + cellHeight / 2;
				if (Math.max(fx, tx) < x || Math.min(fx, tx) > x + width) {
					continue;
				}
				boolean known = from.type().split("\\|", -1)[1].equals("known");
				line(g, fx, fy, tx, ty, known ? 0xC060C070 : 0xA0707080, 1);
			}
		}
		for (Records.Option tech : techs) {
			String[] parts = tech.type().split("\\|", -1);
			String state = parts.length > 1 ? parts[1] : "far";
			int tx = left + tech.group() * pitchX;
			int ty = topY + (tech.id() - 1) * pitchY;
			if (tx + cellWidth < x || tx > x + width) {
				continue;
			}
			boolean over = mx >= tx && mx < tx + cellWidth && my >= ty && my < ty + cellHeight && my > y + 16;
			int fill = switch (state) {
				case "known" -> 0xFF2C5E36;
				case "now" -> 0xFF2B5FA8;
				case "queued" -> 0xFF34476E;
				case "open" -> 0xFF4C4C58;
				default -> 0xFF25252B;
			};
			g.fill(tx, ty, tx + cellWidth, ty + cellHeight, over && !state.equals("known") ? mix(fill, WHITE, 0.2f) : fill);
			g.outline(tx, ty, cellWidth, cellHeight, state.equals("now") ? GOLD : state.equals("open") || over ? WHITE : 0xFF55555F);
			item(g, icon(Looks.choiceIcon("research", 0, parts[0], tech.name())), tx + 1, ty + (cellHeight - 16) / 2, 1f);
			if (tall) {
				int ux = tx + 19;
				for (String line : brings.getOrDefault(parts[0], "").split("\n")) {
					String picture = Rich.picture(line);
					if (picture != null && ux + 9 <= tx + cellWidth - 2) {
						item(g, icon(picture), ux, ty + 14, 0.5f);
						ux += 9;
					}
				}
			}
			String right = state.equals("known") ? "" : tech.turns() >= 999 ? "" : Integer.toString(tech.turns());
			String name = tech.name();
			int space = cellWidth - 22 - font.width(right) - 4;
			while (font.width(name) > space && name.length() > 3) {
				name = name.substring(0, name.length() - 1);
			}
			g.text(font, name, tx + 19, ty + (tall ? 3 : 5), state.equals("far") ? 0xFF9A9AA4 : WHITE, false);
			g.text(font, right, tx + cellWidth - 3 - font.width(right), ty + (tall ? 3 : 5), state.equals("now") ? GOLD : DIM, false);
			if (my > y + 16) {
				String what = switch (state) {
					case "known" -> tech.name() + ": your scholars know it";
					case "now" -> tech.name() + ": being researched, " + tech.turns() + " turns to go";
					case "queued" -> tech.name() + ": next in line";
					case "open" -> "Research " + tech.name() + " (" + tech.turns() + " turns)";
					default -> "Research towards " + tech.name() + ", by way of what it builds on";
				};
				String gives = brings.getOrDefault(parts[0], "");
				zones.add(new Zone(tx, ty, cellWidth, cellHeight, what + (gives.isEmpty() ? "" : "\n" + gives), () -> {
					if (!state.equals("known")) {
						RtsClient.act("choose", 0, 0, tech.id(), tech.turns(), "research\n" + parts[0] + "\n" + tech.name());
						RtsClient.techsChanged();
					}
				}));
			}
		}
		clipOff(g);
	}

	/** The small square that says how near a policy is: adopted, to be chosen now, next in reach, not yet. */
	private void mark(GuiGraphicsExtractor g, int x, int y, String state) {
		switch (state) {
			case "adopted" -> {
				g.fill(x, y, x + 8, y + 8, 0xFF4CB05A);
				g.outline(x, y, 8, 8, 0xFFA8E8A8);
			}
			case "open" -> {
				g.fill(x, y, x + 8, y + 8, GOLD);
				g.outline(x, y, 8, 8, (RtsClient.ticks / 8) % 2 == 0 ? WHITE : 0xFFB08A20);
			}
			case "near" -> {
				g.fill(x, y, x + 8, y + 8, 0xFF2A2618);
				g.outline(x, y, 8, 8, GOLD);
			}
			default -> {
				g.fill(x, y, x + 8, y + 8, 0xFF1C1C22);
				g.outline(x, y, 8, 8, 0xFF5A5A64);
			}
		}
	}

	/** Puts a policy and after it all that builds on it in the order they are read, each a step further in. */
	private static void under(int node, int level, int[] parent, int[] depth, List<Integer> order) {
		order.add(node);
		depth[node] = level;
		for (int i = 0; i < parent.length && level < 8; i++) {
			if (parent[i] == node) {
				under(i, level + 1, parent, depth, order);
			}
		}
	}

	/**
	 * The social policies, every branch a card and in it its policies as a tree: each stands under
	 * the one it builds on, a step in, with a line from the one to the other, so that what leads
	 * to what is seen and need not be read. A square says how near each is. What a policy gives
	 * stands under its name in brief, numbers and pictures first, in the branches of the player's
	 * own age; the card by the pointer has all of it, always. A click adopts.
	 */
	private void policyPanel(GuiGraphicsExtractor g, WorldState state) {
		List<Records.Option> all = RtsClient.policies;
		if (all == null || all.isEmpty()) {
			return;
		}
		Empire empire = state.empire();
		Map<String, String> gives = RtsClient.about.getOrDefault("policy", Map.of());
		int x = 12;
		int y = 20;
		int width = w - 24;
		int height = h - 28;
		panel(g, x, y, width, height);
		g.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xF0101016);
		g.fill(x + 1, y + 1, x + width - 1, y + 14, 0xFF2A2A34);
		g.text(font, "SOCIAL POLICIES", x + 6, y + 4, GOLD, true);
		boolean due = empire != null && empire.blocking().equals("POLICY");
		// How far the next one is: the culture gathered as a bar, and the turns it still takes.
		int right = x + width - 20;
		if (due) {
			String call = "Choose one: the lit ones can be had";
			g.text(font, call, right - font.width(call), y + 4, (RtsClient.ticks / 8) % 2 == 0 ? GOLD : WHITE, false);
		} else if (empire != null) {
			int perTurn = empire.culturePerTurn();
			int wanting = Math.max(0, empire.policyCost() - empire.culture());
			String when = perTurn > 0 ? "next in " + Math.max(1, (wanting + perTurn - 1) / perTurn) + " turns" : "no culture comes in";
			right -= font.width(when);
			g.text(font, when, right, y + 4, DIM, false);
			right -= 66;
			g.fill(right, y + 5, right + 60, y + 10, 0xFF16161C);
			g.fill(right, y + 5, right + Math.min(60, 60 * empire.culture() / Math.max(1, empire.policyCost())), y + 10, Rich.CULTURE.color());
			String numbers = empire.culture() + "/" + empire.policyCost();
			right -= font.width(numbers) + 5;
			g.text(font, numbers, right, y + 4, Rich.CULTURE.color(), false);
			item(g, icon(Rich.CULTURE.picture()), right - 10, y + 3, 0.5f);
			zones.add(new Zone(right - 10, y + 1, x + width - 20 - right + 10, 13, "Culture towards the next policy\n" + signed(perTurn) + " Culture a turn", () -> { }));
		}
		boolean overClose = mx >= x + width - 14 && mx < x + width - 1 && my >= y + 1 && my < y + 14;
		g.text(font, "x", x + width - 10, y + 3, overClose ? WHITE : DIM, false);
		zones.add(new Zone(x + width - 14, y + 1, 13, 13, "Close (Esc)", RtsClient::closePolicies));
		// What the squares and the lines say, along the bottom.
		int legendY = y + height - 11;
		int legendX = x + 8;
		String[][] legend = { { "adopted", "adopted" }, { "open", "choose now" }, { "near", "next in reach" }, { "far", "not yet" } };
		for (String[] entry : legend) {
			mark(g, legendX, legendY, entry[0]);
			g.text(font, entry[1], legendX + 11, legendY, DIM, false);
			legendX += 11 + font.width(entry[1]) + 12;
		}
		String flow = "A line leads from a policy to what it opens";
		g.text(font, flow, x + width - 8 - font.width(flow), legendY, 0xFF80808A, false);
		int branches = 0;
		for (Records.Option row : all) {
			branches = Math.max(branches, row.group() + 1);
		}
		int columns = Math.max(1, (branches + 1) / 2);
		int gap = 4;
		int cardWidth = (width - gap * (columns + 1)) / columns;
		int cardHeight = (height - 18 - 12 - gap * 3) / 2;
		int headHeight = 26;
		for (int branch = 0; branch < branches; branch++) {
			int cx = x + gap + (branch % columns) * (cardWidth + gap);
			int cy = y + 18 + gap + (branch / columns) * (cardHeight + gap);
			List<Records.Option> members = new ArrayList<>();
			Records.Option head = null;
			for (Records.Option row : all) {
				if (row.group() != branch) {
					continue;
				}
				if (row.id() == 0) {
					head = row;
				} else {
					members.add(row);
				}
			}
			if (head == null) {
				continue;
			}
			members.sort((one, other) -> Integer.compare(one.id(), other.id()));
			String[] headParts = head.type().split("\\|", -1);
			String headState = headParts.length > 1 ? headParts[1] : "far";
			boolean opened = headState.equals("adopted");
			boolean toOpen = headState.equals("open");
			// A branch of the player's own age says what its policies give; one of a later age only what they are called.
			boolean lit = !headState.equals("far");
			g.fill(cx, cy, cx + cardWidth, cy + cardHeight, opened ? 0xFF16221A : 0xFF16161C);
			g.outline(cx, cy, cardWidth, cardHeight, opened ? 0xFF4C8A5A : toOpen ? GOLD : 0xFF4A4A54);
			// The branch itself: its name, how much of it is adopted, and what opening it gives.
			boolean overHead = mx >= cx && mx < cx + cardWidth && my >= cy && my < cy + headHeight;
			int headFill = opened ? 0xFF2C5E36 : toOpen ? (overHead ? 0xFFFFE08A : 0xFFE0B040) : 0xFF2A2A34;
			g.fill(cx + 1, cy + 1, cx + cardWidth - 1, cy + headHeight, headFill);
			int ink = toOpen ? 0xFF1A1206 : lit ? WHITE : 0xFFB0B0BC;
			g.text(font, head.name().toUpperCase(Locale.ROOT), cx + 5, cy + 4, ink, !toOpen);
			int had = 0;
			int inAll = 0;
			for (Records.Option policy : members) {
				if (policy.id() != 99) {
					inAll++;
					had += policy.type().split("\\|", -1).length > 1 && policy.type().split("\\|", -1)[1].equals("adopted") ? 1 : 0;
				}
			}
			String tag = opened ? had + "/" + inAll : toOpen ? "OPEN IT" : "";
			g.text(font, tag, cx + cardWidth - 5 - font.width(tag), cy + 4, toOpen ? 0xFF3A2A06 : had == inAll ? 0xFF9FE08A : 0xFFCFEFD4, false);
			String era = headParts.length > 4 ? headParts[4] : "";
			String headKey = headParts[0];
			String headGives = gives.getOrDefault(headKey, "");
			List<List<String>> opening = lit && !headGives.isEmpty() ? Rich.brief(font, headGives, cardWidth - 10, 1) : List.of();
			if (!opening.isEmpty()) {
				rich(g, Rich.pieces(opening.get(0), false, toOpen ? 0xFF3A2A06 : opened ? 0xFFB8E0C0 : 0xFFB8C0D0, !toOpen), cx + 5, cy + 15, false);
			} else {
				g.text(font, lit ? "" : era.isEmpty() ? "closed" : "from the " + era, cx + 5, cy + 15, DIM, false);
			}
			String headName = head.name();
			zones.add(new Zone(cx, cy, cardWidth, headHeight, headName + ": " + (opened ? "this branch is open" : toOpen ? "open this branch (uses the policy you have earned)"
				: headState.equals("near") ? "can be opened with your next policy" : "not open to you yet" + (era.isEmpty() ? "" : " (" + era + ")")) + (headGives.isEmpty() ? "" : "\n" + headGives), () -> {
					if (toOpen) {
						RtsClient.act("choose", 0, 5, 0, 0, "policy\n" + headKey + "\n" + headName);
						RtsClient.policiesChanged();
					}
				}));
			// Each policy under the one it builds on (under the later of them, when it builds on two); what completing the branch gives last.
			int count = members.size();
			Map<String, Integer> place = new HashMap<>();
			for (int i = 0; i < count; i++) {
				place.put(members.get(i).name(), i);
			}
			int[] parent = new int[count];
			int[] depth = new int[count];
			String[] states = new String[count];
			for (int i = 0; i < count; i++) {
				String[] parts = members.get(i).type().split("\\|", -1);
				states[i] = parts.length > 1 ? parts[1] : "far";
				parent[i] = -1;
				if (members.get(i).id() != 99 && parts.length > 3) {
					for (String needs : parts[3].split(";")) {
						Integer at = place.get(needs);
						if (at != null && at != i && members.get(at).id() != 99) {
							parent[i] = Math.max(parent[i], at);
						}
					}
				}
			}
			List<Integer> order = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				if (parent[i] < 0 && members.get(i).id() != 99) {
					under(i, 0, parent, depth, order);
				}
			}
			for (int i = 0; i < count; i++) {
				if (!order.contains(i)) {
					order.add(i);
				}
			}
			int bodyTop = cy + headHeight + 3;
			int rowHeight = Math.max(11, Math.min(26, (cy + cardHeight - 2 - bodyTop) / Math.max(1, count)));
			// One line under a name and no more: the first of what it gives. A list of names with a number each reads; a page of sentences does not.
			int briefRows = lit && rowHeight >= 20 ? 1 : 0;
			int[] rowY = new int[count];
			for (int k = 0; k < count; k++) {
				rowY[order.get(k)] = bodyTop + k * rowHeight;
			}
			// The lines first: down from a policy and across to what builds on it, green where the way is open.
			for (int i = 0; i < count; i++) {
				int from = parent[i];
				if (from < 0) {
					continue;
				}
				int lineX = cx + 6 + depth[from] * 10 + 3;
				int toY = rowY[i] + 5;
				int color = states[from].equals("adopted") ? 0xFF4CB05A : 0xFF5A5A64;
				g.fill(lineX, rowY[from] + 9, lineX + 2, toY + 1, color);
				g.fill(lineX, toY - 1, cx + 6 + depth[i] * 10, toY + 1, color);
			}
			for (int i = 0; i < count; i++) {
				Records.Option policy = members.get(i);
				String[] parts = policy.type().split("\\|", -1);
				String policyState = states[i];
				int ry = rowY[i];
				boolean last = policy.id() == 99;
				int nodeX = cx + 6 + depth[i] * 10;
				int textX = nodeX + 12;
				boolean over = mx >= cx + 2 && mx < cx + cardWidth - 2 && my >= ry && my < ry + rowHeight;
				boolean can = policyState.equals("open");
				boolean has = policyState.equals("adopted");
				boolean soon = policyState.equals("near");
				if (can) {
					g.fill(nodeX - 2, ry, cx + cardWidth - 2, ry + rowHeight - 1, over ? 0xFF6A5A2A : 0xFF3E3620);
				} else if (over) {
					g.fill(nodeX - 2, ry, cx + cardWidth - 2, ry + rowHeight - 1, 0xFF22222A);
				}
				if (last) {
					g.fill(cx + 4, ry - 2, cx + cardWidth - 4, ry - 1, 0xFF3A3A44);
				}
				mark(g, nodeX, ry + 1, policyState);
				String name = last ? "Complete" : policy.name();
				g.text(font, name, textX, ry + 1, has ? 0xFFCFEFD4 : can ? WHITE : soon ? 0xFFD0D0DA : lit ? 0xFF9A9AA6 : 0xFF80808A, false);
				String key = parts[0];
				String text = gives.getOrDefault(key, "");
				if (briefRows > 0 && !text.isEmpty()) {
					List<List<String>> brief = Rich.brief(font, text, cardWidth - (textX - cx) - 4, briefRows);
					for (int r = 0; r < brief.size(); r++) {
						rich(g, Rich.pieces(brief.get(r), false, has ? 0xFF8FB89A : can ? 0xFFD0C8A8 : soon ? 0xFF8C9CB4 : 0xFF6C6C78, has || can || soon), textX, ry + 11 + r * 9, false);
					}
				}
				String needs = parts.length > 3 && !parts[3].isEmpty() ? parts[3].replace(";", " and ") : "";
				String policyName = policy.name();
				String why = has ? "adopted" : can ? "click to adopt (uses the policy you have earned)" : soon ? "can be adopted with your next policy"
					: last ? "yours when every policy of the branch is adopted" : "needs " + (needs.isEmpty() ? "its branch opened" : needs) + " first";
				zones.add(new Zone(cx + 2, ry, cardWidth - 4, rowHeight, policyName + ": " + why + (text.isEmpty() ? "" : "\n" + text), () -> {
					if (can) {
						RtsClient.act("choose", 0, 6, 0, 0, "policy\n" + key + "\n" + policyName);
						RtsClient.policiesChanged();
					}
				}));
			}
		}
	}

	/** While the land is still being put down (a new game, a part of the map never looked at): say so, or the empty view looks broken. */
	private void painting(GuiGraphicsExtractor g) {
		int left = RtsClient.view.painting;
		if (left <= 0) {
			return;
		}
		String text = "Drawing the land" + ".".repeat((int) (RtsClient.ticks / 10 % 4)) + "   " + left + (left == 1 ? " piece" : " pieces") + " to go";
		int width = font.width("Drawing the land...   0000 pieces to go") + 16;
		int x = (w - width) / 2;
		int y = h - 30;
		g.fill(x, y, x + width, y + 16, 0xE0101018);
		g.outline(x, y, width, 16, GOLD);
		g.text(font, text, x + 8, y + 4, WHITE, false);
	}

	private UnitState nextWaiting(WorldState state) {
		int me = state.session().viewer();
		UnitState first = null;
		boolean passed = RtsClient.view.selected == null;
		for (UnitState unit : state.units().values()) {
			if (unit.key().owner() != me || !unit.waiting()) {
				continue;
			}
			if (first == null) {
				first = unit;
			}
			if (passed && !unit.key().equals(RtsClient.view.selected)) {
				return unit;
			}
			if (unit.key().equals(RtsClient.view.selected)) {
				passed = true;
			}
		}
		return first != null && !first.key().equals(RtsClient.view.selected) ? first : first;
	}

	/** What the big button says and does, the way Civilization's does: whatever the game needs next, and the next turn when it needs nothing. */
	private String turnLabel(WorldState state) {
		Empire empire = state.empire();
		if (!RtsClient.view.myTurn) {
			String wait = RtsClient.view.wait;
			return wait.equals("watching") ? "LOOKING ON" : wait.equals("ended") ? "WAITING FOR THE OTHERS" : wait.isEmpty() ? "PLEASE WAIT" : wait.toUpperCase(Locale.ROOT) + " MOVES FIRST";
		}
		String blocking = empire == null ? "" : empire.blocking();
		return switch (blocking) {
			case "POLICY" -> "ADOPT A POLICY";
			case "RESEARCH" -> "CHOOSE RESEARCH";
			case "PRODUCTION" -> "CHOOSE PRODUCTION";
			case "RELIGION" -> "CHOOSE A BELIEF";
			case "VOTE" -> "CAST YOUR VOTE";
			default -> nextWaiting(state) != null ? "UNIT NEEDS ORDERS" : "NEXT TURN";
		};
	}

	private void turn(WorldState state, boolean anyway) {
		Empire empire = state.empire();
		if (!RtsClient.view.myTurn) {
			return;
		}
		String blocking = empire == null ? "" : empire.blocking();
		switch (blocking) {
			case "POLICY" -> RtsClient.openPolicies();
			case "RESEARCH" -> RtsClient.ask("research", 0);
			case "PRODUCTION" -> {
				CityState idle = null;
				for (CityState city : state.cities().values()) {
					if (city.key().owner() == state.session().viewer() && city.productionName().isEmpty() && (city.flags() & CityState.F_PUPPET) == 0) {
						idle = city;
						break;
					}
				}
				if (idle == null) {
					RtsClient.act("open", 0, 0, 0, 0, "waiting");
				} else {
					openCity(idle);
				}
			}
			case "RELIGION" -> RtsClient.act("open", 0, 0, 0, 0, "beliefs");
			case "VOTE" -> RtsClient.act("open", 0, 0, 0, 0, "vote");
			default -> {
				UnitState waiting = anyway ? null : nextWaiting(state);
				if (waiting != null) {
					select(waiting.key());
					double[] spot = RtsClient.spots.get(waiting.key());
					if (spot != null) {
						RtsCamera.lookAt(spot[0], spot[2]);
					}
				} else {
					RtsClient.act("turn", 0, 0, 0, 0, "");
				}
			}
		}
	}

	private int plotColor(WorldState state, PlotCoord plot) {
		PlotState p = state.plot(plot);
		if (!p.revealed()) {
			return 0xFF16161C;
		}
		if (p.city()) {
			return WHITE;
		}
		String terrain = state.legend().type(Legend.Kind.TERRAIN, p.terrain());
		int color;
		if (p.water()) {
			color = terrain.contains("OCEAN") ? 0xFF27488F : 0xFF3A66C0;
		} else if (p.elevation() == PlotState.MOUNTAIN) {
			color = 0xFF7A7A80;
		} else {
			color = terrain.contains("DESERT") ? 0xFFD6C98A : terrain.contains("PLAINS") ? 0xFFA9A654 : terrain.contains("TUNDRA") ? 0xFF8F8A76
				: terrain.contains("SNOW") ? 0xFFEFF3F3 : 0xFF5E9B3A;
			if (p.feature() >= 0) {
				String feature = state.legend().type(Legend.Kind.FEATURE, p.feature());
				if (feature.contains("FOREST") || feature.contains("JUNGLE")) {
					color = mix(color, 0xFF1F5A1F, 0.5f);
				}
			}
			if (p.elevation() == PlotState.HILLS) {
				color = mix(color, 0xFF000000, 0.14f);
			}
		}
		CivInfo owner = p.owner() >= 0 ? state.civ(p.owner()) : null;
		return owner == null ? color : mix(color, 0xFF000000 | owner.color(), 0.5f);
	}

	private void turnAndMap(GuiGraphicsExtractor g, WorldState state, BoardModel model) {
		int cols = state.session().grid().width();
		int rows = state.session().grid().height();
		miniCell = Math.max(1.5f, Math.min(7f, Math.min(180f / (cols + 0.5f), 132f / rows)));
		miniW = Math.round((cols + 0.5f) * miniCell);
		miniH = Math.round(rows * miniCell);
		miniX = w - miniW - 5;
		miniY = h - miniH - 5;
		panel(g, miniX - 3, miniY - 3, miniW + 6, miniH + 6);
		g.fill(miniX, miniY, miniX + miniW, miniY + miniH, 0xFF0A0A10);
		MapTexture.draw(g, state, plot -> plotColor(state, plot), miniX, miniY, miniW, miniH);
		// The part of the land that is on the screen.
		float[][] corners = new float[4][2];
		double[][] screen = { { 0, 16 }, { w, 16 }, { w, h }, { 0, h } };
		for (int i = 0; i < 4; i++) {
			double[] ground = RtsCamera.ground(screen[i][0], screen[i][1], w, h);
			GridPos grid = model.layout().toGrid(ground[0], ground[1]);
			corners[i][0] = (float) (miniX + (grid.u() + 0.5) * miniCell);
			corners[i][1] = (float) (miniY + (rows - 0.5 - grid.v()) * miniCell);
		}
		clipOn(g, miniX - 2, miniY - 2, miniX + miniW + 2, miniY + miniH + 2);
		for (int i = 0; i < 4; i++) {
			line(g, corners[i][0], corners[i][1], corners[(i + 1) % 4][0], corners[(i + 1) % 4][1], WHITE, 1);
		}
		if (RtsClient.view.me != null) {
			// The player's own figure: a gold mark that blinks.
			GridPos own = model.layout().toGrid(model.centerX(RtsClient.view.me) + 0.5, model.centerZ(RtsClient.view.me) + 0.5);
			int ox = Math.round((float) (miniX + (own.u() + 0.5) * miniCell));
			int oy = Math.round((float) (miniY + (rows - 0.5 - own.v()) * miniCell));
			g.fill(ox - 3, oy - 3, ox + 3, oy + 3, (RtsClient.ticks / 10) % 2 == 0 ? WHITE : 0xFF1A1206);
			g.fill(ox - 2, oy - 2, ox + 2, oy + 2, GOLD);
		}
		clipOff(g);
		zones.add(new Zone(miniX - 3, miniY - 3, miniW + 6, miniH + 6, null, () -> {
			double u = (mx - miniX) / miniCell - 0.5;
			double v = rows - 0.5 - (my - miniY) / miniCell;
			GridPos pos = new GridPos(u, v);
			RtsCamera.lookAt(model.layout().x(pos), model.layout().z(pos));
		}));
		// The turn button, over the small map.
		String label = turnLabel(state);
		int width = Math.max(miniW + 6, font.width(label) + 20);
		int x = w - width - 2;
		int y = miniY - 3 - 22;
		boolean over = mx >= x && mx < x + width && my >= y && my < y + 20;
		boolean wait = !RtsClient.view.myTurn;
		boolean next = label.equals("NEXT TURN");
		int fill = wait ? 0xFF4A4A52 : next ? (over ? 0xFF7ED06A : 0xFF58B048) : (over ? 0xFFFFE08A : 0xFFE0B040);
		g.fill(x, y, x + width, y + 20, 0xFF1A1206);
		g.fill(x + 1, y + 1, x + width - 1, y + 19, fill);
		g.fill(x + 1, y + 1, x + width - 1, y + 3, mix(fill, WHITE, 0.35f));
		g.centeredText(font, label, x + width / 2, y + 6, wait ? DIM : 0xFF1A1206);
		String why = RtsClient.view.wait;
		String tip = wait ? (why.equals("watching") ? "You rule no civilization here. The Players button (L) lets you take one."
			: why.equals("ended") ? "You have ended your turn. The turn passes when everybody has."
			: why.isEmpty() ? "The other civilizations are moving" : "You are at war with " + why + ": the two of you move one after the other, and they come first.") : next ? "End your turn (Enter)" : label.equals("UNIT NEEDS ORDERS")
			? "Go to a unit that has nothing to do. Shift and click, or Shift and Enter, ends the turn anyway." : "The game needs this decided before the turn can end (Enter)";
		zones.add(new Zone(x, y, width, 20, tip, () -> turn(state, InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT))));
	}

	// ---- the mouse and the keys ----

	private void select(EntityKey key) {
		RtsClient.act("select", key.owner(), key.id(), 0, 0, "");
	}

	private void clickLand(WorldState state, PlotCoord plot) {
		if (plot == null) {
			return;
		}
		int me = state.session().viewer();
		List<UnitState> mine = new ArrayList<>();
		UnitState stranger = null;
		for (UnitState unit : state.units().values()) {
			if (unit.at().equals(plot)) {
				if (unit.key().owner() == me) {
					mine.add(unit);
				} else {
					stranger = unit;
				}
			}
		}
		if (!mine.isEmpty()) {
			// Several of one's own on a hex: each click takes the next.
			int index = 0;
			for (int i = 0; i < mine.size(); i++) {
				if (mine.get(i).key().equals(RtsClient.view.selected)) {
					index = (i + 1) % mine.size();
				}
			}
			select(mine.get(index).key());
			return;
		}
		CityState city = state.cityAt(plot);
		if (city != null) {
			if (city.key().owner() == me) {
				openCity(city);
			} else {
				RtsClient.act("open", city.key().owner(), city.key().id(), 0, 0, "city");
			}
			return;
		}
		if (stranger != null && RtsClient.view.selected == null) {
			CivInfo owner = state.civ(stranger.key().owner());
			if (owner != null && !owner.barbarian()) {
				RtsClient.act("open", owner.id(), 0, 0, 0, "civ");
			}
			return;
		}
		if (RtsClient.view.selected != null) {
			RtsClient.act("deselect", 0, 0, 0, 0, "");
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		try {
			return clicked(event, doubleClick);
		} catch (RuntimeException e) {
			RtsClient.failed("the view from above, at a click or a key", e);
			return true;
		}
	}

	private boolean clicked(MouseButtonEvent event, boolean doubleClick) {
		WorldState state = RtsClient.state;
		if (state == null || RtsClient.model == null) {
			return true;
		}
		double x = event.x() / k;
		double y = event.y() / k;
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			Zone zone = zoneAt(x, y);
			if (zone != null) {
				zone.click.run();
				return true;
			}
			if (onPanel(x, y)) {
				return true;
			}
			double[] ground = RtsCamera.ground(x, y, w, h);
			PlotCoord at = RtsClient.model.plotAt((int) Math.floor(ground[0]), (int) Math.floor(ground[1]));
			if (RtsClient.aiming >= 0) {
				// A city takes aim: a click on one of its targets fires, a click anywhere else holds fire.
				if (at != null && aimed(RtsClient.aiming).contains(at)) {
					RtsClient.act("bombard", RtsClient.aiming, 0, at.x(), at.y(), "");
				}
				RtsClient.aiming = -1;
				return true;
			}
			clickLand(state, at);
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			for (Zone zone : rightZones) {
				if (zone.has(x, y)) {
					zone.click.run();
					return true;
				}
			}
			if (onPanel(x, y)) {
				return true;
			}
			if (RtsClient.aiming >= 0) {
				RtsClient.aiming = -1;
				return true;
			}
			double[] ground = RtsCamera.ground(x, y, w, h);
			PlotCoord plot = RtsClient.model.plotAt((int) Math.floor(ground[0]), (int) Math.floor(ground[1]));
			if (plot != null && RtsClient.view.selected != null) {
				RtsClient.act("move", 0, 0, plot.x(), plot.y(), "");
			}
			return true;
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		try {
			return dragged(event, dx, dy);
		} catch (RuntimeException e) {
			RtsClient.failed("the view from above, at a click or a key", e);
			return false;
		}
	}

	private boolean dragged(MouseButtonEvent event, double dx, double dy) {
		if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
			// Dragging with the wheel pressed pulls the land along under the pointer.
			float aspect = width / (float) Math.max(1, height);
			RtsCamera.pan(-dx / width * 2.0 * RtsCamera.zoom * aspect, dy / height * 2.0 * RtsCamera.zoom / Math.sin(Math.toRadians(RtsCamera.PITCH)));
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		try {
			return scrolled(x, y, scrollX, scrollY);
		} catch (RuntimeException e) {
			RtsClient.failed("the view from above, at a click or a key", e);
			return true;
		}
	}

	private boolean scrolled(double x, double y, double scrollX, double scrollY) {
		double px = x / k;
		double py = y / k;
		if (RtsClient.menu != null && px >= menuBox[0] && py >= menuBox[1] && px < menuBox[0] + menuBox[2] && py < menuBox[1] + menuBox[3]) {
			RtsClient.menuScroll += scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
			return true;
		}
		if (RtsClient.techs != null && px >= techBox[0] && py >= techBox[1] && px < techBox[0] + techBox[2] && py < techBox[1] + techBox[3]) {
			RtsClient.techScroll = Math.max(0, RtsClient.techScroll + (scrollY > 0 ? -60 : scrollY < 0 ? 60 : 0));
			return true;
		}
		if (RtsClient.policies != null) {
			return true;
		}
		if (RtsClient.choices != null && px >= choiceBox[0] && py >= choiceBox[1] && px < choiceBox[0] + choiceBox[2] && py < choiceBox[1] + choiceBox[3]) {
			// Over the list the wheel moves the list.
			RtsClient.choicesScroll += scrollY > 0 ? -2 : scrollY < 0 ? 2 : 0;
			return true;
		}
		if (scrollY != 0) {
			RtsCamera.zoomBy(scrollY > 0 ? 0.87f : 1.15f);
		}
		return true;
	}

	/** Once a tick while this screen is up: the keys and the edges of the screen move the view. */
	void steer() {
		Minecraft mc = this.minecraft;
		if (!mc.isWindowActive()) {
			return;
		}
		double step = RtsCamera.zoom * 0.06;
		double right = 0;
		double up = 0;
		if (InputConstants.isKeyDown(InputConstants.KEY_W) || InputConstants.isKeyDown(InputConstants.KEY_UP)) {
			up += step;
		}
		if (InputConstants.isKeyDown(InputConstants.KEY_S) || InputConstants.isKeyDown(InputConstants.KEY_DOWN)) {
			up -= step;
		}
		if (InputConstants.isKeyDown(InputConstants.KEY_D) || InputConstants.isKeyDown(InputConstants.KEY_RIGHT)) {
			right += step;
		}
		if (InputConstants.isKeyDown(InputConstants.KEY_A) || InputConstants.isKeyDown(InputConstants.KEY_LEFT)) {
			right -= step;
		}
		// The pointer against an edge of the window pushes the view that way, as in any strategy game.
		double px = mc.mouseHandler.xpos();
		double py = mc.mouseHandler.ypos();
		int windowWidth = mc.getWindow().getWidth();
		int windowHeight = mc.getWindow().getHeight();
		// Only with the game filling the screen: in a window the pointer leaves, and the view would run on.
		if (edgePan && mc.options.fullscreen().get() && px >= 0 && py >= 0 && px <= windowWidth && py <= windowHeight && pointerInWindow(mc)) {
			if (px <= 1) {
				right -= step;
			} else if (px >= windowWidth - 2) {
				right += step;
			}
			if (py <= 1) {
				up += step;
			} else if (py >= windowHeight - 2) {
				up -= step;
			}
		}
		if (right != 0 || up != 0) {
			RtsCamera.pan(right, up / Math.sin(Math.toRadians(RtsCamera.PITCH)));
		}
	}

	/** Development aid: what this client holds for the column of blocks under the pointer. */
	private void probe() {
		double[] ground = RtsCamera.ground(mx, my, w, h);
		int x = (int) Math.floor(ground[0]);
		int z = (int) Math.floor(ground[1]);
		var level = this.minecraft.level;
		var chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
		StringBuilder out = new StringBuilder("PROBE client " + x + "," + z + " chunk " + (x >> 4) + "," + (z >> 4) + (chunk == null ? " NOT LOADED" : " loaded"));
		if (chunk != null) {
			for (int y : new int[] { 50, 60, 64, 67, 70 }) {
				var pos = new net.minecraft.core.BlockPos(x, y, z);
				out.append(" y").append(y).append('=').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos).getBlock()).getPath())
					.append("/sky").append(level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos));
			}
			for (int y : new int[] { 56, 72 }) {
				var section = this.minecraft.levelRenderer.viewArea().getRenderSectionAt(new net.minecraft.core.BlockPos(x, y, z));
				var mesh = section == null ? null : section.getSectionMesh();
				out.append(" section@").append(y).append('=').append(section == null ? "none"
					: (mesh == net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED ? "UNCOMPILED" : mesh == net.minecraft.client.renderer.chunk.CompiledSectionMesh.EMPTY ? "EMPTY"
						: "compiled") + (net.minecraft.core.SectionPos.x(section.getSectionNode()) == (x >> 4) && net.minecraft.core.SectionPos.z(section.getSectionNode()) == (z >> 4) ? "" : "/OTHER-PLACE")
					+ (this.minecraft.levelRenderer.sectionOcclusionGraph().getNode(section) == null ? "/no-node" : "/node"));
			}
		}
		out.append(" camera ").append(this.minecraft.gameRenderer.mainCamera().position()).append(" viewDistance ").append(this.minecraft.options.getEffectiveRenderDistance())
			.append(" viewArea ").append(this.minecraft.levelRenderer.viewArea().getViewDistance()).append(" around ").append(this.minecraft.levelRenderer.viewArea().getCameraSectionPos())
			.append(" visibleSections ").append(this.minecraft.levelRenderer.visibleSections().size()).append(" chunks ").append(level.getChunkSource().gatherStats());
		nl.jordythomassen.livingworld.mc.LivingWorldMod.LOG.info(out.toString());
		RtsClient.act("probe", x, z, 0, 0, "");
	}

	private static boolean pointerQuestionFails;

	/** Whether the pointer is over the game's window: with a second screen it can leave, and its last place (an edge) would go on pushing the view. */
	private static boolean pointerInWindow(Minecraft mc) {
		if (pointerQuestionFails) {
			return true;
		}
		try {
			return (org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags(mc.getWindow().handle()) & org.lwjgl.sdl.SDLVideo.SDL_WINDOW_MOUSE_FOCUS) != 0;
		} catch (Throwable e) {
			pointerQuestionFails = true;
			return true;
		}
	}

	private boolean unitKey(String... types) {
		for (Records.Option option : RtsClient.view.actions) {
			for (String type : types) {
				if (option.type().equals(type) && option.turns() == 1) {
					RtsClient.act("unit", 0, 0, 0, 0, type);
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		try {
			return pressed(event);
		} catch (RuntimeException e) {
			RtsClient.failed("the view from above, at a click or a key", e);
			return true;
		}
	}

	private boolean pressed(KeyEvent event) {
		WorldState state = RtsClient.state;
		int key = event.key();
		if (key >= InputConstants.KEY_1 && key <= InputConstants.KEY_9 && (RtsClient.menu != null || RtsClient.choices != null)) {
			// The buttons of the page that is up are numbered, and so are the lines of the list; the page lies on top.
			int row = key - InputConstants.KEY_1;
			List<Runnable> numbered = RtsClient.menu != null ? pageActions : rowActions;
			if (row < numbered.size()) {
				numbered.get(row).run();
			}
			return true;
		}
		switch (key) {
			case InputConstants.KEY_F1 -> {
				RtsClient.help(!RtsClient.helpOpen);
				return true;
			}
			case InputConstants.KEY_ESCAPE -> {
				if (RtsClient.helpOpen) {
					RtsClient.help(false);
				} else if (RtsClient.menu != null) {
					RtsClient.menu = null;
				} else if (RtsClient.mapOpen) {
					RtsClient.mapOpen = false;
				} else if (RtsClient.techOpen) {
					RtsClient.closeTechs();
				} else if (RtsClient.policiesOpen) {
					RtsClient.closePolicies();
				} else if (RtsClient.aiming >= 0) {
					RtsClient.aiming = -1;
				} else if (RtsClient.choices != null) {
					RtsClient.choices = null;
				} else if (RtsClient.view.selected != null) {
					RtsClient.act("deselect", 0, 0, 0, 0, "");
				} else {
					// The game's own menu (options, leaving the world). It only opens when no other screen is up, so this one steps
					// aside for it; it comes back by itself when the menu closes.
					Minecraft mc = this.minecraft;
					mc.gui.setScreen(null);
					mc.pauseGame(false);
				}
				return true;
			}
			case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
				if (state != null) {
					turn(state, event.hasShiftDown());
				}
				return true;
			}
			case InputConstants.KEY_Q -> {
				RtsCamera.turn(-45f);
				return true;
			}
			case InputConstants.KEY_E -> {
				RtsCamera.turn(45f);
				return true;
			}
			case InputConstants.KEY_H -> {
				RtsClient.lookHome();
				return true;
			}
			case InputConstants.KEY_G -> {
				showGrid = !showGrid;
				return true;
			}
			case InputConstants.KEY_Y -> {
				toggleYields();
				return true;
			}
			case InputConstants.KEY_M -> {
				RtsClient.mapOpen = !RtsClient.mapOpen;
				return true;
			}
			case InputConstants.KEY_T -> {
				openTechTree();
				return true;
			}
			case InputConstants.KEY_P -> {
				if (RtsClient.policiesOpen) {
					RtsClient.closePolicies();
				} else {
					RtsClient.openPolicies();
				}
				return true;
			}
			case InputConstants.KEY_V -> {
				RtsClient.act("open", 0, 0, 0, 0, "victory");
				return true;
			}
			case InputConstants.KEY_R -> {
				RtsClient.act("open", 0, 0, 0, 0, "religion");
				return true;
			}
			case InputConstants.KEY_L -> {
				RtsClient.act("lobby", 0, 0, 0, 0, "open");
				return true;
			}
			case InputConstants.KEY_N -> {
				RtsClient.showLog();
				return true;
			}
			case InputConstants.KEY_O -> {
				// Whose land is whose, as a wash of colour over it: on by default.
				TerritoryTexture.show = !TerritoryTexture.show;
				return true;
			}
			case InputConstants.KEY_F7 -> {
				if (Boolean.getBoolean("livingworld.debug")) {
					this.minecraft.levelRenderer.sectionOcclusionGraph().invalidate();
					nl.jordythomassen.livingworld.mc.LivingWorldMod.LOG.info("PROBE asked the game to work out anew what to draw");
				}
				return true;
			}
			case InputConstants.KEY_F8 -> {
				if (Boolean.getBoolean("livingworld.debug")) {
					this.minecraft.levelExtractor.allChanged();
					nl.jordythomassen.livingworld.mc.LivingWorldMod.LOG.info("PROBE asked the game to build everything it draws anew");
				}
				return true;
			}
			case InputConstants.KEY_F9 -> {
				if (Boolean.getBoolean("livingworld.debug")) {
					probe();
				}
				return true;
			}
			case InputConstants.KEY_F10 -> {
				if (Boolean.getBoolean("livingworld.debug")) {
					RtsCamera.debugPerspective = !RtsCamera.debugPerspective;
				}
				return true;
			}
			case InputConstants.KEY_B -> {
				unitKey("FoundCity");
				return true;
			}
			case InputConstants.KEY_F -> {
				unitKey("Fortify", "Sleep");
				return true;
			}
			case InputConstants.KEY_SPACE -> {
				unitKey("Skip");
				return true;
			}
			case InputConstants.KEY_X -> {
				unitKey("Explore");
				return true;
			}
			case InputConstants.KEY_Z -> {
				// A is taken: it moves the view. Z lets a unit get on with it by itself (a Worker works, a soldier acts).
				unitKey("Automate");
				return true;
			}
			case InputConstants.KEY_TAB -> {
				if (state != null) {
					UnitState waiting = nextWaiting(state);
					if (waiting != null) {
						select(waiting.key());
						double[] spot = RtsClient.spots.get(waiting.key());
						if (spot != null) {
							RtsCamera.lookAt(spot[0], spot[2]);
						}
					}
				}
				return true;
			}
			default -> {
				return super.keyPressed(event);
			}
		}
	}
}
