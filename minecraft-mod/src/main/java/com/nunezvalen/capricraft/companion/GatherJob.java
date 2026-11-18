package com.nunezvalen.capricraft.companion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Gets blocks of some kind until she has enough of what they drop: trees, stone, ore, sand.
 * She only goes for blocks she can see. When there are none in sight she explores: walks around on the
 * surface for trees and sand, or digs a staircase down to the right depth and a tunnel along it for
 * ore, checking the walls as she goes, like a player strip-mining.
 */
final class GatherJob extends Job {
	private final Predicate<BlockState> blocks;
	private final Predicate<ItemStack> yields;
	private final int goal;
	private final int depth;
	private final String what;
	/** Count broken blocks instead of items (for "mine 20 stone", which drops cobblestone) */
	int breaksGoal = -1;
	private int breaks = 0;

	private BlockPos target = null;
	private final Set<BlockPos> skip = new HashSet<>();
	private int scanCooldown = 0;
	private int approachFails = 0;
	private int lingering = 0;
	private boolean seen = false;
	private boolean relocating = false;
	private int relocations = 0;
	private boolean walked = false;
	/** Walking to the target didn't work: digging straight to it */
	private boolean digging = false;

	// Exploring
	private BlockPos wanderTo = null;
	private int wanders = 0;
	private int tunnelled = 0;
	private int turns = 0;
	private int targetY = Integer.MIN_VALUE;

	GatherJob(CapriEntity c, CompanionBrain brain, Predicate<BlockState> blocks, Predicate<ItemStack> yields, int goal, int depth, String what) {
		super(c, brain);
		this.blocks = blocks;
		this.yields = yields;
		this.goal = goal;
		this.depth = depth;
		this.what = what;
	}

	private Item tool = null;

	/** The tool it takes (a pickaxe for ore): without it, or with it about to break, she goes to make one */
	GatherJob needs(Item tool) {
		this.tool = tool;
		return this;
	}

	int brokenSoFar() {
		return breaks;
	}

	private boolean enough() {
		return breaksGoal >= 0 ? breaks >= breaksGoal : c.count(yields) >= goal;
	}

	@Override
	Result tick(ServerLevel level) {
		if (enough()) {
			// A moment to pick up what's still on the ground
			if (lingering++ < 40 && brain.collectDrops(level, 6)) return Result.RUNNING;
			return Result.DONE;
		}
		if (brain.collectDrops(level, 5)) return Result.RUNNING;
		if (tool != null && !Planner.hasToolFor(c, tool)) {
			brain.stopDigging();
			return Result.DONE; // the planner sees the missing tool and makes it
		}

		if (target != null && !blocks.test(level.getBlockState(target))) target = null;
		if (target == null) digging = false;
		if (target == null && --scanCooldown <= 0) {
			scanCooldown = 10;
			target = brain.findVisible(level, blocks, 14, skip);
			approachFails = 0;
			if (target != null) {
				wanderTo = null;
				if (!seen) Companion.event("seen", what);
				seen = true;
			}
		}
		if (target != null) return work(level);
		return explore(level);
	}

	private Result work(ServerLevel level) {
		brain.debug = "target " + target.toShortString() + " reach=" + brain.inReach(target) + " see=" + brain.canSee(level, target, blocks) + " fails=" + approachFails;
		if (brain.inReach(target) && brain.canSee(level, target, blocks)) {
			brain.stopWalking();
			if (brain.dig(level, target)) {
				breaks++;
				target = null;
				scanCooldown = 0;
			} else if (brain.lastUnbreakable) {
				skip.add(target);
				target = null;
			}
			return Result.RUNNING;
		}
		int w = digging ? -1 : brain.walkTo(target, 3.5);
		if (w == 1 && !brain.canSee(level, target, blocks)) w = -1;
		if (w == -1) {
			// No way to walk there: if it's underground she digs her way to it, otherwise she gives up on that one
			boolean buried = target.getY() < c.surfaceY(target.getX(), target.getZ()) - 2;
			if (buried && depth != Planner.SURFACE) {
				digging = true;
				int t = brain.tunnelToward(level, target);
				brain.debug += " tunnel=" + t + " " + brain.tunnelProblem;
				if (t == -1 && ++approachFails > 3) {
					skip.add(target);
					target = null;
					digging = false;
				}
				return Result.RUNNING;
			}
			skip.add(target);
			target = null;
		}
		return Result.RUNNING;
	}

	private Result explore(ServerLevel level) {
		if (depth == Planner.SURFACE || relocating) {
			Result r = wander(level);
			if (wanderTo != null) walked = true;
			// One walk to somewhere new, then she digs again from there
			if (relocating && walked && wanderTo == null) {
				relocating = false;
				walked = false;
				targetY = Integer.MIN_VALUE;
			}
			return r;
		}
		if (c.isInWater() && brain.escapeWater(level)) return Result.RUNNING;
		BlockPos feet = c.blockPosition();
		if (targetY == Integer.MIN_VALUE) {
			targetY = depth == Planner.BELOW_HERE ? feet.getY() - 4 : Math.max(level.getMinY() + 6, depth);
			brain.turnTunnel();
		}
		Direction dir = brain.tunnelDir();
		int dy = feet.getY() - targetY;
		BlockPos goal = dy > 1
			? feet.relative(dir, dy + 2).atY(targetY)
			: feet.relative(dir, 32).atY(feet.getY());
		int t = brain.tunnelToward(level, goal);
		brain.debug = "explore goal " + goal.toShortString() + " t=" + t + " turns=" + turns + " problem=" + brain.tunnelProblem;
		if (t == 1) {
			tunnelled++;
			if (tunnelled % 10 == 0) turns = Math.max(0, turns - 1);
			scanCooldown = 0;
			if (tunnelled % 40 == 0) brain.turnTunnel();
			if (tunnelled > 220) return fail("could_not_find:" + what);
		} else if (t == -1) {
			brain.turnTunnel();
			if (++turns > 6) {
				// Water or lava everywhere here: somewhere else, a few times, before giving up
				if (++relocations > 4) return fail(brain.tunnelProblem.isEmpty() ? "could_not_find:" + what : "blocked:" + brain.tunnelProblem);
				turns = 0;
				relocating = true;
				wanderTo = null;
				wanders = 0;
				Companion.event("relocate", brain.tunnelProblem);
			}
		}
		return Result.RUNNING;
	}

	private Result wander(ServerLevel level) {
		BlockPos feet = c.blockPosition();
		// Underground (after mining): back up to the sky first
		int sky = c.surfaceY(feet.getX(), feet.getZ());
		if (feet.getY() < sky - 3) {
			BlockPos up = feet.relative(brain.tunnelDir(), 6).atY(sky + 1);
			int t = brain.tunnelToward(level, up);
			if (t == -1) brain.turnTunnel();
			return Result.RUNNING;
		}
		if (wanderTo == null) {
			if (++wanders > 14) return fail("could_not_find:" + what);
			// Somewhere dry she can walk to
			for (int attempt = 0; attempt < 12 && wanderTo == null; attempt++) {
				double angle = c.getRandom().nextDouble() * Math.PI * 2;
				int dist = 24 + c.getRandom().nextInt(16);
				int x = feet.getX() + (int) (Math.cos(angle) * dist), z = feet.getZ() + (int) (Math.sin(angle) * dist);
				BlockPos p = new BlockPos(x, c.surfaceY(x, z), z);
				if (!level.getFluidState(p.below()).isEmpty() || !level.getFluidState(p).isEmpty()) continue;
				if (c.getNavigation().createPath(p, 2) == null) continue;
				wanderTo = p;
			}
			if (wanderTo == null) return Result.RUNNING;
		}
		int w = brain.walkTo(wanderTo, 3);
		if (w != 0) {
			wanderTo = null;
			scanCooldown = 0;
		}
		return Result.RUNNING;
	}

	@Override
	String step() {
		return (target == null && depth != Planner.SURFACE && tunnelled > 0 ? "dig for " : "gather ") + what;
	}
}
