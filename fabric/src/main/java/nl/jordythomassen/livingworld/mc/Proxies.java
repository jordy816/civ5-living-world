package nl.jordythomassen.livingworld.mc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import nl.jordythomassen.livingworld.board.BoardModel;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * The Civ units and cities as characters in the Minecraft world.
 *
 * <p>Civilization V owns them: a proxy only shows what Civ says and is rebuilt from Civ's state
 * whenever that changes. Nothing done to a proxy in Minecraft flows back, except through the
 * validated commands. Characters only exist near a player and are never saved with the world.
 */
final class Proxies {
	private static final double APPEAR = 110.0;
	private static final double VANISH = 150.0;
	/** As far as the corners of a ruler's screen reach at the widest zoom. What is loaded of the world is the limit before that. */
	private static final double FARTHEST_FROM_ABOVE = 320.0;
	/** Says who rules from above. Their figures are wanted around the point they look at, as far as a screen shows from up there. */
	java.util.function.Predicate<ServerPlayer> above = player -> false;

	static final class Proxy {
		final EntityKey key;
		final boolean city;
		UnitState unit;
		CityState cityState;
		double x, y, z;
		/** How far over the ground it hovers (a ghast, a vex), in blocks. */
		double lift;
		double shownX, shownY, shownZ;
		boolean placed;
		String look = "";
		String name = "";
		UUID entity;
		UUID title;
		/** What the character rides, when it rides something: a horse, a camel, a boat. */
		UUID mount;

		Proxy(EntityKey key, boolean city) {
			this.key = key;
			this.city = city;
		}
	}

	private final Map<EntityKey, Proxy> units = new HashMap<>();
	private final Map<EntityKey, Proxy> cities = new HashMap<>();
	private final Map<UUID, Proxy> byEntity = new HashMap<>();
	private final Set<UUID> ours = new HashSet<>();
	private WorldState state;
	private int made;
	/** How many times life-size the units' figures are (the governors in their halls stay life-size). */
	double unitScale = 1.0;
	/** The units are Minecraft's own creatures, picked by what they are and how strong (the game that runs inside Minecraft), not plain figures of people. */
	boolean creatures;

	int unitCount() {
		return units.size();
	}

	int cityCount() {
		return cities.size();
	}

	int shown() {
		return byEntity.size();
	}

	int made() {
		return made;
	}

	boolean isOurs(UUID entity) {
		return ours.contains(entity);
	}

	Proxy byEntity(UUID entity) {
		return byEntity.get(entity);
	}

	Proxy unit(EntityKey key) {
		return units.get(key);
	}

	Proxy city(EntityKey key) {
		return cities.get(key);
	}

	/** Everything goes: another game, or the link was reset. */
	void clear(ServerLevel level) {
		for (Proxy proxy : units.values()) {
			hide(level, proxy);
		}
		for (Proxy proxy : cities.values()) {
			hide(level, proxy);
		}
		units.clear();
		cities.clear();
		state = null;
	}

	/** Brings the set of characters in line with Civ's current units and cities. */
	void sync(ServerLevel level, WorldState newState, BoardModel model) {
		state = newState;
		Map<PlotCoord, Integer> stack = new HashMap<>();
		for (Iterator<Proxy> it = units.values().iterator(); it.hasNext(); ) {
			Proxy proxy = it.next();
			if (!newState.units().containsKey(proxy.key)) {
				hide(level, proxy);
				it.remove();
			}
		}
		for (UnitState unit : newState.units().values()) {
			Proxy proxy = units.computeIfAbsent(unit.key(), k -> new Proxy(k, false));
			proxy.unit = unit;
			// Several units on one plot stand around its centre instead of inside each other.
			int index = stack.merge(unit.at(), 1, Integer::sum) - 1;
			boolean onCity = newState.plot(unit.at()).city();
			double radius = onCity ? Math.min(model.layout().hexWidth() * 0.34, 8.5) : (index == 0 ? 0 : 3.5);
			double angle = Math.toRadians(index * 72.0 + (onCity ? 45.0 : 0.0));
			double x = model.centerX(unit.at()) + 0.5 + Math.cos(angle) * radius;
			double z = model.centerZ(unit.at()) + 0.5 + Math.sin(angle) * radius;
			proxy.x = x;
			proxy.z = z;
			proxy.lift = creatures ? Looks.figure(unit, false).lift() * 1.9 * unitScale : 0;
			proxy.y = model.standY((int) Math.floor(x), (int) Math.floor(z)) + proxy.lift;
			String look = unitLook(unit, newState);
			String name = unitName(unit, newState);
			if (!look.equals(proxy.look)) {
				// Its equipment or allegiance changed: build the character anew.
				hide(level, proxy);
				proxy.look = look;
			}
			if (!name.equals(proxy.name)) {
				proxy.name = name;
				Entity entity = proxy.entity == null ? null : level.getEntity(proxy.entity);
				if (entity != null) {
					entity.setCustomName(Component.literal(name));
				}
			}
		}
		for (Iterator<Proxy> it = cities.values().iterator(); it.hasNext(); ) {
			Proxy proxy = it.next();
			if (!newState.cities().containsKey(proxy.key)) {
				hide(level, proxy);
				it.remove();
			}
		}
		for (CityState city : newState.cities().values()) {
			Proxy proxy = cities.computeIfAbsent(city.key(), k -> new Proxy(k, true));
			proxy.cityState = city;
			proxy.x = model.centerX(city.at()) + 0.5;
			proxy.z = model.centerZ(city.at()) + 0.5;
			proxy.y = BoardModel.LAND + 1;
			CivInfo owner = newState.civ(city.key().owner());
			String look = city.name() + "|" + city.population() + "|" + city.key().owner() + "|" + (owner == null ? 0 : owner.eraTier());
			if (!look.equals(proxy.look)) {
				hide(level, proxy);
				proxy.look = look;
			}
		}
	}

	private static String unitName(UnitState unit, WorldState state) {
		CivInfo civ = state.civ(unit.key().owner());
		boolean enemy = civ != null && civ.atWarWithViewer();
		return (enemy ? "[Enemy] " : "") + unit.name() + " " + unit.hp() + "/" + unit.maxHp();
	}

	/** Everything that decides how a unit's character looks; a change means rebuilding it. */
	private static String unitLook(UnitState unit, WorldState state) {
		CivInfo civ = state.civ(unit.key().owner());
		return unit.type() + "|" + (civ == null ? 0 : civ.color()) + "|" + (civ == null ? "" : civ.name()) + "|" + unit.embarked() + "|"
			+ (civ == null ? 0 : civ.eraTier()) + "|" + Math.max(unit.strength(), unit.rangedStrength());
	}

	/** What a unit rides: a boat for ships and for anybody at sea, a horse or a camel for riders, empty for those on foot. */
	String mount(UnitState unit) {
		if (creatures) {
			return Looks.figure(unit, barbarian(unit)).mount();
		}
		String t = unit.type().toUpperCase(Locale.ROOT);
		if (unit.embarked() || (unit.domain().equals("DOMAIN_SEA") && !has(t, "SUBMARINE"))) {
			return "boat";
		}
		if (unit.domain().equals("DOMAIN_AIR")) {
			return "";
		}
		if (has(t, "CAMEL")) {
			return "camel";
		}
		if (has(t, "HORSE", "CHARIOT", "KNIGHT", "CAVALRY", "LANCER", "HUSSAR", "COSSACK", "CONQUISTADOR", "MANDEKALU", "KESHIK", "SIPAHI", "CATAPHRACT", "GENERAL", "KHAN",
			"ELEPHANT")) {
			return "horse";
		}
		return "";
	}

	private boolean barbarian(UnitState unit) {
		CivInfo civ = state == null ? null : state.civ(unit.key().owner());
		return civ != null && civ.barbarian();
	}

	/** How much larger than its kind a unit stands for its strength: the weakest a little under, the strongest a good deal over. */
	private static double grown(UnitState unit) {
		return 0.9 + 0.35 * Math.min(1.0, Math.max(unit.strength(), unit.rangedStrength()) / 100.0);
	}

	/** The animal or boat itself: it stands still unless moved, cannot be hurt, and is ours to remove. */
	private Entity makeMount(ServerLevel level, Proxy proxy, String kind) {
		boolean boat = kind.endsWith("boat");
		String id = kind.equals("boat") ? "minecraft:oak_boat" : "minecraft:" + kind;
		CivInfo civ = state.civ(proxy.key.owner());
		int color = civ == null ? 0x808080 : civ.color();
		// Which horse: by the unit, so that a troop is not all one colour, and the same horse every time. It wears its owner's colour.
		String extra = "";
		if (!boat) {
			double scale = unitScale * (creatures ? grown(proxy.unit) : 1.0) * (kind.equals("ravager") ? 0.8 : 1.0);
			extra = ",NoAI:1b,Silent:1b,PersistenceRequired:1b" + (kind.equals("ravager") ? "" : ",Tame:1b") + (kind.equals("horse") ? ",Variant:" + Math.floorMod(proxy.key.id(), 7) : "")
				+ (kind.equals("horse") && creatures ? ",equipment:{body:{id:\"minecraft:leather_horse_armor\",count:1,components:{\"minecraft:dyed_color\":" + (color & 0xFFFFFF) + "}}}" : "")
				+ (scale == 1.0 ? "" : String.format(Locale.ROOT, ",attributes:[{id:\"minecraft:scale\",base:%.2fd}]", scale));
		}
		String snbt = String.format(Locale.ROOT, "{id:\"%s\",Pos:[%.3fd,%.3fd,%.3fd],Invulnerable:1b,Tags:[\"%s\"]%s}", id, proxy.shownX, proxy.shownY, proxy.shownZ,
			Summon.TAG, extra);
		return Summon.create(level, snbt);
	}

	/** What a unit of this type carries, as a stand-in for the unit's own look. */
	static String weapon(UnitState unit) {
		return Looks.weapon(unit);
	}

	private static boolean has(String type, String... words) {
		for (String word : words) {
			if (type.contains(word)) {
				return true;
			}
		}
		return false;
	}

	/** The owner's colour on the body; the helmet tells the era: leather, then chain, iron, diamond, netherite. */
	private static String armour(int rgb, int eraTier) {
		String dyed = ",count:1,components:{\"minecraft:dyed_color\":" + (rgb & 0xFFFFFF) + "}}";
		// The helmet never wears out: it is what keeps the sun off the undead among the creatures.
		String lasting = ",count:1,components:{\"minecraft:unbreakable\":{}}}";
		String helmet = switch (eraTier) {
			case 0 -> "head:{id:\"minecraft:leather_helmet\",count:1,components:{\"minecraft:unbreakable\":{},\"minecraft:dyed_color\":" + (rgb & 0xFFFFFF) + "}}";
			case 1 -> "head:{id:\"minecraft:chainmail_helmet\"" + lasting;
			case 2, 3 -> "head:{id:\"minecraft:iron_helmet\"" + lasting;
			case 4 -> "head:{id:\"minecraft:diamond_helmet\"" + lasting;
			default -> "head:{id:\"minecraft:netherite_helmet\"" + lasting;
		};
		return helmet + ",chest:{id:\"minecraft:leather_chestplate\"" + dyed
			+ ",legs:{id:\"minecraft:leather_leggings\"" + dyed + ",feet:{id:\"minecraft:leather_boots\"" + dyed;
	}

	private Entity make(ServerLevel level, Proxy proxy) {
		CivInfo civ = state.civ(proxy.key.owner());
		int color = civ == null ? 0x808080 : civ.color();
		String civName = civ == null ? "Unknown" : civ.name();
		int tier = civ == null ? 0 : civ.eraTier();
		String body;
		if (proxy.city) {
			body = "CustomName:{text:" + Summon.quote("Governor of " + proxy.cityState.name()) + "},description:{text:" + Summon.quote(civName)
				+ ",color:" + Summon.hex(Summon.readable(color)) + "},equipment:{mainhand:{id:\"minecraft:writable_book\",count:1}," + armour(color, tier) + "}";
		} else {
			body = "CustomName:{text:" + Summon.quote(proxy.name) + "},description:{text:" + Summon.quote(civName) + ",color:"
				+ Summon.hex(Summon.readable(color)) + "},equipment:{mainhand:{id:\"minecraft:" + weapon(proxy.unit) + "\",count:1}," + armour(color, tier) + "}";
		}
		if (creatures && !proxy.city) {
			Entity creature = Summon.create(level, creature(proxy, color, tier));
			if (creature != null) {
				return creature;
			}
			// A creature this version of the game does not know: the plain figure of a person stands in for it.
		}
		String size = proxy.city || unitScale == 1.0 ? "" : String.format(Locale.ROOT, "attributes:[{id:\"minecraft:scale\",base:%.2fd}],", unitScale);
		String snbt = String.format(Locale.ROOT, "{id:\"minecraft:mannequin\",Pos:[%.3fd,%.3fd,%.3fd],Invulnerable:1b,Silent:1b,immovable:1b,%s"
			+ "CustomNameVisible:1b,Tags:[\"%s\"],%s}", proxy.shownX, proxy.shownY, proxy.shownZ, size, Summon.TAG, body);
		return Summon.create(level, snbt);
	}

	/** A unit as one of Minecraft's creatures: it stands still, is silent, cannot be hurt, and wears its owner's colour where it can wear anything. */
	String creature(Proxy proxy, int color, int tier) {
		Looks.Figure figure = Looks.figure(proxy.unit, barbarian(proxy.unit));
		double scale = unitScale * figure.size() * grown(proxy.unit);
		StringBuilder gear = new StringBuilder();
		if (!figure.hand().isEmpty()) {
			gear.append("mainhand:{id:\"minecraft:").append(figure.hand()).append("\",count:1}");
		}
		if (figure.wears()) {
			gear.append(gear.length() == 0 ? "" : ",").append(armour(color, tier));
		} else if (figure.id().equals("wolf")) {
			gear.append("body:{id:\"minecraft:wolf_armor\",count:1,components:{\"minecraft:dyed_color\":").append(color & 0xFFFFFF).append("}}");
		} else if (figure.id().equals("happy_ghast")) {
			gear.append("body:{id:\"minecraft:").append(nl.jordythomassen.livingworld.board.Dye.NAMES[nl.jordythomassen.livingworld.board.Dye.nearest(color)]).append("_harness\",count:1}");
		}
		return String.format(Locale.ROOT, "{id:\"minecraft:%s\",Pos:[%.3fd,%.3fd,%.3fd],Invulnerable:1b,Silent:1b,NoAI:1b,PersistenceRequired:1b,CustomNameVisible:1b,"
			+ "CustomName:{text:%s},Tags:[\"%s\"],attributes:[{id:\"minecraft:scale\",base:%.2fd}]%s%s}", figure.id(), proxy.shownX, proxy.shownY, proxy.shownZ,
			Summon.quote(proxy.name), Summon.TAG, scale, gear.length() == 0 ? "" : ",equipment:{" + gear + "}", figure.nbt());
	}

	private Entity makeTitle(ServerLevel level, Proxy proxy) {
		CivInfo civ = state.civ(proxy.key.owner());
		int color = Summon.readable(civ == null ? 0xFFFFFF : civ.color());
		String text = proxy.cityState.name() + (proxy.cityState.capital() ? " *" : "") + "  " + proxy.cityState.population();
		String snbt = String.format(Locale.ROOT, "{id:\"minecraft:text_display\",Pos:[%.3fd,%.3fd,%.3fd],billboard:\"center\",view_range:4.0f,"
			+ "shadow:1b,background:1073741824,text:{text:%s,color:%s,bold:1b},Tags:[\"%s\"],"
			+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[5f,5f,5f]}}",
			proxy.x, (double) BoardModel.LAND + 16, proxy.z, Summon.quote(text), Summon.hex(color), Summon.TAG);
		return Summon.create(level, snbt);
	}

	private void hide(ServerLevel level, Proxy proxy) {
		for (UUID id : new UUID[] { proxy.entity, proxy.title, proxy.mount }) {
			if (id != null) {
				Entity entity = level.getEntity(id);
				if (entity != null) {
					entity.discard();
				}
				byEntity.remove(id);
				ours.remove(id);
			}
		}
		proxy.entity = null;
		proxy.title = null;
		proxy.mount = null;
	}

	/** Every tick: characters appear near players, walk towards where Civ has them, and vanish far away. */
	void tick(ServerLevel level) {
		if (state == null) {
			return;
		}
		List<ServerPlayer> players = level.players();
		if (players.isEmpty()) {
			return;
		}
		for (Proxy proxy : units.values()) {
			tick(level, players, proxy);
		}
		for (Proxy proxy : cities.values()) {
			tick(level, players, proxy);
		}
	}

	/** Shows every character whose chunk is loaded, wherever the players are (used by the self-test). */
	void showAll(ServerLevel level) {
		for (Proxy proxy : all()) {
			tick(level, List.of(), proxy);
		}
	}

	/**
	 * How far a character is from a player, for deciding whether it should stand in the world.
	 * On foot that is the plain distance. A player who rules from above hangs high over the land
	 * and behind the point in the middle of their screen, and sees much further than on foot:
	 * for them it is the distance over the ground from that point, counted down so that the edge
	 * of what their screen can show is as far as {@link #APPEAR} is on foot.
	 */
	private double distance(ServerLevel level, ServerPlayer player, Proxy proxy) {
		if (!above.test(player)) {
			return Math.sqrt(player.distanceToSqr(proxy.x, proxy.y, proxy.z));
		}
		double[] lookedAt = nl.jordythomassen.livingworld.board.Overlook.lookedAt(player.getX(), player.getZ(), player.getYRot(),
			nl.jordythomassen.livingworld.board.Overlook.reach(player.getEyeY(), BoardModel.LAND + 1, player.getXRot()));
		double shown = Math.max(APPEAR, Math.min(FARTHEST_FROM_ABOVE, level.getServer().getPlayerList().getViewDistance() * 16.0 - 24.0));
		return Math.hypot(proxy.x - lookedAt[0], proxy.z - lookedAt[1]) * APPEAR / shown;
	}

	private void tick(ServerLevel level, List<ServerPlayer> players, Proxy proxy) {
		// With no players given (the self-test), act as if one stood right here.
		double nearest = players.isEmpty() ? 0.0 : Double.MAX_VALUE;
		for (ServerPlayer player : players) {
			nearest = Math.min(nearest, distance(level, player, proxy));
		}
		Entity entity = proxy.entity == null ? null : level.getEntity(proxy.entity);
		if (entity == null && proxy.entity != null) {
			// Unloaded or removed behind our back.
			hide(level, proxy);
		}
		if (entity == null) {
			if (nearest > APPEAR || !level.getChunkSource().hasChunk((int) Math.floor(proxy.x) >> 4, (int) Math.floor(proxy.z) >> 4)) {
				return;
			}
			if (!proxy.placed) {
				proxy.shownX = proxy.x;
				proxy.shownY = proxy.y;
				proxy.shownZ = proxy.z;
				proxy.placed = true;
			}
			entity = make(level, proxy);
			if (entity == null) {
				return;
			}
			proxy.entity = entity.getUUID();
			ours.add(proxy.entity);
			byEntity.put(proxy.entity, proxy);
			level.addFreshEntity(entity);
			made++;
			String ride = proxy.city ? "" : mount(proxy.unit);
			if (!ride.isEmpty()) {
				Entity mount = makeMount(level, proxy, ride);
				if (mount != null) {
					// Known as ours before it enters the world: whatever carries our tag and is not known is taken for a leftover and removed.
					proxy.mount = mount.getUUID();
					ours.add(proxy.mount);
					// A click on the horse is a click on its rider.
					byEntity.put(proxy.mount, proxy);
					if (level.addFreshEntity(mount)) {
						entity.startRiding(mount, true, false);
					} else {
						ours.remove(proxy.mount);
						byEntity.remove(proxy.mount);
						proxy.mount = null;
					}
				}
			}
			if (proxy.city) {
				Entity title = makeTitle(level, proxy);
				if (title != null) {
					proxy.title = title.getUUID();
					ours.add(proxy.title);
					level.addFreshEntity(title);
				}
			}
			return;
		}
		if (nearest > VANISH) {
			hide(level, proxy);
			return;
		}
		if (proxy.city) {
			return;
		}
		// Walk to where Civ has the unit now. Civ decided the move; this is only how it is shown.
		double dx = proxy.x - proxy.shownX;
		double dz = proxy.z - proxy.shownZ;
		double distance = Math.hypot(dx, dz);
		// Whoever rides is moved by moving what they ride.
		Entity mover = proxy.mount == null ? null : level.getEntity(proxy.mount);
		if (mover == null) {
			mover = entity;
		}
		if (distance < 0.05) {
			if (Math.abs(proxy.y - proxy.shownY) > 0.01) {
				proxy.shownY = proxy.y;
				mover.teleportTo(proxy.shownX, proxy.shownY, proxy.shownZ);
				mover.needsSync = true;
			}
			return;
		}
		// However far, a move is over in a few seconds.
		double step = Math.max(0.28, distance / 50.0);
		if (step >= distance) {
			proxy.shownX = proxy.x;
			proxy.shownZ = proxy.z;
		} else {
			proxy.shownX += dx / distance * step;
			proxy.shownZ += dz / distance * step;
		}
		proxy.shownY = shownHeight(level, proxy, entity);
		float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
		mover.snapTo(proxy.shownX, proxy.shownY, proxy.shownZ, yaw, 0f);
		mover.setYHeadRot(yaw);
		mover.setYBodyRot(yaw);
		// The game tells players where an entity has got to only while it stands within the distance in which entities are
		// simulated, or when it is marked as moved from outside. From above a ruler sees figures well beyond that distance:
		// without the mark their flags would move and they would stay where they stood.
		mover.needsSync = true;
		if (mover != entity) {
			entity.needsSync = true;
			entity.setYRot(yaw);
			entity.setYHeadRot(yaw);
			entity.setYBodyRot(yaw);
		}
	}

	/** While walking, follow the ground under the character's feet. */
	private double shownHeight(ServerLevel level, Proxy proxy, Entity entity) {
		int x = (int) Math.floor(proxy.shownX);
		int z = (int) Math.floor(proxy.shownZ);
		int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return top <= level.getMinY() ? proxy.y : Math.min(top, BoardModel.TOP) + proxy.lift;
	}

	/**
	 * For the self-test: whether this game accepts the creature that stands for a unit, and what it
	 * rides. Returns what was made ("zombie on zombie_horse"), or a word on what was refused.
	 */
	String tryFigure(ServerLevel level, UnitState unit) {
		Proxy proxy = new Proxy(unit.key(), false);
		proxy.unit = unit;
		proxy.name = unit.name();
		proxy.shownY = BoardModel.LAND + 2;
		Looks.Figure figure = Looks.figure(unit, false);
		if (Summon.create(level, creature(proxy, 0x3355AA, 2)) == null) {
			return "REFUSED " + figure.id();
		}
		if (!figure.mount().isEmpty() && makeMount(level, proxy, figure.mount()) == null) {
			return "REFUSED " + figure.mount();
		}
		return figure.id() + (figure.mount().isEmpty() ? "" : " on " + figure.mount());
	}

	List<Proxy> all() {
		List<Proxy> out = new ArrayList<>(units.values());
		out.addAll(cities.values());
		return out;
	}
}
