package com.nunezvalen.capricraft.companion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/**
 * Capri's body in the world. She walks with the game's own mob pathfinding, carries 27 slots plus
 * what she wears and holds, and never loses any of it: when she "dies" she comes back next to you
 * with everything, like keepInventory. Right-click her to open her inventory.
 */
public class CapriEntity extends PathfinderMob implements InventoryCarrier {
	private final SimpleContainer inventory = new SimpleContainer(27);
	final CompanionBrain brain = new CompanionBrain(this);
	int generation = 0;
	private int invulnerable = 0;
	private int regenTimer = 0;

	public CapriEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
		this.setPersistenceRequired();
		this.setCustomName(Component.literal("Capri"));
		this.setCustomNameVisible(true);
		this.getNavigation().setCanOpenDoors(true);
		this.getNavigation().setCanFloat(true);
		for (EquipmentSlot slot : EquipmentSlot.values()) this.setDropChance(slot, 0.0F);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
			.add(Attributes.MAX_HEALTH, 20.0)
			.add(Attributes.MOVEMENT_SPEED, 0.3)
			.add(Attributes.ATTACK_DAMAGE, 1.0)
			.add(Attributes.FOLLOW_RANGE, 48.0)
			.add(Attributes.ARMOR, 0.0);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
		this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
		this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
	}

	@Override
	public SimpleContainer getInventory() {
		return inventory;
	}

	// ---- Life ----

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel)) return;
		// An old body from a chunk that just loaded: a newer one already has her things
		if (generation < Companion.generation) {
			discard();
			return;
		}
		Companion.generation = generation;
		Companion.current = this;
		if (invulnerable > 0) invulnerable--;
		if (tickCount % 100 == 0) Companion.remember(this);
		// While she works far from you, her chunk stays loaded (like a thrown ender pearl's), or she'd freeze there
		if (tickCount % 20 == 0 && brain.busy() && level() instanceof ServerLevel sl) {
			sl.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.ENDER_PEARL, chunkPosition(), 2);
		}
		if (getHealth() < getMaxHealth() && ++regenTimer >= 60) {
			regenTimer = 0;
			heal(1.0F);
		}
	}

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		brain.tick(level);
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		if (invulnerable > 0) return false;
		Entity attacker = source.getEntity();
		// Your hits (and arrows) don't hurt her
		if (attacker instanceof Player player && player.getUUID().equals(ownerId())) return false;
		boolean hurt = super.hurtServer(level, source, damage);
		if (hurt && attacker instanceof net.minecraft.world.entity.LivingEntity living && living != this) brain.attackedBy(living);
		return hurt;
	}

	/** She doesn't die: she comes back next to you, with everything she had */
	@Override
	public void die(DamageSource source) {
		if (!(level() instanceof ServerLevel)) return;
		String message = getCombatTracker().getDeathMessage().getString();
		Companion.event("died", message);
		setHealth(getMaxHealth());
		clearFire();
		removeAllEffects();
		setDeltaMovement(Vec3.ZERO);
		resetFallDistance();
		invulnerable = 60;
		brain.respawned();
		ServerPlayer owner = Companion.owner(level().getServer());
		if (owner != null) teleportNear(owner);
		Companion.remember(this);
	}

	@Override
	protected void onBelowWorld() {
		// Fell out of the world: back to you instead of vanishing
		ServerPlayer owner = Companion.owner(level().getServer());
		if (owner != null) {
			Companion.event("died", "fell out of the world");
			teleportNear(owner);
		} else {
			super.onBelowWorld();
		}
	}

	@Override
	public boolean removeWhenFarAway(double distSqr) {
		return false;
	}

	@Override
	public boolean requiresCustomPersistence() {
		return true;
	}

	@Override
	public boolean canBeLeashed() {
		return false;
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (!(level() instanceof ServerLevel) || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
		player.openMenu(new SimpleMenuProvider((id, playerInventory, p) -> ChestMenu.threeRows(id, playerInventory, inventory), Component.literal("Capri")));
		return InteractionResult.SUCCESS;
	}

	@Override
	public void onRemoval(net.minecraft.world.entity.Entity.RemovalReason reason) {
		if (level() instanceof ServerLevel) {
			com.nunezvalen.capricraft.CapriCraftClient.LOG.info("[CapriCraft] Capri's body (gen {}, current gen {}) left the world: {} at {}", generation, Companion.generation, reason, blockPosition());
			if (reason == net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK && generation == Companion.generation) Companion.remember(this);
		}
		super.onRemoval(reason);
	}

	void leave() {
		brain.cancel();
		discard();
	}

	// ---- Save ----

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		writeInventoryToTag(output);
		output.putInt("CapriGen", generation);
		output.putString("CapriMode", brain.mode());
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		readInventoryFromTag(input);
		generation = input.getIntOr("CapriGen", 0);
		brain.setMode(input.getStringOr("CapriMode", "follow"));
	}

	// ---- Helpers the brain uses ----

	java.util.UUID ownerId() {
		return Companion.localPlayer;
	}

	ServerPlayer owner() {
		return Companion.owner(level().getServer());
	}

	/** Next to the player, even in another dimension */
	void teleportNear(ServerPlayer owner) {
		ServerLevel target = owner.level();
		Vec3 spot = safeSpotNear(target, owner.blockPosition(), owner.position());
		getNavigation().stop();
		resetFallDistance();
		if (target != level()) {
			teleport(new TeleportTransition(target, spot, Vec3.ZERO, owner.getYRot(), 0, TeleportTransition.DO_NOTHING));
		} else {
			teleportTo(spot.x, spot.y, spot.z);
		}
	}

	/** A spot near pos with ground under it and room for her, or pos itself */
	static Vec3 safeSpotNear(ServerLevel level, BlockPos pos, Vec3 fallback) {
		int[][] offsets = {{-2, 0}, {2, 0}, {0, -2}, {0, 2}, {-1, -1}, {1, 1}, {-1, 1}, {1, -1}, {-3, 0}, {3, 0}, {0, 3}, {0, -3}};
		for (int[] o : offsets) {
			for (int dy = 2; dy >= -3; dy--) {
				BlockPos feet = pos.offset(o[0], dy, o[1]);
				if (standable(level, feet)) return Vec3.atBottomCenterOf(feet);
			}
		}
		return fallback;
	}

	static boolean standable(Level level, BlockPos feet) {
		BlockPos below = feet.below();
		return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
			&& level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
			&& level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
			&& level.getFluidState(feet).isEmpty()
			&& !level.getBlockState(below).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK);
	}

	int surfaceY(int x, int z) {
		return level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
	}

	int count(Predicate<ItemStack> what) {
		int n = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack s = inventory.getItem(i);
			if (!s.isEmpty() && what.test(s)) n += s.getCount();
		}
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ItemStack s = getItemBySlot(slot);
			if (!s.isEmpty() && what.test(s)) n += s.getCount();
		}
		return n;
	}

	/** Takes n matching items out of her inventory (and hands) */
	int take(Predicate<ItemStack> what, int n) {
		int left = n;
		for (int i = 0; i < inventory.getContainerSize() && left > 0; i++) {
			ItemStack s = inventory.getItem(i);
			if (!s.isEmpty() && what.test(s)) {
				int k = Math.min(left, s.getCount());
				s.shrink(k);
				left -= k;
			}
		}
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (left <= 0) break;
			ItemStack s = getItemBySlot(slot);
			if (!s.isEmpty() && what.test(s)) {
				int k = Math.min(left, s.getCount());
				s.shrink(k);
				left -= k;
			}
		}
		inventory.setChanged();
		return n - left;
	}

	/** Puts it in her inventory; whatever doesn't fit drops at her feet */
	void give(ItemStack stack) {
		ItemStack rest = inventory.addItem(stack);
		if (!rest.isEmpty() && level() instanceof ServerLevel sl) spawnAtLocation(sl, rest);
	}

	/** Swaps the best item for the job into her main hand (the old one goes back to the inventory) */
	void hold(Predicate<ItemStack> what, java.util.Comparator<ItemStack> better) {
		ItemStack inHand = getMainHandItem();
		int best = -1;
		ItemStack bestStack = inHand.isEmpty() || !what.test(inHand) ? ItemStack.EMPTY : inHand;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack s = inventory.getItem(i);
			if (!s.isEmpty() && what.test(s) && (bestStack.isEmpty() || better.compare(s, bestStack) > 0)) {
				bestStack = s;
				best = i;
			}
		}
		if (best < 0) return;
		ItemStack chosen = inventory.removeItemNoUpdate(best);
		if (!inHand.isEmpty()) inventory.setItem(best, inHand);
		setItemSlot(EquipmentSlot.MAINHAND, chosen);
		inventory.setChanged();
	}

	/** Puts on any armor in her inventory that's better than what she wears */
	boolean wearBestArmor() {
		boolean changed = false;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack s = inventory.getItem(i);
			Equippable eq = s.get(DataComponents.EQUIPPABLE);
			if (eq == null) continue;
			EquipmentSlot slot = eq.slot();
			if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) continue;
			ItemStack worn = getItemBySlot(slot);
			if (Ranks.armor(s) > Ranks.armor(worn)) {
				inventory.setItem(i, worn.isEmpty() ? ItemStack.EMPTY : worn);
				setItemSlot(slot, s);
				playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.0F, 1.0F);
				Companion.event("wore", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath());
				changed = true;
			}
		}
		if (changed) inventory.setChanged();
		return changed;
	}

	/** Eats the best food she has; true if she ate */
	boolean eat() {
		int bestSlot = -1;
		int bestNutrition = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack s = inventory.getItem(i);
			FoodProperties food = s.get(DataComponents.FOOD);
			if (food != null && !Ranks.badFood(s) && food.nutrition() > bestNutrition) {
				bestNutrition = food.nutrition();
				bestSlot = i;
			}
		}
		if (bestSlot < 0) return false;
		ItemStack s = inventory.getItem(bestSlot);
		String name = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		s.shrink(1);
		inventory.setChanged();
		heal(bestNutrition);
		level().playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 1.0F, 1.0F);
		Companion.event("ate", name);
		return true;
	}

	/** Picks up items lying right next to her (not the ones she threw to you) */
	void pickUpNearby() {
		if (!(level() instanceof ServerLevel sl)) return;
		List<ItemEntity> items = sl.getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(1.5, 0.75, 1.5), e -> e.isAlive() && !e.hasPickUpDelay() && e.getOwner() != this);
		for (ItemEntity item : items) {
			ItemStack stack = item.getItem();
			if (!inventory.canAddItem(stack)) continue;
			int before = stack.getCount();
			ItemStack rest = inventory.addItem(stack.copy());
			take(item, before - rest.getCount());
			if (rest.isEmpty()) item.discard();
			else item.setItem(rest);
		}
	}

	/**
	 * With her bag full, she leaves behind the plain stuff she picked up on the way (dirt, gravel,
	 * extra stone) so there's room for what matters. Keeps a stack of cobblestone for filling gaps.
	 */
	void tidy() {
		boolean full = true;
		for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i).isEmpty()) full = false;
		if (!full || !(level() instanceof ServerLevel sl)) return;
		java.util.Set<net.minecraft.world.item.Item> junk = java.util.Set.of(
			net.minecraft.world.item.Items.DIRT, net.minecraft.world.item.Items.GRAVEL, net.minecraft.world.item.Items.GRANITE,
			net.minecraft.world.item.Items.DIORITE, net.minecraft.world.item.Items.ANDESITE, net.minecraft.world.item.Items.TUFF,
			net.minecraft.world.item.Items.COBBLED_DEEPSLATE, net.minecraft.world.item.Items.NETHERRACK, net.minecraft.world.item.Items.COBBLESTONE,
			net.minecraft.world.item.Items.ROTTEN_FLESH, net.minecraft.world.item.Items.WHEAT_SEEDS, net.minecraft.world.item.Items.BIRCH_SAPLING,
			net.minecraft.world.item.Items.OAK_SAPLING, net.minecraft.world.item.Items.SPRUCE_SAPLING, net.minecraft.world.item.Items.CALCITE,
			net.minecraft.world.item.Items.LEAF_LITTER, net.minecraft.world.item.Items.DARK_OAK_SAPLING, net.minecraft.world.item.Items.JUNGLE_SAPLING);
		int keep = -1;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack s = inventory.getItem(i);
			if (s.is(net.minecraft.world.item.Items.COBBLESTONE) && (keep < 0 || s.getCount() > inventory.getItem(keep).getCount())) keep = i;
		}
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack s = inventory.getItem(i);
			if (s.isEmpty() || !junk.contains(s.getItem()) || i == keep) continue;
			ItemEntity dropped = new ItemEntity(sl, getX(), getY() + 0.5, getZ(), inventory.removeItemNoUpdate(i));
			dropped.setThrower(this);
			dropped.setPickUpDelay(40);
			sl.addFreshEntity(dropped);
		}
		inventory.setChanged();
	}

	/** Throws a stack towards the player */
	void throwTo(Entity target, ItemStack stack) {
		if (!(level() instanceof ServerLevel sl) || stack.isEmpty()) return;
		ItemEntity item = new ItemEntity(sl, getX(), getEyeY() - 0.3, getZ(), stack);
		Vec3 dir = target.position().add(0, 0.5, 0).subtract(item.position());
		Vec3 v = dir.normalize().scale(Math.min(0.45, 0.12 + dir.length() * 0.06));
		item.setDeltaMovement(v.x, v.y + 0.15, v.z);
		item.setThrower(this);
		item.setPickUpDelay(15);
		sl.addFreshEntity(item);
		swingForAttack(InteractionHand.MAIN_HAND);
	}

	AABB around(double r) {
		return getBoundingBox().inflate(r);
	}
}
