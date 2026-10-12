package nl.jordythomassen.livingworld.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The player's game hangs the ruler somewhere; the server must find back what that ruler looks at. */
class OverlookTest {
	@Test
	void whatIsLookedAtIsFoundBackFromWhereTheRulerHangs() {
		double ground = BoardModel.LAND + 1;
		double eye = BoardModel.LAND + 36 + 1.62;
		for (double yaw : new double[] { -135, -90, -45, 0, 45, 90, 180, 225 }) {
			for (double pitch : new double[] { 30, 45, 60 }) {
				double reach = Overlook.reach(eye, ground, pitch);
				double[] stand = Overlook.standFor(120.5, -77.25, yaw, reach);
				double[] back = Overlook.lookedAt(stand[0], stand[1], yaw, reach);
				assertEquals(120.5, back[0], 1e-9, "x at yaw " + yaw);
				assertEquals(-77.25, back[1], 1e-9, "z at yaw " + yaw);
			}
		}
	}

	@Test
	void atFortyFiveDegreesThePointLiesAsFarAheadAsTheEyeIsHigh() {
		assertEquals(36.62, Overlook.reach(101.62, 65, 45), 1e-9);
		// Looking north-east (yaw -135) the ruler hangs south-west of the point: smaller x, larger z.
		double[] stand = Overlook.standFor(0, 0, -135, 10);
		assertTrue(stand[0] < 0 && stand[1] > 0, stand[0] + ", " + stand[1]);
	}

	@Test
	void lookingAlmostLevelOrFromUnderTheGroundGivesNoNonsense() {
		// Nearly level: the angle is held at fifteen degrees, so the point does not run off to the horizon.
		assertEquals(Overlook.reach(100, 65, 15), Overlook.reach(100, 65, 0), 1e-9);
		assertEquals(0.0, Overlook.reach(60, 65, 45), 1e-9);
	}
}
