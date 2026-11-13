package com.nunezvalen.capricraft;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Capri Craft: CapriDesktop asks, and Capri plays as you. She listens on 127.0.0.1:25599 and runs
 * whatever task she gets on the game's own tick, so every click and step happens like the player's.
 */
public final class CapriCraftClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("capricraft");
	public static String VERSION = "0.0.0";

	@Override
	public void onInitializeClient() {
		VERSION = FabricLoader.getInstance().getModContainer("capricraft")
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("0.0.0");
		TaskRunner runner = new TaskRunner();
		ClientTickEvents.END_CLIENT_TICK.register(runner::tick);
		// Her cursor is drawn over every menu she might work in
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof AbstractContainerScreen<?>) ScreenEvents.afterExtract(screen).register(runner.hand::render);
		});
		new BridgeServer(runner).start();
	}
}
