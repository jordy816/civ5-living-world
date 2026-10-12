package nl.jordythomassen.livingworld.mc.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import nl.jordythomassen.livingworld.mc.LivingWorldMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Civilization V's tech tree is Minecraft's progression in a Living World: after the game has
 * worked out what a crafting grid makes, the result is taken away again when the player's
 * civilization has not yet discovered what it takes (no iron tools before Iron Working).
 * Outside a Living World nothing is changed.
 */
@Mixin(CraftingMenu.class)
public abstract class CraftingMenuMixin {
	@Inject(method = "slotChangedCraftingGrid", at = @At("TAIL"))
	private static void livingworld$followTheTechTree(AbstractContainerMenu menu, ServerLevel level, Player player, CraftingContainer craftSlots,
		ResultContainer resultSlots, RecipeHolder<CraftingRecipe> recipe, CallbackInfo info) {
		ItemStack result = resultSlots.getItem(0);
		String missing = LivingWorldMod.missingTech(result);
		if (missing == null || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		resultSlots.setItem(0, ItemStack.EMPTY);
		menu.setRemoteSlot(0, ItemStack.EMPTY);
		serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(menu.containerId, menu.incrementStateId(), 0, ItemStack.EMPTY));
		serverPlayer.sendOverlayMessage(Component.literal("Your civilization has not discovered " + missing + " yet"));
	}
}
