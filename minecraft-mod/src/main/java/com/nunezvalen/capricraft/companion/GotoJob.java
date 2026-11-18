package com.nunezvalen.capricraft.companion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Walks to coordinates, a stretch of up to 32 blocks at a time */
final class GotoJob extends Job {
	private final int x, z;
	private final Integer y;
	private BlockPos leg = null;
	private int fails = 0;

	GotoJob(CapriEntity c, CompanionBrain brain, int x, Integer y, int z) {
		super(c, brain);
		this.x = x;
		this.y = y;
		this.z = z;
	}

	@Override
	Result tick(ServerLevel level) {
		Vec3 here = c.position();
		double dx = x + 0.5 - here.x, dz = z + 0.5 - here.z;
		double dist = Math.sqrt(dx * dx + dz * dz);
		if (dist < 3 && (y == null || Math.abs(y - here.y) < 4)) return Result.DONE;
		if (leg == null) {
			double step = Math.min(dist, 32);
			int lx = (int) Math.floor(here.x + dx / dist * step), lz = (int) Math.floor(here.z + dz / dist * step);
			int ly = dist <= 32 && y != null ? y : c.surfaceY(lx, lz);
			leg = new BlockPos(lx, ly, lz);
		}
		int w = brain.walkTo(leg, 2.5);
		if (w == 1) {
			leg = null;
			fails = 0;
		} else if (w == -1) {
			leg = null;
			if (++fails > 6) return fail("cant_reach:" + x + "," + z);
		}
		return Result.RUNNING;
	}

	@Override
	String step() {
		return "goto " + x + " " + z;
	}
}
