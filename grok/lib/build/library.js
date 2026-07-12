// grok/lib/build/library.js — SCHEMATIC LIBRARY: save liked builds for reuse.
//
// Persists each saved build under blueprints/saved/:
//   <slug>.blueprint.json   the Layer-A semantic blueprint (re-anchorable + editable)
//   <slug>.schem            a prismarine-schematic export of the compiled target
//   index.json              name -> { file, schem, meta } catalog
//
// A saved build is reused by loading its blueprint, re-anchoring anchor.origin to
// a new world origin, and feeding it back through build/index.planAndBuild — so
// it flows through the SAME additive, site-aware compile→diff→realize path and
// honours GROK_BUILD_MODE. Because it round-trips as a blueprint, a subsequent
// edit_build works on it unchanged.

const fs = require('fs')
const path = require('path')
const { slug } = require('./util')
const { compile } = require('./compile')
const realize = require('./realize')

function makeLibrary(ctx) {
  const { dir, log = () => {} } = ctx
  const INDEX = path.join(dir, 'index.json')
  const readIndex = () => { try { return JSON.parse(fs.readFileSync(INDEX, 'utf8')) } catch { return { builds: {} } } }
  const writeIndex = (d) => { fs.mkdirSync(dir, { recursive: true }); fs.writeFileSync(INDEX, JSON.stringify(d, null, 2)) }
  const keyOf = (name) => slug(name)

  // Save a blueprint (its JSON + a .schem export) under `name`.
  async function save(name, blueprint, meta = {}) {
    if (!blueprint) throw new Error('nothing to save — no active build')
    fs.mkdirSync(dir, { recursive: true })
    const key = keyOf(name)
    const bpFile = path.join(dir, `${key}.blueprint.json`)
    const schemFile = path.join(dir, `${key}.schem`)
    fs.writeFileSync(bpFile, JSON.stringify(blueprint, null, 2))
    let schemOk = false
    try { const target = compile(blueprint); await realize.exportSchem(target, schemFile); schemOk = true }
    catch (e) { log('library schem err', e.message) }
    const idx = readIndex()
    idx.builds[key] = {
      name, key, file: path.basename(bpFile), schem: schemOk ? path.basename(schemFile) : null,
      regions: (blueprint.regions || []).length, style: blueprint.style || null,
      savedAt: new Date().toISOString(), ...meta
    }
    writeIndex(idx)
    return idx.builds[key]
  }

  function list() { return Object.values(readIndex().builds) }
  function has(name) { return !!readIndex().builds[keyOf(name)] }

  // Load a saved blueprint (fresh object each call). Returns null if unknown.
  function load(name) {
    const idx = readIndex(); const rec = idx.builds[keyOf(name)]
    if (!rec) return null
    try { return JSON.parse(fs.readFileSync(path.join(dir, rec.file), 'utf8')) } catch (e) { log('library load err', e.message); return null }
  }

  function remove(name) {
    const idx = readIndex(); const key = keyOf(name); const rec = idx.builds[key]
    if (!rec) return false
    for (const f of [rec.file, rec.schem]) if (f) { try { fs.unlinkSync(path.join(dir, f)) } catch {} }
    delete idx.builds[key]; writeIndex(idx); return true
  }

  // Re-anchor a loaded blueprint to a new world origin. Component coords are LOCAL
  // (build-space), so re-anchoring is just moving anchor.origin; anchorToSite (run
  // later in the pipeline) will drop origin.y onto the surveyed ground.
  function reanchor(bp, origin) {
    if (!bp.anchor) bp.anchor = {}
    bp.anchor.origin = { x: Math.round(origin.x), y: Math.round(origin.y), z: Math.round(origin.z) }
    return bp
  }

  return { save, list, has, load, remove, reanchor, dir, INDEX }
}

module.exports = makeLibrary
