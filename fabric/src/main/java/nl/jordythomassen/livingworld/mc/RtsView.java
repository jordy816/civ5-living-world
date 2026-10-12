package nl.jordythomassen.livingworld.mc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.protocol.Records;
import nl.jordythomassen.livingworld.state.EntityKey;

/** What one player sees beyond the game itself: the view they are in, their selection, and news for them. */
public final class RtsView {
	/** The player rules from above (true) or walks the land (false). */
	public boolean rts;
	public boolean myTurn;
	/** The unit the player has selected, or null. */
	public EntityKey selected;
	/** What the selected unit can do where it stands: the game's own list. The last number says whether it can be done now. */
	public List<Records.Option> actions = new ArrayList<>();
	/** Where the selected unit can walk to this turn, and whom it could attack. */
	public List<PlotCoord> reach = new ArrayList<>();
	public List<PlotCoord> attack = new ArrayList<>();
	/** Offers of other civilizations that wait for an answer. */
	public int proposals;
	/** News since the last view, oldest first. */
	public List<String> notices = new ArrayList<>();
	/** A hex the view should go to (a unit that waits, a city that fell), or null. */
	public PlotCoord lookAt;
	/** How many chunks of land near the players are still to be painted; 0 when the land is all there. */
	public int painting;
	/** Why the player may not act, when they may not: empty (the computer's civilizations move), "ended", "watching", or who moves first in a war. */
	public String wait = "";
	/** The civilization the player rules, or -1 when they look on. */
	public int seat = -1;
	/** Several people each rule a civilization: the land the player's own people have not seen is hidden from them. */
	public boolean several;
	/**
	 * What the game says beside its picture of the world: the empire's other numbers (group 31: faith, golden age,
	 * resources, score, cities that can fire) and the selected unit's promotions (32) and experience (33).
	 */
	public List<Records.Option> extra = new ArrayList<>();

	public byte[] write() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeBoolean(rts);
			out.writeBoolean(myTurn);
			out.writeBoolean(selected != null);
			if (selected != null) {
				out.writeInt(selected.owner());
				out.writeInt(selected.id());
			}
			out.writeInt(actions.size());
			for (Records.Option option : actions) {
				out.writeInt(option.group());
				out.writeInt(option.id());
				out.writeUTF(option.type());
				out.writeUTF(option.name());
				out.writeInt(option.turns());
			}
			plots(out, reach);
			plots(out, attack);
			out.writeInt(proposals);
			out.writeInt(notices.size());
			for (String notice : notices) {
				out.writeUTF(notice);
			}
			out.writeBoolean(lookAt != null);
			if (lookAt != null) {
				out.writeInt(lookAt.x());
				out.writeInt(lookAt.y());
			}
			out.writeInt(painting);
			out.writeUTF(wait);
			out.writeInt(seat);
			out.writeBoolean(several);
			out.writeInt(extra.size());
			for (Records.Option option : extra) {
				out.writeInt(option.group());
				out.writeInt(option.id());
				out.writeUTF(option.type());
				out.writeUTF(option.name());
				out.writeInt(option.turns());
			}
			return bytes.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static RtsView read(byte[] data) throws IOException {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		RtsView view = new RtsView();
		view.rts = in.readBoolean();
		view.myTurn = in.readBoolean();
		if (in.readBoolean()) {
			view.selected = new EntityKey(in.readInt(), in.readInt());
		}
		for (int i = in.readInt(); i > 0; i--) {
			view.actions.add(new Records.Option(in.readInt(), in.readInt(), in.readUTF(), in.readUTF(), in.readInt()));
		}
		view.reach = plots(in);
		view.attack = plots(in);
		view.proposals = in.readInt();
		for (int i = in.readInt(); i > 0; i--) {
			view.notices.add(in.readUTF());
		}
		if (in.readBoolean()) {
			view.lookAt = new PlotCoord(in.readInt(), in.readInt());
		}
		view.painting = in.readInt();
		view.wait = in.readUTF();
		view.seat = in.readInt();
		view.several = in.readBoolean();
		for (int i = in.readInt(); i > 0; i--) {
			view.extra.add(new Records.Option(in.readInt(), in.readInt(), in.readUTF(), in.readUTF(), in.readInt()));
		}
		return view;
	}

	private static void plots(DataOutputStream out, List<PlotCoord> plots) throws IOException {
		out.writeInt(plots.size());
		for (PlotCoord plot : plots) {
			out.writeInt(plot.x());
			out.writeInt(plot.y());
		}
	}

	private static List<PlotCoord> plots(DataInputStream in) throws IOException {
		int count = in.readInt();
		List<PlotCoord> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			out.add(new PlotCoord(in.readInt(), in.readInt()));
		}
		return out;
	}
}
