package com.nunezvalen.capricraft.companion;

import net.minecraft.server.level.ServerLevel;

/** One thing she's doing right now (chop a tree, craft a pickaxe, smelt iron), a tick at a time */
abstract class Job {
	enum Result { RUNNING, DONE, FAILED }

	final CapriEntity c;
	final CompanionBrain brain;
	String failure = "failed";
	boolean announced = false;

	Job(CapriEntity c, CompanionBrain brain) {
		this.c = c;
		this.brain = brain;
	}

	abstract Result tick(ServerLevel level);

	/** What she says she's doing (sent as a "step" event once) */
	abstract String step();

	/** Interrupted (a fight, a new order): let go of anything half done */
	void pause() {
		brain.stopDigging();
	}

	/** Cancelled for good: give back anything half used */
	void abort() {
		pause();
	}

	Result fail(String reason) {
		failure = reason;
		return Result.FAILED;
	}
}
