package com.nunezvalen.capricraft.companion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Builds something in front of you: a house (7x7 with a door, windows and a roof), a small shelter,
 * a tower with a ladder inside, or a wall. She gets the materials first (planks from trees, or
 * cobblestone if you asked for stone or she has plenty), then clears the spot and places block by
 * block, walls from the bottom up, like a person would.
 */
final class BuildJob extends Job {
	enum Kind { CLEAR, FLOOR, WALL, ROOF, DOOR, WINDOW, LADDER, TORCH }

	record Spot(BlockPos pos, Kind kind) {}

	private final String what;
	private final String material;
	private final List<Spot> plan = new ArrayList<>();
	private Direction facing;
	private int height;
	private boolean stone;
	private int phase = 0; // 0 decide, 1 materials, 2 extras, 3 build
	private Job current = null;
	private int index = 0;
	private int waited = 0;
	private int pause = 0;
	private int skipped = 0;
	private Item doorItem = null;
	private boolean wantsLadder = false;

	BuildJob(CapriEntity c, CompanionBrain brain, String what, String material) {
		super(c, brain);
		this.what = switch (what) {
			case "shelter", "refugio", "hut" -> "shelter";
			case "tower", "torre" -> "tower";
			case "wall", "muro", "pared" -> "wall";
			default -> "house";
		};
		this.material = material;
	}

	private Predicate<ItemStack> materialItems() {
		return stone ? s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) : s -> s.is(ItemTags.PLANKS);
	}

	@Override
	Result tick(ServerLevel level) {
		if (phase == 0) {
			ServerPlayer owner = c.owner();
			if (owner == null) return fail("no_player");
			design(owner);
			// Not on a lake: most of the floor has to be dry ground
			long wet = plan.stream().filter(s -> s.kind == Kind.FLOOR && !level.getFluidState(s.pos.above()).isEmpty() || s.kind == Kind.FLOOR && !level.getFluidState(s.pos).isEmpty()).count();
			long floor = plan.stream().filter(s -> s.kind == Kind.FLOOR).count();
			if (floor > 0 && wet * 3 > floor) return fail("no_room_for:build_water");
			int need = (int) plan.stream().filter(s -> s.kind == Kind.WALL || s.kind == Kind.ROOF || s.kind == Kind.FLOOR).count();
			int stoneHave = c.count(s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE));
			int woodHave = c.count(s -> s.is(ItemTags.PLANKS)) + c.count(s -> s.is(ItemTags.LOGS)) * 4;
			stone = material.contains("stone") || material.contains("piedra") || material.contains("cobble")
				|| (!material.contains("wood") && !material.contains("plank") && !material.contains("madera") && stoneHave >= need && woodHave < need);
			phase = 1;
			Companion.event("build_needs", (stone ? "cobblestone" : "planks") + " x" + need);
			return Result.RUNNING;
		}
		if (phase == 1) {
			int need = (int) plan.stream().filter(s -> s.kind == Kind.WALL || s.kind == Kind.ROOF || s.kind == Kind.FLOOR).count();
			Planner.Want want = stone
				? new Planner.Want(materialItems(), Items.COBBLESTONE, "cobblestone")
				: Planner.Want.tag(ItemTags.PLANKS, Items.OAK_PLANKS, "planks");
			// Floors are often already there: about two thirds of it is enough to start
			Result r = runPlan(level, want, Math.max(16, need - need / 6));
			if (r != Result.DONE) return r;
			phase = 2;
			return Result.RUNNING;
		}
		if (phase == 2) {
			// A door to match her wood, and a ladder for a tower (both optional: on failure she builds without)
			if (doorItem == null) doorItem = matchingDoor();
			if (doorItem != null && c.count(s -> s.is(ItemTags.WOODEN_DOORS)) == 0) {
				Result r = runPlan(level, Planner.Want.of(doorItem), 1);
				if (r == Result.RUNNING) return r;
				if (r == Result.FAILED) doorItem = null;
			}
			if (wantsLadder && c.count(s -> s.is(Items.LADDER)) < height) {
				Result r = runPlan(level, Planner.Want.of(Items.LADDER), height);
				if (r == Result.RUNNING) return r;
				if (r == Result.FAILED) wantsLadder = false;
			}
			phase = 3;
			Companion.event("building", what);
			return Result.RUNNING;
		}
		return build(level);
	}

	private Result runPlan(ServerLevel level, Planner.Want want, int count) {
		if (current == null) {
			try {
				current = Planner.next(c, brain, level, want, count);
			} catch (Planner.PlanException e) {
				return fail(e.getMessage());
			}
			if (current == null) return Result.DONE;
		}
		Result r = current.tick(level);
		if (r == Result.FAILED) {
			failure = current.failure;
			current = null;
			return Result.FAILED;
		}
		if (r == Result.DONE) current = null;
		return Result.RUNNING;
	}

	private Item matchingDoor() {
		for (int i = 0; i < c.getInventory().getContainerSize(); i++) {
			ItemStack s = c.getInventory().getItem(i);
			if (s.is(ItemTags.PLANKS)) {
				String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().replace("_planks", "_door");
				Item door = CompanionBrain.item(id);
				if (door != null) return door;
			}
		}
		return Items.OAK_DOOR;
	}

	private Result build(ServerLevel level) {
		if (pause-- > 0) return Result.RUNNING;
		while (index < plan.size() && done(level, plan.get(index))) index++;
		if (index >= plan.size()) {
			Companion.event("built", what + (skipped > 0 ? " skipped:" + skipped : ""));
			return Result.DONE;
		}
		Spot spot = plan.get(index);
		BlockPos pos = spot.pos;
		brain.debug = "build " + index + "/" + plan.size() + " " + spot.kind + " " + pos.toShortString() + " reach=" + brain.inReach(pos) + " waited=" + waited + " skipped=" + skipped + " state=" + level.getBlockState(pos).getBlock();
		if (!brain.inReach(pos)) {
			int w = brain.walkTo(pos, 3.6);
			if (w == -1 || ++waited > 120) {
				skip();
			}
			return Result.RUNNING;
		}
		brain.stopWalking();
		if (spot.kind == Kind.CLEAR) {
			if (brain.dig(level, pos) || brain.lastUnbreakable) {
				index++;
				waited = 0;
			}
			return Result.RUNNING;
		}
		// Something soft in the way (grass, flowers, snow): gone first
		BlockState there = level.getBlockState(pos);
		if (!there.isAir() && there.canBeReplaced() && there.getFluidState().isEmpty()) level.destroyBlock(pos, false, c);
		// She's standing where the block goes: step aside
		if (c.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos))) {
			BlockPos aside = pos.relative(facing.getOpposite(), 2);
			c.getNavigation().moveTo(aside.getX() + 0.5, aside.getY(), aside.getZ() + 0.5, 1.0);
			if (++waited > 80) skip();
			return Result.RUNNING;
		}
		boolean placed = switch (spot.kind) {
			case FLOOR, WALL, ROOF -> brain.placeBlock(level, pos, materialItems());
			case WINDOW -> brain.placeBlock(level, pos, s -> s.is(Items.GLASS) || s.is(Items.GLASS_PANE)) || brain.placeBlock(level, pos, materialItems());
			case TORCH -> brain.placeBlock(level, pos, s -> s.is(Items.TORCH));
			case LADDER -> placeLadder(level, pos);
			case DOOR -> placeDoor(level, pos);
			default -> false;
		};
		if (placed) {
			index++;
			waited = 0;
			pause = 4;
		} else if (spot.kind == Kind.TORCH || (spot.kind == Kind.LADDER && c.count(s -> s.is(Items.LADDER)) == 0)
			|| (spot.kind == Kind.DOOR && c.count(s -> s.is(ItemTags.WOODEN_DOORS)) == 0)) {
			index++; // optional pieces she doesn't have
		} else if (spot.kind == Kind.DOOR || spot.kind == Kind.LADDER) {
			// Someone in the doorway (often her): out of the way, and again
			BlockPos aside = pos.relative(facing.getOpposite(), 3);
			if (waited % 20 == 0) c.getNavigation().moveTo(aside.getX() + 0.5, aside.getY(), aside.getZ() + 0.5, 1.0);
			if (++waited > 160) skip();
		} else if (c.count(materialItems()) == 0) {
			// Ran out: back to getting materials, then on with the building
			phase = 1;
		} else if (++waited > 60) {
			skip();
		}
		return Result.RUNNING;
	}

	private void skip() {
		index++;
		skipped++;
		waited = 0;
		brain.stopWalking();
	}

	private boolean done(ServerLevel level, Spot spot) {
		BlockState st = level.getBlockState(spot.pos);
		return switch (spot.kind) {
			case CLEAR -> st.getCollisionShape(level, spot.pos).isEmpty() && st.getFluidState().isEmpty();
			case FLOOR -> st.isFaceSturdy(level, spot.pos, Direction.UP);
			case WALL, ROOF, WINDOW -> !st.canBeReplaced();
			case DOOR -> st.getBlock() instanceof DoorBlock || doorItem == null && !st.isAir();
			case LADDER -> st.is(Blocks.LADDER);
			case TORCH -> st.is(Blocks.TORCH) || c.count(s -> s.is(Items.TORCH)) == 0;
		};
	}

	private boolean placeLadder(ServerLevel level, BlockPos pos) {
		for (int i = 0; i < c.getInventory().getContainerSize(); i++) {
			ItemStack s = c.getInventory().getItem(i);
			if (!s.is(Items.LADDER)) continue;
			// On the back wall, facing into the tower
			return brain.placeState(level, pos, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, facing.getOpposite()), s);
		}
		return false;
	}

	private boolean placeDoor(ServerLevel level, BlockPos pos) {
		for (int i = 0; i < c.getInventory().getContainerSize(); i++) {
			ItemStack s = c.getInventory().getItem(i);
			if (!s.is(ItemTags.WOODEN_DOORS) || !(s.getItem() instanceof BlockItem bi)) continue;
			if (!level.getBlockState(pos.above()).canBeReplaced()) return false;
			BlockState lower = bi.getBlock().defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HINGE, DoorHingeSide.LEFT).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
			if (!brain.placeState(level, pos, lower, s)) return false;
			level.setBlock(pos.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
			return true;
		}
		return false;
	}

	/** Lays it out in front of the player, the door facing them */
	private void design(ServerPlayer owner) {
		facing = owner.getDirection();
		Direction right = facing.getClockWise();
		BlockPos base = owner.blockPosition().below();
		int w, d;
		switch (what) {
			case "shelter" -> { w = 5; d = 5; height = 3; }
			case "tower" -> { w = 5; d = 5; height = 8; wantsLadder = true; }
			case "wall" -> { w = 9; d = 1; height = 3; }
			default -> { w = 7; d = 7; height = 3; }
		}
		int half = w / 2;
		BlockPos front = base.relative(facing, 3);
		java.util.function.BiFunction<Integer, Integer, BlockPos> at = (i, k) -> front.relative(right, i).relative(facing, k);

		if (what.equals("wall")) {
			for (int y = 1; y <= height; y++) for (int i = -half; i <= half; i++) add(at.apply(i, 0).above(y), Kind.CLEAR, Kind.WALL);
			plan.sort(java.util.Comparator.comparingInt((Spot s) -> s.kind == Kind.CLEAR ? 0 : 1).thenComparingInt(s -> s.pos.getY()));
			return;
		}
		List<Spot> clear = new ArrayList<>(), floor = new ArrayList<>(), walls = new ArrayList<>(), roof = new ArrayList<>(), extra = new ArrayList<>();
		for (int i = -half; i <= half; i++) {
			for (int k = 0; k < d; k++) {
				BlockPos ground = at.apply(i, k);
				boolean edge = i == -half || i == half || k == 0 || k == d - 1;
				floor.add(new Spot(ground, Kind.FLOOR));
				for (int y = 1; y <= height; y++) {
					BlockPos p = ground.above(y);
					if (!edge) {
						clear.add(new Spot(p, Kind.CLEAR));
						continue;
					}
					boolean door = i == 0 && k == 0 && y <= 2;
					boolean window = y == 2 && k == d / 2 && (i == -half || i == half) && !what.equals("tower");
					boolean towerWindow = what.equals("tower") && y % 3 == 2 && y > 2 && i == 0 && k == d - 1;
					if (door) {
						clear.add(new Spot(p, Kind.CLEAR));
						if (y == 1) extra.add(new Spot(p, Kind.DOOR));
					} else if (window || towerWindow) {
						walls.add(new Spot(p, Kind.WINDOW));
					} else {
						walls.add(new Spot(p, Kind.WALL));
					}
				}
				roof.add(new Spot(ground.above(height + 1), Kind.ROOF));
			}
		}
		if (wantsLadder) {
			for (int y = 1; y <= height; y++) extra.add(new Spot(at.apply(0, d - 2).above(y), Kind.LADDER));
		}
		extra.add(new Spot(at.apply(-half + 1, d - 2).above(1), Kind.TORCH));
		walls.sort(java.util.Comparator.comparingInt(s -> s.pos.getY()));
		// Roof from the edges in, so every block has a neighbour to go against
		BlockPos center = at.apply(0, d / 2);
		roof.sort(java.util.Comparator.comparingInt(s -> -(Math.abs(s.pos.getX() - center.getX()) + Math.abs(s.pos.getZ() - center.getZ()))));
		plan.addAll(clear);
		plan.addAll(floor);
		plan.addAll(walls);
		plan.addAll(extra.stream().filter(s -> s.kind == Kind.DOOR).toList());
		plan.addAll(extra.stream().filter(s -> s.kind == Kind.LADDER).toList());
		plan.addAll(roof);
		plan.addAll(extra.stream().filter(s -> s.kind == Kind.TORCH).toList());
	}

	private void add(BlockPos p, Kind... kinds) {
		for (Kind k : kinds) plan.add(new Spot(p, k));
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

	@Override
	String step() {
		if (current != null) return current.step();
		return phase >= 3 ? "build " + what : "prepare " + what;
	}
}
