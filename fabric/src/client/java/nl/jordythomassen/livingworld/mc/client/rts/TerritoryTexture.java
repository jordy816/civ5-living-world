package nl.jordythomassen.livingworld.mc.client.rts;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import nl.jordythomassen.livingworld.board.BoardModel;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.mc.LivingWorldMod;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.WorldState;
import org.joml.Matrix3x2f;

/**
 * Whose land is whose, laid over the land itself: every civilization's territory in a wash of
 * its colour with a broad band of it along the border, as Civilization draws it; land one has
 * seen but does not see now a shade darker (the fog of war); and, when several people each rule
 * a civilization, land one's own people never saw under cloud. One picture of the whole map, a
 * dot a block, made anew only when borders or sight change, and laid on the ground by the same
 * flat camera the land is seen through.
 */
final class TerritoryTexture {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(LivingWorldMod.MOD_ID, "rts/territory");
	/** The largest picture made; a larger map gets coarser dots. */
	private static final int LIMIT = 2048;
	private static DynamicTexture texture;
	private static int width;
	private static int height;
	private static int minX;
	private static int minZ;
	private static int scale = 1;
	/** Which plot each dot lies on (its number in the game's list, -1 off the map): worked out once per map. */
	private static int[] plotAt;
	private static String plotsFor = "";
	private static long drawn = Long.MIN_VALUE;
	private static final Matrix3x2f LAY = new Matrix3x2f();
	private static final float[] P0 = new float[2];
	private static final float[] PX = new float[2];
	private static final float[] PZ = new float[2];
	static boolean show = true;

	private TerritoryTexture() {
	}

	private static int over(int top, int under) {
		int ta = top >>> 24;
		if (ta == 0) {
			return under;
		}
		int ua = under >>> 24;
		if (ua == 0) {
			return top;
		}
		float a = ta / 255f;
		float b = ua / 255f * (1 - a);
		float out = a + b;
		int r = Math.round((((top >> 16) & 0xFF) * a + ((under >> 16) & 0xFF) * b) / out);
		int g = Math.round((((top >> 8) & 0xFF) * a + ((under >> 8) & 0xFF) * b) / out);
		int bl = Math.round(((top & 0xFF) * a + (under & 0xFF) * b) / out);
		return (Math.round(out * 255) << 24) | (r << 16) | (g << 8) | bl;
	}

	/** A colour that reads on grass, sand and snow alike: the civilization's own, lifted when it is too dark to see. */
	private static int readable(int rgb) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		int light = (r * 3 + g * 6 + b) / 10;
		if (light < 70) {
			r = Math.min(255, r + 90);
			g = Math.min(255, g + 90);
			b = Math.min(255, b + 90);
		}
		return (r << 16) | (g << 8) | b;
	}

	private static void update(WorldState state, BoardModel model, boolean several, long key) {
		int[] bounds = model.bounds();
		int spanX = bounds[2] - bounds[0];
		int spanZ = bounds[3] - bounds[1];
		int step = Math.max(1, (int) Math.ceil(Math.max(spanX, spanZ) / (double) LIMIT));
		int wide = Math.max(1, spanX / step);
		int high = Math.max(1, spanZ / step);
		String plotsKey = state.session().gameId() + "/" + bounds[0] + "/" + bounds[1] + "/" + wide + "/" + high + "/" + step;
		if (texture == null || wide != width || high != height) {
			forget();
			width = wide;
			height = high;
			texture = new DynamicTexture(() -> "Living World: whose land is whose", wide, high, true);
			Minecraft.getInstance().getTextureManager().register(ID, texture);
		}
		if (!plotsKey.equals(plotsFor) || plotAt == null) {
			var grid = state.session().grid();
			plotAt = new int[wide * high];
			for (int z = 0; z < high; z++) {
				for (int x = 0; x < wide; x++) {
					PlotCoord plot = model.plotAt(bounds[0] + x * step + step / 2, bounds[1] + z * step + step / 2);
					plotAt[z * wide + x] = plot == null ? -1 : plot.y() * grid.width() + plot.x();
				}
			}
			plotsFor = plotsKey;
		}
		minX = bounds[0];
		minZ = bounds[1];
		scale = step;
		NativeImage image = texture.getPixels();
		if (image == null) {
			return;
		}
		int count = state.plotCount();
		// Per plot: whose it is as the player knows it (-1 nobody's, -2 never seen), and what lies over it.
		int[] owner = new int[count];
		int[] wash = new int[count];
		int[] band = new int[count];
		int[] shade = new int[count];
		for (int i = 0; i < count; i++) {
			PlotState plot = state.plot(i);
			if (!plot.revealed()) {
				owner[i] = -2;
				// Under cloud. With one civilization for everybody the land itself is cloud there already.
				shade[i] = several ? 0xF8E6EAF0 : 0;
				continue;
			}
			owner[i] = plot.owner();
			CivInfo civ = plot.owner() < 0 ? null : state.civ(plot.owner());
			if (civ != null) {
				int rgb = readable(civ.color());
				wash[i] = 0x24000000 | rgb;
				band[i] = 0xF0000000 | rgb;
			} else {
				owner[i] = -1;
			}
			shade[i] = plot.visible() ? 0 : 0x62060810;
		}
		int near = Math.max(1, 2 / step);
		int far = Math.max(2, 4 / step);
		for (int z = 0; z < high; z++) {
			for (int x = 0; x < wide; x++) {
				int at = plotAt[z * wide + x];
				if (at < 0 || at >= count) {
					image.setPixel(x, z, 0);
					continue;
				}
				int mine = owner[at];
				int color = 0;
				if (mine >= 0) {
					// How near the land of somebody else (or nobody) is: a strong line at the very edge, a softer band inside it.
					boolean edge = differs(x, z, wide, high, near, mine, owner);
					boolean inner = !edge && differs(x, z, wide, high, far, mine, owner);
					color = edge ? band[at] : inner ? (band[at] & 0x00FFFFFF) | 0x90000000 : wash[at];
				}
				image.setPixel(x, z, over(shade[at], color));
			}
		}
		texture.upload();
		drawn = key;
	}

	private static boolean differs(int x, int z, int wide, int high, int reach, int mine, int[] owner) {
		return other(x - reach, z, wide, high, owner) != mine || other(x + reach, z, wide, high, owner) != mine || other(x, z - reach, wide, high, owner) != mine
			|| other(x, z + reach, wide, high, owner) != mine;
	}

	private static int other(int x, int z, int wide, int high, int[] owner) {
		if (x < 0 || z < 0 || x >= wide || z >= high) {
			return -1;
		}
		int at = plotAt[z * wide + x];
		return at < 0 || at >= owner.length ? -1 : Math.max(-1, owner[at]);
	}

	/** Lays the picture on the land as the camera shows it; {@code w} and {@code h} are the screen in the panels' units. */
	static void draw(GuiGraphicsExtractor g, WorldState state, BoardModel model, boolean several, float w, float h) {
		if (!show && !several) {
			return;
		}
		long key = several ? 7 : 3;
		for (int i = 0; i < state.plotCount(); i++) {
			PlotState plot = state.plot(i);
			key = key * 31 + (plot.revealed() ? (plot.visible() ? 2 : 1) + plot.owner() * 4 + 8 : 0);
		}
		for (CivInfo civ : state.civs().values()) {
			key = key * 31 + civ.color();
		}
		key = key * 31 + state.session().gameId().hashCode() + (show ? 0 : 99);
		if (key != drawn || texture == null) {
			update(state, model, several, key);
		}
		if (texture == null) {
			return;
		}
		double y = RtsCamera.GROUND_Y;
		RtsCamera.project(minX, y, minZ, w, h, P0);
		RtsCamera.project(minX + (double) width * scale, y, minZ, w, h, PX);
		RtsCamera.project(minX, y, minZ + (double) height * scale, w, h, PZ);
		g.pose().pushMatrix();
		try {
			LAY.set((PX[0] - P0[0]) / width, (PX[1] - P0[1]) / width, (PZ[0] - P0[0]) / height, (PZ[1] - P0[1]) / height, P0[0], P0[1]);
			g.pose().mul(LAY);
			g.blit(RenderPipelines.GUI_TEXTURED, ID, 0, 0, 0f, 0f, width, height, width, height, width, height);
		} finally {
			g.pose().popMatrix();
		}
	}

	static void forget() {
		if (texture != null) {
			Minecraft.getInstance().getTextureManager().release(ID);
			texture = null;
		}
		drawn = Long.MIN_VALUE;
		plotAt = null;
		plotsFor = "";
	}
}
