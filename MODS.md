# bubble-sky — modded client/server setup

The server is a **modded** Fabric **1.21.6** server. To join, your client must run the
**same mod set** (custom blocks won't load otherwise). Below is exactly what's on the
server + how to set up a matching client.

## Loader / API
- **Fabric Loader** 0.19.3 (via PrismLauncher — instance `bubble-sky-1.21.6`)
- **Fabric API** `fabric-api-0.128.2+1.21.6.jar`

## Mods (must match server + client)
| Mod | File | Source (Modrinth slug) |
|---|---|---|
| Fabric API | `fabric-api-0.128.2+1.21.6.jar` | `fabric-api` |
| Blockus | `blockus-2.13.2+1.21.8.jar` | `blockus` |
| Macaw's Furniture | `mcw-furniture-3.4.1-mc1.21.6fabric.jar` | `macaws-furniture` |
| Macaw's Windows | `mcw-mcwwindows-2.4.2-mc1.21.6fabric.jar` | `macaws-windows` |
| Macaw's Doors | `mcw-doors-1.1.5-mc1.21.6fabric.jar` | `macaws-doors` |
| Macaw's Fences & Walls | `mcw-mcwfences-1.2.1-mc1.21.6fabric.jar` | `macaws-fences-and-walls` |
| Farmer's Delight | `FarmersDelight-1.21.8-3.3.3+refabricated.jar` | `farmers-delight-refabricated` |
| **Tower Defense (ours)** | `towerdefense-1.0.0.jar` | build from `mods/towerdefense/` |

(All are Fabric 1.21.6-compatible; the "1.21.8" builds declare `>=1.21.6`.)

## Client setup (PrismLauncher — mirrors Andrew's)
1. Create a **Fabric 1.21.6** instance named `bubble-sky-1.21.6` (Fabric Loader 0.19.3).
2. Put all the mod jars above into the instance's `.minecraft/mods/` folder.
   - **Asset mods:** download from Modrinth (links via the slugs above), or use the
     download loop in `scripts/` (the same curl-from-Modrinth commands the repo uses).
   - **Tower Defense jar:** `cd mods/towerdefense && JAVA_HOME=<jdk21> ./gradlew build`,
     then copy `build/libs/towerdefense-1.0.0.jar`.
3. (Optional) Xaero's Minimap for convenience — client-only, not required to join.

## Joining
- Multiplayer → add server `<Andrew's LAN IP>:25565` (default `192.168.86.188:25565`).
- Off-LAN needs a tunnel/port-forward (not covered here).
- Server runs offline-mode; use your normal username.

## Agents / bridge
- The mod runs an **agent bridge** on `127.0.0.1:25580` (token in
  `server/config/bubblesky-bridge.json`). Grok drives building over it; your swarm can too
  via `mcp/bridge-drone.mjs` (see `BRIDGE.md`).

## What's in the world
Tower-defense game (`/td`, or press **J** for the menu), the `acid` block, the **Layout
Wand** + **Flag Bow** (plant flags/regions), and Grok the AI builder (talks to `claudebert`).
