// testbot.js — throwaway mineflayer harness driven by file-based IPC.
// Polls grok/testbot.cmd every 500ms; on change runs the command.
// Commands: pos | inv | slot N | cast | look <yaw> <pitch> | quit
// Logs go to stdout (redirect to grok/testbot-live.log).

const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')

const CMD_FILE = path.join(__dirname, 'testbot.cmd')
const USERNAME = process.env.TESTBOT_NAME || 'TestDummy'

function log(...a) {
  console.log(`[${new Date().toISOString().slice(11, 23)}]`, ...a)
}

const bot = mineflayer.createBot({
  host: 'localhost',
  port: 25565,
  username: USERNAME,
  auth: 'offline',
  version: '1.21.6'
})

const sleep = (ms) => new Promise(r => setTimeout(r, ms))

bot.on('spawn', () => {
  const p = bot.entity.position
  log(`SPAWN pos=(${p.x.toFixed(2)},${p.y.toFixed(2)},${p.z.toFixed(2)}) health=${bot.health} food=${bot.food}`)
})

bot.on('error', (e) => { log('ERROR', e && e.message ? e.message : e); process.exit(1) })
bot.on('kicked', (r) => { log('KICKED', typeof r === 'string' ? r : JSON.stringify(r)); process.exit(1) })
bot.on('end', (r) => { log('END', r || ''); process.exit(0) })

function hotbarNames() {
  // Hotbar is inventory slots 36-44 (quickbar 0-8).
  const out = []
  for (let i = 0; i < 9; i++) {
    const item = bot.inventory.slots[36 + i]
    if (!item) { out.push(`${i}: (empty)`); continue }
    let name = item.customName || null
    if (name) {
      try { const j = JSON.parse(name); name = j.text || (Array.isArray(j.extra) ? j.extra.map(e => e.text || '').join('') : null) || name } catch {}
    }
    const disp = name || item.displayName || item.name
    out.push(`${i}: ${disp} x${item.count} (${item.name})`)
  }
  return out
}

async function runCommand(raw) {
  const line = raw.trim()
  if (!line) return
  const parts = line.split(/\s+/)
  const cmd = parts[0].toLowerCase()
  try {
    if (cmd === 'pos') {
      const p = bot.entity.position
      log(`POS (${p.x.toFixed(2)},${p.y.toFixed(2)},${p.z.toFixed(2)}) yaw=${bot.entity.yaw.toFixed(2)} pitch=${bot.entity.pitch.toFixed(2)} onGround=${bot.entity.onGround}`)
    } else if (cmd === 'inv') {
      log('INV\n' + hotbarNames().join('\n'))
    } else if (cmd === 'slot') {
      const n = parseInt(parts[1], 10)
      bot.setQuickBarSlot(n)
      await sleep(150)
      log(`SLOT set to ${n}; held=${bot.heldItem ? bot.heldItem.name : '(empty)'}`)
    } else if (cmd === 'cast') {
      bot.activateItem()
      await sleep(220)
      bot.deactivateItem()
      log(`CAST activated+deactivated held=${bot.heldItem ? bot.heldItem.name : '(empty)'}`)
    } else if (cmd === 'look') {
      const yaw = parseFloat(parts[1])
      const pitch = parseFloat(parts[2])
      await bot.look(yaw, pitch, true)
      log(`LOOK yaw=${yaw} pitch=${pitch}`)
    } else if (cmd === 'quit') {
      log('QUIT requested')
      bot.quit()
      setTimeout(() => process.exit(0), 500)
    } else {
      log('UNKNOWN cmd:', line)
    }
  } catch (e) {
    log('CMD ERROR', cmd, e && e.message ? e.message : e)
  }
}

let lastContent = null
// Prime lastContent so a pre-existing file doesn't fire on startup.
try { lastContent = fs.readFileSync(CMD_FILE, 'utf8') } catch {}

setInterval(() => {
  let content
  try { content = fs.readFileSync(CMD_FILE, 'utf8') } catch { return }
  if (content !== lastContent) {
    lastContent = content
    runCommand(content)
  }
}, 500)

log(`testbot starting as ${USERNAME}, polling ${CMD_FILE}`)
