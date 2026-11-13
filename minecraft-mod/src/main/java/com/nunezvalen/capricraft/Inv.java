package com.nunezvalen.capricraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/** Everything she does with the player's hands and inventory, the way the game itself would do it */
public final class Inv {
	private Inv() {}

	/** The 36 slots that hold items: 0-8 the hotbar, 9-35 the rest */
	public static final int MAIN_SLOTS = 36;

	public static int count(LocalPlayer player, Predicate<ItemStack> what) {
		Inventory inv = player.getInventory();
		int total = 0;
		for (int i = 0; i < MAIN_SLOTS; i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && what.test(stack)) total += stack.getCount();
		}
		return total;
	}

	public static int count(LocalPlayer player, Item item) {
		return count(player, s -> s.is(item));
	}

	/** Inventory index (0-35) of the first stack that matches, or -1 */
	public static int find(LocalPlayer player, Predicate<ItemStack> what) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < MAIN_SLOTS; i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && what.test(stack)) return i;
		}
		return -1;
	}

	/** The menu slot (in whatever is open) that shows inventory index `index`, or -1 */
	public static int menuSlotOf(LocalPlayer player, AbstractContainerMenu menu, int index) {
		for (Slot slot : menu.slots) {
			if (slot.container == player.getInventory() && slot.getContainerSlot() == index) return slot.index;
		}
		return -1;
	}

	public static void click(Minecraft mc, AbstractContainerMenu menu, int slot, int button, ContainerInput type) {
		mc.gameMode.handleContainerInput(menu.containerId, slot, button, type, mc.player);
	}

	/**
	 * Puts one matching item into each of the given menu slots: picks the stack up, drops one item
	 * per slot with a right click, and puts the rest back where it was. False if she runs out.
	 */
	public static boolean placeOneEach(Minecraft mc, AbstractContainerMenu menu, Predicate<ItemStack> what, int... targets) {
		LocalPlayer player = mc.player;
		int placed = 0;
		while (placed < targets.length) {
			int index = find(player, what);
			if (index < 0) return false;
			int from = menuSlotOf(player, menu, index);
			if (from < 0) return false;
			int available = player.getInventory().getItem(index).getCount();
			click(mc, menu, from, 0, ContainerInput.PICKUP);
			while (available > 0 && placed < targets.length) {
				click(mc, menu, targets[placed], 1, ContainerInput.PICKUP);
				available--;
				placed++;
			}
			// Whatever is left on the cursor goes back to its slot
			if (!menu.getCarried().isEmpty()) click(mc, menu, from, 0, ContainerInput.PICKUP);
		}
		return true;
	}

	/** Moves a whole matching stack into one menu slot (a furnace's input or fuel) */
	public static boolean placeStack(Minecraft mc, AbstractContainerMenu menu, Predicate<ItemStack> what, int target) {
		LocalPlayer player = mc.player;
		int index = find(player, what);
		if (index < 0) return false;
		int from = menuSlotOf(player, menu, index);
		if (from < 0) return false;
		click(mc, menu, from, 0, ContainerInput.PICKUP);
		click(mc, menu, target, 0, ContainerInput.PICKUP);
		if (!menu.getCarried().isEmpty()) click(mc, menu, from, 0, ContainerInput.PICKUP);
		return true;
	}

	/** Puts a matching item in her hand: straight from the hotbar, or swapped into its last slot */
	public static boolean hold(Minecraft mc, Predicate<ItemStack> what) {
		LocalPlayer player = mc.player;
		int index = find(player, what);
		if (index < 0) return false;
		if (index > 8) {
			// Only the plain inventory can swap into the hotbar
			if (player.containerMenu != player.inventoryMenu) player.closeContainer();
			int from = menuSlotOf(player, player.inventoryMenu, index);
			click(mc, player.inventoryMenu, from, 8, ContainerInput.SWAP);
			index = 8;
		}
		player.getInventory().setSelectedSlot(index);
		return true;
	}

	/** A spot within reach where a block can stand: empty, on solid ground, and not where the player is */
	public static BlockPos placeSpot(LocalPlayer player) {
		BlockPos feet = player.blockPosition();
		int[][] offsets = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 1}, {1, 2}, {-2, -1}, {-1, -2}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int dy : new int[] {0, -1, 1}) {
			for (int[] o : offsets) {
				BlockPos pos = feet.offset(o[0], dy, o[1]);
				BlockState here = player.level().getBlockState(pos);
				BlockPos below = pos.below();
				BlockState ground = player.level().getBlockState(below);
				if (here.canBeReplaced() && here.getFluidState().isEmpty() && ground.isFaceSturdy(player.level(), below, Direction.UP)) {
					if (!player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos))) return pos;
				}
			}
		}
		return null;
	}

	/** Places the held block at `pos`, clicking the top of the block under it */
	public static void placeAt(Minecraft mc, BlockPos pos) {
		BlockPos below = pos.below();
		Vec3 hit = new Vec3(below.getX() + 0.5, below.getY() + 1.0, below.getZ() + 0.5);
		mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, below, false));
	}

	/** Right-clicks a block (opens a crafting table or a furnace) */
	public static void use(Minecraft mc, BlockPos pos) {
		Vec3 hit = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
		mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, pos, false));
	}

	public static boolean within(LocalPlayer player, BlockPos pos, double reach) {
		return player.position().distanceToSqr(Vec3.atCenterOf(pos)) <= reach * reach;
	}

	/** 0 wooden or golden, 1 stone, 2 iron, 3 diamond, 4 netherite; -1 none */
	public static int bestPickaxe(LocalPlayer player) {
		int best = -1;
		for (int i = 0; i < MAIN_SLOTS; i++) {
			Item item = player.getInventory().getItem(i).getItem();
			int tier = item == Items.NETHERITE_PICKAXE ? 4
				: item == Items.DIAMOND_PICKAXE ? 3
				: item == Items.IRON_PICKAXE ? 2
				: item == Items.STONE_PICKAXE ? 1
				: (item == Items.WOODEN_PICKAXE || item == Items.GOLDEN_PICKAXE) ? 0
				: -1;
			best = Math.max(best, tier);
		}
		return best;
	}
}
