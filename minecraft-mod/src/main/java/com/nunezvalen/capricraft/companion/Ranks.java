package com.nunezvalen.capricraft.companion;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** How good a weapon, a piece of armor or a food is, so she uses the best she has */
final class Ranks {
	private Ranks() {}

	static int material(ItemStack s) {
		String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		if (id.startsWith("netherite_")) return 7;
		if (id.startsWith("diamond_")) return 6;
		if (id.startsWith("iron_")) return 5;
		if (id.startsWith("chainmail_") || id.startsWith("turtle_")) return 4;
		if (id.startsWith("copper_")) return 3;
		if (id.startsWith("stone_")) return 3;
		if (id.startsWith("golden_")) return 2;
		if (id.startsWith("wooden_") || id.startsWith("leather_")) return 1;
		return 0;
	}

	static boolean isWeapon(ItemStack s) {
		return s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES) || s.is(Items.TRIDENT) || s.is(Items.MACE);
	}

	/** Swords first, then axes, by material */
	static int weapon(ItemStack s) {
		if (s.isEmpty()) return 0;
		if (s.is(Items.MACE)) return 70;
		if (s.is(Items.TRIDENT)) return 60;
		if (s.is(ItemTags.SWORDS)) return material(s) * 10 + 5;
		if (s.is(ItemTags.AXES)) return material(s) * 10;
		return 0;
	}

	static int armor(ItemStack s) {
		if (s.isEmpty()) return 0;
		if (s.is(Items.ELYTRA)) return 1;
		return material(s) * 10 + 1;
	}

	static boolean badFood(ItemStack s) {
		return s.is(Items.ROTTEN_FLESH) || s.is(Items.SPIDER_EYE) || s.is(Items.POISONOUS_POTATO) || s.is(Items.PUFFERFISH)
			|| s.is(Items.CHORUS_FRUIT) || s.is(Items.SUSPICIOUS_STEW) || s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)
			|| s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.CHICKEN) || s.is(Items.MUTTON) || s.is(Items.RABBIT) || s.is(Items.COD) || s.is(Items.SALMON);
	}
}
