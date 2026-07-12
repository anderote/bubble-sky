# Client setup

Client-only helpers for the Prism Launcher instance.

## Minimap

Install Fabric Loader, Fabric API, and Xaero's Minimap into the local Prism instance:

```sh
eval "$(fnm env --use-on-cd)"
fnm use
node ./client/install-minimap.mjs
```

Defaults:

| Setting | Default | Override |
|---------|---------|----------|
| Minecraft version | `1.21.6` | `MINECRAFT_VERSION` |
| Prism instance | `~/Library/Application Support/PrismLauncher/instances/bubble-sky-1.21.6` | `PRISM_INSTANCE_DIR` |

Restart Minecraft after running the installer. The minimap is client-side, so each
player should run this on their own machine. Nearby players show on the minimap when
the minimap settings allow player/entity radar and the server does not block it.
