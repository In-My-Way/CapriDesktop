package com.nunezvalen.capricraft.companion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** "Come here": walks to you (or appears next to you when you're far) */
final class ComeJob extends Job {
	private int stuck = 0;

	ComeJob(CapriEntity c, CompanionBrain brain) {
		super(c, brain);
	}

	@Override
	Result tick(ServerLevel level) {
		ServerPlayer owner = c.owner();
		if (owner == null) return fail("no_player");
		if (owner.level() != c.level() || c.distanceTo(owner) > 48) {
			c.teleportNear(owner);
			return Result.DONE;
		}
		if (c.distanceTo(owner) < 3) {
			c.getNavigation().stop();
			return Result.DONE;
		}
		if (c.tickCount % 10 == 0) c.getNavigation().moveTo(owner, 1.3);
		if (++stuck > 400) {
			c.teleportNear(owner);
			return Result.DONE;
		}
		return Result.RUNNING;
	}

	@Override
	String step() {
		return "come";
	}
}
