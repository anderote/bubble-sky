// grok/agents/agent.js — ONE embodied, LLM-brained, NON-GODMODE builder.
//
// An Agent is a real mineflayer PLAYER (offline auth, vanilla server) that is set
// to CREATIVE through the opped console FIFO and places every block BY HAND via
// `bot.placeBlock` — no /setblock, no /fill. It extends the "dumb" Builder
// (builders/builder.js) purely to REUSE its proven embodied placement machinery
// (creative-via-console, support-aware bottom-up ordering, teleport+chunk-stream,
// placement retry, atomic claim/lease writes to the shared state.json).
//
// What makes it an AGENT rather than a dumb Builder is a BRAIN:
//   1. OBSERVE  — teleport to its assigned plot, sample the ground, note neighbours.
//   2. DECIDE   — its OWN LLM call (lib/llm.js) picks WHAT to build in the plot
//                 (building type / style / size) given {plot region, role/theme,
//                 brief world observation}. It announces the choice in chat.
//   3. AUTHOR   — turns that decision into a real blueprint for the plot via the
//                 Architect (lib/build author→anchor→compile→diff), scoped to the
//                 plot origin + footprint, producing the additive job list.
//   4. CLAIM    — writes those jobs into the SHARED state.json (Codex's schema),
//                 tagged with the agent's plot claim key, and takes the claim lease
//                 so no other agent can touch the plot (disjoint plots + disjoint
//                 claim keys ⇒ agents never collide).
//   5. BUILD    — places its own jobs embodied (reuses Builder.buildRegion), posts
//                 progress/status back into the shared state, and chats when done.

const { Builder, withState, keyOf } = require('../builders/builder')
const { author, blueprint } = require('../lib/build')
const { anchorToSite, heightSampler } = require('../lib/build/anchor')
const { compile } = require('../lib/build/compile')
const { diff, botReadBlock } = require('../lib/build/diff')
const { claimRegion, releaseRegion } = require('../lib/build/state')
const { createLLM } = require('../lib/llm')
const Vec3 = require('vec3')

const sleep = ms => new Promise(r => setTimeout(r, ms))

// The Architect's palettes lean on this server's MOD blocks (blockus:, mcw*:,
// farmersdelight:). The embodied test runs on a VANILLA server, so those items
// don't exist. vanillaize() rewrites any non-minecraft block to a close vanilla
// equivalent so every authored cell is actually hand-placeable. (On the modded
// server with Carpet fake players — the follow-up — this pass is skipped.)
const VANILLA = require('minecraft-data')('1.21.6')
const MOD_MAP = {
  'blockus:bluestone': 'stone', 'blockus:limestone': 'smooth_stone',
  'blockus:chiseled_limestone': 'chiseled_stone_bricks', 'blockus:gray_stone_bricks': 'stone_bricks',
  'blockus:small_marble_bricks': 'polished_diorite', 'blockus:marble': 'quartz_block',
  'blockus:polished_marble': 'quartz_block', 'blockus:marble_tiles': 'quartz_block',
  'blockus:marble_pillar': 'quartz_pillar', 'blockus:chiseled_diorite': 'chiseled_stone_bricks',
  'blockus:gray_shingles': 'deepslate_tiles',
  'mcwwindows:oak_window': 'glass_pane', 'mcwwindows:spruce_window': 'glass_pane', 'mcwwindows:stone_window': 'glass_pane',
  'mcwfurnitures:white_modern_couch': 'white_wool',
  'farmersdelight:cooking_pot': 'cauldron', 'farmersdelight:cutting_board': 'crafting_table',
  'farmersdelight:stove': 'furnace', 'farmersdelight:rice_bale': 'hay_block', 'farmersdelight:straw_bale': 'hay_block'
}
function vanillaize(block) {
  if (block == null) return block
  const s = String(block)
  const br = s.indexOf('[')
  let base = br === -1 ? s : s.slice(0, br)
  const state = br === -1 ? '' : s.slice(br)
  base = base.replace(/^minecraft:/, '')
  if (VANILLA.blocksByName[base] && (VANILLA.itemsByName[base] || VANILLA.itemsByName[base.replace(/_block$/, '')])) return base + state
  if (MOD_MAP[base]) return MOD_MAP[base]                                   // known mapping (drop state)
  if (base.includes(':')) {                                                 // unknown mod block → heuristic
    const bare = base.split(':').pop()
    if (VANILLA.blocksByName[bare] && VANILLA.itemsByName[bare]) return bare
    if (/window|glass/.test(bare)) return 'glass_pane'
    if (/couch|sofa|cushion/.test(bare)) return 'white_wool'
    if (/shingle|tile|roof/.test(bare)) return 'stone_bricks'
    if (/marble|quartz/.test(bare)) return 'quartz_block'
    if (/stove|oven|furnace|hearth/.test(bare)) return 'furnace'
    if (/bale|hay|straw|rice|thatch/.test(bare)) return 'hay_block'
    if (/chair|stool|bench|seat/.test(bare)) return 'oak_slab'
    if (/table|desk|counter|board|shelf/.test(bare)) return 'oak_planks'
    if (/pillar|column/.test(bare)) return 'quartz_pillar'
    if (/limestone|stone/.test(bare)) return 'stone_bricks'
    return 'oak_planks'                                                     // safe default
  }
  return base + state                                                       // bare vanilla name we couldn't item-verify
}

// Deterministic role → build brief, used as the LLM prompt seed AND as the offline
// fallback so the crew still produces distinct buildings with no network.
const ROLE_BRIEF = {
  house:   { archetype: 'cottage', styles: ['rustic', 'medieval', 'cottage'], rooms: 'living, bedroom, kitchen', hint: 'a cozy villager home with a chimney and gabled roof' },
  smithy:  { archetype: 'cottage', styles: ['medieval', 'rustic'],            rooms: 'forge, storeroom',          hint: 'a blacksmith forge with furnaces, an anvil and a chimney' },
  tavern:  { archetype: 'house',   styles: ['rustic', 'medieval'],            rooms: 'great_hall, kitchen, quarters', hint: 'a two-story tavern/inn with a big common room and a bar' },
  church:  { archetype: 'cathedral', styles: ['medieval'],                    rooms: 'chapel',                    hint: 'a small stone chapel with a tall nave, big windows and a bell spire' },
  market:  { archetype: 'house',   styles: ['rustic', 'medieval'],            rooms: 'great_hall, storeroom',     hint: 'an open market hall / trading post with stalls and awnings' },
  bakery:  { archetype: 'cottage', styles: ['rustic', 'cottage'],            rooms: 'kitchen, storeroom',        hint: 'a bakery with brick ovens and a shop counter' },
  barn:    { archetype: 'cottage', styles: ['rustic'],                        rooms: 'granary, stable',           hint: 'a farm barn with hay, animal stalls and storage' },
  library: { archetype: 'tower',   styles: ['medieval', 'fantasy'],           rooms: 'library',                   hint: 'a scholar\'s book tower lined with shelves' },
  well:    { archetype: 'cottage', styles: ['medieval'],                      rooms: 'storeroom',                 hint: 'a covered well house' }
}

class Agent extends Builder {
  constructor(opts) {
    super(opts)
    this.plot = opts.plot                       // { key, role, theme, origin:{x,y,z}, foot:{w,l} }
    this.architect = opts.architect             // makeArchitect({...}) instance
    this.villageGoal = opts.villageGoal || 'a village'
    this.provider = opts.provider || (process.env.ANTHROPIC_API_KEY ? 'anthropic' : 'xai')
    this.model = opts.model || (this.provider === 'anthropic' ? 'claude-opus-4-8' : 'grok-4.20-0309-non-reasoning')
    this._authored = false
    this._buildingLabel = null
    this._brainVia = null
    this.name = opts.username
  }

  // ---- lifecycle: OBSERVE → DECIDE → AUTHOR → CLAIM → BUILD ----
  async run() {
    while (!this.stopped) {
      try {
        await this.connect()
        await this.ensureCreative()
        this.startSync()
        await sleep(400)
        if (!this._authored) await this.think()      // decide + author + write jobs (once)
        await this.claimMine()
        const jobs = this.myPendingJobs()
        if (jobs.length) {
          this.log(`building ${this._buildingLabel} — ${jobs.length} blocks by hand in plot ${this.plot.key}`)
          await this.buildRegion(this.plot.key, jobs)
        }
        await this.postStatus('done')
        break
      } catch (e) {
        this.log('session ended:', e.message)
      }
      if (this.stopped) break
      this.quit()
      // Reconnect only if this agent's OWN plot still has unbuilt blocks.
      if (this._authored && this.myPendingJobs().length === 0) break
      this.log('reconnecting to finish my plot…')
      await sleep(1500)
    }
    await this.releaseMine()
    this.quit()
    return {
      name: this.name, role: this.plot.role, key: this.plot.key,
      building: this._buildingLabel, via: this._brainVia,
      placed: this.placed, skipped: this.skipped, jobs: this._jobCount || 0
    }
  }

  // ---- 1+2+3+4: reason about the plot, then author + register its jobs ----
  async think() {
    const c = this.plot.origin
    await this.ensureNear(c)                       // stream the plot's chunks in
    const groundTop = this.sampleGround(c)
    const floorY = groundTop + 1                    // build ON TOP of the ground (hand-placeable)
    const obs = this.observe(c, groundTop)

    const decision = await this.decide(obs, floorY)
    this._buildingLabel = decision.label
    this._brainVia = decision.via
    try { this.bot.chat(`${decision.chat}`) } catch {}
    this.log(`brain(${decision.via}) decided: ${decision.label} — "${decision.goal}"`)

    // AUTHOR a real blueprint for THIS plot (scoped to plot origin, floated at floorY
    // so the lowest course lands on air just above the ground and is hand-placeable).
    const origin = { x: c.x, y: floorY, z: c.z }
    const bp = await author(this.architect, { goal: decision.goal, origin }, origin, (...a) => this.log('architect:', ...a))
    bp.anchor.terrainFit = 'float'
    anchorToSite(bp, { groundY: floorY, terrainFit: 'float' })
    const target = compile(bp)
    const read = botReadBlock(this.bot)
    const d = diff(target, read, { structure: bp.name })

    // Tag every job with this agent's plot claim key + a namespaced id, then MERGE
    // into the shared state (locked write). Disjoint keys ⇒ no cross-agent claiming.
    const tagged = d.jobs.map(j => ({
      ...j,
      block: vanillaize(j.block),          // rewrite mod blocks → vanilla (embodied test server)
      id: `${this.plot.key}:${j.id}`,
      region: this.plot.key,
      claim: this.plot.key
    }))
    await withState(this.statePath, state => {
      state.jobs.push(...tagged)
      state.crew = state.crew || {}
      state.crew[this.plot.key] = {
        agent: this.name, role: this.plot.role, building: this._buildingLabel,
        goal: decision.goal, jobs: tagged.length, placed: 0, status: 'building', via: decision.via
      }
    })
    this._authored = true
    this._jobCount = tagged.length
    this.log(`authored ${tagged.length} jobs (${d.placed} solid / ${d.air} carve) for ${this._buildingLabel}`)
  }

  // ---- the LLM BRAIN: decide WHAT to build in this plot ----
  async decide(obs, floorY) {
    const role = this.plot.role
    const brief = ROLE_BRIEF[role] || ROLE_BRIEF.house
    const maxW = this.plot.foot.w, maxL = this.plot.foot.l
    const tool = {
      name: 'decide_building',
      description: 'Decide the single building to construct in this plot.',
      schema: {
        type: 'object',
        properties: {
          building: { type: 'string', description: 'short building name, e.g. "blacksmith forge", "tavern", "chapel"' },
          archetype: { type: 'string', enum: ['cottage', 'house', 'manor', 'keep', 'tower', 'cathedral', 'hall'], description: 'silhouette family' },
          style: { type: 'string', description: 'palette/style, e.g. rustic, medieval, cottage, fantasy' },
          width: { type: 'integer', description: `footprint width in blocks, 6..${maxW}` },
          length: { type: 'integer', description: `footprint length in blocks, 6..${maxL}` },
          rooms: { type: 'string', description: 'comma-separated furnished room program' },
          chat: { type: 'string', description: 'one short first-person line to say in game about what you are building and where' }
        },
        required: ['building', 'archetype', 'style', 'width', 'length', 'chat']
      }
    }
    const sys = `You are ${this.name}, an embodied builder-bot in a Minecraft village build. You place every block BY HAND (no commands). You have been assigned ONE plot and the role of "${role}". Decide the SINGLE building to construct there — pick its type, style, footprint and room program so it reads clearly as a ${role}. Keep the footprint within the plot. Call decide_building; do not chat outside the tool.`
    const user = {
      role, theme: this.plot.theme || null, villageGoal: this.villageGoal,
      plot: { key: this.plot.key, center: this.plot.origin, maxFootprint: { width: maxW, length: maxL }, floorY },
      observation: obs,
      guidance: brief.hint,
      suggestedArchetype: brief.archetype, suggestedStyles: brief.styles, suggestedRooms: brief.rooms
    }
    try {
      const llm = createLLM({ provider: this.provider })
      const { toolCalls } = await llm.chat({
        system: sys,
        messages: [{ role: 'user', content: JSON.stringify(user) }],
        tools: [tool], toolChoice: { name: 'decide_building' }, maxTokens: 700, model: this.model
      })
      const a = toolCalls[0] && toolCalls[0].args
      if (!a || !a.building) throw new Error('no decision returned')
      const w = clamp(a.width, 6, maxW, Math.min(10, maxW))
      const l = clamp(a.length, 6, maxL, Math.min(10, maxL))
      const rooms = a.rooms ? `, rooms: ${a.rooms}` : (brief.rooms ? `, rooms: ${brief.rooms}` : '')
      const goal = `${a.style || brief.styles[0]} ${a.archetype || brief.archetype} ${a.building}, ${w}x${l}${rooms}`
      const chat = String(a.chat || `Building a ${a.building} on plot ${this.plot.key}.`).slice(0, 120)
      return { via: this.provider, label: a.building, goal, chat }
    } catch (e) {
      this.log(`brain LLM unavailable (${e.message}); using role fallback`)
      const w = Math.min(brief.archetype === 'cathedral' ? 12 : 10, maxW)
      const l = Math.min(brief.archetype === 'cathedral' ? 16 : 10, maxL)
      const style = brief.styles[0]
      const goal = `${style} ${brief.archetype} ${role}, ${w}x${l}, rooms: ${brief.rooms}`
      const chat = `Building a ${role} on the ${this.plot.dir || ''} plot.`.replace('  ', ' ')
      return { via: 'fallback', label: role, goal, chat }
    }
  }

  // ---- brief world observation for the brain ----
  sampleGround(c) {
    for (let y = c.y + 12; y >= c.y - 30; y--) {
      let b; try { b = this.bot.blockAt(new Vec3(c.x, y, c.z)) } catch { b = null }
      if (b && b.name !== 'air' && b.name !== 'cave_air' && b.name !== 'void_air') return y
    }
    return c.y - 1
  }
  observe(c, groundTop) {
    let block = 'unknown'
    try { const b = this.bot.blockAt(new Vec3(c.x, groundTop, c.z)); if (b) block = b.name } catch {}
    // count nearby placed (non-natural) structure blocks to sense neighbours
    let neighbours = 0
    for (const [dx, dz] of [[-8, 0], [8, 0], [0, -8], [0, 8]]) {
      try { const b = this.bot.blockAt(new Vec3(c.x + dx, groundTop + 2, c.z + dz)); if (b && b.name !== 'air') neighbours++ } catch {}
    }
    return { groundBlock: block, groundTopY: groundTop, floorY: groundTop + 1, nearbyStructureBlocks: neighbours }
  }

  // ---- shared-state claim / status helpers (reuse the lease schema) ----
  claimMine() {
    return withState(this.statePath, state => claimRegion(state, this.plot.key, this.name, this.ttl))
  }
  releaseMine() {
    return withState(this.statePath, state => releaseRegion(state, this.plot.key, this.name)).catch(() => {})
  }
  myPendingJobs() {
    let state; try { state = JSON.parse(require('fs').readFileSync(this.statePath, 'utf8')) } catch { return [] }
    return state.jobs.filter(j => keyOf(j) === this.plot.key && !j.done && !j.skip).map(j => ({ ...j }))
  }
  postStatus(status) {
    const line = status === 'done'
      ? `${this._buildingLabel || this.plot.role} finished — placed ${this.placed} blocks by hand.`
      : `working on ${this._buildingLabel || this.plot.role}…`
    try { this.bot && this.bot.chat(line) } catch {}
    return withState(this.statePath, state => {
      state.crew = state.crew || {}
      const c = state.crew[this.plot.key] || (state.crew[this.plot.key] = {})
      c.agent = this.name; c.role = this.plot.role; c.building = this._buildingLabel
      c.placed = this.placed; c.skipped = this.skipped; c.status = status
    }).catch(() => {})
  }
}

function clamp(v, lo, hi, dflt) {
  const n = Math.round(+v)
  if (!Number.isFinite(n)) return dflt
  return Math.max(lo, Math.min(hi, n))
}

module.exports = { Agent, ROLE_BRIEF }
