package com.nunezvalen.capricraft.companion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Hunts animals (or monsters) until she has enough of what they drop: leather, meat, string.
 * KillJob uses the same chase to kill a number of something you named.
 */
class HuntJob extends Job {
	final Predicate<Entity> prey;
	private final Predicate<ItemStack> yields;
	private final int goal;
	final String what;
	LivingEntity target = null;
	private BlockPos wanderTo = null;
	private int wanders = 0;
	private int lingering = 0;
	private int chasing = 0;
	private final java.util.Set<Integer> skipped = new java.util.HashSet<>();

	HuntJob(CapriEntity c, CompanionBrain brain, Predicate<Entity> prey, Predicate<ItemStack> yields, int goal, String what) {
		super(c, brain);
		this.prey = prey;
		this.yields = yields;
		this.goal = goal;
		this.what = what;
	}

	boolean enough() {
		return c.count(yields) >= goal;
	}

	@Override
	Result tick(ServerLevel level) {
		if (enough()) {
			if (lingering++ < 40 && brain.collectDrops(level, 6)) return Result.RUNNING;
			return Result.DONE;
		}
		if (target == null && brain.collectDrops(level, 7)) return Result.RUNNING;
		if (target != null && (!target.isAlive() || target.level() != c.level() || c.distanceTo(target) > 40)) {
			if (!target.isAlive()) onKill(target);
			target = null;
			brain.huntTarget = null;
		}
		if (target == null) {
			List<LivingEntity> found = level.getEntitiesOfClass(LivingEntity.class, c.around(32),
				e -> e.isAlive() && e != c && prey.test(e) && !skipped.contains(e.getId()) && !brain.isOwnerOrPet(e) && !(e instanceof AgeableMob a && a.isBaby()) && !e.hasCustomName());
			found.sort(Comparator.comparingDouble(c::distanceToSqr));
			if (!found.isEmpty()) {
				target = found.getFirst();
				brain.huntTarget = target;
				wanderTo = null;
			}
		}
		if (target == null) return wander(level);
		// Can't get to this one (across a river, up a cliff): another
		if (++chasing > 400) {
			skipped.add(target.getId());
			target = null;
			brain.huntTarget = null;
			chasing = 0;
			return Result.RUNNING;
		}
		float before = target.getHealth();
		brain.fight(level, target);
		if (target.getHealth() < before) chasing = 0;
		return Result.RUNNING;
	}

	void onKill(LivingEntity dead) {}

	private Result wander(ServerLevel level) {
		BlockPos feet = c.blockPosition();
		if (wanderTo == null) {
			if (++wanders > 12) return fail("could_not_find:" + what);
			double angle = c.getRandom().nextDouble() * Math.PI * 2;
			int x = feet.getX() + (int) (Math.cos(angle) * 30), z = feet.getZ() + (int) (Math.sin(angle) * 30);
			wanderTo = new BlockPos(x, c.surfaceY(x, z), z);
		}
		if (brain.walkTo(wanderTo, 3) != 0) wanderTo = null;
		return Result.RUNNING;
	}

	@Override
	void pause() {
		super.pause();
		brain.huntTarget = null;
		target = null;
	}

	@Override
	String step() {
		return "hunt " + what;
	}
}
