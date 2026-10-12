package nl.jordythomassen.livingworld.board;

/**
 * The geometry of ruling from above: somebody hangs over the land looking down at an angle, and
 * the point of the land in the middle of their view lies some way in front of them. The player's
 * own game uses this to hang the player where the view needs them; the server uses it the other
 * way round, to know what such a player is looking at. One place for both, so they cannot drift.
 * Angles are Minecraft's: yaw 0 looks south (towards +z), pitch 90 straight down.
 */
public final class Overlook {
	/**
	 * The height somebody who looks from above hangs at. The player's own game steers them there
	 * and the server puts them there: a server that is not the player's own takes a leap of more
	 * than ten blocks made by the player's game for cheating and puts the player back.
	 */
	public static final int HANG_Y = BoardModel.LAND + 36;

	private Overlook() {
	}

	/** How far in front of the eye, over the ground, the looked-at point lies. */
	public static double reach(double eyeY, double groundY, double pitchDegrees) {
		double pitch = Math.toRadians(Math.max(15.0, Math.min(89.0, pitchDegrees)));
		return Math.max(0.0, eyeY - groundY) / Math.tan(pitch);
	}

	/** The point of the land looked at from a place: x and z. */
	public static double[] lookedAt(double x, double z, double yawDegrees, double reach) {
		double yaw = Math.toRadians(yawDegrees);
		return new double[] { x - Math.sin(yaw) * reach, z + Math.cos(yaw) * reach };
	}

	/** Where to hang to have a point of the land in the middle of the view: x and z. */
	public static double[] standFor(double lookedAtX, double lookedAtZ, double yawDegrees, double reach) {
		double yaw = Math.toRadians(yawDegrees);
		return new double[] { lookedAtX + Math.sin(yaw) * reach, lookedAtZ - Math.cos(yaw) * reach };
	}
}
