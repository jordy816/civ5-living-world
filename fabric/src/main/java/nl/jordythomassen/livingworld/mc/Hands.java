package nl.jordythomassen.livingworld.mc;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What a ruler's own hands are good for, down on the land. They work their own land only: what
 * its forests, fields and hills yield (it grows back every turn) can be carried to one of their
 * cities, where wood and ore become production, the harvest food, and gold and gems gold. A
 * city takes from hands no more in a turn than it makes itself, so walking helps a city along
 * and never does its work for it. (Bread is kept out of it: bread is what heals a unit.)
 */
public final class Hands {
	public static final int PRODUCTION = 0;
	public static final int FOOD = 1;
	public static final int GOLD = 2;
	/** What the game is told a load is, by its kind. */
	public static final String[] ITEM = { "", "food", "gold" };
	public static final String[] MEASURE = { "Production", "Food", "Gold" };

	/** Something that can be carried to a city: what it is called, how it is known, what one of it is worth there, and as what. */
	public record Good(String name, Predicate<ItemStack> is, int worth, int kind) {
	}

	public static final List<Good> GOODS = List.of(
		new Good("Logs", stack -> stack.is(ItemTags.LOGS), LivingWorldServer.HAMMERS_PER_LOG, PRODUCTION),
		new Good("Raw iron", stack -> stack.is(Items.RAW_IRON), 3, PRODUCTION),
		new Good("Raw copper", stack -> stack.is(Items.RAW_COPPER), 2, PRODUCTION),
		new Good("Coal", stack -> stack.is(Items.COAL), 2, PRODUCTION),
		new Good("Pumpkin", stack -> stack.is(Items.PUMPKIN), 2, FOOD),
		new Good("Wheat", stack -> stack.is(Items.WHEAT), 1, FOOD),
		new Good("Melon", stack -> stack.is(Items.MELON_SLICE), 1, FOOD),
		new Good("Berries", stack -> stack.is(Items.SWEET_BERRIES), 1, FOOD),
		new Good("Apple", stack -> stack.is(Items.APPLE), 1, FOOD),
		new Good("Fish", stack -> stack.is(Items.COD) || stack.is(Items.SALMON), 2, FOOD),
		new Good("Emerald", stack -> stack.is(Items.EMERALD), 6, GOLD),
		new Good("Raw gold", stack -> stack.is(Items.RAW_GOLD), 4, GOLD),
		new Good("Gold", stack -> stack.is(Items.GOLD_INGOT), 4, GOLD));

	private Hands() {
	}

	/** What this is worth to a city, or null when a city has no use for it. */
	public static Good of(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		for (Good good : GOODS) {
			if (good.is().test(stack)) {
				return good;
			}
		}
		return null;
	}

	/** Whether using this on the land changes the land: a block to set down, water or fire to let loose, a tool that works the ground. */
	public static boolean changesTheLand(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		return stack.getItem() instanceof net.minecraft.world.item.BlockItem || stack.getItem() instanceof net.minecraft.world.item.BucketItem
			|| stack.getItem() instanceof net.minecraft.world.item.FlintAndSteelItem || stack.getItem() instanceof net.minecraft.world.item.FireChargeItem
			|| stack.getItem() instanceof net.minecraft.world.item.BoneMealItem || stack.is(ItemTags.HOES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.AXES);
	}
}
