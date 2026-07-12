# server/ — Fabric 1.21.6 dev server (Workstream A)

Local, persistent Minecraft Java **1.21.6** server on the Fabric loader, running in
`online-mode=false` so the AI-agent bots (Workstream B) can join without licenses.

Only source/config is committed here (`run.sh`, `server.properties.example`, this README,
`mods/.gitkeep`). The jars, world, and logs are **generated** — rebuild them with the steps
below. Nothing here requires owning Minecraft.

## Versions (pinned)

| Piece | Version |
|-------|---------|
| Minecraft | 1.21.6 |
| Fabric loader | 0.19.3 |
| Fabric installer | 1.1.1 |
| Fabric API (mod) | 0.128.2+1.21.6 |
| Java | Temurin 21 |

Don't bump Minecraft without re-checking Mineflayer/MCP/mindcraft alignment (see repo README).

## 1. Install Java 21 (once)

macOS default sudo/brew-cask needs a password and SDKMAN needs bash 4+, so the reproducible
path is a hermetic Temurin tarball (no admin rights):

```bash
mkdir -p ~/.jdks && cd ~/.jdks
curl -sL -o temurin21.tar.gz "https://api.adoptium.net/v3/binary/latest/21/ga/mac/aarch64/jdk/hotspot/normal/eclipse"
tar xzf temurin21.tar.gz && rm temurin21.tar.gz
# JAVA_HOME → ~/.jdks/jdk-21.0.11+10/Contents/Home
```

Alternatives: `brew install --cask temurin@21` (needs your sudo password) or any Java 21 JDK.
`run.sh` reads `JAVA_HOME`; if unset it defaults to the `~/.jdks` path above.

## 2. Rebuild the server (once, after cloning)

From the repo root:

```bash
export JAVA_HOME="$HOME/.jdks/jdk-21.0.11+10/Contents/Home"
curl -sL -o /tmp/fabric-installer.jar \
  "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.1/fabric-installer-1.1.1.jar"
"$JAVA_HOME/bin/java" -jar /tmp/fabric-installer.jar server \
  -mcversion 1.21.6 -loader 0.19.3 -dir server -downloadMinecraft
cp server/server.properties.example server/server.properties
```

Then drop Fabric API into `server/mods/`:

```bash
curl -sL -o server/mods/fabric-api-0.128.2+1.21.6.jar \
  "$(curl -s 'https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%221.21.6%22%5D&loaders=%5B%22fabric%22%5D' \
    | python3 -c "import sys,json;d=json.load(sys.stdin);f=d[0]['files'];p=next((x for x in f if x.get('primary')),f[0]);print(p['url'])")"
```

## 3. Run

```bash
./server/run.sh      # first run generates the world; "Done (…)!" means ready on :25565
```

Stop with `Ctrl-C` (world saves on shutdown) or type `stop` in the console.

## Config notes

- `online-mode=false` — **required** so bots join with plain offline usernames.
- `gamemode=creative`, `difficulty=peaceful` — calm sandbox for mod + agent testing; change freely.
- To connect as a human you need Minecraft **Java Edition**; add the server as `localhost`.

## Mods

Compiled mod jars go in `server/mods/`. Keep **bot-facing mods vanilla-compatible** — see the
coexistence rule in [`../ARCHITECTURE.md`](../ARCHITECTURE.md). Mod dev workspace lives in
[`../mods/`](../mods/).
