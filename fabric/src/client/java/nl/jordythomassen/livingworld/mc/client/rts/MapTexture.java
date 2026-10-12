package nl.jordythomassen.livingworld.mc.client.rts;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.mc.LivingWorldMod;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * The known world as one small picture, a hex a dot, made anew only when the game moves on.
 * The small map and the world map both show it stretched to their size: drawing a few thousand
 * hexes as that many little squares every frame is what makes a large map slow.
 */
final class MapTexture {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(LivingWorldMod.MOD_ID, "rts/known_world");
	private static DynamicTexture texture;
	private static WorldState drawn;
	private static int width;
	private static int height;

	private MapTexture() {
	}

	/** Two dots a hex across, so that every other row can sit half a hex further along. */
	private static void update(WorldState state, ToIntFunction<PlotCoord> color) {
		var grid = state.session().grid();
		int wide = grid.width() * 2 + 1;
		int high = grid.height();
		if (texture == null || wide != width || high != height) {
			forget();
			width = wide;
			height = high;
			texture = new DynamicTexture(() -> "Living World: the known world", wide, high, true);
			Minecraft.getInstance().getTextureManager().register(ID, texture);
		}
		NativeImage image = texture.getPixels();
		if (image == null) {
			return;
		}
		image.fillRect(0, 0, wide, high, 0);
		for (int row = 0; row < grid.height(); row++) {
			int shift = grid.rowShift(row) >= 0.25 ? 1 : 0;
			int y = high - 1 - row;
			for (int col = 0; col < grid.width(); col++) {
				int argb = color.applyAsInt(new PlotCoord(col, row));
				image.setPixel(col * 2 + shift, y, argb);
				image.setPixel(col * 2 + shift + 1, y, argb);
			}
		}
		texture.upload();
		drawn = state;
	}

	/** Draws the known world stretched into the given box. Its shape is that of the map: half a hex wider than its columns, for the rows that sit half a hex along. */
	static void draw(GuiGraphicsExtractor g, WorldState state, ToIntFunction<PlotCoord> color, int x, int y, int drawWidth, int drawHeight) {
		if (state != drawn || texture == null) {
			update(state, color);
		}
		if (texture == null) {
			return;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, ID, x, y, 0f, 0f, drawWidth, drawHeight, width, height, width, height);
	}

	/** Lets the picture go: on leaving a world. */
	static void forget() {
		if (texture != null) {
			Minecraft.getInstance().getTextureManager().release(ID);
			texture = null;
		}
		drawn = null;
	}
}
