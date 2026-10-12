package nl.jordythomassen.livingworld.unciv;

import com.unciv.logic.GameInfo;
import com.unciv.models.ruleset.unique.UniqueType;
import com.unciv.logic.battle.AttackableTile;
import com.unciv.logic.battle.Battle;
import com.unciv.logic.battle.MapUnitCombatant;
import com.unciv.logic.battle.TargetHelper;
import com.unciv.logic.city.City;
import com.unciv.logic.city.CityConstructions;
import com.unciv.logic.civilization.Civilization;
import com.unciv.logic.civilization.PlayerType;
import com.unciv.logic.civilization.diplomacy.DiplomacyManager;
import com.unciv.logic.map.mapunit.MapUnit;
import com.unciv.logic.map.mapunit.movement.PathsToTilesWithinTurn;
import com.unciv.logic.map.mapunit.movement.UnitMovement;
import com.unciv.logic.map.tile.Tile;
import com.unciv.models.UnitAction;
import com.unciv.models.UnitActionType;
import com.unciv.models.ruleset.Building;
import com.unciv.models.ruleset.INonPerpetualConstruction;
import com.unciv.models.ruleset.PerpetualConstruction;
import com.unciv.models.ruleset.Policy;
import com.unciv.models.ruleset.PolicyBranch;
import com.unciv.models.ruleset.tech.Technology;
import com.unciv.models.ruleset.tile.TileImprovement;
import com.unciv.models.ruleset.unit.BaseUnit;
import com.unciv.models.ruleset.unit.Promotion;
import com.unciv.models.stats.Stat;
import com.unciv.logic.automation.unit.UnitAutomation;
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions;
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsPillage;
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsUpgrade;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import nl.jordythomassen.livingworld.commands.GameCommand;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.protocol.Records;
import nl.jordythomassen.livingworld.state.EntityKey;

/**
 * Everything a player can ask of the game, carried out on Unciv's own game logic: the same calls
 * its screens and its AI make. Nothing here decides a rule; when Unciv says no, the answer is no.
 */
final class UncivActions {
	record Result(boolean done, String message, long before, long after) {
		static Result refused(String why) {
			return new Result(false, why, Long.MIN_VALUE, Long.MIN_VALUE);
		}

		static Result done(String what) {
			return new Result(true, what, Long.MIN_VALUE, Long.MIN_VALUE);
		}

		static Result done(String what, long before, long after) {
			return new Result(true, what, before, after);
		}
	}

	private final GameInfo game;
	private final UncivSnapshot snapshot;
	private final Civilization viewer;
	private final Consumer<String> log;
	private final UncivDiplomacy diplomacy;
	private final UncivFaith faith;
	private final UncivTrade trade;
	private final UncivInfo info;

	UncivActions(GameInfo game, UncivSnapshot snapshot, Civilization viewer, Consumer<String> log) {
		this.game = game;
		this.snapshot = snapshot;
		this.viewer = viewer;
		this.log = log;
		this.diplomacy = new UncivDiplomacy(game, viewer);
		this.faith = new UncivFaith(game, viewer);
		this.trade = new UncivTrade(game, viewer);
		this.info = new UncivInfo(game, snapshot, viewer, this);
	}

	/** A turn has passed: deals half put together are off the table. */
	void newTurn() {
		trade.newTurn();
	}

	UncivDiplomacy diplomacy() {
		return diplomacy;
	}

	Result apply(GameCommand command) {
		return switch (command.op()) {
			case UNIT_MOVE_TO -> move(command);
			case UNIT_ACTION -> unitAction(command);
			case UNIT_PROMOTE -> promote(command);
			case UNIT_HEAL -> heal(command);
			case CITY_ADD_PRODUCTION -> addProduction(command);
			case CITY_BUILD -> build(command);
			case CITY_BUY -> buy(command);
			case RESEARCH -> research(command);
			case POLICY -> policy(command);
			case REVEAL -> reveal(command);
			case IMPROVE -> improve(command);
			case GIFT -> gift(command);
			case DECLARE_WAR -> war(command);
			case MAKE_PEACE -> diplomacy.step(snapshot.civ(command.target().id()), "peace");
			case DIPLOMACY -> diplomacy.step(snapshot.civ(command.target().id()), command.item());
			case ANSWER -> diplomacy.answer(command.item(), command.amount() == 1);
			case CITY_FATE -> fate(command);
			case BELIEF -> faith.choose(command.item());
			case TRADE -> trade.step(snapshot.civ(command.target().id()), command.item());
			case CITY_WORK -> work(command);
			case CHOICE -> choice(command);
			default -> Result.refused("That is not possible yet.");
		};
	}

	/**
	 * A last look before a turn ends. Beliefs are the player's to choose and the turn waits for
	 * that; should a choice be open all the same, the priests make it the way the other
	 * civilizations do, so that it is never lost. Returns what to tell the player, or null.
	 */
	String beforeEndOfTurn() {
		if (viewer.isMajorCiv() && !viewer.isDefeated() && viewer.mayVoteForDiplomaticVictory()) {
			// The turn waits for the player's vote; one never cast is an abstention.
			viewer.diplomaticVoteForCiv(null);
		}
		if (UncivFaith.open(viewer).isEmpty()) {
			return null;
		}
		try {
			com.unciv.logic.automation.civilization.ReligionAutomation.INSTANCE.chooseReligiousBeliefs$Unciv_core(viewer);
		} catch (RuntimeException e) {
			log.accept("Living World: choosing beliefs failed: " + e);
			return null;
		}
		return "Your priests have settled what was left open in matters of faith.";
	}

	/** Cities with nothing to do pick their own work, as a governor would. */
	void governorsChoose() {
		for (City city : viewer.getCities()) {
			city.getCityConstructions().chooseNextConstruction();
		}
	}

	// ---- units ----

	MapUnit ownUnit(EntityKey key) {
		MapUnit unit = key == null ? null : snapshot.unit(key);
		return unit != null && unit.getCiv() == viewer ? unit : null;
	}

	private Result move(GameCommand command) {
		MapUnit unit = ownUnit(command.target());
		if (unit == null) {
			return Result.refused("That unit is not yours to command.");
		}
		Tile destination = snapshot.map().tile(command.plot());
		if (destination == null || !destination.isExplored(viewer)) {
			return Result.refused("That place is not on your map.");
		}
		Tile from = unit.getTile();
		if (destination == from) {
			return Result.done("Already there.", 0, 0);
		}
		int before = from.aerialDistanceTo(destination);
		AttackableTile target = attackable(unit, destination);
		if (target != null) {
			// A nuclear weapon is aimed at a place, not at somebody: there may be nobody there to name.
			String foe = target.getCombatant() == null ? null : target.getCombatant().getName();
			boolean nuclear = unit.isNuclearWeapon();
			String weapon = unit.getBaseUnit().getName();
			if (nuclear) {
				// Unciv's ordinary attack wants somebody on the tile; its own nuclear button calls this instead.
				com.unciv.logic.battle.Nuke.INSTANCE.NUKE(new MapUnitCombatant(unit), destination);
			} else {
				Battle.INSTANCE.moveAndAttack(new MapUnitCombatant(unit), target);
			}
			return Result.done(nuclear ? "The " + weapon + " has fallen" + (foe == null ? "." : " on " + foe + ".") : "Attacked " + (foe == null ? "the place" : foe) + ".", before, 0);
		}
		String never = cannotEnter(unit, destination);
		if (never != null) {
			return Result.refused(never);
		}
		UnitMovement movement = unit.getMovement();
		if (!movement.canReach(destination)) {
			return Result.refused(noWay(unit, destination));
		}
		String order = "moveTo " + destination.getPosition().getX() + "," + destination.getPosition().getY();
		if (!unit.hasMovement()) {
			unit.setAction(order);
			return Result.done("It has no moves left this turn and will set out with the next.", before, before);
		}
		Tile reached;
		try {
			reached = movement.headTowards(destination);
		} catch (Exception e) {
			return Result.refused(noWay(unit, destination));
		}
		if (unit.getTile() != destination) {
			unit.setAction(order);
		} else if (unit.isMoving()) {
			unit.setAction(null);
		}
		return Result.done(reached == destination ? "Arrived." : "On its way; it walks on over the coming turns.", before, reached.aerialDistanceTo(destination));
	}

	/**
	 * Why this kind of unit can never stand on that tile as things are, or null when it could: a
	 * mountain, the sea before there are ships, land for a ship. Asked before any way there is looked
	 * for: Unciv's own search sets a unit off towards a place it can never enter, to stop at the shore.
	 */
	private String cannotEnter(MapUnit unit, Tile destination) {
		if (unit.getBaseUnit().isAirUnit()) {
			return null;
		}
		if (destination.isImpassible() && !destination.isCityCenter()) {
			return "Nobody can cross that (" + UncivLink.words(destination.getLastTerrain().getName()) + " is impassable).";
		}
		if (unit.getBaseUnit().isLandUnit() && destination.isWater()) {
			if (!viewer.getTech().getUnitsCanEmbark()) {
				return "Your people cannot take to the water yet. Crossing it comes with Optics.";
			}
			if (destination.isOcean() && !viewer.getTech().getEmbarkedUnitsCanEnterOcean() && !viewer.getTech().getAllUnitsCanEnterOcean()) {
				return "The open ocean is beyond your people until Astronomy; the coast is not.";
			}
		}
		if (unit.getBaseUnit().isWaterUnit() && destination.isLand() && !destination.isCityCenter()) {
			return "A " + UncivLink.words(unit.getBaseUnit().getName()) + " cannot go on land.";
		}
		return null;
	}

	/** Why a unit cannot go somewhere, as far as that can be said: the sea before ships, a mountain, closed borders, somebody in the way. */
	private String noWay(MapUnit unit, Tile destination) {
		String who = UncivLink.words(unit.getBaseUnit().getName());
		String never = cannotEnter(unit, destination);
		if (never != null) {
			return never;
		}
		Civilization owner = destination.getOwner();
		if (owner != null && owner != viewer && !viewer.isAtWarWith(owner) && !viewer.getDiplomacyFunctions().canPassThroughTiles(owner)) {
			return owner.getCivName() + " keeps its borders closed to you. Open borders are part of a deal; war opens them too.";
		}
		if (destination.isCityCenter() && owner != viewer) {
			return "That city is not yours to walk into.";
		}
		MapUnit there = unit.isCivilian() ? destination.getCivilianUnit() : destination.getMilitaryUnit();
		if (there != null && there != unit && there.getCiv() == viewer) {
			return "One of your own already stands there, and two of a kind do not share a hex.";
		}
		return "There is no way for this " + who + " to get there: something on the way cannot be crossed.";
	}

	/** The fight a move to that tile would start, or null when moving there is just moving. */
	AttackableTile attackable(MapUnit unit, Tile destination) {
		if (!unit.canAttack()) {
			return null;
		}
		// Kotlin's default arguments, spelled out: with zones of control, no caches, the escort counted in; every tile, free to move.
		PathsToTilesWithinTurn paths = unit.getMovement().getDistanceToTiles(true, null, null, true);
		ArrayList<AttackableTile> targets = TargetHelper.INSTANCE.getAttackableEnemies(unit, paths, null, false);
		for (AttackableTile target : targets) {
			if (target.getTileToAttack() == destination) {
				return target;
			}
		}
		return null;
	}

	/** One thing a unit can do where it stands. {@code run} is null when it could, but not right now. */
	private record Act(String key, String title, Runnable run) {
	}

	/** The kinds of action Unciv can list one at a time without its own screen in front of the player. */
	private static final List<UnitActionType> LISTED = List.of(UnitActionType.FoundCity, UnitActionType.SetUp, UnitActionType.Guard, UnitActionType.Repair,
		UnitActionType.CreateImprovement, UnitActionType.HurryResearch, UnitActionType.HurryPolicy, UnitActionType.HurryWonder, UnitActionType.HurryBuilding,
		UnitActionType.ConductTradeMission, UnitActionType.FoundReligion, UnitActionType.EnhanceReligion, UnitActionType.SpreadReligion,
		UnitActionType.RemoveHeresy, UnitActionType.AddInCapital, UnitActionType.Transform, UnitActionType.TriggerUnique);

	/**
	 * What a unit can do where it stands. The special actions (found a city, a Great Person's deeds,
	 * religion) come from Unciv's own lists; the everyday orders (fortify, sleep, skip, explore,
	 * automate, pillage, upgrade, disband) Unciv only assembles together with its screen, so they
	 * are made here from the same few calls its buttons make.
	 */
	private List<Act> unitActs(MapUnit unit) {
		List<Act> out = new ArrayList<>();
		for (UnitActionType type : LISTED) {
			try {
				int nth = 0;
				for (UnitAction action : Kt.list(UnitActions.INSTANCE.getUnitActions(unit, type))) {
					// The first of a kind goes by the kind's name; a second one (two improvements a Great Person can make) by its title as well.
					String title = UncivLink.words(action.getTitle());
					String key = nth++ == 0 ? type.name() : type.name() + ":" + title;
					out.add(new Act(key, title, action.getAction() == null ? null : () -> action.getAction().invoke()));
				}
			} catch (RuntimeException e) {
				// A kind of action Unciv cannot list without its screen is simply not on offer.
			}
		}
		boolean moves = unit.hasMovement();
		Tile tile = unit.getTile();
		try {
			if (UnitActionsPillage.INSTANCE.canPillage(unit, tile)) {
				UnitAction pillage = UnitActionsPillage.INSTANCE.getPillageAction$Unciv_core(unit, tile);
				if (pillage != null) {
					out.add(new Act("Pillage", UncivLink.words(pillage.getTitle()), pillage.getAction() == null ? null : () -> pillage.getAction().invoke()));
				}
			}
			for (UnitAction upgrade : Kt.list(UnitActionsUpgrade.INSTANCE.getUpgradeActions(unit))) {
				out.add(new Act("Upgrade:" + upgrade.getTitle(), UncivLink.words(upgrade.getTitle()), upgrade.getAction() == null ? null : () -> upgrade.getAction().invoke()));
			}
		} catch (RuntimeException e) {
			// As above.
		}
		if (unit.isFortified() || unit.isSleeping() || unit.isMoving() || unit.isExploring() || unit.isAutomated()) {
			String doing = unit.isFortified() ? "Stop fortifying" : unit.isSleeping() ? "Wake up" : unit.isMoving() ? "Stop where it is" : unit.isExploring()
				? "Stop exploring" : "Stop working by itself";
			out.add(new Act("Stop", doing, () -> {
				unit.setAutomated(false);
				unit.setAction(null);
			}));
		}
		if (!unit.isFortified() && unit.canFortify(false)) {
			out.add(new Act("Fortify", "Fortify", moves ? unit::fortify : null));
			if (unit.getHealth() < 100) {
				out.add(new Act("FortifyUntilHealed", "Fortify until healed", moves ? unit::fortifyUntilHealed : null));
			}
		} else if (!unit.isSleeping() && !unit.isFortified()) {
			out.add(new Act("Sleep", "Sleep", () -> unit.setAction(UnitActionType.Sleep.getValue())));
		}
		if (!unit.isExploring() && !unit.getBaseUnit().isAirUnit()) {
			out.add(new Act("Explore", "Explore by itself", () -> {
				unit.setAction(UnitActionType.Explore.getValue());
				if (unit.hasMovement()) {
					UnitAutomation.INSTANCE.automatedExplore(unit);
				}
			}));
		}
		if (unit.getCache().getHasUniqueToBuildImprovements()) {
			// A Worker's own trade: what it could build on the hex it stands on, each with the turns it takes.
			Tile here = unit.getTile();
			for (TileImprovement improvement : game.getRuleset().getTileImprovements().values()) {
				String name = improvement.getName();
				if (name.startsWith("Cancel") || name.equals(here.getImprovement()) || name.equals(here.getImprovementInProgress())) {
					continue;
				}
				try {
					if (!unit.canBuildImprovement(improvement, here) || !here.getImprovementFunctions().canBuildImprovement(improvement, unit.getCache().getState())) {
						continue;
					}
					int turns = improvement.getTurnsToBuild(viewer, unit);
					out.add(new Act("Build:" + name, "Build: " + UncivLink.words(name) + " (" + turns + (turns == 1 ? " turn)" : " turns)"),
						moves ? () -> here.startWorkingOnImprovement(improvement, viewer, unit) : null));
				} catch (RuntimeException e) {
					// What Unciv cannot judge here is not on offer.
				}
			}
			if (here.getImprovementInProgress() != null) {
				out.add(new Act("StopBuilding", "Stop building the " + UncivLink.words(here.getImprovementInProgress()), here::stopWorkingOnImprovement));
			}
		}
		if (!unit.isAutomated()) {
			out.add(new Act("Automate", unit.isCivilian() ? "Work by itself" : "Act by itself", moves ? () -> {
				unit.setAutomated(true);
				UnitAutomation.INSTANCE.automateUnitMoves(unit);
			} : null));
		}
		if (unit.getDue()) {
			out.add(new Act("Skip", "Wait this turn", moves ? () -> unit.setDue(false) : null));
		}
		out.add(new Act("Disband", "Disband", unit::disband));
		return out;
	}

	private Result unitAction(GameCommand command) {
		MapUnit unit = ownUnit(command.target());
		if (unit == null) {
			return Result.refused("That unit is not yours to command.");
		}
		for (Act act : unitActs(unit)) {
			if (!act.key().equals(command.item())) {
				continue;
			}
			if (act.run() == null) {
				return Result.refused("It cannot do that right now.");
			}
			try {
				act.run().run();
			} catch (Throwable first) {
				// Some actions ask a human for confirmation on Unciv's own screen. Asked as the AI would, they just happen.
				Result again = asAi(unit, command.item());
				if (again != null) {
					return again;
				}
				log.accept("Living World: unit action " + command.item() + " failed: " + first + UncivLink.where(first));
				return Result.refused("The game could not do that.");
			}
			return Result.done(act.title() + ".");
		}
		return Result.refused("That is not something this unit can do here.");
	}

	private Result asAi(MapUnit unit, String key) {
		viewer.setPlayerType(PlayerType.AI);
		try {
			for (Act act : unitActs(unit)) {
				if (act.key().equals(key) && act.run() != null) {
					act.run().run();
					return Result.done(act.title() + ".");
				}
			}
		} catch (Throwable e) {
			return null;
		} finally {
			viewer.setPlayerType(PlayerType.Human);
		}
		return null;
	}

	private Result promote(GameCommand command) {
		MapUnit unit = ownUnit(command.target());
		if (unit == null) {
			return Result.refused("That unit is not yours to command.");
		}
		if (!unit.getPromotions().canBePromoted()) {
			return Result.refused("It has not earned a promotion.");
		}
		for (Promotion promotion : Kt.list(unit.getPromotions().getAvailablePromotions())) {
			if (("PROMOTION_" + Names.key(promotion.getName())).equals(command.item())) {
				unit.getPromotions().addPromotion(promotion.getName(), false);
				return Result.done("Promoted: " + promotion.getName() + ".");
			}
		}
		return Result.refused("That promotion is not open to it.");
	}

	private Result heal(GameCommand command) {
		MapUnit unit = ownUnit(command.target());
		if (unit == null) {
			return Result.refused("That unit is not yours to care for.");
		}
		int before = unit.getHealth();
		if (before >= 100) {
			return Result.refused("It is not wounded.");
		}
		unit.healBy(Math.max(1, Math.min(50, command.amount())));
		return Result.done("Treated.", before, unit.getHealth());
	}

	// ---- cities ----

	City ownCity(EntityKey key) {
		City city = key == null ? null : snapshot.city(key);
		return city != null && city.getCiv() == viewer ? city : null;
	}

	City ownCity(int id) {
		return ownCity(new EntityKey(snapshot.civId(viewer), id));
	}

	/** Everything a city can start on now: key, the construction's name in Unciv, and the kind (0 unit, 1 building, 3 conversion). */
	Map<String, Object[]> buildable(City city) {
		Map<String, Object[]> out = new LinkedHashMap<>();
		CityConstructions constructions = city.getCityConstructions();
		for (Building building : Kt.list(constructions.getBuildableBuildings$Unciv_core())) {
			out.put("BUILDING_" + Names.key(building.getName()), new Object[] { building.getName(), 1 });
		}
		for (BaseUnit unit : Kt.list(constructions.getConstructableUnits())) {
			out.put("UNIT_" + Names.key(unit.getName()), new Object[] { unit.getName(), 0 });
		}
		for (PerpetualConstruction perpetual : PerpetualConstruction.Companion.getPerpetualConstructionsMap().values()) {
			if (!perpetual.getName().isEmpty() && !perpetual.getName().equals("Nothing") && perpetual.isBuildable(constructions)) {
				out.put("PROCESS_" + Names.key(perpetual.getName()), new Object[] { perpetual.getName(), 3 });
			}
		}
		return out;
	}

	private Result build(GameCommand command) {
		City city = ownCity(command.target());
		if (city == null) {
			return Result.refused("That city is not yours.");
		}
		if (city.isPuppet()) {
			return Result.refused("A puppet city chooses for itself.");
		}
		CityConstructions constructions = city.getCityConstructions();
		if (command.item().startsWith("unqueue:")) {
			// One of the things lined up after what the city builds now is taken out again; the number is its place in the line.
			int place;
			try {
				place = Integer.parseInt(command.item().substring(8));
			} catch (NumberFormatException e) {
				return Result.refused("That is not in the line.");
			}
			if (place < 1 || place >= constructions.getConstructionQueue().size()) {
				return Result.refused("That is not in the line.");
			}
			String gone = constructions.getConstructionQueue().get(place);
			constructions.removeFromQueue(place, false);
			return Result.done(UncivLink.words(gone) + " is out of the line in " + city.getName() + ".");
		}
		boolean after = command.item().startsWith("queue:");
		Object[] choice = buildable(city).get(after ? command.item().substring(6) : command.item());
		if (choice == null) {
			return Result.refused(city.getName() + " cannot build that right now.");
		}
		if (after) {
			// Lined up behind what the city builds now: it starts on it by itself when that is done.
			String current = constructions.currentConstructionName();
			if (current == null || current.isEmpty() || current.equals("Nothing")) {
				constructions.setCurrentConstruction((String) choice[0]);
				constructions.setCurrentConstructionIsUserSet(true);
				return Result.done(city.getName() + " has new orders.");
			}
			var thing = constructions.getConstruction$Unciv_core((String) choice[0]);
			if (constructions.isQueueFull() || !constructions.canAddToQueue(thing)) {
				return Result.refused("That cannot be lined up in " + city.getName() + " (the line is full, or it is in it already).");
			}
			constructions.addToQueue((String) choice[0], null);
			return Result.done(UncivLink.words((String) choice[0]) + " is lined up in " + city.getName() + ".");
		}
		city.getCityConstructions().setCurrentConstruction((String) choice[0]);
		city.getCityConstructions().setCurrentConstructionIsUserSet(true);
		return Result.done(city.getName() + " has new orders.");
	}

	private Result buy(GameCommand command) {
		City city = ownCity(command.target());
		if (city == null) {
			return Result.refused("That city is not yours.");
		}
		if (command.item().equals("land")) {
			// Land next to what the city holds, bought for it from the treasury.
			Tile tile = snapshot.map().tile(command.plot());
			if (tile == null || !city.getExpansion().canBuyTile(tile)) {
				return Result.refused(city.getName() + " cannot buy that land.");
			}
			int price = city.getExpansion().getGoldCostOfTile(tile, 0);
			if (viewer.getGold() < price) {
				return Result.refused("That land costs " + price + " gold; your treasury holds " + viewer.getGold() + ".");
			}
			int had = viewer.getGold();
			city.getExpansion().buyTile(tile);
			return Result.done("The land now belongs to " + city.getName() + ".", had, viewer.getGold());
		}
		if (command.item().startsWith("faith:")) {
			// Missionaries, prophets and the buildings of a religion are had for faith, never for work or gold.
			INonPerpetualConstruction holy = info.forFaith(city).get(command.item().substring(6));
			if (holy == null) {
				return Result.refused(city.getName() + " cannot buy that with faith.");
			}
			Integer price = holy.getStatBuyCost(city, Stat.Faith);
			int stored = viewer.getReligionManager().getStoredFaith();
			if (price == null || stored < price) {
				return Result.refused("That takes " + price + " faith; your people have " + stored + ".");
			}
			if (!city.getCityConstructions().purchaseConstruction(holy, -1, false, Stat.Faith, null)) {
				return Result.refused("The purchase did not go through.");
			}
			return Result.done("Bought " + UncivLink.words(holy.getName()) + " in " + city.getName() + " with faith.", stored, viewer.getReligionManager().getStoredFaith());
		}
		Object[] choice = buildable(city).get(command.item());
		if (choice == null || !(city.getCityConstructions().getConstruction$Unciv_core((String) choice[0]) instanceof INonPerpetualConstruction thing)) {
			return Result.refused(city.getName() + " cannot buy that.");
		}
		Integer cost = thing.getStatBuyCost(city, Stat.Gold);
		if (cost == null || !thing.canBePurchasedWithStat(city, Stat.Gold)) {
			return Result.refused("That cannot be bought with gold here.");
		}
		if (viewer.getGold() < cost) {
			return Result.refused("That costs " + cost + " gold; your treasury holds " + viewer.getGold() + ".");
		}
		int before = viewer.getGold();
		if (!city.getCityConstructions().purchaseConstruction((String) choice[0], -1, false, Stat.Gold, null)) {
			return Result.refused("The purchase did not go through.");
		}
		return Result.done("Bought " + choice[0] + " in " + city.getName() + ".", before, viewer.getGold());
	}

	/** A city that came to the player from somebody else this very turn: conquered, and not yet a turn old in their hands. */
	private boolean takenThisTurn(City city) {
		return city.getTurnAcquired() == game.getTurns() && !city.getPreviousOwner().isEmpty() && city.getFoundingCivObject() != viewer;
	}

	/** The civilization a city could be given back to: whoever founded it, when that is somebody else the player is not at war with. */
	private Civilization founder(City city) {
		Civilization founder = city.getFoundingCivObject();
		return founder == null || founder == viewer || founder == city.getCiv() || viewer.isAtWarWith(founder) ? null : founder;
	}

	/** What the player may do with one of their cities (group 19): the type is the word {@link #fate} takes. */
	private List<Records.Option> fates(City city) {
		List<Records.Option> out = new ArrayList<>();
		if (city == null) {
			return out;
		}
		int index = 0;
		if (city.isBeingRazed()) {
			out.add(new Records.Option(19, index++, "spare", "Stop burning it down", 0));
		}
		if (city.isPuppet()) {
			out.add(new Records.Option(19, index++, "puppet", "Leave it a puppet: it pays into your treasury, runs itself, and weighs little on your people's mood", 0));
			if (!viewer.hasUnique(UniqueType.MayNotAnnexCities, viewer.getState())) {
				out.add(new Records.Option(19, index++, "annex", "Annex it: you govern it yourself; its people resent it for a while", 0));
			}
		}
		Civilization founder = founder(city);
		if (founder != null) {
			out.add(new Records.Option(19, index++, "liberate", "Liberate it: " + UncivLink.words(city.getName()) + " goes back to " + founder.getCivName() + ", who will not forget it", 0));
		}
		if (!city.isBeingRazed() && city.canBeDestroyed(takenThisTurn(city))) {
			out.add(new Records.Option(19, index++, "raze", "Raze it: it loses a citizen a turn until nothing is left", 0));
		}
		return out;
	}

	/** What becomes of a city the player holds: kept as a puppet, taken in fully, given back to its founder, or burned down. */
	/** Which land a city's citizens work: a place the player fixes, a place given back to the city's own choice, what the city looks to, or all of it afresh. */
	private Result work(GameCommand command) {
		City city = ownCity(command.target());
		if (city == null) {
			return Result.refused("That city is not yours.");
		}
		String name = UncivLink.words(city.getName());
		if (city.isPuppet()) {
			return Result.refused(name + " is a puppet: it decides for itself.");
		}
		String what = command.item() == null ? "" : command.item();
		if (what.equals("reset")) {
			city.setCityFocus(com.unciv.logic.city.CityFocus.NoFocus);
			city.setManualSpecialists(false);
			city.reassignPopulation(true);
			return Result.done("The citizens of " + name + " choose their own work again.");
		}
		if (what.startsWith("focus:")) {
			com.unciv.logic.city.CityFocus focus = switch (what.substring(6)) {
				case "food" -> com.unciv.logic.city.CityFocus.FoodFocus;
				case "production" -> com.unciv.logic.city.CityFocus.ProductionFocus;
				case "gold" -> com.unciv.logic.city.CityFocus.GoldFocus;
				case "science" -> com.unciv.logic.city.CityFocus.ScienceFocus;
				case "culture" -> com.unciv.logic.city.CityFocus.CultureFocus;
				case "faith" -> com.unciv.logic.city.CityFocus.FaithFocus;
				default -> com.unciv.logic.city.CityFocus.NoFocus;
			};
			city.setCityFocus(focus);
			city.reassignPopulation(false);
			return Result.done(focus == com.unciv.logic.city.CityFocus.NoFocus ? name + " weighs everything evenly." : name + " looks to " + what.substring(6) + " first.");
		}
		if (what.startsWith("specialist+:") || what.startsWith("specialist-:")) {
			// A citizen put to work in a building (a Merchant in the Market), or taken out of it again. From then on the city
			// leaves its specialists as the player set them, as it leaves the land the player chose.
			String kind = what.substring(12);
			boolean more = what.charAt(10) == '+';
			var people = city.getPopulation();
			Integer room = people.getMaxSpecialists().get(kind);
			Integer now = people.getSpecialistAllocations().get(kind);
			int most = room == null ? 0 : room;
			int have = now == null ? 0 : now;
			if (most <= 0) {
				return Result.refused(name + " has no building a " + kind + " could work in.");
			}
			if (more) {
				if (have >= most) {
					return Result.refused("There is no more room for a " + kind + " in " + name + ".");
				}
				if (city.getLockedTiles().size() + people.getNumberOfSpecialists() + 1 > people.getPopulation()) {
					return Result.refused("Every citizen of " + name + " already works where you put them. Take one away first.");
				}
				city.setManualSpecialists(true);
				people.getSpecialistAllocations().add(kind, 1);
				city.reassignPopulation(false);
				return Result.done("A citizen of " + name + " works as a " + kind + " from now on.");
			}
			if (have <= 0) {
				return Result.refused("No " + kind + " works in " + name + ".");
			}
			city.setManualSpecialists(true);
			people.getSpecialistAllocations().add(kind, -1);
			city.reassignPopulation(false);
			return Result.done("One " + kind + " of " + name + " goes back to the land.");
		}
		Tile tile = snapshot.map().tile(command.plot());
		if (tile == null || tile.isCityCenter() || Kt.list(city.getWorkableTiles()).stream().noneMatch(t -> t == tile)) {
			return Result.refused("That land is not " + name + "'s to work.");
		}
		if (city.getLockedTiles().contains(tile.getPosition())) {
			city.getLockedTiles().remove(tile.getPosition());
			city.reassignPopulation(false);
			return Result.done("That place is left to " + name + "'s own choice again.");
		}
		if (tile.isWorked() && tile.getWorkingCity() != city) {
			return Result.refused("Another city works that land.");
		}
		if (city.getLockedTiles().size() + (city.getManualSpecialists() ? city.getPopulation().getNumberOfSpecialists() : 0) >= city.getPopulation().getPopulation()) {
			return Result.refused("Every citizen of " + name + " already works where you put them. Take one away first.");
		}
		if (tile.isBlockaded()) {
			// The game would take the citizen away again at once; say so instead of saying yes.
			return Result.refused("An enemy holds that place: nobody can work it until they are gone.");
		}
		city.getLockedTiles().add(tile.getPosition());
		city.reassignPopulation(false);
		if (!city.getLockedTiles().contains(tile.getPosition())) {
			// Reassigning drops places that cannot be worked, for whatever reason of the game's own.
			return Result.refused("That land cannot be worked right now.");
		}
		return Result.done("A citizen of " + name + " works that land from now on.");
	}

	private Result fate(GameCommand command) {
		City city = ownCity(command.target());
		if (city == null) {
			return Result.refused("That city is not yours.");
		}
		String name = UncivLink.words(city.getName());
		if (fates(city).stream().noneMatch(o -> o.type().equals(command.item()))) {
			return Result.refused("That cannot be done with " + name + " now.");
		}
		switch (command.item()) {
			case "puppet" -> {
				return Result.done(name + " stays a puppet.");
			}
			case "annex" -> {
				city.annexCity();
				return Result.done(name + " is now governed by you.");
			}
			case "liberate" -> {
				Civilization founder = founder(city);
				city.liberateCity(viewer);
				return Result.done(name + " is free again, under " + founder.getCivName() + ".");
			}
			case "raze" -> {
				// As Unciv's own choice does it: a city is taken in before it is burned.
				if (city.isPuppet() && !viewer.hasUnique(UniqueType.MayNotAnnexCities, viewer.getState())) {
					city.annexCity();
				}
				city.setBeingRazed(true);
				return Result.done(name + " is being razed.");
			}
			default -> {
				city.setBeingRazed(false);
				return Result.done(name + " is spared.");
			}
		}
	}

	/** What hands have brought each city this turn ("turn:city:kind"), and the turn that is counted for. */
	private final Map<String, Integer> handed = new HashMap<>();
	private int handedTurn = -1;
	/** The least a city takes from hands in a turn, however little it makes itself: three logs' worth. */
	static final int LEAST_BY_HAND = 6;
	/** The most gold hands bring a city in a turn. */
	static final int GOLD_BY_HAND = 10;

	private int handedSoFar(City city, String kind) {
		if (handedTurn != game.getTurns()) {
			handed.clear();
			handedTurn = game.getTurns();
		}
		return handed.getOrDefault(city.getId() + ":" + kind, 0);
	}

	/**
	 * What a city can still take from its ruler's own hands this turn: production, food, gold.
	 * Hands help a city along; they do not do its work for it. So a city takes no more production
	 * in a turn than it makes itself (but always three logs' worth), no more food than feeds
	 * its people a turn, and a purse of gold.
	 */
	int[] handRoom(City city) {
		int makes = Math.round(city.getCityStats().getCurrentCityStats().getProduction());
		return new int[] { Math.max(0, Math.max(LEAST_BY_HAND, makes) - handedSoFar(city, "production")),
			Math.max(0, 2 + city.getPopulation().getPopulation() - handedSoFar(city, "food")), Math.max(0, GOLD_BY_HAND - handedSoFar(city, "gold")) };
	}

	/** What a ruler on foot hands to a city: wood and ore for what it builds (no item), or "food" or "gold". */
	private Result addProduction(GameCommand command) {
		City city = ownCity(command.target());
		if (city == null) {
			return Result.refused("That city is not yours.");
		}
		String kind = "food".equals(command.item()) || "gold".equals(command.item()) ? command.item() : "production";
		int[] room = handRoom(city);
		int left = room[kind.equals("production") ? 0 : kind.equals("food") ? 1 : 2];
		if (left <= 0) {
			return Result.refused(city.getName() + " can take no more " + (kind.equals("production") ? "work" : kind) + " from your hands this turn.");
		}
		int amount = Math.max(1, Math.min(left, command.amount()));
		int before;
		int after;
		switch (kind) {
			case "food" -> {
				before = city.getPopulation().getFoodStored();
				city.getPopulation().setFoodStored(before + amount);
				after = before + amount;
			}
			case "gold" -> {
				before = viewer.getGold();
				viewer.addGold(amount);
				after = viewer.getGold();
			}
			default -> {
				String current = city.getCityConstructions().currentConstructionName();
				if (current == null || current.isEmpty() || PerpetualConstruction.Companion.isNamePerpetual(current)) {
					return Result.refused(city.getName() + " is not building anything to help with.");
				}
				before = city.getCityConstructions().getWorkDone(current);
				city.getCityConstructions().addProductionPoints(amount);
				after = city.getCityConstructions().getWorkDone(current);
			}
		}
		handed.merge(city.getId() + ":" + kind, amount, Integer::sum);
		return Result.done(city.getName() + ": +" + amount + " " + (kind.equals("production") ? "Production" : kind.equals("food") ? "Food" : "Gold"), before, after);
	}

	// ---- the realm ----

	private Result research(GameCommand command) {
		for (Technology tech : game.getRuleset().getTechnologies().values()) {
			if (!("TECH_" + Names.techKey(tech.getName())).equals(command.item())) {
				continue;
			}
			if (viewer.getTech().isResearched(tech.getName())) {
				return Result.refused("Your scholars know that already.");
			}
			if (!viewer.getTech().canBeResearched(tech.getName())) {
				// Further off: everything on the way to it is researched first, in order, as in Civilization.
				ArrayList<String> path = new ArrayList<>();
				for (Technology step : viewer.getTech().getRequiredTechsToDestination(tech)) {
					path.add(step.getName());
				}
				if (path.isEmpty() || viewer.getTech().getFreeTechs() > 0) {
					return Result.refused("That cannot be researched right now.");
				}
				viewer.getTech().setTechsToResearch(path);
				return Result.done("Research chosen: " + path.get(0) + " first, on the way to " + tech.getName() + ".");
			}
			if (viewer.getTech().getFreeTechs() > 0) {
				viewer.getTech().getFreeTechnology(tech.getName());
				return Result.done("Learned " + tech.getName() + " at once.");
			}
			ArrayList<String> queue = new ArrayList<>();
			queue.add(tech.getName());
			viewer.getTech().setTechsToResearch(queue);
			return Result.done("Research chosen.");
		}
		return Result.refused("No such technology.");
	}

	private Result policy(GameCommand command) {
		if (!viewer.getPolicies().canAdoptPolicy()) {
			return Result.refused("Your culture has not earned a policy yet.");
		}
		for (Policy policy : game.getRuleset().getPolicies().values()) {
			if (!("POLICY_" + Names.key(policy.getName())).equals(command.item())) {
				continue;
			}
			if (!viewer.getPolicies().isAdoptable(policy, true)) {
				return Result.refused("That policy cannot be adopted right now.");
			}
			int before = viewer.getPolicies().getAdoptedPolicies().size();
			viewer.getPolicies().adopt(policy, false);
			return Result.done("Policy adopted.", before, viewer.getPolicies().getAdoptedPolicies().size());
		}
		return Result.refused("No such policy.");
	}

	private Result gift(GameCommand command) {
		Civilization other = snapshot.civ(command.target().id());
		if (other == null || !other.isCityState() || !viewer.knows(other)) {
			return Result.refused("That is not a city-state you know.");
		}
		int amount = command.amount();
		if (amount != 250 && amount != 500 && amount != 1000) {
			return Result.refused("That is not a gift they would understand.");
		}
		if (viewer.getGold() < amount) {
			return Result.refused("Your treasury does not hold " + amount + " gold.");
		}
		DiplomacyManager theirs = other.getDiplomacyManager(viewer);
		int before = theirs == null ? 0 : Math.round(theirs.getInfluence());
		other.getCityStateFunctions().receiveGoldGift(viewer, amount);
		return Result.done("Gift sent.", before, theirs == null ? before : Math.round(theirs.getInfluence()));
	}

	private Result war(GameCommand command) {
		Civilization other = snapshot.civ(command.target().id());
		if (other == null || other == viewer || other.isBarbarian() || !viewer.knows(other)) {
			return Result.refused("That is not a civilization you can declare war on.");
		}
		if (viewer.isAtWarWith(other)) {
			return Result.refused("You are already at war with " + other.getCivName() + ".");
		}
		DiplomacyManager ours = viewer.getDiplomacyManager(other);
		if (ours == null || !ours.canDeclareWar()) {
			return Result.refused("You cannot declare war on " + other.getCivName() + " right now.");
		}
		ours.declareWar(new com.unciv.logic.civilization.diplomacy.DeclareWarReason(com.unciv.logic.civilization.diplomacy.WarType.DirectWar, null));
		return Result.done("War declared.");
	}

	// ---- the land, as a player on foot changes it ----

	private Result reveal(GameCommand command) {
		Tile centre = snapshot.map().tile(command.plot());
		if (centre == null || !centre.isExplored(viewer)) {
			return Result.refused("You can only explore from land your civilization knows.");
		}
		int radius = Math.max(1, Math.min(2, command.amount()));
		int found = 0;
		for (Tile tile : Kt.list(centre.getTilesInDistance(radius))) {
			if (!tile.isExplored(viewer)) {
				tile.setExplored(viewer, true, null);
				found++;
			}
			// Who lives there is met, as when a scout walks up to them.
			Civilization owner = tile.getOwner();
			if (owner != null && owner != viewer && !owner.isBarbarian() && !viewer.knows(owner)) {
				viewer.getDiplomacyFunctions().makeCivilizationsMeet(owner, false);
			}
		}
		return Result.done("Explored.", 0, found);
	}

	/** What a player could build on a tile by hand: what Unciv lets this civilization build there, without the great improvements. */
	private List<TileImprovement> works(Tile tile) {
		List<TileImprovement> out = new ArrayList<>();
		if (tile == null || tile.getOwner() != viewer || tile.isCityCenter()) {
			return out;
		}
		for (TileImprovement improvement : game.getRuleset().getTileImprovements().values()) {
			if (improvement.isGreatImprovement() || improvement.getName().startsWith("Cancel") || improvement.getName().equals(tile.getImprovement())) {
				continue;
			}
			if (improvement.getTechRequired() != null && !viewer.getTech().isResearched(improvement.getTechRequired())) {
				continue;
			}
			try {
				if (tile.getImprovementFunctions().canBuildImprovement(improvement, viewer.getState())) {
					out.add(improvement);
				}
			} catch (RuntimeException e) {
				// An improvement Unciv cannot judge here is not on offer.
			}
		}
		return out;
	}

	private Result improve(GameCommand command) {
		Tile tile = snapshot.map().tile(command.plot());
		if (tile == null || tile.getOwner() != viewer) {
			return Result.refused("You can only work land inside your own borders.");
		}
		String name = snapshot.improvementName(command.amount());
		for (TileImprovement improvement : works(tile)) {
			if (improvement.getName().equals(name)) {
				tile.setImprovement(improvement.getName(), viewer, null);
				return Result.done("Done.");
			}
		}
		return Result.refused("That cannot be built here.");
	}

	/** The smaller decisions: a city fires on an enemy, a building is sold, a Great Person given freely is named. */
	private Result choice(GameCommand command) {
		String item = command.item();
		if (item.equals("bombard")) {
			City city = ownCity(command.target());
			Tile target = snapshot.map().tile(command.plot());
			if (city == null || target == null) {
				return Result.refused("That city is not yours.");
			}
			if (!city.canBombard()) {
				return Result.refused(city.getName() + " cannot fire this turn.");
			}
			if (!info.bombardable(city).contains(target)) {
				return Result.refused("That is out of " + city.getName() + "'s reach.");
			}
			var foe = Battle.INSTANCE.getMapCombatantOfTile(target);
			if (foe == null) {
				return Result.refused("There is nobody there to fire on.");
			}
			String name = UncivLink.words(foe.getName());
			Battle.INSTANCE.attack(new com.unciv.logic.battle.CityCombatant(city), foe);
			return Result.done(city.getName() + " fired on " + name + ".");
		}
		if (item.startsWith("sell:")) {
			City city = ownCity(command.target());
			if (city == null) {
				return Result.refused("That city is not yours.");
			}
			if (city.isPuppet() || city.getHasSoldBuildingThisTurn()) {
				return Result.refused(city.getName() + " can sell one building a turn" + (city.isPuppet() ? ", and a puppet none at all." : "."));
			}
			for (Building building : UncivInfo.built(city)) {
				if (("BUILDING_" + Names.key(building.getName())).equals(item.substring(5))) {
					if (!building.isSellable()) {
						return Result.refused("That cannot be sold.");
					}
					int before = viewer.getGold();
					city.sellBuilding(building);
					return Result.done("Sold the " + UncivLink.words(building.getName()) + " of " + city.getName() + ".", before, viewer.getGold());
				}
			}
			return Result.refused("That does not stand in " + city.getName() + ".");
		}
		if (item.startsWith("spy:")) {
			// "spy:<which>:home", "spy:<which>:coup", or "spy:<which>:move" with the city as the target.
			String[] parts = item.split(":");
			com.unciv.models.Spy spy;
			try {
				spy = parts.length < 3 ? null : info.spy(Integer.parseInt(parts[1]));
			} catch (NumberFormatException e) {
				spy = null;
			}
			if (spy == null || !spy.isAlive()) {
				return Result.refused("That spy takes no orders now.");
			}
			String name = UncivLink.words(spy.getName());
			switch (parts[2]) {
				case "home" -> {
					spy.moveTo(null);
					return Result.done(name + " comes back to the hideout.");
				}
				case "coup" -> {
					if (!spy.canDoCoup()) {
						return Result.refused(name + " cannot stage a coup there now.");
					}
					spy.setAction(com.unciv.models.SpyAction.Coup, 1);
					return Result.done(name + " stages a coup. The next turn says how it went.");
				}
				case "move" -> {
					City city = snapshot.city(command.target());
					if (city == null || !city.getCenterTile().isExplored(viewer) || !spy.canMoveTo(city)) {
						return Result.refused(name + " cannot go there.");
					}
					spy.moveTo(city);
					return Result.done(name + " is on the way to " + UncivLink.words(city.getName()) + ".");
				}
				default -> {
					return Result.refused("That is not an order for a spy.");
				}
			}
		}
		if (item.startsWith("great:")) {
			if (viewer.getGreatPeople().getFreeGreatPeople() <= 0) {
				return Result.refused("No Great Person is yours for the choosing.");
			}
			for (BaseUnit person : viewer.getGreatPeople().getFreeGreatPersonOptions()) {
				if (("GREAT_" + Names.key(person.getName())).equals(item.substring(6))) {
					if (viewer.getGreatPeople().chooseFreeGreatPerson(person.getName()) == null) {
						return Result.refused("There is no room for them in your capital.");
					}
					return Result.done("A " + UncivLink.words(person.getName()) + " has come to your capital.");
				}
			}
			return Result.refused("That Great Person is not on offer.");
		}
		return Result.refused("That is not possible yet.");
	}

	// ---- what the game offers ----

	List<Records.Option> options(String kind, int a, int c) {
		List<Records.Option> out = new ArrayList<>();
		switch (kind) {
			case "build", "buy" -> {
				City city = ownCity(a);
				if (city == null || city.isPuppet()) {
					return out;
				}
				boolean buying = kind.equals("buy");
				int index = 0;
				for (Map.Entry<String, Object[]> entry : buildable(city).entrySet()) {
					String name = (String) entry.getValue()[0];
					int group = (Integer) entry.getValue()[1];
					if (!buying) {
						out.add(new Records.Option(group, index++, entry.getKey(), name, group == 3 ? 0 : city.getCityConstructions().turnsToConstruction(name, false)));
					} else if (city.getCityConstructions().getConstruction$Unciv_core(name) instanceof INonPerpetualConstruction thing
						&& thing.canBePurchasedWithStat(city, Stat.Gold)) {
						Integer cost = thing.getStatBuyCost(city, Stat.Gold);
						if (cost != null) {
							// For a purchase the last number is the price in gold.
							out.add(new Records.Option(group, index++, entry.getKey(), name, cost));
						}
					}
				}
			}
			case "research" -> {
				int index = 0;
				for (Technology tech : game.getRuleset().getTechnologies().values()) {
					if (viewer.getTech().canBeResearched(tech.getName())) {
						int turns;
						try {
							turns = Integer.parseInt(viewer.getTech().turnsToTech(tech.getName()).trim());
						} catch (NumberFormatException e) {
							turns = 999;
						}
						out.add(new Records.Option(0, index++, "TECH_" + Names.techKey(tech.getName()), tech.getName(), turns));
					}
				}
			}
			case "policy" -> {
				if (!viewer.getPolicies().canAdoptPolicy()) {
					return out;
				}
				int index = 0;
				for (Policy policy : game.getRuleset().getPolicies().values()) {
					if (viewer.getPolicies().isAdoptable(policy, true)) {
						out.add(new Records.Option(policy instanceof PolicyBranch ? 5 : 6, index++, "POLICY_" + Names.key(policy.getName()), policy.getName(), 0));
					}
				}
			}
			case "hands" -> {
				// What one of the player's cities can still take from their own hands this turn.
				City city = ownCity(a);
				if (city != null) {
					int[] room = handRoom(city);
					out.add(new Records.Option(0, room[0], "production", "Production", 0));
					out.add(new Records.Option(1, room[1], "food", "Food", 0));
					out.add(new Records.Option(2, room[2], "gold", "Gold", 0));
				}
			}
			case "works" -> {
				for (TileImprovement improvement : works(snapshot.map().tile(new PlotCoord(a, c)))) {
					out.add(new Records.Option(4, snapshot.improvementId(improvement.getName()), "BUILD_" + Names.key(improvement.getName()), improvement.getName(), 0));
				}
			}
			case "unit" -> {
				MapUnit unit = ownUnit(new EntityKey(snapshot.civId(viewer), a));
				if (unit == null) {
					return out;
				}
				int index = 0;
				for (Act act : unitActs(unit)) {
					// The last number says whether it can be done right now.
					out.add(new Records.Option(7, index++, act.key(), act.title(), act.run() == null ? 0 : 1));
				}
				if (unit.getPromotions().canBePromoted()) {
					out.add(new Records.Option(7, index, "Promote", "Choose a promotion", 1));
				}
			}
			case "proposals" -> out.addAll(diplomacy.proposals());
			case "land" -> {
				// Which of the player's cities could buy the hex at (a, c), and for how much (the last number).
				Tile tile = snapshot.map().tile(new PlotCoord(a, c));
				if (tile != null && tile.getOwner() == null && tile.isExplored(viewer)) {
					for (City city : viewer.getCities()) {
						if (city.getExpansion().canBuyTile(tile)) {
							out.add(new Records.Option(22, UncivSnapshot.cityId(city), "land", UncivLink.words(city.getName()), city.getExpansion().getGoldCostOfTile(tile, 0)));
						}
					}
				}
			}
			case "beliefs" -> out.addAll(faith.choices());
			case "vote" -> out.addAll(diplomacy.candidates(snapshot));
			case "tradeGive" -> out.addAll(trade.offers(snapshot.civ(a), true));
			case "tradeTake" -> out.addAll(trade.offers(snapshot.civ(a), false));
			case "tradeTable" -> out.addAll(trade.table(snapshot.civ(a)));
			case "fate" -> out.addAll(fates(ownCity(new EntityKey(snapshot.civId(viewer), a))));
			case "captured" -> {
				// Cities taken this turn, for which the player has not been asked yet what becomes of them.
				int index = 0;
				for (City city : viewer.getCities()) {
					if (takenThisTurn(city)) {
						out.add(new Records.Option(20, snapshot.cityId(city), "city", UncivLink.words(city.getName()), index++));
					}
				}
			}
			case "reach" -> {
				// Where a unit can walk to this turn (group 11) and whom it could attack (group 12); the type is the plot, "x,y".
				MapUnit unit = ownUnit(new EntityKey(snapshot.civId(viewer), a));
				if (unit == null || !unit.hasMovement()) {
					return out;
				}
				for (Tile tile : Kt.list(unit.getMovement().getReachableTilesInCurrentTurn(true))) {
					if (tile != unit.getTile() && unit.getMovement().canMoveTo(tile, false, false, true)) {
						var plot = snapshot.map().plot(tile);
						out.add(new Records.Option(11, snapshot.map().index(plot), plot.x() + "," + plot.y(), "", 1));
					}
				}
				if (unit.canAttack()) {
					PathsToTilesWithinTurn paths = unit.getMovement().getDistanceToTiles(true, null, null, true);
					for (AttackableTile target : TargetHelper.INSTANCE.getAttackableEnemies(unit, paths, null, false)) {
						var plot = snapshot.map().plot(target.getTileToAttack());
						out.add(new Records.Option(12, snapshot.map().index(plot), plot.x() + "," + plot.y(), "", 1));
					}
				}
			}
			case "worked" -> {
				// The land around a city of the player's: group 1 worked, 2 worked because the player said so, 3 the city itself, 4 worked by
				// another city, 0 free to work; the type is the plot. One more line, type "info": group free citizens, id citizens, turns
				// places fixed by the player, name "what the city looks to|1 when roads or harbours join it to the capital".
				City city = ownCity(a);
				if (city == null) {
					return out;
				}
				int index = 0;
				for (Tile tile : Kt.list(city.getWorkableTiles())) {
					var plot = snapshot.map().plot(tile);
					int how = tile.isCityCenter() ? 3 : tile.getWorkingCity() == city ? (city.getLockedTiles().contains(tile.getPosition()) ? 2 : 1) : tile.isWorked() ? 4 : 0;
					out.add(new Records.Option(how, index++, plot.x() + "," + plot.y(), "", 0));
				}
				String focus = switch (city.getCityFocus()) {
					case FoodFocus -> "food";
					case ProductionFocus -> "production";
					case GoldFocus -> "gold";
					case ScienceFocus -> "science";
					case CultureFocus -> "culture";
					case FaithFocus -> "faith";
					default -> "none";
				};
				out.add(new Records.Option(city.getPopulation().getFreePopulation(), city.getPopulation().getPopulation(), "info",
					focus + "|" + (city.isCapital() || city.isConnectedToCapital() ? "1" : "0"), city.getLockedTiles().size()));
				// And the places in its buildings: type "specialist:Merchant", group how many work there, id how many could.
				for (Map.Entry<String, Integer> slot : city.getPopulation().getMaxSpecialists().entrySet()) {
					if (slot.getValue() != null && slot.getValue() > 0) {
						Integer at = city.getPopulation().getSpecialistAllocations().get(slot.getKey());
						out.add(new Records.Option(at == null ? 0 : at, slot.getValue(), "specialist:" + slot.getKey(), slot.getKey(), 0));
					}
				}
			}
			case "yields" -> {
				// What every hex the player has seen yields: group food, id production, turns gold; the type is the plot ("x,y"), the name "science,culture,faith".
				for (Tile tile : game.getTileMap().getTileList()) {
					if (!tile.isExplored(viewer)) {
						continue;
					}
					com.unciv.models.stats.Stats stats = tile.getStats().getTileStats(viewer);
					var plot = snapshot.map().plot(tile);
					out.add(new Records.Option(Math.round(stats.getFood()), Math.round(stats.getProduction()), plot.x() + "," + plot.y(),
						Math.round(stats.getScience()) + "," + Math.round(stats.getCulture()) + "," + Math.round(stats.getFaith()), Math.round(stats.getGold())));
				}
			}
			case "techtree" -> {
				// Every technology: group its column, id its row, turns the turns it would take; the type is its key, then how it stands
				// (known, now, queued, open, far), what it builds on and its era, as "TECH_X|state|Name of one;Name of another|Ancient era".
				String current = viewer.getTech().currentTechnologyName();
				for (Technology tech : game.getRuleset().getTechnologies().values()) {
					if (tech.getColumn() == null) {
						continue;
					}
					String name = tech.getName();
					String state = viewer.getTech().isResearched(name) ? "known" : name.equals(current) ? "now" : viewer.getTech().getTechsToResearch().contains(name) ? "queued"
						: viewer.getTech().canBeResearched(name) ? "open" : "far";
					int turns = 0;
					if (!state.equals("known")) {
						try {
							turns = Integer.parseInt(viewer.getTech().turnsToTech(name).trim());
						} catch (NumberFormatException e) {
							turns = 999;
						}
					}
					out.add(new Records.Option(tech.getColumn().getColumnNumber(), tech.getRow(), "TECH_" + Names.techKey(name) + "|" + state + "|" + String.join(";", tech.getPrerequisites()) + "|" + tech.era(),
						name, turns));
				}
			}
			case "promote" -> {
				MapUnit unit = ownUnit(new EntityKey(snapshot.civId(viewer), a));
				if (unit == null || !unit.getPromotions().canBePromoted()) {
					return out;
				}
				int index = 0;
				for (Iterator<Promotion> it = unit.getPromotions().getAvailablePromotions().iterator(); it.hasNext(); ) {
					Promotion promotion = it.next();
					out.add(new Records.Option(8, index++, "PROMOTION_" + Names.key(promotion.getName()), promotion.getName(), 0));
				}
			}
			case "about:research", "about:policy", "about:build", "about:buy", "about:faith", "about:promote", "about:greatPeople" -> out.addAll(info.about(kind.substring(6), a));
			case "faith" -> {
				// What a city could buy with faith (group 0 a unit, 1 a building); the last number is the price in faith.
				City city = ownCity(a);
				if (city != null) {
					int index = 0;
					for (Map.Entry<String, INonPerpetualConstruction> entry : info.forFaith(city).entrySet()) {
						Integer cost = entry.getValue().getStatBuyCost(city, Stat.Faith);
						out.add(new Records.Option(entry.getValue() instanceof Building ? 1 : 0, index++, entry.getKey(), entry.getValue().getName(), cost == null ? 0 : cost));
					}
				}
			}
			case "queue" -> {
				// What a city builds now and what is lined up after it: the id its place in the line, the last number the turns it takes.
				City city = ownCity(a);
				if (city != null) {
					List<String> queue = city.getCityConstructions().getConstructionQueue();
					for (int i = 0; i < queue.size(); i++) {
						String name = queue.get(i);
						int turns = PerpetualConstruction.Companion.getPerpetualConstructionsMap().containsKey(name) ? 0 : city.getCityConstructions().turnsToConstruction(name, false);
						out.add(new Records.Option(25, i, name, name, turns));
					}
				}
			}
			case "sellable" -> {
				// The buildings of a city that could be sold (group 26); the last number is the gold it brings.
				City city = ownCity(a);
				if (city != null && !city.isPuppet() && !city.getHasSoldBuildingThisTurn()) {
					int index = 0;
					for (Building building : UncivInfo.built(city)) {
						if (building.isSellable()) {
							out.add(new Records.Option(26, index++, "BUILDING_" + Names.key(building.getName()), building.getName(), city.getGoldForSellingBuilding(building.getName())));
						}
					}
				}
			}
			case "greatPeople" -> {
				if (viewer.getGreatPeople().getFreeGreatPeople() > 0) {
					int index = 0;
					for (BaseUnit person : viewer.getGreatPeople().getFreeGreatPersonOptions()) {
						out.add(new Records.Option(27, index++, "GREAT_" + Names.key(person.getName()), person.getName(), 0));
					}
				}
			}
			case "policytree" -> out.addAll(info.policyTree());
			case "spies" -> out.addAll(info.spies());
			case "spyTargets" -> out.addAll(info.spyTargets(a));
			case "quests" -> out.addAll(info.quests(snapshot.civ(a)));
			case "battle" -> out.addAll(info.battle(a, -1, c < 0 || c >= snapshot.map().width() * snapshot.map().height() ? null : snapshot.map().tile(c)));
			case "cityBattle" -> out.addAll(info.battle(-1, a, c < 0 || c >= snapshot.map().width() * snapshot.map().height() ? null : snapshot.map().tile(c)));
			case "religion" -> out.addAll(info.religion());
			case "victory" -> out.addAll(info.victory());
			case "happiness" -> out.addAll(info.happiness());
			case "cityinfo" -> out.addAll(info.city(a));
			case "view" -> {
				// Everything the view from above shows beside the picture of the world, in one answer: what the unit in hand can do
				// and reach, how many proposals wait (group 30), the empire's other numbers and that unit's promotions.
				if (a >= 0) {
					out.addAll(options("unit", a, 0));
					out.addAll(options("reach", a, 0));
					out.addAll(info.unit(a));
				}
				out.add(new Records.Option(30, diplomacy.proposals().size(), "proposals", "", 0));
				out.addAll(info.realm());
			}
			default -> {
			}
		}
		return out;
	}
}
