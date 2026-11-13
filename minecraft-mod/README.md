# Capri Craft

A Fabric mod for Minecraft Java **26.3** that lets Capri (CapriDesktop) play as you. Ask her
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
| `GET /ping` | `{"ok":true,"version":"0.1.0","inWorld":true}` |
| `GET /status?since=N` | current task, its step, and the events after id N |
| `POST /task` | `{"action":"get","item":"diamond","count":3}`, `{"action":"mine","block":"oak_log","count":10}`, `{"action":"goto","x":0,"y":64,"z":0}`, `{"action":"stop"}` |

Items `get` understands: `diamond`, `iron_ingot`, `raw_iron`, `coal`, `iron_pickaxe`,
`stone_pickaxe`, `wooden_pickaxe`, `crafting_table`, `furnace`, `stick`, `planks`, `log`,
`cobblestone`, or any block id (it is mined).

## Build

Needs **JDK 25**.

1. Download `baritone-api-fabric-1.20.0.jar` from
   [Baritone v1.20.0](https://github.com/cabaletta/baritone/releases/tag/v1.20.0) into `libs/`.
2. Generate the Gradle wrapper once (needs a local Gradle 9.8+), or open the folder in IntelliJ:
   `gradle wrapper --gradle-version 9.8.2`
3. `./gradlew build` → `build/libs/capricraft-0.1.0.jar`

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for 26.3. Give that profile its
   **own game directory** in the launcher, so it doesn't load mods made for other versions.
2. In that profile's `mods` folder put:
   - Fabric API for 26.3
   - `baritone-standalone-fabric-1.20.0.jar`
   - `capricraft-0.1.0.jar`
3. Start the game, join a world, and ask Capri.
