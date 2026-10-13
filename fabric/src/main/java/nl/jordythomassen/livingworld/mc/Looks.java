package nl.jordythomassen.livingworld.mc;

import java.util.Locale;
import nl.jordythomassen.livingworld.state.UnitState;

/** How units are told apart at a glance: what each kind carries. Used for the figure in the world and for its icon on screen. */
public final class Looks {
	/** How many times life-size the units stand when the land is ruled from above: about as tall as a house, as in Civilization. */
	public static final double UNIT_SCALE = 4.0;

	private Looks() {
	}

	/** What a unit of this type carries, as a stand-in for the unit's own look. */
	public static String weapon(UnitState unit) {
		return weapon(unit.type(), unit.combat());
	}

	/**
	 * The creature of Minecraft that stands for a unit in the world.
	 *
	 * @param id the creature
	 * @param nbt what makes this one what it is (a villager's trade, a creeper's charge), with a leading comma, or empty
	 * @param size how large it stands next to a person of the same strength (a ghast is shrunk, a wolf grown)
	 * @param wears whether it can wear armour, and so its owner's colour on its body
	 * @param hand what it holds, or empty for nothing
	 * @param mount what it rides: "boat", "horse", "zombie_horse", "skeleton_horse", "camel", "ravager", or empty
	 * @param lift how far over the ground it hovers, in its own heights
	 */
	public record Figure(String id, String nbt, double size, boolean wears, String hand, String mount, double lift) {
	}

	private static Figure person(String id, String hand) {
		return new Figure(id, id.contains("piglin") ? ",IsImmuneToZombification:1b" : "", id.equals("wither_skeleton") ? 0.9 : 1.0, true, hand, "", 0);
	}

	private static Figure creature(String id, String nbt, double size) {
		return new Figure(id, nbt, size, false, "", "", 0);
	}

	private static Figure villager(String trade) {
		return new Figure("villager", ",VillagerData:{profession:\"minecraft:" + trade + "\",level:3,type:\"minecraft:plains\"}", 1.0, false, "", "", 0);
	}

	private static Figure riding(Figure rider, String mount) {
		return new Figure(rider.id(), rider.nbt(), rider.size(), rider.wears(), rider.hand(), mount, 0);
	}

	/**
	 * Which creature a unit is. Minecraft's own cast, picked by what the unit is and how strong:
	 * the weak are zombies and skeletons, the strong wither skeletons, piglins and golems; siege
	 * engines are creepers, blazes and ghasts, tanks are ravagers, the Giant Death Robot a warden;
	 * villagers do the peaceful work, and the barbarians are illagers. It need not fit the age the
	 * unit is from. It should be told apart at a glance, and the stronger look the stronger.
	 */
	public static Figure figure(UnitState unit, boolean barbarian) {
		String t = unit.type().toUpperCase(Locale.ROOT).replace(' ', '_');
		int might = Math.max(unit.strength(), unit.rangedStrength());
		boolean atSea = unit.embarked() || unit.domain().equals("DOMAIN_SEA");
		Figure figure = onLand(t, unit, might, barbarian);
		if (unit.embarked()) {
			// Anybody crossing water sits in a boat, whatever they are ashore; what does not fit in one becomes its crew.
			return riding(figure.mount().isEmpty() && (figure.wears() || figure.id().equals("villager") || figure.id().equals("wandering_trader")) ? figure
				: person("drowned", "trident"), "boat");
		}
		if (atSea) {
			// Whatever floats is a boat with a crew; the stronger the ship, the darker its wood and the harder its crew.
			Figure crew = has(t, "WORK") ? villager("fisherman") : barbarian ? person("pillager", "crossbow") : might <= 20 ? person("drowned", "trident")
				: might <= 40 ? person("piglin", "crossbow") : might <= 70 ? person("wither_skeleton", "crossbow") : person("piglin_brute", "crossbow");
			return riding(crew, has(t, "SUBMARINE") ? "pale_oak_boat" : might <= 20 ? "boat" : might <= 40 ? "spruce_boat" : might <= 70 ? "dark_oak_boat" : "mangrove_boat");
		}
		return figure;
	}

	private static Figure onLand(String t, UnitState unit, int might, boolean barbarian) {
		if (!unit.combat()) {
			if (has(t, "SETTLER")) {
				return creature("wandering_trader", "", 1.0);
			}
			if (has(t, "WORKER")) {
				return villager("mason");
			}
			if (has(t, "WORK")) {
				return villager("fisherman");
			}
			if (has(t, "MISSIONARY")) {
				return villager("cleric");
			}
			if (has(t, "INQUISITOR")) {
				return creature("witch", "", 1.0);
			}
			if (has(t, "PROPHET")) {
				return creature("evoker", "", 1.1);
			}
			if (has(t, "SCIENTIST")) {
				return villager("librarian");
			}
			if (has(t, "ENGINEER")) {
				return villager("toolsmith");
			}
			if (has(t, "MERCHANT")) {
				return creature("wandering_trader", "", 1.1);
			}
			if (has(t, "ARTIST", "WRITER", "MUSICIAN")) {
				return villager("cartographer");
			}
			if (has(t, "GENERAL", "KHAN")) {
				return riding(person("wither_skeleton", "golden_sword"), "horse");
			}
			if (has(t, "ADMIRAL")) {
				return person("drowned", "trident");
			}
			return villager("nitwit");
		}
		// What is unlike anything else first.
		if (has(t, "DEATH_ROBOT")) {
			return creature("warden", "", 1.2);
		}
		if (has(t, "XCOM")) {
			return creature("enderman", "", 0.95);
		}
		if (has(t, "TANK", "PANZER", "LANDSHIP", "MODERN_ARMOR", "MECHANIZED")) {
			return creature("ravager", "", 1.05 + Math.min(0.25, might / 400.0));
		}
		if (has(t, "HELICOPTER")) {
			return new Figure("bee", ",AngerTime:2000000", 2.6, false, "", "", 1.0);
		}
		if (has(t, "ANTI_AIRCRAFT", "MOBILE_SAM")) {
			return creature("shulker", "", 1.3);
		}
		if (has(t, "ATOMIC", "NUCLEAR")) {
			return creature("creeper", ",powered:1b", 1.5);
		}
		if (has(t, "MISSILE")) {
			return new Figure("blaze", "", 0.8, false, "", "", 0.3);
		}
		if (unit.domain().equals("DOMAIN_AIR")) {
			return has(t, "BOMBER", "B17", "STEALTH") ? new Figure("happy_ghast", "", 0.5, false, "", "", 0.5) : new Figure("vex", "", 1.45, false, "iron_sword", "", 1.2);
		}
		if (has(t, "CATAPULT", "BALLISTA")) {
			return creature("creeper", "", 1.15);
		}
		if (has(t, "TREBUCHET", "HWACHA")) {
			return creature("creeper", ",powered:1b", 1.2);
		}
		if (has(t, "CANNON", "ANTI_TANK")) {
			return new Figure("blaze", "", 1.1, false, "", "", 0.15);
		}
		if (has(t, "ROCKET")) {
			return new Figure("ghast", "", 0.62, false, "", "", 0.25);
		}
		if (has(t, "ARTILLERY")) {
			return new Figure("ghast", "", 0.5, false, "", "", 0.25);
		}
		if (has(t, "GATLING", "MACHINE_GUN")) {
			return new Figure("blaze", "", has(t, "MACHINE") ? 1.2 : 0.95, false, "", "", 0.15);
		}
		if (has(t, "BAZOOKA")) {
			return creature("breeze", "", 1.2);
		}
		// Those who ride.
		if (has(t, "ELEPHANT")) {
			return riding(person(barbarian ? "pillager" : "skeleton", "bow"), "ravager");
		}
		if (has(t, "CAMEL")) {
			return riding(person(barbarian ? "pillager" : "skeleton", "bow"), "camel");
		}
		if (has(t, "HORSE", "CHARIOT", "KNIGHT", "CAVALRY", "LANCER", "HUSSAR", "COSSACK", "CONQUISTADOR", "MANDEKALU", "KESHIK", "SIPAHI", "CATAPHRACT", "COMPANION", "COMANCHE")) {
			if (barbarian) {
				return riding(person("vindicator", "iron_axe"), "horse");
			}
			if (unit.rangedStrength() > 0) {
				return riding(person("skeleton", "bow"), "skeleton_horse");
			}
			return might <= 14 ? riding(person("zombie", "iron_sword"), "zombie_horse") : might <= 25 ? riding(person("wither_skeleton", "iron_sword"), "horse")
				: riding(person("piglin_brute", "golden_sword"), "horse");
		}
		// Those who shoot.
		if (unit.rangedStrength() > 0) {
			if (barbarian) {
				return person("pillager", "crossbow");
			}
			return might <= 8 ? person("skeleton", "bow") : might <= 13 ? person("stray", "bow") : might <= 20 ? person("bogged", "bow") : person("piglin", "crossbow");
		}
		// And those who fight hand to hand.
		if (has(t, "SCOUT", "EXPLORER", "PATHFINDER")) {
			return creature("wolf", "", 1.3);
		}
		if (barbarian) {
			return person("vindicator", "iron_axe");
		}
		if (has(t, "SPEAR", "PIKE", "HOPLITE", "PHALANX", "LANDSKNECHT", "IMPI", "IMMORTAL")) {
			return person("drowned", "trident");
		}
		if (might <= 9) {
			return person("zombie", "stone_axe");
		}
		if (might <= 16) {
			return person("husk", "iron_sword");
		}
		if (might <= 22) {
			return person("wither_skeleton", "iron_sword");
		}
		if (might <= 30) {
			return person("piglin", "crossbow");
		}
		if (might <= 45) {
			return person("piglin_brute", "crossbow");
		}
		return creature("iron_golem", "", might <= 60 ? 0.85 : 1.0);
	}

	private static boolean has(String type, String... words) {
		for (String word : words) {
			if (type.contains(word)) {
				return true;
			}
		}
		return false;
	}

	/** The same for a kind of unit that does not stand anywhere yet, as in a list of what a city can train. */
	public static String weapon(String type, boolean combat) {
		String t = type.toUpperCase(Locale.ROOT).replace(' ', '_');
		if (has(t, "WORKER", "WORKBOAT", "WORK_BOAT")) {
			return "iron_shovel";
		}
		if (has(t, "SETTLER")) {
			return "campfire";
		}
		if (has(t, "SCOUT", "EXPLORER", "PATHFINDER")) {
			return "spyglass";
		}
		if (has(t, "CARAVAN", "CARGO")) {
			return "chest";
		}
		if (has(t, "MISSIONARY", "PROPHET", "INQUISITOR", "WRITER", "SCIENTIST")) {
			return "book";
		}
		if (has(t, "ARTIST", "MUSICIAN")) {
			return "painting";
		}
		if (has(t, "ENGINEER")) {
			return "iron_pickaxe";
		}
		if (has(t, "MERCHANT")) {
			return "gold_ingot";
		}
		if (has(t, "CROSSBOW", "MUSKET", "RIFLE", "INFANTRY", "MARINE", "PARATROOPER", "MINUTEMAN", "GATLING", "MACHINE_GUN", "BAZOOKA")) {
			return "crossbow";
		}
		if (has(t, "ARCHER", "BOWMAN", "SLINGER", "LONGBOW", "COMPOSITE", "CHU_KO_NU")) {
			return "bow";
		}
		if (has(t, "CATAPULT", "TREBUCHET", "CANNON", "ARTILLERY", "ROCKET", "BALLISTA", "BOMBER", "MISSILE")) {
			return "fire_charge";
		}
		if (has(t, "SPEAR", "PIKE", "HOPLITE", "PHALANX", "LANDSKNECHT", "IMPI", "LANCER")) {
			return "trident";
		}
		if (has(t, "HORSE", "CHARIOT", "KNIGHT", "CAVALRY", "CAMEL", "ELEPHANT", "HUSSAR", "COSSACK", "TANK", "PANZER", "GENERAL")) {
			return "golden_sword";
		}
		if (has(t, "WARRIOR", "BRUTE", "JAGUAR", "MAORI", "BARBARIAN")) {
			return "stone_axe";
		}
		if (has(t, "TRIREME", "GALLEY", "GALLEASS", "CARAVEL", "FRIGATE", "SHIP", "BOAT", "IRONCLAD", "SUBMARINE", "DESTROYER", "CARRIER", "CRUISER")) {
			return "oak_boat";
		}
		return combat ? "iron_sword" : "stick";
	}

	private static final String[][] TECHS = { { "POTTERY", "flower_pot" }, { "ANIMAL", "lead" }, { "ARCHERY", "bow" }, { "MINING", "iron_pickaxe" }, { "SAILING", "oak_boat" },
		{ "CALENDAR", "clock" }, { "WRITING", "writable_book" }, { "TRAPPING", "rabbit_hide" }, { "WHEEL", "minecart" }, { "MASONRY", "stone_bricks" }, { "BRONZE", "copper_ingot" },
		{ "OPTICS", "spyglass" }, { "HORSEBACK", "saddle" }, { "MATHEMATICS", "comparator" }, { "CONSTRUCTION", "bricks" }, { "PHILOSOPHY", "book" }, { "DRAMA", "painting" },
		{ "CURRENCY", "gold_nugget" }, { "ENGINEERING", "piston" }, { "IRON", "iron_ingot" }, { "THEOLOGY", "candle" }, { "CIVIL", "paper" }, { "GUILDS", "emerald" },
		{ "METAL_CASTING", "anvil" }, { "COMPASS", "compass" }, { "EDUCATION", "bookshelf" }, { "CHIVALRY", "iron_horse_armor" }, { "MACHINERY", "crossbow" }, { "PHYSICS", "observer" },
		{ "STEEL", "iron_sword" }, { "ASTRONOMY", "spyglass" }, { "ACOUSTICS", "note_block" }, { "BANKING", "gold_ingot" }, { "PRINTING", "paper" }, { "GUNPOWDER", "gunpowder" },
		{ "NAVIGATION", "map" }, { "ARCHITECTURE", "quartz_pillar" }, { "ECONOMICS", "emerald" }, { "METALLURGY", "iron_block" }, { "CHEMISTRY", "brewing_stand" },
		{ "ARCHAEOLOGY", "brush" }, { "SCIENTIFIC", "enchanting_table" }, { "INDUSTRIAL", "furnace" }, { "RIFLING", "crossbow" }, { "MILITARY", "iron_chestplate" },
		{ "FERTILIZER", "bone_meal" }, { "BIOLOGY", "oak_sapling" }, { "ELECTRICITY", "redstone" }, { "STEAM", "blast_furnace" }, { "DYNAMITE", "tnt" }, { "REFRIGERATION", "ice" },
		{ "RADIO", "jukebox" }, { "REPLACEABLE", "iron_ingot" }, { "FLIGHT", "elytra" }, { "RAILROAD", "rail" }, { "PLASTIC", "slime_ball" }, { "ELECTRONICS", "repeater" },
		{ "BALLISTICS", "firework_rocket" }, { "COMBUSTION", "flint_and_steel" }, { "PENICILLIN", "glass_bottle" }, { "ATOMIC", "tnt" }, { "RADAR", "observer" },
		{ "COMBINED", "shield" }, { "ECOLOGY", "oak_leaves" }, { "NUCLEAR", "tnt" }, { "ROCKETRY", "firework_rocket" }, { "COMPUTER", "redstone_lamp" }, { "TELECOM", "lightning_rod" },
		{ "MOBILE", "minecart" }, { "ADVANCED", "dispenser" }, { "SATELLITE", "end_rod" }, { "ROBOTICS", "iron_block" }, { "LASER", "end_rod" }, { "GLOBALIZATION", "globe_banner_pattern" },
		{ "PARTICLE", "ender_pearl" }, { "STEALTH", "phantom_membrane" }, { "NANO", "diamond" }, { "FUTURE", "nether_star" }, { "AGRICULTURE", "wheat" } };

	private static final String[][] BUILDINGS = { { "MONUMENT", "chiseled_stone_bricks" }, { "GRANARY", "hay_block" }, { "LIBRARY", "bookshelf" }, { "BARRACKS", "iron_sword" },
		{ "WALLS", "stone_brick_wall" }, { "SHRINE", "candle" }, { "TEMPLE", "lectern" }, { "WORKSHOP", "crafting_table" }, { "MARKET", "emerald" }, { "WATER", "water_bucket" },
		{ "STONE_WORKS", "stone" }, { "CIRCUS", "firework_rocket" }, { "COLOSSEUM", "iron_bars" }, { "LIGHTHOUSE", "lantern" }, { "HARBOR", "oak_boat" }, { "AQUEDUCT", "bucket" },
		{ "STABLE", "saddle" }, { "FORGE", "anvil" }, { "AMPHITHEATER", "painting" }, { "UNIVERSITY", "enchanting_table" }, { "CASTLE", "stone_bricks" }, { "ARMORY", "iron_chestplate" },
		{ "MINT", "gold_ingot" }, { "GARDEN", "poppy" }, { "BANK", "gold_block" }, { "OBSERVATORY", "spyglass" }, { "OPERA", "note_block" }, { "WINDMILL", "white_wool" },
		{ "FACTORY", "furnace" }, { "HOSPITAL", "golden_apple" }, { "PALACE", "gold_block" }, { "COURTHOUSE", "lectern" }, { "CARAVANSARY", "chest" }, { "SCHOOL", "book" },
		{ "MUSEUM", "painting" }, { "ARSENAL", "tnt" }, { "SEAPORT", "oak_boat" }, { "STADIUM", "iron_bars" }, { "PLANT", "blast_furnace" }, { "LAB", "brewing_stand" },
		{ "TOWER", "lightning_rod" }, { "HOTEL", "red_bed" }, { "AIRPORT", "elytra" }, { "PYRAMID", "sandstone" }, { "STONEHENGE", "mossy_cobblestone" }, { "ORACLE", "ender_eye" },
		{ "COLOSSUS", "copper_block" }, { "GREAT", "nether_star" }, { "HANGING", "vine" }, { "WEALTH", "gold_nugget" }, { "RESEARCH", "experience_bottle" } };

	/**
	 * A small picture for a line in a list of choices: something to research ({@code research}),
	 * to adopt ({@code policy}), or for a city to build or buy (group 0 is a unit).
	 */
	public static String choiceIcon(String kind, int group, String type, String name) {
		String t = (type + "_" + name).toUpperCase(Locale.ROOT).replace(' ', '_');
		if (kind.equals("policy")) {
			return group == 5 ? "bookshelf" : "paper";
		}
		if (kind.equals("research")) {
			for (String[] tech : TECHS) {
				if (t.contains(tech[0])) {
					return tech[1];
				}
			}
			return "experience_bottle";
		}
		if (group == 0) {
			return weapon(t, true);
		}
		for (String[] building : BUILDINGS) {
			if (t.contains(building[0])) {
				return building[1];
			}
		}
		return group == 1 ? "bricks" : "nether_star";
	}
}
