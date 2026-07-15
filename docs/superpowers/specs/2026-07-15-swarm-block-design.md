# Swarm Block — Enemy AI Warlord & LLM Colonists — Design

_Date: 2026-07-15 · Mod: `mods/towerdefense` (Fabric 1.21.6) + `grok/` (Node agent)_

## Architecture decision (recorded)

**We use Family B: an LLM orchestrator drives the game through our existing mod
bridge / commands — NOT an external neural motor policy.**

Two families of "controllable Minecraft AI" exist:
- **Family A — neural motor policies** (VPT, STEVE-1, Pan-1): pixels→keyboard/mouse,
  ~30s horizon, unreliable (~38–90% on narrow tasks), and blind to modded content.
  Hierarchical papers (JARVIS-1/DEPS/Plan4MC) confirm the low-level policy is the
  bottleneck ("frequently become the performance bottleneck due to repeated failures").
- **Family B — code/command agents** (Voyager, mindcraft, VillagerAgent): LLM brain,
  reliable structured execution via Mineflayer/commands.

We already built the Family-B substrate: the mod's HTTP **bridge** (`bridge/`, :25580)
exposes `/command /setblock /fill /batch` + world reads `/block /region /scan` + `/chat
/players /status /flags`, and **Grok** (`grok/`) is an LLM orchestrator over it. For a
game we fully control, deterministic commands beat a ~40%-reliable pixel policy, and only
Family B is mod-aware. **Escape hatch:** if we ever want no-godmode *legit-survival*
embodiment, [STEVE-1](https://github.com/Shalev-Lifshitz/STEVE-1) (open, text-instructable)
would sit *below* our planner — a future novelty experiment, not a dependency.

## #17 — Enemy AI Warlord (LLM wave director)

Make waves a **living, adapting, taunting opponent** instead of pure procedural scaling.
An LLM "Warlord" persona directs each wave from the Node side, over the bridge.

### Principles
- **Additive + graceful degradation.** WaveManager keeps its current procedural scaling.
  A submitted wave plan is used only if present and valid; if the Warlord is offline or
  errors, the game plays exactly as today.
- **Bounded.** The mod computes a per-wave **threat budget** (from current difficulty).
  The Warlord chooses *distribution + tactics* within that budget; the mod validates and
  clamps so the LLM can't trivially nuke or trivialize a wave.

### Mod side (17a) — the interface
New bridge endpoints (in `bridge/BridgeHandlers.java`, reading a new director state):
- `GET /td/battlefield` → JSON: `wave`, `waveInProgress`, `intermission`, `idol{pos,hp,max}`,
  `towers[{type,x,y,z,tier}]`, `players[{name,class,x,y,z,level}]`, `lastWave{number,
  spawned,leaked,killedByTowers,killedByPlayers,durationTicks}`, `budget` (threat units the
  next wave may use), `enemyTypes[]` (ids selectable) with per-type threat cost.
- `POST /td/waveplan {wave, composition{enemyId:count}, spawnEmphasis(point or weights),
  tactic, taunt}` → validated against the budget for that wave, clamped, stored as the plan
  for the upcoming wave.
- `POST /td/taunt {text}` → broadcast a Warlord chat line.

`game/WaveManager.java` director hook: when starting wave N, if a valid plan for N exists,
build the spawn set from its composition + spawn emphasis (within budget); else default.
A small `game/WarlordDirector.java` holds the pending plan + last-wave telemetry and the
threat-budget/cost math. Telemetry (spawned/leaked/killed-by) recorded as the wave runs.

### Agent side (17b) — the brain (`grok/warlord.js`)
A small Node agent (own process/mode, reuses `lib/bridge` + `lib/llm` + Claude Opus
architect). Loop: on intermission → `GET /td/battlefield` → LLM produces a wave plan
(probe the weakest flank from tower distribution, pick composition/tactic within budget) +
an in-character taunt → `POST /td/waveplan`. On wave clear → read outcome → adapt next
wave. Persona: a necromancer-warlord voice. Offline/error → no plan submitted (mod falls
back). Rate-limited to once per intermission.

## #18 — LLM colonists (foreman brain)

The colony already has a **rule-based work brain** (`ColonyWorkGoal`: colonists autonomously
pick MINE/CHOP/HUNT/FORAGE/HAUL/IDLE by priority; recruited with gold, POP_CAP 10, bound to a
home flag). #18 adds an **LLM "foreman"** that *strategically directs* the colony via the
bridge, plus a **defensive job** so the colony helps hold the line — the mirror of the Warlord.

### Mod side (18a)
1. New `ColonistEntity.Job.BUILD` + execution in `ColonyWorkGoal` (`stepBuild`): the colonist
   paths to an assigned **target** and raises/repairs a short wall segment there — places cheap
   blocks (cobblestone) up to a height along a segment; REPAIR = only fill missing/air cells in
   the segment. Store the assigned target (BlockPos + direction/length + optional block id) on
   the colonist (cleared when done). (RESTOCK-towers is a future stretch — note it, don't build.)
2. Colony bridge endpoints (secured, server-thread applied, same pattern as `/td/*`):
   - `GET /td/colony` → `{flags[{name,x,y,z,dim}], colonists[{name,uuid,x,y,z,job,priorities,
     owner,invCount,target?}]}`.
   - `POST /td/colony/assign {colonist(name|uuid), job, target?{x,y,z}, length?, dir?, block?}`
     → set that colonist's job (+ target for BUILD); validate job ∈ enum; applied on the server
     thread. Overrides the rule-based pick until the task completes / is reassigned.
   - `POST /td/colony/priorities {colonist, priorities[...]}` (optional) → reorder its priorities.
3. Graceful default: with NO assignment, colonists behave exactly as today (rule-based work).

### Agent side (18b) — `grok/foreman.js`
Reuses the Warlord scaffolding (`lib/bridge` + `lib/llm`, Claude Opus). Loop: read `GET /td/colony`
+ `GET /td/battlefield` (reuse!) → the LLM foreman assigns colonists: during **intermission**,
send builders to **fortify the weak flank the Warlord is about to hit** (walls/repairs), keep
others gathering; react to threats. Chat in a gruff foreman voice. Rate-limited; on error the
colony falls back to its rule-based brain. Symmetry with the Warlord: same flank intel, opposite
intent (attack vs fortify).

## Build order
1. **17a** — mod-side: `WarlordDirector`, the 3 bridge endpoints, WaveManager director hook,
   threat budget/cost, telemetry, graceful fallback. Testable with `curl` (no LLM needed).
2. **17b** — `grok/warlord.js` agent: battlefield→LLM plan+taunt→submit, adapt on clear.
3. **#18** — LLM colonists on the same scaffolding.

## Non-goals (this block)
- No neural motor policy (Family A). No change to the RPG/skill systems. Difficulty stays
  bounded by the mod's budget — the LLM flavors waves, it doesn't own balance.
