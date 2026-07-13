# Bubble Sky Build Protocol v1

This neutral contract is shared by `grok/` producers and `mcp/` consumers. It resolves the
interop question tracked in issue #9 without making either lane depend on the other.

## Canonical job shape

```json
{ "id": "keep-000001", "x": 10, "y": 64, "z": -5,
  "block": "stone_bricks", "phase": "foundation",
  "region": "foundation", "worker": "Drone1" }
```

Coordinates are flat integer fields because both the Mineflayer worker and bridge drone consume
that shape. `normalizeBuildState()` accepts Grok's legacy `{pos:{x,y,z}}` jobs at read boundaries,
but every newly written v1 state is flat and carries `schemaVersion: 1`.

## Claims have two levels

- `state.claims[region]` is an optional coarse lease for planners assigning a semantic region.
- `progress/<worker>.json` uses `claimedIds` for atomic execution ownership of individual jobs.

Region leases do not mark jobs complete and cannot replace worker progress. Worker claims do not
grant ownership over an entire semantic region.

## Imports

```js
// CommonJS (Grok)
const { normalizeBuildState } = require('../../shared/build-protocol/index.cjs')

// ESM (MCP)
import { normalizeBuildState } from '../shared/build-protocol/index.mjs'
```

Schemas are deliberately dependency-free JSON Schema 2020-12 documents. Runtime normalization
also has no dependencies so it can run in the server, builders, tests, and small command tools.
