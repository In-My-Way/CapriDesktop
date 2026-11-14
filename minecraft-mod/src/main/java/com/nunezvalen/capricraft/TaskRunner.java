package com.nunezvalen.capricraft;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import com.mojang.blaze3d.platform.InputConstants;
import baritone.api.Settings;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;

/**
 * Turns "get me diamonds" into steps and runs them on the client thread, one tick at a time.
 *
 * Every time a step ends she plans again from what the player has right now: no pickaxe, so a
 * wooden one first; no planks for it, so logs before that. Anything already in the inventory is a
 * step she skips. Walking and mining are Baritone's job; crafting and smelting she does herself by
 * moving items in the menus, the same clicks a player would make.
 */
public final class TaskRunner {
	// What counts as what
	static final Predicate<ItemStack> LOGS = s -> s.is(ItemTags.LOGS);
	static final Predicate<ItemStack> PLANKS = s -> s.is(ItemTags.PLANKS);
	static final Predicate<ItemStack> STICKS = s -> s.is(Items.STICK);
	static final Predicate<ItemStack> STONE = s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.BLACKSTONE);
	static final Predicate<ItemStack> COAL = s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
	static final Block[] LOG_BLOCKS = {
		Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG, Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG,
		Blocks.DARK_OAK_LOG, Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG, Blocks.PALE_OAK_LOG
	};

	// Shapes, as slots of a 3x3 crafting table (1-9, row by row) or of the 2x2 inventory grid (1-2 top, 3-4 bottom)
	static final int[] PICKAXE_HEAD = {1, 2, 3};
	static final int[] PICKAXE_HANDLE = {5, 8};
	static final int[] FURNACE_RING = {1, 2, 3, 4, 6, 7, 8, 9};
	static final int[] SWORD_BLADE = {2, 5};
	static final int[] SWORD_HANDLE = {8};

	static final Predicate<ItemStack> RAW_MEAT = s -> s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.MUTTON) || s.is(Items.CHICKEN) || s.is(Items.RABBIT);
	static final Predicate<ItemStack> COOKED_FOOD = s -> s.is(Items.COOKED_BEEF) || s.is(Items.COOKED_PORKCHOP) || s.is(Items.COOKED_MUTTON) || s.is(Items.COOKED_CHICKEN) || s.is(Items.COOKED_RABBIT) || s.is(Items.BREAD) || s.is(Items.BAKED_POTATO);

	/** The Nether portal she's building, once she picked a spot for it */
	private Portal portal = null;

	private final ConcurrentLinkedQueue<JsonObject> inbox = new ConcurrentLinkedQueue<>();
	private final Deque<JsonObject> events = new ArrayDeque<>();
	private long nextEventId = 1;

	// The task
	private String action = null;   // get | mine | goto
	private String target = null;   // item or block id
	private int amount = 0;
	private String step = "idle";
	private Step current = null;
	private long startedAt = 0;
	private volatile String statusSnapshot = "{\"state\":\"idle\"}";
	private volatile boolean inWorld = false;

	// Where she left her crafting table and furnace
	private BlockPos tablePos = null;
	private BlockPos furnacePos = null;
	private boolean stopKeyWasDown = false;

	/** Her mouse in the menus */
	final Hand hand = new Hand();
	private final java.util.Random random = new java.util.Random();

	// ---- Called from the bridge thread ----

	public void submit(JsonObject task) {
		inbox.add(task);
	}

	public String pingJson() {
		return "{\"ok\":true,\"version\":\"" + CapriCraftClient.VERSION + "\",\"inWorld\":" + inWorld + "}";
	}

	public synchronized String statusJson(long since) {
		JsonObject out = com.google.gson.JsonParser.parseString(statusSnapshot).getAsJsonObject();
		JsonArray list = new JsonArray();
		for (JsonObject e : events) if (e.get("id").getAsLong() > since) list.add(e);
		out.add("events", list);
		return out.toString();
	}

	// ---- Client thread ----

	public void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		inWorld = player != null && mc.level != null;

		JsonObject task;
		while ((task = inbox.poll()) != null) accept(mc, task);

		if (player == null) {
			if (action != null) end("failed", "not_in_world");
			snapshot();
			return;
		}

		// K (with no menu open) gives the player their hands back
		boolean stopDown = mc.gui.screen() == null && InputConstants.isKeyDown(InputConstants.KEY_K);
		if (stopDown && !stopKeyWasDown && action != null) {
			end("stopped", "player");
		}
		stopKeyWasDown = stopDown;

		if (action != null) {
			try {
				if (player.isDeadOrDying()) {
					end("failed", "died");
				} else if (player.getHealth() <= 4f) {
					// Two hearts left: out to the title screen before she loses the player's things
					end("fled", "low_health");
					mc.schedule(() -> exitToTitle(mc));
				} else {
					// A pause menu that opened on its own (the window lost focus) would freeze her; one the
					// player opened on purpose stays
					if (mc.gui.screen() instanceof PauseScreen && !mc.isWindowActive()) mc.gui.setScreen(null);
					hand.tick(mc);
					if (!hand.busy() && !fight(mc, player) && !eat(mc, player)) run(mc);
				}
			} catch (RuntimeException e) {
				CapriCraftClient.LOG.error("[CapriCraft] Step failed", e);
				end("failed", "error");
			}
		}
		snapshot();
	}

	/** Save and quit to the title screen, the same three steps the game's own death screen takes */
	private static void exitToTitle(Minecraft mc) {
		if (mc.level != null) mc.level.disconnect(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
		mc.disconnectWithSavingScreen();
		mc.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
	}

	// ---- Interruptions: a fight, a meal. While one lasts the task waits, and then she plans again ----

	private Monster foe = null;
	private boolean fighting = false;
	private int calmTicks = 0;
	private int fightTicks = 0;

	/** True while she's fighting: a monster close and coming for her gets her full attention */
	private boolean fight(Minecraft mc, LocalPlayer player) {
		Monster nearest = null;
		double best = 6.0 * 6.0;
		for (Monster m : player.level().getEntitiesOfClass(Monster.class, player.getBoundingBox().inflate(6.0))) {
			if (!m.isAlive() || !player.hasLineOfSight(m)) continue;
			// Endermen and the like leave her alone unless she starts it
			if (m instanceof net.minecraft.world.entity.NeutralMob && m.getTarget() != player) continue;
			double d = player.distanceToSqr(m);
			if (d < best) {
				best = d;
				nearest = m;
			}
		}
		if (nearest == null) {
			if (!fighting) return false;
			if (++calmTicks < 30) return true;
			// All clear: back to work
			fighting = false;
			foe = null;
			baritone().getPathingBehavior().cancelEverything();
			return false;
		}
		calmTicks = 0;
		if (!fighting) {
			fighting = true;
			fightTicks = 0;
			baritone().getPathingBehavior().cancelEverything();
			hand.reset();
			if (player.containerMenu != player.inventoryMenu) player.closeContainer();
			current = null; // the step starts over when she's done
			event("fight", BuiltInRegistries.ENTITY_TYPE.getKey(nearest.getType()).getPath());
		}
		foe = nearest;
		fightTicks++;
		Inv.holdBestSword(mc);
		Inv.lookTowards(player, foe.getEyePosition(), 35f);
		float distance = player.distanceTo(foe);
		if (distance > 3.0f) {
			// Close in on it (a skeleton won't come to her)
			if (fightTicks % 10 == 1) baritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(foe.blockPosition(), 1));
		} else {
			baritone().getPathingBehavior().cancelEverything();
			if (player.getAttackStrengthScale(0f) >= 0.95f) {
				mc.gameMode.attack(player, foe);
				Inv.swing(player);
			}
		}
		return true;
	}

	private int eatTicks = -1;
	private int foodBefore = 0;

	/** True while she's eating: hungry, or hurt and not full, with food in her pocket */
	private boolean eat(Minecraft mc, LocalPlayer player) {
		if (eatTicks >= 0) {
			eatTicks++;
			boolean ate = Inv.count(player, Inv::edible) < foodBefore;
			if (ate || eatTicks > 60) {
				mc.options.keyUse.setDown(false);
				eatTicks = -1;
				if (ate) event("ate", "food");
				return false;
			}
			mc.options.keyUse.setDown(true);
			return true;
		}
		int food = player.getFoodData().getFoodLevel();
		boolean hungry = food <= 14 || (food < 20 && player.getHealth() < 12f);
		if (!hungry || mc.gui.screen() != null) return false;
		// The good stuff first; raw meat only when she's really hungry, since it's what she cooks
		boolean holding = Inv.hold(mc, COOKED_FOOD);
		if (!holding && food <= 8) holding = Inv.hold(mc, Inv::edible);
		if (!holding && food <= 14) holding = Inv.hold(mc, s -> Inv.edible(s) && !RAW_MEAT.test(s));
		if (!holding) return false;
		baritone().getPathingBehavior().cancelEverything();
		current = null;
		foodBefore = Inv.count(player, Inv::edible);
		eatTicks = 0;
		mc.options.keyUse.setDown(true);
		mc.gameMode.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND);
		return true;
	}

	private void releaseKeys(Minecraft mc) {
		mc.options.keyUse.setDown(false);
		mc.options.keyUp.setDown(false);
		mc.options.keyJump.setDown(false);
		eatTicks = -1;
		fighting = false;
		foe = null;
	}

	// Baritone settings she changes while she plays, and what they were before
	private final Map<Settings.Setting<Object>, Object> savedSettings = new HashMap<>();

	@SuppressWarnings("unchecked")
	private <T> void setting(Settings.Setting<T> s, T value) {
		Settings.Setting<Object> any = (Settings.Setting<Object>) (Object) s;
		savedSettings.putIfAbsent(any, any.value);
		s.value = value;
	}

	/** Plays like a person: no boxes through walls, her head turns where she looks, quiet chat */
	private void playLikeAPerson() {
		Settings s = BaritoneAPI.getSettings();
		setting(s.allowInventory, true);
		setting(s.renderGoal, false);
		setting(s.renderPath, false);
		setting(s.renderSelectionBoxes, false);
		setting(s.renderGoalXZBeacon, false);
		setting(s.renderCachedChunks, false);
		setting(s.chatDebug, false);
		setting(s.freeLook, false);
		setting(s.smoothLook, true);
		setting(s.legitMine, false);
		// She keeps playing while the player looks at another window
		Minecraft mc = Minecraft.getInstance();
		if (pauseOnLostFocusBefore == null) pauseOnLostFocusBefore = mc.options.pauseOnLostFocus;
		mc.options.pauseOnLostFocus = false;
	}

	private Boolean pauseOnLostFocusBefore = null;

	private void restoreSettings() {
		savedSettings.forEach((s, v) -> s.value = v);
		savedSettings.clear();
		if (pauseOnLostFocusBefore != null) {
			Minecraft.getInstance().options.pauseOnLostFocus = pauseOnLostFocusBefore;
			pauseOnLostFocusBefore = null;
		}
	}

	private void accept(Minecraft mc, JsonObject task) {
		String what = task.has("action") ? task.get("action").getAsString() : "";
		if (what.equals("stop")) {
			if (action != null) end("stopped", "capri");
			return;
		}
		if (mc.player == null) {
			event("failed", "not_in_world");
			return;
		}
		if (mc.player.isDeadOrDying()) {
			// On the death screen: nothing she can do until the player respawns
			event("failed", "player_dead");
			return;
		}
		if (action != null) end("stopped", "replaced");
		action = what;
		target = task.has("item") ? task.get("item").getAsString() : task.has("block") ? task.get("block").getAsString() : null;
		if (what.equals("nether")) {
			// "Take me to the Nether" is a get with the trip itself as the thing to get
			action = "get";
			target = "nether";
		}
		amount = task.has("count") ? Math.max(1, Math.min(task.get("count").getAsInt(), 640)) : 1;
		if (what.equals("goto")) {
			int x = task.get("x").getAsInt();
			int z = task.get("z").getAsInt();
			if (task.has("y")) baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(x, task.get("y").getAsInt(), z));
			else baritone().getCustomGoalProcess().setGoalAndPath(new GoalXZ(x, z));
		}
		startedAt = System.currentTimeMillis();
		current = null;
		step = "planning";
		playLikeAPerson();
		hand.reset();
		event("started", what + ":" + target + ":" + amount);
		say(mc, "Capri took over: " + what + " " + (target == null ? "" : target) + " (press K to take back control)");
	}

	private void run(Minecraft mc) {
		if (current != null) {
			if (!current.tick(mc)) return;
			current = null;
		}
		if (action == null) return;
		if (action.equals("goto")) {
			if (!baritone().getCustomGoalProcess().isActive() && !baritone().getPathingBehavior().isPathing()) end("done", "arrived");
			return;
		}
		Step next = action.equals("mine") ? planMine(mc.player) : planGet(mc.player, target, amount);
		if (action == null) return; // the plan itself ended the task
		if (next == null) {
			end("done", target + ":" + amount);
			return;
		}
		current = next;
		step = next.name();
	}

	private void end(String how, String detail) {
		baritone().getPathingBehavior().cancelEverything();
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
		hand.reset();
		releaseKeys(mc);
		restoreSettings();
		event(how, detail);
		say(mc, how.equals("done") ? "Done! Control is yours again."
			: how.equals("stopped") ? "Stopped. Control is yours again."
			: how.equals("fled") ? "Two hearts left! Leaving to the title screen before we lose everything."
			: "I couldn't finish (" + detail + "). Control is yours again.");
		action = null;
		target = null;
		current = null;
		step = "idle";
	}

	// ---- Planning: the first thing missing for what was asked ----

	private Step planMine(LocalPlayer player) {
		Block block = blockById(target);
		if (block == null) {
			end("failed", "unknown_block:" + target);
			return null;
		}
		Item drop = block.asItem();
		if (Inv.count(player, drop) >= amount) return null;
		return new MineStep("mine " + target, amount, s -> s.is(drop), block);
	}

	private Step planGet(LocalPlayer player, String item, int n) {
		// On a long trip, no food and getting hungry: hunt first, if there's anything to hunt nearby
		if (LONG_TRIPS.contains(item)) {
			Step food = huntIfStarving(player);
			if (food != null) return food;
		}
		switch (item) {
			case "diamond": {
				if (Inv.count(player, Items.DIAMOND) >= n) return null;
				Step tool = needPickaxe(player, 2);
				if (tool != null) return tool;
				if ((tool = needSword(player, 1)) != null) return tool;
				return new MineStep("mine diamonds", n, s -> s.is(Items.DIAMOND), Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
			}
			case "diamond_pickaxe":
				return needPickaxe(player, 3);
			case "stone_sword":
				return needSword(player, 1);
			case "iron_sword":
				return needSword(player, 2);
			case "iron_helmet":
				return needArmor(player, ARMOR[2]);
			case "iron_chestplate":
				return needArmor(player, ARMOR[0]);
			case "iron_leggings":
				return needArmor(player, ARMOR[1]);
			case "iron_boots":
				return needArmor(player, ARMOR[3]);
			case "iron_armor": {
				for (ArmorPiece piece : ARMOR) {
					Step s = needArmor(player, piece);
					if (s != null) return s;
				}
				return null;
			}
			case "food":
				return needFood(player, n);
			case "flint":
				return needFlint(player, n);
			case "flint_and_steel":
				return needFlintAndSteel(player);
			case "obsidian":
				return needObsidian(player, n);
			case "nether":
				return planNether(player);
			case "iron_ingot":
				return needIron(player, n);
			case "raw_iron": {
				if (Inv.count(player, Items.RAW_IRON) >= n) return null;
				Step tool = needPickaxe(player, 1);
				if (tool != null) return tool;
				return new MineStep("mine iron", n, s -> s.is(Items.RAW_IRON), Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
			}
			case "coal": {
				if (Inv.count(player, Items.COAL) >= n) return null;
				Step tool = needPickaxe(player, 0);
				if (tool != null) return tool;
				return new MineStep("mine coal", n, s -> s.is(Items.COAL), Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
			}
			case "iron_pickaxe":
				return Inv.bestPickaxe(player) >= 2 ? null : needPickaxe(player, 2);
			case "stone_pickaxe":
				return Inv.bestPickaxe(player) >= 1 ? null : needPickaxe(player, 1);
			case "wooden_pickaxe":
				return Inv.bestPickaxe(player) >= 0 ? null : needPickaxe(player, 0);
			case "crafting_table":
				if (Inv.count(player, Items.CRAFTING_TABLE) >= n) return null;
				return needTableItem(player, 0);
			case "furnace":
				if (Inv.count(player, Items.FURNACE) >= n) return null;
				return needFurnaceItem(player);
			case "stick":
				return needSticks(player, n);
			case "planks":
				return needPlanks(player, n, 0);
			case "log":
				return needLogs(player, n);
			case "cobblestone":
				return needStone(player, n);
			default: {
				// Anything else that is a block: dig it up
				Block block = blockById(item);
				if (block == null || block == Blocks.AIR) {
					end("failed", "unknown_item:" + item);
					return null;
				}
				Item drop = block.asItem();
				if (Inv.count(player, drop) >= n) return null;
				return new MineStep("mine " + item, n, s -> s.is(drop), block);
			}
		}
	}

	/** A pickaxe of at least `tier` (0 wood, 1 stone, 2 iron, 3 diamond), and whatever it takes to make it */
	private Step needPickaxe(LocalPlayer player, int tier) {
		int best = Inv.bestPickaxe(player);
		if (best >= tier) return null;
		if (tier >= 3) {
			if (best < 2) return needPickaxe(player, 2);
			if (Inv.count(player, Items.DIAMOND) < 3) {
				Step s = needSword(player, 1);
				if (s != null) return s;
				return new MineStep("mine diamonds", 3, x -> x.is(Items.DIAMOND), Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
			}
			Step s = needSticks(player, 2);
			if (s != null) return s;
			if ((s = needTable(player, 0)) != null) return s;
			return new CraftStep("craft diamond pickaxe", Items.DIAMOND_PICKAXE, true,
				new Ingredient(x -> x.is(Items.DIAMOND), PICKAXE_HEAD), new Ingredient(STICKS, PICKAXE_HANDLE));
		}
		if (tier >= 2 && best < 2) {
			// Iron first needs stone, which needs wood: climb one rung at a time
			if (best < 1) return needPickaxe(player, 1);
			Step s = needIron(player, 3);
			if (s != null) return s;
			if ((s = needSticks(player, 2)) != null) return s;
			if ((s = needTable(player, 0)) != null) return s;
			return new CraftStep("craft iron pickaxe", Items.IRON_PICKAXE, true,
				new Ingredient(x -> x.is(Items.IRON_INGOT), PICKAXE_HEAD), new Ingredient(STICKS, PICKAXE_HANDLE));
		}
		if (tier >= 1 && best < 1) {
			if (best < 0) return needPickaxe(player, 0);
			Step s = needStone(player, 3);
			if (s != null) return s;
			if ((s = needSticks(player, 2)) != null) return s;
			if ((s = needTable(player, 0)) != null) return s;
			return new CraftStep("craft stone pickaxe", Items.STONE_PICKAXE, true,
				new Ingredient(STONE, PICKAXE_HEAD), new Ingredient(STICKS, PICKAXE_HANDLE));
		}
		Step s = needPlanks(player, 3, 0);
		if (s != null) return s;
		if ((s = needSticks(player, 2)) != null) return s;
		if ((s = needTable(player, 3)) != null) return s;
		return new CraftStep("craft wooden pickaxe", Items.WOODEN_PICKAXE, true,
			new Ingredient(PLANKS, PICKAXE_HEAD), new Ingredient(STICKS, PICKAXE_HANDLE));
	}

	private Step needIron(LocalPlayer player, int n) {
		int have = Inv.count(player, Items.IRON_INGOT);
		if (have >= n) return null;
		int missing = n - have;
		int raw = Inv.count(player, Items.RAW_IRON);
		if (raw < missing) {
			Step tool = needPickaxe(player, 1);
			if (tool != null) return tool;
			return new MineStep("mine iron", missing, s -> s.is(Items.RAW_IRON), Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
		}
		Step s = needSmelting(player, missing);
		if (s != null) return s;
		return new SmeltStep("smelt iron", x -> x.is(Items.RAW_IRON), x -> x.is(Items.IRON_INGOT), "minecraft:iron_ingot", missing);
	}

	/** A furnace to smelt `items` things in, and something to burn */
	private Step needSmelting(LocalPlayer player, int items) {
		if (!near(player, furnacePos, Blocks.FURNACE) && Inv.count(player, Items.FURNACE) == 0) {
			Step s = needFurnaceItem(player);
			if (s != null) return s;
		}
		int planksForFuel = (int) Math.ceil(items / 1.5);
		if (Inv.count(player, COAL) == 0 && Inv.count(player, PLANKS) < planksForFuel) {
			return needPlanks(player, planksForFuel, 0);
		}
		return null;
	}

	// ---- Survival: a sword, armor, food ----

	/** What's worth bringing food for: anything that takes her underground for a while */
	static final java.util.Set<String> LONG_TRIPS = java.util.Set.of("diamond", "diamond_pickaxe", "obsidian", "nether",
		"iron_armor", "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots", "iron_ingot", "iron_sword", "iron_pickaxe");

	/** A sword of at least `tier` (1 stone, 2 iron) */
	private Step needSword(LocalPlayer player, int tier) {
		if (Inv.bestSword(player) >= tier) return null;
		if (tier >= 2) {
			Step s = needIron(player, 2);
			if (s != null) return s;
			if ((s = needSticks(player, 1)) != null) return s;
			if ((s = needTable(player, 0)) != null) return s;
			return new CraftStep("craft iron sword", Items.IRON_SWORD, true, new Ingredient(x -> x.is(Items.IRON_INGOT), SWORD_BLADE), new Ingredient(STICKS, SWORD_HANDLE));
		}
		Step s = needPickaxe(player, 0);
		if (s != null) return s;
		if ((s = needStone(player, 2)) != null) return s;
		if ((s = needSticks(player, 1)) != null) return s;
		if ((s = needTable(player, 0)) != null) return s;
		return new CraftStep("craft stone sword", Items.STONE_SWORD, true, new Ingredient(STONE, SWORD_BLADE), new Ingredient(STICKS, SWORD_HANDLE));
	}

	record ArmorPiece(Item item, net.minecraft.world.entity.EquipmentSlot slot, int iron, int[] shape, String name) {}

	static final ArmorPiece[] ARMOR = {
		new ArmorPiece(Items.IRON_CHESTPLATE, net.minecraft.world.entity.EquipmentSlot.CHEST, 8, new int[] {1, 3, 4, 5, 6, 7, 8, 9}, "chestplate"),
		new ArmorPiece(Items.IRON_LEGGINGS, net.minecraft.world.entity.EquipmentSlot.LEGS, 7, new int[] {1, 2, 3, 4, 6, 7, 9}, "leggings"),
		new ArmorPiece(Items.IRON_HELMET, net.minecraft.world.entity.EquipmentSlot.HEAD, 5, new int[] {1, 2, 3, 4, 6}, "helmet"),
		new ArmorPiece(Items.IRON_BOOTS, net.minecraft.world.entity.EquipmentSlot.FEET, 4, new int[] {4, 6, 7, 9}, "boots")
	};

	/** One piece of iron armor, worn: anything already in that slot counts */
	private Step needArmor(LocalPlayer player, ArmorPiece piece) {
		if (!player.getItemBySlot(piece.slot()).isEmpty()) return null;
		if (Inv.count(player, piece.item()) > 0) return new EquipStep("put on " + piece.name(), piece.item(), piece.slot());
		Step s = needIron(player, piece.iron());
		if (s != null) return s;
		if ((s = needTable(player, 0)) != null) return s;
		return new CraftStep("craft iron " + piece.name(), piece.item(), true, new Ingredient(x -> x.is(Items.IRON_INGOT), piece.shape()));
	}

	/** `n` cooked meals: hunt, then cook */
	private Step needFood(LocalPlayer player, int n) {
		int have = Inv.count(player, COOKED_FOOD);
		if (have >= n) return null;
		int missing = n - have;
		if (Inv.count(player, RAW_MEAT) < missing) return new HuntStep("hunt", Inv.count(player, RAW_MEAT) + missing, true);
		Step s = needSmelting(player, missing);
		if (s != null) return s;
		return new SmeltStep("cook food", RAW_MEAT, COOKED_FOOD, "food", missing);
	}

	private long noHuntUntil = 0;

	/** Hungry, nothing to eat, and an animal nearby: a short hunt */
	private Step huntIfStarving(LocalPlayer player) {
		if (player.getFoodData().getFoodLevel() > 10 || Inv.count(player, Inv::edible) > 0) return null;
		if (System.currentTimeMillis() < noHuntUntil || HuntStep.nearestPrey(player, 48) == null) return null;
		return new HuntStep("hunt", Inv.count(player, RAW_MEAT) + 3, false);
	}

	// ---- The Nether: diamond pickaxe, flint and steel, obsidian, a portal ----

	private Step needFlint(LocalPlayer player, int n) {
		if (Inv.count(player, Items.FLINT) >= n) return null;
		// Gravel drops flint now and then: dig until it does
		return new MineStep("dig gravel", n, x -> x.is(Items.FLINT), 0, Blocks.GRAVEL);
	}

	private Step needFlintAndSteel(LocalPlayer player) {
		if (Inv.count(player, Items.FLINT_AND_STEEL) > 0) return null;
		Step s = needIron(player, 1);
		if (s != null) return s;
		if ((s = needFlint(player, 1)) != null) return s;
		return new CraftStep("craft flint and steel", Items.FLINT_AND_STEEL, false, new Ingredient(x -> x.is(Items.IRON_INGOT), 1), new Ingredient(x -> x.is(Items.FLINT), 2));
	}

	private Step needObsidian(LocalPlayer player, int n) {
		if (Inv.count(player, Items.OBSIDIAN) >= n) return null;
		Step s = needPickaxe(player, 3);
		if (s != null) return s;
		return new MineStep("mine obsidian", n, x -> x.is(Items.OBSIDIAN), Blocks.OBSIDIAN);
	}

	private Step planNether(LocalPlayer player) {
		if (player.level().dimension() == net.minecraft.world.level.Level.NETHER) return null;
		Step s = needPickaxe(player, 3);
		if (s != null) return s;
		if ((s = needSword(player, 1)) != null) return s;
		if ((s = needFlintAndSteel(player)) != null) return s;
		// What the frame still needs, counting what's already standing
		int placed = Portal.obsidianPlaced(player.level(), portal);
		if ((s = needObsidian(player, Portal.OBSIDIAN - placed)) != null) return s;
		if ((s = needStone(player, 4)) != null) return s;
		return new PortalStep();
	}

	private Step needFurnaceItem(LocalPlayer player) {
		Step s = needStone(player, 8);
		if (s != null) return s;
		if ((s = needTable(player, 0)) != null) return s;
		return new CraftStep("craft furnace", Items.FURNACE, true, new Ingredient(STONE, FURNACE_RING));
	}

	private Step needStone(LocalPlayer player, int n) {
		if (Inv.count(player, STONE) >= n) return null;
		Step tool = needPickaxe(player, 0);
		if (tool != null) return tool;
		// Enough for the stone pickaxe and a furnace in one trip
		int want = Math.max(n, 12);
		return new MineStep("mine stone", want, STONE, Blocks.STONE, Blocks.DEEPSLATE, Blocks.COBBLESTONE);
	}

	private Step needSticks(LocalPlayer player, int n) {
		if (Inv.count(player, STICKS) >= n) return null;
		Step s = needPlanks(player, 2, 0);
		if (s != null) return s;
		return new CraftStep("craft sticks", Items.STICK, false, new Ingredient(PLANKS, 1, 3));
	}

	/** `n` planks to use, plus `extra` that something else is already counting on */
	private Step needPlanks(LocalPlayer player, int n, int extra) {
		if (Inv.count(player, PLANKS) >= n + extra) return null;
		if (Inv.count(player, LOGS) == 0) return needLogs(player, 1);
		return new CraftStep("craft planks", null, false, new Ingredient(LOGS, true, 1));
	}

	private Step needLogs(LocalPlayer player, int n) {
		if (Inv.count(player, LOGS) >= n) return null;
		// One trip to the trees covers every wooden thing on the way to an iron pickaxe
		int want = action != null && action.equals("get") && !"log".equals(target) ? Math.max(n, 7) : n;
		return new MineStep("chop wood", want, LOGS, LOG_BLOCKS);
	}

	/** A crafting table she can reach: the one she placed, a new one from her pocket, or a new one crafted */
	private Step needTable(LocalPlayer player, int planksReserved) {
		if (near(player, tablePos, Blocks.CRAFTING_TABLE)) return null;
		if (Inv.count(player, Items.CRAFTING_TABLE) > 0) return new PlaceStep("place crafting table", Items.CRAFTING_TABLE, true);
		// Walking back to the one she left is cheaper than four planks, unless it's far
		if (tablePos != null && player.level().getBlockState(tablePos).is(Blocks.CRAFTING_TABLE) && Inv.within(player, tablePos, 48.0)) {
			return new WalkStep("back to the crafting table", tablePos);
		}
		return needTableItem(player, planksReserved);
	}

	private Step needTableItem(LocalPlayer player, int planksReserved) {
		Step s = needPlanks(player, 4, planksReserved);
		if (s != null) return s;
		return new CraftStep("craft crafting table", Items.CRAFTING_TABLE, false, new Ingredient(PLANKS, 1, 2, 3, 4));
	}

	private boolean near(LocalPlayer player, BlockPos pos, Block block) {
		return pos != null && player.level().getBlockState(pos).is(block) && Inv.within(player, pos, 4.0);
	}

	// ---- Steps ----

	interface Step {
		String name();
		/** True once it's over (done or failed: failing ends the task) */
		boolean tick(Minecraft mc);
	}

	record Ingredient(Predicate<ItemStack> what, boolean wholeStack, int... slots) {
		Ingredient(Predicate<ItemStack> what, int... slots) {
			this(what, false, slots);
		}
	}

	/** Baritone walks and digs until the inventory has enough */
	final class MineStep implements Step {
		private final String name;
		private final int quantity;
		private final Predicate<ItemStack> counts;
		private final Block[] blocks;
		private boolean started = false;
		private int idleTicks = 0;
		private final Integer legitY;
		private final long deadline;
		private final int baritoneQuantity;

		MineStep(String name, int quantity, Predicate<ItemStack> counts, Block... blocks) {
			this(name, quantity, counts, quantity, blocks);
		}

		/** `baritoneQuantity` 0: Baritone keeps digging until she has enough of what `counts` counts */
		MineStep(String name, int quantity, Predicate<ItemStack> counts, int baritoneQuantity, Block... blocks) {
			this.baritoneQuantity = baritoneQuantity;
			this.name = name;
			this.quantity = quantity;
			this.counts = counts;
			this.blocks = blocks;
			this.legitY = legitLevel(blocks);
			// Without seeing through walls, diamonds take a while
			this.deadline = System.currentTimeMillis() + (legitY != null && legitY < 0 ? 30 : 15) * 60 * 1000;
		}

		public String name() { return name; }

		int have(LocalPlayer player) { return Inv.count(player, counts); }
		int need() { return quantity; }

		public boolean tick(Minecraft mc) {
			if (Inv.count(mc.player, counts) >= quantity) {
				baritone().getPathingBehavior().cancelEverything();
				event("got", name);
				return true;
			}
			if (!started) {
				if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
				// Ores only once she has seen them, digging at the height they're found at, like a person
				Settings settings = BaritoneAPI.getSettings();
				setting(settings.legitMine, legitY != null);
				if (legitY != null) setting(settings.legitMineYLevel, legitY);
				baritone().getMineProcess().mine(baritoneQuantity, blocks);
				started = true;
				event("step", name);
				return false;
			}
			// Baritone gives up when it can't find any more of it
			idleTicks = baritone().getMineProcess().isActive() ? 0 : idleTicks + 1;
			if (idleTicks > 60 || System.currentTimeMillis() > deadline) {
				end("failed", "could_not_find:" + name);
				return true;
			}
			return false;
		}
	}

	/** Baritone walks her up to a block she placed earlier */
	final class WalkStep implements Step {
		private final String name;
		private final BlockPos pos;
		private boolean started = false;
		private int idleTicks = 0;

		WalkStep(String name, BlockPos pos) {
			this.name = name;
			this.pos = pos;
		}

		public String name() { return name; }

		public boolean tick(Minecraft mc) {
			if (Inv.within(mc.player, pos, 3.5)) {
				baritone().getPathingBehavior().cancelEverything();
				return true;
			}
			if (!started) {
				if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
				baritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, 2));
				started = true;
				event("step", name);
				return false;
			}
			idleTicks = baritone().getCustomGoalProcess().isActive() ? 0 : idleTicks + 1;
			if (idleTicks > 60) {
				// Couldn't get there: forget it, and the next plan crafts a new one
				if (pos.equals(tablePos)) tablePos = null;
				return true;
			}
			return false;
		}
	}

	/** Puts a piece of armor on: opens the inventory and shift-clicks it, the way a player does */
	final class EquipStep implements Step {
		private final String name;
		private final Item item;
		private final net.minecraft.world.entity.EquipmentSlot slot;
		private int phase = 0;
		private int wait = 0;

		EquipStep(String name, Item item, net.minecraft.world.entity.EquipmentSlot slot) {
			this.name = name;
			this.item = item;
			this.slot = slot;
		}

		public String name() { return name; }

		public boolean tick(Minecraft mc) {
			LocalPlayer player = mc.player;
			switch (phase) {
				case 0 -> {
					event("step", name);
					if (player.containerMenu != player.inventoryMenu) player.closeContainer();
					mc.gui.setScreen(new InventoryScreen(player));
					hand.pause(6 + random.nextInt(6));
					phase = 1;
				}
				case 1 -> {
					int index = Inv.find(player, s -> s.is(item));
					if (index < 0) {
						finish(mc);
						end("failed", "missing:" + name);
						return true;
					}
					hand.click(Inv.menuSlotOf(player, player.inventoryMenu, index), 0, ContainerInput.QUICK_MOVE);
					phase = 2;
				}
				case 2 -> {
					if (player.getItemBySlot(slot).is(item)) {
						hand.pause(6 + random.nextInt(6));
						phase = 3;
					} else if (++wait > 20) {
						finish(mc);
						end("failed", "could_not_wear:" + name);
						return true;
					}
				}
				default -> {
					finish(mc);
					event("wore", id(item));
					return true;
				}
			}
			return false;
		}

		private void finish(Minecraft mc) {
			hand.reset();
			if (mc.gui.screen() != null) mc.player.closeContainer();
		}
	}

	/** Hunts animals for meat: Baritone follows one, she hits it, then picks up what it dropped */
	final class HuntStep implements Step {
		private final String name;
		private final int meat;
		private final boolean asked;
		private final long deadline = System.currentTimeMillis() + 6 * 60 * 1000;
		private net.minecraft.world.entity.animal.Animal prey = null;
		private net.minecraft.world.phys.Vec3 lootAt = null;
		private int lootTicks = 0;
		private int chaseTicks = 0;
		private boolean announced = false;

		/** Until she has `meat` pieces of meat; `asked` false when it's only because she's hungry */
		HuntStep(String name, int meat, boolean asked) {
			this.name = name;
			this.meat = meat;
			this.asked = asked;
		}

		public String name() { return name; }

		static net.minecraft.world.entity.animal.Animal nearestPrey(LocalPlayer player, double range) {
			net.minecraft.world.entity.animal.Animal best = null;
			double bestD = range * range;
			for (net.minecraft.world.entity.animal.Animal a : player.level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, player.getBoundingBox().inflate(range))) {
				boolean food = a instanceof net.minecraft.world.entity.animal.cow.Cow || a instanceof net.minecraft.world.entity.animal.pig.Pig
					|| a instanceof net.minecraft.world.entity.animal.sheep.Sheep || a instanceof net.minecraft.world.entity.animal.chicken.Chicken
					|| a instanceof net.minecraft.world.entity.animal.rabbit.Rabbit;
				if (!food || !a.isAlive() || a.isBaby()) continue;
				double d = player.distanceToSqr(a);
				if (d < bestD) {
					bestD = d;
					best = a;
				}
			}
			return best;
		}

		public boolean tick(Minecraft mc) {
			LocalPlayer player = mc.player;
			if (Inv.count(player, RAW_MEAT) + Inv.count(player, COOKED_FOOD) >= meat) {
				baritone().getPathingBehavior().cancelEverything();
				event("got", name);
				return true;
			}
			if (System.currentTimeMillis() > deadline) return giveUp();
			if (!announced) {
				event("step", name);
				announced = true;
			}
			// It died: walk over what it dropped
			if (prey != null && !prey.isAlive()) {
				lootAt = prey.position();
				lootTicks = 0;
				prey = null;
				baritone().getPathingBehavior().cancelEverything();
			}
			if (lootAt != null) {
				if (++lootTicks > 120) {
					lootAt = null;
					return false;
				}
				net.minecraft.world.entity.item.ItemEntity drop = null;
				for (net.minecraft.world.entity.item.ItemEntity e : player.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(lootAt, lootAt).inflate(6))) {
					if (RAW_MEAT.test(e.getItem())) {
						drop = e;
						break;
					}
				}
				if (drop == null) {
					lootAt = null;
					return false;
				}
				if (lootTicks % 20 == 1) baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(drop.blockPosition()));
				return false;
			}
			if (prey == null) {
				prey = nearestPrey(player, 64);
				if (prey == null) return giveUp();
				final net.minecraft.world.entity.animal.Animal target = prey;
				baritone().getFollowProcess().follow(e -> e == target);
				chaseTicks = 0;
				return false;
			}
			if (++chaseTicks > 20 * 60) {
				// This one keeps getting away: try another
				prey = null;
				baritone().getPathingBehavior().cancelEverything();
				return false;
			}
			if (player.distanceTo(prey) <= 3.0f && player.hasLineOfSight(prey)) {
				Inv.holdBestSword(mc);
				Inv.lookTowards(player, prey.getEyePosition(), 40f);
				if (player.getAttackStrengthScale(0f) >= 0.95f) {
					mc.gameMode.attack(player, prey);
					Inv.swing(player);
				}
			}
			return false;
		}

		private boolean giveUp() {
			baritone().getPathingBehavior().cancelEverything();
			if (asked) {
				end("failed", "could_not_find:" + name);
			} else {
				// Only hungry: go on without it, and don't try again for a while
				noHuntUntil = System.currentTimeMillis() + 5 * 60 * 1000;
			}
			return true;
		}
	}

	/** Builds the frame, lights it with the flint and steel, and walks in */
	final class PortalStep implements Step {
		private int phase = 0;
		private int wait = 0;
		private int index = 0;
		private int tries = 0;
		private List<Portal.Placement> placements;
		private boolean walking = false;

		public String name() { return "build portal"; }

		public boolean tick(Minecraft mc) {
			LocalPlayer player = mc.player;
			switch (phase) {
				case 0 -> {
					// The spot: the one she started on, if nothing got in the way, or a new one
					if (portal == null || !portal.stillFits(player.level())) portal = Portal.find(player.level(), player.blockPosition());
					if (portal == null) {
						end("failed", "no_room_for:portal");
						return true;
					}
					if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
					event("step", "build portal");
					placements = portal.placements();
					index = 0;
					phase = 1;
					walking = false;
				}
				case 1 -> {
					// Walk to where she stands to build it
					if (player.blockPosition().equals(portal.stand) || Inv.within(player, portal.stand, 0.6)) {
						baritone().getPathingBehavior().cancelEverything();
						phase = 2;
						return false;
					}
					if (!walking) {
						baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(portal.stand));
						walking = true;
						wait = 0;
					} else if (!baritone().getCustomGoalProcess().isActive() && ++wait > 60) {
						end("failed", "could_not_reach:portal");
						return true;
					}
				}
				case 2 -> {
					// One block at a time, looking at where it goes
					while (index < placements.size() && done(player, placements.get(index))) index++;
					if (index >= placements.size()) {
						phase = 4;
						return false;
					}
					Portal.Placement p = placements.get(index);
					if (!Inv.hold(mc, p.obsidian() ? s -> s.is(Items.OBSIDIAN) : STONE)) {
						end("failed", "missing:" + (p.obsidian() ? "obsidian" : "cobblestone"));
						return true;
					}
					if (Inv.lookTowards(player, Inv.faceCenter(p.support(), p.face()), 18f)) {
						Inv.placeAgainst(mc, p.support(), p.face());
						phase = 3;
						wait = 0;
					}
				}
				case 3 -> {
					Portal.Placement p = placements.get(index);
					if (done(player, p)) {
						tries = 0;
						phase = 2;
						hand.pause(8 + random.nextInt(8)); // a moment between blocks, like a person
					} else if (++wait > 15) {
						if (++tries > 3) {
							end("failed", "could_not_place:portal");
							return true;
						}
						phase = 2;
					}
				}
				case 4 -> {
					// Light it: flint and steel on top of the bottom obsidian
					if (portal.lit(player.level())) {
						event("placed", "portal lit");
						phase = 6;
						wait = 0;
						return false;
					}
					if (!Inv.hold(mc, s -> s.is(Items.FLINT_AND_STEEL))) {
						end("failed", "missing:flint_and_steel");
						return true;
					}
					BlockPos floor = portal.at(1, 0);
					if (Inv.lookTowards(player, Inv.faceCenter(floor, Direction.UP), 18f)) {
						Inv.placeAgainst(mc, floor, Direction.UP);
						phase = 5;
						wait = 0;
					}
				}
				case 5 -> {
					if (portal.lit(player.level())) {
						event("placed", "portal lit");
						phase = 6;
						wait = 0;
					} else if (++wait > 20) {
						if (++tries > 3) {
							end("failed", "portal_did_not_light");
							return true;
						}
						phase = 4;
					}
				}
				case 6 -> {
					// Walk in: straight ahead and a hop onto the bottom row
					if (wait++ == 0) event("step", "enter portal");
					Inv.lookTowards(player, net.minecraft.world.phys.Vec3.atCenterOf(portal.inside()), 30f);
					boolean inside = player.blockPosition().equals(portal.inside());
					mc.options.keyUp.setDown(!inside);
					mc.options.keyJump.setDown(!inside && player.onGround() && Inv.within(player, portal.inside(), 1.9));
					if (inside) {
						mc.options.keyUp.setDown(false);
						mc.options.keyJump.setDown(false);
						phase = 7;
						wait = 0;
					} else if (wait > 100) {
						mc.options.keyUp.setDown(false);
						mc.options.keyJump.setDown(false);
						end("failed", "could_not_enter:portal");
						return true;
					}
				}
				default -> {
					// A few seconds in the purple, then the Nether
					if (player.level().dimension() == net.minecraft.world.level.Level.NETHER) {
						event("arrived", "nether");
						return true;
					}
					if (++wait > 20 * 20) {
						end("failed", "portal_did_not_take_me");
						return true;
					}
				}
			}
			return false;
		}

		private boolean done(LocalPlayer player, Portal.Placement p) {
			var state = player.level().getBlockState(p.pos());
			return p.obsidian() ? state.is(Blocks.OBSIDIAN) : !state.canBeReplaced();
		}
	}

	/** Puts a crafting table or a furnace down next to the player, after a look at the spot */
	final class PlaceStep implements Step {
		private final String name;
		private final Item item;
		private final boolean isTable;
		private BlockPos pos;
		private int phase = 0;
		private int wait = 0;

		PlaceStep(String name, Item item, boolean isTable) {
			this.name = name;
			this.item = item;
			this.isTable = isTable;
		}

		public String name() { return name; }

		public boolean tick(Minecraft mc) {
			LocalPlayer player = mc.player;
			switch (phase) {
				case 0 -> {
					if (player.containerMenu != player.inventoryMenu) player.closeContainer();
					pos = Inv.placeSpot(player);
					if (pos == null) {
						end("failed", "no_room_for:" + name);
						return true;
					}
					if (!Inv.hold(mc, s -> s.is(item))) {
						end("failed", "missing:" + name);
						return true;
					}
					event("step", name);
					phase = 1;
				}
				case 1 -> {
					if (Inv.lookTowards(player, new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5), 14f)) {
						Inv.placeAt(mc, pos);
						phase = 2;
						wait = 0;
					}
				}
				default -> {
					if (player.level().getBlockState(pos).is(isTable ? Blocks.CRAFTING_TABLE : Blocks.FURNACE)) {
						if (isTable) tablePos = pos; else furnacePos = pos;
						event("placed", name);
						return true;
					}
					if (++wait > 40) {
						end("failed", "could_not_place:" + name);
						return true;
					}
				}
			}
			return false;
		}
	}

	/** Opens the menu, lays the recipe out with her mouse one click at a time, and takes the result */
	final class CraftStep implements Step {
		private final String name;
		private final Item result; // null: whatever comes out (planks of any wood)
		private final boolean onTable;
		private final List<Inv.Fill> fills = new ArrayList<>();
		private int phase = 0;
		private int wait = 0;
		private int before = 0;

		CraftStep(String name, Item result, boolean onTable, Ingredient... ingredients) {
			this.name = name;
			this.result = result;
			this.onTable = onTable;
			for (Ingredient ing : ingredients) {
				for (int slot : ing.slots()) fills.add(new Inv.Fill(slot, ing.what(), ing.wholeStack()));
			}
		}

		public String name() { return name; }

		private int resultCount(LocalPlayer player) {
			return result == null ? Inv.count(player, PLANKS) : Inv.count(player, result);
		}

		private int gridEnd() {
			return onTable ? 9 : 4;
		}

		public boolean tick(Minecraft mc) {
			LocalPlayer player = mc.player;
			AbstractContainerMenu menu = player.containerMenu;
			switch (phase) {
				case 0 -> {
					before = resultCount(player);
					event("step", name);
					if (menu != player.inventoryMenu) player.closeContainer();
					if (onTable) {
						if (!near(player, tablePos, Blocks.CRAFTING_TABLE)) {
							end("failed", "table_gone");
							return true;
						}
						phase = 1;
					} else {
						mc.gui.setScreen(new InventoryScreen(player));
						phase = 2;
					}
					wait = 0;
				}
				case 1 -> {
					if (Inv.lookTowards(player, Vec3.atCenterOf(tablePos), 14f)) {
						Inv.use(mc, tablePos);
						phase = 2;
						wait = 0;
					}
				}
				case 2 -> {
					boolean ready = onTable ? menu instanceof CraftingMenu : menu == player.inventoryMenu;
					if (!ready) {
						if (++wait > 40) {
							end("failed", "menu_did_not_open:" + name);
							return true;
						}
						return false;
					}
					// A look at the grid before she starts
					hand.pause(8 + random.nextInt(8));
					phase = 3;
				}
				case 3 -> {
					Inv.Move move = Inv.nextFill(player, menu, fills);
					if (move == Inv.MISSING) {
						finish(mc);
						end("failed", "missing_ingredients:" + name);
						return true;
					}
					if (move == Inv.DONE) {
						hand.pause(4 + random.nextInt(4));
						phase = 4;
						wait = 0;
					} else {
						hand.click(move.slot(), move.button(), ContainerInput.PICKUP);
					}
				}
				case 4 -> {
					if (menu.getSlot(0).hasItem()) {
						hand.click(0, 0, ContainerInput.QUICK_MOVE);
						phase = 5;
					} else if (++wait > 30) {
						finish(mc);
						end("failed", "recipe_did_not_work:" + name);
						return true;
					}
				}
				case 5 -> {
					// Anything left in the grid goes back to the inventory
					for (int slot = 1; slot <= gridEnd(); slot++) {
						if (menu.getSlot(slot).hasItem()) {
							hand.click(slot, 0, ContainerInput.QUICK_MOVE);
							return false;
						}
					}
					hand.pause(6 + random.nextInt(6));
					phase = 6;
				}
				default -> {
					finish(mc);
					if (resultCount(player) <= before) {
						end("failed", "nothing_crafted:" + name);
						return true;
					}
					event("crafted", result == null ? "planks" : id(result));
					return true;
				}
			}
			return false;
		}

		private void finish(Minecraft mc) {
			hand.reset();
			// Closing the menu hands back whatever is still in the grid or on the cursor
			if (mc.gui.screen() != null) mc.player.closeContainer();
		}
	}

	/** Places a furnace if needed, feeds it with her mouse, and checks on it until it's done */
	final class SmeltStep implements Step {
		private final String name;
		private final Predicate<ItemStack> input;
		private final Predicate<ItemStack> output;
		private final String outputName;
		private final int quantity;
		private int phase = 0;
		private int wait = 0;
		private int target;
		private long deadline;
		private PlaceStep placing;
		private final List<Inv.Fill> fills = new ArrayList<>();

		SmeltStep(String name, Predicate<ItemStack> input, Predicate<ItemStack> output, String outputName, int quantity) {
			this.name = name;
			this.input = input;
			this.output = output;
			this.outputName = outputName;
			this.quantity = quantity;
		}

		public String name() { return name; }

		public boolean tick(Minecraft mc) {
			LocalPlayer player = mc.player;
			AbstractContainerMenu menu = player.containerMenu;
			if (phase > 1 && System.currentTimeMillis() > deadline) {
				close(mc);
				end("failed", "smelting_too_slow");
				return true;
			}
			switch (phase) {
				case 0 -> {
					target = Inv.count(player, output) + quantity;
					deadline = System.currentTimeMillis() + 4 * 60 * 1000;
					if (near(player, furnacePos, Blocks.FURNACE)) {
						event("step", name);
						phase = 2;
					} else {
						placing = new PlaceStep("place furnace", Items.FURNACE, false);
						phase = 1;
					}
				}
				case 1 -> {
					if (!placing.tick(mc)) return false;
					if (action == null) return true; // placing failed and ended the task
					event("step", name);
					phase = 2;
				}
				case 2 -> {
					// Walk up to it, look at it, open it
					if (!near(player, furnacePos, Blocks.FURNACE)) {
						end("failed", "furnace_gone");
						return true;
					}
					if (menu != player.inventoryMenu) player.closeContainer();
					if (Inv.lookTowards(player, Vec3.atCenterOf(furnacePos), 14f)) {
						Inv.use(mc, furnacePos);
						phase = 3;
						wait = 0;
					}
				}
				case 3 -> {
					if (!(menu instanceof AbstractFurnaceMenu)) {
						if (++wait > 40) {
							end("failed", "furnace_did_not_open");
							return true;
						}
						return false;
					}
					hand.pause(6 + random.nextInt(6));
					phase = 4;
				}
				case 4 -> {
					if (!(menu instanceof AbstractFurnaceMenu furnace)) {
						phase = 2;
						return false;
					}
					// Take out what's done first
					if (furnace.getSlot(2).hasItem()) {
						hand.click(2, 0, ContainerInput.QUICK_MOVE);
						return false;
					}
					if (Inv.count(player, output) >= target) {
						phase = 7;
						return false;
					}
					// What it still needs: something to smelt (slot 0) and something to burn (slot 1)
					fills.clear();
					boolean hasInput = furnace.getSlot(0).hasItem() || Inv.count(player, input) > 0;
					if (!hasInput) {
						close(mc);
						end("failed", "smelting_stopped");
						return true;
					}
					if (!furnace.getSlot(0).hasItem()) fills.add(new Inv.Fill(0, input, true));
					if (!furnace.getSlot(1).hasItem() && !furnace.isLit()) {
						Predicate<ItemStack> fuel = Inv.count(player, COAL) > 0 ? COAL : Inv.count(player, PLANKS) > 0 ? PLANKS : Inv.count(player, LOGS) > 0 ? LOGS : null;
						if (fuel == null) {
							close(mc);
							end("failed", "no_fuel");
							return true;
						}
						fills.add(new Inv.Fill(1, fuel, true));
					}
					phase = 5;
				}
				case 5 -> {
					Inv.Move move = Inv.nextFill(player, menu, fills);
					if (move == Inv.DONE || move == Inv.MISSING) {
						// It's burning: close it and keep an eye on it from outside
						hand.pause(6 + random.nextInt(6));
						phase = 6;
						wait = 0;
					} else {
						hand.click(move.slot(), move.button(), ContainerInput.PICKUP);
					}
				}
				case 6 -> {
					if (wait == 0) close(mc);
					// An item takes 10 seconds: look again every 5
					if (++wait >= 100) phase = 2;
				}
				default -> {
					// Done: take the leftover fuel back too
					if (menu instanceof AbstractFurnaceMenu furnace && furnace.getSlot(1).hasItem()) {
						hand.click(1, 0, ContainerInput.QUICK_MOVE);
						phase = 8;
						return false;
					}
					close(mc);
					event("smelted", outputName);
					return true;
				}
			}
			return false;
		}

		private void close(Minecraft mc) {
			hand.reset();
			if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
		}
	}

	// ---- Helpers ----

	/** The height she strip-mines at for an ore, or null for things she can see from where she is */
	static Integer legitLevel(Block[] blocks) {
		for (Block b : blocks) {
			if (b == Blocks.DIAMOND_ORE || b == Blocks.DEEPSLATE_DIAMOND_ORE) return -54;
			if (b == Blocks.IRON_ORE || b == Blocks.DEEPSLATE_IRON_ORE) return 16;
			if (b == Blocks.COAL_ORE || b == Blocks.DEEPSLATE_COAL_ORE) return 48;
			if (BuiltInRegistries.BLOCK.getKey(b).getPath().endsWith("_ore")) return 16;
		}
		return null;
	}

	private static IBaritone baritone() {
		return BaritoneAPI.getProvider().getPrimaryBaritone();
	}

	static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	/** "diamond_ore" or "minecraft:diamond_ore" to the block, or null */
	static Block blockById(String name) {
		if (name == null) return null;
		String wanted = name.contains(":") ? name : "minecraft:" + name;
		for (Block block : BuiltInRegistries.BLOCK) {
			if (BuiltInRegistries.BLOCK.getKey(block).toString().equals(wanted)) return block;
		}
		return null;
	}

	private synchronized void event(String kind, String detail) {
		JsonObject e = new JsonObject();
		e.addProperty("id", nextEventId++);
		e.addProperty("kind", kind);
		e.addProperty("detail", detail);
		e.addProperty("at", System.currentTimeMillis());
		events.addLast(e);
		while (events.size() > 100) events.removeFirst();
		CapriCraftClient.LOG.info("[CapriCraft] {} {}", kind, detail);
	}

	private void snapshot() {
		JsonObject s = new JsonObject();
		s.addProperty("state", action == null ? "idle" : "running");
		s.addProperty("inWorld", inWorld);
		LocalPlayer me = Minecraft.getInstance().player;
		if (me != null) {
			s.addProperty("health", me.getHealth());
			s.addProperty("food", me.getFoodData().getFoodLevel());
			s.addProperty("dead", me.isDeadOrDying());
			s.addProperty("dimension", me.level().dimension().identifier().getPath());
		}
		if (action != null) {
			s.addProperty("action", action);
			if (target != null) s.addProperty("target", target);
			s.addProperty("count", amount);
			s.addProperty("step", step);
			s.addProperty("seconds", (System.currentTimeMillis() - startedAt) / 1000);
			if (current instanceof MineStep m && Minecraft.getInstance().player != null) {
				s.addProperty("have", m.have(Minecraft.getInstance().player));
				s.addProperty("need", m.need());
			}
		}
		statusSnapshot = s.toString();
	}

	private static void say(Minecraft mc, String text) {
		if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§d[Capri]§r " + text));
	}

	List<String> debugInventory(LocalPlayer player) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
			ItemStack s = player.getInventory().getItem(i);
			if (!s.isEmpty()) out.add(s.getCount() + "x " + id(s.getItem()));
		}
		return out;
	}
}
