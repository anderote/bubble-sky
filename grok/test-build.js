// grok/test-build.js — unit tests for the agent-native build pipeline.
// Runnable with: `node test-build.js`  (NO server needed).
//
// Covers: voxel set/get + palette interning + .schem round-trip; compile a
// fixture blueprint (specific cells, hollow interiors empty, carve=air); an
// additive diff against a partially-built world (never air outside carve); and
// the site anchor stepping a foundation on a synthetic slope.

const path = require('path')
const voxel = require('./lib/build/voxel')
const blueprint = require('./lib/build/blueprint')
const { compile } = require('./lib/build/compile')
const { anchorToSite } = require('./lib/build/anchor')
const { diff } = require('./lib/build/diff')
const state = require('./lib/build/state')
const realize = require('./lib/build/realize')

let pass = 0, fail = 0
function ok(cond, msg) { if (cond) { pass++ } else { fail++; console.error('  FAIL:', msg) } }
function eq(a, b, msg) { ok(a === b, `${msg} (got ${JSON.stringify(a)}, want ${JSON.stringify(b)})`) }
async function section(name, fn) { console.log('\n#', name); try { await fn() } catch (e) { fail++; console.error('  THREW:', e.stack) } }

;(async () => {
  // ---- 1. voxel: set/get + palette interning ----
  await section('voxel set/get + palette interning', () => {
    const t = voxel.createTarget({ x: 10, y: 64, z: 10 }, { x: 4, y: 4, z: 4 })
    eq(t.palette.length, 1, 'palette starts with only <unset>')
    eq(voxel.get(t, 1, 1, 1), null, 'unset cell reads null')
    voxel.set(t, 1, 1, 1, 'stone_bricks')
    voxel.set(t, 2, 1, 1, 'stone_bricks')       // same block → same palette index
    voxel.set(t, 1, 2, 1, 'oak_planks')
    eq(voxel.get(t, 1, 1, 1), 'stone_bricks', 'get returns set block')
    eq(t.palette.length, 3, 'interning dedupes: <unset>, stone_bricks, oak_planks')
    ok(t.grid[voxel.idx(t, 1, 1, 1)] === t.grid[voxel.idx(t, 2, 1, 1)], 'same block shares palette index')
    // index 0 stays reserved for preserve-world
    eq(voxel.get(t, 3, 3, 3), null, 'untouched cell still preserves world (null)')
    // out-of-bounds writes are dropped, never touch the world
    eq(voxel.set(t, 99, 0, 0, 'tnt'), -1, 'OOB write dropped')
    eq(t.oob, 1, 'OOB write counted')
    eq(voxel.countSet(t), 3, 'exactly 3 cells set')
  })

  // ---- 2. voxel: .schem round-trip ----
  await section('.schem round-trip', async () => {
    const t = voxel.createTarget({ x: 0, y: 64, z: 0 }, { x: 3, y: 3, z: 3 })
    voxel.set(t, 0, 0, 0, 'stone_bricks')
    voxel.set(t, 1, 0, 0, 'cobblestone')
    voxel.set(t, 0, 1, 0, 'oak_stairs[facing=east,half=bottom]')
    const tmp = path.join(require('os').tmpdir(), `grok-schem-${Date.now()}.schem`)
    await voxel.writeSchemFile(t, tmp)
    const t2 = await voxel.readSchemFile(tmp)
    eq(t2.size.x, 3, 'round-trip size.x')
    eq(base(voxel.get(t2, 0, 0, 0)), 'stone_bricks', 'round-trip stone_bricks')
    eq(base(voxel.get(t2, 1, 0, 0)), 'cobblestone', 'round-trip cobblestone')
    eq(base(voxel.get(t2, 0, 1, 0)), 'oak_stairs', 'round-trip oak_stairs base')
    eq(voxel.get(t2, 2, 2, 2), null, 'unset stays air/unset after round-trip')
    require('fs').unlinkSync(tmp)
  })

  // ---- 3. compile fixture: specific cells, hollow empty, carve=air ----
  let fixtureTarget
  await section('compile fixture blueprint', () => {
    const raw = require('./lib/build/__tests__/fixture.blueprint.json')
    const bp = blueprint.normalize(raw, raw.anchor.origin)
    const v = blueprint.validate(bp)
    ok(v.ok, 'fixture validates: ' + v.errors.join('; '))
    const t = compile(bp)
    fixtureTarget = t
    eq(t.originWorld.x, 100, 'world origin x = anchor + bounds.min')
    // foundation is solid cobblestone at y=0
    eq(base(voxel.get(t, 4, 0, 4)), 'cobblestone', 'foundation cell is cobblestone')
    eq(base(voxel.get(t, 0, 0, 0)), 'cobblestone', 'foundation corner filled')
    // shell wall present on the boundary
    eq(base(voxel.get(t, 0, 3, 0)), 'stone_bricks', 'shell wall on boundary')
    // hollow interior is EMPTY (unset, never air)
    eq(voxel.get(t, 4, 3, 4), null, 'hollow interior preserved (unset, not air)')
    eq(voxel.get(t, 3, 4, 3), null, 'hollow interior cell 2 unset')
    // carve produced air exactly at the doorway (overwriting the shell there)
    eq(base(voxel.get(t, 4, 1, 0)), 'air', 'carve doorway cell = air')
    eq(base(voxel.get(t, 4, 2, 0)), 'air', 'carve doorway cell 2 = air')
    // point light placed
    eq(base(voxel.get(t, 4, 4, 4)), 'lantern', 'point lantern placed in interior')
    // meta tags exist
    const i = voxel.idx(t, 0, 0, 0)
    ok(t.meta.get(i) && t.meta.get(i).region === 'foundation', 'foundation cell tagged with region')
  })

  // ---- 4. additive diff vs partially-built world ----
  await section('additive diff (only missing cells, no air outside carve)', () => {
    const t = fixtureTarget
    // Seed a world Map: the whole foundation is ALREADY cobblestone; everything
    // else is air; the two doorway cells are stone (so carve must clear them).
    const worldMap = new Map()
    const key = (x, y, z) => `${x},${y},${z}`
    voxel.forEachSet(t, (lx, ly, lz, block) => {
      const w = voxel.worldOf(t, lx, ly, lz)
      // pre-place foundation as matching cobblestone
      if (ly === 0) worldMap.set(key(w.x, w.y, w.z), 'cobblestone')
    })
    // doorway cells already have a wall block that must be carved to air
    const d0 = voxel.worldOf(t, 4, 1, 0), d1 = voxel.worldOf(t, 4, 2, 0)
    worldMap.set(key(d0.x, d0.y, d0.z), 'stone_bricks')
    worldMap.set(key(d1.x, d1.y, d1.z), 'stone_bricks')
    const readBlock = (x, y, z) => worldMap.get(key(x, y, z)) || 'air'

    const res = diff(t, readBlock, { structure: 'Test Keep' })
    ok(res.additive, 'diff marks additive')
    // No job should target a foundation cell (world already matches).
    const foundationJobs = res.jobs.filter(j => j.pos.y === t.originWorld.y)
    eq(foundationJobs.length, 0, 'no jobs for already-correct foundation')
    // Air jobs appear ONLY at the two carve doorway cells.
    const airJobs = res.jobs.filter(j => base(j.block) === 'air')
    eq(airJobs.length, 2, 'exactly 2 air jobs (the carve doorway)')
    for (const j of airJobs) {
      const isDoor = (j.pos.x === d0.x && j.pos.z === d0.z && (j.pos.y === d0.y || j.pos.y === d1.y))
      ok(isDoor, `air job only at carve cell (got ${JSON.stringify(j.pos)})`)
    }
    // Every job lies within the target bounds (surroundings untouched).
    for (const j of res.jobs) {
      const within = j.pos.x >= t.originWorld.x && j.pos.x < t.originWorld.x + t.size.x &&
        j.pos.z >= t.originWorld.z && j.pos.z < t.originWorld.z + t.size.z
      ok(within, 'job within target footprint')
    }
    // Jobs carry region + phase tags and stable ids.
    ok(res.jobs.every(j => j.region && j.phase && j.id), 'jobs carry region/phase/id')
    // Ordering: foundation-phase jobs (there are none here) then walls before openings.
    const phases = res.jobs.map(j => j.phase)
    const wallsAt = phases.indexOf('walls'), openAt = phases.lastIndexOf('openings')
    if (wallsAt !== -1 && openAt !== -1) ok(wallsAt <= openAt, 'walls ordered before openings')

    // realize batching produces /fill runs + /setblock singletons
    const fakeHands = { fills: 0, sets: 0, fillBox() { this.fills++ }, setBlock() { this.sets++ } }
    const r = realize.realize(res.jobs, fakeHands)
    eq(r.ops, fakeHands.fills + fakeHands.sets, 'realize ops == fills+sets')
    ok(r.fills > 0, 'realize collapsed some runs into /fill')

    // state.json shape (Codex parity) with a region + claims
    const st = state.buildState(res, { structure: 'Test Keep', workers: [1, 2] })
    eq(st.source.type, 'blueprint', 'state source type blueprint')
    ok(st.source.additive === true, 'state marks additive')
    ok(st.jobs.every(j => j.worker === 1 || j.worker === 2), 'workers assigned')
    ok(state.claimRegion(st, 'shell', 'GrokDev'), 'claim a region')
    ok(!state.claimRegion(st, 'shell', 'CodexDrone1'), 'second agent cannot steal live claim')
  })

  // ---- 5. anchor steps a foundation on a synthetic slope ----
  await section('anchor site-fit steps foundation on slope', () => {
    const bp = blueprint.normalize({
      name: 'Slope Test', palette: 'medieval',
      anchor: { origin: { x: 0, y: 90, z: 0 }, facing: 'north', terrainFit: 'follow' },
      bounds: { min: { x: 0, y: 0, z: 0 }, max: { x: 4, y: 4, z: 4 } },
      regions: [{ id: 'foundation', name: 'Foundation', phase: 1, components: [
        { kind: 'box', role: 'foundation', a: { x: 0, y: 0, z: 0 }, b: { x: 4, y: 0, z: 4 } }
      ] }]
    }, { x: 0, y: 90, z: 0 })
    // Synthetic slope: ground drops 2 blocks per +x. groundY (median of x0..4) = 76.
    const heightAt = (x, z) => 80 - 2 * x
    anchorToSite(bp, { groundY: 76, heightAt, terrainFit: 'follow' })
    eq(bp.anchor.origin.y, 76, 'origin.y set to surveyed ground')
    const footings = bp.regions.find(r => r.id === 'footings')
    ok(footings, 'footings region added')
    // deepest footing at the lowest ground column (x=4) is below x=3's
    const depthAt = (xCol) => Math.min(...footings.components.filter(c => c.a.x === xCol).map(c => c.a.y), 0)
    ok(depthAt(4) < depthAt(3), `footing at x=4 (${depthAt(4)}) deeper than x=3 (${depthAt(3)})`)
    ok(bp.bounds.min.y < 0, 'bounds extended downward for footings')
    // compile it and confirm footing cells are filled below the foundation base
    const t = compile(bp)
    // local index for column x=4, at the deep footing row
    const deep = depthAt(4) // negative
    eq(base(voxel.get(t, 4 - bp.bounds.min.x, deep - bp.bounds.min.y, 2 - bp.bounds.min.z)), 'cobblestone', 'deep footing cell filled')
  })

  // ---- 6. incremental edit: add a west tower touches only the tower ----
  await section('edit op: add west tower is a new region', () => {
    const raw = require('./lib/build/__tests__/fixture.blueprint.json')
    const bp = blueprint.normalize(raw, raw.anchor.origin)
    const before = bp.regions.length
    const touched = blueprint.transform(bp, { type: 'add_tower', side: 'west', height: 12 })
    eq(bp.regions.length, before + 1, 'one region added')
    eq(touched[0], 'west_tower', 'transform reports the tower region')
    ok(bp.regions.some(r => r.id === 'west_tower'), 'west_tower present')
    // raise_roof shifts roof-ish regions up (none here to move, but must not throw)
    ok(Array.isArray(blueprint.transform(bp, { type: 'raise_roof', dy: 2 })), 'raise_roof returns touched list')
  })

  console.log(`\n=== ${pass} passed, ${fail} failed ===`)
  process.exit(fail ? 1 : 0)
})()

function base(s) { return s == null ? null : String(s).replace(/\[.*$/, '') }
