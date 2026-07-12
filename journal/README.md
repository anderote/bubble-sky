# Journals

Per-person, append-only notebooks. One file per collaborator: `journal/<name>.md`.

**Why per-person files:** two people editing one shared log causes merge conflicts. Your own
file never conflicts with anyone else's, so you can commit freely.

## How to use

- Create `journal/<yourname>.md` (copy the format below).
- **Append** dated entries at the top (newest first). Don't rewrite old entries.
- Log what you're starting, decisions you made, blockers, and what you finished.
- Keep the one-line status in [`COORDINATION.md`](../COORDINATION.md) in sync — the board is the
  quick glance; your journal is the detail.

## Entry format

```markdown
## 2026-07-11
- Started X on branch `ws-a/foo`.
- Decision: chose Y because Z.
- Blocked on: <thing> — need <person/input>.
- Done: <what landed>.
```
