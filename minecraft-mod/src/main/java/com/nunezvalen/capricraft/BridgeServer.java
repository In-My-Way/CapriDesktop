package com.nunezvalen.capricraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The door Capri knocks on: a tiny HTTP server on 127.0.0.1 only. It never touches the game itself:
 * commands are queued for the client thread, and status is a snapshot the game thread keeps fresh.
 *
 *   GET  /ping           {"ok":true,"version":"…","inWorld":true}
 *   GET  /status?since=N the current task, its step and the events after N
 *   POST /task           {"action":"get","item":"diamond","count":3} | {"action":"mine","block":"oak_log","count":10}
 *                        | {"action":"goto","x":0,"y":64,"z":0} | {"action":"stop"}
 *   GET  /companion/status?since=N  Capri's body in the world: where she is, what she carries, what
 *                        she's doing, and events (including what the player typed in the chat)
 *   POST /companion      {"action":"join"|"leave"|"follow"|"stay"|"come"|"stop"} | {"action":"get","item":"diamond","count":3,"give":true}
 *                        | {"action":"mine","block":"sand","count":20} | {"action":"kill","mob":"zombie","count":3}
 *                        | {"action":"build","what":"house","material":"stone"} | {"action":"give","item":"all"}
 *                        | {"action":"say","text":"..."} (in the game's chat)
 */
public final class BridgeServer implements Runnable {
	public static final int PORT = 25599;
	private final TaskRunner runner;
	private ServerSocket server;

	public BridgeServer(TaskRunner runner) {
		this.runner = runner;
	}

	public void start() {
		Thread thread = new Thread(this, "CapriCraft-Bridge");
		thread.setDaemon(true);
		thread.start();
	}

	@Override
	public void run() {
		try {
			server = new ServerSocket(PORT, 8, InetAddress.getLoopbackAddress());
			CapriCraftClient.LOG.info("[CapriCraft] Waiting for Capri on 127.0.0.1:{}", PORT);
			while (!server.isClosed()) {
				Socket socket = server.accept();
				Thread worker = new Thread(() -> handle(socket), "CapriCraft-Request");
				worker.setDaemon(true);
				worker.start();
			}
		} catch (IOException e) {
			CapriCraftClient.LOG.error("[CapriCraft] The bridge could not open port {}: {}", PORT, e.getMessage());
		}
	}

	private void handle(Socket socket) {
		try (socket) {
			socket.setSoTimeout(5000);
			BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			String requestLine = in.readLine();
			if (requestLine == null) return;
			String[] parts = requestLine.split(" ");
			String method = parts.length > 0 ? parts[0] : "";
			String target = parts.length > 1 ? parts[1] : "/";

			int length = 0;
			String origin = null;
			String line;
			while ((line = in.readLine()) != null && !line.isEmpty()) {
				int colon = line.indexOf(':');
				if (colon < 0) continue;
				String name = line.substring(0, colon).trim().toLowerCase();
				String value = line.substring(colon + 1).trim();
				if (name.equals("content-length")) length = Math.min(Integer.parseInt(value), 64 * 1024);
				if (name.equals("origin")) origin = value;
			}
			// A web page can reach 127.0.0.1 too: anything coming from a browser is turned away
			if (origin != null) {
				respond(socket, 403, "{\"ok\":false,\"error\":\"forbidden\"}");
				return;
			}
			char[] body = new char[length];
			int read = 0;
			while (read < length) {
				int n = in.read(body, read, length - read);
				if (n < 0) break;
				read += n;
			}
			String bodyText = new String(body, 0, read);

			String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
			String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";

			if (method.equals("GET") && path.equals("/ping")) {
				respond(socket, 200, runner.pingJson());
			} else if (method.equals("GET") && path.equals("/status")) {
				long since = 0;
				for (String pair : query.split("&")) {
					if (pair.startsWith("since=")) {
						try { since = Long.parseLong(pair.substring(6)); } catch (NumberFormatException ignored) {}
					}
				}
				respond(socket, 200, runner.statusJson(since));
			} else if (method.equals("GET") && path.equals("/companion/status")) {
				long since = 0;
				for (String pair : query.split("&")) {
					if (pair.startsWith("since=")) {
						try { since = Long.parseLong(pair.substring(6)); } catch (NumberFormatException ignored) {}
					}
				}
				respond(socket, 200, com.nunezvalen.capricraft.companion.Companion.statusJson(since));
			} else if (method.equals("POST") && path.equals("/companion")) {
				JsonObject order;
				try {
					order = JsonParser.parseString(bodyText).getAsJsonObject();
				} catch (RuntimeException e) {
					respond(socket, 400, "{\"ok\":false,\"error\":\"bad_json\"}");
					return;
				}
				if (!com.nunezvalen.capricraft.companion.Companion.serverRunning()) {
					respond(socket, 409, "{\"ok\":false,\"error\":\"not_in_world\"}");
					return;
				}
				com.nunezvalen.capricraft.companion.Companion.submit(order);
				respond(socket, 202, "{\"ok\":true}");
			} else if (method.equals("POST") && path.equals("/task")) {
				JsonObject task;
				try {
					task = JsonParser.parseString(bodyText).getAsJsonObject();
				} catch (RuntimeException e) {
					respond(socket, 400, "{\"ok\":false,\"error\":\"bad_json\"}");
					return;
				}
				runner.submit(task);
				respond(socket, 202, "{\"ok\":true}");
			} else if (method.equals("POST") && path.equals("/dev/command") && net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
				// Only in the development client (gradlew runClient): a chat command, to set up a test
				String command = bodyText.trim();
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
				mc.execute(() -> {
					if (mc.player != null) mc.player.connection.sendCommand(command.startsWith("/") ? command.substring(1) : command);
				});
				respond(socket, 202, "{\"ok\":true}");
			} else if (method.equals("POST") && path.equals("/dev/chat") && net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
				// Only in the development client: the player types this in the chat
				String text = bodyText.trim();
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
				mc.execute(() -> {
					if (mc.player != null) mc.player.connection.sendChat(text);
				});
				respond(socket, 202, "{\"ok\":true}");
			} else if (method.equals("POST") && path.equals("/dev/screenshot") && net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
				// Only in the development client: the game window as a PNG in run/screenshots (to check how she looks)
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
				mc.execute(() -> net.minecraft.client.Screenshot.grab(mc, false));
				respond(socket, 202, "{\"ok\":true}");
			} else if (method.equals("POST") && path.equals("/dev/newworld") && net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
				// Only in the development client, from the title screen: a brand-new survival world (cheats on) named by the body
				String name = bodyText.trim().isEmpty() ? "Capri Test" : bodyText.trim();
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
				mc.execute(() -> {
					if (mc.level != null) return;
					net.minecraft.world.level.LevelSettings settings = new net.minecraft.world.level.LevelSettings(
						name,
						net.minecraft.world.level.GameType.SURVIVAL,
						new net.minecraft.world.level.LevelSettings.DifficultySettings(net.minecraft.world.Difficulty.NORMAL, false, false),
						true,
						net.minecraft.world.level.WorldDataConfiguration.DEFAULT
					);
					String folder;
					try {
						folder = net.minecraft.util.FileUtil.findAvailableName(mc.getLevelSource().getBaseDir(), name, "");
					} catch (IOException e) {
						folder = name + " " + System.currentTimeMillis();
					}
					mc.createWorldOpenFlows().createFreshLevel(
						folder,
						settings,
						net.minecraft.world.level.levelgen.WorldOptions.defaultWithRandomSeed(),
						net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions,
						new net.minecraft.client.gui.screens.TitleScreen()
					);
				});
				respond(socket, 202, "{\"ok\":true}");
			} else if (method.equals("POST") && path.equals("/dev/respawn") && net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
				mc.execute(() -> {
					if (mc.player != null && mc.player.isDeadOrDying()) {
						mc.player.respawn();
						mc.gui.setScreen(null);
					}
				});
				respond(socket, 202, "{\"ok\":true}");
			} else {
				respond(socket, 404, "{\"ok\":false,\"error\":\"not_found\"}");
			}
		} catch (IOException | RuntimeException e) {
			CapriCraftClient.LOG.warn("[CapriCraft] Request failed: {}", e.getMessage());
		}
	}

	private static void respond(Socket socket, int status, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		String reason = switch (status) {
			case 200 -> "OK";
			case 202 -> "Accepted";
			case 400 -> "Bad Request";
			case 403 -> "Forbidden";
			case 409 -> "Conflict";
			default -> "Not Found";
		};
		String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
			+ "Content-Type: application/json; charset=utf-8\r\n"
			+ "Content-Length: " + bytes.length + "\r\n"
			+ "Connection: close\r\n\r\n";
		OutputStream out = socket.getOutputStream();
		out.write(head.getBytes(StandardCharsets.US_ASCII));
		out.write(bytes);
		out.flush();
	}
}
