package nl.jordythomassen.livingworld.mc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.Locale;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import nl.jordythomassen.livingworld.coords.PlotCoord;
import nl.jordythomassen.livingworld.state.CityState;
import nl.jordythomassen.livingworld.state.WorldState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LivingWorldMod implements ModInitializer {
	public static final String MOD_ID = "livingworld";
	/** The tag every figure this mod puts in the world carries (see {@link Summon#TAG}). */
	public static final String FIGURE_TAG = Summon.TAG;
	public static final String WORLD_NAME = "Civ V Living World";
	public static final Logger LOG = LoggerFactory.getLogger("LivingWorld");
	private static final Identifier BOARD = Identifier.fromNamespaceAndPath(MOD_ID, "board");

	private static LivingWorldServer session;

	public static ModConfig config() {
		return ModConfig.load(FabricLoader.getInstance().getGameDir());
	}

	/** The game being played in this Minecraft right now, or null. */
	static LivingWorldServer session() {
		return session;
	}

	@Override
	public void onInitialize() {
		Net.register();
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(Net.Act.TYPE, (act, context) -> context.server().execute(() -> {
			if (session != null) {
				try {
					session.as(context.player(), () -> session.rts.act(context.player(), act));
				} catch (RuntimeException e) {
					LOG.error("Living World: could not carry out '{}'", act.verb(), e);
				}
			}
		}));
		ServerLifecycleEvents.SERVER_STARTED.register(LivingWorldMod::started);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (session != null) {
				session.stop();
				session = null;
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (session != null) {
				try {
					session.tick();
				} catch (RuntimeException e) {
					// A fault here must never take the world down with it.
					LOG.error("Living World: tick failed", e);
				}
			}
		});
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
			if (session != null && level == session.server.overworld()) {
				session.painter.markChunk(chunk.getPos().x(), chunk.getPos().z());
			}
		});
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			// Characters are never meant to be saved: one that comes back with a chunk is a leftover.
			if (session != null && entity.entityTags().contains(Summon.TAG) && !session.proxies.isOurs(entity.getUUID())) {
				level.getServer().execute(entity::discard);
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (session != null) {
				session.as(handler.getPlayer(), () -> session.joined(handler.getPlayer()));
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (session != null) {
				session.left(handler.getPlayer());
			}
		});
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (session == null || level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			if (!session.proxies.isOurs(entity.getUUID())) {
				return InteractionResult.PASS;
			}
			if (hand == InteractionHand.MAIN_HAND) {
				session.as(serverPlayer, () -> session.interact(serverPlayer, entity));
			}
			return InteractionResult.SUCCESS;
		});
		// The Codex in hand, and the bell and the lectern in the player's own halls: no command needed for anything.
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (session == null || level.isClientSide() || !(player instanceof ServerPlayer serverPlayer) || level != session.server.overworld()) {
				return InteractionResult.PASS;
			}
			if (session.asking(serverPlayer, () -> session.useBlock(serverPlayer, hand, hit.getBlockPos()))) {
				return InteractionResult.SUCCESS;
			}
			// Setting a block down, pouring water, lighting a fire, tilling: on one's own land only.
			if (Hands.changesTheLand(player.getItemInHand(hand))
				&& (!session.handsFree(serverPlayer, hit.getBlockPos()) || !session.handsFree(serverPlayer, hit.getBlockPos().relative(hit.getDirection())))) {
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});
		// Breaking a block likewise: hands work their own land, and leave cities and wonders standing.
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
			session == null || !(player instanceof ServerPlayer serverPlayer) || level != session.server.overworld() || session.handsFree(serverPlayer, pos));
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (session == null || level.isClientSide() || !(player instanceof ServerPlayer serverPlayer) || level != session.server.overworld()) {
				return InteractionResult.PASS;
			}
			return session.asking(serverPlayer, () -> session.useItem(serverPlayer, hand)) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		});
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (session != null && player instanceof ServerPlayer serverPlayer && level == session.server.overworld()) {
				session.as(serverPlayer, () -> session.blockBroken(serverPlayer, pos, state));
			}
		});
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
			session != null && session.proxies.isOurs(entity.getUUID()) ? InteractionResult.FAIL : InteractionResult.PASS);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(Commands.literal("civ")
			.executes(c -> help(c))
			.then(Commands.literal("status").executes(c -> reply(c, session == null ? "Living World is not active in this world." : session.describe())))
			.then(Commands.literal("home").executes(c -> {
				if (active(c)) {
					session.sendHome(c.getSource().getPlayerOrException());
				}
				return 1;
			}))
			.then(Commands.literal("goto")
				.then(Commands.argument("x", IntegerArgumentType.integer(0)).then(Commands.argument("y", IntegerArgumentType.integer(0)).executes(c -> {
					if (active(c)) {
						PlotCoord plot = session.state().session().grid().normalize(new PlotCoord(IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y")));
						if (plot == null) {
							return reply(c, "That plot is off the map.");
						}
						session.teleport(c.getSource().getPlayerOrException(), plot);
					}
					return 1;
				})))
				.then(Commands.argument("city", StringArgumentType.greedyString()).executes(c -> {
					if (active(c)) {
						String wanted = StringArgumentType.getString(c, "city").toLowerCase(Locale.ROOT);
						for (CityState city : session.state().cities().values()) {
							if (city.name().toLowerCase(Locale.ROOT).startsWith(wanted)) {
								session.teleport(c.getSource().getPlayerOrException(), city.at());
								return 1;
							}
						}
						return reply(c, "No city in sight is called that.");
					}
					return 1;
				})))
			.then(Commands.literal("endturn").executes(c -> {
				if (active(c)) {
					ServerPlayer who = c.getSource().getPlayerOrException();
					session.as(who, () -> session.endTurn(who));
				}
				return 1;
			}))
			.then(Commands.literal("work").executes(c -> {
				if (active(c)) {
					ServerPlayer who = c.getSource().getPlayerOrException();
					session.as(who, () -> session.gameplay.workMenu(who));
				}
				return 1;
			}))
			.then(Commands.literal("research").executes(c -> {
				if (active(c)) {
					ServerPlayer who = c.getSource().getPlayerOrException();
					session.as(who, () -> session.gameplay.researchMenu(who));
				}
				return 1;
			}))
			.then(Commands.literal("follow")
				.then(Commands.literal("on").executes(c -> follow(c, true)))
				.then(Commands.literal("off").executes(c -> follow(c, false))))
			.then(Commands.literal("repaint").executes(c -> {
				if (active(c)) {
					ServerPlayer player = c.getSource().getPlayerOrException();
					// Puts everything nearby back as Civilization V has it, including what players built or dug there.
					session.painter.forgetAround(player.chunkPosition().x(), player.chunkPosition().z(), 3);
					return reply(c, "Putting the land around you back as Civilization V has it.");
				}
				return 1;
			}))
			.then(Commands.literal("turn").executes(c -> {
				if (session == null || session.mock() == null) {
					return reply(c, "Turns are played in Civilization V. (This command only exists for the built-in mock game.)");
				}
				synchronized (session.mock()) {
					session.mock().endTurn();
				}
				return 1;
			}))
			.then(Commands.literal("act").then(Commands.argument("token", StringArgumentType.word()).executes(c -> {
				ServerPlayer player = c.getSource().getPlayerOrException();
				if (session == null || !session.asking(player, () -> session.actions.run(player, StringArgumentType.getString(c, "token")))) {
					player.sendSystemMessage(Component.literal("That choice is no longer available."));
				}
				return 1;
			})))));
		LOG.info("Living World: loaded");
	}

	private static void started(MinecraftServer server) {
		boolean board = server.overworld().dimensionTypeRegistration().unwrapKey().map(key -> key.identifier().equals(BOARD)).orElse(false);
		if (!board) {
			LOG.info("Living World: this world is not a Living World board; doing nothing here");
			return;
		}
		session = new LivingWorldServer(server, config());
		session.start();
	}

	private static boolean active(CommandContext<CommandSourceStack> c) {
		if (session == null || session.state() == null || session.model() == null) {
			reply(c, session == null ? "Living World is not active in this world." : "No Civilization V game is being shown yet.");
			return false;
		}
		return true;
	}

	private static int follow(CommandContext<CommandSourceStack> c, boolean on) {
		if (session == null) {
			return reply(c, "Living World is not active in this world.");
		}
		session.setRule("follow", on);
		return reply(c, on ? "Civilization V's camera now follows you." : "Civilization V's camera stays where you leave it.");
	}

	/** Nothing needs typing: "/civ" by itself hands out the Codex if it was lost and opens it. */
	private static int help(CommandContext<CommandSourceStack> c) {
		ServerPlayer player = c.getSource().getPlayer();
		if (session == null || player == null) {
			return reply(c, "Living World is not active in this world.");
		}
		session.codex.give(player);
		session.codex.open(player);
		return 1;
	}

	private static int reply(CommandContext<CommandSourceStack> c, String text) {
		c.getSource().sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	/**
	 * For the crafting hook: the Civ technology still needed to craft this item ("Iron Working"),
	 * or null when it may be crafted.
	 */
	public static String missingTech(net.minecraft.world.item.ItemStack stack) {
		if (session == null || stack.isEmpty()) {
			return null;
		}
		String missing = session.missingTech(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		return missing == null ? null : Gameplay.words(missing);
	}

	static WorldState shownState() {
		return session == null ? null : session.state();
	}
}
