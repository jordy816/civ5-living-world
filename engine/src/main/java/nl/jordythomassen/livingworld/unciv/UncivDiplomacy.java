package nl.jordythomassen.livingworld.unciv;

import com.unciv.Constants;
import com.unciv.logic.GameInfo;
import com.unciv.logic.automation.civilization.DiplomacyAutomation;
import com.unciv.logic.civilization.AlertType;
import com.unciv.logic.civilization.Civilization;
import com.unciv.logic.civilization.PopupAlert;
import com.unciv.logic.civilization.diplomacy.Demand;
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags;
import com.unciv.logic.civilization.diplomacy.DiplomacyManager;
import com.unciv.logic.trade.Trade;
import com.unciv.logic.trade.TradeEvaluation;
import com.unciv.logic.trade.TradeLogic;
import com.unciv.logic.trade.TradeOffer;
import com.unciv.logic.trade.TradeOfferType;
import com.unciv.logic.trade.TradeRequest;
import java.util.ArrayList;
import java.util.List;
import nl.jordythomassen.livingworld.protocol.Records;

/**
 * Dealings with the other civilizations. Whether one of them agrees to something is always
 * decided by Unciv's own AI code, the same that decides it between two computer players.
 */
final class UncivDiplomacy {
	private final GameInfo game;
	private final Civilization viewer;

	UncivDiplomacy(GameInfo game, Civilization viewer) {
		this.game = game;
		this.viewer = viewer;
	}

	/** Whom the player can vote for as world leader when the vote is called (group 21); the last entry is to abstain. */
	List<Records.Option> candidates(UncivSnapshot snapshot) {
		List<Records.Option> out = new ArrayList<>();
		if (!viewer.mayVoteForDiplomaticVictory()) {
			return out;
		}
		for (Civilization civ : game.getCivilizations()) {
			if (civ.isMajorCiv() && !civ.isDefeated() && civ != viewer && viewer.knows(civ)) {
				out.add(new Records.Option(21, snapshot.civId(civ), "vote", civ.getCivName(), 0));
			}
		}
		out.add(new Records.Option(21, -1, "abstain", "Nobody: abstain", 0));
		return out;
	}

	/** The player's vote for a world leader; null abstains. */
	UncivActions.Result vote(Civilization chosen) {
		if (!viewer.mayVoteForDiplomaticVictory()) {
			return UncivActions.Result.refused("No vote is being held.");
		}
		if (chosen != null && (chosen == viewer || !chosen.isMajorCiv() || chosen.isDefeated() || !viewer.knows(chosen))) {
			return UncivActions.Result.refused("That is not somebody you can vote for.");
		}
		viewer.diplomaticVoteForCiv(chosen == null ? null : chosen.getCivID());
		return UncivActions.Result.done(chosen == null ? "You abstain." : "Your vote goes to " + chosen.getCivName() + ".");
	}

	UncivActions.Result step(Civilization other, String what) {
		if (what.equals("abstain")) {
			return vote(null);
		}
		if (what.equals("vote")) {
			return vote(other);
		}
		if (other == null || other == viewer || other.isBarbarian() || !viewer.knows(other) || other.isDefeated()) {
			return UncivActions.Result.refused("That is not a civilization you can deal with.");
		}
		DiplomacyManager ours = viewer.getDiplomacyManager(other);
		DiplomacyManager theirs = other.getDiplomacyManager(viewer);
		if (ours == null || theirs == null) {
			return UncivActions.Result.refused("You have not met them.");
		}
		String name = other.getCivName();
		switch (what) {
			case "peace" -> {
				if (!viewer.isAtWarWith(other)) {
					return UncivActions.Result.refused("You are not at war with " + name + ".");
				}
				if (ours.hasFlag(DiplomacyFlags.DeclaredWar) || theirs.hasFlag(DiplomacyFlags.DeclaredWar)) {
					return UncivActions.Result.refused("The war has only just begun: it is too soon to speak of peace.");
				}
				TradeLogic deal = new TradeLogic(viewer, other);
				TradeOffer treaty = new TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, 1, game.getSpeed());
				deal.getCurrentTrade().getOurOffers().add(treaty);
				deal.getCurrentTrade().getTheirOffers().add(treaty);
				if (other.isHuman()) {
					// Somebody rules them: it is theirs to say, and they find it among their proposals.
					other.getTradeRequests().removeIf(request -> request.getRequestingCiv().equals(viewer.getCivID()) && request.getTrade().isPeaceTreaty());
					other.getTradeRequests().add(new TradeRequest(viewer.getCivID(), deal.getCurrentTrade().reverse()));
					return UncivActions.Result.done("Peace is offered to " + name + ". It is theirs to accept.");
				}
				// A city-state takes peace when it is offered; a civilization weighs it, as it would another AI's offer.
				if (other.isMajorCiv() && !new TradeEvaluation().isTradeAcceptable(deal.getCurrentTrade().reverse(), other, viewer)) {
					return UncivActions.Result.refused(name + " will not make peace now.");
				}
				deal.acceptTrade(true);
				return UncivActions.Result.done("Peace with " + name + ".");
			}
			case "friendship" -> {
				if (!other.isMajorCiv() || !viewer.getDiplomacyFunctions().canSignDeclarationOfFriendshipWith(other)) {
					return UncivActions.Result.refused("A declaration of friendship with " + name + " is not possible now.");
				}
				if (other.isHuman()) {
					other.getPopupAlerts().removeIf(alert -> alert.getType() == AlertType.DeclarationOfFriendship && alert.getValue().equals(viewer.getCivID()));
					other.getPopupAlerts().add(new PopupAlert(AlertType.DeclarationOfFriendship, viewer.getCivID()));
					return UncivActions.Result.done("You have offered " + name + " a declaration of friendship. It is theirs to accept.");
				}
				if (!DiplomacyAutomation.INSTANCE.wantsToSignDeclarationOfFrienship$Unciv_core(other, viewer)) {
					return UncivActions.Result.refused(name + " does not wish to declare friendship.");
				}
				ours.signDeclarationOfFriendship();
				return UncivActions.Result.done("You and " + name + " have declared friendship.");
			}
			case "denounce" -> {
				if (!other.isMajorCiv() || ours.hasFlag(DiplomacyFlags.Denunciation)) {
					return UncivActions.Result.refused("You have nothing new to say about " + name + ".");
				}
				ours.denounce();
				return UncivActions.Result.done("You have denounced " + name + ".");
			}
			case "demandSettle", "demandReligion", "demandSpies", "demandAttack" -> {
				// A demand is put to them as Unciv's own screen puts it; the computer answers with its turn (and is told to the
				// player as news), a person finds it among their proposals.
				if (!other.isMajorCiv() || viewer.isAtWarWith(other)) {
					return UncivActions.Result.refused("That is not something to ask of " + name + " now.");
				}
				AlertType ask = switch (what) {
					case "demandSettle" -> AlertType.DemandToStopSettlingCitiesNear;
					case "demandReligion" -> AlertType.DemandToStopSpreadingReligion;
					case "demandSpies" -> AlertType.DemandToStopSpyingOnUs;
					default -> AlertType.DemandToNotAttackUs;
				};
				for (PopupAlert alert : other.getPopupAlerts()) {
					if (alert.getType() == ask && alert.getValue().equals(viewer.getCivID())) {
						return UncivActions.Result.refused("You have asked them that already; their answer is still to come.");
					}
				}
				other.getPopupAlerts().add(new PopupAlert(ask, viewer.getCivID()));
				return UncivActions.Result.done("Put to " + name + ". Their answer comes with their turn.");
			}
			case "protect" -> {
				if (!other.isCityState() || !other.getCityStateFunctions().otherCivCanPledgeProtection(viewer)) {
					return UncivActions.Result.refused("You cannot pledge to protect " + name + " now.");
				}
				other.getCityStateFunctions().addProtectorCiv(viewer);
				return UncivActions.Result.done("You have pledged to protect " + name + ".");
			}
			default -> {
				return UncivActions.Result.refused("That is not something you can do with them.");
			}
		}
	}

	private static Demand demand(AlertType type) {
		return switch (type) {
			case DemandToStopSettlingCitiesNear -> Demand.DoNotSettleNearUs;
			case DemandToStopSpreadingReligion -> Demand.DoNotSpreadReligion;
			case DemandToStopSpyingOnUs -> Demand.DontSpyOnUs;
			case DemandToNotAttackUs -> Demand.DoNotAttackUs;
			default -> null;
		};
	}

	/**
	 * Unciv queues little screens for a human player: news, and a few that want an answer. The
	 * news reaches the player as notifications already, so it is dropped here; what wants an answer
	 * stays until it gets one.
	 */
	void putNewsAway() {
		viewer.getPopupAlerts().removeIf(alert -> demand(alert.getType()) == null && alert.getType() != AlertType.DeclarationOfFriendship);
	}

	/** What other civilizations have put to the player and still want an answer to. */
	List<Records.Option> proposals() {
		List<Records.Option> out = new ArrayList<>();
		int index = 0;
		for (TradeRequest request : viewer.getTradeRequests()) {
			Civilization from = game.getCivilization(request.getRequestingCiv());
			Trade trade = request.getTrade();
			String give = offers(trade.getOurOffers());
			String get = offers(trade.getTheirOffers());
			String text = "From " + from.getCivName() + ": " + (get.isEmpty() ? "nothing from them" : "they give " + get) + "; " + (give.isEmpty() ? "nothing from you" : "you give " + give);
			out.add(new Records.Option(9, index, "trade:" + index, text, 0));
			index++;
		}
		index = 0;
		for (PopupAlert alert : viewer.getPopupAlerts()) {
			Demand demand = demand(alert.getType());
			if (demand != null) {
				Civilization from = game.getCivilization(alert.getValue());
				out.add(new Records.Option(10, index, "demand:" + index, "From " + from.getCivName() + ", a demand: " + UncivLink.words(demand.getDemandText()), 0));
			} else if (alert.getType() == AlertType.DeclarationOfFriendship) {
				Civilization from = game.getCivilization(alert.getValue());
				out.add(new Records.Option(10, index, "friend:" + index, "From " + from.getCivName() + ": a declaration of friendship, for all the world to see", 0));
			}
			index++;
		}
		return out;
	}

	private static String offers(List<TradeOffer> offers) {
		List<String> parts = new ArrayList<>();
		for (TradeOffer offer : offers) {
			parts.add(UncivTrade.words(offer));
		}
		return String.join(", ", parts);
	}

	UncivActions.Result answer(String which, boolean accept) {
		String[] parts = which.split(":");
		int index;
		try {
			index = Integer.parseInt(parts[1]);
		} catch (RuntimeException e) {
			return UncivActions.Result.refused("That proposal is no longer on the table.");
		}
		if (parts[0].equals("trade") && index >= 0 && index < viewer.getTradeRequests().size()) {
			TradeRequest request = viewer.getTradeRequests().get(index);
			Civilization from = game.getCivilization(request.getRequestingCiv());
			if (accept) {
				if (!new TradeEvaluation().isTradeValid(request.getTrade(), viewer, from)) {
					viewer.getTradeRequests().remove(request);
					return UncivActions.Result.refused("That deal can no longer be made: one of you no longer has what it names.");
				}
				TradeLogic deal = new TradeLogic(viewer, from);
				deal.getCurrentTrade().set(request.getTrade());
				deal.acceptTrade(true);
				if (from.isHuman()) {
					from.addNotification(viewer.getCivName() + " has accepted what you proposed.", com.unciv.logic.civilization.Notification.NotificationCategory.Trade);
				}
			} else {
				request.decline(viewer);
			}
			viewer.getTradeRequests().remove(request);
			return UncivActions.Result.done(accept ? "Agreed with " + from.getCivName() + "." : "Declined.");
		}
		if (parts[0].equals("friend") && index >= 0 && index < viewer.getPopupAlerts().size() && viewer.getPopupAlerts().get(index).getType() == AlertType.DeclarationOfFriendship) {
			PopupAlert alert = viewer.getPopupAlerts().get(index);
			Civilization from = game.getCivilization(alert.getValue());
			viewer.getPopupAlerts().remove(alert);
			if (!accept) {
				if (from.isHuman()) {
					from.addNotification(viewer.getCivName() + " does not wish to declare friendship.", com.unciv.logic.civilization.Notification.NotificationCategory.Diplomacy);
				}
				return UncivActions.Result.done("Declined.");
			}
			if (!viewer.getDiplomacyFunctions().canSignDeclarationOfFriendshipWith(from)) {
				return UncivActions.Result.refused("A declaration of friendship with " + from.getCivName() + " is not possible any more.");
			}
			viewer.getDiplomacyManager(from).signDeclarationOfFriendship();
			return UncivActions.Result.done("You and " + from.getCivName() + " have declared friendship.");
		}
		if (parts[0].equals("demand") && index >= 0 && index < viewer.getPopupAlerts().size()) {
			PopupAlert alert = viewer.getPopupAlerts().get(index);
			Demand demand = demand(alert.getType());
			Civilization from = game.getCivilization(alert.getValue());
			DiplomacyManager ours = viewer.getDiplomacyManager(from);
			if (demand != null && ours != null) {
				if (accept) {
					ours.agreeToDemand(demand);
				} else {
					ours.refuseDemand(demand);
				}
				viewer.getPopupAlerts().remove(alert);
				return UncivActions.Result.done(accept ? "You gave " + from.getCivName() + " your word." : "You told " + from.getCivName() + " you will do as you please.");
			}
		}
		return UncivActions.Result.refused("That proposal is no longer on the table.");
	}
}
