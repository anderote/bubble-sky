# mindcraft

Workstream B setup notes for autonomous LLM bots.

The upstream mindcraft repo is intentionally not committed here. Bootstrap it into the ignored
`mindcraft/upstream/` directory:

```sh
./mindcraft/bootstrap.sh
```

The bootstrap script locally pins dependency versions that match upstream's patch files inside
the ignored checkout. Those edits stay in `mindcraft/upstream/` and are not committed here.
Node is pinned to 22 because current Mineflayer/Minecraft protocol packages declare Node 22 as
their engine floor. Avoid Node 24+ for now because upstream mindcraft still warns about native
dependency issues there.

Create `mindcraft/upstream/keys.json` from the upstream template and put API keys there.
At minimum, configure an Anthropic key if the bot profile uses Claude:

```json
{
  "ANTHROPIC_API_KEY": "..."
}
```

Connection target for v1:

| Setting | Value |
|---------|-------|
| Host | `localhost` |
| Port | `25565` |
| Minecraft version | `1.21.6` |
| Server auth | offline mode |

Do not commit `keys.json`, `.env`, or the cloned upstream repo. The local clone can be
recreated at any time from GitHub.
