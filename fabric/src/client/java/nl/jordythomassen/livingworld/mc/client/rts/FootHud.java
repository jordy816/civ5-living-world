package nl.jordythomassen.livingworld.mc.client.rts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import nl.jordythomassen.livingworld.board.BoardModel;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.gameplay.TechGate;
import nl.jordythomassen.livingworld.mc.Hands;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * What a ruler sees in the corner of the screen when walking the land: whose land they stand
 * on, and so whether their hands are free there; what the thing in their hand is good for; and,
 * for a little while after coming down, what walking is for at all. Without it the land is
 * just Minecraft, and nobody could know that a log is worth two hammers at the city hall.
 */
public final class FootHud {
	private static final int GREEN = 0xFF7FE060;
	private static final int GOLD = 0xFFFFD75E;
	private static final int RED = 0xFFFF8C78;
	private static final int GREY = 0xFFB4B4C0;
	private static final int DIM = 0xFFC8C8D4;
	/** How long after coming down the card that says what walking is for stays up, in ticks. */
	private static final int CARD_TICKS = 300;
	private static final Map<String, ItemStack> ICONS = new HashMap<>();
	private static long cameDown = Long.MIN_VALUE;
	private static boolean wasAbove = true;

	private FootHud() {
	}

	/** Called while the view from above is up: the next time the land's own screen is drawn, the player has just come down. */
	public static void above() {
		wasAbove = true;
	}

	private static ItemStack icon(String name) {
		return ICONS.computeIfAbsent(name, key -> new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(key))));
	}

	private static int rich(GuiGraphicsExtractor g, Font font, List<Rich.Piece> pieces, int x, int y) {
		for (Rich.Piece piece : pieces) {
			if (piece.picture() != null) {
				g.pose().pushMatrix();
				g.pose().translate(x + 1, y);
				g.pose().scale(0.5f, 0.5f);
				g.item(icon(piece.picture()), 0, 0);
				g.pose().popMatrix();
				x += Rich.PICTURE;
			} else {
				g.text(font, piece.text(), x, y, piece.color(), true);
				x += font.width(piece.text());
			}
		}
		return x;
	}

	private static List<Rich.Piece> line(String text, int color) {
		return Rich.pieces(Rich.words(text, false), false, color, true);
	}

	public static void draw(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		WorldState state = RtsClient.state;
		BoardModel model = RtsClient.model;
		if (player == null || state == null || model == null || mc.gameMode == null || mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
			return;
		}
		if (wasAbove) {
			wasAbove = false;
			cameDown = RtsClient.ticks;
		}
		Font font = mc.font;
		int me = state.session().viewer();
		PlotCoord plot = model.plotAt(player.getBlockX(), player.getBlockZ());
		String title;
		String hint;
		int color;
		String picture;
		if (plot == null || !state.session().grid().contains(plot)) {
			title = "OPEN SEA";
			hint = "Nothing out here for your hands";
			color = GREY;
			picture = "barrier";
		} else {
			PlotState there = state.plot(plot);
			CityState city = there.city() ? state.cityAt(plot) : null;
			BoardModel.Site site = model.siteAt(plot);
			CivInfo owner = there.owner() < 0 ? null : state.civ(there.owner());
			if (city != null && city.key().owner() == me) {
				title = city.name().toUpperCase(Locale.ROOT);
				hint = "Bell: end turn   Lectern: research   Governor: the city, and arms";
				color = GOLD;
				picture = "bell";
			} else if (city != null) {
				title = city.name().toUpperCase(Locale.ROOT);
				hint = "A city of " + (owner == null ? "another people" : owner.name());
				color = GREY;
				picture = "white_banner";
			} else if (site != null) {
				CityState of = state.cities().get(site.city());
				title = TechGate.words(site.wonder()).toUpperCase(Locale.ROOT);
				hint = "A wonder of the world" + (of == null ? "" : ", built by " + of.name());
				color = GOLD;
				picture = "nether_star";
			} else if (there.owner() == me) {
				title = "YOUR LAND";
				hint = "Yours to fell, mine and harvest";
				color = GREEN;
				picture = "lime_dye";
			} else if (owner != null) {
				title = "LAND OF " + owner.name().toUpperCase(Locale.ROOT);
				hint = "Hands off: not your land";
				color = RED;
				picture = "red_dye";
			} else {
				title = "NOBODY'S LAND";
				hint = "Hands off: outside your borders";
				color = GREY;
				picture = "gray_dye";
			}
		}
		// Each line: a small picture, and a few words.
		List<String> pictures = new ArrayList<>();
		List<List<Rich.Piece>> lines = new ArrayList<>();
		pictures.add("");
		lines.add(line(hint, DIM));
		// What is in the hand, and what a city would make of it.
		ItemStack held = player.getMainHandItem();
		Hands.Good good = Hands.of(held);
		if (good != null) {
			pictures.add("chest");
			lines.add(line(good.name() + " at a city: +" + good.worth() + " " + Hands.MEASURE[good.kind()] + " each", GOLD));
		} else if (held.is(Items.BREAD)) {
			pictures.add("bread");
			lines.add(line("Use on a wounded unit of yours: it heals", GOLD));
		}
		// For a while after coming down: what walking is for.
		long down = RtsClient.ticks - cameDown;
		if (down >= 0 && down < CARD_TICKS) {
			pictures.add("");
			lines.add(List.of());
			pictures.add("stone_axe");
			lines.add(line("Fell, mine, harvest: on your own land", DIM));
			pictures.add("chest");
			lines.add(line("At a city: logs +2 Production   wheat +1 Food   gold +4 Gold", DIM));
			pictures.add("bread");
			lines.add(line("Bread heals a unit", DIM));
			pictures.add("iron_sword");
			lines.add(line("Near an enemy unit its soldiers come at you", DIM));
			pictures.add("book");
			lines.add(line("The Codex does all the rest   F12: back above", DIM));
		}
		int width = 14 + font.width(title);
		for (int i = 0; i < lines.size(); i++) {
			width = Math.max(width, Rich.width(font, lines.get(i)) + (pictures.get(i).isEmpty() ? 0 : 11));
		}
		// Under the bar that says whose turn it is, which has the top of the screen.
		int x = 4;
		int y = 24;
		int height = 14 + lines.size() * 10;
		g.fill(x, y, x + width + 8, y + height, 0xA0101016);
		g.fill(x, y, x + 2, y + height, color);
		g.pose().pushMatrix();
		g.pose().translate(x + 5, y + 2);
		g.pose().scale(0.5f, 0.5f);
		g.item(icon(picture), 0, 0);
		g.pose().popMatrix();
		g.text(font, title, x + 16, y + 3, color, true);
		for (int i = 0; i < lines.size(); i++) {
			int inset = 0;
			if (!pictures.get(i).isEmpty()) {
				g.pose().pushMatrix();
				g.pose().translate(x + 5, y + 14 + i * 10);
				g.pose().scale(0.5f, 0.5f);
				g.item(icon(pictures.get(i)), 0, 0);
				g.pose().popMatrix();
				inset = 11;
			}
			rich(g, font, lines.get(i), x + 5 + inset, y + 14 + i * 10);
		}
	}
}
