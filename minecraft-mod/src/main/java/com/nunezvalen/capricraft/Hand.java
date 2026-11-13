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
		drawArrow(g, cx, cy);
	}

	/** A small arrow, pink with a dark edge, its tip at (x, y) */
	private static void drawArrow(GuiGraphicsExtractor g, int x, int y) {
		int edge = 0xFF1A0A14;
		int fill = 0xFFFF6FB5;
		for (int row = 0; row < 11; row++) {
			int width = row < 8 ? row + 1 : 3;
			int start = row < 8 ? 0 : row - 6;
			g.fill(x + start - 1, y + row - 1, x + start + width + 1, y + row + 2, edge);
		}
		for (int row = 0; row < 11; row++) {
			int width = row < 8 ? row + 1 : 3;
			int start = row < 8 ? 0 : row - 6;
			g.fill(x + start, y + row, x + start + width, y + row + 1, fill);
		}
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
