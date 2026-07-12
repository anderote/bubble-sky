# Coordination Board

Live status for who's doing what, and the branch structure. **Update your own row** when you
start, switch, or finish work. For detail, append to your own notebook in [`journal/`](journal/)
— per-person files never merge-conflict; this board's rows are one-line-per-person on purpose.

_Last board update: 2026-07-11_

---

## Who's working on what

| Person | Agent / tool | Workstream | Current branch | Focus right now | Updated |
|--------|--------------|------------|----------------|-----------------|---------|
| Andrew (`anderote`) | Claude Code | **A — Server & Modding** | `ws-a/server-setup` | P0/P1 ✅ server up; ripping through P4/P5 (mod workspace) | 2026-07-11 |
| _Codex collaborator_ | Codex | **B — AI Agents** | _tbd_ | P2/P3: MCP wiring + mindcraft bot | _tbd_ |

> Workstream ownership is defined in [`README.md`](README.md#work-division-2-collaborators).
> Stay in your lane's directories to avoid stepping on each other:
> **A** owns `server/` + `mods/`; **B** owns `mcp/` + `mindcraft/`.

---

## Branch registry

| Branch | Owner | Purpose | Base | Status |
|--------|-------|---------|------|--------|
| `main` | — | Integration / source of truth. Protected — PRs only. | — | 🟢 protected |
| `ws-a/server-setup` | Andrew | Fabric 1.21.6 server install + config (P0/P1) | `main` | 🟢 active |
| `ws-b/…` | Codex | AI-agent lane branches (MCP, mindcraft) | `main` | ⚪ tbd |

**Status legend:** 🟢 active · 🟡 in review (PR open) · 🔵 merged · ⚪ planned · 🔴 stale/abandoned

---

## Conventions

- **Branch naming:** `<workstream>/<short-topic>` — e.g. `ws-a/fabric-server`, `ws-b/mcp-wiring`.
- **Don't commit directly to `main`.** Work on a branch, open a PR, merge when green.
- **One owner per workstream lane** (see README). If you need to touch the other lane's
  directories, say so in your journal + ping the owner.
- **Coexistence rule holds:** bot-facing mods stay vanilla-compatible (see
  [`ARCHITECTURE.md` §5](ARCHITECTURE.md)).
- **When you start/switch/finish work:** update your row above **and** append a dated line to
  `journal/<you>.md`.
- **Register new branches** in the table above so everyone sees the structure.

---

## Sync rhythm (keep collaborators in step)

We're two agents/humans on one repo — sync often so nobody drifts or duplicates work.

- **Pull `main` before every session:** `git switch main && git pull`, then rebase your
  branch onto it (`git switch <your-branch> && git rebase main`).
- **Push your branch frequently** — at least at every phase checkpoint and end of session,
  not just when a workstream is "done". Small, frequent pushes beat one big drop.
- **Open a PR early** (draft is fine) so the other person can see your direction; merge to
  `main` once the phase's done-criteria pass.
- **After any merge to `main`,** everyone pulls `main` and rebases their active branch.
- **Status travels with code:** update your journal + your board row in the same push.
- **Flag cross-lane changes** (anything touching the coexistence contract or the other lane's
  dirs) in the activity log *before* merging.
- **Rough cadence:** push at least once per phase; pull `main` at the start of each session
  and whenever the other person announces a merge.

## Agent-to-agent comms

We talk to each other through **GitHub commit & PR comments** (async, tied to the exact code).

- Leave decisions, questions, and 👍/LGTM as **commit comments** on the relevant commit, or as
  **PR review comments** when a PR is open.
- Sign your note (`~ Codex`, `~ Andrew/Claude`) so it's clear who's speaking.
- For anything the whole team needs to remember, promote it from a comment into the **Activity
  log** or **Conventions** here so it isn't lost in commit history.
- Roster: **Andrew / Claude Code** (`anderote`), **Codex** (`allibell`).

---

## Activity log (cross-team highlights only)

Newest first. Keep this to notable shared events — day-to-day detail goes in your journal.

- **2026-07-11** — Andrew (Claude Code) — Workstream A P0/P1 ✅: Java 21 pinned + Fabric 1.21.6 server
  (`online-mode=false`) boots on :25565. Branch `ws-a/server-setup` pushed. Added Sync-rhythm
  conventions. Continuing through P4/P5 (mod dev workspace).
- **2026-07-11** — Andrew (Claude Code) — Repo scaffolded; README, ARCHITECTURE, design spec, and this
  coordination system added. Starting Workstream A (server) on `ws-a/server-setup`. Codex to take
  Workstream B.
