package com.nunezvalen.capricraft.companion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.function.Predicate;

/** "Kill 3 zombies": the hunt, counted in kills instead of drops */
final class KillJob extends HuntJob {
	private final int wanted;
	private int kills = 0;

	KillJob(CapriEntity c, CompanionBrain brain, Predicate<Entity> prey, int wanted, String what) {
		super(c, brain, prey, s -> false, Integer.MAX_VALUE, what);
		this.wanted = wanted;
	}

	@Override
	boolean enough() {
		return kills >= wanted;
	}

	@Override
	void onKill(LivingEntity dead) {
		kills++;
	}

	@Override
	Result tick(ServerLevel level) {
		Result r = super.tick(level);
		if (r == Result.FAILED && kills > 0) {
			// Killed some, there are no more around: that's a done, not a failure
			failure = "only:" + kills;
		}
		return r;
	}

	@Override
	String step() {
		return "kill " + what;
	}
}
