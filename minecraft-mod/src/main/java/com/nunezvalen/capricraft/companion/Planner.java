package com.nunezvalen.capricraft.companion;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Works out the next thing to do to end up with N of something, from what she has right now. It
 * knows where raw things come from (logs from trees, iron from ore she has to find, leather from
 * cows), and for everything else it reads the game's own crafting and smelting recipes, so "make
 * me a bucket" or "a shield" work without anyone writing them down. Called again after every step.
 */
final class Planner {
	/** Something she wants: any item that matches, and one example to plan with */
	record Want(Predicate<ItemStack> matches, Item example, String name) {
		static Want of(Item item) {
			return new Want(s -> s.is(item), item, id(item));
		}

		static Want tag(TagKey<Item> tag, Item example, String name) {
			return new Want(s -> s.is(tag), example, name);
		}
	}

	static final int SURFACE = Integer.MIN_VALUE;
	static final int BELOW_HERE = Integer.MAX_VALUE;

	/** Where a raw thing comes from */
	record Source(Predicate<BlockState> blocks, Item pickaxe, int depth, Predicate<Entity> prey, String what) {
		boolean hunted() {
			return prey != null;
		}
	}

	static final class PlanException extends Exception {
		PlanException(String reason) {
			super(reason, null, false, false);
		}
	}

	static final Want FOOD = new Want(
		s -> s.is(Items.COOKED_BEEF) || s.is(Items.COOKED_PORKCHOP) || s.is(Items.COOKED_CHICKEN) || s.is(Items.COOKED_MUTTON)
			|| s.is(Items.COOKED_RABBIT) || s.is(Items.BREAD) || s.is(Items.BAKED_POTATO) || s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON),
		Items.COOKED_BEEF, "food");
	static final Predicate<ItemStack> RAW_MEAT = s -> s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.CHICKEN) || s.is(Items.MUTTON) || s.is(Items.RABBIT);
	static final Predicate<ItemStack> FUEL_COAL = s -> s.is(ItemTags.COALS);
	static final Predicate<ItemStack> FUEL_WOOD = s -> s.is(ItemTags.PLANKS) || s.is(ItemTags.LOGS);

	private static Map<Item, List<RecipeHolder<?>>> recipes = null;
	private static final Map<Item, Source> SOURCES = new HashMap<>();

	static {
		Predicate<BlockState> stone = s -> s.is(Blocks.STONE) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.DEEPSLATE) || s.is(Blocks.COBBLED_DEEPSLATE)
			|| s.is(Blocks.ANDESITE) || s.is(Blocks.DIORITE) || s.is(Blocks.GRANITE) || s.is(Blocks.TUFF);
		Source stoneSource = new Source(s -> s.is(Blocks.STONE) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.DEEPSLATE) || s.is(Blocks.COBBLED_DEEPSLATE), Items.WOODEN_PICKAXE, BELOW_HERE, null, "stone");
		SOURCES.put(Items.COBBLESTONE, stoneSource);
		SOURCES.put(Items.COBBLED_DEEPSLATE, stoneSource);
		SOURCES.put(Items.ANDESITE, new Source(s -> s.is(Blocks.ANDESITE), Items.WOODEN_PICKAXE, BELOW_HERE, null, "andesite"));
		SOURCES.put(Items.DIORITE, new Source(s -> s.is(Blocks.DIORITE), Items.WOODEN_PICKAXE, BELOW_HERE, null, "diorite"));
		SOURCES.put(Items.GRANITE, new Source(s -> s.is(Blocks.GRANITE), Items.WOODEN_PICKAXE, BELOW_HERE, null, "granite"));
		SOURCES.put(Items.COAL, new Source(s -> s.is(net.minecraft.tags.BlockItemTags.COAL_ORES.block()), Items.WOODEN_PICKAXE, 48, null, "coal"));
		SOURCES.put(Items.RAW_IRON, new Source(s -> s.is(BlockTags.IRON_ORES), Items.STONE_PICKAXE, 16, null, "iron"));
		SOURCES.put(Items.RAW_COPPER, new Source(s -> s.is(BlockTags.COPPER_ORES), Items.STONE_PICKAXE, 48, null, "copper"));
		SOURCES.put(Items.RAW_GOLD, new Source(s -> s.is(Blocks.GOLD_ORE) || s.is(Blocks.DEEPSLATE_GOLD_ORE), Items.IRON_PICKAXE, -16, null, "gold"));
		SOURCES.put(Items.DIAMOND, new Source(s -> s.is(net.minecraft.tags.BlockItemTags.DIAMOND_ORES.block()), Items.IRON_PICKAXE, -54, null, "diamonds"));
		SOURCES.put(Items.REDSTONE, new Source(s -> s.is(net.minecraft.tags.BlockItemTags.REDSTONE_ORES.block()), Items.IRON_PICKAXE, -54, null, "redstone"));
		SOURCES.put(Items.LAPIS_LAZULI, new Source(s -> s.is(net.minecraft.tags.BlockItemTags.LAPIS_ORES.block()), Items.STONE_PICKAXE, 0, null, "lapis"));
		SOURCES.put(Items.EMERALD, new Source(s -> s.is(net.minecraft.tags.BlockItemTags.EMERALD_ORES.block()), Items.IRON_PICKAXE, 16, null, "emeralds"));
		SOURCES.put(Items.QUARTZ, new Source(s -> s.is(Blocks.NETHER_QUARTZ_ORE), Items.WOODEN_PICKAXE, BELOW_HERE, null, "quartz"));
		SOURCES.put(Items.OBSIDIAN, new Source(s -> s.is(Blocks.OBSIDIAN), Items.DIAMOND_PICKAXE, -40, null, "obsidian"));
		SOURCES.put(Items.FLINT, new Source(s -> s.is(Blocks.GRAVEL), null, SURFACE, null, "flint"));
		SOURCES.put(Items.CLAY_BALL, new Source(s -> s.is(Blocks.CLAY), null, SURFACE, null, "clay"));
		SOURCES.put(Items.MELON_SLICE, new Source(s -> s.is(Blocks.MELON), null, SURFACE, null, "melon"));
		SOURCES.put(Items.SNOWBALL, new Source(s -> s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.SNOW), Items.WOODEN_SHOVEL, SURFACE, null, "snow"));
		SOURCES.put(Items.GLOWSTONE_DUST, new Source(s -> s.is(Blocks.GLOWSTONE), null, BELOW_HERE, null, "glowstone"));
		SOURCES.put(Items.STICK, null); // always crafted
		// Animals and monsters
		hunt(Items.LEATHER, "leather", net.minecraft.world.entity.EntityTypes.COW, net.minecraft.world.entity.EntityTypes.MOOSHROOM, net.minecraft.world.entity.EntityTypes.HORSE);
		hunt(Items.BEEF, "beef", net.minecraft.world.entity.EntityTypes.COW, net.minecraft.world.entity.EntityTypes.MOOSHROOM);
		hunt(Items.PORKCHOP, "pork", net.minecraft.world.entity.EntityTypes.PIG);
		hunt(Items.CHICKEN, "chicken", net.minecraft.world.entity.EntityTypes.CHICKEN);
		hunt(Items.FEATHER, "feathers", net.minecraft.world.entity.EntityTypes.CHICKEN);
		hunt(Items.MUTTON, "mutton", net.minecraft.world.entity.EntityTypes.SHEEP);
		hunt(Items.RABBIT, "rabbit", net.minecraft.world.entity.EntityTypes.RABBIT);
		hunt(Items.RABBIT_HIDE, "rabbit hide", net.minecraft.world.entity.EntityTypes.RABBIT);
		hunt(Items.STRING, "string", net.minecraft.world.entity.EntityTypes.SPIDER, net.minecraft.world.entity.EntityTypes.CAVE_SPIDER);
		hunt(Items.BONE, "bones", net.minecraft.world.entity.EntityTypes.SKELETON, net.minecraft.world.entity.EntityTypes.STRAY);
		hunt(Items.ARROW, "arrows", net.minecraft.world.entity.EntityTypes.SKELETON, net.minecraft.world.entity.EntityTypes.STRAY);
		hunt(Items.GUNPOWDER, "gunpowder", net.minecraft.world.entity.EntityTypes.CREEPER);
		hunt(Items.ROTTEN_FLESH, "rotten flesh", net.minecraft.world.entity.EntityTypes.ZOMBIE, net.minecraft.world.entity.EntityTypes.HUSK, net.minecraft.world.entity.EntityTypes.DROWNED);
		hunt(Items.ENDER_PEARL, "ender pearls", net.minecraft.world.entity.EntityTypes.ENDERMAN);
		hunt(Items.SLIME_BALL, "slime", net.minecraft.world.entity.EntityTypes.SLIME);
		hunt(Items.BLAZE_ROD, "blaze rods", net.minecraft.world.entity.EntityTypes.BLAZE);
		hunt(Items.INK_SAC, "ink", net.minecraft.world.entity.EntityTypes.SQUID);
		hunt(Items.SPIDER_EYE, "spider eyes", net.minecraft.world.entity.EntityTypes.SPIDER, net.minecraft.world.entity.EntityTypes.CAVE_SPIDER);
	}

	private static void hunt(Item item, String what, EntityType<?>... types) {
		Set<EntityType<?>> set = Set.of(types);
		SOURCES.put(item, new Source(null, null, SURFACE, e -> set.contains(e.getType()), what));
	}

	private final CapriEntity c;
	private final CompanionBrain brain;
	private final ServerLevel level;
	private final Map<Item, Boolean> obtainable = new HashMap<>();
	private final Set<Item> visiting = new HashSet<>();

	private Planner(CapriEntity c, CompanionBrain brain, ServerLevel level) {
		this.c = c;
		this.brain = brain;
		this.level = level;
	}

	/** The next job towards `count` of `want`, or null when she already has them */
	static Job next(CapriEntity c, CompanionBrain brain, ServerLevel level, Want want, int count) throws PlanException {
		return new Planner(c, brain, level).plan(want, count, 0);
	}

	static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).getPath();
	}

	private Job plan(Want want, int count, int depth) throws PlanException {
		if (depth > 14) throw new PlanException("too_deep:" + want.name());
		int have = c.count(want.matches());
		if (have >= count) return null;
		int missing = count - have;

		// Food: cook raw meat she has, or hunt for some
		if (want == FOOD) {
			int raw = c.count(RAW_MEAT);
			if (raw > 0) return smeltWith(s -> RAW_MEAT.test(s), Math.min(raw, missing), FOOD.matches(), "food", depth);
			return new HuntJob(c, brain, e -> e.getType() == net.minecraft.world.entity.EntityTypes.COW || e.getType() == net.minecraft.world.entity.EntityTypes.PIG || e.getType() == net.minecraft.world.entity.EntityTypes.SHEEP
				|| e.getType() == net.minecraft.world.entity.EntityTypes.CHICKEN || e.getType() == net.minecraft.world.entity.EntityTypes.RABBIT, RAW_MEAT, c.count(RAW_MEAT) + missing, "food");
		}

		// Planks of any wood: from whatever logs she has, or chop a tree
		if (want.example().builtInRegistryHolder().is(ItemTags.PLANKS)) {
			Job fromLogs = planksFromLogs(want, missing);
			if (fromLogs != null) return fromLogs;
			int logsNeeded = (missing + 3) / 4;
			return new GatherJob(c, brain, s -> s.is(BlockTags.LOGS), st -> st.is(ItemTags.LOGS), c.count(st -> st.is(ItemTags.LOGS)) + logsNeeded, SURFACE, "wood");
		}

		// Logs
		if (want.example().builtInRegistryHolder().is(ItemTags.LOGS)) {
			Predicate<BlockState> trees = s -> s.is(BlockTags.LOGS) && want.matches().test(new ItemStack(s.getBlock().asItem()));
			return new GatherJob(c, brain, trees, want.matches(), have + missing, SURFACE, "wood");
		}

		// Raw things: ore, stone, sand, animals
		Source source = sourceFor(want);
		if (source != null) {
			if (source.hunted()) return new HuntJob(c, brain, source.prey(), want.matches(), have + missing, source.what());
			if (source.pickaxe() != null && !hasPickaxeFor(source.pickaxe())) {
				Job tool = plan(durable(source.pickaxe()), 1, depth + 1);
				if (tool != null) return tool;
			}
			return new GatherJob(c, brain, source.blocks(), want.matches(), have + missing, source.depth(), source.what()).needs(source.pickaxe());
		}

		// Crafted or smelted: the first recipe whose ingredients she can get without going in circles
		// (iron ingots from an iron block made of iron ingots...): what's being planned can't be its own ingredient
		Item example = want.example();
		boolean added = visiting.add(example);
		try {
			for (RecipeHolder<?> holder : recipesFor(example)) {
				obtainable.clear();
				if (!canMake(holder, depth)) continue;
				Recipe<?> recipe = holder.value();
				if (recipe instanceof SmeltingRecipe smelting) {
					return smeltWith(smelting.input(), missing, want.matches(), want.name(), depth);
				}
				return craft(holder, want, missing, depth);
			}
		} finally {
			if (added) visiting.remove(example);
		}

		// A block nobody crafts: dig it up (sand, dirt, gravel, sugar cane...)
		if (want.example() instanceof BlockItem blockItem) {
			Block block = blockItem.getBlock();
			BlockState state = block.defaultBlockState();
			Item pickaxe = pickaxeFor(state);
			if (pickaxe != null && !hasPickaxeFor(pickaxe)) {
				Job tool = plan(durable(pickaxe), 1, depth + 1);
				if (tool != null) return tool;
			}
			return new GatherJob(c, brain, s -> s.is(block), want.matches(), have + missing, pickaxe != null ? BELOW_HERE : SURFACE, id(want.example())).needs(pickaxe);
		}
		throw new PlanException("cannot_get:" + want.name());
	}

	// ---- Crafting ----

	private Job craft(RecipeHolder<?> holder, Want want, int missing, int depth) throws PlanException {
		Recipe<?> recipe = holder.value();
		int makes = Math.max(1, result(recipe).getCount());
		int times = (missing + makes - 1) / makes;
		boolean table = needsTable(recipe);
		if (table && !brain.hasTableNear(level) && c.count(s -> s.is(Items.CRAFTING_TABLE)) == 0) {
			Job t = plan(Want.of(Items.CRAFTING_TABLE), 1, depth + 1);
			if (t != null) return t;
		}
		for (Map.Entry<Ingredient, Integer> e : ingredientCounts(recipe).entrySet()) {
			Ingredient ingredient = e.getKey();
			int need = e.getValue() * times;
			if (c.count(ingredient) < need) {
				Job sub = plan(wantFor(ingredient), need, depth + 1);
				if (sub != null) return sub;
			}
		}
		return new CraftJob(c, brain, holder, times, table, id(want.example()));
	}

	private Job planksFromLogs(Want want, int missing) {
		// Planks that match what's wanted, made from logs she carries
		for (int i = 0; i < c.getInventory().getContainerSize(); i++) {
			ItemStack log = c.getInventory().getItem(i);
			if (log.isEmpty() || !log.is(ItemTags.LOGS)) continue;
			for (Holder<Item> planks : BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.PLANKS)) {
				if (!want.matches().test(new ItemStack(planks))) continue;
				for (RecipeHolder<?> holder : recipesFor(planks.value())) {
					if (holder.value() instanceof ShapelessRecipe r && r.placementInfo().ingredients().size() == 1 && r.placementInfo().ingredients().getFirst().test(log)) {
						int times = Math.min((missing + 3) / 4, c.count(s -> r.placementInfo().ingredients().getFirst().test(s)));
						return new CraftJob(c, brain, holder, Math.max(1, times), false, id(planks.value()));
					}
				}
			}
		}
		return null;
	}

	static Map<Ingredient, Integer> ingredientCounts(Recipe<?> recipe) {
		Map<Ingredient, Integer> counts = new LinkedHashMap<>();
		for (Ingredient i : recipe.placementInfo().ingredients()) counts.merge(i, 1, Integer::sum);
		return counts;
	}

	static boolean needsTable(Recipe<?> recipe) {
		if (recipe instanceof ShapedRecipe shaped) return shaped.getWidth() > 2 || shaped.getHeight() > 2;
		return recipe.placementInfo().ingredients().size() > 4;
	}

	static ItemStack result(Recipe<?> recipe) {
		if (recipe instanceof ShapedRecipe r) return r.assemble(CraftingInput.EMPTY);
		if (recipe instanceof ShapelessRecipe r) return r.assemble(CraftingInput.EMPTY);
		if (recipe instanceof SmeltingRecipe r) return r.assemble(new SingleRecipeInput(ItemStack.EMPTY));
		return ItemStack.EMPTY;
	}

	/** An ingredient as something to get: prefer what she already carries, then what she can make */
	private Want wantFor(Ingredient ingredient) {
		Item best = null;
		int bestCount = 0;
		for (int i = 0; i < c.getInventory().getContainerSize(); i++) {
			ItemStack s = c.getInventory().getItem(i);
			if (!s.isEmpty() && ingredient.test(s) && s.getCount() > bestCount) {
				best = s.getItem();
				bestCount = s.getCount();
			}
		}
		if (best == null) {
			for (Holder<Item> h : ingredient.items().toList()) {
				if (obtainable(h.value(), 1)) {
					best = h.value();
					break;
				}
			}
		}
		if (best == null) best = ingredient.items().findFirst().map(Holder::value).orElse(Items.AIR);
		return new Want(ingredient, best, id(best));
	}

	// ---- Smelting ----

	private Job smeltWith(Predicate<ItemStack> input, int n, Predicate<ItemStack> output, String name, int depth) throws PlanException {
		int haveInput = c.count(input);
		if (haveInput < n) {
			Ingredient ing = input instanceof Ingredient i ? i : null;
			Want inputWant = ing != null ? wantFor(ing) : new Want(input, Items.BEEF, "raw meat");
			Job sub = plan(inputWant, n, depth + 1);
			if (sub != null) return sub;
		}
		if (!brain.hasFurnaceNear(level) && c.count(s -> s.is(Items.FURNACE)) == 0) {
			Job f = plan(Want.of(Items.FURNACE), 1, depth + 1);
			if (f != null) return f;
		}
		// Fuel: coal lasts 8 items, a plank or a log 1.5
		double fuel = c.count(FUEL_COAL) * 8 + c.count(FUEL_WOOD) * 1.5;
		if (fuel < n) {
			int logs = (int) Math.ceil((n - fuel) / 1.5);
			return new GatherJob(c, brain, s -> s.is(BlockTags.LOGS), st -> st.is(ItemTags.LOGS), c.count(st -> st.is(ItemTags.LOGS)) + logs, SURFACE, "wood");
		}
		return new SmeltJob(c, brain, input, n, output, name);
	}

	// ---- What she can get at all ----

	private boolean canMake(RecipeHolder<?> holder, int depth) {
		for (Ingredient ingredient : ingredientCounts(holder.value()).keySet()) {
			if (c.count(ingredient) > 0) continue;
			boolean any = false;
			for (Holder<Item> h : ingredient.items().toList()) {
				if (obtainable(h.value(), depth + 1)) {
					any = true;
					break;
				}
			}
			if (!any) return false;
		}
		return true;
	}

	private boolean obtainable(Item item, int depth) {
		Boolean known = obtainable.get(item);
		if (known != null) return known;
		if (depth > 10 || visiting.contains(item)) return false;
		if (c.count(s -> s.is(item)) > 0) return true;
		Holder<Item> holder = item.builtInRegistryHolder();
		if (holder.is(ItemTags.LOGS) || holder.is(ItemTags.PLANKS) || SOURCES.get(item) != null) {
			obtainable.put(item, true);
			return true;
		}
		visiting.add(item);
		boolean result = false;
		for (RecipeHolder<?> r : recipesFor(item)) {
			if (canMake(r, depth)) {
				result = true;
				break;
			}
		}
		if (!result && item instanceof BlockItem && recipesFor(item).isEmpty()) result = true;
		visiting.remove(item);
		obtainable.put(item, result);
		return result;
	}

	private Source sourceFor(Want want) {
		Source s = SOURCES.get(want.example());
		if (s != null) return s;
		for (Map.Entry<Item, Source> e : SOURCES.entrySet()) {
			if (e.getValue() != null && want.matches().test(new ItemStack(e.getKey()))) return e.getValue();
		}
		return null;
	}

	boolean hasPickaxeFor(Item pickaxe) {
		return hasToolFor(c, pickaxe);
	}

	/** That tool, but only one that isn't about to break counts */
	static Want durable(Item tool) {
		return new Want(s -> s.is(tool) && healthy(s), tool, id(tool));
	}

	static boolean healthy(ItemStack s) {
		return !s.isDamageableItem() || s.getMaxDamage() - s.getDamageValue() > 8;
	}

	/** Has a tool at least as good as this one, that isn't about to break */
	static boolean hasToolFor(CapriEntity c, Item pickaxe) {
		BlockState sample = switch (id(pickaxe)) {
			case "wooden_pickaxe" -> Blocks.STONE.defaultBlockState();
			case "stone_pickaxe" -> Blocks.IRON_ORE.defaultBlockState();
			case "iron_pickaxe" -> Blocks.DIAMOND_ORE.defaultBlockState();
			case "diamond_pickaxe" -> Blocks.OBSIDIAN.defaultBlockState();
			case "wooden_shovel" -> Blocks.SNOW_BLOCK.defaultBlockState();
			default -> null;
		};
		if (sample == null) return true;
		BlockState s = sample;
		return c.count(st -> st.isCorrectToolForDrops(s) && healthy(st)) > 0;
	}

	static Item pickaxeFor(BlockState state) {
		if (!state.requiresCorrectToolForDrops()) return null;
		for (Item p : new Item[] {Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE}) {
			if (new ItemStack(p).isCorrectToolForDrops(state)) return p;
		}
		for (Item t : new Item[] {Items.WOODEN_SHOVEL, Items.WOODEN_AXE, Items.WOODEN_HOE}) {
			if (new ItemStack(t).isCorrectToolForDrops(state)) return t;
		}
		return null;
	}

	// ---- The recipe book, read once ----

	List<RecipeHolder<?>> recipesFor(Item item) {
		if (recipes == null) {
			Map<Item, List<RecipeHolder<?>>> index = new HashMap<>();
			for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
				Recipe<?> r = holder.value();
				if (!(r instanceof ShapedRecipe || r instanceof ShapelessRecipe || r instanceof SmeltingRecipe)) continue;
				ItemStack out = result(r);
				if (out.isEmpty()) continue;
				// Smelting an ore block only works with Silk Touch: raw iron, not iron ore, goes in her furnace
				if (r instanceof SmeltingRecipe s && s.input().items().allMatch(h -> id(h.value()).endsWith("_ore"))) continue;
				index.computeIfAbsent(out.getItem(), k -> new ArrayList<>()).add(holder);
			}
			// Crafting before smelting, simpler recipes first
			for (List<RecipeHolder<?>> list : index.values()) {
				list.sort((a, b) -> {
					boolean sa = a.value() instanceof SmeltingRecipe, sb = b.value() instanceof SmeltingRecipe;
					if (sa != sb) return sa ? 1 : -1;
					return Integer.compare(a.value().placementInfo().ingredients().size(), b.value().placementInfo().ingredients().size());
				});
			}
			recipes = index;
		}
		return recipes.getOrDefault(item, List.of());
	}

	/** Forget the cached recipe book (a new world may have other recipes) */
	static void reset() {
		recipes = null;
	}
}
