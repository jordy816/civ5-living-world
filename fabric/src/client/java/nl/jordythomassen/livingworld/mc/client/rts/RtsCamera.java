package nl.jordythomassen.livingworld.mc.client.rts;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import nl.jordythomassen.livingworld.board.BoardModel;
import org.joml.Matrix4f;
import org.joml.Vector3fc;

/**
 * The view from above: a flat (orthographic) projection looking down at the land at an angle, the
 * way a strategy game shows its map. The technique is the one Reign of Nether uses: the player
 * hangs high over the land looking down, and the game's perspective is swapped for a parallel
 * one whose height in blocks is the zoom. Panning moves the player; nothing else is special.
 */
public final class RtsCamera {
	public static final float ZOOM_MIN = 14f;
	public static final float ZOOM_MAX = 220f;
	/** Some seven hexes from the top of the screen to the bottom, as Civilization shows its map. */
	public static final float ZOOM_DEFAULT = 60f;
	/** How steeply the view looks down, in degrees. */
	public static final float PITCH = 45f;
	/** Everything within this distance in front of and behind the player is drawn. */
	private static final float DEPTH = 1000f;
	/**
	 * How high the player hangs. Where exactly makes no difference to the picture (the projection
	 * is flat), but the world is loaded around the player, so not far from the point looked at.
	 */
	static final double PLAYER_Y = nl.jordythomassen.livingworld.board.Overlook.HANG_Y;
	/** Ticks spent waiting for a server to lift the player to that height (see steer). */
	private static int waitingForHeight;
	/** The height at which clicks and outlines meet the land. */
	static final double GROUND_Y = BoardModel.LAND + 1;

	private static boolean active;
	/** The zoom and the shape of the window for which the game last worked out what is in view, and when. */
	private static float cullZoom = -1f;
	private static float cullAspect = -1f;
	private static long cullAt;
	/** The view was sent somewhere else in one go, rather than moved there. */
	private static boolean leapt;
	/** Development aid: the ordinary perspective from the same place, to tell what the flat projection changes. */
	static boolean debugPerspective;
	/** The point on the ground in the middle of the screen. */
	static double focusX;
	static double focusZ;
	static boolean focusSet;
	/** Half the height of the view, in blocks. */
	static float zoom = ZOOM_DEFAULT;
	private static float zoomBefore = ZOOM_DEFAULT;
	static float zoomTarget = ZOOM_DEFAULT;
	/** Which way the view looks, in Minecraft's degrees; north-east to begin with. */
	static float yaw = -135f;
	static float yawTarget = -135f;

	private RtsCamera() {
	}

	public static boolean active() {
		return active;
	}

	/** Whether the world is drawn with the flat projection right now. */
	public static boolean flat() {
		return active && !debugPerspective;
	}

	static void setActive(boolean on) {
		if (on && !active) {
			// Up from the land to where the view hangs is a leap like any other: a server that is not the player's own is asked to do it.
			leapt = true;
			// And what is in view is worked out anew for the flat projection.
			cullZoom = -1f;
		}
		active = on;
	}

	private static float aspect() {
		var window = Minecraft.getInstance().getWindow();
		return window.getHeight() == 0 ? 1f : window.getWidth() / (float) window.getHeight();
	}

	/** The zoom of the picture being drawn right now: between two ticks it glides. */
	static float shown() {
		float part = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		return zoomBefore + (zoom - zoomBefore) * part;
	}

	/**
	 * The flat projection of the view from above, written into {@code dest}. The game draws with
	 * its depth turned round (the far end is 0, the near end 1), so the projection for drawing
	 * runs from far to near; the one that only decides what is in view runs the ordinary way.
	 */
	public static Matrix4f matrix(Matrix4f dest, boolean forDrawing) {
		float half = shown();
		float halfWidth = half * aspect();
		boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
		if (forDrawing) {
			return dest.setOrtho(-halfWidth, halfWidth, -half, half, DEPTH, -DEPTH, zeroToOne);
		}
		// For deciding what is in view the game first moves its frustum back until a cube of eight blocks around
		// the eye fits inside. A flat one never grows going back, so one narrower than that cube would keep the
		// game stepping back for ever (a tall narrow window, zoomed right in). Never narrower than the cube, then.
		float least = 14f;
		return dest.setOrtho(-Math.max(least, halfWidth), Math.max(least, halfWidth), -Math.max(least, half), Math.max(least, half), -DEPTH, DEPTH, zeroToOne);
	}

	/** How far out the view may go: no further than the world is loaded around the player. */
	static float zoomMax() {
		if (Boolean.getBoolean("livingworld.rts.zoomfree")) {
			// Development aid: look beyond what is loaded, to see where the loaded world ends.
			return ZOOM_MAX;
		}
		int chunks = Minecraft.getInstance().options.getEffectiveRenderDistance();
		double reach = chunks * 16.0 - (PLAYER_Y - GROUND_Y) - 24.0;
		double corner = Math.hypot(aspect(), 1.0 / Math.sin(Math.toRadians(PITCH)));
		return (float) Math.max(ZOOM_DEFAULT, Math.min(ZOOM_MAX, reach / corner));
	}

	/** One step of smooth zooming and turning, and the player put where the view needs them. */
	static void tick(LocalPlayer player) {
		zoomBefore = zoom;
		// The zoom asked for is kept; only what is shown stays within what is loaded. Coming back up, the
		// world is not yet loaded as far as it will be a moment later, and that must not undo the player's zoom.
		float wanted = Math.min(zoomTarget, zoomMax());
		zoom += (wanted - zoom) * 0.35f;
		if (Math.abs(wanted - zoom) < 0.05f) {
			zoom = wanted;
		}
		// The game works out which parts of the world are in view only when the eye moves or turns. Zooming does neither:
		// it changes how much the same eye sees. Without telling the game, zooming out shows land only as far as it
		// reached before, and the rest stays undrawn until the view is moved. (The game does this itself when its own
		// field of view changes.) Twice a second while the zoom is changing, and once more when it has settled.
		float aspect = aspect();
		boolean settled = zoom == wanted;
		if ((zoom != cullZoom || aspect != cullAspect) && (settled || RtsClient.ticks - cullAt >= 10)) {
			cullZoom = zoom;
			cullAspect = aspect;
			cullAt = RtsClient.ticks;
			Minecraft.getInstance().levelRenderer.sectionOcclusionGraph().invalidate();
		}
		yaw += (yawTarget - yaw) * 0.3f;
		if (Math.abs(yawTarget - yaw) < 0.2f) {
			yaw = yawTarget;
		}
		BoardModel model = RtsClient.model;
		if (model != null) {
			// The middle of the view stays over the map itself, not over the sea painted around it.
			var grid = model.state().session().grid();
			double west = model.layout().x(new nl.jordythomassen.livingworld.coords.GridPos(0, 0));
			double east = model.layout().x(new nl.jordythomassen.livingworld.coords.GridPos(grid.width(), 0));
			double south = model.layout().z(new nl.jordythomassen.livingworld.coords.GridPos(0, 0));
			double north = model.layout().z(new nl.jordythomassen.livingworld.coords.GridPos(0, grid.height()));
			focusX = Math.max(Math.min(west, east), Math.min(Math.max(west, east), focusX));
			focusZ = Math.max(Math.min(north, south), Math.min(Math.max(north, south), focusZ));
		}
		// The player hangs behind the point looked at, so that point is in the middle of the screen. The server
		// works the same sum backwards to know what this player is looking at (Overlook).
		double[] stand = nl.jordythomassen.livingworld.board.Overlook.standFor(focusX, focusZ, yaw,
			nl.jordythomassen.livingworld.board.Overlook.reach(PLAYER_Y + player.getEyeHeight(), GROUND_Y, PITCH));
		double toX = stand[0];
		double toZ = stand[1];
		if (Minecraft.getInstance().hasSingleplayerServer()) {
			// One's own game lets its owner move as they like.
			leapt = false;
		} else {
			// A server that is not the player's own takes a move of more than ten blocks in a tick for cheating and puts the
			// player back. A leap (home, the small map, a banner) is therefore asked of the server, which then moves the
			// player itself; anything else is taken in steps the server accepts, the view catching up over a few ticks.
			// The height is the server's to give: it lifts whoever looks from above (at the table before a game, coming
			// in, coming back up). A leap up there made from here would be put back, tick after tick. Should a server
			// not do it (an older one), the leap is tried after all rather than leaving the view hanging.
			if (Math.abs(player.getY() - PLAYER_Y) > 8.0 && waitingForHeight++ < 60) {
				return;
			}
			waitingForHeight = 0;
			double jump = Math.hypot(toX - player.getX(), toZ - player.getZ());
			if (leapt) {
				leapt = false;
				if (jump > 8.0) {
					RtsClient.act("goto", (int) Math.floor(toX), (int) PLAYER_Y, (int) Math.floor(toZ), 0, "");
				}
			} else if (jump > 8.0) {
				toX = player.getX() + (toX - player.getX()) * 8.0 / jump;
				toZ = player.getZ() + (toZ - player.getZ()) * 8.0 / jump;
			}
		}
		player.setPos(toX, PLAYER_Y, toZ);
		player.setYRot(yaw);
		player.setXRot(PITCH);
		player.setYHeadRot(yaw);
		player.setDeltaMovement(Vec3.ZERO);
	}

	/** Moves the view over the land: right and up as seen on the screen, in blocks. */
	static void pan(double right, double up) {
		double rad = Math.toRadians(yaw);
		// Up the screen is the way the view looks, along the ground; right is a quarter turn from it.
		focusX += -Math.cos(rad) * right - Math.sin(rad) * up;
		focusZ += -Math.sin(rad) * right + Math.cos(rad) * up;
	}

	static void lookAt(double x, double z) {
		focusX = x;
		focusZ = z;
		focusSet = true;
		leapt = true;
	}

	static void zoomBy(float factor) {
		// From what is shown now: a zoom asked for earlier may lie beyond what is loaded at the moment, and
		// the wheel must answer at once, not first work its way back down to the picture.
		float most = zoomMax();
		zoomTarget = Math.max(ZOOM_MIN, Math.min(most, Math.min(zoomTarget, most) * factor));
	}

	static void turn(float degrees) {
		yawTarget += degrees;
	}

	/** Where a point of the world is on a screen of the given size (in whatever units that size is in). */
	static void project(double x, double y, double z, float width, float height, float[] out) {
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		Vec3 eye = camera.position();
		Vector3fc left = camera.leftVector();
		Vector3fc up = camera.upVector();
		double dx = x - eye.x;
		double dy = y - eye.y;
		double dz = z - eye.z;
		double across = -(dx * left.x() + dy * left.y() + dz * left.z());
		double upward = dx * up.x() + dy * up.y() + dz * up.z();
		float half = shown();
		out[0] = (float) ((across / (half * aspect()) + 1.0) * 0.5 * width);
		out[1] = (float) ((1.0 - upward / half) * 0.5 * height);
	}

	/** The point of the ground under a point of the screen: x and z. */
	static double[] ground(double sx, double sy, float width, float height) {
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		Vec3 eye = camera.position();
		Vector3fc left = camera.leftVector();
		Vector3fc up = camera.upVector();
		Vector3fc forward = camera.forwardVector();
		float half = shown();
		double across = (sx / width * 2.0 - 1.0) * half * aspect();
		double upward = (1.0 - sy / height * 2.0) * half;
		double px = eye.x - left.x() * across + up.x() * upward;
		double py = eye.y - left.y() * across + up.y() * upward;
		double pz = eye.z - left.z() * across + up.z() * upward;
		double t = forward.y() == 0 ? 0 : (GROUND_Y - py) / forward.y();
		return new double[] { px + forward.x() * t, pz + forward.z() * t };
	}
}
