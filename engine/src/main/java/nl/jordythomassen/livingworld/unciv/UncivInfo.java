package nl.jordythomassen.livingworld.unciv;

import com.unciv.logic.GameInfo;
import com.unciv.logic.battle.AttackableTile;
import com.unciv.logic.battle.BattleDamage;
import com.unciv.logic.battle.CityCombatant;
import com.unciv.logic.battle.ICombatant;
import com.unciv.logic.battle.MapUnitCombatant;
import com.unciv.logic.battle.TargetHelper;
import com.unciv.logic.city.City;
import com.unciv.logic.civilization.Civilization;
import com.unciv.logic.civilization.managers.ReligionManager;
import com.unciv.logic.map.mapunit.MapUnit;
import com.unciv.logic.map.tile.Tile;
import com.unciv.models.Counter;
import com.unciv.models.Religion;
import com.unciv.models.ruleset.Belief;
import com.unciv.models.ruleset.Building;
import com.unciv.models.ruleset.IConstruction;
import com.unciv.models.ruleset.INonPerpetualConstruction;
import com.unciv.models.ruleset.Milestone;
import com.unciv.models.ruleset.PerpetualConstruction;
import com.unciv.models.ruleset.Policy;
import com.unciv.models.ruleset.PolicyBranch;
import com.unciv.models.ruleset.Victory;
import com.unciv.models.ruleset.tech.Technology;
import com.unciv.models.ruleset.tile.ResourceSupplyList;
import com.unciv.models.ruleset.tile.ResourceType;
import com.unciv.models.ruleset.tile.TileImprovement;
import com.unciv.models.ruleset.tile.TileResource;
import com.unciv.models.ruleset.unit.BaseUnit;
import com.unciv.models.ruleset.unit.Promotion;
import com.unciv.models.stats.Stat;
import com.unciv.models.stats.Stats;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import nl.jordythomassen.livingworld.protocol.Records;
import nl.jordythomassen.livingworld.state.EntityKey;

/**
 * What things are and what they do, in words and numbers: the texts Unciv shows beside its own
 * lists, what a fight would cost before it is fought, and the pages of an empire a player looks
 * things up on (faith, the ways to win, a city in detail). Nothing here changes the game.
 */
final class UncivInfo {
	/** How a line of a page is to be read: a heading, plain, an aside, good news, bad news. */
	static final int HEAD = 0;
	static final int TEXT = 1;
	static final int DIM = 2;
	static final int GOOD = 3;
	static final int BAD = 4;

	private final GameInfo game;
	private final UncivSnapshot snapshot;
	private final Civilization viewer;
	private final UncivActions actions;

	UncivInfo(GameInfo game, UncivSnapshot snapshot, Civilization viewer, UncivActions actions) {
		this.game = game;
		this.snapshot = snapshot;
		this.viewer = viewer;
		this.actions = actions;
	}

	/** One of Unciv's texts as plain lines: its fill-in brackets and little pictures gone, empty lines dropped, what carries a number first. */
	static String lines(String text) {
		if (text == null) {
			return "";
		}
		List<String> plain = new ArrayList<>();
		for (String line : text.split("\n")) {
			String words = UncivLink.words(line);
			if (forPlayers(words)) {
				plain.add(words);
			}
		}
		return ordered(plain);
	}

	/**
	 * A description as a player reads it at a glance: what it gives in numbers first, the upkeep
	 * said shortly, conditions and warnings after. The order within each kind is Unciv's own.
	 */
	private static String ordered(List<String> plain) {
		List<String> numbers = new ArrayList<>();
		List<String> rest = new ArrayList<>();
		for (String line : plain) {
			String shorter = line.replace("Maintenance cost: ", "Upkeep ").replace(" in all cities", " in every city").replace("when constructing ", "for ");
			boolean gives = shorter.matches("^[+\\-]?\\d.*") && !shorter.startsWith("Upkeep");
			(gives ? numbers : rest).add(shorter);
		}
		numbers.addAll(rest);
		return String.join("\n", numbers);
	}

	/** Rules that are there for the computer's players, for Unciv's own screens or for its book of knowledge: no news to a player. */
	private static final List<String> NOT_FOR_PLAYERS = List.of("for AI decisions", "Is part of Great Person group", "gets a name from", "Great Person - ", "Only available when religion is enabled",
		"Automation is a primary action", "Never appears as a Barbarian", "ruins-like effects", "Unbuildable", "Will not be displayed", "Excluded from map editor", "Comment ", "Hidden ",
		"Religious Unit", "Uncapturable", "Can be purchased with Faith", "Can be purchased for", "Cannot be purchased", "Shown while", "Not shown on");

	private static boolean forPlayers(String line) {
		for (String phrase : NOT_FOR_PLAYERS) {
			if (line.contains(phrase)) {
				return false;
			}
		}
		return !line.isEmpty();
	}

	private static String rules(Collection<String> uniques) {
		List<String> plain = new ArrayList<>();
		for (String unique : uniques) {
			String words = UncivLink.words(unique);
			if (forPlayers(words)) {
				plain.add(words);
			}
		}
		return ordered(plain);
	}

	/** Unciv's own description when it can be had without its screens, else the bare rules of the thing. */
	private static String described(Supplier<String> own, Collection<String> uniques) {
		try {
			String text = lines(own.get());
			if (!text.isEmpty()) {
				return text;
			}
		} catch (Throwable e) {
			// Some descriptions lean on Unciv's screens, which are not here; the rules themselves say as much.
		}
		return rules(uniques);
	}

	private static String round(float value) {
		return Math.abs(value - Math.round(value)) < 0.05f ? Integer.toString(Math.round(value)) : String.format(Locale.ROOT, "%.1f", value);
	}

	private static String signed(float value) {
		return (value >= 0 ? "+" : "") + round(value);
	}

	/** The buildings that stand in a city. (Unciv has a list of their names by the same name; Java cannot tell the two apart.) */
	@SuppressWarnings("unchecked")
	static List<Building> built(City city) {
		return Kt.list((kotlin.sequences.Sequence<Building>) Kt.get(city.getCityConstructions(), "getBuiltBuildings", kotlin.sequences.Sequence.class));
	}

	// ---- what the things in a list are ----

	/**
	 * What each thing in one of the game's lists is and does: the same type keys as the list, the
	 * name the text (lines apart), the id a number that goes with it (a cost).
	 */
	List<Records.Option> about(String kind, int a) {
		List<Records.Option> out = new ArrayList<>();
		switch (kind) {
			case "research" -> {
				for (Technology tech : game.getRuleset().getTechnologies().values()) {
					out.add(new Records.Option(0, viewer.getTech().costOfTech(tech.getName()), "TECH_" + Names.techKey(tech.getName()), technology(tech), 0));
				}
			}
			case "policy" -> {
				for (Policy policy : game.getRuleset().getPolicies().values()) {
					String text = described(policy::getDescription, policy.getUniques());
					// Unciv's own text has the name again; the list has that already.
					String name = UncivLink.words(policy.getName());
					List<String> kept = new ArrayList<>();
					for (String line : text.split("\n")) {
						if (!line.isEmpty() && !line.equals(name)) {
							kept.add(line);
						}
					}
					text = kept.isEmpty() ? rules(policy.getUniques()) : String.join("\n", kept);
					out.add(new Records.Option(0, 0, "POLICY_" + Names.key(policy.getName()), text, 0));
				}
			}
			case "build", "buy" -> {
				City city = actions.ownCity(a);
				if (city == null) {
					return out;
				}
				for (Map.Entry<String, Object[]> entry : actions.buildable(city).entrySet()) {
					out.add(new Records.Option(0, 0, entry.getKey(), construction(city, city.getCityConstructions().getConstruction$Unciv_core((String) entry.getValue()[0])), 0));
				}
			}
			case "faith" -> {
				City city = actions.ownCity(a);
				if (city == null) {
					return out;
				}
				for (Map.Entry<String, INonPerpetualConstruction> entry : forFaith(city).entrySet()) {
					out.add(new Records.Option(0, 0, entry.getKey(), construction(city, entry.getValue()), 0));
				}
			}
			case "promote" -> {
				MapUnit unit = actions.ownUnit(new EntityKey(snapshot.civId(viewer), a));
				if (unit == null) {
					return out;
				}
				List<Promotion> open = Kt.list(unit.getPromotions().getAvailablePromotions());
				for (Promotion promotion : open) {
					out.add(new Records.Option(0, 0, "PROMOTION_" + Names.key(promotion.getName()), promotion(promotion), 0));
				}
			}
			case "greatPeople" -> {
				for (BaseUnit person : viewer.getGreatPeople().getFreeGreatPersonOptions()) {
					out.add(new Records.Option(0, 0, "GREAT_" + Names.key(person.getName()), construction(null, person), 0));
				}
			}
			default -> {
			}
		}
		return out;
	}

	/**
	 * What a technology opens, a thing a line, each line beginning with the kind of thing it is
	 * ("Unit: Archer", "Wonder: Temple of Artemis"): the screen draws the kind as a picture. Its
	 * own rules after.
	 */
	private String technology(Technology tech) {
		List<String> out = new ArrayList<>();
		String name = tech.getName();
		// Of what another people builds in its own way, only the player's own kind is theirs to have.
		for (BaseUnit unit : game.getRuleset().getUnits().values()) {
			if (name.equals(unit.getRequiredTech()) && viewer.getEquivalentUnit(unit) == unit
				&& unit.getUniques().stream().noneMatch(rule -> rule.contains("Unbuildable") || rule.contains("Will not be displayed"))) {
				out.add("Unit: " + UncivLink.words(unit.getName()) + "   " + numbers(unit));
			}
		}
		for (Building building : game.getRuleset().getBuildings().values()) {
			if (name.equals(building.getRequiredTech()) && viewer.getEquivalentBuilding(building) == building) {
				out.add((building.isAnyWonder() ? "Wonder: " : "Building: ") + UncivLink.words(building.getName()));
			}
		}
		for (TileImprovement improvement : game.getRuleset().getTileImprovements().values()) {
			if (name.equals(improvement.getTechRequired()) && (improvement.getUniqueTo() == null || improvement.getUniqueTo().equals(viewer.getCivName()))) {
				out.add("Improvement: " + UncivLink.words(improvement.getName()));
			}
		}
		for (TileResource resource : game.getRuleset().getTileResources().values()) {
			if (name.equals(resource.getRevealedBy())) {
				out.add("Reveals: " + UncivLink.words(resource.getName()));
			}
		}
		String own = rules(tech.getUniques());
		if (!own.isEmpty()) {
			out.add(own);
		}
		return out.isEmpty() ? "A step on the way to what comes after." : String.join("\n", out);
	}

	/** What a unit is in numbers, each before its measure: the screen draws the measures as pictures. */
	private static String numbers(BaseUnit unit) {
		StringBuilder head = new StringBuilder();
		if (unit.getStrength() > 0) {
			head.append(unit.getStrength()).append(" Strength   ");
		}
		if (unit.getRangedStrength() > 0) {
			head.append(unit.getRangedStrength()).append(" Ranged Strength   ").append(unit.getRange()).append(" Range   ");
		}
		return head.append(unit.getMovement()).append(" Movement").toString();
	}

	private static String promotion(Promotion promotion) {
		String text = rules(promotion.getUniques());
		return text.isEmpty() ? "A step on the way to stronger promotions." : text;
	}

	private String construction(City city, IConstruction thing) {
		if (thing instanceof Building building) {
			return described(() -> building.getDescription(city, true), building.getUniques());
		}
		if (thing instanceof BaseUnit unit) {
			String rest = rules(unit.getUniques());
			return numbers(unit) + (rest.isEmpty() ? "" : "\n" + rest);
		}
		if (thing instanceof PerpetualConstruction perpetual) {
			return "The city's work goes into " + UncivLink.words(perpetual.getName()).toLowerCase(Locale.ROOT) + " for as long as this stands.";
		}
		return "";
	}

	/** What a city could buy with faith right now: key, and the thing itself. */
	Map<String, INonPerpetualConstruction> forFaith(City city) {
		Map<String, INonPerpetualConstruction> out = new LinkedHashMap<>();
		if (!game.isReligionEnabled() || city.isPuppet()) {
			return out;
		}
		for (BaseUnit unit : game.getRuleset().getUnits().values()) {
			if (buyable(city, unit)) {
				out.put("UNIT_" + Names.key(unit.getName()), unit);
			}
		}
		for (Building building : game.getRuleset().getBuildings().values()) {
			if (buyable(city, building)) {
				out.put("BUILDING_" + Names.key(building.getName()), building);
			}
		}
		return out;
	}

	private static boolean buyable(City city, INonPerpetualConstruction thing) {
		try {
			return thing.canBePurchasedWithStat(city, Stat.Faith) && thing.getStatBuyCost(city, Stat.Faith) != null;
		} catch (RuntimeException e) {
			return false;
		}
	}

	// ---- the tree of policies ----

	/**
	 * Every social policy: the group its branch (in the order of the rules), the id its place in
	 * it (row * 10 + column, 0 for the branch itself and 99 for what completing it gives), the
	 * type "POLICY_X|state|Branch name|what it builds on;...|era", the state one of adopted, open
	 * (can be taken now), near (all it builds on is there; culture is wanting) and far.
	 */
	List<Records.Option> policyTree() {
		List<Records.Option> out = new ArrayList<>();
		boolean pick = viewer.getPolicies().canAdoptPolicy();
		int index = 0;
		for (PolicyBranch branch : game.getRuleset().getPolicyBranches().values()) {
			out.add(policyRow(index, 0, branch, branch, pick));
			for (Policy policy : branch.getPolicies()) {
				boolean last = policy.getPolicyBranchType() == Policy.PolicyBranchType.BranchComplete;
				out.add(policyRow(index, last ? 99 : policy.getRow() * 10 + policy.getColumn(), policy, branch, pick));
			}
			index++;
		}
		return out;
	}

	private Records.Option policyRow(int group, int place, Policy policy, PolicyBranch branch, boolean pick) {
		boolean adopted = viewer.getPolicies().isAdopted(policy.getName());
		boolean within = viewer.getPolicies().isAdoptable(policy, true);
		String state = adopted ? "adopted" : within ? (pick ? "open" : "near") : "far";
		List<String> requires = policy.getRequires() == null ? List.of() : policy.getRequires();
		return new Records.Option(group, place, "POLICY_" + Names.key(policy.getName()) + "|" + state + "|" + branch.getName() + "|" + String.join(";", requires) + "|" + branch.getEra(),
			policy.getName(), 0);
	}

	// ---- a fight before it is fought ----

	/**
	 * What an attack would come to, as Unciv reckons it before the dice: group 40 the attacker and
	 * 41 the defender (id health now, turns the damage to expect, type "strength|least|most", the
	 * name who), 42 and 43 what weighs on either side (the name, the id a percentage).
	 *
	 * @param unit the attacking unit of the player's, or -1 when a city of theirs bombards
	 * @param city that city when {@code unit} is -1
	 */
	List<Records.Option> battle(int unit, int city, Tile target) {
		List<Records.Option> out = new ArrayList<>();
		if (target == null) {
			return out;
		}
		ICombatant attacker;
		ICombatant defender;
		Tile from;
		if (unit >= 0) {
			MapUnit own = actions.ownUnit(new EntityKey(snapshot.civId(viewer), unit));
			AttackableTile attack = own == null ? null : actions.attackable(own, target);
			if (attack == null || attack.getCombatant() == null) {
				return out;
			}
			attacker = new MapUnitCombatant(own);
			defender = attack.getCombatant();
			from = attack.getTileToAttackFrom();
		} else {
			City own = actions.ownCity(city);
			if (own == null || !bombardable(own).contains(target)) {
				return out;
			}
			attacker = new CityCombatant(own);
			defender = com.unciv.logic.battle.Battle.INSTANCE.getMapCombatantOfTile(target);
			from = own.getCenterTile();
			if (defender == null) {
				return out;
			}
		}
		BattleDamage damage = BattleDamage.INSTANCE;
		int toDefender = damage.calculateDamageToDefender(attacker, defender, from, 0.5f);
		int toAttacker = damage.calculateDamageToAttacker(attacker, defender, from, 0.5f);
		out.add(new Records.Option(40, attacker.getHealth(), round(damage.getAttackingStrength(attacker, defender, from)) + "|"
			+ damage.calculateDamageToAttacker(attacker, defender, from, 0f) + "|" + damage.calculateDamageToAttacker(attacker, defender, from, 1f), UncivLink.words(attacker.getName()), toAttacker));
		out.add(new Records.Option(41, defender.getHealth(), round(damage.getDefendingStrength(attacker, defender, from)) + "|"
			+ damage.calculateDamageToDefender(attacker, defender, from, 0f) + "|" + damage.calculateDamageToDefender(attacker, defender, from, 1f), UncivLink.words(defender.getName()), toDefender));
		for (Map.Entry<String, Integer> modifier : damage.getAttackModifiers(attacker, defender, from).entrySet()) {
			out.add(new Records.Option(42, modifier.getValue(), "modifier", UncivLink.words(modifier.getKey()), 0));
		}
		if (!defender.isCivilian()) {
			for (Map.Entry<String, Integer> modifier : damage.getDefenceModifiers(attacker, defender, from).entrySet()) {
				out.add(new Records.Option(43, modifier.getValue(), "modifier", UncivLink.words(modifier.getKey()), 0));
			}
		}
		return out;
	}

	/** The tiles one of the player's cities could bombard now. */
	List<Tile> bombardable(City city) {
		if (!city.canBombard()) {
			return List.of();
		}
		return Kt.list(TargetHelper.INSTANCE.getBombardableTiles(city));
	}

	// ---- what the top of the screen shows, and the unit in hand ----

	/**
	 * The empire's numbers the picture of the world has no place for (group 31): by type "faith"
	 * (id what is stored, turns what a turn brings, the name what the people believe), "faithGoal"
	 * (id the faith it takes, the name "pantheon" or "prophet"), "golden" (id happiness saved up,
	 * turns what a golden age takes, the name the turns one still lasts), "resource" (the name,
	 * id how many are free, turns 1 strategic or 2 luxury), "score", "freeGreat" (id how many Great
	 * People are the player's for the choosing) and "bombard" (id a city that can fire, the name
	 * where at: "x,y;x,y").
	 */
	List<Records.Option> realm() {
		List<Records.Option> out = new ArrayList<>();
		Stats next = viewer.getStats().getStatsForNextTurn();
		if (game.isReligionEnabled()) {
			ReligionManager faith = viewer.getReligionManager();
			Religion religion = faith.getReligion();
			out.add(new Records.Option(31, faith.getStoredFaith(), "faith", religion == null ? "" : UncivLink.words(religion.getReligionDisplayName()), Math.round(next.getFaith())));
			String open = UncivFaith.open(viewer);
			if (religion == null || religion.isPantheon()) {
				boolean pantheon = religion == null;
				int needed = pantheon ? faith.faithForPantheon(0) : faith.faithForNextGreatProphet();
				if (open.isEmpty() && (pantheon || faith.remainingFoundableReligions() > 0)) {
					out.add(new Records.Option(31, needed, "faithGoal", pantheon ? "pantheon" : "prophet", 0));
				}
			} else {
				out.add(new Records.Option(31, faith.faithForNextGreatProphet(), "faithGoal", "prophet", 0));
			}
		}
		var golden = viewer.getGoldenAges();
		out.add(new Records.Option(31, golden.getStoredHappiness(), "golden", Integer.toString(golden.getTurnsLeftForCurrentGoldenAge()), golden.happinessRequiredForNextGoldenAge()));
		Map<String, int[]> resources = new LinkedHashMap<>();
		ResourceSupplyList supply = viewer.getCivResourceSupply();
		for (ResourceSupplyList.ResourceSupply entry : supply) {
			ResourceType type = entry.getResource().getResourceType();
			if (type == ResourceType.Strategic || type == ResourceType.Luxury) {
				resources.computeIfAbsent(entry.getResource().getName(), name -> new int[] { 0, type == ResourceType.Strategic ? 1 : 2 })[0] += entry.getAmount();
			}
		}
		for (Map.Entry<String, int[]> resource : resources.entrySet()) {
			if (resource.getValue()[1] == 1 || resource.getValue()[0] > 0) {
				out.add(new Records.Option(31, resource.getValue()[0], "resource", resource.getKey(), resource.getValue()[1]));
			}
		}
		out.add(new Records.Option(31, (int) Math.round(viewer.calculateTotalScore()), "score", "", 0));
		if (viewer.getGreatPeople().getFreeGreatPeople() > 0) {
			out.add(new Records.Option(31, viewer.getGreatPeople().getFreeGreatPeople(), "freeGreat", "", 0));
		}
		if (game.isEspionageEnabled() && !viewer.getEspionageManager().getSpyList().isEmpty()) {
			// How many spies there are, and how many of them wait for orders.
			out.add(new Records.Option(31, viewer.getEspionageManager().getSpyList().size(), "spies", "", viewer.getEspionageManager().getIdleSpies().size()));
		}
		for (City city : viewer.getCities()) {
			List<Tile> targets = bombardable(city);
			if (!targets.isEmpty()) {
				StringBuilder at = new StringBuilder();
				for (Tile tile : targets) {
					var plot = snapshot.map().plot(tile);
					at.append(at.length() == 0 ? "" : ";").append(plot.x()).append(',').append(plot.y());
				}
				out.add(new Records.Option(31, UncivSnapshot.cityId(city), "bombard", at.toString(), 0));
			}
		}
		return out;
	}

	/**
	 * A unit of the player's beyond what the picture of the world says of it: its promotions
	 * (group 32, the name "Name" and what it gives on the lines after) and how far it is from the
	 * next (group 33, type "xp": id what it has, turns what it takes).
	 */
	List<Records.Option> unit(int id) {
		List<Records.Option> out = new ArrayList<>();
		MapUnit unit = snapshot.unit(new EntityKey(snapshot.civId(viewer), id));
		if (unit == null) {
			return out;
		}
		for (Promotion promotion : Kt.list(unit.getPromotions().getPromotions(true))) {
			out.add(new Records.Option(32, 0, "promotion", UncivLink.words(promotion.getName()) + "\n" + promotion(promotion), 0));
		}
		if (!unit.isCivilian()) {
			out.add(new Records.Option(33, unit.getPromotions().getXP(), "xp", "", unit.getPromotions().xpForNextPromotion()));
		}
		return out;
	}

	// ---- pages ----

	private static void line(List<Records.Option> out, int style, String text) {
		out.add(new Records.Option(style, out.size(), "line", text, 0));
	}

	private static String step(boolean taken) {
		return taken ? "\u2714" : "\u25CB";
	}

	private static String beliefText(Belief belief) {
		String effect = rules(belief.getUniques()).replace("\n", "; ");
		return UncivLink.words(belief.getName()) + (effect.isEmpty() ? "" : ": " + effect);
	}

	/** Faith and what the player's people believe, as a page of lines (the group says how each reads). */
	List<Records.Option> religion() {
		List<Records.Option> out = new ArrayList<>();
		if (!game.isReligionEnabled()) {
			line(out, TEXT, "This game is played without religion.");
			return out;
		}
		ReligionManager faith = viewer.getReligionManager();
		Religion religion = faith.getReligion();
		Stats next = viewer.getStats().getStatsForNextTurn();
		line(out, HEAD, "Faith");
		line(out, TEXT, faith.getStoredFaith() + " Faith  (" + signed(next.getFaith()) + " a turn)");
		// The way a faith goes, and how far along the player's is.
		boolean founded = religion != null && !religion.isPantheon();
		line(out, TEXT, step(religion != null) + " Pantheon  \u2192  " + step(founded) + " Religion  \u2192  " + step(founded && religion.isEnhancedReligion()) + " Enhanced");
		if (religion == null) {
			line(out, TEXT, "Next: a pantheon at " + faith.faithForPantheon(0) + " Faith");
			line(out, DIM, "One belief that helps all your cities.");
		} else {
			if (religion.isPantheon() && faith.remainingFoundableReligions() > 0) {
				line(out, TEXT, "Next: a Great Prophet at about " + faith.faithForNextGreatProphet() + " Faith, to found a religion");
			} else if (!religion.isPantheon() && !religion.isEnhancedReligion()) {
				line(out, TEXT, "Next: a Great Prophet at about " + faith.faithForNextGreatProphet() + " Faith, to add two beliefs");
			}
			line(out, HEAD, religion.isPantheon() ? "Your pantheon" : "Your religion: " + UncivLink.words(religion.getReligionDisplayName()));
			for (Belief belief : Kt.list(religion.getAllBeliefsOrdered())) {
				line(out, GOOD, beliefText(belief));
			}
			if (!religion.isPantheon()) {
				line(out, DIM, "Followed by " + faith.numberOfCitiesFollowingThisReligion() + (faith.numberOfCitiesFollowingThisReligion() == 1 ? " city" : " cities")
					+ ". Spread by Missionaries and Prophets, bought with Faith.");
			} else if (faith.remainingFoundableReligions() <= 0) {
				line(out, DIM, "Every religion the world has room for has been founded.");
			}
		}
		if (!viewer.getCities().isEmpty()) {
			line(out, HEAD, "Your cities");
			for (City city : viewer.getCities()) {
				String major = city.getReligion().getMajorityReligionName();
				Religion held = city.getReligion().getMajorityReligion();
				line(out, major == null ? DIM : TEXT, UncivLink.words(city.getName()) + ": " + (major == null || held == null ? "no religion"
					: UncivLink.words(held.getReligionDisplayName()) + "  " + city.getReligion().getFollowersOfMajorityReligion() + "/" + city.getPopulation().getPopulation()));
			}
		}
		boolean any = false;
		for (Religion other : game.getReligions().values()) {
			if (other.isPantheon() || other == religion) {
				continue;
			}
			Civilization founder = other.getFoundingCiv();
			if (founder != viewer && !viewer.knows(founder)) {
				continue;
			}
			if (!any) {
				line(out, HEAD, "Other religions");
				any = true;
			}
			line(out, TEXT, UncivLink.words(other.getReligionDisplayName()) + ", founded by " + founder.getCivName());
			for (Belief belief : Kt.list(other.getAllBeliefsOrdered())) {
				line(out, DIM, "  " + beliefText(belief));
			}
		}
		return out;
	}

	/** How near everyone is to winning, and how the civilizations compare. */
	List<Records.Option> victory() {
		List<Records.Option> out = new ArrayList<>();
		List<Civilization> known = new ArrayList<>();
		known.add(viewer);
		for (Civilization civ : game.getCivilizations()) {
			if (civ != viewer && civ.isMajorCiv() && !civ.isDefeated() && viewer.knows(civ)) {
				known.add(civ);
			}
		}
		List<String> enabled = game.getGameParameters().getVictoryTypes();
		for (Map.Entry<String, Victory> entry : game.getRuleset().getVictories().entrySet()) {
			if (!enabled.contains(entry.getKey())) {
				continue;
			}
			Victory victory = entry.getValue();
			line(out, HEAD, UncivLink.words(entry.getKey()) + " victory");
			List<Milestone> milestones = victory.getMilestoneObjects();
			for (Milestone milestone : milestones) {
				boolean done = milestone.hasBeenCompletedBy(viewer);
				String text;
				try {
					text = UncivLink.words(milestone.getVictoryScreenButtonHeaderText(done, viewer));
				} catch (Throwable e) {
					text = UncivLink.words(milestone.getUniqueDescription());
				}
				line(out, done ? GOOD : TEXT, (done ? "\u2714 " : "\u25CB ") + text);
			}
			StringBuilder race = new StringBuilder();
			for (Civilization civ : known) {
				if (civ != viewer) {
					race.append(race.length() == 0 ? "" : ", ").append(civ.getCivName()).append(' ').append(civ.getVictoryManager().amountMilestonesCompleted(victory)).append('/').append(milestones.size());
				}
			}
			if (race.length() > 0) {
				line(out, DIM, "   " + race);
			}
		}
		if (enabled.contains("Time") || game.getRuleset().getVictories().values().stream().anyMatch(Victory::enablesMaxTurns)) {
			int last = game.getGameParameters().getMaxTurns();
			line(out, DIM, "Ends after turn " + last + " (now turn " + game.getTurns() + "): the highest score wins");
		}
		line(out, HEAD, "Score");
		known.sort((one, other) -> Double.compare(other.calculateTotalScore(), one.calculateTotalScore()));
		int place = 1;
		for (Civilization civ : known) {
			line(out, civ == viewer ? GOOD : TEXT, place++ + ". " + civ.getCivName() + ": " + Math.round(civ.calculateTotalScore()));
		}
		line(out, HEAD, "How you compare");
		for (com.unciv.ui.screens.victoryscreen.RankingType type : com.unciv.ui.screens.victoryscreen.RankingType.values()) {
			if (type == com.unciv.ui.screens.victoryscreen.RankingType.Score) {
				continue;
			}
			try {
				int mine = viewer.getStatForRanking(type);
				int rank = 1;
				int best = mine;
				String bestName = "you";
				for (Civilization civ : known) {
					int theirs = civ.getStatForRanking(type);
					if (civ != viewer && theirs > mine) {
						rank++;
					}
					if (theirs > best) {
						best = theirs;
						bestName = civ.getCivName();
					}
				}
				line(out, rank == 1 ? GOOD : TEXT, UncivLink.words(type.name()) + ": " + mine + "   #" + rank + " of " + known.size() + (rank == 1 ? "" : "   (" + bestName + " " + best + ")"));
			} catch (Throwable e) {
				// A measure Unciv only has with its screens is left out.
			}
		}
		return out;
	}

	/** One city of the player's in full: what it makes, when it grows, what stands in it. */
	List<Records.Option> city(int id) {
		List<Records.Option> out = new ArrayList<>();
		City city = actions.ownCity(id);
		if (city == null) {
			return out;
		}
		Stats stats = city.getCityStats().getCurrentCityStats();
		line(out, HEAD, "Each turn");
		line(out, TEXT, signed(stats.getFood()) + " Food   " + round(stats.getProduction()) + " Production   " + signed(stats.getGold()) + " Gold   " + round(stats.getScience())
			+ " Science   " + round(stats.getCulture()) + " Culture" + (game.isReligionEnabled() ? "   " + round(stats.getFaith()) + " Faith" : ""));
		Integer grows = city.getPopulation().getNumTurnsToNewPopulation();
		Integer starves = city.getPopulation().getNumTurnsToStarvation();
		String food = city.getPopulation().getFoodStored() + "/" + city.getPopulation().getFoodToNextPopulation() + " Food";
		if (city.isStarving()) {
			line(out, BAD, "Starving: loses a citizen " + (starves == null ? "soon" : "in " + starves + " turns") + "   " + food);
		} else if (grows != null) {
			line(out, GOOD, "Grows in " + grows + " turns   " + food);
		} else {
			line(out, TEXT, "Not growing   " + food);
		}
		int toTile = city.getExpansion().getCultureToNextTile() - city.getExpansion().getCultureStored();
		line(out, TEXT, stats.getCulture() > 0 ? "Border grows in " + Math.max(1, (int) Math.ceil(toTile / stats.getCulture())) + " turns" : "Border does not grow (no Culture)");
		line(out, TEXT, new CityCombatant(city).getDefendingStrength(null) + " Strength   " + city.getHealth() + "/" + city.getMaxHealth$Unciv_core() + " health"
			+ (city.canBombard() ? "   can fire this turn" : ""));
		if (game.isReligionEnabled()) {
			Religion held = city.getReligion().getMajorityReligion();
			line(out, held == null ? DIM : TEXT, held == null ? "No religion"
				: UncivLink.words(held.getReligionDisplayName()) + "  " + city.getReligion().getFollowersOfMajorityReligion() + "/" + city.getPopulation().getPopulation() + " citizens");
		}
		List<String> queue = city.getCityConstructions().getConstructionQueue();
		if (queue.size() > 1) {
			line(out, HEAD, "Lined up");
			for (int i = 1; i < queue.size(); i++) {
				line(out, TEXT, i + ". " + UncivLink.words(queue.get(i)));
			}
		}
		Counter<String> great = city.getGreatPersonPoints();
		if (!great.isEmpty()) {
			line(out, HEAD, "Great People");
			for (Map.Entry<String, Integer> entry : great.entrySet()) {
				int have = viewer.getGreatPeople().getGreatPersonPointsCounter().get(entry.getKey());
				line(out, TEXT, UncivLink.words(entry.getKey()) + ": +" + entry.getValue() + " a turn   " + have + "/" + viewer.getGreatPeople().getPointsRequiredForGreatPerson(entry.getKey()));
			}
		}
		List<Building> built = built(city);
		if (!built.isEmpty()) {
			line(out, HEAD, "Buildings");
			for (Building building : built) {
				String effect;
				try {
					effect = lines(building.getShortDescription(true, unique -> true)).replace("\n", "; ");
				} catch (Throwable e) {
					effect = rules(building.getUniques()).replace("\n", "; ");
				}
				line(out, TEXT, UncivLink.words(building.getName()) + (effect.isEmpty() ? "" : ": " + effect) + (building.getMaintenance() > 0 ? "   (-" + building.getMaintenance() + " Gold)" : ""));
			}
		}
		Map<String, Float> happy = city.getCityStats().getHappinessList();
		if (!happy.isEmpty()) {
			line(out, HEAD, "Happiness here");
			for (Map.Entry<String, Float> entry : happy.entrySet()) {
				if (Math.abs(entry.getValue()) >= 0.5f) {
					line(out, entry.getValue() < 0 ? BAD : TEXT, UncivLink.words(entry.getKey()) + ": " + signed(entry.getValue()));
				}
			}
		}
		return out;
	}

	// ---- spies ----

	private static String doing(com.unciv.models.Spy spy) {
		String turns = spy.getTurnsRemainingForAction() > 0 ? ", " + spy.getTurnsRemainingForAction() + " more turns" : "";
		return switch (spy.getAction()) {
			case None -> "waiting in the hideout for orders";
			case Moving -> "on the way" + turns;
			case EstablishNetwork -> "settling in" + turns;
			case Surveillance -> "watching the city";
			case StealingTech -> "after a technology" + turns;
			case RiggingElections -> "rigging the elections" + turns;
			case Coup -> "staging a coup";
			case CounterIntelligence -> "guarding against other spies";
			case Dead -> "killed; a successor is on the way" + turns;
		};
	}

	/**
	 * The player's spies (group 28): the id each one's place in the list, the name who and where
	 * and at what, the last number 1 when a coup can be staged where it is. Empty in a game
	 * without spies.
	 */
	List<Records.Option> spies() {
		List<Records.Option> out = new ArrayList<>();
		if (!game.isEspionageEnabled()) {
			return out;
		}
		List<com.unciv.models.Spy> all = viewer.getEspionageManager().getSpyList();
		for (int i = 0; i < all.size(); i++) {
			com.unciv.models.Spy spy = all.get(i);
			City at = spy.getCityOrNull();
			String where = at == null ? "" : " in " + UncivLink.words(at.getName()) + " (" + at.getCiv().getCivName() + ")";
			out.add(new Records.Option(28, i, spy.isAlive() ? "spy" : "dead", UncivLink.words(spy.getName()) + ", rank " + spy.getRank() + where + ": " + doing(spy),
				spy.isAlive() && spy.canDoCoup() ? 1 : 0));
		}
		return out;
	}

	/** Where one of the player's spies could be sent (group 29): the id the city, the type its owner's number, the name the city and whose it is. */
	List<Records.Option> spyTargets(int index) {
		List<Records.Option> out = new ArrayList<>();
		com.unciv.models.Spy spy = spy(index);
		if (spy == null || !spy.isAlive()) {
			return out;
		}
		for (City city : Kt.list(game.getCities())) {
			Tile centre = city.getCenterTileOrNull();
			if (centre == null || !centre.isExplored(viewer) || city == spy.getCityOrNull() || !spy.canMoveTo(city)) {
				continue;
			}
			Civilization owner = city.getCiv();
			String what = owner == viewer ? "yours: it guards the city against other spies" : owner.isCityState() ? "a city-state: it rigs the elections for your influence"
				: owner.getCivName() + ": it steals what they know and you do not";
			out.add(new Records.Option(29, UncivSnapshot.cityId(city), Integer.toString(snapshot.civId(owner)), UncivLink.words(city.getName()) + " (" + what + ")", owner == viewer ? 0 : 1));
		}
		return out;
	}

	com.unciv.models.Spy spy(int index) {
		if (!game.isEspionageEnabled()) {
			return null;
		}
		List<com.unciv.models.Spy> all = viewer.getEspionageManager().getSpyList();
		return index < 0 || index >= all.size() ? null : all.get(index);
	}

	/** What a city-state asks of the player, in full: what, how long it stands, and what it is worth. */
	List<Records.Option> quests(Civilization cityState) {
		List<Records.Option> out = new ArrayList<>();
		if (cityState == null || !cityState.isCityState()) {
			return out;
		}
		var asked = Kt.list(cityState.getQuestManager().getAssignedQuestsFor(viewer.getCivName()));
		if (asked.isEmpty()) {
			line(out, TEXT, cityState.getCivName() + " asks nothing of you at the moment. Requests come every thirty turns or so.");
		}
		for (var quest : asked) {
			line(out, HEAD, UncivLink.words(quest.getQuestName()));
			String text;
			try {
				text = UncivLink.words(quest.getDescription());
			} catch (Throwable e) {
				text = "";
			}
			if (!text.isEmpty()) {
				line(out, TEXT, text);
			}
			var rules = game.getRuleset().getQuests().get(quest.getQuestName());
			String reward = rules == null ? "" : "Worth " + Math.round(rules.getInfluence()) + " influence. ";
			line(out, DIM, reward + (quest.doesExpire() ? "It stands for " + quest.getRemainingTurns() + " more turns." : "It stands until it is done."));
			try {
				String race = UncivLink.words(cityState.getQuestManager().getScoreStringForGlobalQuest(quest));
				if (!race.isEmpty()) {
					line(out, DIM, race);
				}
			} catch (Throwable e) {
				// A request without a race for it.
			}
		}
		var relations = cityState.getDiplomacyManager(viewer);
		if (relations != null) {
			line(out, HEAD, "Where you stand");
			line(out, TEXT, "Influence " + Math.round(relations.getInfluence()) + " (friends from 30, allies from 60 when nobody has more). It falls back towards its resting point every turn.");
		}
		return out;
	}

	/** Where the empire's happiness comes from and goes, with the golden age it saves up for. */
	List<Records.Option> happiness() {
		List<Records.Option> out = new ArrayList<>();
		line(out, HEAD, "Happiness: " + viewer.getHappiness());
		for (Map.Entry<String, Float> entry : viewer.getStats().getHappinessBreakdown().entrySet()) {
			if (Math.abs(entry.getValue()) >= 0.5f) {
				line(out, entry.getValue() < 0 ? BAD : TEXT, UncivLink.words(entry.getKey()) + ": " + signed(entry.getValue()));
			}
		}
		var golden = viewer.getGoldenAges();
		line(out, HEAD, "Golden age");
		if (golden.isGoldenAge()) {
			line(out, GOOD, "On: " + golden.getTurnsLeftForCurrentGoldenAge() + " more turns of extra Production, Gold and Culture");
		} else {
			line(out, TEXT, golden.getStoredHappiness() + "/" + golden.happinessRequiredForNextGoldenAge() + " spare Happiness saved up");
		}
		return out;
	}
}
