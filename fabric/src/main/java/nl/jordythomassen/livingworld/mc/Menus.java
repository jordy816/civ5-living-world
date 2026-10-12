package nl.jordythomassen.livingworld.mc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;

/** Builds the interaction menus: a title, a few lines of live Civ data and some buttons. */
final class Menus {
	private final Actions actions;
	/** Says whether a player rules from above with panels of their own, which then show the page instead of Minecraft's dialog. */
	java.util.function.Predicate<ServerPlayer> panels = player -> false;

	Menus(Actions actions) {
		this.actions = actions;
	}

	final class Builder {
		private final ServerPlayer player;
		private final Component title;
		private final List<DialogBody> body = new ArrayList<>();
		private final List<ActionButton> buttons = new ArrayList<>();
		private final List<Net.Line> plainLines = new ArrayList<>();
		private final List<Net.Button> plainButtons = new ArrayList<>();
		private final int color;
		private int columns = 1;
		private String kind = "";
		private int kindOf;
		private List<String> left = List.of();
		private List<String> right = List.of();

		private Builder(ServerPlayer player, Component title, int color) {
			this.player = player;
			this.title = title;
			this.color = color;
		}

		/** Marks the page as one the panels of the view from above lay out in their own way (see {@link Net.Menu}). On foot it changes nothing. */
		Builder kind(String what, int of) {
			kind = what;
			kindOf = of;
			return this;
		}

		/** The two sides of a deal, a thing a line, for the panels of the view from above. */
		Builder table(List<String> gives, List<String> gets) {
			left = List.copyOf(gives);
			right = List.copyOf(gets);
			return this;
		}

		Builder columns(int count) {
			columns = Math.max(1, count);
			return this;
		}

		Builder line(String text) {
			body.add(new PlainMessage(Component.literal(text), 300));
			plainLines.add(new Net.Line(text, 0));
			return this;
		}

		Builder line(Component text) {
			body.add(new PlainMessage(text, 300));
			var tint = text.getStyle().getColor();
			plainLines.add(new Net.Line(text.getString(), tint == null ? 0 : 0xFF000000 | tint.getValue()));
			return this;
		}

		Builder button(String label, String tooltip, Consumer<ServerPlayer> action) {
			String token = actions.register(player, action);
			plainButtons.add(new Net.Button(label, tooltip == null ? "" : tooltip, token));
			buttons.add(new ActionButton(new CommonButtonData(Component.literal(label), Optional.ofNullable(tooltip).map(Component::literal), columns > 1 ? 200 : 260),
				Optional.of(new StaticAction(new ClickEvent.RunCommand("/civ act " + token)))));
			return this;
		}

		void open() {
			if (panels.test(player)) {
				net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, new Net.Menu(title.getString(), color, columns, plainLines, plainButtons, kind, kindOf, left, right));
				return;
			}
			ActionButton leave = new ActionButton(new CommonButtonData(Component.literal("Leave"), 260), Optional.empty());
			if (buttons.isEmpty()) {
				buttons.add(leave);
			}
			// Not paused: Civilization V keeps running while a menu is open.
			CommonDialogData common = new CommonDialogData(title, Optional.empty(), true, false, DialogAction.CLOSE, body, List.of());
			player.openDialog(Holder.direct(new MultiActionDialog(common, buttons, Optional.of(leave), columns)));
		}
	}

	Builder menu(ServerPlayer player, String title, int color) {
		MutableComponent text = Component.literal(title).withColor(Summon.readable(color));
		return new Builder(player, text, color);
	}
}
