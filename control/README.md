# Bubble Sky Station vNext

Station turns each collaborator's Mac into a small, named Bubble Sky node. A node advertises
its roles and installed agent providers, routes conversational or development prompts to a
peer over HTTP, reports progress into Minecraft, and stages checksum-locked play releases.

## First-time setup on each Mac

On Andrew's Mac:

```sh
git pull --ff-only origin main
./scripts/station.mjs setup andrew
```

On Alli's Mac, use `./scripts/station.mjs setup alli`. The guided setup finds the local agent
CLI, asks for the Minecraft/peer addresses, writes a private mode-`0600` config, checks the
machine, and offers to install the background services. Ask the already-configured collaborator
for the shared Station token over a private channel; both Macs need the same value. To pass it
without putting it in shell history:

```sh
read -rs BUBBLE_STATION_TOKEN && export BUBBLE_STATION_TOKEN
./scripts/station.mjs setup andrew
unset BUBBLE_STATION_TOKEN
```

The default `.local` peer names work on a shared LAN. Stable Tailscale addresses also work.
Port `25880` must only be reachable over that trusted network. Run these any time:

```sh
./scripts/station.mjs doctor
./scripts/station.mjs status
./scripts/station.mjs pair-code
./scripts/station.mjs logs station
```

For manual/custom configuration, start from `control/station.example.json` (Alli/Codex) or
`control/station.andrew.example.json` (Andrew/Claude), save it as
`~/.config/bubble-sky/station.json`, then run `scripts/install-station.sh`. Template providers
may define separate `chatArgs` and `devArgs`; the Andrew example enables Claude's edit permission
only for isolated dev jobs.

A repository admin runs `scripts/configure-github-protection.sh` once. This enables
auto-merge and requires the control-plane check, both mod builds, one fresh partner approval,
resolved conversations, and linear history. A collaborator with only `WRITE` access cannot
configure this rule.

The installer creates three macOS LaunchAgents: Station, the Minecraft chat gateway, and the
release watcher. Logs are in `~/Library/Logs/BubbleSky/`.

## Minecraft terminal

Because the server runs in offline mode, player names are not authentication. Station creates
a six-digit code at startup. Find it in `station.log`, then whisper the bot:

```text
/msg DevStation pair 123456
```

Paired commands:

```text
@dev agents
@dev ask codex explain the current tower architecture
@dev ask andrew-mac/claude review the bridge API
@dev work codex add a bridge contract test
@dev work andrew-mac/claude fix poison tower targeting
@dev reply chat-abc123 keep going and compare both approaches
@dev status dev-abc123
@dev later 10mins
@dev postpone for half an hour
```

`ask` preserves the agent session for `reply`. `work` starts from `origin/main` in a separate
worktree, lets the selected agent implement and test the request, commits and pushes the result,
opens a PR, and requests GitHub auto-merge. Branch protection remains the authority: CI and the
other collaborator's approval must pass before merge.

## Release flow

Every merge to `main` runs `.github/workflows/release.yml`, builds both local mods, downloads
the exact third-party files in `release/mods.lock.json`, verifies SHA-256 values, and publishes
`bubble-sky-release.tgz` under an immutable `play-<git-sha>` GitHub release.

Both watchers download and verify it, report readiness, and wait until every configured peer is
ready. Each Station announces a five-minute countdown. Natural durations such as
`@dev later 10mins`, `@dev wait 1h 30m`, or `@dev postpone for half an hour` postpone it. Role-specific
deployment then closes Minecraft, swaps the complete mods directory, snapshots/stops/restarts the
server when applicable, restarts configured agents, launches Prism with `--server`, and reports
success. Previous mod directories remain available for automatic rollback on failure.

Test a bundle without GitHub or waiting:

```sh
node scripts/build-release.mjs /tmp/bubble-release
node control/deploy-release.mjs ~/.config/bubble-sky/station.json --bundle=/tmp/bubble-release --now
```

For a non-destructive first test, point `deployment.prismRoot` and `deployment.serverDir` at
throwaway directories and set `autoLaunchPrism` and `autoCloseMinecraft` to `false`.

## Security boundary

- Do not expose Station directly to the public internet.
- Keep the repository private before enabling unattended agents or self-hosted execution.
- Agent work runs in isolated worktrees. Codex uses `workspace-write` for dev and `read-only`
  for conversation; custom provider commands should use equivalent restrictions.
- Release hooks are explicit configuration (`agentRestartCommands`), not arbitrary scripts
  discovered in a PR.
- Deployment never pulls into or resets a developer checkout.
