package com.nunezvalen.capricraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.util.Random;

/**
 * Capri's own mouse. Every click she makes in a menu goes through here: her cursor glides to the
 * slot, rests a moment, and clicks, at about the pace of a person. The player's real mouse is left
 * alone; hers is drawn on top of the menu in her color, carrying whatever she picked up.
 */
public final class Hand {
	private static final Field LEFT_POS = field("leftPos");
	private static final Field TOP_POS = field("topPos");

	private final Random random = new Random();

	// Where her cursor is, in GUI coordinates, and where it's going
	private double x = -1, y = -1;
	private double fromX, fromY, toX, toY;
	private int moveTick = 0, moveTicks = 0;
	private int restTicks = 0;

	// The click she's on her way to make
	private int slot = -1, button = 0;
	private ContainerInput type = null;
	private int afterClick = 0;

	// The ring that spreads out from a click
	private static final int RIPPLE_TICKS = 8;
	private int ripple = 0;

	/** True while she's still moving to, or just finished, a click: the task waits for her */
	public boolean busy() {
		return type != null || afterClick > 0;
	}

	/** Queue one click; she makes it once her cursor gets there */
	public void click(int slot, int button, ContainerInput type) {
		this.slot = slot;
		this.button = button;
		this.type = type;
		this.moveTicks = 0;
	}

	/** A short pause, as if she were looking at the menu */
	public void pause(int ticks) {
		afterClick = Math.max(afterClick, ticks);
	}

	/** Forget everything: the menu closed */
	public void reset() {
		type = null;
		afterClick = 0;
		x = y = -1;
	}

	public void tick(Minecraft mc) {
		if (ripple > 0) ripple--;
		if (type == null) {
			if (afterClick > 0) afterClick--;
			return;
		}
		AbstractContainerMenu menu = mc.player == null ? null : mc.player.containerMenu;
		if (menu == null || slot >= menu.slots.size()) {
			type = null;
			return;
		}
		Screen screen = mc.gui.screen();
		if (!(screen instanceof AbstractContainerScreen<?> gui) || gui.getMenu() != menu) {
			// No menu on screen to show it in: just do it
			Inv.click(mc, menu, slot, button, type);
			type = null;
			afterClick = 2;
			return;
		}
		if (moveTicks == 0) {
			// Set off toward the slot: a little off center, and slower the farther it is
			Slot target = menu.getSlot(slot);
			double tx = left(gui) + target.x + 8 + random.nextInt(5) - 2;
			double ty = top(gui) + target.y + 8 + random.nextInt(5) - 2;
			if (x < 0) {
				x = gui.width / 2.0 + random.nextInt(40) - 20;
				y = gui.height - 30;
			}
			fromX = x; fromY = y; toX = tx; toY = ty;
			double distance = Math.hypot(tx - x, ty - y);
			moveTicks = (int) Math.max(4, Math.min(16, 4 + distance / 12));
			moveTick = 0;
			restTicks = 2 + random.nextInt(3);
			return;
		}
		if (moveTick < moveTicks) {
			moveTick++;
			double t = ease(moveTick / (double) moveTicks);
			x = fromX + (toX - fromX) * t;
			y = fromY + (toY - fromY) * t;
			return;
		}
		if (restTicks-- > 0) return;
		Inv.click(mc, menu, slot, button, type);
		type = null;
		afterClick = 2 + random.nextInt(3);
		ripple = RIPPLE_TICKS;
	}

	/** Drawn after the menu: her cursor, and what she's carrying */
	public void render(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		if (x < 0 || !(screen instanceof AbstractContainerScreen<?> gui)) return;
		double px = x, py = y;
		if (type != null && moveTicks > 0 && moveTick < moveTicks) {
			double t = ease(Math.min(1, (moveTick + partial) / moveTicks));
			px = fromX + (toX - fromX) * t;
			py = fromY + (toY - fromY) * t;
		}
		int cx = (int) Math.round(px), cy = (int) Math.round(py);
		g.nextStratum();
		ItemStack carried = gui.getMenu().getCarried();
		if (!carried.isEmpty()) g.item(carried, cx - 8, cy - 8);
		if (ripple > 0) drawRipple(g, cx, cy, RIPPLE_TICKS - ripple + partial);
		drawLayer(g, cx, cy, GLOW, 0x4000F0FF);
		drawLayer(g, cx, cy, EDGE, 0xFFFFFFFF);
		drawLayer(g, cx, cy, ARROW, 0xFF00F0FF);
		drawBadge(g, cx, cy);
	}

	// Her cursor, the same one she uses on the desktop (ScannerView): a cyan neon arrow with a white
	// edge and a glow. The shape is the SVG path M3 3 L10.07 19.97 L12.58 12.58 L19.97 10.07 Z,
	// turned into pixels once.
	private static final double SCALE = 0.75;
	private static final int SIZE = 20;
	private static final int PAD = 2; // room for the glow above and left of the tip
	private static final boolean[][] ARROW = new boolean[SIZE][SIZE];
	private static final boolean[][] EDGE;
	private static final boolean[][] GLOW;

	static {
		double[][] path = {{3, 3}, {10.07, 19.97}, {12.58, 12.58}, {19.97, 10.07}};
		for (int py = 0; py < SIZE; py++) {
			for (int px = 0; px < SIZE; px++) {
				ARROW[py][px] = inside(path, 3 + (px - PAD + 0.5) / SCALE, 3 + (py - PAD + 0.5) / SCALE);
			}
		}
		EDGE = grow(ARROW, 1);
		GLOW = grow(ARROW, 2);
	}

	private static boolean inside(double[][] poly, double x, double y) {
		boolean in = false;
		for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
			if ((poly[i][1] > y) != (poly[j][1] > y)
				&& x < (poly[j][0] - poly[i][0]) * (y - poly[i][1]) / (poly[j][1] - poly[i][1]) + poly[i][0]) in = !in;
		}
		return in;
	}

	/** The shape made `by` pixels fatter on every side */
	private static boolean[][] grow(boolean[][] shape, int by) {
		boolean[][] out = new boolean[SIZE][SIZE];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				if (!shape[y][x]) continue;
				for (int dy = -by; dy <= by; dy++) {
					for (int dx = -by; dx <= by; dx++) {
						int nx = x + dx, ny = y + dy;
						if (nx >= 0 && ny >= 0 && nx < SIZE && ny < SIZE && Math.abs(dx) + Math.abs(dy) <= by + 1) out[ny][nx] = true;
					}
				}
			}
		}
		return out;
	}

	/** One color over every pixel of a shape, in runs along each row; the tip sits at (x, y) */
	private static void drawLayer(GuiGraphicsExtractor g, int x, int y, boolean[][] shape, int color) {
		for (int row = 0; row < SIZE; row++) {
			int start = -1;
			for (int col = 0; col <= SIZE; col++) {
				boolean on = col < SIZE && shape[row][col];
				if (on && start < 0) start = col;
				if (!on && start >= 0) {
					g.fill(x + start - PAD, y + row - PAD, x + col - PAD, y + row - PAD + 1, color);
					start = -1;
				}
			}
		}
	}

	/** The cyan ring that spreads out where she clicked */
	private static void drawRipple(GuiGraphicsExtractor g, int x, int y, float t) {
		double radius = 2 + t * 1.4;
		int alpha = (int) (0xE0 * (1 - t / RIPPLE_TICKS));
		if (alpha <= 0) return;
		int color = (alpha << 24) | 0x22D3EE;
		int points = 28;
		for (int i = 0; i < points; i++) {
			double a = i * Math.PI * 2 / points;
			int px = x + (int) Math.round(Math.cos(a) * radius);
			int py = y + (int) Math.round(Math.sin(a) * radius);
			g.fill(px, py, px + 1, py + 1, color);
		}
	}

	/** The little "CAPRI ⚡" tag next to the arrow, gently pulsing */
	private static void drawBadge(GuiGraphicsExtractor g, int x, int y) {
		var font = Minecraft.getInstance().font;
		String label = "CAPRI ⚡";
		int bx = x + 13, by = y - 5;
		int w = 8 + font.width(label) + 3;
		int h = 11;
		double pulse = 0.65 + 0.35 * Math.sin(System.currentTimeMillis() / 300.0);
		int border = ((int) (0xCC * pulse) << 24) | 0x22D3EE;
		g.fill(bx - 1, by - 1, bx + w + 1, by + h + 1, border);
		g.fill(bx, by, bx + w, by + h, 0xF2090B14);
		g.fill(bx + 3, by + 4, bx + 6, by + 7, 0xFF22D3EE);
		g.text(font, label, bx + 8, by + 2, 0xFF67E8F9, false);
	}

	private static double ease(double t) {
		return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
	}

	private static Field field(String name) {
		try {
			Field f = AbstractContainerScreen.class.getDeclaredField(name);
			f.setAccessible(true);
			return f;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Capri Craft: AbstractContainerScreen." + name + " is missing", e);
		}
	}

	private static int left(AbstractContainerScreen<?> gui) {
		try {
			return LEFT_POS.getInt(gui);
		} catch (IllegalAccessException e) {
			return (gui.width - 176) / 2;
		}
	}

	private static int top(AbstractContainerScreen<?> gui) {
		try {
			return TOP_POS.getInt(gui);
		} catch (IllegalAccessException e) {
			return (gui.height - 166) / 2;
		}
	}
}
