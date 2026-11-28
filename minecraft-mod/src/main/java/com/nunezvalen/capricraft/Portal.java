package com.nunezvalen.capricraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Where she builds a Nether portal and how. The smallest one that lights: a frame 4 wide and 5 tall
 * whose 10 sides are obsidian; the 4 corners can be anything, so they're cobblestone, which also
 * gives each side block something to stand on while she places it.
 *
 *   C O O C     row 4
 *   O . . O     rows 1-3 (inside: the portal)
 *   O . . O
 *   O . . O
 *   C O O C     row 0, on the ground
 *
 * She stands two blocks in front of it, facing it.
 */
public final class Portal {
	public static final int OBSIDIAN = 10;

	static final int[][] OBSIDIAN_SPOTS = {{1, 0}, {2, 0}, {0, 1}, {0, 2}, {0, 3}, {3, 1}, {3, 2}, {3, 3}, {1, 4}, {2, 4}};

	/** The bottom-left corner, on the ground */
	public final BlockPos base;
	/** The way the frame runs, left to right */
	public final Direction along;
	/** The way she faces it from where she stands */
	public final Direction facing;
	/** Where she stands while she builds */
	public final BlockPos stand;

	Portal(BlockPos base, Direction along, Direction facing, BlockPos stand) {
		this.base = base;
		this.along = along;
		this.facing = facing;
		this.stand = stand;
	}

	public BlockPos at(int i, int j) {
		return base.relative(along, i).above(j);
	}

	/** Inside, at the bottom: where she walks in */
	public BlockPos inside() {
		return at(1, 1);
	}

	/** One block to put down: what goes there, what she clicks, and which face of it */
	public record Placement(BlockPos pos, boolean obsidian, BlockPos support, Direction face) {}

	/** In an order where every block has something to be placed against */
	public List<Placement> placements() {
		List<Placement> out = new ArrayList<>();
		// The bottom row, on the ground
		for (int i = 0; i < 4; i++) out.add(new Placement(at(i, 0), i == 1 || i == 2, at(i, 0).below(), Direction.UP));
		// The sides, stacked up from it
		for (int i : new int[] {0, 3}) {
			for (int j = 1; j <= 3; j++) out.add(new Placement(at(i, j), true, at(i, j - 1), Direction.UP));
		}
		// The top corners, then the top between them
		out.add(new Placement(at(0, 4), false, at(0, 3), Direction.UP));
		out.add(new Placement(at(3, 4), false, at(3, 3), Direction.UP));
		out.add(new Placement(at(1, 4), true, at(0, 4), along));
		out.add(new Placement(at(2, 4), true, at(3, 4), along.getOpposite()));
		return out;
	}

	/** How many of the 10 obsidian blocks are already in place */
	public static int obsidianPlaced(Level level, Portal portal) {
		if (portal == null) return 0;
		int n = 0;
		for (int[] spot : OBSIDIAN_SPOTS) if (level.getBlockState(portal.at(spot[0], spot[1])).is(Blocks.OBSIDIAN)) n++;
		return n;
	}

	public boolean lit(Level level) {
		return level.getBlockState(inside()).is(Blocks.NETHER_PORTAL);
	}

	/** Still a good spot: nothing got built in the way */
	public boolean stillFits(Level level) {
		return fits(level, base, along, facing) != null;
	}

	/** A flat spot near `feet` with room for the frame and for her in front of it, or null */
	public static Portal find(Level level, BlockPos feet) {
		Portal best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dy : new int[] {0, -1, 1, -2, 2}) {
			for (int dx = -7; dx <= 7; dx++) {
				for (int dz = -7; dz <= 7; dz++) {
					BlockPos base = feet.offset(dx, dy, dz);
					for (Direction along : new Direction[] {Direction.EAST, Direction.SOUTH}) {
						for (Direction facing : new Direction[] {along.getClockWise(), along.getCounterClockWise()}) {
							Portal p = fits(level, base, along, facing);
							if (p == null) continue;
							double d = p.stand.distSqr(feet);
							if (d < bestDistance) {
								bestDistance = d;
								best = p;
							}
						}
					}
				}
			}
			if (best != null) return best;
		}
		return null;
	}

	private static Portal fits(Level level, BlockPos base, Direction along, Direction facing) {
		for (int i = 0; i < 4; i++) {
			BlockPos ground = base.relative(along, i).below();
			if (!level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) return null;
			for (int j = 0; j < 5; j++) {
				BlockState here = level.getBlockState(base.relative(along, i).above(j));
				boolean free = here.canBeReplaced() && here.getFluidState().isEmpty();
				boolean already = here.is(Blocks.OBSIDIAN) || here.is(Blocks.NETHER_PORTAL)
					|| (((i == 0 || i == 3) && (j == 0 || j == 4)) && !here.canBeReplaced());
				if (!free && !already) return null;
			}
		}
		// She stands in front of the left half of the inside, two blocks out, facing back at it
		BlockPos stand = base.relative(along, 1).relative(facing.getOpposite(), 2);
		BlockPos standGround = stand.below();
		if (!level.getBlockState(standGround).isFaceSturdy(level, standGround, Direction.UP)) return null;
		for (BlockPos p : new BlockPos[] {stand, stand.above(), stand.relative(facing), stand.relative(facing).above()}) {
			BlockState s = level.getBlockState(p);
			if (!s.canBeReplaced() || !s.getFluidState().isEmpty()) return null;
		}
		// The step between her and the portal needs ground too
		BlockPos between = stand.relative(facing).below();
		if (!level.getBlockState(between).isFaceSturdy(level, between, Direction.UP)) return null;
		return new Portal(base, along, facing, stand);
	}
}
