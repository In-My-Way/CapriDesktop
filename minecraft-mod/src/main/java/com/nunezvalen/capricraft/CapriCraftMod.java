package com.nunezvalen.capricraft;

import com.nunezvalen.capricraft.companion.CapriEntity;
import com.nunezvalen.capricraft.companion.Companion;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * The part of the mod that lives in the world itself: Capri as a companion who joins your world,
 * with a body, an inventory and a skin of her own. It runs on the singleplayer (or LAN host) server
 * that the game starts inside the same process.
 */
public final class CapriCraftMod implements ModInitializer {
	public static final ResourceKey<EntityType<?>> CAPRI_KEY = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("capricraft", "capri"));
	public static final EntityType<CapriEntity> CAPRI = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		CAPRI_KEY,
		EntityType.Builder.of(CapriEntity::new, MobCategory.MISC).sized(0.6F, 1.8F).eyeHeight(1.62F).clientTrackingRange(10).build(CAPRI_KEY)
	);

	@Override
	public void onInitialize() {
		FabricDefaultAttributeRegistry.register(CAPRI, CapriEntity.createAttributes());
		ServerLifecycleEvents.SERVER_STARTED.register(Companion::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(Companion::onServerStopping);
		ServerTickEvents.END_SERVER_TICK.register(Companion::onServerTick);
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> Companion.onChat(sender, message.signedContent()));
	}
}
