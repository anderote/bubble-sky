// grok/builders/builder.js — ONE "dumb" builder bot.
//
// A builder is a plain mineflayer player (offline auth) that joins the VANILLA
// server, gets set to CREATIVE (via an opped console), CLAIMS a partition of the
// shared build state.json (using the `claims` lease from lib/build/state.js), and
// physically PLACES its blocks as a player — `bot.creative.setInventorySlot` to
// load the block into the hotbar, then `bot.placeBlock` against an adjacent
// existing face. No /fill, no /setblock: every block is a real player placement.
//
// Natural order: jobs are sorted BOTTOM-UP (y,z,x). A cell is only placed when it
// has an adjacent EXISTING block to place against (preferring the block directly
// below). Because we go bottom-up, layer n-1 is always finished before layer n, so
// every cell has support the moment we reach it; the rare cell whose neighbours
// aren't ready is deferred and retried, so nothing is ever placed against air.
//
// Claiming is atomic: every read-modify-write of the state file is guarded by a
// lockfile so two builders can never grab the same partition or double-place.

const mineflayer = require('mineflayer')
const { pathfinder } = require('mineflayer-pathfinder')
const Vec3 = require('vec3')
const fs = require('fs')
const cp = require('child_process')
const { claimRegion, releaseRegion, releaseExpired } = require('../lib/build/state')
const { baseName } = require('../lib/build/util')

const sleep = ms => new Promise(r => setTimeout(r, ms))
function withTimeout(p, ms) { return Promise.race([p, new Promise(res => setTimeout(res, ms))]) }

// ---- atomic state file access (lockfile guard) ----
function shq(s) { return "'" + String(s).replace(/'/g, "'\\''") + "'" }

async function acquireLock(lockPath) {
  for (let i = 0; i < 400; i++) {
    try { const fd = fs.openSync(lockPath, 'wx'); fs.writeSync(fd, String(process.pid)); fs.closeSync(fd); return }
    catch (e) {
      if (e.code !== 'EEXIST') throw e
      try { const st = fs.statSync(lockPath); if (Date.now() - st.mtimeMs > 4000) fs.unlinkSync(lockPath) } catch {}
      await sleep(15 + Math.random() * 35)
    }
  }
  throw new Error('lock timeout: ' + lockPath)
}
function releaseLock(lockPath) { try { fs.unlinkSync(lockPath) } catch {} }

// Locked read-modify-write. fn(state) mutates in place; return value is passed back.
async function withState(statePath, fn) {
  const lp = statePath + '.lock'
  await acquireLock(lp)
  try {
    const state = JSON.parse(fs.readFileSync(statePath, 'utf8'))
    const ret = fn(state)
    fs.writeFileSync(statePath, JSON.stringify(state, null, 2))
    return ret
  } finally { releaseLock(lp) }
}

// The partition key a job belongs to: fleet-assigned `claim`, else its semantic region.
function keyOf(job) { return job.claim || job.region || 'build' }

class Builder {
  constructor(opts) {
    this.opts = opts
    this.name = opts.username
    this.statePath = opts.statePath
    this.consolePath = opts.consolePath
    this.host = opts.host || 'localhost'
    this.port = opts.port || 25565
    this.version = opts.version || '1.21.6'
    this.ttl = opts.ttl || 120000
    this.delay = Math.max(40, Math.round(1000 / (opts.blocksPerSec || 6)))
    this.maxTries = opts.maxTries || 3
    this.log = (...a) => (opts.log || console.log)(`[${this.name}]`, ...a)
    this.stopped = false
    this._ended = false
    this.placed = 0
    this.skipped = 0
    this.owned = new Set()
  }

  // ---- console (opped) helpers ----
  sendConsole(command) {
    if (!this.consolePath) return Promise.resolve()
    return new Promise(res => {
      let done = false, child = null; const fin = () => { if (!done) { done = true; try { child && child.kill() } catch {} res() } }
      try {
        child = cp.spawn('sh', ['-c', `printf '%s\\n' ${shq(command)} > ${shq(this.consolePath)}`], { stdio: 'ignore' })
        child.on('exit', fin); child.on('error', fin)
      } catch { fin() }
      setTimeout(fin, 1500)
    })
  }

  // ---- lifecycle ----
  connect() {
    return new Promise((resolve, reject) => {
      this._ended = false
      const bot = mineflayer.createBot({
        host: this.host, port: this.port, username: this.name, auth: 'offline', version: this.version
      })
      this.bot = bot
      bot.loadPlugin(pathfinder)
      this.mcData = null; this.Item = null; this._equipped = null
      bot.once('spawn', () => {
        this.mcData = require('minecraft-data')(bot.version)
        this.Item = require('prismarine-item')(bot.version)
        this.log('spawned at', bot.entity.position && bot.entity.position.floored())
        resolve()
      })
      bot.on('kicked', r => this.log('kicked', typeof r === 'string' ? r : JSON.stringify(r)))
      bot.on('error', e => { if (!/PartialReadError|VarInt|buffer/.test(e && e.message)) this.log('err', e.message) })
      bot.on('end', () => { this._ended = true; this.stopSync() })
      bot.once('error', e => reject(e))
    })
  }

  // Position heartbeat. Far teleports deadlock: with no chunk under the bot,
  // mineflayer pauses physics and never sends a position packet, so the server
  // never streams the destination chunks. We send the serverbound `position`
  // packet ourselves (correct 1.21.x flags bitfield) to keep the server's view
  // centred on the bot, which streams the build chunks and lets physics resume.
  startSync() {
    if (this._syncIv) return
    this._syncIv = setInterval(() => {
      if (this._ended || !this.bot || !this.bot.entity) return
      const p = this.bot.entity.position
      try { this.bot._client.write('position', { x: p.x, y: p.y, z: p.z, flags: { onGround: false, hasHorizontalCollision: false } }) } catch {}
    }, 200)
  }
  stopSync() { if (this._syncIv) { clearInterval(this._syncIv); this._syncIv = null } }

  async ensureCreative() {
    // Op + set creative from the server console (op level 4). Idempotent.
    await this.sendConsole(`op ${this.name}`)
    await sleep(300)
    await this.sendConsole(`gamemode creative ${this.name}`)
    // Also self-request in case op already landed.
    try { this.bot.chat('/gamemode creative') } catch {}
    for (let i = 0; i < 40 && !this._ended; i++) {
      if (this.bot.game && this.bot.game.gameMode === 'creative') { this.log('creative confirmed'); return true }
      if (i === 12) { await this.sendConsole(`gamemode creative ${this.name}`) }
      await sleep(250)
    }
    this.log('WARN could not confirm creative (gameMode=' + (this.bot.game && this.bot.game.gameMode) + ')')
    return false
  }

  quit() { this.stopSync(); try { this.bot && this.bot.quit() } catch {} }

  stop() { this.stopped = true; this.quit() }

  // ---- main loop ----
  async run() {
    while (!this.stopped) {
      try {
        await this.connect()
        await this.ensureCreative()
        this.startSync()
        await sleep(400)
        await this.workLoop()
      } catch (e) {
        this.log('session ended:', e.message)
      }
      if (this.stopped) break
      // Reconnect only if there is still claimable work left.
      let remaining = false
      try { remaining = await this.pendingCount() > 0 } catch {}
      this.quit()
      if (!remaining) break
      this.log('reconnecting (work remains)…')
      await sleep(1500)
    }
    this.quit()
    return { name: this.name, placed: this.placed, skipped: this.skipped, owned: [...this.owned] }
  }

  async pendingCount() {
    const state = JSON.parse(fs.readFileSync(this.statePath, 'utf8'))
    return state.jobs.filter(j => !j.done && !j.skip).length
  }

  async workLoop() {
    while (!this.stopped && !this._ended) {
      const claim = await this.claimNext()
      if (!claim) {
        if (await this.pendingCount() === 0) return         // all built
        await sleep(1200)                                   // wait for others / lease expiry
        // second check: if everything left is claimed-and-alive, idle a bit then retry
        continue
      }
      this.owned.add(claim.key)
      this.log(`claimed "${claim.key}" (${claim.jobs.length} jobs)`)
      await this.buildRegion(claim.key, claim.jobs)
      await this.releaseKey(claim.key)
    }
  }

  // Atomically pick a partition with pending jobs that no live agent holds.
  claimNext() {
    return withState(this.statePath, state => {
      releaseExpired(state)
      const groups = new Map()
      for (const j of state.jobs) { if (j.done || j.skip) continue; const k = keyOf(j); if (!groups.has(k)) groups.set(k, []); groups.get(k).push(j) }
      for (const [k, jobs] of groups) {
        const c = state.claims && state.claims[k]
        const free = !c || (Date.now() - c.ts) >= c.ttl || c.agent === this.name
        if (free && claimRegion(state, k, this.name, this.ttl)) return { key: k, jobs: jobs.map(j => ({ ...j })) }
      }
      return null
    })
  }

  renew(key) { return withState(this.statePath, state => { const c = state.claims && state.claims[key]; if (c && c.agent === this.name) c.ts = Date.now() }) }
  releaseKey(key) { return withState(this.statePath, state => releaseRegion(state, key, this.name)) }
  markDone(ids) {
    if (!ids.length) return Promise.resolve()
    const set = new Set(ids)
    return withState(this.statePath, state => { for (const j of state.jobs) if (set.has(j.id)) j.done = true })
  }
  markSkip(ids) {
    if (!ids.length) return Promise.resolve()
    const set = new Set(ids)
    return withState(this.statePath, state => { for (const j of state.jobs) if (set.has(j.id)) j.skip = true })
  }

  // ---- build one partition, bottom-up, deferring un-supported cells ----
  async buildRegion(key, jobs) {
    jobs.sort((a, b) => a.pos.y - b.pos.y || a.pos.z - b.pos.z || a.pos.x - b.pos.x)
    await this.ensureNear(jobs[0].pos)
    let pending = jobs.slice()
    const doneBatch = []
    const flush = async () => { if (doneBatch.length) { await this.markDone(doneBatch.splice(0)); await this.renew(key) } }

    let pass = 0
    while (pending.length && !this.stopped && !this._ended) {
      pass++
      let placedThisPass = 0, exists = 0, defer = 0, fail = 0
      const next = []
      for (const job of pending) {
        if (this.stopped || this._ended) { next.push(job); continue }
        const r = await this.placeJob(job)
        if (r === 'placed') { this.placed++; placedThisPass++; doneBatch.push(job.id); await sleep(this.delay) }
        else if (r === 'exists') { exists++; doneBatch.push(job.id) }
        else if (r === 'defer') { defer++; next.push(job) }
        else { // fail
          fail++
          job._tries = (job._tries || 0) + 1
          if (job._tries < this.maxTries) next.push(job)
          else { this.skipped++; this.log('skip', job.id, job.block, `${job.pos.x},${job.pos.y},${job.pos.z}`, this.lastErr || '') }
        }
        if (doneBatch.length >= 16) await flush()
      }
      await flush()
      this.log(`${key} pass ${pass}: placed ${placedThisPass}, exists ${exists}, defer ${defer}, fail ${fail}, remaining ${next.length}`)
      if (placedThisPass === 0 && next.length === pending.length) {
        // Mark these permanently un-placeable so no builder re-serves them; the
        // final re-diff will catch them. Prevents an infinite re-claim loop.
        this.log(`stuck: ${next.length} unsupported/failed cells left for re-diff`)
        await this.markSkip(next.map(j => j.id))
        break
      }
      pending = next
    }
    await flush()
  }

  // ---- placement primitives ----
  async placeJob(job) {
    const { x, y, z } = job.pos
    const dest = new Vec3(x, y, z)
    let existing = this.bot.blockAt(dest)
    if (existing == null) { await this.ensureNear(job.pos); existing = this.bot.blockAt(dest) }
    if (existing == null) return 'defer'                                   // chunk not ready
    if (existing.name === baseName(job.block)) return 'exists'            // already matches
    if (existing.name !== 'air' && existing.name !== 'cave_air' && existing.name !== 'void_air') return 'exists' // occupied; dumb builder leaves it

    const face = this.findSupport(dest)
    if (!face) return 'defer'                                             // no neighbour yet

    if (!(await this.equip(job.block))) return 'fail'
    // Up to 2 attempts, repositioning within reach of the reference face.
    for (let attempt = 0; attempt < 2 && !this._ended; attempt++) {
      if (attempt > 0 || this.bot.entity.position.distanceTo(face.ref.position) > 4.2) { await this.hoverNear(dest); await sleep(120) }
      try {
        await this.bot.placeBlock(face.ref, face.vec)
        return 'placed'
      } catch (e) {
        this.lastErr = e.message
        const now = this.bot.blockAt(dest)
        if (now && now.name === baseName(job.block)) return 'exists'   // it actually landed
      }
    }
    return 'fail'
  }

  // Prefer the block directly BELOW (bottom-up guarantees it exists), then sides, then above.
  findSupport(dest) {
    const cands = [
      { off: [0, -1, 0], face: [0, 1, 0] },
      { off: [0, 0, -1], face: [0, 0, 1] },
      { off: [0, 0, 1], face: [0, 0, -1] },
      { off: [-1, 0, 0], face: [1, 0, 0] },
      { off: [1, 0, 0], face: [-1, 0, 0] },
      { off: [0, 1, 0], face: [0, -1, 0] }
    ]
    for (const c of cands) {
      const ref = this.bot.blockAt(dest.offset(c.off[0], c.off[1], c.off[2]))
      if (ref && ref.name !== 'air' && ref.name !== 'cave_air' && ref.name !== 'void_air') {
        return { ref, vec: new Vec3(c.face[0], c.face[1], c.face[2]) }
      }
    }
    return null
  }

  async equip(block) {
    const name = baseName(block)
    if (this._equipped === name) return true
    let def = this.mcData.itemsByName[name]
    if (!def) def = this.mcData.itemsByName[name.replace(/_block$/, '')]
    if (!def && this.mcData.blocksByName[name]) def = this.mcData.itemsByName[name] // block w/o item
    if (!def) { this.lastErr = 'no item for ' + name; return false }
    const slot = 36 + (this.bot.quickBarSlot || 0)
    try {
      await this.bot.creative.setInventorySlot(slot, new this.Item(def.id, 1))
      this._equipped = name
      return true
    } catch (e) { this.lastErr = 'equip ' + e.message; this._equipped = null; return false }
  }

  // Get within reach of a target, loading its chunk first. Long hop = opped
  // console teleport (the heartbeat then streams the chunk); short hop = flyTo.
  async ensureNear(pos) {
    const hover = new Vec3(pos.x + 0.5, pos.y + 3, pos.z + 0.5)
    if (this.bot.entity.position.distanceTo(hover) > 6) {
      await this.sendConsole(`tp ${this.name} ${Math.floor(hover.x)} ${hover.y.toFixed(1)} ${Math.floor(hover.z)}`)
      await sleep(700)
      // Point the heartbeat at the destination so the server streams THESE chunks.
      try { this.bot.entity.position.set(hover.x, hover.y, hover.z) } catch {}
    }
    // Wait for the target's chunk to arrive (heartbeat drives the streaming).
    let loaded = false
    for (let i = 0; i < 60 && !this._ended; i++) {
      if (this.bot.blockAt(new Vec3(pos.x, pos.y - 1, pos.z)) != null) { loaded = true; break }
      await sleep(200)
    }
    if (!loaded) { this.log('WARN chunk not loaded near', `${pos.x},${pos.y},${pos.z}`); return }
    try { this.bot.creative.startFlying(); await withTimeout(this.bot.creative.flyTo(hover), 4000) } catch {}
  }

  async hoverNear(dest) {
    const hover = new Vec3(dest.x + 0.5, dest.y + 2.2, dest.z + 0.5)
    try { this.bot.creative.startFlying(); await withTimeout(this.bot.creative.flyTo(hover), 4000) } catch {}
  }
}

module.exports = { Builder, withState, keyOf }
