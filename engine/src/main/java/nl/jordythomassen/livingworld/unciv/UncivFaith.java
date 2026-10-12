package nl.jordythomassen.livingworld.unciv;

import com.unciv.logic.GameInfo;
import com.unciv.logic.civilization.Civilization;
import com.unciv.logic.civilization.managers.ReligionManager;
import com.unciv.logic.civilization.managers.ReligionState;
import com.unciv.models.Counter;
import com.unciv.models.ruleset.Belief;
import com.unciv.models.ruleset.BeliefType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import nl.jordythomassen.livingworld.protocol.Records;

/**
 * What the player's people believe. The game says when a belief is to be chosen (a pantheon once
 * there is faith enough, the beliefs of a religion when a Great Prophet founds or deepens one)
 * and of which kind; the player chooses, one at a time, and the game adopts them together.
 */
final class UncivFaith {
	private final GameInfo game;
	private final Civilization viewer;
	/** What the player picked so far for the choice that is open; given to the game when it is complete. */
	private final List<Belief> picked = new ArrayList<>();
	private String pickedFor = "";

	UncivFaith(GameInfo game, Civilization viewer) {
		this.game = game;
		this.viewer = viewer;
	}

	/** Which choice of beliefs is open for a civilization: PANTHEON, FOUNDING, ENHANCING, FREE, or empty. */
	static String open(Civilization civ) {
		if (!civ.getGameInfo().isReligionEnabled() || !civ.isMajorCiv()) {
			return "";
		}
		ReligionManager faith = civ.getReligionManager();
		if (faith.getReligionState() == ReligionState.FoundingReligion) {
			return "FOUNDING";
		}
		if (faith.getReligionState() == ReligionState.EnhancingReligion) {
			return "ENHANCING";
		}
		if (faith.canFoundOrExpandPantheon()) {
			return "PANTHEON";
		}
		return faith.hasFreeBeliefs() ? "FREE" : "";
	}

	/** How many beliefs of each kind the open choice asks for. */
	private Counter<BeliefType> wanted(String open) {
		ReligionManager faith = viewer.getReligionManager();
		switch (open) {
			case "FOUNDING":
				return faith.getBeliefsToChooseAtFounding();
			case "ENHANCING":
				return faith.getBeliefsToChooseAtEnhancing();
			case "FREE":
				return faith.freeBeliefsAsEnums();
			case "PANTHEON": {
				Counter<BeliefType> one = new Counter<>();
				one.add(BeliefType.Pantheon, 1);
				return one;
			}
			default:
				return new Counter<>();
		}
	}

	/** The kind of belief to choose next, or null when nothing (more) is asked. */
	private BeliefType next(String open) {
		if (!open.equals(pickedFor)) {
			picked.clear();
			pickedFor = open;
		}
		Counter<BeliefType> wanted = wanted(open);
		for (BeliefType type : BeliefType.values()) {
			if (type == BeliefType.None) {
				continue;
			}
			long have = picked.stream().filter(b -> b.getType() == type).count();
			// A belief "of any kind" is counted after those of a named kind.
			if (type == BeliefType.Any) {
				long named = 0;
				for (BeliefType other : BeliefType.values()) {
					if (other != BeliefType.Any && other != BeliefType.None) {
						named += Math.min(wanted.get(other), picked.stream().filter(b -> b.getType() == other).count());
					}
				}
				have = picked.size() - named;
			}
			if (have < wanted.get(type)) {
				return type;
			}
		}
		return null;
	}

	private List<Belief> available(BeliefType type) {
		List<Belief> out = new ArrayList<>();
		for (Belief belief : game.getRuleset().getBeliefs().values()) {
			if (belief.getType() == BeliefType.None || (type != BeliefType.Any && belief.getType() != type) || picked.contains(belief)) {
				continue;
			}
			// A belief somebody's religion already holds is taken.
			if (viewer.getReligionManager().getReligionWithBelief(belief) == null) {
				out.add(belief);
			}
		}
		return out;
	}

	private static String kind(BeliefType type) {
		return switch (type) {
			case Pantheon -> "what your people pray to";
			case Founder -> "what the founders of your religion gain";
			case Follower -> "what its followers gain";
			case Enhancer -> "how your religion spreads";
			default -> "a belief";
		};
	}

	/** The beliefs to choose from now (group 16); the first entry (group 17) says what is being chosen. Empty when nothing is asked. */
	List<Records.Option> choices() {
		List<Records.Option> out = new ArrayList<>();
		String open = open(viewer);
		BeliefType type = open.isEmpty() ? null : next(open);
		if (type == null) {
			return out;
		}
		String what = switch (open) {
			case "PANTHEON" -> "Your people have faith enough to choose their gods.";
			case "FOUNDING" -> "Your Great Prophet founds a religion.";
			case "ENHANCING" -> "Your Great Prophet deepens your religion.";
			default -> "Your religion may take another belief.";
		};
		out.add(new Records.Option(17, 0, open, what + " Choose " + kind(type) + ".", 0));
		int index = 0;
		for (Belief belief : available(type)) {
			StringBuilder effect = new StringBuilder();
			for (String unique : belief.getUniques()) {
				effect.append(effect.length() == 0 ? "" : "; ").append(UncivLink.words(unique));
			}
			out.add(new Records.Option(16, index++, belief.getName(), UncivLink.words(belief.getName()) + (effect.length() == 0 ? "" : ": " + effect), 0));
		}
		return out;
	}

	/** The player chose a belief. When that completes what was asked, the game adopts them all. */
	UncivActions.Result choose(String name) {
		String open = open(viewer);
		BeliefType type = open.isEmpty() ? null : next(open);
		if (type == null) {
			return UncivActions.Result.refused("No belief is to be chosen now.");
		}
		Belief belief = available(type).stream().filter(b -> b.getName().equals(name)).findFirst().orElse(null);
		if (belief == null) {
			return UncivActions.Result.refused("That belief cannot be chosen now.");
		}
		picked.add(belief);
		if (next(open) != null) {
			return UncivActions.Result.done(UncivLink.words(belief.getName()) + " it is. One more to choose.");
		}
		ReligionManager faith = viewer.getReligionManager();
		List<Belief> all = new ArrayList<>(picked);
		picked.clear();
		pickedFor = "";
		switch (open) {
			case "PANTHEON" -> faith.chooseBeliefs(all, faith.usingFreeBeliefs());
			case "FOUNDING" -> {
				String religion = religionName();
				if (religion == null) {
					return UncivActions.Result.refused("Every religion has been founded already.");
				}
				faith.foundReligion$Unciv_core(religion, religion);
				faith.chooseBeliefs(all, false);
				return UncivActions.Result.done("Your people have founded " + UncivLink.words(religion) + ".");
			}
			case "ENHANCING" -> faith.chooseBeliefs(all, false);
			default -> faith.chooseBeliefs(all, true);
		}
		return UncivActions.Result.done(open.equals("PANTHEON") ? "Your people now pray to " + UncivLink.words(belief.getName()) + "." : "Your religion has taken it up.");
	}

	/** The religion a civilization founds: the one its people are known for when still free, else the first that is. */
	private String religionName() {
		Set<String> used = new LinkedHashSet<>();
		for (var religion : game.getReligions().values()) {
			used.add(religion.getName());
		}
		String favoured = viewer.getNation().getFavoredReligion();
		if (favoured != null && game.getRuleset().getReligions().contains(favoured) && !used.contains(favoured)) {
			return favoured;
		}
		for (String religion : game.getRuleset().getReligions()) {
			if (!used.contains(religion)) {
				return religion;
			}
		}
		return null;
	}
}
