package com.nunezvalen.capricraft.companion;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/** "Mine 20 sand": first the tool it needs, then that many blocks, then (if asked) to you */
final class MineTask extends Job {
	private final Block block;
	private final int count;
	private final boolean give;
	private final String name;
	private Job current = null;
	private GatherJob gather = null;
	private GiveJob giving = null;
	private int remainingAfterTool = -1;

	MineTask(CapriEntity c, CompanionBrain brain, Block block, int count, boolean give) {
		super(c, brain);
		this.block = block;
		this.count = count;
		this.give = give;
		this.name = BuiltInRegistries.BLOCK.getKey(block).getPath();
	}

	@Override
	Result tick(ServerLevel level) {
		if (giving != null) {
			Result r = giving.tick(level);
			if (r == Result.FAILED) failure = giving.failure;
			return r;
		}
		if (gather == null) {
			if (current == null) {
				Item pickaxe = Planner.pickaxeFor(block.defaultBlockState());
				if (pickaxe != null) {
					try {
						current = Planner.next(c, brain, level, Planner.durable(pickaxe), 1);
					} catch (Planner.PlanException e) {
						return fail(e.getMessage());
					}
				}
				if (current == null) {
					int depth = pickaxe != null ? Planner.BELOW_HERE : Planner.SURFACE;
					gather = new GatherJob(c, brain, s -> s.is(block), s -> false, Integer.MAX_VALUE, depth, name).needs(pickaxe);
					gather.breaksGoal = remainingAfterTool > 0 ? remainingAfterTool : count;
					return Result.RUNNING;
				}
			}
			Result r = current.tick(level);
			if (r == Result.FAILED) return fail(current.failure);
			if (r == Result.DONE) current = null;
			return Result.RUNNING;
		}
		Result r = gather.tick(level);
		if (r == Result.FAILED) return fail(gather.failure);
		Item needed = Planner.pickaxeFor(block.defaultBlockState());
		if (r == Result.DONE && needed != null && !Planner.hasToolFor(c, needed) && gather.brokenSoFar() < count) {
			// The pickaxe broke halfway: a new one, then on with the same count
			current = null;
			remainingAfterTool = count - gather.brokenSoFar();
			gather = null;
			return Result.RUNNING;
		}
		if (r == Result.DONE) {
			if (!give) return Result.DONE;
			Item item = block.asItem();
			giving = new GiveJob(c, brain, s -> s.is(item) || s.is(net.minecraft.world.item.Items.COBBLESTONE), count, name);
		}
		return Result.RUNNING;
	}

	@Override
	String step() {
		if (giving != null) return giving.step();
		if (gather != null) return gather.step();
		return current != null ? current.step() : "mine " + name;
	}

	@Override
	void pause() {
		super.pause();
		if (current != null) current.pause();
	}

	@Override
	void abort() {
		super.pause();
		if (current != null) current.abort();
	}
}
