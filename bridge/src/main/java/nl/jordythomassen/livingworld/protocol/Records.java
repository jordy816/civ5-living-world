package nl.jordythomassen.livingworld.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.CivInfo;
import nl.jordythomassen.livingworld.state.EntityKey;
import nl.jordythomassen.livingworld.state.Legend;
import nl.jordythomassen.livingworld.state.PlotState;
import nl.jordythomassen.livingworld.state.UnitState;

/**
 * Decodes single records of the wire format. Each method takes the fields of one line (tag
 * first) and throws {@link BadRecord} when the line does not have the agreed shape, so a bad
 * line is dropped and logged instead of quietly producing wrong state.
 */
public final class Records {
	public static final class BadRecord extends RuntimeException {
		public BadRecord(String message) {
			super(message);
		}
	}

	private Records() {
	}

	private static void need(List<String> f, int count) {
		if (f.size() < count) {
			throw new BadRecord("record " + f.get(0) + " has " + f.size() + " fields, expected " + count);
		}
	}

	private static int i(List<String> f, int index) {
		try {
			return Integer.parseInt(f.get(index));
		} catch (NumberFormatException e) {
			throw new BadRecord("record " + f.get(0) + " field " + index + " is not a number: " + f.get(index));
		}
	}

	private static long l(List<String> f, int index) {
		try {
			return Long.parseLong(f.get(index));
		} catch (NumberFormatException e) {
			throw new BadRecord("record " + f.get(0) + " field " + index + " is not a number: " + f.get(index));
		}
	}

	private static double d(List<String> f, int index) {
		try {
			double value = Double.parseDouble(f.get(index));
			if (Double.isNaN(value) || Double.isInfinite(value)) {
				throw new NumberFormatException();
			}
			return value;
		} catch (NumberFormatException e) {
			throw new BadRecord("record " + f.get(0) + " field " + index + " is not a number: " + f.get(index));
		}
	}

	/** S proto bootId gameId turn viewer viewerTeam W H wrapX unitsRev citiesRev plotsRev civsRev viewerTurn */
	public static Heartbeat heartbeat(List<String> f) {
		need(f, 15);
		Heartbeat hb = new Heartbeat(i(f, 1), f.get(2), f.get(3), i(f, 4), i(f, 5), i(f, 6), i(f, 7), i(f, 8), i(f, 9) != 0,
			l(f, 10), l(f, 11), l(f, 12), l(f, 13), i(f, 14) != 0);
		if (hb.width() <= 0 || hb.height() <= 0 || hb.width() > 512 || hb.height() > 512) {
			throw new BadRecord("implausible map size " + hb.width() + "x" + hb.height());
		}
		return hb;
	}

	/** G x00 y00 x10 y10 x01 y01 x02 y02 */
	public static Geometry geometry(List<String> f) {
		need(f, 9);
		return new Geometry(d(f, 1), d(f, 2), d(f, 3), d(f, 4), d(f, 5), d(f, 6), d(f, 7), d(f, 8));
	}

	/** L kind id type name */
	public static void legend(List<String> f, Legend into) {
		need(f, 5);
		Legend.Kind kind = switch (f.get(1)) {
			case "T" -> Legend.Kind.TERRAIN;
			case "F" -> Legend.Kind.FEATURE;
			case "I" -> Legend.Kind.IMPROVEMENT;
			case "R" -> Legend.Kind.RESOURCE;
			case "O" -> Legend.Kind.ROUTE;
			default -> throw new BadRecord("unknown legend kind " + f.get(1));
		};
		into.put(kind, new Legend.Entry(i(f, 2), f.get(3), f.get(4)));
	}

	public static final int CIV_ALIVE = 1;
	public static final int CIV_MINOR = 2;
	public static final int CIV_BARBARIAN = 4;
	public static final int CIV_HUMAN = 8;
	public static final int CIV_AT_WAR = 16;
	public static final int CIV_MET = 32;

	/** V id team type name adjective leader flags color accent [era artStyle] */
	public static CivInfo civ(List<String> f) {
		need(f, 10);
		int flags = i(f, 7);
		return new CivInfo(i(f, 1), i(f, 2), f.get(3), f.get(4), f.get(5), f.get(6),
			(flags & CIV_ALIVE) != 0, (flags & CIV_MINOR) != 0, (flags & CIV_BARBARIAN) != 0, (flags & CIV_HUMAN) != 0,
			i(f, 8) & 0xFFFFFF, i(f, 9) & 0xFFFFFF, (flags & CIV_AT_WAR) != 0, (flags & CIV_MET) != 0,
			f.size() > 10 ? f.get(10) : "", f.size() > 11 ? f.get(11) : "");
	}

	private static List<String> list(String field) {
		if (field == null || field.isEmpty()) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (String part : field.split(",")) {
			if (!part.isEmpty()) {
				out.add(part);
			}
		}
		return List.copyOf(out);
	}

	/** Y gold goldRate science happiness culture cultureRate policyCost goldenAgeTurns era year researchType researchName researchTurns */
	public static nl.jordythomassen.livingworld.state.Empire empire(List<String> f) {
		need(f, 14);
		return new nl.jordythomassen.livingworld.state.Empire(i(f, 1), i(f, 2), i(f, 3), i(f, 4), i(f, 5), i(f, 6), i(f, 7), i(f, 8), f.get(9),
			i(f, 10), f.get(11), f.get(12), i(f, 13), f.size() > 14 ? i(f, 14) : 0, f.size() > 15 ? f.get(15) : "");
	}

	/** K tech,tech,... (the technologies the viewer knows; may come in several records) */
	public static List<String> techs(List<String> f) {
		need(f, 2);
		return list(f.get(1));
	}

	/** M id minor influence standing word ally quests giftSmall giftMedium giftLarge */
	public static nl.jordythomassen.livingworld.state.Relation relation(List<String> f) {
		need(f, 11);
		return new nl.jordythomassen.livingworld.state.Relation(i(f, 1), i(f, 2) != 0, i(f, 3), i(f, 4), f.get(5), i(f, 6), list(f.get(7)),
			i(f, 8), i(f, 9), i(f, 10));
	}

	/** One choice the game offers: O group id type name turns */
	public record Option(int group, int id, String type, String name, int turns) {
	}

	public static Option option(List<String> f) {
		need(f, 6);
		return new Option(i(f, 1), i(f, 2), f.get(3), f.get(4), i(f, 5));
	}

	/** One row (or part of a row) of plots, starting at column {@code x0}. */
	public record PlotRow(int y, int x0, List<PlotState> plots) {
	}

	/** P y x0 cell;cell;... where a cell is "?" (never seen) or t,f,e,o,i,r,rt,rv,fl */
	public static PlotRow plotRow(List<String> f) {
		need(f, 4);
		int y = i(f, 1);
		int x0 = i(f, 2);
		String[] cells = f.get(3).split(";", -1);
		List<PlotState> plots = new ArrayList<>(cells.length);
		for (String cell : cells) {
			if (cell.equals("?")) {
				plots.add(PlotState.UNKNOWN);
				continue;
			}
			String[] v = cell.split(",", -1);
			if (v.length != 9) {
				throw new BadRecord("plot cell has " + v.length + " values in row " + y);
			}
			try {
				int elevation = Integer.parseInt(v[2]);
				if (elevation < 0 || elevation > 2) {
					throw new BadRecord("plot elevation " + elevation + " in row " + y);
				}
				plots.add(new PlotState(Integer.parseInt(v[0]), Integer.parseInt(v[1]), elevation, Integer.parseInt(v[3]),
					Integer.parseInt(v[4]), Integer.parseInt(v[5]), Integer.parseInt(v[6]), Integer.parseInt(v[7]) & 7,
					Integer.parseInt(v[8]) | PlotState.F_REVEALED));
			} catch (NumberFormatException e) {
				throw new BadRecord("plot cell is not numeric in row " + y + ": " + cell);
			}
		}
		return new PlotRow(y, x0, plots);
	}

	/** U owner id type name x y hp maxHp moves maxMoves strength ranged domain level xp flags */
	public static UnitState unit(List<String> f) {
		need(f, 17);
		return new UnitState(new EntityKey(i(f, 1), i(f, 2)), f.get(3), f.get(4), new PlotCoord(i(f, 5), i(f, 6)),
			i(f, 7), i(f, 8), i(f, 9), i(f, 10), i(f, 11), i(f, 12), f.get(13), i(f, 14), i(f, 15), i(f, 16));
	}

	/** T owner id name x y pop flags hp maxHp prodType prodName prod prodNeeded prodTurns food foodNeeded strength [buildings wonders] */
	public static CityState city(List<String> f) {
		need(f, 18);
		return new CityState(new EntityKey(i(f, 1), i(f, 2)), f.get(3), new PlotCoord(i(f, 4), i(f, 5)), i(f, 6), i(f, 7),
			i(f, 8), i(f, 9), f.get(10), f.get(11), i(f, 12), i(f, 13), i(f, 14), i(f, 15), i(f, 16), i(f, 17),
			list(f.size() > 18 ? f.get(18) : ""), list(f.size() > 19 ? f.get(19) : ""));
	}

	/** E number type args... */
	public static Hint hint(List<String> f) {
		need(f, 3);
		return new Hint(l(f, 1), f.get(2), List.copyOf(f.subList(3, f.size())));
	}

	/** R commandId status code message key=value... (status 1 = applied) */
	public static CommandResult result(List<String> f) {
		need(f, 5);
		Map<String, String> values = new LinkedHashMap<>();
		for (String pair : f.subList(5, f.size())) {
			int eq = pair.indexOf('=');
			if (eq > 0) {
				values.put(pair.substring(0, eq), pair.substring(eq + 1));
			}
		}
		return new CommandResult(f.get(1), f.get(2).equals("1"), f.get(3), f.get(4), values);
	}
}
