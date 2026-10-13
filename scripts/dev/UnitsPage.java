import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.mc.Looks;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.UnitState;

/**
 * Writes docs/UNITS.md: every unit of the game with its numbers (from engine/build/docs/units.tsv, which the
 * engine's tests write from the rules) and the creature of Minecraft it is shown as (from the mod's own Looks).
 * Run by scripts/dev/units-page.sh.
 */
public final class UnitsPage {
	private static String words(String id) {
		String text = id.replace('_', ' ');
		return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
	}

	private static String creature(Looks.Figure figure) {
		String who = words(figure.id());
		int trade = figure.nbt().indexOf("profession:\"minecraft:");
		if (trade >= 0) {
			String rest = figure.nbt().substring(trade + "profession:\"minecraft:".length());
			who = "Villager (" + rest.substring(0, rest.indexOf('"')) + ")";
		}
		if (figure.nbt().contains("powered:1b")) {
			who = "Charged creeper";
		}
		if (!figure.hand().isEmpty()) {
			who += " with " + (figure.hand().matches("^[aeiou].*") ? "an " : "a ") + figure.hand().replace('_', ' ');
		}
		if (!figure.mount().isEmpty()) {
			who += figure.mount().endsWith("boat") ? ", in a " + (figure.mount().equals("boat") ? "boat" : figure.mount().replace('_', ' ')) : ", on a " + figure.mount().replace('_', ' ');
		}
		return who;
	}

	public static void main(String[] args) throws Exception {
		List<String[]> rows = new ArrayList<>();
		for (String line : Files.readAllLines(Path.of(args[0]))) {
			if (!line.isEmpty()) {
				rows.add(line.split("\t", -1));
			}
		}
		rows.sort(Comparator.comparingInt((String[] r) -> Integer.parseInt(r[8])).thenComparing(r -> r[11].isEmpty() ? 0 : 1).thenComparing(r -> r[0]));
		Map<String, StringBuilder> eras = new LinkedHashMap<>();
		int id = 0;
		for (String[] r : rows) {
			int strength = Integer.parseInt(r[2]);
			int ranged = Integer.parseInt(r[3]);
			int moves = Integer.parseInt(r[5]);
			int cost = Integer.parseInt(r[6]);
			boolean soldier = r[13].equals("1");
			int flags = (soldier ? UnitState.F_COMBAT : 0) | (r[14].equals("1") ? UnitState.F_GREAT_PERSON : 0);
			UnitState unit = new UnitState(new EntityKey(0, id++), r[1], r[0], new PlotCoord(0, 0), 100, 100, moves * 60, moves * 60, strength, ranged, r[10], 0, 0, flags);
			String era = r[9].isEmpty() ? "Without a technology" : words(r[9]);
			StringBuilder table = eras.computeIfAbsent(era, e -> new StringBuilder("\n## " + e + "\n\n| Unit | In Minecraft | Strength | Ranged | Moves | Cost | Needs | Whose |\n|---|---|---|---|---|---|---|---|\n"));
			table.append("| **").append(r[0]).append("** | ").append(creature(Looks.figure(unit, false))).append(" | ").append(strength > 0 ? strength : "").append(" | ")
				.append(ranged > 0 ? ranged + " (range " + r[4] + ")" : "").append(" | ").append(r[10].equals("DOMAIN_AIR") ? "" : moves).append(" | ").append(cost > 0 ? cost : "")
				.append(" | ").append(r[7]).append(" | ").append(r[11].isEmpty() ? "" : r[11] + (r[12].isEmpty() ? "" : " (their " + r[12] + ")")).append(" |\n");
		}
		StringBuilder page = new StringBuilder("""
			# The units, and who they are in Minecraft

			Every unit of the game: what it is in numbers, what it takes, and which of Minecraft's creatures stands
			for it on the land. The numbers are the game's own rules (Civilization V with Gods & Kings as
			[Unciv](https://github.com/yairm210/Unciv) has them, and the Shoshone's two units); the creature is the
			mod's choice. This page is written from both by `scripts/dev/units-page.sh`, so it says what the game
			does.

			**How a creature is chosen.** By what the unit is and how strong, not by the age it is from: it should
			be told apart at a glance, and the stronger should look the stronger.

			| What the unit is | The creature |
			|---|---|
			| Fights hand to hand | Zombie, then husk, then stronger undead and piglins as the strength goes up; spearmen and pikemen are drowned with a trident |
			| Shoots | Skeleton, stray, bogged, then a piglin with a crossbow |
			| Rides | A rider on a zombie horse, a horse or a skeleton horse; on a camel; an archer on a ravager for elephants |
			| Scouts | A wolf |
			| Siege | Creepers for catapults and trebuchets, a blaze for cannons and guns, a ghast for artillery and rockets |
			| Armour | A ravager |
			| In the air | A vex for fighters, a happy ghast for bombers, a bee for the helicopter |
			| At sea | A boat with a crew: the stronger the ship, the darker its wood and the harder its crew |
			| Peaceful work | Villagers by trade (a mason for the Worker, a librarian for the Great Scientist), a wandering trader for the Settler |

			A unit stands **the larger the stronger it is** (from nine tenths to one and a quarter of its kind's own
			size, and several times life-size in all, so that it can be seen from above), and wears its owner's
			colour where it can wear anything; its helmet says the age of its people (leather, chain, iron, diamond,
			netherite). **Barbarians are illagers** whatever the unit: a vindicator with an axe hand to hand, a
			pillager with a crossbow for those who shoot. **Anybody crossing water** sits in a boat.

			**On foot** (F12) the soldiers that an enemy unit sends at you are the same creature, life-size and with
			a will of their own. Where that creature cannot fight on the ground a stand-in comes: a husk for a
			creeper, a vindicator for an evoker, an iron golem for a warden, and a zombie or (for units that shoot)
			a skeleton for everything that flies or floats. How hard they hit and how much they take follows the
			unit's strength: see [GAMEPLAY.md](GAMEPLAY.md#walking-the-land).

			Strength is what the unit fights with hand to hand and defends with; *Ranged* is what it shoots with and
			how many hexes far; *Moves* is hexes a turn on open land; *Cost* is Production at normal speed (a Quick
			game takes two thirds). *Whose* names the one civilization that has it, and what it stands in for.
			""".stripIndent());
		for (StringBuilder table : eras.values()) {
			page.append(table);
		}
		Files.writeString(Path.of(args[1]), page.toString());
		System.out.println("wrote " + args[1] + ": " + rows.size() + " units in " + eras.size() + " groups");
	}
}
