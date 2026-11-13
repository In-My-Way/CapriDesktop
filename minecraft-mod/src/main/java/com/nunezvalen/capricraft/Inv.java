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

	/** One menu slot to fill: with one item (right click) or a whole stack (left click) */
	public record Fill(int slot, Predicate<ItemStack> what, boolean wholeStack) {}

	/** The next click toward filling the slots: a slot and a mouse button */
	public record Move(int slot, int button) {}

	public static final Move DONE = new Move(-1, -1);
	public static final Move MISSING = new Move(-2, -2);

	/**
	 * Looks at the menu as it is now and picks the one click that gets the slots closer to filled,
	 * the way a person does it: pick a stack up, drop one in each slot that wants it, put the rest
	 * back. DONE when every slot has something, MISSING when she has nothing left for one.
	 */
	public static Move nextFill(LocalPlayer player, AbstractContainerMenu menu, java.util.List<Fill> fills) {
		ItemStack carried = menu.getCarried();
		if (!carried.isEmpty()) {
			for (Fill f : fills) {
				if (!menu.getSlot(f.slot()).hasItem() && f.what().test(carried)) return new Move(f.slot(), f.wholeStack() ? 0 : 1);
			}
			// Nothing else wants it: back into the inventory
			int home = emptyOrSame(player, menu, carried);
			return home >= 0 ? new Move(home, 0) : DONE;
		}
		for (Fill f : fills) {
			if (menu.getSlot(f.slot()).hasItem()) continue;
			int index = find(player, f.what());
			if (index < 0) return MISSING;
			int from = menuSlotOf(player, menu, index);
			return from < 0 ? MISSING : new Move(from, 0);
		}
		return DONE;
	}

	/** An inventory slot in the menu where the carried stack can go back: one like it, or an empty one */
	private static int emptyOrSame(LocalPlayer player, AbstractContainerMenu menu, ItemStack carried) {
		int empty = -1;
		for (Slot slot : menu.slots) {
			if (slot.container != player.getInventory() || slot.getContainerSlot() >= MAIN_SLOTS) continue;
			ItemStack here = slot.getItem();
			if (here.isEmpty()) {
				if (empty < 0) empty = slot.index;
			} else if (ItemStack.isSameItemSameComponents(here, carried) && here.getCount() < here.getMaxStackSize()) {
				return slot.index;
			}
		}
		return empty;
	}

	/**
	 * Turns her head a step toward a point, the way a person looks at something: true once she's
	 * looking at it.
	 */
	public static boolean lookTowards(LocalPlayer player, Vec3 at, float maxStep) {
		Vec3 eyes = player.getEyePosition();
		double dx = at.x - eyes.x, dy = at.y - eyes.y, dz = at.z - eyes.z;
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		float newYaw = net.minecraft.util.Mth.approachDegrees(player.getYRot(), yaw, maxStep);
		float newPitch = net.minecraft.util.Mth.approachDegrees(player.getXRot(), pitch, maxStep);
		player.setYRot(newYaw);
		player.setXRot(newPitch);
		return Math.abs(net.minecraft.util.Mth.wrapDegrees(newYaw - yaw)) < 1f && Math.abs(newPitch - pitch) < 1f;
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
