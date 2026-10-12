package nl.jordythomassen.livingworld.mc.client.rts;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import nl.jordythomassen.livingworld.board.BoardModel;
import nl.jordythomassen.livingworld.board.UnitSpots;
import nl.jordythomassen.livingworld.coords.McLayout;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.mc.LivingWorldMod;
import nl.jordythomassen.livingworld.mc.Net;
import nl.jordythomassen.livingworld.mc.RtsView;
import nl.jordythomassen.livingworld.protocol.Records;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.StateCodec;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * The player's own side of ruling from above: it keeps the last picture of the game the server
 * sent, moves the view, and turns clicks into wishes for the server. It decides nothing about
 * the game itself.
 */
public final class RtsClient {
	/**
	 * A piece of news: what kind it is (the picture it gets and where a click on it leads), the hex it is about (or
	 * null), its words, when it came (in ticks of this client) and in which turn.
	 */
	public record Notice(String kind, PlotCoord place, String text, long at, int turn) {
	}

	/** The hex the pointer rests on, when it is on the land and not on a panel: where F12 sets the player down. */
	static PlotCoord pointed;

	static WorldState state;
	static BoardModel model;
	static Map<EntityKey, double[]> spots = Map.of();
	static RtsView view = new RtsView();
	static final Deque<Notice> notices = new ArrayDeque<>();

	/** A list the game offers to choose from, for the panel down the left side. */
	public record Choices(String kind, int a, List<Records.Option> options) {
	}

	static Choices choices;
	static int choicesScroll;
	/** A page of the game's menus, shown as a panel over the map; null when none is up. */
	static Net.Menu menu;
	static int menuScroll;
	/** What each hex yields (food, production, gold, science, culture, faith), when the player asked to see it. */
	static Map<PlotCoord, int[]> yields = Map.of();
	static boolean showYields;
	/** The technologies as a tree, while that panel is up. */
	static List<Records.Option> techs;
	static int techScroll = -1;
	/** Whether the tree is wanted: an answer that comes after it was closed must not bring it back. */
	static boolean techOpen;
	/** Which land the city whose list is up has its citizens work (see the engine's "worked"), and which city that is. */
	static List<Records.Option> worked = List.of();
	static int workedCity = -1;
	/** What each thing in the game's lists is and does: by the kind of list, then by the thing's key. */
	static final Map<String, Map<String, String>> about = new java.util.HashMap<>();
	/** The tree of social policies, while that panel is up. */
	static List<Records.Option> policies;
	static boolean policiesOpen;
	private static String askedPolicies = "";
	/** What the city whose list is up builds now and has lined up after it. */
	static List<Records.Option> queue = List.of();
	static int queueCity = -1;
	/** Choosing from a city's list lines the thing up behind what it builds, instead of replacing that. */
	static boolean lineUp;
	/** A fight foretold: what was last asked ("unit id/plot index"), and the game's answer to it. */
	static String battleAsked = "";
	static String battleFor = "";
	static List<Records.Option> battle = List.of();
	/** A city of the player's that is taking aim: its targets are marked and a click on one fires. -1 when none is. */
	static int aiming = -1;
	/** The whole known world as one flat map over the screen. */
	static boolean mapOpen;
	/** The page that says how the view is worked: up by itself the first time, with F1 ever after. */
	static boolean helpOpen;
	/** A fault in the view, to be dealt with at the next tick (not in the middle of a picture). */
	private static RuntimeException fault;
	private static String faultWhere = "";

	private static java.nio.file.Path helpSeenNote() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("livingworld-help-seen.txt");
	}

	/** Opens or closes the page of keys; closing it once means it does not come up by itself again. */
	static void help(boolean open) {
		helpOpen = open;
		if (!open) {
			try {
				if (!java.nio.file.Files.exists(helpSeenNote())) {
					java.nio.file.Files.writeString(helpSeenNote(), "seen");
				}
			} catch (IOException e) {
				LivingWorldMod.LOG.warn("Living World: could not note that the keys were shown", e);
			}
		}
	}

	static boolean failing() {
		return fault != null;
	}

	/** Something in the view went wrong: remembered, and dealt with at the next tick. */
	static void failed(String where, RuntimeException e) {
		if (fault == null) {
			fault = e;
			faultWhere = where;
		}
	}
	/** What the yields and the tree were last asked for, so they are asked again only when that changed. */
	private static String askedYields = "";
	private static String askedTechs = "";
	static long ticks;
	private static boolean enabled;
	private static boolean f12WasDown;
	/** From above one sees far: the world is loaded at least this many chunks around while ruling from above. */
	private static final int VIEW_CHUNKS = Integer.getInteger("livingworld.rts.distance", 20);
	/** The player's own render distance, to put back on the way down; 0 when it was left alone. */
	private static int distanceBefore;
	private static boolean distanceChecked;
	/** How many chunks the player's game held when it was last asked to work out anew what to draw. */
	private static int chunksSeen = -1;
	/** A world was joined and has not been tidied up after yet. */
	private static boolean inWorld;
	/** The tick at which the player asked to be set down, until the server has said they are; -1 otherwise. */
	private static long goingDownSince = -1;

	private RtsClient() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(Net.State.TYPE, (payload, context) -> context.client().execute(() -> onState(payload)));
		ClientPlayNetworking.registerGlobalReceiver(Net.View.TYPE, (payload, context) -> context.client().execute(() -> onView(payload)));
		ClientPlayNetworking.registerGlobalReceiver(Net.Choices.TYPE, (payload, context) -> context.client().execute(() -> {
			if (payload.kind().equals("yields")) {
				Map<PlotCoord, int[]> fresh = new java.util.HashMap<>();
				for (Records.Option option : payload.options()) {
					String[] at = option.type().split(",");
					String[] rest = option.name().split(",");
					fresh.put(new PlotCoord(Integer.parseInt(at[0]), Integer.parseInt(at[1])), new int[] { option.group(), option.id(), option.turns(),
						Integer.parseInt(rest[0]), Integer.parseInt(rest[1]), Integer.parseInt(rest[2]) });
				}
				yields = fresh;
			} else if (payload.kind().equals("techtree")) {
				techs = techOpen ? payload.options() : null;
			} else if (payload.kind().equals("worked")) {
				worked = payload.options();
				workedCity = payload.a();
			} else if (payload.kind().startsWith("about:")) {
				Map<String, String> texts = new java.util.HashMap<>();
				for (Records.Option option : payload.options()) {
					texts.put(option.type(), option.name());
				}
				about.put(payload.kind().substring(6), texts);
			} else if (payload.kind().equals("policytree")) {
				policies = policiesOpen ? payload.options() : null;
			} else if (payload.kind().equals("queue")) {
				queue = payload.options();
				queueCity = payload.a();
			} else if (payload.kind().equals("battle") || payload.kind().equals("cityBattle")) {
				battle = payload.options();
				battleFor = battleAsked;
			} else if (payload.kind().equals("lobby:nations")) {
				LobbyScreen.nations = payload.options();
			} else if (payload.kind().equals("lobby")) {
				LobbyScreen.show(payload.a(), payload.options());
			} else if (payload.kind().equals("lobby:close")) {
				LobbyScreen.close();
			} else {
				choices = new Choices(payload.kind(), payload.a(), payload.options());
				choicesScroll = 0;
				mapOpen = false;
				closePolicies();
				if (payload.kind().equals("build") || payload.kind().equals("buy") || payload.kind().equals("faith")) {
					act("ask2", payload.a(), 0, 0, 0, "worked");
					act("ask2", payload.a(), 0, 0, 0, "queue");
				} else {
					lineUp = false;
				}
			}
		}));
		ClientPlayNetworking.registerGlobalReceiver(Net.Menu.TYPE, (payload, context) -> context.client().execute(() -> {
			menu = payload;
			menuScroll = 0;
			choices = null;
			mapOpen = false;
			closeTechs();
			closePolicies();
		}));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			reset();
			inWorld = true;
			if (ClientPlayNetworking.canSend(Net.Act.TYPE)) {
				act("hello", 0, 0, 0, 0, LivingWorldMod.version());
			}
		});
		// Leaving a world is tidied up by the tick below, on the game's own thread: the news of a disconnection can come
		// from another thread, and a task handed to the game at that moment is thrown away with the world.
		ClientTickEvents.END_CLIENT_TICK.register(RtsClient::tick);
	}

	static boolean enabled() {
		return enabled;
	}

	/** Where the player's own render distance is written down while it is raised, in case the game ends before it is put back. */
	private static java.nio.file.Path distanceNote() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("livingworld-renderdistance.txt");
	}

	private static void seeFar(boolean on) {
		Minecraft minecraft = Minecraft.getInstance();
		var distance = minecraft.options.renderDistance();
		if (on && distanceBefore == 0 && distance.get() < VIEW_CHUNKS) {
			distanceBefore = distance.get();
			try {
				java.nio.file.Files.writeString(distanceNote(), Integer.toString(distanceBefore));
			} catch (IOException e) {
				LivingWorldMod.LOG.warn("Living World: could not note the render distance", e);
			}
			distance.set(VIEW_CHUNKS);
			// The server sends each player the world only as far as that player asked for, and hears of a new wish only
			// when the settings are sent to it (which the game otherwise does when it saves them). Without this the land
			// would be loaded and painted far around, and sent no further than before.
			minecraft.options.broadcastOptions();
		} else if (!on && distanceBefore > 0) {
			// Put back what the player had, unless they chose something else themselves in the meantime; and write it to the
			// settings file, because the raised value may have been saved there (any visit to the options saves them).
			if (distance.get() == VIEW_CHUNKS) {
				distance.set(distanceBefore);
			}
			distanceBefore = 0;
			minecraft.options.save();
			try {
				java.nio.file.Files.deleteIfExists(distanceNote());
			} catch (IOException e) {
				LivingWorldMod.LOG.warn("Living World: could not remove the render distance note", e);
			}
		}
	}

	/** Once, at the first tick: a render distance left raised by a game that ended badly is put back. */
	private static void restoreDistanceLeftBehind() {
		java.nio.file.Path note = distanceNote();
		if (!java.nio.file.Files.exists(note)) {
			return;
		}
		try {
			int before = Integer.parseInt(java.nio.file.Files.readString(note).trim());
			Minecraft minecraft = Minecraft.getInstance();
			if (before >= 2 && before < VIEW_CHUNKS && minecraft.options.renderDistance().get() == VIEW_CHUNKS) {
				minecraft.options.renderDistance().set(before);
				minecraft.options.save();
			}
		} catch (IOException | RuntimeException e) {
			LivingWorldMod.LOG.warn("Living World: could not put the render distance back", e);
		} finally {
			// Whatever it held, also nonsense: it is not looked at twice.
			try {
				java.nio.file.Files.deleteIfExists(note);
			} catch (IOException e) {
				LivingWorldMod.LOG.warn("Living World: could not remove the render distance note", e);
			}
		}
	}

	private static void reset() {
		toldMismatch = false;
		enabled = false;
		seeFar(false);
		RtsCamera.setActive(false);
		RtsCamera.focusSet = false;
		state = null;
		model = null;
		spots = Map.of();
		view = new RtsView();
		notices.clear();
		choices = null;
		menu = null;
		closeTechs();
		yields = Map.of();
		askedYields = "";
		worked = List.of();
		workedCity = -1;
		mapOpen = false;
		helpOpen = false;
		fault = null;
		goingDownSince = -1;
		about.clear();
		log.clear();
		closePolicies();
		queue = List.of();
		queueCity = -1;
		lineUp = false;
		battle = List.of();
		battleAsked = "";
		battleFor = "";
		aiming = -1;
		MapTexture.forget();
		TerritoryTexture.forget();
		LobbyScreen.forget();
	}

	static void closePolicies() {
		policiesOpen = false;
		policies = null;
		askedPolicies = "";
	}

	static void openPolicies() {
		policiesOpen = true;
		askedPolicies = "";
		choices = null;
		menu = null;
		mapOpen = false;
		closeTechs();
		refreshLists();
	}

	/** Something was adopted: the tree is asked for again with the next picture of the game. */
	static void policiesChanged() {
		askedPolicies = "";
	}

	/** Everything the game has told since this world was opened, the newest first, each with its turn. */
	static final Deque<String> log = new ArrayDeque<>();

	/** The news so far as a page over the map: what scrolled off the side of the screen can be read again. */
	static void showLog() {
		List<Net.Line> lines = new java.util.ArrayList<>();
		int shown = 0;
		for (String entry : log) {
			if (shown++ >= 30) {
				break;
			}
			lines.add(new Net.Line(entry, 0));
		}
		if (lines.isEmpty()) {
			lines.add(new Net.Line("Nothing has happened yet.", 0));
		}
		menu = new Net.Menu("The news so far", 0xC8C8D8, 1, lines, List.of(), "", 0, List.of(), List.of());
		menuScroll = 0;
		choices = null;
		mapOpen = false;
		closeTechs();
		closePolicies();
	}

	/** One of the empire's other numbers (see the engine's "realm"), or null. */
	static Records.Option extra(String type) {
		for (Records.Option row : view.extra) {
			if (row.group() == 31 && row.type().equals(type)) {
				return row;
			}
		}
		return null;
	}

	/** Asks what a fight would come to, once per pairing; the answer is kept until another is asked for. */
	static void foretell(String kind, int who, int plotIndex) {
		String key = kind + "/" + who + "/" + plotIndex + "/" + (state == null ? 0 : System.identityHashCode(state));
		if (!key.equals(battleAsked)) {
			battleAsked = key;
			act("ask2", who, 0, plotIndex, 0, kind);
		}
	}

	/**
	 * Whether a figure in the world is not for this player's eyes: when several people each rule a
	 * civilization the world is built from what any of them knows, and what stands on land this
	 * player's own people do not see right now is theirs to find out.
	 */
	public static boolean hidden(net.minecraft.world.entity.Entity entity) {
		if (!view.several || state == null || model == null || entity instanceof net.minecraft.world.entity.player.Player) {
			return false;
		}
		PlotCoord plot = model.plotAt(entity.getBlockX(), entity.getBlockZ());
		return plot != null && !state.plot(plot).visible();
	}

	static void closeTechs() {
		techOpen = false;
		techs = null;
		askedTechs = "";
	}

	/** Something was chosen in the tree: what it shows is asked for again with the next picture of the game. */
	static void techsChanged() {
		askedTechs = "";
	}

	static void openTechs() {
		techOpen = true;
		techScroll = -1;
		askedTechs = "";
		refreshLists();
	}

	static void yieldsOn(boolean on) {
		showYields = on;
		askedYields = "";
		refreshLists();
	}

	/**
	 * Asks for the yields and the tree again when the game moved on in a way that changes them.
	 * Not at every new picture of the game: answering such a question makes the game take a new
	 * picture, and the two would chase each other for ever.
	 */
	static void refreshLists() {
		if (state == null) {
			return;
		}
		var empire = state.empire();
		if (choices != null && (choices.kind().equals("build") || choices.kind().equals("buy") || choices.kind().equals("faith"))) {
			// The city whose list is up: who works where may have changed. (A question does not make the game move on, so this cannot run in circles.)
			act("ask2", choices.a(), 0, 0, 0, "worked");
			act("ask2", choices.a(), 0, 0, 0, "queue");
		}
		if (policiesOpen) {
			String now = state.session().gameId() + "/" + state.turn() + "/" + (empire == null ? "" : empire.policies() + "/" + empire.blocking() + "/" + (empire.culture() >= empire.policyCost()));
			if (!now.equals(askedPolicies)) {
				askedPolicies = now;
				act("ask2", 0, 0, 0, 0, "policytree");
			}
		}
		if (showYields) {
			int seen = 0;
			int worked = 0;
			for (int i = 0; i < state.plotCount(); i++) {
				seen += state.plot(i).revealed() ? 1 : 0;
				worked += state.plot(i).improvement() >= 0 ? 1 : 0;
			}
			String now = state.session().gameId() + "/" + state.turn() + "/" + seen + "/" + worked + "/" + state.cities().size();
			if (!now.equals(askedYields)) {
				askedYields = now;
				act("ask2", 0, 0, 0, 0, "yields");
			}
		}
		if (techOpen) {
			String now = state.session().gameId() + "/" + state.turn() + "/" + state.techs().size() + "/" + (empire == null ? "" : empire.researchName());
			if (!now.equals(askedTechs)) {
				askedTechs = now;
				act("ask2", 0, 0, 0, 0, "techtree");
			}
		}
	}

	/** Asks the game what there is to choose from: research, policy, or build or buy in a city of the player's. */
	static void ask(String kind, int a) {
		act("ask", a, 0, 0, 0, kind);
	}

	static void act(String verb, int a, int b, int c, int d, String text) {
		if (ClientPlayNetworking.canSend(Net.Act.TYPE)) {
			ClientPlayNetworking.send(new Net.Act(verb, a, b, c, d, text));
		}
	}

	private static void onState(Net.State payload) {
		try {
			WorldState fresh = StateCodec.read(payload.data());
			double width = payload.hexWidth();
			// The same layout the server paints the land with: the middle of the map on the block origin.
			McLayout layout = new McLayout(width, -fresh.session().grid().width() * width / 2.0, fresh.session().grid().height() * (width * Math.sqrt(3.0) / 2.0) / 2.0);
			boolean otherGame = state == null || !state.session().gameId().equals(fresh.session().gameId());
			WorldState before = state;
			state = fresh;
			model = new BoardModel(fresh, layout);
			if (!otherGame) {
				Sounds.changed(before, fresh, model);
			}
			spots = UnitSpots.of(fresh, model);
			if (otherGame) {
				RtsCamera.focusSet = false;
			}
			if (!RtsCamera.focusSet) {
				lookHome();
			}
			refreshLists();
		} catch (IOException | RuntimeException e) {
			LivingWorldMod.LOG.warn("Living World: could not read the game's state", e);
		}
	}

	/** Turns the view to the player's capital, or to one of their units when there is no city yet. */
	static void lookHome() {
		if (state == null || model == null) {
			return;
		}
		int me = state.session().viewer();
		PlotCoord at = null;
		for (CityState city : state.cities().values()) {
			if (city.key().owner() == me && (at == null || city.capital())) {
				at = city.at();
			}
		}
		if (at == null) {
			for (UnitState unit : state.units().values()) {
				if (unit.key().owner() == me) {
					at = unit.at();
					break;
				}
			}
		}
		if (at != null) {
			RtsCamera.lookAt(model.centerX(at) + 0.5, model.centerZ(at) + 0.5);
		}
	}

	/**
	 * Down to the land, to walk it: onto the hex the pointer rests on (the server sets a ruler down on their own land
	 * only, the nearest to it), or with the pointer on a panel, where the player's own figure stands.
	 */
	static void walk() {
		PlotCoord middle = enabled ? pointed : null;
		act("mode", 0, 0, middle == null ? -1 : middle.x(), middle == null ? -1 : middle.y(), "");
		// Let go of the player at once: the server is about to set them down, and a view that went on holding them
		// up for even one more tick would have them fall from the sky.
		goingDownSince = ticks;
		if (enabled) {
			setEnabled(false);
		}
	}

	private static void onView(Net.View payload) {
		try {
			RtsView fresh = RtsView.read(payload.data());
			for (String told : fresh.notices) {
				// kind | x | y | the words (see RtsView.notices)
				String[] parts = told.split("\\|", 4);
				String kind = parts.length == 4 ? parts[0] : "news";
				String text = parts.length == 4 ? parts[3] : told;
				PlotCoord place = null;
				try {
					place = parts.length == 4 && Integer.parseInt(parts[1]) >= 0 ? new PlotCoord(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])) : null;
				} catch (NumberFormatException e) {
					// News without a place.
				}
				log.addFirst((state == null ? "" : "Turn " + state.turn() + ":  ") + text);
				// The same thing said twice in a row is shown once.
				Notice last = notices.peekFirst();
				if (kind.equals("turn") || (last != null && last.text().equals(text) && ticks - last.at() < 200)) {
					continue;
				}
				notices.addFirst(new Notice(kind, place, text, ticks, state == null ? 0 : state.turn()));
				if (enabled) {
					Sounds.news(kind);
				}
			}
			while (log.size() > 200) {
				log.removeLast();
			}
			while (notices.size() > 40) {
				notices.removeLast();
			}
			view = fresh;
			if (fresh.lookAt != null && model != null) {
				RtsCamera.lookAt(model.centerX(fresh.lookAt) + 0.5, model.centerZ(fresh.lookAt) + 0.5);
			}
			if (goingDownSince >= 0) {
				// Asked to be set down: a view that still says "above" was sent before the server heard of it.
				if (!fresh.rts || ticks - goingDownSince > 100) {
					goingDownSince = -1;
				} else {
					return;
				}
			}
			if (fresh.rts != enabled) {
				setEnabled(fresh.rts);
			}
		} catch (IOException | RuntimeException e) {
			LivingWorldMod.LOG.warn("Living World: could not read the view", e);
			Minecraft minecraft = Minecraft.getInstance();
			if (!toldMismatch && minecraft.getConnection() != null && !minecraft.hasSingleplayerServer()) {
				// What the server sends cannot be read: it is of another version than this game (an older one: a newer one
				// would have said so itself). Nothing from above would work here, so the player is taken out, to a screen
				// that says why and what to do.
				toldMismatch = true;
				minecraft.getConnection().getConnection().disconnect(net.minecraft.network.chat.Component.literal("This server runs another version of Civ V Living World than your game ("
					+ LivingWorldMod.version() + ").\n\nEverybody in a game needs the same version.\nAsk the host to update the server, or get the server's version at\n"
					+ LivingWorldMod.RELEASES));
			}
		}
	}

	/** Whether the player was told that this game and the server are of different versions (once a world). */
	private static boolean toldMismatch;

	private static void setEnabled(boolean on) {
		enabled = on;
		RtsCamera.setActive(on);
		seeFar(on);
		Minecraft minecraft = Minecraft.getInstance();
		if (on && !java.nio.file.Files.exists(helpSeenNote())) {
			// The first time ever: say how it works.
			helpOpen = true;
		}
		if (!on) {
			helpOpen = false;
		}
		if (on) {
			if (!RtsCamera.focusSet) {
				lookHome();
			}
		} else {
			// Whatever was open over the map belongs to the view from above.
			choices = null;
			menu = null;
			mapOpen = false;
			closeTechs();
			if (minecraft.gui.screen() instanceof RtsScreen) {
				minecraft.gui.setScreen(null);
			}
			minecraft.mouseHandler.grabMouse();
		}
	}

	private static void tick(Minecraft minecraft) {
		ticks++;
		if (!distanceChecked) {
			distanceChecked = true;
			restoreDistanceLeftBehind();
		}
		if (minecraft.player == null || minecraft.level == null) {
			if (inWorld) {
				// The world is gone: the render distance goes back, the map's picture is let go, nothing is kept for the next one.
				inWorld = false;
				reset();
			}
			return;
		}
		// F12 switches between ruling from above and walking, wherever the player is.
		boolean f12 = InputConstants.isKeyDown(InputConstants.KEY_F12);
		if (f12 && !f12WasDown && ClientPlayNetworking.canSend(Net.Act.TYPE)) {
			if (enabled) {
				walk();
			} else {
				act("mode", 1, 0, 0, 0, "");
			}
		}
		f12WasDown = f12;
		if (fault != null) {
			// The view from above went wrong. It is closed, the player set on foot, and the game goes on: on foot it is played
			// as it always was. F12 tries the view again.
			RuntimeException e = fault;
			LivingWorldMod.LOG.error("Living World: " + faultWhere + " failed; the view from above is closed and the player set on foot", e);
			if (enabled) {
				walk();
			}
			fault = null;
			minecraft.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("The view from above ran into a fault and was closed (" + e
				+ "). You are on foot now and the game goes on; F12 tries the view again.").withStyle(net.minecraft.ChatFormatting.RED));
			return;
		}
		if (LobbyScreen.wanted && minecraft.gui.overlay() == null && (minecraft.gui.screen() == null || minecraft.gui.screen() instanceof RtsScreen)) {
			// The table or the door is still to be dealt with: it comes back when whatever lay over it is closed.
			minecraft.gui.setScreen(new LobbyScreen());
		}
		if (!enabled) {
			return;
		}
		try {
			if (minecraft.gui.screen() instanceof RtsScreen screen) {
				screen.steer();
			}
			RtsCamera.tick(minecraft.player);
		} catch (RuntimeException e) {
			failed("moving the view from above", e);
			return;
		}
		if (minecraft.gui.screen() == null && minecraft.gui.overlay() == null) {
			// A menu was closed: the panels of the view from above come back.
			minecraft.gui.setScreen(new RtsScreen());
		}
	}
}
