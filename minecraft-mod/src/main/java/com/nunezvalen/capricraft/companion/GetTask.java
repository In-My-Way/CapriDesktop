package com.nunezvalen.capricraft.companion;

import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * "Get me N of X": asks the planner for the next job, does it, asks again, until she has them.
 * With `give` she then brings them to you.
 */
final class GetTask extends Job {
	private final List<Planner.Want> wants;
	private final int count;
	private final boolean give;
	private final String name;
	private int index = 0;
	private Job current = null;
	private GiveJob giving = null;
	private String lastStep = null;

	GetTask(CapriEntity c, CompanionBrain brain, List<Planner.Want> wants, int count, boolean give, String name) {
		super(c, brain);
		this.wants = wants;
		this.count = count;
		this.give = give;
		this.name = name;
	}

	@Override
	Result tick(ServerLevel level) {
		if (giving != null) {
			Result r = giving.tick(level);
			if (r == Result.FAILED) failure = giving.failure;
			return r;
		}
		if (current == null) {
			if (index >= wants.size()) {
				if (!give) return Result.DONE;
				Planner.Want w = wants.getFirst();
				giving = wants.size() > 1 ? new GiveJob(c, brain, s -> wants.stream().anyMatch(x -> x.matches().test(s)), 4, name) : new GiveJob(c, brain, w.matches(), count, w.name());
				return Result.RUNNING;
			}
			try {
				current = Planner.next(c, brain, level, wants.get(index), count);
			} catch (Planner.PlanException e) {
				return fail(e.getMessage());
			}
			if (current == null) {
				index++;
				return Result.RUNNING;
			}
		}
		Result r = current.tick(level);
		if (r == Result.RUNNING) return r;
		if (r == Result.FAILED) return fail(current.failure);
		current = null;
		return Result.RUNNING;
	}

	@Override
	String step() {
		if (giving != null) return giving.step();
		if (current != null) lastStep = current.step();
		return lastStep != null ? lastStep : "get " + name;
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
