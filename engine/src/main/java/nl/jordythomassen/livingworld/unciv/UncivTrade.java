package nl.jordythomassen.livingworld.unciv;

import com.unciv.logic.GameInfo;
import com.unciv.logic.civilization.Civilization;
import com.unciv.logic.trade.Trade;
import com.unciv.logic.trade.TradeEvaluation;
import com.unciv.logic.trade.TradeLogic;
import com.unciv.logic.trade.TradeOffer;
import com.unciv.logic.trade.TradeOfferType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import nl.jordythomassen.livingworld.protocol.Records;

/**
 * Deals the player proposes to another civilization: gold, gold a turn, resources, open borders,
 * embassies, research agreements, defensive pacts, peace, cities, an introduction to a third
 * civilization, and war or peace with one. The player puts a deal together piece by
 * piece; what can be offered and asked comes from Unciv's own lists, and whether the other
 * side agrees is decided by Unciv's AI, as between two computer players.
 */
final class UncivTrade {
	private static final int[] GOLD = { 25, 50, 100, 250, 500, 1000 };
	private static final int[] GOLD_A_TURN = { 1, 2, 3, 5, 10 };

	private final GameInfo game;
	private final Civilization viewer;
	/** The deal being put together with each civilization, by its name. "Ours" is what the player gives. */
	private final Map<String, Trade> drafts = new HashMap<>();

	UncivTrade(GameInfo game, Civilization viewer) {
		this.game = game;
		this.viewer = viewer;
	}

	private static boolean dealable(TradeOffer offer) {
		return offer.isTradable() && switch (offer.getType()) {
			case Embassy, Gold, Gold_Per_Turn, Treaty, Agreement, Luxury_Resource, Strategic_Resource, City, Introduction, WarDeclaration, PeaceProposal -> true;
			default -> false;
		};
	}

	private boolean canDealWith(Civilization other) {
		return other != null && other != viewer && other.isMajorCiv() && viewer.knows(other) && !other.isDefeated();
	}

	private Trade draft(Civilization other) {
		return drafts.computeIfAbsent(other.getCivName(), name -> new Trade());
	}

	private static String key(String side, TradeOffer offer, int amount) {
		return side + "|" + offer.getType().name() + "|" + offer.getName() + "|" + amount;
	}

	/** One thing in a deal, in plain words: "100 gold", "3 gold a turn", "Wine", "Open Borders", "The city of Thebes (5 citizens)". */
	static String words(TradeOffer offer) {
		return words(offer, offer.getAmount(), 0);
	}

	private static String words(TradeOffer offer, int amount, int available) {
		return switch (offer.getType()) {
			case Gold -> amount + " gold";
			case Gold_Per_Turn -> amount + " gold a turn";
			case Luxury_Resource, Strategic_Resource -> UncivLink.words(offer.getName()) + (available > 1 ? " (one of " + available + ")" : "");
			// Unciv's own words for these: "Declare war on Greece", "Make peace with Greece", "Introduction to Greece".
			case WarDeclaration, PeaceProposal, Introduction -> UncivLink.words(offer.getOfferText(0).split("\n")[0]);
			case City -> city(offer);
			default -> UncivLink.words(offer.getName());
		};
	}

	/** A city in a deal goes by its name and size; Unciv knows it by a long id. */
	private static String city(TradeOffer offer) {
		var game = com.unciv.UncivGame.Companion.getCurrent().getGameInfo();
		if (game != null) {
			for (var city : Kt.list(game.getCities())) {
				if (city.getId().equals(offer.getName())) {
					return "The city of " + UncivLink.words(city.getName()) + " (" + city.getPopulation().getPopulation() + " citizens)";
				}
			}
		}
		return "A city";
	}

	/** What one side could put into the deal, each as one choice (gold in a few round sums). {@code ours}: what the player could give. */
	List<Records.Option> offers(Civilization other, boolean ours) {
		List<Records.Option> out = new ArrayList<>();
		if (!canDealWith(other)) {
			return out;
		}
		TradeLogic logic = new TradeLogic(viewer, other);
		Trade draft = draft(other);
		String side = ours ? "give" : "take";
		int index = 0;
		for (TradeOffer offer : ours ? logic.getOurAvailableOffers() : logic.getTheirAvailableOffers()) {
			if (!dealable(offer)) {
				continue;
			}
			int inDeal = 0;
			for (TradeOffer already : ours ? draft.getOurOffers() : draft.getTheirOffers()) {
				if (already.getName().equals(offer.getName()) && already.getType() == offer.getType()) {
					inDeal = already.getAmount();
				}
			}
			int left = offer.getAmount() - inDeal;
			if (left <= 0) {
				continue;
			}
			if (offer.getType() == TradeOfferType.Gold || offer.getType() == TradeOfferType.Gold_Per_Turn) {
				for (int amount : offer.getType() == TradeOfferType.Gold ? GOLD : GOLD_A_TURN) {
					if (amount <= left) {
						out.add(new Records.Option(13, index++, key(side, offer, amount), words(offer, amount, left), 0));
					}
				}
			} else {
				out.add(new Records.Option(13, index++, key(side, offer, 1), words(offer, 1, left), 0));
			}
		}
		return out;
	}

	/** The deal as it stands: what the player gives (group 14), what the player gets (15), and what the other side thinks of it (18). */
	List<Records.Option> table(Civilization other) {
		List<Records.Option> out = new ArrayList<>();
		if (!canDealWith(other)) {
			return out;
		}
		Trade draft = draft(other);
		int index = 0;
		for (TradeOffer offer : draft.getOurOffers()) {
			out.add(new Records.Option(14, index++, key("give", offer, offer.getAmount()), words(offer, offer.getAmount(), 0), 0));
		}
		for (TradeOffer offer : draft.getTheirOffers()) {
			out.add(new Records.Option(15, index++, key("take", offer, offer.getAmount()), words(offer, offer.getAmount(), 0), 0));
		}
		String name = other.getCivName();
		String verdict;
		int agree = 0;
		if (viewer.isAtWarWith(other) && !draft.isPeaceTreaty()) {
			verdict = "You are at war with " + name + ": only a deal with a peace treaty in it can be made.";
		} else if (draft.getOurOffers().isEmpty() && draft.getTheirOffers().isEmpty()) {
			verdict = "Nothing is on the table yet.";
		} else if (!valid(draft, other)) {
			verdict = "This deal cannot be made as it stands.";
		} else if (acceptable(draft, other)) {
			verdict = name + " would agree to this.";
			agree = 1;
		} else {
			verdict = name + " would refuse this. Offer more, or ask for less.";
		}
		out.add(new Records.Option(18, 0, "verdict", verdict, agree));
		return out;
	}

	private boolean valid(Trade draft, Civilization other) {
		try {
			return new TradeEvaluation().isTradeValid(draft, viewer, other);
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** Whether the other side takes the deal: Unciv's AI judges it from its own chair, as it would another AI's offer. */
	private boolean acceptable(Trade draft, Civilization other) {
		return new TradeEvaluation().isTradeAcceptable(draft.reverse(), other, viewer);
	}

	/** A step at the table: "give|..." and "take|..." put something in, "clear" empties it, "propose" puts the deal to the other side. */
	UncivActions.Result step(Civilization other, String what) {
		if (!canDealWith(other)) {
			return UncivActions.Result.refused("That is not a civilization you can make deals with.");
		}
		Trade draft = draft(other);
		String name = other.getCivName();
		if (what.equals("clear")) {
			drafts.remove(name);
			return UncivActions.Result.done("The table is cleared.");
		}
		if (what.equals("propose")) {
			if (draft.getOurOffers().isEmpty() && draft.getTheirOffers().isEmpty()) {
				return UncivActions.Result.refused("Nothing is on the table.");
			}
			if (viewer.isAtWarWith(other) && !draft.isPeaceTreaty()) {
				return UncivActions.Result.refused("You are at war with " + name + ".");
			}
			if (!valid(draft, other)) {
				return UncivActions.Result.refused("This deal cannot be made as it stands.");
			}
			if (other.isHuman()) {
				// Somebody rules them: the deal goes to them as a proposal, and they say yes or no.
				other.getTradeRequests().add(new com.unciv.logic.trade.TradeRequest(viewer.getCivID(), draft.reverse()));
				drafts.remove(name);
				return UncivActions.Result.done("The deal is put to " + name + ". It is theirs to accept.");
			}
			if (!acceptable(draft, other)) {
				return UncivActions.Result.refused(name + " refuses this deal.");
			}
			TradeLogic deal = new TradeLogic(viewer, other);
			deal.getCurrentTrade().set(draft);
			deal.acceptTrade(true);
			drafts.remove(name);
			return UncivActions.Result.done(name + " agrees. The deal is made.");
		}
		String[] parts = what.split("\\|");
		if (parts.length != 4 || !(parts[0].equals("give") || parts[0].equals("take"))) {
			return UncivActions.Result.refused("That is not something for the table.");
		}
		boolean ours = parts[0].equals("give");
		// Only what the game itself lists can be put on the table, in an amount it has.
		Records.Option listed = offers(other, ours).stream().filter(o -> o.type().equals(what)).findFirst().orElse(null);
		if (listed == null) {
			return UncivActions.Result.refused("That cannot be " + (ours ? "offered" : "asked for") + " now.");
		}
		TradeOfferType type = TradeOfferType.valueOf(parts[1]);
		int amount = Integer.parseInt(parts[3]);
		TradeOffer offer = new TradeOffer(parts[2], type, amount, game.getSpeed());
		if (type == TradeOfferType.Treaty) {
			// A treaty is signed by both.
			draft.getOurOffers().add(offer);
			draft.getTheirOffers().add(new TradeOffer(parts[2], type, amount, game.getSpeed()));
		} else if (ours) {
			draft.getOurOffers().add(offer);
		} else {
			draft.getTheirOffers().add(offer);
		}
		return UncivActions.Result.done(listed.name() + " is on the table.");
	}

	/** A turn has passed: what could be offered then may not be there now. */
	void newTurn() {
		drafts.clear();
	}
}
