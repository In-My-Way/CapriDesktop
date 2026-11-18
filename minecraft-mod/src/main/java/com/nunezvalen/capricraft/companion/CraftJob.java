package com.nunezvalen.capricraft.companion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;

import java.util.Map;

/** Crafts a recipe `times` times. 3x3 recipes need a crafting table: she puts hers down and takes it back after */
final class CraftJob extends Job {
	private final RecipeHolder<?> holder;
	private final int times;
	private final boolean table;
	private final String name;
	private BlockPos tablePos = null;
	private boolean placedTable = false;
	private int working = 0;

	CraftJob(CapriEntity c, CompanionBrain brain, RecipeHolder<?> holder, int times, boolean table, String name) {
		super(c, brain);
		this.holder = holder;
		this.times = times;
		this.table = table;
		this.name = name;
	}

	@Override
	Result tick(ServerLevel level) {
		if (table) {
			if (tablePos == null || !level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)) {
				tablePos = brain.findNear(level, Blocks.CRAFTING_TABLE, 8);
				if (tablePos == null) {
					tablePos = brain.placeNear(level, Items.CRAFTING_TABLE, Blocks.CRAFTING_TABLE.defaultBlockState());
					if (tablePos == null) return fail("no_room_for:crafting_table");
					placedTable = true;
					com.nunezvalen.capricraft.companion.Companion.event("placed", "crafting_table");
				}
			}
			int w = brain.walkTo(tablePos, 3.5);
			if (w == -1) {
				tablePos = null;
				return fail("cant_reach:crafting_table");
			}
			if (w == 0) return Result.RUNNING;
			c.getLookControl().setLookAt(net.minecraft.world.phys.Vec3.atCenterOf(tablePos));
		}
		if (++working % 6 == 1) c.swingForAttack(InteractionHand.MAIN_HAND);
		if (working < 24) return Result.RUNNING;

		Recipe<?> recipe = holder.value();
		Map<Ingredient, Integer> needs = Planner.ingredientCounts(recipe);
		int possible = times;
		for (Map.Entry<Ingredient, Integer> e : needs.entrySet()) possible = Math.min(possible, c.count(e.getKey()) / e.getValue());
		if (possible <= 0) {
			finish(level);
			return Result.DONE; // something was used up meanwhile: the planner will see what's missing
		}
		for (Map.Entry<Ingredient, Integer> e : needs.entrySet()) c.take(e.getKey(), e.getValue() * possible);
		ItemStack one = Planner.result(recipe);
		int total = one.getCount() * possible;
		while (total > 0) {
			int k = Math.min(total, one.getMaxStackSize());
			c.give(one.copyWithCount(k));
			total -= k;
		}
		Companion.event("crafted", name + (possible * one.getCount() > 1 ? " x" + possible * one.getCount() : ""));
		finish(level);
		return Result.DONE;
	}

	private void finish(ServerLevel level) {
		if (placedTable) brain.pickUpBlock(level, tablePos, Blocks.CRAFTING_TABLE);
		placedTable = false;
	}

	@Override
	void pause() {
		super.pause();
		working = 0;
	}

	@Override
	void abort() {
		pause();
		if (c.level() instanceof ServerLevel sl) finish(sl);
	}

	@Override
	String step() {
		return "craft " + name;
	}
}
