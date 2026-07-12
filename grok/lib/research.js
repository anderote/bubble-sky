// grok/lib/research.js — WEB-RESEARCH step for the Architect.
//
// research(request) -> { brief, palette?, features?, sources?, _via }
//
// Calls the Anthropic Messages API directly (we already talk to it raw via fetch
// in lib/llm.js) with the WEB SEARCH server tool enabled, and asks claude-fable-5
// for a COMPACT, actionable Minecraft-build reference brief. The result is small —
// it feeds the design-params planner prompt, not the user.
//
// Graceful + bounded:
//   - ~20s timeout per attempt
//   - in-memory + on-disk cache keyed by normalized request (memory/research-cache.json)
//   - if web search is unavailable / errors / refuses, fall back to a model-only
//     brief (no tools); if THAT fails, return an empty brief. Never throw — a
//     research failure must never block or crash a build.
//   - only qualifying (open-ended / "nice" / "detailed" / named-style) requests
//     should be researched (see shouldResearch); "9x9 tower" should NOT be.
const fs = require('fs')
const path = require('path')

const MODEL = process.env.GROK_RESEARCH_MODEL || 'claude-fable-5'
const TIMEOUT_MS = +(process.env.GROK_RESEARCH_TIMEOUT_MS || 20000)
const CACHE_FILE = path.join(__dirname, '..', 'memory', 'research-cache.json')

const memCache = new Map()
let diskCache = null
function disk() {
  if (diskCache) return diskCache
  try { diskCache = JSON.parse(fs.readFileSync(CACHE_FILE, 'utf8')) } catch { diskCache = {} }
  return diskCache
}
function loadCache(key) {
  if (memCache.has(key)) return memCache.get(key)
  const d = disk()
  if (d[key]) { memCache.set(key, d[key]); return d[key] }
  return null
}
function saveCache(key, val) {
  memCache.set(key, val)
  const d = disk(); d[key] = val
  try { fs.mkdirSync(path.dirname(CACHE_FILE), { recursive: true }); fs.writeFileSync(CACHE_FILE, JSON.stringify(d, null, 2)) } catch {}
}

// Normalize a request into a stable cache key.
function normReq(request) {
  return String(request || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 120)
}

const SYS = `You are a Minecraft build-reference researcher. Given a build request, produce a COMPACT, actionable reference brief a Minecraft (Java 1.21.6) builder can act on. Search the web for real reference builds/tutorials when helpful.
Return ONLY a single compact JSON object (no prose, no markdown fences), with keys:
  brief:    2-4 sentences: recommended style + layout/proportion tips.
  palette:  { wall, trim, accent, roof, floor, light } of REAL Minecraft 1.21.6 block names.
  features: array of 3-6 short strings (key features / rooms).
  sources:  array of up to 2 source URLs (omit if none).
Keep it small and specific — it feeds an automated planner, not a human.`

async function callAnthropic(body, signal) {
  const res = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: { 'x-api-key': process.env.ANTHROPIC_API_KEY, 'anthropic-version': '2023-06-01', 'content-type': 'application/json' },
    body: JSON.stringify(body), signal
  })
  if (!res.ok) throw new Error(`anthropic ${res.status}: ${(await res.text()).slice(0, 160)}`)
  return res.json()
}

// Pull the concatenated text + any web_search source URLs out of a Messages response.
function extract(j) {
  let text = ''
  const sources = []
  for (const b of (j.content || [])) {
    if (b.type === 'text') text += b.text
    else if (b.type === 'web_search_tool_result' && Array.isArray(b.content)) {
      for (const r of b.content) if (r && r.url) sources.push(r.url)
    }
  }
  return { text: text.trim(), sources }
}

function parseBrief(text, fallbackSources) {
  let obj = null
  const m = text.match(/\{[\s\S]*\}/)
  if (m) { try { obj = JSON.parse(m[0]) } catch {} }
  if (!obj) obj = { brief: text.slice(0, 600) }
  if ((!obj.sources || !obj.sources.length) && fallbackSources && fallbackSources.length) obj.sources = fallbackSources.slice(0, 2)
  return obj
}

async function attempt(body, label) {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), TIMEOUT_MS)
  try {
    const j = await callAnthropic(body, ctrl.signal)
    if (j.stop_reason === 'refusal') throw new Error('refusal')
    const { text, sources } = extract(j)
    if (!text) throw new Error('empty response')
    const brief = parseBrief(text, sources)
    brief._via = label
    return brief
  } finally { clearTimeout(timer) }
}

// research(request) — never throws. Returns a small brief object; on total failure
// returns { brief: '', _via: 'none', _fallback: <reason> } so a build still proceeds.
async function research(request) {
  const key = normReq(request)
  const cached = loadCache(key)
  if (cached) return Object.assign({}, cached, { _via: (cached._via || 'cache') + '+cache' })
  if (!process.env.ANTHROPIC_API_KEY) return { brief: '', _via: 'none', _fallback: 'no ANTHROPIC_API_KEY' }

  const user = `Build request: "${request}". Produce the compact JSON reference brief.`
  const withSearch = {
    model: MODEL, max_tokens: 1500, system: SYS,
    messages: [{ role: 'user', content: user }],
    tools: [{ type: 'web_search_20260209', name: 'web_search', max_uses: 3 }]
  }
  const modelOnly = { model: MODEL, max_tokens: 1200, system: SYS, messages: [{ role: 'user', content: user }] }

  let result, searchErr
  try {
    result = await attempt(withSearch, 'web_search')
  } catch (e) {
    searchErr = e.message
    try { result = await attempt(modelOnly, 'model-only'); result._searchErr = searchErr }
    catch (e2) { return { brief: '', _via: 'none', _fallback: `search: ${searchErr}; model: ${e2.message}` } }
  }
  saveCache(key, result)
  return result
}

// Whether a build request warrants a web-research pass. Open-ended / "nice" /
// "detailed" / named-style requests qualify; precise dimensioned primitives
// (e.g. "9x9 stone tower") do NOT.
function shouldResearch(goal) {
  const g = String(goal || '').toLowerCase()
  // Explicit dimensions like 9x9 / 12 x 20 → a precise request, skip research.
  if (/\b\d+\s*[x×]\s*\d+\b/.test(g)) return false
  return /\b(cozy|cosy|nice|detailed|grand|epic|fancy|beautiful|impressive|elegant|ornate|rustic|charming|whimsical|cottage|cabin|castle|keep|fortress|mansion|manor|palace|cathedral|chapel|temple|village|town|tavern|inn|farmhouse|windmill|lighthouse|wizard|elven|fantasy|medieval|modern|japanese|pagoda|greenhouse|treehouse)\b/.test(g)
    || /\bmake something\b|\bsomething cool\b|\bsurprise me\b/.test(g)
}

module.exports = { research, shouldResearch, normReq }
