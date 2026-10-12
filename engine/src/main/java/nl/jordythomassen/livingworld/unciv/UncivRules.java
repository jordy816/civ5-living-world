package nl.jordythomassen.livingworld.unciv;

import com.unciv.logic.city.City;
import com.unciv.logic.civilization.Civilization;
import com.unciv.logic.map.mapunit.MapUnit;
import java.util.Iterator;

/** The few rules Unciv keeps in its own screens instead of its game logic, restated here. */
public final class UncivRules {
	private UncivRules() {
	}

	/**
	 * What a player must still decide before the turn may end, as Unciv's Next Turn button would
	 * insist: "POLICY", "RESEARCH", "PRODUCTION", or "UNITS" (which ending the turn settles by
	 * letting them wait). Empty when nothing stands in the way.
	 */
	public static String blocking(Civilization civ) {
		if (civ.getPolicies().canAdoptPolicy()) {
			return "POLICY";
		}
		if (civ.shouldOpenTechPicker()) {
			return "RESEARCH";
		}
		if (cityWithoutWork(civ) != null) {
			return "PRODUCTION";
		}
		if (!UncivFaith.open(civ).isEmpty()) {
			return "RELIGION";
		}
		if (civ.isMajorCiv() && !civ.isDefeated() && civ.mayVoteForDiplomaticVictory()) {
			return "VOTE";
		}
		for (Iterator<MapUnit> it = civ.getUnits().getDueUnits().iterator(); it.hasNext(); ) {
			it.next();
			return "UNITS";
		}
		return "";
	}

	/** A city of this civilization that is building nothing, or null. Puppets choose for themselves. */
	public static City cityWithoutWork(Civilization civ) {
		for (City city : civ.getCities()) {
			if (city.isPuppet()) {
				continue;
			}
			String current = city.getCityConstructions().currentConstructionName();
			if (current == null || current.isEmpty() || current.equals("Nothing")) {
				return city;
			}
		}
		return null;
	}
}
