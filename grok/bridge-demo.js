// grok/bridge-demo.js — proves Grok's building code runs on a MODDED server
// through the AgentBridge HTTP API, with NO Mineflayer / vanilla protocol.
//
// It drives the real structures.js `round_tower` generator through the bridge
// (batched), places a MODDED block (towerdefense:acid) to prove custom content
// lands, then reads everything back and asserts writes == reads.
//
// Run against a running modded server + bridge (see scripts/deploy-modded.sh):
//   BUBBLESKY_BRIDGE_TOKEN=... node grok/bridge-demo.js
//
// Env: BUBBLESKY_BRIDGE_URL (default http://127.0.0.1:25580),
//      BUBBLESKY_BRIDGE_TOKEN (required).

const makeBridge = require('./lib/bridge')
const makeStructures = require('./structures')

const ORIGIN = { x: 220, y: 100, z: 220 }

let failures = 0
function check(name, cond, detail = '') {
  const tag = cond ? 'PASS' : 'FAIL'
  if (!cond) failures++
  console.log(`  [${tag}] ${name}${detail ? ' — ' + detail : ''}`)
}

// A structures.js ctx that records ops into a batch instead of enqueuing commands.
function batchCtx(bridge) {
  const ops = []
  const B = (v, def) => bridge.cleanBlock(v, def)
  const clamp = (v, lo, hi, def) => {
    const n = (v == null || isNaN(v)) ? def : Number(v)
    return Math.max(lo, Math.min(hi, Math.round(n)))
  }
  const set = (x, y, z, block) =>
    ops.push({ op: 'setblock', x: Math.round(x), y: Math.round(y), z: Math.round(z), block: B(block, 'stone') })
  const fillBox = (x1, y1, z1, x2, y2, z2, block) =>
    ops.push({ op: 'fill', x1: Math.round(x1), y1: Math.round(y1), z1: Math.round(z1),
      x2: Math.round(x2), y2: Math.round(y2), z2: Math.round(z2), block: B(block, 'stone') })
  const enqueue = (cmd) => ops.push({ op: 'command', command: String(cmd).replace(/^\//, '') })
  return { ctx: { enqueue, fillBox, B, clamp, set }, ops }
}

async function main() {
  const bridge = makeBridge()
  console.log(`bridge-demo → ${bridge.baseUrl}`)

  // 1. health
  const h = await bridge.health()
  console.log('health:', JSON.stringify(h))
  check('health ok', h.ok === true && typeof h.mcVersion === 'string')

  // 2. command
  const t = await bridge.command('time set day')
  console.log('command(time set day):', JSON.stringify(t))
  check('command returned', t.ok === true)

  // 3. build a real structures.js round_tower through the bridge (batched)
  const { ctx, ops } = batchCtx(bridge)
  const S = makeStructures(ctx)
  S.gens.round_tower(ORIGIN, { radius: 3, height: 8, material: 'stone_bricks' })
  console.log(`round_tower generated ${ops.length} ops; sending as one /batch…`)
  const br = await bridge.batch(ops)
  const okOps = br.results.filter(r => r.ok).length
  check('batch build applied', br.ok === true && okOps === ops.length, `${okOps}/${ops.length} ops ok`)

  // 4. read back the lantern at the tower centre (proves the build landed)
  const lantern = await bridge.getBlock(ORIGIN.x, ORIGIN.y + 1, ORIGIN.z)
  console.log('centre block:', JSON.stringify(lantern))
  check('lantern at tower centre', lantern.block.includes('lantern'), lantern.block)

  // 5. place + read back a MODDED block (towerdefense:acid) — the whole point
  const acidPos = { x: ORIGIN.x, y: ORIGIN.y + 10, z: ORIGIN.z }
  const setAcid = await bridge.setBlock(acidPos.x, acidPos.y, acidPos.z, 'towerdefense:acid')
  console.log('setBlock acid:', JSON.stringify(setAcid))
  const readAcid = await bridge.getBlock(acidPos.x, acidPos.y, acidPos.z)
  console.log('read acid:', JSON.stringify(readAcid))
  check('modded block placed + read back', readAcid.block.startsWith('towerdefense:acid'), readAcid.block)

  // 6. fill a small box then count via region
  const bx = { x1: ORIGIN.x + 10, y1: ORIGIN.y, z1: ORIGIN.z + 10, x2: ORIGIN.x + 13, y2: ORIGIN.y + 2, z2: ORIGIN.z + 13 }
  const f = await bridge.fillBox(bx.x1, bx.y1, bx.z1, bx.x2, bx.y2, bx.z2, 'glowstone')
  console.log('fill glowstone box:', JSON.stringify(f))
  // count = cells actually CHANGED (0 on an idempotent re-run); volume is the box size.
  check('fill volume == box', f.volume === 4 * 3 * 4 && f.count <= f.volume, `count=${f.count} volume=${f.volume}`)

  const reg = await bridge.readRegion(bx.x1 + 1, bx.y1 + 1, bx.z1 + 1, 3, 2, 3)
  const glow = reg.cells.filter(c => reg.palette[c[3]].includes('glowstone')).length
  console.log(`region: ${reg.count} non-air cells, ${glow} glowstone, palette=${JSON.stringify(reg.palette)}`)
  check('region reflects fill', glow > 0)

  // 7. scan
  const sc = await bridge.scan(ORIGIN.x, ORIGIN.y, ORIGIN.z, 32)
  console.log(`scan: ${sc.players.length} players, ${sc.entities.length} entities`)
  check('scan ok', Array.isArray(sc.players) && Array.isArray(sc.entities))

  console.log(failures === 0 ? '\nALL CHECKS PASSED' : `\n${failures} CHECK(S) FAILED`)
  process.exit(failures === 0 ? 0 : 1)
}

main().catch(e => { console.error('demo error:', e.message); process.exit(1) })
