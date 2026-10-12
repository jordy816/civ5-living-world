package nl.jordythomassen.livingworld.commands;

import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * The checks a command must pass before it is sent, and the test for whether its effect is
 * visible afterwards. Civ re-checks everything itself; these exist so a hopeless request is
 * refused with a clear reason instead of disappearing into the game.
 */
public final class CommandRules {
	/** Most hammers one command may add: a mission reward, not a cheat code. */
	public static final int MAX_PRODUCTION = 60;
	public static final int MAX_HEAL = 50;
	/** The other players' turns can take a while: how long to wait for the next turn to begin. */
	public static final long END_TURN_CONFIRM_MS = 120_000;

	private CommandRules() {
	}

	/** Null when the command may be sent; otherwise the reason it may not. */
	public static String refuse(GameCommand command, WorldState state) {
		if (state == null) {
			return "Not connected to Civilization V.";
		}
		if (!state.session().bootId().equals(command.bootId()) || !state.session().gameId().equals(command.gameId())) {
			return "That request was made in another Civ session.";
		}
		if (command.target() == null) {
			return "No target.";
		}
		// The first half of every target is the player giving the order; Civ checks it again.
		if (command.target().owner() != state.session().viewer()) {
			return "That does not belong to your civilization.";
		}
		switch (command.op()) {
			case UNIT_MOVE_TO -> {
				UnitState unit = state.units().get(command.target());
				if (unit == null) {
					return "That unit is no longer there.";
				}
				PlotCoord to = command.plot() == null ? null : state.session().grid().normalize(command.plot());
				if (to == null) {
					return "That spot is off the map.";
				}
				PlotState plot = state.plot(to);
				if (!plot.revealed()) {
					return "Your civilization has not explored that spot.";
				}
				if (plot.elevation() == PlotState.MOUNTAIN) {
					return "Units cannot cross mountains.";
				}
				if (plot.water() && !unit.domain().equals("DOMAIN_SEA") && !unit.embarked()) {
					return "That unit cannot go into the water from here.";
				}
				if (unit.at().equals(to)) {
					return "The unit is already there.";
				}
			}
			case CITY_ADD_PRODUCTION -> {
				CityState city = state.cities().get(command.target());
				if (city == null) {
					return "That city is no longer yours.";
				}
				if (command.amount() < 1 || command.amount() > MAX_PRODUCTION) {
					return "A delivery must be between 1 and " + MAX_PRODUCTION + " hammers.";
				}
				boolean goods = "food".equals(command.item()) || "gold".equals(command.item());
				if (!goods && (city.productionNeeded() <= 0 || city.productionType().isEmpty())) {
					return city.name() + " is not building anything right now.";
				}
			}
			case UNIT_HEAL -> {
				UnitState unit = state.units().get(command.target());
				if (unit == null) {
					return "That unit is no longer there.";
				}
				if (command.amount() < 1 || command.amount() > MAX_HEAL) {
					return "Healing must be between 1 and " + MAX_HEAL + " hit points.";
				}
				if (unit.hp() >= unit.maxHp()) {
					return "That unit is not wounded.";
				}
			}
			case END_TURN -> {
				// Asked during one turn, it must not end a later one by arriving late.
				if (command.turn() != state.turn()) {
					return "That turn is already over.";
				}
			}
			case CITY_BUILD -> {
				if (state.cities().get(command.target()) == null) {
					return "That city is no longer yours.";
				}
				if (command.plot() == null || command.plot().x() < 0 || command.plot().x() > 3) {
					return "That is not something a city can build.";
				}
			}
			case RESEARCH -> {
				if (command.plot() == null) {
					return "No technology chosen.";
				}
			}
			case REVEAL -> {
				PlotCoord at = command.plot() == null ? null : state.session().grid().normalize(command.plot());
				if (at == null || !state.plot(at).revealed()) {
					return "You can only explore from land your civilization knows.";
				}
				if (command.amount() < 1 || command.amount() > 2) {
					return "Too far to see.";
				}
			}
			case IMPROVE -> {
				PlotCoord at = command.plot() == null ? null : state.session().grid().normalize(command.plot());
				if (at == null) {
					return "That spot is off the map.";
				}
				PlotState plot = state.plot(at);
				if (plot.owner() != state.session().viewer()) {
					return "You can only work land inside your own borders.";
				}
				if (plot.city() || plot.water() || plot.elevation() == PlotState.MOUNTAIN) {
					return "Nothing can be built on this plot.";
				}
			}
			case GIFT -> {
				nl.jordythomassen.livingworld.state.Relation relation = state.relations().get(command.target().id());
				if (relation == null || !relation.minor()) {
					return "That is not a city-state you know.";
				}
				if (command.amount() != relation.giftSmall() && command.amount() != relation.giftMedium() && command.amount() != relation.giftLarge()) {
					return "That is not a gift the game offers.";
				}
				if (state.empire() == null || state.empire().gold() < command.amount()) {
					return "Your treasury does not hold " + command.amount() + " gold.";
				}
			}
			case POLICY -> {
				if (command.plot() == null || (command.plot().x() != 5 && command.plot().x() != 6)) {
					return "No policy chosen.";
				}
			}
			case DECLARE_WAR -> {
				nl.jordythomassen.livingworld.state.Relation relation = state.relations().get(command.target().id());
				nl.jordythomassen.livingworld.state.CivInfo civ = state.civ(command.target().id());
				if (relation == null || relation.minor() || civ == null) {
					return "That is not a civilization you can declare war on.";
				}
				if (civ.atWarWithViewer()) {
					return "You are already at war with " + civ.name() + ".";
				}
				if (!relation.canDeclareWar()) {
					return "You cannot declare war on " + civ.name() + " right now.";
				}
			}
		}
		return null;
	}

	/** Whether Civ's state now shows what the command was meant to do. */
	public static boolean confirmed(CommandTicket ticket, WorldState state) {
		GameCommand command = ticket.command();
		switch (command.op()) {
			case UNIT_MOVE_TO -> {
				UnitState unit = state.units().get(command.target());
				if (unit == null) {
					return false;
				}
				// "before" holds the distance to the goal when the order was given. A unit that got
				// closer has taken the order; a long walk finishes over the following turns.
				return state.session().grid().distance(unit.at(), command.plot()) < ticket.before();
			}
			case CITY_ADD_PRODUCTION -> {
				CityState city = state.cities().get(command.target());
				if (city == null) {
					return false;
				}
				if ("food".equals(command.item()) || "gold".equals(command.item())) {
					// Food and gold are counted by the game itself, which answered with what it did.
					return true;
				}
				// After a turn change the build may have completed and reset: Civ's own readback
				// (before/after, taken in the same instant as the change) then stands on its own.
				return city.production() >= ticket.after() || state.turn() != ticket.appliedTurn();
			}
			case UNIT_HEAL -> {
				UnitState unit = state.units().get(command.target());
				return unit != null && (unit.hp() >= ticket.after() || state.turn() != ticket.appliedTurn());
			}
			case END_TURN -> {
				return state.turn() > command.turn();
			}
			case CITY_BUILD -> {
				CityState city = state.cities().get(command.target());
				return city != null && (city.productionType().equals(command.item()) || state.turn() != ticket.appliedTurn());
			}
			case RESEARCH -> {
				String tech = command.item().startsWith("TECH_") ? command.item().substring(5) : command.item();
				return (state.empire() != null && state.empire().researchType().equals(command.item())) || state.knows(tech);
			}
			case REVEAL -> {
				if (ticket.after() == 0) {
					// Civ found nothing new to reveal there.
					return true;
				}
				PlotCoord centre = state.session().grid().normalize(command.plot());
				for (PlotCoord near : state.session().grid().neighbors(centre)) {
					if (!state.plot(near).revealed()) {
						return false;
					}
				}
				return true;
			}
			case IMPROVE -> {
				// "before" holds what the plot looked like when the work was asked for.
				return state.plot(command.plot()).hashCode() != ticket.before();
			}
			case GIFT -> {
				nl.jordythomassen.livingworld.state.Relation relation = state.relations().get(command.target().id());
				return relation != null && relation.influence() > ticket.before();
			}
			case DECLARE_WAR -> {
				nl.jordythomassen.livingworld.state.CivInfo civ = state.civ(command.target().id());
				return civ != null && civ.atWarWithViewer();
			}
			case POLICY -> {
				return state.empire() != null && state.empire().policies() >= ticket.after();
			}
		}
		return false;
	}
}
