package com.nunezvalen.capricraft.companion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Smelts or cooks n items: puts her furnace down, feeds it, waits by it (5 seconds an item), takes it all back */
final class SmeltJob extends Job {
	private final Predicate<ItemStack> input;
	private final int n;
	private final String name;
	private BlockPos furnace = null;
	private boolean placed = false;
	private final List<ItemStack> cooking = new ArrayList<>();
	private int timer = 0;

	SmeltJob(CapriEntity c, CompanionBrain brain, Predicate<ItemStack> input, int n, Predicate<ItemStack> output, String name) {
		super(c, brain);
		this.input = input;
		this.n = n;
		this.name = name;
	}

	@Override
	Result tick(ServerLevel level) {
		if (furnace == null || !level.getBlockState(furnace).is(Blocks.FURNACE)) {
			if (!cooking.isEmpty()) {
				// Someone broke the furnace: she gets her things back and the planner starts over
				cooking.forEach(c::give);
				cooking.clear();
				return Result.DONE;
			}
			furnace = brain.findNear(level, Blocks.FURNACE, 8);
			if (furnace == null) {
				furnace = brain.placeNear(level, Items.FURNACE, Blocks.FURNACE.defaultBlockState());
				if (furnace == null) return fail("no_room_for:furnace");
				placed = true;
			}
		}
		int w = brain.walkTo(furnace, 3.5);
		if (w == -1) return fail("cant_reach:furnace");
		if (w == 0) return Result.RUNNING;
		c.getLookControl().setLookAt(net.minecraft.world.phys.Vec3.atCenterOf(furnace));

		if (cooking.isEmpty()) {
			int k = Math.min(n, c.count(input));
			if (k <= 0) return finish(level, Result.DONE);
			// Fuel first: coal (8 items each) or wood (1.5)
			int coal = Math.min(c.count(Planner.FUEL_COAL), (k + 7) / 8);
			int stillToFuel = Math.max(0, k - coal * 8);
			int wood = (int) Math.ceil(stillToFuel / 1.5);
			if (c.count(Planner.FUEL_WOOD) < wood) return finish(level, Result.DONE);
			c.take(Planner.FUEL_COAL, coal);
			c.take(Planner.FUEL_WOOD, wood);
			for (int i = 0; i < c.getInventory().getContainerSize() && cooking.stream().mapToInt(ItemStack::getCount).sum() < k; i++) {
				ItemStack s = c.getInventory().getItem(i);
				if (s.isEmpty() || !input.test(s)) continue;
				int take = Math.min(s.getCount(), k - cooking.stream().mapToInt(ItemStack::getCount).sum());
				cooking.add(s.split(take));
			}
			c.getInventory().setChanged();
			light(level, true);
			timer = 0;
		}
		int total = cooking.stream().mapToInt(ItemStack::getCount).sum();
		if (++timer < total * 100) return Result.RUNNING;
		for (ItemStack raw : cooking) {
			Optional<RecipeHolder<SmeltingRecipe>> recipe = level.recipeAccess().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(raw.copyWithCount(1)), level);
			if (recipe.isEmpty()) {
				c.give(raw);
				continue;
			}
			ItemStack out = recipe.get().value().assemble(new SingleRecipeInput(raw.copyWithCount(1)));
			c.give(out.copyWithCount(out.getCount() * raw.getCount()));
		}
		cooking.clear();
		Companion.event("smelted", name);
		return finish(level, Result.DONE);
	}

	private void light(ServerLevel level, boolean lit) {
		BlockState st = level.getBlockState(furnace);
		if (st.hasProperty(AbstractFurnaceBlock.LIT)) level.setBlock(furnace, st.setValue(AbstractFurnaceBlock.LIT, lit), 3);
	}

	private Result finish(ServerLevel level, Result r) {
		if (furnace != null && level.getBlockState(furnace).is(Blocks.FURNACE)) light(level, false);
		if (placed) brain.pickUpBlock(level, furnace, Blocks.FURNACE);
		placed = false;
		return r;
	}

	@Override
	void abort() {
		super.abort();
		cooking.forEach(c::give);
		cooking.clear();
		if (c.level() instanceof ServerLevel sl && furnace != null) finish(sl, Result.DONE);
	}

	@Override
	String step() {
		return "smelt " + name;
	}
}
