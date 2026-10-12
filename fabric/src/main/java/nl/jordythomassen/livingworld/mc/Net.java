package nl.jordythomassen.livingworld.mc;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import nl.jordythomassen.livingworld.protocol.Records;

/**
 * What the server and a player's own game say to each other for the view from above: the server
 * sends the picture of the game and what the player has selected; the player's game sends what
 * was clicked. Clicks are wishes: the server carries each out through the same checked commands
 * as everything else.
 */
public final class Net {
	/** The whole picture of the game, as the player's civilization knows it, and how wide a hex is in blocks. */
	public record State(byte[] data, double hexWidth) implements CustomPacketPayload {
		public static final Type<State> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LivingWorldMod.MOD_ID, "state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.of((buf, value) -> {
			buf.writeByteArray(value.data);
			buf.writeDouble(value.hexWidth);
		}, buf -> new State(buf.readByteArray(), buf.readDouble()));

		@Override
		public Type<State> type() {
			return TYPE;
		}
	}

	/** What is this player's alone: the mode, the selection and what it can do, news. See {@link RtsView}. */
	public record View(byte[] data) implements CustomPacketPayload {
		public static final Type<View> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LivingWorldMod.MOD_ID, "view"));
		public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC = StreamCodec.of((buf, value) -> buf.writeByteArray(value.data),
			buf -> new View(buf.readByteArray()));

		@Override
		public Type<View> type() {
			return TYPE;
		}
	}

	/** Something the player did on the map or pressed on a panel: a verb, up to four numbers and a word. */
	public record Act(String verb, int a, int b, int c, int d, String text) implements CustomPacketPayload {
		public static final Type<Act> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LivingWorldMod.MOD_ID, "act"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Act> CODEC = StreamCodec.of((buf, value) -> {
			buf.writeUtf(value.verb);
			buf.writeInt(value.a);
			buf.writeInt(value.b);
			buf.writeInt(value.c);
			buf.writeInt(value.d);
			buf.writeUtf(value.text);
		}, buf -> new Act(buf.readUtf(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readUtf()));

		@Override
		public Type<Act> type() {
			return TYPE;
		}
	}

	/** What the game offers to choose from right now: {@code kind} is research, policy, build or buy; {@code a} the city for the last two. */
	public record Choices(String kind, int a, List<Records.Option> options) implements CustomPacketPayload {
		public static final Type<Choices> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LivingWorldMod.MOD_ID, "choices"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Choices> CODEC = StreamCodec.of((buf, value) -> {
			buf.writeUtf(value.kind);
			buf.writeInt(value.a);
			buf.writeInt(value.options.size());
			for (Records.Option option : value.options) {
				buf.writeInt(option.group());
				buf.writeInt(option.id());
				buf.writeUtf(option.type());
				buf.writeUtf(option.name());
				buf.writeInt(option.turns());
			}
		}, buf -> {
			String kind = buf.readUtf();
			int a = buf.readInt();
			int count = buf.readInt();
			// Everything that was written is read: stopping short would leave the rest in the buffer, and the
			// game takes a message with bytes left over for a broken one and closes the connection.
			List<Records.Option> options = new ArrayList<>();
			for (int i = count; i > 0; i--) {
				options.add(new Records.Option(buf.readInt(), buf.readInt(), buf.readUtf(), buf.readUtf(), buf.readInt()));
			}
			return new Choices(kind, a, options);
		});

		@Override
		public Type<Choices> type() {
			return TYPE;
		}
	}

	/** One line of a page: its text and its colour (0 for the ordinary one). */
	public record Line(String text, int color) {
	}

	/** One button of a page: what it says, what it explains when pointed at, and the word that presses it. */
	public record Button(String label, String tip, String token) {
	}

	/**
	 * A page of the game's menus (a deal, the other civilizations, the settings ...), to show beside the map instead of over it.
	 * {@code kind} says when the page is one the player's panels lay out in their own way: "leader" (dealing with the
	 * civilization {@code a}) or "deal" (the table of a deal with it, {@code left} what the player gives, {@code right} what they get).
	 */
	public record Menu(String title, int color, int columns, List<Line> lines, List<Button> buttons, String kind, int a, List<String> left, List<String> right)
		implements CustomPacketPayload {
		public static final Type<Menu> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LivingWorldMod.MOD_ID, "menu"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Menu> CODEC = StreamCodec.of((buf, value) -> {
			buf.writeUtf(value.title);
			buf.writeInt(value.color);
			buf.writeInt(value.columns);
			buf.writeInt(value.lines.size());
			for (Line line : value.lines) {
				buf.writeUtf(line.text);
				buf.writeInt(line.color);
			}
			buf.writeInt(value.buttons.size());
			for (Button button : value.buttons) {
				buf.writeUtf(button.label);
				buf.writeUtf(button.tip);
				buf.writeUtf(button.token);
			}
			buf.writeUtf(value.kind);
			buf.writeInt(value.a);
			for (List<String> side : List.of(value.left, value.right)) {
				buf.writeInt(side.size());
				for (String thing : side) {
					buf.writeUtf(thing);
				}
			}
		}, buf -> {
			String title = buf.readUtf();
			int color = buf.readInt();
			int columns = buf.readInt();
			// Everything that was written is read: stopping short would leave the rest in the buffer, and the
			// game takes a message with bytes left over for a broken one and closes the connection.
			List<Line> lines = new ArrayList<>();
			for (int i = buf.readInt(); i > 0; i--) {
				lines.add(new Line(buf.readUtf(), buf.readInt()));
			}
			List<Button> buttons = new ArrayList<>();
			for (int i = buf.readInt(); i > 0; i--) {
				buttons.add(new Button(buf.readUtf(), buf.readUtf(), buf.readUtf()));
			}
			String kind = buf.readUtf();
			int a = buf.readInt();
			List<List<String>> sides = new ArrayList<>();
			for (int side = 0; side < 2; side++) {
				List<String> things = new ArrayList<>();
				for (int i = buf.readInt(); i > 0; i--) {
					things.add(buf.readUtf());
				}
				sides.add(things);
			}
			return new Menu(title, color, columns, lines, buttons, kind, a, sides.get(0), sides.get(1));
		});

		@Override
		public Type<Menu> type() {
			return TYPE;
		}
	}

	private Net() {
	}

	/** Makes the messages known; once, when the mod loads, on both sides. */
	static void register() {
		PayloadTypeRegistry.clientboundPlay().register(State.TYPE, State.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(View.TYPE, View.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Choices.TYPE, Choices.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Menu.TYPE, Menu.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Act.TYPE, Act.CODEC);
	}
}
