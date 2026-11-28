package com.nunezvalen.capricraft.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nunezvalen.capricraft.CapriCraftClient;
import com.nunezvalen.capricraft.CapriCraftMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Where the bridge (CapriDesktop) and Capri's body in the world meet. Orders come in from the
 * bridge thread and are handled on the server thread; what she does goes out as numbered events.
 *
 * Her inventory is also kept in a file of the world (capricraft_capri.dat), not only in her body:
 * if she was left in a far chunk and is called again, a new body takes the same things, and the old
 * one, if its chunk ever loads, sees it's out of date and goes away. That's how nothing gets duplicated
 * or lost, even after she dies.
 */
public final class Companion {
	private static final ConcurrentLinkedQueue<JsonObject> inbox = new ConcurrentLinkedQueue<>();
	private static final Deque<JsonObject> events = new ArrayDeque<>();
	// Starts at the clock, not 1: after a game restart the app (still waiting for ids above the last it saw) gets the new ones
	private static long nextEventId = System.currentTimeMillis();
	private static volatile String snapshot = "{\"present\":false}";

	/** The player she belongs to: whoever is playing on this computer */
	public static volatile UUID localPlayer = null;

	static volatile CapriEntity current = null;
	private static MinecraftServer server = null;
	private static CompoundTag storedBody = null;
	static int generation = 0;
	private static boolean storeDirty = false;
	private static int ticks = 0;

	private Companion() {}

	// ---- Bridge thread ----

	public static void submit(JsonObject order) {
		inbox.add(order);
	}

	public static boolean present() {
		CapriEntity body = current;
		return body != null && !body.isRemoved();
	}

	public static boolean serverRunning() {
		return server != null;
	}

	public static synchronized String statusJson(long since) {
		JsonObject out = JsonParser.parseString(snapshot).getAsJsonObject();
		JsonArray list = new JsonArray();
		for (JsonObject e : events) if (e.get("id").getAsLong() > since) list.add(e);
		out.add("events", list);
		return out.toString();
	}

	// ---- Anywhere (kept small and synchronized) ----

	public static synchronized void event(String kind, String detail) {
		JsonObject e = new JsonObject();
		e.addProperty("id", nextEventId++);
		e.addProperty("kind", kind);
		e.addProperty("detail", detail == null ? "" : detail);
		events.addLast(e);
		while (events.size() > 200) events.removeFirst();
		CapriCraftClient.LOG.info("[CapriCraft] Capri: {} {}", kind, detail);
	}

	// ---- Server thread ----

	/** Says it in the game's chat, as if she typed it */
	public static void say(String text) {
		if (server == null || text == null || text.isBlank()) return;
		String clean = text.replaceAll("\\[[a-zA-Z ]+\\]", "").replaceAll("\\s+", " ").trim();
		if (clean.isEmpty()) return;
		Component line = Component.literal("<")
			.append(Component.literal("Capri").withStyle(ChatFormatting.LIGHT_PURPLE))
			.append(Component.literal("> " + clean));
		server.getPlayerList().broadcastSystemMessage(line, false);
	}

	public static void onChat(ServerPlayer sender, String text) {
		if (text == null || text.isBlank()) return;
		if (localPlayer != null && !sender.getUUID().equals(localPlayer)) return;
		event("chat", text.trim());
	}

	public static void onServerStarted(MinecraftServer s) {
		server = s;
		storedBody = null;
		generation = 0;
		current = null;
		try {
			Path file = storeFile(s);
			if (Files.exists(file)) {
				CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
				generation = tag.getIntOr("Gen", 0);
				storedBody = tag.getCompound("Body").orElse(null);
			}
		} catch (Exception e) {
			CapriCraftClient.LOG.warn("[CapriCraft] Could not read Capri's saved inventory: {}", e.getMessage());
		}
	}

	public static void onServerStopping(MinecraftServer s) {
		CapriEntity body = current;
		if (body != null && !body.isRemoved()) remember(body);
		writeStore(s);
		current = null;
		server = null;
		snapshot = "{\"present\":false}";
	}

	public static void onServerTick(MinecraftServer s) {
		server = s;
		ticks++;
		JsonObject order;
		while ((order = inbox.poll()) != null) {
			try {
				handle(s, order);
			} catch (RuntimeException e) {
				CapriCraftClient.LOG.error("[CapriCraft] Capri could not follow the order {}", order, e);
				event("failed", "error:" + e.getClass().getSimpleName());
			}
		}
		CapriEntity body = current;
		if (body != null && body.isRemoved()) current = body = null;
		if (storeDirty && ticks % 600 == 0) writeStore(s);
		if (ticks % 10 == 0) snapshot = snapshotJson(body);
	}

	private static void handle(MinecraftServer s, JsonObject order) {
		String action = order.has("action") ? order.get("action").getAsString() : "";
		CapriEntity body = current != null && !current.isRemoved() ? current : null;
		ServerPlayer owner = owner(s);
		switch (action) {
			case "say" -> say(order.has("text") ? order.get("text").getAsString() : "");
			case "join" -> {
				if (owner == null) {
					event("failed", "no_player");
					return;
				}
				if (body != null) {
					body.teleportNear(owner);
					event("joined", "again");
				} else {
					spawn(owner);
				}
			}
			case "leave" -> {
				if (body == null) {
					event("left", "not_here");
					return;
				}
				remember(body);
				body.leave();
				current = null;
				writeStore(s);
				event("left", "");
			}
			default -> {
				if (body == null) {
					event("failed", "not_here");
					return;
				}
				body.brain.accept(order);
			}
		}
	}

	static ServerPlayer owner(MinecraftServer s) {
		if (s == null) return null;
		if (localPlayer != null) {
			ServerPlayer p = s.getPlayerList().getPlayer(localPlayer);
			if (p != null) return p;
		}
		List<ServerPlayer> players = s.getPlayerList().getPlayers();
		return players.isEmpty() ? null : players.getFirst();
	}

	private static void spawn(ServerPlayer owner) {
		ServerLevel level = owner.level();
		CapriEntity body = CapriCraftMod.CAPRI.create(level, EntitySpawnReason.MOB_SUMMONED);
		if (body == null) {
			event("failed", "could_not_spawn");
			return;
		}
		boolean returning = storedBody != null;
		if (returning) {
			try {
				body.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), storedBody));
			} catch (RuntimeException e) {
				CapriCraftClient.LOG.warn("[CapriCraft] Capri's saved body could not be read, she starts fresh: {}", e.getMessage());
			}
		}
		body.setUUID(UUID.randomUUID());
		body.generation = ++generation;
		if (body.getHealth() <= 0) body.setHealth(body.getMaxHealth());
		Vec3 spot = CapriEntity.safeSpotNear(level, owner.blockPosition(), owner.position());
		body.snapTo(spot.x, spot.y, spot.z, owner.getYRot() + 180, 0);
		body.setDeltaMovement(Vec3.ZERO);
		boolean added = level.addFreshEntity(body);
		CapriCraftClient.LOG.info("[CapriCraft] Capri's body {} gen {} at {} added={}", body.getUUID(), body.generation, body.blockPosition(), added);
		current = body;
		remember(body);
		writeStore(level.getServer());
		event("joined", returning ? "back" : "first");
	}

	/** Keeps a copy of her body (inventory, armor, health) in case this one is lost */
	static void remember(CapriEntity body) {
		if (body.generation != generation) return;
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, body.registryAccess());
		body.saveWithoutId(out);
		storedBody = out.buildResult();
		storeDirty = true;
	}

	private static void writeStore(MinecraftServer s) {
		if (s == null || storedBody == null) return;
		try {
			CompoundTag tag = new CompoundTag();
			tag.putInt("Gen", generation);
			tag.put("Body", storedBody);
			NbtIo.writeCompressed(tag, storeFile(s));
			storeDirty = false;
		} catch (Exception e) {
			CapriCraftClient.LOG.warn("[CapriCraft] Could not save Capri's inventory: {}", e.getMessage());
		}
	}

	private static Path storeFile(MinecraftServer s) {
		return s.getWorldPath(LevelResource.ROOT).resolve("capricraft_capri.dat");
	}

	private static String snapshotJson(CapriEntity body) {
		JsonObject out = new JsonObject();
		out.addProperty("present", body != null);
		out.addProperty("server", server != null);
		if (body == null) return out.toString();
		out.addProperty("mode", body.brain.mode());
		out.addProperty("task", body.brain.taskName());
		out.addProperty("step", body.brain.step());
		out.addProperty("debug", body.brain.debug);
		out.addProperty("health", Math.round(body.getHealth()));
		out.addProperty("maxHealth", Math.round(body.getMaxHealth()));
		BlockPos pos = body.blockPosition();
		out.addProperty("x", pos.getX());
		out.addProperty("y", pos.getY());
		out.addProperty("z", pos.getZ());
		out.addProperty("dimension", body.level().dimension().identifier().getPath());
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (int i = 0; i < body.getInventory().getContainerSize(); i++) {
			ItemStack stack = body.getInventory().getItem(i);
			if (!stack.isEmpty()) counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), stack.getCount(), Integer::sum);
		}
		JsonObject inv = new JsonObject();
		counts.forEach(inv::addProperty);
		out.add("inventory", inv);
		JsonObject worn = new JsonObject();
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack stack = body.getItemBySlot(slot);
			if (!stack.isEmpty()) worn.addProperty(slot.getName(), BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
		}
		out.add("equipment", worn);
		return out.toString();
	}
}
