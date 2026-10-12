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
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import nl.jordythomassen.livingworld.board.BoardModel;
import nl.jordythomassen.livingworld.commands.CommandOp;
import nl.jordythomassen.livingworld.commands.CommandStatus;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.Empire;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.UnitState;
import nl.jordythomassen.livingworld.state.WorldState;

/**
 * A ruler in person. Where their figure stands while they rule from above; the land their feet
 * may be set down on (their own); the soldiers of an enemy unit, who step out to meet a ruler
 * who comes near and fight with a will of their own, never far from their unit; what falling
 * costs; and the arms a ruler buys to bear themselves.
 *
 * <p>None of it replaces the game above: a ruler's hands wound a unit, an army wins the war
 * (the game itself keeps count, see the engine's "strike").
 */
final class Ruler {
	/** On the figure of a ruler who is ruling from above. */
	static final String FIGURE_TAG = "livingworld_ruler";
	/** On a soldier that stepped out of an enemy unit. */
	static final String SOLDIER_TAG = "livingworld_soldier";
	/** On a ruler who was given their first arms. */
	private static final String ARMED_TAG = "livingworld_armed";
	private static final String TRAINED_TAG = "livingworld_trained_";
	/** How near (blocks) a ruler on foot comes to an enemy unit before its soldiers step out. */
	private static final double ENGAGE = 20.0;
	/** The health a unit loses for each of its soldiers that a ruler beats. */
	static final int PER_SOLDIER = 20;
	/** How often a ruler can train: a heart each time. */
	static final int TRAINING = 5;

	/** A set of arms a ruler can buy: what it is called, the technology their people need for it, its price in gold, and what is in it. */
	record Arms(String name, String tech, int price, String holds, Item weapon, Item head, Item chest, Item legs, Item feet, Item side, Item more, int count) {
	}

	static final List<Arms> ARMS = List.of(
		new Arms("A fishing rod", "SAILING", 15, "Fish from your own shore: a city takes fish as Food", Items.FISHING_ROD, null, null, null, null, null, null, 0),
		new Arms("A bow and arrows", "ARCHERY", 30, "A bow (if you have none) and 32 arrows", Items.BOW, null, null, null, null, null, Items.ARROW, 32),
		new Arms("Bronze arms", "BRONZE_WORKING", 40, "A sword, helmet, breastplate, greaves and boots of copper", Items.COPPER_SWORD, Items.COPPER_HELMET, Items.COPPER_CHESTPLATE,
			Items.COPPER_LEGGINGS, Items.COPPER_BOOTS, null, null, 0),
		new Arms("Iron arms", "IRON_WORKING", 100, "A sword and armour of iron, and a shield", Items.IRON_SWORD, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS,
			Items.IRON_BOOTS, Items.SHIELD, null, 0),
		new Arms("Steel arms", "STEEL", 200, "A sword and armour of the best steel", Items.DIAMOND_SWORD, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS,
			Items.DIAMOND_BOOTS, null, null, 0),
		new Arms("Arms of the new age", "RIFLING", 350, "The strongest sword and armour there is", Items.NETHERITE_SWORD, Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE,
			Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS, null, null, 0));

	/** Soldiers of one enemy unit, out to meet one ruler. */
	private static final class Fight {
		final EntityKey unit;
		final UUID ruler;
		final String name;
		final double x;
		final double z;
		final List<UUID> soldiers = new ArrayList<>();
		/** What a soldier of this unit is, as the game's own entity data with the place left open (see stepOut). */
		String soldier = "";
		/** Soldiers that have yet to step out: they come one at a time, the next only when the one before is beaten. */
		int waiting;
		/** How many checks nobody has been out: the ruler gets two seconds to breathe before the next one comes. */
		int since;
		int beaten;
		/** Since when (in checks) the ruler has been out of the soldiers' reach. */
		int alone;

		Fight(EntityKey unit, UUID ruler, String name, double x, double z) {
			this.unit = unit;
			this.ruler = ruler;
			this.name = name;
			this.x = x;
			this.z = z;
		}
	}

	private final LivingWorldServer session;
	private final Map<EntityKey, Fight> fights = new HashMap<>();
	private final Map<UUID, Fight> bySoldier = new HashMap<>();
	/** Units whose soldiers have had their fight this turn (the turn it was), or that have none to send. */
	private final Map<EntityKey, Integer> rested = new HashMap<>();
	/** Units the game is being asked about, and at which tick: an answer that never comes is not waited for for ever. */
	private final Map<EntityKey, Integer> asking = new HashMap<>();
	/** The figure of each ruler who rules from above. */
	private final Map<UUID, UUID> figures = new HashMap<>();
	private final Set<UUID> ours = new HashSet<>();
	/** Where each ruler last stood on their own feet: x and z. */
	private final Map<UUID, double[]> stood = new HashMap<>();
	private final Set<UUID> fallen = new HashSet<>();

	Ruler(LivingWorldServer session) {
		this.session = session;
	}

	boolean isOurs(UUID entity) {
		return ours.contains(entity);
	}

	int fights() {
		return fights.size();
	}

	int soldiers() {
		return bySoldier.size();
	}

	boolean hasFigure(ServerPlayer player) {
		return figures.containsKey(player.getUUID());
	}

	/** For the self-test: one of the soldiers that are out, or null. */
	UUID anySoldier() {
		return bySoldier.isEmpty() ? null : bySoldier.keySet().iterator().next();
	}

	/** For the self-test: what the buttons of the arms page do. */
	void buyByName(ServerPlayer player, String name) {
		if (name.equals("Training")) {
			buyTraining(player, 60 * (trained(player) + 1));
			return;
		}
		for (Arms arms : ARMS) {
			if (arms.name().equals(name)) {
				buy(player, arms);
			}
		}
	}

	/** Another game, or none: nothing of the old one stays. */
	void clear(ServerLevel level) {
		for (UUID id : new ArrayList<>(ours)) {
			Entity entity = level.getEntity(id);
			if (entity != null) {
				entity.discard();
			}
		}
		ours.clear();
		fights.clear();
		bySoldier.clear();
		rested.clear();
		asking.clear();
		figures.clear();
		stood.clear();
	}

	void forget(ServerPlayer player) {
		ServerLevel level = session.server.overworld();
		removeFigure(level, player.getUUID());
		for (Iterator<Fight> it = fights.values().iterator(); it.hasNext(); ) {
			Fight fight = it.next();
			if (fight.ruler.equals(player.getUUID())) {
				dismiss(level, fight);
				it.remove();
			}
		}
		fallen.remove(player.getUUID());
	}

	// ---- where a ruler stands ----

	/** The hex a ruler's figure stands on while they rule from above, or null when they have none. */
	PlotCoord figureAt(ServerPlayer player) {
		BoardModel model = session.model();
		double[] spot = stood.get(player.getUUID());
		return model == null || spot == null ? null : model.plotAt((int) Math.floor(spot[0]), (int) Math.floor(spot[1]));
	}

	/** Where a ruler belongs when nothing says otherwise: before the keep of their capital, else beside one of their units. */
	private double[] seat(BoardModel model, WorldState state, int civ) {
		PlotCoord at = null;
		boolean city = false;
		for (CityState c : state.cities().values()) {
			if (c.key().owner() == civ && (at == null || c.capital())) {
				at = c.at();
				city = true;
			}
		}
		if (at == null) {
			for (UnitState u : state.units().values()) {
				if (u.key().owner() == civ && !state.plot(u.at()).water()) {
					at = u.at();
					break;
				}
			}
		}
		return at == null ? null : new double[] { model.centerX(at) + 0.5, model.centerZ(at) + (city ? 7 : 3) + 0.5 };
	}

	/** Sets a ruler down before their own capital; false when they have no place of their own in this game. */
	boolean home(ServerPlayer player) {
		BoardModel model = session.model();
		WorldState state = session.stateOf(player);
		int civ = session.seats.of(player);
		double[] seat = model == null || state == null || civ < 0 ? null : seat(model, state, civ);
		if (seat == null) {
			return false;
		}
		player.teleportTo(session.server.overworld(), seat[0], model.standY((int) Math.floor(seat[0]), (int) Math.floor(seat[1])), seat[1], Set.of(), 180f, 0f, false);
		return true;
	}

	private static boolean standable(PlotState plot) {
		return plot.revealed() && !plot.water() && plot.elevation() != PlotState.MOUNTAIN;
	}

	/** Whether a ruler may be set down on this hex: land of their own. */
	boolean mayStandOn(ServerPlayer player, PlotCoord plot) {
		WorldState state = session.stateOf(player);
		int civ = session.seats.of(player);
		if (state == null || civ < 0 || plot == null || !state.session().grid().contains(plot)) {
			return false;
		}
		PlotState there = state.plot(plot);
		return standable(there) && there.owner() == civ;
	}

	/**
	 * Where a ruler who comes down is set on their feet: the hex they pointed at when it is their own
	 * land, else the nearest of their own land to it; without a hex pointed at, where their figure
	 * stands. A people without land yet (a settler on the way) is set down beside one of its units.
	 */
	PlotCoord landing(ServerPlayer player, PlotCoord wanted) {
		BoardModel model = session.model();
		WorldState state = session.stateOf(player);
		int civ = session.seats.of(player);
		if (model == null || state == null) {
			return null;
		}
		PlotCoord from = wanted != null ? wanted : figureAt(player);
		if (from == null) {
			from = model.plotAt(player.getBlockX(), player.getBlockZ());
		}
		if (from == null) {
			return null;
		}
		var grid = state.session().grid();
		PlotCoord own = null;
		PlotCoord any = null;
		int nearestOwn = Integer.MAX_VALUE;
		int nearestAny = Integer.MAX_VALUE;
		for (int x = 0; x < grid.width(); x++) {
			for (int y = 0; y < grid.height(); y++) {
				PlotCoord at = new PlotCoord(x, y);
				PlotState plot = state.plot(at);
				if (!standable(plot)) {
					continue;
				}
				int distance = grid.distance(from, at);
				if (civ >= 0 && plot.owner() == civ && distance < nearestOwn) {
					nearestOwn = distance;
					own = at;
				}
				if (distance < nearestAny) {
					nearestAny = distance;
					any = at;
				}
			}
		}
		if (own != null || civ < 0) {
			return own != null ? own : any;
		}
		// No land yet: beside the nearest of their own units.
		PlotCoord beside = null;
		int nearest = Integer.MAX_VALUE;
		for (UnitState unit : state.units().values()) {
			if (unit.key().owner() == civ && standable(state.plot(unit.at())) && grid.distance(from, unit.at()) < nearest) {
				nearest = grid.distance(from, unit.at());
				beside = unit.at();
			}
		}
		return beside != null ? beside : any;
	}

	/** A ruler came down to walk: their figure goes, and whoever comes down for the first time is given something to wear. */
	void cameDown(ServerPlayer player) {
		removeFigure(session.server.overworld(), player.getUUID());
		if (session.builtin && session.seats.of(player) >= 0 && player.addTag(ARMED_TAG)) {
			WorldState state = session.stateOf(player);
			CivInfo civ = state == null ? null : state.civ(session.seats.of(player));
			int color = civ == null ? 0xA06A42 : civ.color();
			wear(player, EquipmentSlot.HEAD, dyed(Items.LEATHER_HELMET, color));
			wear(player, EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, color));
			wear(player, EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, color));
			wear(player, EquipmentSlot.FEET, dyed(Items.LEATHER_BOOTS, color));
			give(player, new ItemStack(Items.WOODEN_SWORD));
		}
		train(player, trained(player));
	}

	private static ItemStack dyed(Item item, int color) {
		ItemStack stack = new ItemStack(item);
		stack.set(net.minecraft.core.component.DataComponents.DYED_COLOR, new net.minecraft.world.item.component.DyedItemColor(color & 0xFFFFFF));
		return stack;
	}

	private void removeFigure(ServerLevel level, UUID player) {
		UUID id = figures.remove(player);
		if (id != null) {
			ours.remove(id);
			Entity entity = level.getEntity(id);
			if (entity != null) {
				entity.discard();
			}
		}
	}

	/** While a ruler is above, their figure stands where they last walked, for everybody to see: in their own likeness, with what they wear. */
	private void figure(ServerLevel level, ServerPlayer player, BoardModel model, WorldState state, int civ) {
		UUID id = player.getUUID();
		UUID known = figures.get(id);
		if (known != null) {
			if (level.getEntity(known) != null) {
				return;
			}
			// Out of the loaded world: it is made anew when somebody comes by.
			figures.remove(id);
			ours.remove(known);
		}
		double[] spot = stood.get(id);
		if (spot == null || model.plotAt((int) Math.floor(spot[0]), (int) Math.floor(spot[1])) == null) {
			spot = seat(model, state, civ);
			if (spot == null) {
				return;
			}
			stood.put(id, spot);
		}
		int bx = (int) Math.floor(spot[0]);
		int bz = (int) Math.floor(spot[1]);
		if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
			return;
		}
		CivInfo info = state.civ(civ);
		String name = player.getName().getString();
		String snbt = String.format(Locale.ROOT, "{id:\"minecraft:mannequin\",Pos:[%.3fd,%.3fd,%.3fd],Rotation:[180f,0f],Invulnerable:1b,Silent:1b,immovable:1b,Glowing:1b,"
			+ "CustomNameVisible:1b,CustomName:{text:%s,color:\"gold\",bold:1b},description:{text:%s,color:%s},profile:{name:%s},Tags:[\"%s\"],"
			+ "attributes:[{id:\"minecraft:scale\",base:1.50d}]}", spot[0], (double) model.standY(bx, bz), spot[1], Summon.quote(name),
			Summon.quote(info == null ? "Ruler" : "Ruler of " + info.name()), Summon.hex(Summon.readable(info == null ? 0xFFFFFF : info.color())), Summon.quote(name), FIGURE_TAG);
		Entity entity = Summon.create(level, snbt);
		if (entity == null) {
			return;
		}
		if (entity instanceof LivingEntity living) {
			for (EquipmentSlot slot : new EquipmentSlot[] { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND }) {
				ItemStack worn = player.getItemBySlot(slot);
				if (!worn.isEmpty()) {
					living.setItemSlot(slot, worn.copy());
				}
			}
		}
		figures.put(id, entity.getUUID());
		ours.add(entity.getUUID());
		level.addFreshEntity(entity);
	}

	// ---- every half second ----

	void tick(ServerLevel level) {
		BoardModel model = session.model();
		if (!session.builtin || model == null || session.server.getTickCount() % 10 != 0) {
			return;
		}
		int now = session.server.getTickCount();
		asking.values().removeIf(since -> now - since > 100);
		for (ServerPlayer player : level.players()) {
			attend(level, player);
		}
		for (Iterator<Fight> it = fights.values().iterator(); it.hasNext(); ) {
			Fight fight = it.next();
			if (!goesOn(level, model, fight)) {
				it.remove();
			}
		}
	}

	/** One ruler's half second: their figure while they are above; on foot, where they stand and whom they meet. */
	void attend(ServerLevel level, ServerPlayer player) {
		BoardModel model = session.model();
		int civ = session.seats.of(player);
		WorldState state = session.stateOf(player);
		if (model == null || civ < 0 || state == null) {
			return;
		}
		if (session.rts.isAbove(player)) {
			figure(level, player, model, state, civ);
			return;
		}
		if (player.isSpectator() || !player.isAlive()) {
			return;
		}
		if (model.plotAt(player.getBlockX(), player.getBlockZ()) != null) {
			stood.put(player.getUUID(), new double[] { player.getX(), player.getZ() });
		}
		meet(level, player, state, civ);
	}

	/** An enemy unit a ruler on foot has come near sends its soldiers out, once a turn. */
	private void meet(ServerLevel level, ServerPlayer player, WorldState state, int civ) {
		for (UnitState unit : state.units().values()) {
			EntityKey key = unit.key();
			if (key.owner() == civ || !unit.combat() || unit.embarked() || !unit.domain().equals("DOMAIN_LAND") || fights.containsKey(key) || asking.containsKey(key)) {
				continue;
			}
			Integer was = rested.get(key);
			if (was != null && was == state.turn()) {
				continue;
			}
			CivInfo owner = state.civ(key.owner());
			if (owner == null || !(owner.barbarian() || owner.atWarWithViewer())) {
				continue;
			}
			Proxies.Proxy proxy = session.proxies.unit(key);
			if (proxy == null || !proxy.placed) {
				continue;
			}
			double dx = proxy.x - player.getX();
			double dz = proxy.z - player.getZ();
			if (dx * dx + dz * dz > ENGAGE * ENGAGE) {
				continue;
			}
			asking.put(key, session.server.getTickCount());
			int turn = state.turn();
			double x = proxy.x;
			double z = proxy.z;
			boolean barbarian = owner.barbarian();
			int color = owner.color();
			int tier = owner.eraTier();
			// The game says how much of this unit a ruler's hands may still take this turn; that many soldiers come, at most.
			session.ask(player, "foe", key.id(), key.owner(), (who, rows) -> {
				asking.remove(key);
				int room = rows == null || rows.isEmpty() ? 0 : rows.get(0).id();
				int count = Math.min(room / PER_SOLDIER, unit.hp() > 66 ? 3 : unit.hp() > 33 ? 2 : 1);
				if (who == null || count < 1 || fights.containsKey(key)) {
					rested.put(key, turn);
					return;
				}
				stepOut(session.server.overworld(), who, unit, barbarian, color, tier, x, z, count);
			});
		}
	}

	/** Which of Minecraft's creatures fights for a unit: the one the unit is shown as when that one can fight on foot, else one that can. */
	private static String soldier(Looks.Figure figure, UnitState unit) {
		if (figure.wears()) {
			return figure.id();
		}
		return switch (figure.id()) {
			case "wolf", "iron_golem", "ravager", "witch" -> figure.id();
			case "evoker" -> "vindicator";
			case "creeper" -> "husk";
			case "warden" -> "iron_golem";
			default -> unit.rangedStrength() > 0 ? "skeleton" : "zombie";
		};
	}

	private void stepOut(ServerLevel level, ServerPlayer player, UnitState unit, boolean barbarian, int color, int tier, double x, double z, int count) {
		BoardModel model = session.model();
		Looks.Figure figure = Looks.figure(unit, barbarian);
		String kind = soldier(figure, unit);
		int might = Math.max(unit.strength(), unit.rangedStrength());
		// A soldier of a Warrior falls to three blows of a wooden sword and takes a heart a blow; one of a late army is five times that.
		double health = Math.max(8.0, Math.min(40.0, 8.0 + might * 0.4));
		double damage = Math.max(2.0, Math.min(7.0, 1.5 + might * 0.05));
		double scale = kind.equals("ravager") ? 0.6 : kind.equals("iron_golem") ? 0.75 : 1.0;
		String hand = figure.wears() && !figure.hand().isEmpty() ? figure.hand() : kind.equals("skeleton") ? "bow" : "";
		boolean wears = !kind.equals("wolf") && !kind.equals("iron_golem") && !kind.equals("ravager") && !kind.equals("witch");
		Fight fight = new Fight(unit.key(), player.getUUID(), unit.name(), x, z);
		// (The arms in a soldier's hand are for show: how hard he hits is his own strength, which follows his unit's.)
		String held = hand.isEmpty() ? "" : "mainhand:{id:\"minecraft:" + hand + "\",count:1,components:{\"minecraft:attribute_modifiers\":[]}}";
		String worn = (held.isEmpty() ? "" : held) + (wears ? (held.isEmpty() ? "" : ",") + Proxies.armour(color, tier) : "");
		fight.soldier = String.format(Locale.ROOT, "{id:\"minecraft:%s\",Pos:[%%.3fd,%%.3fd,%%.3fd],CustomNameVisible:1b,CustomName:{text:%s,color:\"red\"},Tags:[\"%s\"],"
			+ "CanPickUpLoot:0b,Health:%.1ff,attributes:[{id:\"minecraft:max_health\",base:%.1fd},{id:\"minecraft:attack_damage\",base:%.1fd},"
			+ "{id:\"minecraft:follow_range\",base:48.0d},{id:\"minecraft:scale\",base:%.2fd}]%s%s}", kind, Summon.quote(unit.name()), SOLDIER_TAG, health, health, damage, scale,
			worn.isEmpty() ? "" : ",equipment:{" + worn + "}", kind.contains("piglin") ? ",IsImmuneToZombification:1b" : kind.equals("wolf") ? ",AngerTime:6000" : "");
		fight.waiting = count;
		if (!send(level, fight, player)) {
			rested.put(unit.key(), Integer.MAX_VALUE);
			return;
		}
		fights.put(unit.key(), fight);
		level.playSound(null, x, player.getY(), z, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 3.0f, 1.2f);
		// (In the chat, where it stays to be read: the line over the hotbar says the land one stands on, twice a second.)
		player.sendSystemMessage(Component.literal(count == 1 ? "A soldier of the " + unit.name() + " comes at you." : "The " + unit.name() + " sends " + count + " soldiers at you, one after the other.")
			.withStyle(ChatFormatting.RED));
		LivingWorldMod.LOG.info("Living World: {} soldiers of {} step out to meet {}", count, unit.name(), player.getName().getString());
	}

	/** One soldier steps out, on the side of his unit that faces the ruler. False when the world would not have him. */
	private boolean send(ServerLevel level, Fight fight, ServerPlayer player) {
		BoardModel model = session.model();
		double toward = Math.atan2(player.getZ() - fight.z, player.getX() - fight.x) + (fight.beaten % 2 == 0 ? 0.35 : -0.35);
		double sx = fight.x + Math.cos(toward) * 3.5;
		double sz = fight.z + Math.sin(toward) * 3.5;
		Entity entity = Summon.create(level, String.format(Locale.ROOT, fight.soldier, sx, (double) model.standY((int) Math.floor(sx), (int) Math.floor(sz)), sz));
		fight.waiting--;
		fight.since = 0;
		if (entity == null) {
			return false;
		}
		ours.add(entity.getUUID());
		if (!level.addFreshEntity(entity)) {
			ours.remove(entity.getUUID());
			return false;
		}
		fight.soldiers.add(entity.getUUID());
		bySoldier.put(entity.getUUID(), fight);
		if (entity instanceof Mob mob) {
			mob.setTarget(player);
		}
		level.sendParticles(ParticleTypes.POOF, sx, entity.getY() + 1.0, sz, 8, 0.3, 0.5, 0.3, 0.02);
		return true;
	}

	/** One look at a fight: its soldiers keep to their unit and to their ruler. False when it is over. */
	private boolean goesOn(ServerLevel level, BoardModel model, Fight fight) {
		ServerPlayer player = session.server.getPlayerList().getPlayer(fight.ruler);
		WorldState state = player == null ? null : session.stateOf(player);
		UnitState unit = state == null ? null : state.units().get(fight.unit);
		Proxies.Proxy proxy = session.proxies.unit(fight.unit);
		boolean there = unit != null && proxy != null && Math.abs(proxy.x - fight.x) + Math.abs(proxy.z - fight.z) < 8.0;
		double leash = model.layout().hexWidth() * 1.5;
		boolean near = player != null && player.isAlive() && !player.isSpectator() && !session.rts.isAbove(player)
			&& sq(player.getX() - fight.x, player.getZ() - fight.z) < (leash + 10.0) * (leash + 10.0);
		fight.alone = near ? 0 : fight.alone + 1;
		for (Iterator<UUID> it = fight.soldiers.iterator(); it.hasNext(); ) {
			UUID id = it.next();
			Entity entity = level.getEntity(id);
			if (!(entity instanceof Mob mob) || !mob.isAlive()) {
				it.remove();
				bySoldier.remove(id);
				ours.remove(id);
				continue;
			}
			double away = sq(mob.getX() - fight.x, mob.getZ() - fight.z);
			if (away > (leash + 16.0) * (leash + 16.0)) {
				// Dragged or lured too far: back with its unit.
				mob.teleportTo(fight.x, model.standY((int) Math.floor(fight.x), (int) Math.floor(fight.z)), fight.z);
				mob.setTarget(null);
			} else if (away > leash * leash || !near) {
				mob.setTarget(null);
				mob.getNavigation().moveTo(fight.x, mob.getY(), fight.z, 1.1);
			} else if (mob.getTarget() != player) {
				mob.setTarget(player);
			}
		}
		if (fight.soldiers.isEmpty() && fight.waiting > 0 && near && there && ++fight.since >= 4) {
			// The next one, two seconds after the one before fell. Never two at once: with the next one on a timer a
			// ruler who was slow with the first had three on them, and fell.
			send(level, fight, player);
		}
		if (fight.soldiers.isEmpty() && fight.waiting > 0 && near && there) {
			return true;
		}
		if (fight.soldiers.isEmpty() && fight.waiting <= 0) {
			// All of them beaten: this unit has had its fight for the turn. (Lost some other way, they can be met again;
			// the game itself keeps count of what a ruler's hands may take from a unit in a turn.)
			if (fight.beaten > 0) {
				rested.put(fight.unit, state == null ? Integer.MAX_VALUE : state.turn());
				if (player != null) {
					level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.3f);
				}
			}
			return false;
		}
		// The unit moved on or is gone, or the ruler has been away for five seconds: what is left of them falls back.
		if (!there || fight.alone >= 10 || fight.soldiers.isEmpty()) {
			dismiss(level, fight);
			if (player != null && player.isAlive() && there) {
				player.sendSystemMessage(Component.literal("The soldiers of the " + fight.name + " fall back to their unit.").withStyle(ChatFormatting.GRAY));
			}
			return false;
		}
		return true;
	}

	private static double sq(double dx, double dz) {
		return dx * dx + dz * dz;
	}

	private void dismiss(ServerLevel level, Fight fight) {
		for (UUID id : fight.soldiers) {
			bySoldier.remove(id);
			ours.remove(id);
			Entity entity = level.getEntity(id);
			if (entity != null) {
				level.sendParticles(ParticleTypes.POOF, entity.getX(), entity.getY() + 1.0, entity.getZ(), 8, 0.3, 0.5, 0.3, 0.02);
				entity.discard();
			}
		}
		fight.soldiers.clear();
	}

	// ---- beating and falling ----

	/** A soldier became another creature (a zombie under water becomes a drowned one): it is the same soldier still. */
	void became(Mob before, Mob after) {
		Fight fight = bySoldier.remove(before.getUUID());
		if (fight == null) {
			return;
		}
		ours.remove(before.getUUID());
		ours.add(after.getUUID());
		after.addTag(SOLDIER_TAG);
		fight.soldiers.remove(before.getUUID());
		fight.soldiers.add(after.getUUID());
		bySoldier.put(after.getUUID(), fight);
	}

	/** Something died. A soldier beaten by a ruler costs its unit health in the game; a ruler who falls is carried home. */
	void died(LivingEntity entity, DamageSource source) {
		if (entity instanceof ServerPlayer player) {
			if (session.builtin && session.seats.of(player) >= 0) {
				fallen.add(player.getUUID());
			}
			return;
		}
		Fight fight = bySoldier.remove(entity.getUUID());
		if (fight == null) {
			return;
		}
		ours.remove(entity.getUUID());
		fight.soldiers.remove(entity.getUUID());
		if (source.getEntity() instanceof ServerPlayer killer && session.seats.of(killer) >= 0) {
			fight.beaten++;
			session.as(killer, () -> session.submit(killer, CommandOp.CHOICE, fight.unit, null, PER_SOLDIER, null, "Your fight", null, "strike:", false));
		}
	}

	/** A ruler who fell stands before their capital again. It cost their treasury, and what they carried for their cities is lost. */
	void respawned(ServerPlayer player) {
		train(player, trained(player));
		if (!fallen.remove(player.getUUID()) || !session.builtin || session.model() == null || session.seats.of(player) < 0) {
			return;
		}
		int lost = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (Hands.of(stack) != null) {
				lost += stack.getCount();
				player.getInventory().setItem(i, ItemStack.EMPTY);
			}
		}
		int goods = lost;
		session.as(player, () -> {
			session.sendHome(player);
			WorldState state = session.stateOf(player);
			if (state != null) {
				session.submit(player, CommandOp.CHOICE, new EntityKey(session.seats.of(player), -1), null, 0, null, "Carried home", null, "ruler:fell", false);
			}
			if (goods > 0) {
				LivingWorldServer.say(player, "What you carried for your cities was lost on the way (" + goods + ").");
			}
		});
	}

	// ---- arms ----

	private static int trained(ServerPlayer player) {
		int level = 0;
		for (String tag : player.entityTags()) {
			if (tag.startsWith(TRAINED_TAG)) {
				try {
					level = Math.max(level, Integer.parseInt(tag.substring(TRAINED_TAG.length())));
				} catch (NumberFormatException e) {
					// Not one of ours.
				}
			}
		}
		return Math.min(TRAINING, level);
	}

	/** A heart more for every time a ruler trained. */
	private static void train(ServerPlayer player, int level) {
		AttributeInstance health = player.getAttribute(Attributes.MAX_HEALTH);
		if (health != null && Math.abs(health.getBaseValue() - (20.0 + 2.0 * level)) > 0.01) {
			health.setBaseValue(20.0 + 2.0 * level);
		}
	}

	private static void give(ServerPlayer player, ItemStack stack) {
		if (!player.getInventory().add(stack) || !stack.isEmpty()) {
			player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
		}
	}

	/** Puts a piece of armour on; what was worn there goes to the pack. */
	private static void wear(ServerPlayer player, EquipmentSlot slot, ItemStack stack) {
		ItemStack before = player.getItemBySlot(slot);
		player.setItemSlot(slot, stack);
		if (!before.isEmpty()) {
			give(player, before.copy());
		}
	}

	/** What a ruler can buy for themselves, at one of their governors: arms their people know how to make, and training. */
	void armsMenu(ServerPlayer player, int color) {
		WorldState state = session.stateOf(player);
		int civ = session.seats.of(player);
		if (state == null || civ < 0) {
			return;
		}
		Empire empire = state.empire();
		Menus.Builder menu = session.menus.menu(player, "Arms for yourself", color);
		menu.line("Treasury " + (empire == null ? "?" : empire.gold()) + " Gold. What you buy is yours: a ruler who falls loses gold, not arms.");
		menu.columns(2);
		for (Arms arms : ARMS) {
			boolean known = state.techs().contains(arms.tech());
			menu.button(arms.name() + (known ? "  ·  " + arms.price() + " Gold" : "  ·  needs " + Gameplay.words(arms.tech())), arms.holds(), p -> buy(p, arms));
		}
		int level = trained(player);
		if (level < TRAINING) {
			int price = 60 * (level + 1);
			menu.button("Train  ·  " + price + " Gold", "One heart more, for good (" + level + " of " + TRAINING + " taken)", p -> buyTraining(p, price));
		} else {
			menu.line("You have trained all a ruler can.");
		}
		menu.open();
	}

	private void buy(ServerPlayer player, Arms arms) {
		WorldState state = session.stateOf(player);
		int civ = session.seats.of(player);
		if (state == null || civ < 0) {
			return;
		}
		if (!state.techs().contains(arms.tech())) {
			LivingWorldServer.say(player, "Your people do not know how to make that yet: it takes " + Gameplay.words(arms.tech()) + ".");
			return;
		}
		session.submitThen(player, CommandOp.CHOICE, new EntityKey(civ, -1), null, arms.price(), arms.name(), "pay:" + arms.name(), ticket -> {
			if (ticket.status() != CommandStatus.CONFIRMED) {
				return;
			}
			if (arms.weapon() != null && !(arms.weapon() == Items.BOW && player.getInventory().contains(stack -> stack.is(Items.BOW)))) {
				give(player, new ItemStack(arms.weapon()));
			}
			if (arms.head() != null) {
				wear(player, EquipmentSlot.HEAD, new ItemStack(arms.head()));
				wear(player, EquipmentSlot.CHEST, new ItemStack(arms.chest()));
				wear(player, EquipmentSlot.LEGS, new ItemStack(arms.legs()));
				wear(player, EquipmentSlot.FEET, new ItemStack(arms.feet()));
			}
			if (arms.side() != null) {
				give(player, new ItemStack(arms.side()));
			}
			if (arms.more() != null) {
				give(player, new ItemStack(arms.more(), arms.count()));
			}
			sound(player, SoundEvents.ANVIL_USE, 0.9f);
		});
	}

	private void buyTraining(ServerPlayer player, int price) {
		int civ = session.seats.of(player);
		int level = trained(player);
		if (civ < 0 || level >= TRAINING) {
			return;
		}
		session.submitThen(player, CommandOp.CHOICE, new EntityKey(civ, -1), null, price, "Training", "pay:Training", ticket -> {
			if (ticket.status() != CommandStatus.CONFIRMED) {
				return;
			}
			player.removeTag(TRAINED_TAG + level);
			player.addTag(TRAINED_TAG + (level + 1));
			train(player, level + 1);
			player.setHealth(player.getMaxHealth());
			sound(player, SoundEvents.PLAYER_LEVELUP, 1.0f);
		});
	}

	private static void sound(ServerPlayer player, SoundEvent sound, float pitch) {
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, 0.8f, pitch);
	}
}
