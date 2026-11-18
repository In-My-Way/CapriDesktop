# Capri Craft

A Fabric mod for Minecraft Java **26.3** that brings Capri (CapriDesktop) into your world.

## Capri in your world

Say "unite a mi mundo" / "join my world" (by voice, in Capri's chat, or in the game's chat) and
Capri appears next to you with her own skin: a companion with a body, not your player. She follows
you, fights whatever attacks you or her, eats when she's hurt and puts on the best armor she has.
Ask her, by voice or in the Minecraft chat, and she answers out loud and in the chat:

- **Get anything:** "traeme 5 diamantes", "haceme un escudo", "conseguí comida". She reads the
  game's own recipes, so she works out the whole chain herself: chops trees, crafts the table and the
  pickaxes, digs a staircase down and strip-mines for ore (only what she can see), smelts it in her
  furnace, and hands it to you.
- **Mine:** "picá 20 de arena". **Kill:** "matá 3 zombies", "limpiá la zona de monstruos".
- **Build:** a house, a small shelter, a tower with a ladder, or a wall, in wood or stone
  ("construime una casa de piedra"). She gets the materials first if she doesn't have them.
- **Follow / wait / come:** "seguime", "quedate acá", "vení". **Give:** "dame todo", "dame la espada".
  **Go:** "andá a 100 64 -200". **Leave:** "salí del mundo".
- **Her inventory:** right-click her to open it, or ask "qué tenés".

When she dies she comes back next to you with everything she had (like keepInventory). Her things
are also saved in the world folder (`capricraft_capri.dat`), so they're there the next time she
joins. While she's in your world the game doesn't pause when you switch to another window.

The rest of this file is the older mode, where she takes over your own player.

## Capri as you

Capri can also play as you. Ask her
"andá a buscarme diamantes" and she takes over your player: chops wood, crafts a table, sticks and a
wooden pickaxe, mines stone for a stone pickaxe, finds and smelts iron, makes an iron pickaxe and
goes for the diamonds. Anything you already have is a step she skips. Press **K** at any time to
take control back.

Walking and mining are done by [Baritone](https://github.com/cabaletta/baritone); crafting and
smelting the mod does itself, with the same inventory clicks a player would make.

## How it talks to Capri

The mod listens on `127.0.0.1:25599` (loopback only, browser requests are refused):

| Request | What it does |
| --- | --- |
| `GET /ping` | `{"ok":true,"version":"0.2.0","inWorld":true,"companion":false,"server":true}` |
| `GET /status?since=N` | current task, its step, and the events after id N |
| `POST /task` | `{"action":"get","item":"diamond","count":3}`, `{"action":"mine","block":"oak_log","count":10}`, `{"action":"goto","x":0,"y":64,"z":0}`, `{"action":"stop"}` |
| `GET /companion/status?since=N` | Capri's body: where she is, health, inventory, what she's doing, and events (including what the player typed in the chat) |
| `POST /companion` | `{"action":"join"}`, `"leave"`, `"follow"`, `"stay"`, `"come"`, `"stop"`, `{"action":"get","item":"shield","count":1,"give":true}`, `{"action":"mine","block":"sand","count":20}`, `{"action":"kill","mob":"zombie","count":3}`, `{"action":"build","what":"house","material":"stone"}`, `{"action":"give","item":"all"}`, `{"action":"say","text":"..."}` |

Things `get` understands: `diamond`, `iron_ingot`, `raw_iron`, `coal`, `log`, `planks`, `stick`,
`cobblestone`, `crafting_table`, `furnace`, the `wooden_`, `stone_`, `iron_` and `diamond_pickaxe`,
`stone_sword`, `iron_sword`, `iron_armor` (or one piece: `iron_helmet`, `iron_chestplate`,
`iron_leggings`, `iron_boots`, which she also puts on), `food` (she hunts and cooks), `flint`,
`flint_and_steel`, `obsidian`, or any block id (it is mined). `{"action":"nether"}` gets whatever a
portal needs, builds it, lights it and walks in.

While she plays she eats when she's hungry, fights monsters that come for her, and leaves to the
title screen at two hearts. She mines ores only once she has seen them, and crafts with a cursor of
her own that moves at a person's pace.

In the development client (`gradlew runClient`) there are two more routes for setting up tests:
`POST /dev/command` (a chat command, like `give @s minecraft:obsidian 10`), `POST /dev/respawn`,
`POST /dev/chat` (types in the chat) and `POST /dev/screenshot` (saves one to `run/screenshots`).
They don't exist in a normal install.

## Build

Needs **JDK 25**.

1. Download `baritone-api-fabric-1.20.0.jar` from
   [Baritone v1.20.0](https://github.com/cabaletta/baritone/releases/tag/v1.20.0) into `libs/`.
2. Generate the Gradle wrapper once (needs a local Gradle 9.8+), or open the folder in IntelliJ:
   `gradle wrapper --gradle-version 9.8.2`
3. `./gradlew build` → `build/libs/capricraft-0.2.0.jar`

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for 26.3. Give that profile its
   **own game directory** in the launcher, so it doesn't load mods made for other versions.
2. In that profile's `mods` folder put:
   - Fabric API for 26.3
   - `baritone-api-fabric-1.20.0.jar` (the full Baritone; the "standalone" build hides the API this mod calls)
   - `capricraft-0.2.0.jar`
3. Start the game, join a world, and ask Capri.
