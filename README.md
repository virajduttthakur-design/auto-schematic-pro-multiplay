# Auto Schematic Pro

Fabric mod (client + server) for Minecraft Java Edition 1.21.11. It provides a glass-style schematic panel, Sponge `.schem` selection, a material summary, a build origin selector, and double-chest material loading/restocking.

## Multiplayer

The mod works in single-player, Open-to-LAN and on dedicated servers. **It must be installed on both the server and every client that uses it** (plus Fabric API on both); it cannot work on a vanilla or unmodded server.

How it works: the client uploads the selected schematic to the server in chunks (vanilla limits client-to-server packets to 32 KiB), and the **server** does all the placing, item removal and chest access, then streams progress back to the GUI. Single-player uses the same code path through the integrated server.

Server-side safeguards (all enforced by the server, never trusted from the client):

- **Permission:** on dedicated servers nobody may build until listed in `allowedPlayers` (or `allowEveryone` is set). Integrated servers default to everyone allowed.
- Spawn protection, the world border, and claim/protection mods that hook Fabric's `UseBlockCallback` are respected; protected positions are skipped.
- Existing non-replaceable blocks are not overwritten on dedicated servers (`overwriteExistingBlocks`), and blocks with block entities (chests, signs, ...) are never overwritten.
- The build origin and supply chest must be near the player when the build starts; schematic size, palette size, upload size and concurrent builds are capped.
- Only the 36 main inventory slots are used (never armor or off-hand). A build stops if its player leaves.

Config file `config/auto_schematic_pro.json` (created on first start, on whichever side hosts the world):

| Key | Default (dedicated) | Meaning |
| --- | --- | --- |
| `allowEveryone` | `false` | Let every player use the builder |
| `allowedPlayers` | `[]` | Names (case-insensitive) or UUIDs allowed when `allowEveryone` is false |
| `maxBlocks` | `500000` | Largest accepted schematic, in placed blocks |
| `maxOriginDistance` | `128` | Max horizontal distance from the player to the origin |
| `maxChestDistance` | `16` | Max distance from the player to the supply chest |
| `placeIntervalTicks` | `2` | Server ticks between placements |
| `maxConcurrentBuilds` | `4` | Simultaneous builds on the server |
| `overwriteExistingBlocks` | `false` | Allow replacing existing solid blocks |

Limitations: block entities, entities and biome data are still not restored. `.schem` files are read from the **client's** `schematics` folder.

## Use

1. Build the mod with Java 21 and Gradle 8.14 or newer: `gradle build`.
2. Copy `build/libs/auto-schematic-pro-1.0.0.jar` into the `mods` folder of the Fabric 1.21.11 client **and** server, with Fabric API installed on both.
3. Put Sponge `.schem` files in the client's Minecraft game directory `schematics` folder.
4. On a dedicated server, add your name to `allowedPlayers` in the server's `config/auto_schematic_pro.json` and restart (or set `allowEveryone`).
5. Press **G** to open the panel and select a schematic. Aim at the block for its lower corner and choose **Set origin from crosshair**.
6. Stand near the chest, aim at either half of a connected double chest and choose **Use double chest in crosshair**.
7. Choose **Start build**. Required items are moved from the chest into your inventory before building; if more are needed later, the builder restocks from the chest and pauses when a material is unavailable.

The builder places one block every two server ticks by default. **Stop build** cancels your active build.

## Development

The project targets Minecraft 1.21.11, Yarn `1.21.11+build.6`, Fabric Loader `0.18.4`, and Fabric API `0.141.6+1.21.11`. Open it as a Gradle project or run `gradle build` from this directory.
