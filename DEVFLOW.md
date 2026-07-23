# Bubble Sky dev flow: Minecraft ↔ Codex/Claude apps

Minecraft chat, the Codex app, and the Claude app are three doors into the same repository.
They do not share a proprietary chat session, so Station uses a durable job transcript as the
handoff boundary. This keeps the switch explicit and makes it possible to inspect what an agent
actually saw.

Use the lightest door for the job:

- Press **`B`** for exact, repeatable building (bridge, wall, flatten, tower pad, temporary
  tower stairs, or kill lane). It is instant, visual, configurable, previewable, and undoable;
  no agent or API key is involved.
- Use Minecraft chat (`@codex`, `@claude`, or `@dev`) when you want an agent but want to stay
  in the game.
- Use the Codex/Claude desktop apps for longer implementation, debugging, review, and PR work.
  Say “Pick up my latest Minecraft dev job” to bring the durable chat context with you.

## Start in Minecraft

After pairing with DevStation:

```text
@codex explain the tower upgrade code
@claude review the release flow
@codex work add a bridge contract test
@dev use claude
@dev ask what should we improve next?
@dev reply compare that with Codex's suggestion
@dev handoff
```

`@codex` and `@claude` start conversational jobs unless the next word is `work`.
`@dev use codex|claude` changes your short-command default. `@dev reply` continues the active
Station conversation with its original provider.

To move the active Minecraft thread into either desktop chat app, tell the app:

```text
Pick up my latest Minecraft dev job.
```

The repository instructions tell the app to run:

```sh
./scripts/station.mjs jobs
./scripts/station.mjs handoff latest
```

You can name a job when more than one is active:

```text
Continue Minecraft job chat-abc123.
```

## Start in a desktop chat app

Ask Codex or Claude normally. Both apps receive the same project map and safety rules through
`AGENTS.md`, `CLAUDE.md`, and this file. When the result matters to players, the app can post a
short update into Minecraft:

```sh
./scripts/station.mjs announce "Codex finished the bridge review; no protocol changes needed."
```

For implementation work, use a normal branch/worktree or ask Station from Minecraft with
`@codex work ...` / `@claude work ...`. Never have two agents edit the same worktree.

## Useful operator commands

```sh
./scripts/station.mjs jobs                 # recent jobs across both Macs
./scripts/station.mjs handoff latest       # full newest transcript for an app
./scripts/station.mjs handoff chat-abc123  # a particular transcript
./scripts/station.mjs announce "message"   # send [DEV] status into Minecraft
./scripts/station.mjs status
./scripts/station.mjs logs chat
```

If Station is offline, run `./scripts/station.mjs restart`. If the other Mac is offline, its
jobs will appear again when that peer is reachable.
