// grok/lib/memory.js — persist the active build project so follow-ups
// ("add a moat", "make it taller") edit the SAME structure instead of restarting.
const fs = require('fs')
const path = require('path')

module.exports = function makeMemory(dir) {
  const FILE = path.join(dir || path.join(__dirname, '..', 'memory'), 'projects.json')
  function read() {
    try { return JSON.parse(fs.readFileSync(FILE, 'utf8')) } catch { return { projects: [], activeId: null } }
  }
  function write(data) {
    try { fs.mkdirSync(path.dirname(FILE), { recursive: true }); fs.writeFileSync(FILE, JSON.stringify(data, null, 2)) }
    catch (e) { /* non-fatal */ }
  }

  // Save/update a project after a build. Keyed by id; keeps last 20.
  function save(proj) {
    const data = read()
    const id = proj.id || `p${Date.now().toString(36)}`
    const now = new Date().toISOString()
    const existing = data.projects.find(p => p.id === id)
    const rec = Object.assign(existing || { id, createdAt: now }, proj, { id, updatedAt: now })
    if (!existing) data.projects.push(rec)
    data.projects = data.projects.slice(-20)
    data.activeId = id
    write(data)
    return rec
  }

  function active() {
    const data = read()
    return data.projects.find(p => p.id === data.activeId) || data.projects[data.projects.length - 1] || null
  }

  // Compact summary of the active project for the Architect context.
  function context() {
    const p = active()
    if (!p) return null
    return {
      project: p.project || p.name,
      origin: p.origin,
      palette: p.palette,
      phasesDone: (p.phasesDone || []).slice(0, 16),
      goal: p.goal
    }
  }

  return { save, active, context, read, FILE }
}
