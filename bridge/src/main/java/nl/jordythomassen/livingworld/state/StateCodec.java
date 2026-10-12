package nl.jordythomassen.livingworld.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import nl.jordythomassen.livingworld.coords.CivWorldLayout;
import nl.jordythomassen.livingworld.coords.HexGrid;
import nl.jordythomassen.livingworld.coords.PlotCoord;

/**
 * The picture of a game as bytes, so the server can hand it to a player's own screen: the
 * top-down view draws its map, banners and panels from the very same {@link WorldState} the
 * server paints the land from. Compressed; a huge map is some tens of kilobytes.
 */
public final class StateCodec {
	private static final int VERSION = 1;

	private StateCodec() {
	}

	public static byte[] write(WorldState state) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(16_384);
			try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
				out.writeInt(VERSION);
				SessionInfo session = state.session();
				out.writeInt(session.protocol());
				out.writeUTF(session.bootId());
				out.writeUTF(session.gameId());
				out.writeInt(session.viewer());
				out.writeInt(session.viewerTeam());
				out.writeInt(session.grid().width());
				out.writeInt(session.grid().height());
				out.writeBoolean(session.grid().wrapX());
				out.writeDouble(session.grid().oddRowShift());
				out.writeBoolean(session.civLayout() != null);
				if (session.civLayout() != null) {
					out.writeDouble(session.civLayout().originX());
					out.writeDouble(session.civLayout().originY());
					out.writeDouble(session.civLayout().columnStep());
					out.writeDouble(session.civLayout().rowStep());
				}
				out.writeInt(state.turn());
				for (Legend.Kind kind : Legend.Kind.values()) {
					var entries = state.legend().entries(kind);
					out.writeInt(entries.size());
					for (Legend.Entry entry : entries) {
						out.writeInt(entry.id());
						out.writeUTF(entry.type());
						out.writeUTF(entry.name());
					}
				}
				out.writeInt(state.civs().size());
				for (CivInfo civ : state.civs().values()) {
					out.writeInt(civ.id());
					out.writeInt(civ.team());
					out.writeUTF(civ.type());
					out.writeUTF(civ.name());
					out.writeUTF(civ.adjective());
					out.writeUTF(civ.leader());
					out.writeBoolean(civ.alive());
					out.writeBoolean(civ.minor());
					out.writeBoolean(civ.barbarian());
					out.writeBoolean(civ.human());
					out.writeInt(civ.color());
					out.writeInt(civ.accentColor());
					out.writeBoolean(civ.atWarWithViewer());
					out.writeBoolean(civ.metByViewer());
					out.writeUTF(civ.era());
					out.writeUTF(civ.artStyle());
				}
				out.writeInt(state.plotCount());
				for (int i = 0; i < state.plotCount(); i++) {
					PlotState plot = state.plot(i);
					out.writeShort(plot.terrain());
					out.writeShort(plot.feature());
					out.writeByte(plot.elevation());
					out.writeShort(plot.owner());
					out.writeShort(plot.improvement());
					out.writeShort(plot.resource());
					out.writeByte(plot.route());
					out.writeInt(plot.rivers());
					out.writeInt(plot.flags());
				}
				out.writeInt(state.units().size());
				for (UnitState unit : state.units().values()) {
					key(out, unit.key());
					out.writeUTF(unit.type());
					out.writeUTF(unit.name());
					plot(out, unit.at());
					out.writeInt(unit.hp());
					out.writeInt(unit.maxHp());
					out.writeInt(unit.moves());
					out.writeInt(unit.maxMoves());
					out.writeInt(unit.strength());
					out.writeInt(unit.rangedStrength());
					out.writeUTF(unit.domain());
					out.writeInt(unit.level());
					out.writeInt(unit.experience());
					out.writeInt(unit.flags());
				}
				out.writeInt(state.cities().size());
				for (CityState city : state.cities().values()) {
					key(out, city.key());
					out.writeUTF(city.name());
					plot(out, city.at());
					out.writeInt(city.population());
					out.writeInt(city.flags());
					out.writeInt(city.hp());
					out.writeInt(city.maxHp());
					out.writeUTF(city.productionType());
					out.writeUTF(city.productionName());
					out.writeInt(city.production());
					out.writeInt(city.productionNeeded());
					out.writeInt(city.productionTurns());
					out.writeInt(city.food());
					out.writeInt(city.foodNeeded());
					out.writeInt(city.strength());
					strings(out, city.buildings());
					strings(out, city.wonders());
				}
				Empire empire = state.empire();
				out.writeBoolean(empire != null);
				if (empire != null) {
					out.writeInt(empire.gold());
					out.writeInt(empire.goldPerTurn());
					out.writeInt(empire.science());
					out.writeInt(empire.happiness());
					out.writeInt(empire.culture());
					out.writeInt(empire.culturePerTurn());
					out.writeInt(empire.policyCost());
					out.writeInt(empire.goldenAgeTurns());
					out.writeUTF(empire.era());
					out.writeInt(empire.year());
					out.writeUTF(empire.researchType());
					out.writeUTF(empire.researchName());
					out.writeInt(empire.researchTurns());
					out.writeInt(empire.policies());
					out.writeUTF(empire.blocking());
				}
				strings(out, new ArrayList<>(state.techs()));
				out.writeInt(state.relations().size());
				for (Relation relation : state.relations().values()) {
					out.writeInt(relation.id());
					out.writeBoolean(relation.minor());
					out.writeInt(relation.influence());
					out.writeInt(relation.standing());
					out.writeUTF(relation.word());
					out.writeInt(relation.ally());
					strings(out, relation.quests());
					out.writeInt(relation.giftSmall());
					out.writeInt(relation.giftMedium());
					out.writeInt(relation.giftLarge());
				}
			}
			return bytes.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("could not write the game's state", e);
		}
	}

	public static WorldState read(byte[] data) throws IOException {
		try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
			int version = in.readInt();
			if (version != VERSION) {
				throw new IOException("the game's state has version " + version + ", this build reads " + VERSION);
			}
			int protocol = in.readInt();
			String bootId = in.readUTF();
			String gameId = in.readUTF();
			int viewer = in.readInt();
			int viewerTeam = in.readInt();
			HexGrid grid = new HexGrid(in.readInt(), in.readInt(), in.readBoolean(), in.readDouble());
			CivWorldLayout civLayout = in.readBoolean() ? new CivWorldLayout(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble()) : null;
			SessionInfo session = new SessionInfo(protocol, bootId, gameId, viewer, viewerTeam, grid, civLayout);
			int turn = in.readInt();
			Legend legend = new Legend();
			for (Legend.Kind kind : Legend.Kind.values()) {
				for (int i = in.readInt(); i > 0; i--) {
					legend.put(kind, new Legend.Entry(in.readInt(), in.readUTF(), in.readUTF()));
				}
			}
			Map<Integer, CivInfo> civs = new HashMap<>();
			for (int i = in.readInt(); i > 0; i--) {
				CivInfo civ = new CivInfo(in.readInt(), in.readInt(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readBoolean(), in.readBoolean(),
					in.readBoolean(), in.readBoolean(), in.readInt(), in.readInt(), in.readBoolean(), in.readBoolean(), in.readUTF(), in.readUTF());
				civs.put(civ.id(), civ);
			}
			PlotState[] plots = new PlotState[in.readInt()];
			for (int i = 0; i < plots.length; i++) {
				plots[i] = new PlotState(in.readShort(), in.readShort(), in.readByte(), in.readShort(), in.readShort(), in.readShort(), in.readByte(), in.readInt(),
					in.readInt());
			}
			Map<EntityKey, UnitState> units = new HashMap<>();
			for (int i = in.readInt(); i > 0; i--) {
				UnitState unit = new UnitState(key(in), in.readUTF(), in.readUTF(), plot(in), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(),
					in.readInt(), in.readUTF(), in.readInt(), in.readInt(), in.readInt());
				units.put(unit.key(), unit);
			}
			Map<EntityKey, CityState> cities = new HashMap<>();
			for (int i = in.readInt(); i > 0; i--) {
				CityState city = new CityState(key(in), in.readUTF(), plot(in), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readUTF(), in.readUTF(),
					in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), strings(in), strings(in));
				cities.put(city.key(), city);
			}
			Empire empire = null;
			if (in.readBoolean()) {
				empire = new Empire(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readUTF(),
					in.readInt(), in.readUTF(), in.readUTF(), in.readInt(), in.readInt(), in.readUTF());
			}
			Set<String> techs = new HashSet<>(strings(in));
			Map<Integer, Relation> relations = new HashMap<>();
			for (int i = in.readInt(); i > 0; i--) {
				Relation relation = new Relation(in.readInt(), in.readBoolean(), in.readInt(), in.readInt(), in.readUTF(), in.readInt(), strings(in), in.readInt(),
					in.readInt(), in.readInt());
				relations.put(relation.id(), relation);
			}
			return new WorldState(session, turn, legend, civs, plots, units, cities, empire, techs, relations);
		}
	}

	private static void key(DataOutputStream out, EntityKey key) throws IOException {
		out.writeInt(key.owner());
		out.writeInt(key.id());
	}

	private static EntityKey key(DataInputStream in) throws IOException {
		return new EntityKey(in.readInt(), in.readInt());
	}

	private static void plot(DataOutputStream out, PlotCoord plot) throws IOException {
		out.writeInt(plot.x());
		out.writeInt(plot.y());
	}

	private static PlotCoord plot(DataInputStream in) throws IOException {
		return new PlotCoord(in.readInt(), in.readInt());
	}

	private static void strings(DataOutputStream out, List<String> list) throws IOException {
		out.writeInt(list.size());
		for (String text : list) {
			out.writeUTF(text);
		}
	}

	private static List<String> strings(DataInputStream in) throws IOException {
		int count = in.readInt();
		List<String> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			out.add(in.readUTF());
		}
		return out;
	}
}
