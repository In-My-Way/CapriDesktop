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
	private static Boolean pauseBefore = null;

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
		// Capri's own body in your world: her skin, and who she belongs to
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(CapriCraftMod.CAPRI, com.nunezvalen.capricraft.client.CapriRenderer::new);
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player != null) com.nunezvalen.capricraft.companion.Companion.localPlayer = mc.player.getUUID();
			// Baritone reads plain chat as its own commands ("build me a house" → a schematic called "me"):
			// the chat is for talking to Capri. Its "#" commands still work. Set every tick: its settings file loads late
			if (mc.player != null) {
				baritone.api.Settings bs = baritone.api.BaritoneAPI.getSettings();
				if (bs.chatControl.value) bs.chatControl.value = false;
				if (bs.chatControlAnyway.value) bs.chatControlAnyway.value = false;
			}
			// While she's in your world the game doesn't pause when you look at another window (to talk to her, say)
			boolean present = com.nunezvalen.capricraft.companion.Companion.present();
			if (present && pauseBefore == null) {
				pauseBefore = mc.options.pauseOnLostFocus;
				mc.options.pauseOnLostFocus = false;
			} else if (!present && pauseBefore != null) {
				mc.options.pauseOnLostFocus = pauseBefore;
				pauseBefore = null;
			}
		});
		new BridgeServer(runner).start();
	}
}
