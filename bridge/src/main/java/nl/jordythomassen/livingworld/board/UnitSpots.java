package nl.jordythomassen.livingworld.board;

import java.util.HashMap;
import java.util.Map;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * Where on its hex each unit stands. Several units on one hex stand around its middle, and on a
 * city's hex they stand around the hall. Worked out the same way wherever it is asked, so the
 * characters in the world and the flags over them on a player's screen are in the same place.
 */
public final class UnitSpots {
	private UnitSpots() {
	}

	/** For every unit: x, y (where it stands), z. */
	public static Map<EntityKey, double[]> of(WorldState state, BoardModel model) {
		Map<EntityKey, double[]> out = new HashMap<>();
		Map<PlotCoord, Integer> stack = new HashMap<>();
		for (UnitState unit : state.units().values()) {
			int index = stack.merge(unit.at(), 1, Integer::sum) - 1;
			boolean onCity = state.plot(unit.at()).city();
			// Beside a wonder one stands before it, not on its roof.
			boolean onWonder = !onCity && model.siteAt(unit.at()) != null;
			double radius = onCity ? Math.min(model.layout().hexWidth() * 0.34, 8.5) : onWonder ? Math.min(model.layout().hexWidth() * 0.37, 12.0) : (index == 0 ? 0 : 3.5);
			double angle = Math.toRadians(onWonder ? 90.0 + (index % 2 == 0 ? 1 : -1) * ((index + 1) / 2) * 36.0 : index * 72.0 + (onCity ? 45.0 : 0.0));
			double x = model.centerX(unit.at()) + 0.5 + Math.cos(angle) * radius;
			double z = model.centerZ(unit.at()) + 0.5 + Math.sin(angle) * radius;
			out.put(unit.key(), new double[] { x, model.standY((int) Math.floor(x), (int) Math.floor(z)), z });
		}
		return out;
	}
}
