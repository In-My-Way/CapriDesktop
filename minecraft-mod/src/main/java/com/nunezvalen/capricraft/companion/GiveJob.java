package com.nunezvalen.capricraft.companion;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/** Walks up to you and throws you the things (or everything she carries, with a null filter) */
final class GiveJob extends Job {
	private final Predicate<ItemStack> what;
	private int left;
	private final String name;
	private int given = 0;
	private int cooldown = 0;

	GiveJob(CapriEntity c, CompanionBrain brain, Predicate<ItemStack> what, int count, String name) {
		super(c, brain);
		this.what = what;
		this.left = what == null ? Integer.MAX_VALUE : count;
		this.name = name;
	}

	@Override
	Result tick(ServerLevel level) {
		ServerPlayer owner = c.owner();
		if (owner == null) return fail("no_player");
		if (owner.level() != c.level() || c.distanceTo(owner) > 40) c.teleportNear(owner);
		if (c.distanceTo(owner) > 2.8) {
			if (c.tickCount % 10 == 0) c.getNavigation().moveTo(owner, 1.2);
			return Result.RUNNING;
		}
		c.getNavigation().stop();
		c.getLookControl().setLookAt(owner, 30, 30);
		if (cooldown-- > 0) return Result.RUNNING;
		for (int i = 0; i < c.getInventory().getContainerSize() && left > 0; i++) {
			ItemStack s = c.getInventory().getItem(i);
			if (s.isEmpty() || (what != null && !what.test(s))) continue;
			int k = Math.min(left, s.getCount());
			ItemStack thrown = s.split(k);
			c.getInventory().setChanged();
			c.throwTo(owner, thrown);
			left -= k;
			given += k;
			cooldown = 4;
			return Result.RUNNING;
		}
		// What she holds in her hand counts too (you asked for her sword, say)
		if (what != null && left > 0 && what.test(c.getMainHandItem())) {
			ItemStack held = c.getMainHandItem().split(Math.min(left, c.getMainHandItem().getCount()));
			c.throwTo(owner, held);
			given += held.getCount();
			left = 0;
		}
		if (given == 0) return fail("dont_have:" + name);
		Companion.event("gave", name + " x" + given);
		return Result.DONE;
	}

	@Override
	String step() {
		return "give " + name;
	}
}
