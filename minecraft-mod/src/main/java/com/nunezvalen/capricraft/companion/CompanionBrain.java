package com.nunezvalen.capricraft.companion;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What Capri decides, every tick: fight whatever is attacking you or her, eat when she's hurt, do
 * the job she was given, and otherwise follow you (or wait where you told her to). Also the hands and
 * feet the jobs share: walking somewhere, digging a block the way a player does (cracks, the right
 * tool, drops only with the right pickaxe), tunnelling a staircase, placing blocks, picking things up.
 */
final class CompanionBrain {
	final CapriEntity c;
	private String mode = "follow";
	private BlockPos stayPos = null;
	private Job task = null;
	private String lastStep = "";
	private int ticks = 0;

	// Fighting
	private LivingEntity enemy = null;
	private int attackCooldown = 0;
	private LivingEntity lastAttacker = null;
	private int lastAttackedAt = -1000;
	private int eatCooldown = 0;
	private int fightTicks = 0;
	private final java.util.Map<Integer, Integer> ignored = new java.util.HashMap<>();
	/** Set while a task asked her to kill this one (it doesn't stop on its own) */
	LivingEntity huntTarget = null;

	CompanionBrain(CapriEntity c) {
		this.c = c;
	}

	// ---- What the bridge and the snapshot see ----

	String mode() {
		return mode;
	}

	void setMode(String m) {
		mode = "stay".equals(m) ? "stay" : "follow";
	}

	String taskName() {
		return task == null ? "" : task.getClass().getSimpleName().replace("Job", "").replace("Task", "").toLowerCase();
	}

	String step() {
		if (enemy != null) return "fight " + id(enemy);
		return task == null ? mode : lastStep;
	}

	/** What she's stuck on, for the status (debugging) */
	String debug = "";

	boolean busy() {
		return task != null;
	}

	void cancel() {
		if (task != null) task.abort();
		task = null;
		huntTarget = null;
		stopWalking();
		stopDigging();
	}

	void respawned() {
		enemy = null;
		lastAttacker = null;
		if (task != null) task.pause();
		stopDigging();
	}

	void attackedBy(LivingEntity attacker) {
		if (attacker instanceof Player p && p.getUUID().equals(c.ownerId())) return;
		lastAttacker = attacker;
		lastAttackedAt = ticks;
	}

	// ---- Orders ----

	void accept(JsonObject o) {
		String action = str(o, "action", "");
		int count = Math.max(1, Math.min(int_(o, "count", 1), 2304));
		switch (action) {
			case "follow" -> {
				cancel();
				mode = "follow";
				Companion.event("mode", "follow");
			}
			case "stay" -> {
				cancel();
				mode = "stay";
				stayPos = c.blockPosition();
				Companion.event("mode", "stay");
			}
			case "stop" -> {
				cancel();
				Companion.event("stopped", "");
			}
			case "come" -> start(new ComeJob(c, this));
			case "get" -> {
				String item = str(o, "item", "");
				List<Planner.Want> wants = wantsFor(item);
				if (wants.isEmpty()) {
					Companion.event("failed", "unknown:" + item);
					return;
				}
				start(new GetTask(c, this, wants, wants.size() > 1 ? 1 : count, o.has("give") && o.get("give").getAsBoolean(), item));
			}
			case "mine" -> {
				String id = str(o, "block", "").replace("minecraft:", "");
				Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(id)).orElse(null);
				if (block == null || block == Blocks.AIR) {
					Companion.event("failed", "unknown:" + id);
					return;
				}
				start(new MineTask(c, this, block, count, o.has("give") && o.get("give").getAsBoolean()));
			}
			case "kill" -> {
				String mob = str(o, "mob", "hostile").replace("minecraft:", "");
				Predicate<Entity> prey;
				if (mob.equals("hostile") || mob.equals("monsters")) {
					prey = e -> e instanceof Enemy && !(e instanceof Creeper);
				} else {
					EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.withDefaultNamespace(mob)).orElse(null);
					if (type == null) {
						Companion.event("failed", "unknown_mob:" + mob);
						return;
					}
					prey = e -> e.getType() == type;
				}
				start(new KillJob(c, this, prey, count, mob));
			}
			case "build" -> start(new BuildJob(c, this, str(o, "what", "house"), str(o, "material", "")));
			case "give" -> {
				String item = str(o, "item", "all");
				if (item.equals("all")) {
					start(new GiveJob(c, this, null, 0, "all"));
				} else {
					List<Planner.Want> wants = wantsFor(item);
					if (wants.isEmpty()) {
						Companion.event("failed", "unknown:" + item);
						return;
					}
					Planner.Want w = wants.getFirst();
					int amount = o.has("count") ? count : Math.max(1, c.count(w.matches()));
					start(new GiveJob(c, this, w.matches(), amount, w.name()));
				}
			}
			case "goto" -> {
				if (!o.has("x") || !o.has("z")) return;
				int x = o.get("x").getAsInt(), z = o.get("z").getAsInt();
				Integer y = o.has("y") ? o.get("y").getAsInt() : null;
				start(new GotoJob(c, this, x, y, z));
			}
			case "drop" -> {
				// Leaves everything she carries on the ground right here (for a chest, say)
				for (int i = 0; i < c.getInventory().getContainerSize(); i++) {
					ItemStack s = c.getInventory().removeItemNoUpdate(i);
					if (!s.isEmpty() && c.level() instanceof ServerLevel sl) c.spawnAtLocation(sl, s);
				}
				c.getInventory().setChanged();
				Companion.event("dropped", "all");
			}
			default -> Companion.event("failed", "unknown_action:" + action);
		}
	}

	private void start(Job job) {
		cancel();
		task = job;
		lastStep = "";
		Companion.event("started", taskName());
	}

	static String str(JsonObject o, String key, String fallback) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
	}

	static int int_(JsonObject o, String key, int fallback) {
		try {
			return o.has(key) ? o.get(key).getAsInt() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/** "diamond", "log", "food", "iron_armor"... as things to get */
	static List<Planner.Want> wantsFor(String raw) {
		String name = raw.toLowerCase().replace("minecraft:", "").trim().replace(' ', '_');
		List<Planner.Want> out = new ArrayList<>();
		switch (name) {
			case "food" -> out.add(Planner.FOOD);
			case "log", "logs", "wood" -> out.add(Planner.Want.tag(ItemTags.LOGS, Items.OAK_LOG, "log"));
			case "planks" -> out.add(Planner.Want.tag(ItemTags.PLANKS, Items.OAK_PLANKS, "planks"));
			case "stone", "cobblestone" -> out.add(new Planner.Want(s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE), Items.COBBLESTONE, "cobblestone"));
			case "iron_armor", "diamond_armor", "leather_armor", "golden_armor", "chainmail_armor" -> {
				String m = name.replace("_armor", "");
				for (String piece : new String[] {"chestplate", "leggings", "helmet", "boots"}) {
					Item item = item(m + "_" + piece);
					if (item != null) out.add(Planner.Want.of(item));
				}
			}
			default -> {
				Item item = item(name);
				if (item == null) {
					Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(name)).orElse(null);
					if (block != null && block.asItem() != Items.AIR) item = block.asItem();
				}
				if (item != null && item != Items.AIR) out.add(Planner.Want.of(item));
			}
		}
		return out;
	}

	static Item item(String id) {
		try {
			return BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(id)).filter(i -> i != Items.AIR).orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ---- Every tick ----

	void tick(ServerLevel level) {
		ticks++;
		if (attackCooldown > 0) attackCooldown--;
		if (eatCooldown > 0) eatCooldown--;
		if (ticks % 20 == 0) c.wearBestArmor();
		if (ticks % 100 == 0) c.tidy();
		c.pickUpNearby();

		if (c.getHealth() <= 12 && eatCooldown <= 0 && c.eat()) eatCooldown = 60;

		if (fleeCreeper(level)) return;

		updateEnemy(level);
		if (enemy != null && enemy != huntTarget) {
			if (task != null) task.pause();
			fight(level, enemy);
			return;
		}

		if (task != null) {
			Job.Result r;
			try {
				r = task.tick(level);
			} catch (RuntimeException e) {
				com.nunezvalen.capricraft.CapriCraftClient.LOG.error("[CapriCraft] Capri's task crashed", e);
				r = task.fail("error:" + e.getClass().getSimpleName());
			}
			if (r == Job.Result.RUNNING) {
				String s = task.step();
				if (s != null && !s.isEmpty() && !s.equals(lastStep)) {
					lastStep = s;
					Companion.event("step", s);
				}
				return;
			}
			Job done = task;
			task = null;
			huntTarget = null;
			stopWalking();
			stopDigging();
			if (r == Job.Result.DONE) Companion.event("done", done.step());
			else Companion.event("failed", done.failure);
			return;
		}
		idle(level);
	}

	private void idle(ServerLevel level) {
		ServerPlayer owner = c.owner();
		if (mode.equals("stay")) {
			if (stayPos != null && c.blockPosition().distSqr(stayPos) > 4) walkTo(stayPos, 1.0);
			else stopWalking();
			return;
		}
		if (owner == null || owner.isSpectator()) return;
		if (owner.level() != c.level()) {
			c.teleportNear(owner);
			return;
		}
		double d = c.distanceTo(owner);
		if (d > 40) {
			c.teleportNear(owner);
		} else if (d > 5) {
			if (ticks % 10 == 0) c.getNavigation().moveTo(owner, d > 10 ? 1.3 : 1.0);
		} else if (d < 3) {
			c.getNavigation().stop();
		}
	}

	// ---- Fighting ----

	private void updateEnemy(ServerLevel level) {
		if (enemy != null && (!enemy.isAlive() || enemy.level() != c.level() || c.distanceTo(enemy) > 24)) enemy = null;
		// Ten seconds without landing a hit (behind a wall, across water): not worth it
		if (enemy != null && ++fightTicks > 200) {
			ignored.put(enemy.getId(), ticks + 600);
			if (enemy == lastAttacker) lastAttacker = null;
			enemy = null;
		}
		if (enemy != null) return;
		fightTicks = 0;
		ignored.values().removeIf(until -> until < ticks);
		if (lastAttacker != null && !ignored.containsKey(lastAttacker.getId()) && ticks - lastAttackedAt < 200 && lastAttacker.isAlive() && lastAttacker.level() == c.level() && c.distanceTo(lastAttacker) < 16) {
			enemy = lastAttacker;
			Companion.event("fight", id(enemy));
			return;
		}
		ServerPlayer owner = c.owner();
		if (owner != null && owner.level() == c.level()) {
			LivingEntity hurtOwner = owner.getLastHurtByMob();
			if (hurtOwner != null && hurtOwner != c && !ignored.containsKey(hurtOwner.getId()) && hurtOwner.isAlive() && owner.tickCount - owner.getLastHurtByMobTimestamp() < 100 && c.distanceTo(hurtOwner) < 20) {
				enemy = hurtOwner;
				Companion.event("fight", id(enemy));
				return;
			}
			LivingEntity ownerHit = owner.getLastHurtMob();
			if (ownerHit instanceof Enemy && !ignored.containsKey(ownerHit.getId()) && ownerHit.isAlive() && owner.tickCount - owner.getLastHurtMobTimestamp() < 100 && c.distanceTo(ownerHit) < 20) {
				enemy = ownerHit;
				Companion.event("fight", id(enemy));
				return;
			}
		}
		if (ticks % 10 != 0) return;
		// Monsters close by, or coming for one of you
		List<Mob> mobs = level.getEntitiesOfClass(Mob.class, c.around(12), m -> m instanceof Enemy && m.isAlive() && !(m instanceof Creeper) && !ignored.containsKey(m.getId()));
		Mob best = null;
		double bestD = Double.MAX_VALUE;
		for (Mob m : mobs) {
			LivingEntity t = m.getTarget();
			boolean aggressive = t == c || (owner != null && t == owner);
			boolean neutral = m.getType() == net.minecraft.world.entity.EntityTypes.ENDERMAN || m.getType() == net.minecraft.world.entity.EntityTypes.ZOMBIFIED_PIGLIN || m.getType() == net.minecraft.world.entity.EntityTypes.PIGLIN;
			double d = c.distanceTo(m);
			if (!aggressive && (neutral || d > 7)) continue;
			if (!c.hasLineOfSight(m)) continue;
			if (d < bestD) {
				bestD = d;
				best = m;
			}
		}
		if (best != null) {
			enemy = best;
			Companion.event("fight", id(enemy));
		}
	}

	/** Walks up to it and hits it with the best weapon she has */
	void fight(ServerLevel level, LivingEntity target) {
		c.hold(Ranks::isWeapon, Comparator.comparingInt(Ranks::weapon));
		c.getLookControl().setLookAt(target, 30, 30);
		if (ticks % 5 == 0) c.getNavigation().moveTo(target, 1.3);
		if (attackCooldown <= 0 && c.isWithinMeleeAttackRange(target) && c.hasLineOfSight(target)) {
			c.swingForAttack(InteractionHand.MAIN_HAND);
			if (c.doHurtTarget(level, target)) fightTicks = 0;
			attackCooldown = Ranks.isWeapon(c.getMainHandItem()) ? 12 : 16;
			if (!target.isAlive() || target.isDeadOrDying()) {
				Companion.event("killed", id(target));
				if (target == enemy) enemy = null;
			}
		}
	}

	private boolean fleeCreeper(ServerLevel level) {
		List<Creeper> creepers = level.getEntitiesOfClass(Creeper.class, c.around(5), cr -> cr.isAlive() && cr.getSwellDir() > 0);
		if (creepers.isEmpty()) return false;
		Creeper cr = creepers.getFirst();
		Vec3 away = c.position().subtract(cr.position()).normalize().scale(7).add(c.position());
		c.getNavigation().moveTo(away.x, away.y, away.z, 1.5);
		if (ticks % 40 == 0) Companion.event("flee", "creeper");
		return true;
	}

	static String id(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
	}

	// ---- Walking ----

	private BlockPos walkDest = null;
	private int walkRepath = 0;
	private double walkBest = 0;
	private int walkNoProgress = 0;

	/** 1 arrived, 0 on the way, -1 can't get there */
	int walkTo(BlockPos dest, double reach) {
		double d = c.getEyePosition().distanceTo(Vec3.atCenterOf(dest));
		double feet = c.position().distanceTo(Vec3.atBottomCenterOf(dest));
		if (Math.min(d, feet) <= reach) {
			c.getNavigation().stop();
			walkDest = null;
			return 1;
		}
		if (!dest.equals(walkDest)) {
			walkDest = dest;
			walkRepath = 0;
			walkBest = d;
			walkNoProgress = 0;
		}
		if (--walkRepath <= 0) {
			walkRepath = 20;
			Path path = c.getNavigation().createPath(dest, Math.max(0, (int) Math.floor(reach) - 1));
			if (path == null) {
				walkDest = null;
				return -1;
			}
			c.getNavigation().moveTo(path, 1.1);
		}
		if (d < walkBest - 0.4) {
			walkBest = d;
			walkNoProgress = 0;
		} else if (++walkNoProgress > 70) {
			walkDest = null;
			c.getNavigation().stop();
			return -1;
		}
		return 0;
	}

	void stopWalking() {
		walkDest = null;
		c.getNavigation().stop();
	}

	// ---- Digging ----

	private BlockPos digPos = null;
	private float digProgress = 0;
	private int digTicks = 0;
	boolean lastUnbreakable = false;

	/** One tick of breaking it; true once it's gone */
	boolean dig(ServerLevel level, BlockPos pos) {
		lastUnbreakable = false;
		BlockState st = level.getBlockState(pos);
		if (st.isAir() || (st.getCollisionShape(level, pos).isEmpty() && !st.getFluidState().isEmpty())) return true;
		float hardness = st.getDestroySpeed(level, pos);
		if (hardness < 0 || st.is(Blocks.BEDROCK)) {
			lastUnbreakable = true;
			return false;
		}
		if (!pos.equals(digPos)) {
			stopDigging();
			digPos = pos;
			digProgress = 0;
			digTicks = 0;
			BlockState target = st;
			c.hold(s -> s.getDestroySpeed(target) > 1.0F || s.isCorrectToolForDrops(target),
				Comparator.<ItemStack>comparingInt(s -> s.isCorrectToolForDrops(target) ? 1 : 0).thenComparingDouble(s -> s.getDestroySpeed(target)));
		}
		ItemStack tool = c.getMainHandItem();
		boolean correct = !st.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(st);
		float speed = Math.max(1.0F, tool.getDestroySpeed(st));
		if (c.isUnderWater()) speed /= 5.0F;
		float perTick = hardness == 0 ? 1.0F : speed / hardness / (correct ? 30.0F : 100.0F);
		digProgress += perTick;
		digTicks++;
		c.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		if (digTicks % 5 == 1) c.swingForAttack(InteractionHand.MAIN_HAND);
		if (digTicks % 4 == 1) level.playSound(null, pos, st.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.25F, 0.5F);
		if (digProgress < 1.0F) {
			level.destroyBlockProgress(c.getId(), pos, Math.min(9, (int) (digProgress * 10)));
			return false;
		}
		level.destroyBlockProgress(c.getId(), pos, -1);
		BlockEntity be = st.hasBlockEntity() ? level.getBlockEntity(pos) : null;
		level.levelEvent(null, LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK, pos, Block.getId(st));
		if (correct) Block.dropResources(st, level, pos, be, c, tool);
		level.setBlock(pos, level.getFluidState(pos).createLegacyBlock(), 3);
		if (!tool.isEmpty() && hardness > 0) tool.hurtAndBreak(1, c, EquipmentSlot.MAINHAND);
		digPos = null;
		return true;
	}

	void stopDigging() {
		if (digPos != null && c.level() instanceof ServerLevel sl) sl.destroyBlockProgress(c.getId(), digPos, -1);
		digPos = null;
		digProgress = 0;
	}

	boolean inReach(BlockPos pos) {
		return c.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= 4.5;
	}

	/** Can she see the block (or the tree it's part of) from where she stands? */
	boolean canSee(ServerLevel level, BlockPos pos, Predicate<BlockState> sameKind) {
		BlockHitResult hit = level.clip(new ClipContext(c.getEyePosition(), Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, c));
		if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return true;
		BlockPos at = hit.getBlockPos();
		if (at.equals(pos)) return true;
		BlockState st = level.getBlockState(at);
		return sameKind.test(st) || st.is(net.minecraft.tags.BlockTags.LEAVES);
	}

	static boolean exposed(ServerLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockPos n = pos.relative(d);
			BlockState st = level.getBlockState(n);
			if (st.isAir() || st.getCollisionShape(level, n).isEmpty()) return true;
		}
		return false;
	}

	/** The closest block of that kind she can actually see, within r blocks */
	BlockPos findVisible(ServerLevel level, Predicate<BlockState> what, int r, Set<BlockPos> skip) {
		BlockPos center = BlockPos.containing(c.getEyePosition());
		List<BlockPos> found = new ArrayList<>();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int minY = Math.max(level.getMinY(), center.getY() - r), maxY = Math.min(level.getMaxY(), center.getY() + r);
		for (int y = minY; y <= maxY; y++) {
			for (int x = -r; x <= r; x++) {
				for (int z = -r; z <= r; z++) {
					m.set(center.getX() + x, y, center.getZ() + z);
					if (!what.test(level.getBlockState(m))) continue;
					BlockPos p = m.immutable();
					if (skip.contains(p) || !exposed(level, p)) continue;
					found.add(p);
				}
			}
		}
		found.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
		int checks = 0;
		for (BlockPos p : found) {
			if (checks++ > 12) break;
			if (canSee(level, p, what)) return p;
		}
		return null;
	}

	// ---- Tunnelling ----

	private Direction tunnelDir = Direction.NORTH;
	private int tunnelStuck = 0;
	private BlockPos tunnelFrom = null;
	String tunnelProblem = "";

	/** One step of a 1x2 tunnel (a staircase when it has to go up or down): 1 stepped, 0 working, -1 blocked */
	int tunnelToward(ServerLevel level, BlockPos goal) {
		BlockPos feet = c.blockPosition();
		int dx = goal.getX() - feet.getX(), dz = goal.getZ() - feet.getZ(), dy = goal.getY() - feet.getY();
		Direction dir;
		if (dx == 0 && dz == 0) dir = tunnelDir;
		else dir = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
		tunnelDir = dir;
		int vy = dy < 0 ? -1 : (dy > 1 ? 1 : 0);
		BlockPos next = feet.relative(dir).offset(0, vy, 0);
		List<BlockPos> clear = new ArrayList<>();
		if (vy == 1) clear.add(feet.above(2));
		clear.add(next.above());
		clear.add(next);
		if (vy == -1) clear.add(next.above(2));
		// Water or lava next to where she's about to dig: sealed with a block first, like a player would
		for (BlockPos p : clear) {
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (clear.contains(n) || n.equals(feet) || n.equals(feet.above())) continue;
				if (level.getFluidState(n).isEmpty()) continue;
				boolean lava = level.getFluidState(n).is(net.minecraft.tags.FluidTags.LAVA);
				if (!placeBlock(level, n, filler())) {
					tunnelProblem = lava ? "lava" : "water";
					return -1;
				}
				return 0;
			}
			if (!level.getFluidState(p).isEmpty()) {
				if (!placeBlock(level, p, filler())) {
					tunnelProblem = level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA) ? "lava" : "water";
					return -1;
				}
				return 0;
			}
		}
		for (BlockPos p : clear) {
			BlockState st = level.getBlockState(p);
			if (st.getCollisionShape(level, p).isEmpty()) continue;
			if (!inReach(p)) {
				stopWalking();
				c.getMoveControl().setWantedPosition(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 1.0);
			}
			if (!dig(level, p)) {
				if (lastUnbreakable) {
					tunnelProblem = "bedrock";
					return -1;
				}
				return 0;
			}
			return 0;
		}
		// Something to stand on
		BlockPos floor = next.below();
		BlockState below = level.getBlockState(floor);
		if (!below.isFaceSturdy(level, floor, Direction.UP)) {
			if (!level.getFluidState(floor).isEmpty() && level.getFluidState(floor).is(net.minecraft.tags.FluidTags.LAVA)) {
				tunnelProblem = "lava";
				return -1;
			}
			if (!placeBlock(level, floor, filler())) {
				if (level.getBlockState(floor.below()).isAir() && level.getBlockState(floor.below(2)).isAir()) {
					tunnelProblem = "drop";
					return -1;
				}
			}
		}
		if (!next.equals(tunnelFrom)) {
			tunnelFrom = next;
			tunnelStuck = 0;
		}
		c.getMoveControl().setWantedPosition(next.getX() + 0.5, next.getY(), next.getZ() + 0.5, 1.0);
		if (vy == 1 && c.onGround()) c.getJumpControl().jump();
		if (c.blockPosition().equals(next)) {
			tunnelStuck = 0;
			return 1;
		}
		// Stuck on an edge: a little nudge
		if (++tunnelStuck > 12) {
			c.teleportTo(next.getX() + 0.5, next.getY(), next.getZ() + 0.5);
			tunnelStuck = 0;
			return 1;
		}
		return 0;
	}

	void turnTunnel() {
		tunnelDir = tunnelDir.getClockWise();
	}

	Direction tunnelDir() {
		return tunnelDir;
	}

	static boolean nearLava(ServerLevel level, BlockPos p) {
		if (level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA)) return true;
		for (Direction d : Direction.values()) {
			if (level.getFluidState(p.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return true;
		}
		return false;
	}

	/** Out of the water to the closest dry spot she can stand on; true while she's on her way */
	boolean escapeWater(ServerLevel level) {
		BlockPos feet = c.blockPosition();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(feet.offset(-6, -2, -6), feet.offset(6, 3, 6))) {
			if (!CapriEntity.standable(level, p) || nearWater(level, p)) continue;
			double d = p.distSqr(feet);
			if (d < bestD) {
				bestD = d;
				best = p.immutable();
			}
		}
		if (best == null) return false;
		return walkTo(best, 0.8) == 0;
	}

	static boolean nearWater(ServerLevel level, BlockPos p) {
		if (level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) return true;
		for (Direction d : Direction.values()) {
			if (d == Direction.DOWN) continue;
			if (level.getFluidState(p.relative(d)).is(net.minecraft.tags.FluidTags.WATER)) return true;
		}
		return false;
	}

	/** A plain block she doesn't mind using to fill a gap */
	Predicate<ItemStack> filler() {
		return s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.DIRT) || s.is(Items.NETHERRACK) || s.is(Items.ANDESITE)
			|| s.is(Items.DIORITE) || s.is(Items.GRANITE) || s.is(Items.TUFF) || s.is(Items.STONE) || s.is(Items.DEEPSLATE) || s.is(Items.CALCITE)
			|| s.is(Items.BLACKSTONE) || s.is(Items.BASALT) || s.is(Items.MUD) || s.is(Items.COARSE_DIRT);
	}

	// ---- Placing ----

	/** Puts down one block from her inventory (the first that matches); false if she has none */
	boolean placeBlock(ServerLevel level, BlockPos pos, Predicate<ItemStack> which) {
		for (int i = 0; i < c.getInventory().getContainerSize(); i++) {
			ItemStack s = c.getInventory().getItem(i);
			if (s.isEmpty() || !which.test(s) || !(s.getItem() instanceof BlockItem bi)) continue;
			return placeState(level, pos, bi.getBlock().defaultBlockState(), s);
		}
		return false;
	}

	boolean placeState(ServerLevel level, BlockPos pos, BlockState state, ItemStack from) {
		BlockState there = level.getBlockState(pos);
		if (!there.canBeReplaced()) return false;
		if (!level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(pos), e -> e.isAlive() && !e.isSpectator()).isEmpty()) return false;
		level.setBlock(pos, state, 3);
		level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
		c.swingForAttack(InteractionHand.MAIN_HAND);
		c.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		if (from != null) {
			from.shrink(1);
			c.getInventory().setChanged();
		}
		return true;
	}

	/** Puts a crafting table or furnace down next to her; returns where */
	BlockPos placeNear(ServerLevel level, Item item, BlockState state) {
		BlockPos feet = c.blockPosition();
		ServerPlayer owner = c.owner();
		List<BlockPos> spots = new ArrayList<>();
		for (BlockPos p : BlockPos.betweenClosed(feet.offset(-3, -2, -3), feet.offset(3, 2, 3))) {
			if (p.getX() == feet.getX() && p.getZ() == feet.getZ()) continue;
			spots.add(p.immutable());
		}
		spots.sort(Comparator.comparingDouble(p -> p.distSqr(feet) + Math.abs(p.getY() - feet.getY()) * 2));
		for (BlockPos p : spots) {
			{
				{
					if (owner != null && (owner.blockPosition().equals(p) || owner.blockPosition().above().equals(p))) continue;
					BlockState below = level.getBlockState(p.below());
					if (!level.getBlockState(p).canBeReplaced() || !level.getFluidState(p).isEmpty() || below.isAir() || below.canBeReplaced()) continue;
					for (int i = 0; i < c.getInventory().getContainerSize(); i++) {
						ItemStack s = c.getInventory().getItem(i);
						if (s.is(item) && placeState(level, p, state, s)) return p;
					}
				}
			}
		}
		return null;
	}

	/** Takes back a table or furnace she put down */
	void pickUpBlock(ServerLevel level, BlockPos pos, Block block) {
		if (pos == null || !level.getBlockState(pos).is(block)) return;
		BlockState st = level.getBlockState(pos);
		level.removeBlockEntity(pos);
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		level.playSound(null, pos, st.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
		c.swingForAttack(InteractionHand.MAIN_HAND);
		c.give(new ItemStack(block.asItem()));
	}

	BlockPos findNear(ServerLevel level, Block block, int r) {
		BlockPos center = c.blockPosition();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -3, -r), center.offset(r, 3, r))) {
			if (level.getBlockState(p).is(block)) {
				double d = p.distSqr(center);
				if (d < bestD) {
					bestD = d;
					best = p.immutable();
				}
			}
		}
		return best;
	}

	boolean hasTableNear(ServerLevel level) {
		return findNear(level, Blocks.CRAFTING_TABLE, 8) != null;
	}

	boolean hasFurnaceNear(ServerLevel level) {
		return findNear(level, Blocks.FURNACE, 8) != null;
	}

	// ---- Picking things up ----

	private final Set<Integer> unreachableItems = new HashSet<>();

	/** Walks to the closest item lying around (that she didn't throw); true while she's going for one */
	boolean collectDrops(ServerLevel level, double r) {
		List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, c.around(r), e -> e.isAlive() && e.getOwner() != c && !unreachableItems.contains(e.getId()) && c.getInventory().canAddItem(e.getItem()));
		if (items.isEmpty()) return false;
		items.sort(Comparator.comparingDouble(c::distanceToSqr));
		ItemEntity item = items.getFirst();
		int w = walkTo(item.blockPosition(), 0.8);
		if (w == -1) {
			unreachableItems.add(item.getId());
			return false;
		}
		return w == 0;
	}

	ServerPlayer ownerIfHere() {
		ServerPlayer owner = c.owner();
		return owner != null && owner.level() == c.level() ? owner : null;
	}

	boolean isOwnerOrPet(Entity e) {
		if (e instanceof Player p) return true;
		if (e instanceof TamableAnimal t && t.isTame()) return true;
		return e == c;
	}
}
