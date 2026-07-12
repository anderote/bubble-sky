// grok/lib/llm.js — pluggable LLM backend, one method per client:
//   chat({ system, messages, tools, toolChoice, maxTokens, model })
//     → { text, toolCalls: [{ id, name, args }] }   (args already JSON-parsed)
//
// Neutral shapes (provider-independent):
//   messages   = [{ role:'user'|'assistant', content }]   (no system role — passed separately)
//   tools      = [{ name, description, schema }]           (schema = JSON Schema object)
//   toolChoice = 'auto' | { name } | 'none'
//
// Two backends selected by `provider`: 'xai' (OpenAI-compatible) and 'anthropic'.

// ---- xAI (OpenAI-compatible chat/completions) ----
async function xaiChat({ system, messages, tools, toolChoice, maxTokens, model }) {
  const KEY = process.env.XAI_API_KEY
  const body = {
    model,
    max_tokens: maxTokens || 1024,
    temperature: 0.4,
    messages: [{ role: 'system', content: system }, ...messages],
  }
  if (tools && tools.length) {
    body.tools = tools.map(t => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.schema } }))
    body.tool_choice = toolChoice === 'none' ? 'none'
      : (toolChoice && toolChoice.name) ? { type: 'function', function: { name: toolChoice.name } }
      : 'auto'
  }
  const res = await fetch('https://api.x.ai/v1/chat/completions', {
    method: 'POST', headers: { Authorization: `Bearer ${KEY}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body)
  })
  if (!res.ok) throw new Error(`xai ${res.status}: ${(await res.text()).slice(0, 160)}`)
  const msg = (await res.json()).choices?.[0]?.message || {}
  const toolCalls = (msg.tool_calls || []).map(c => {
    let args = {}; try { args = JSON.parse(c.function?.arguments || '{}') } catch {}
    return { id: c.id, name: c.function?.name, args }
  })
  return { text: (msg.content || '').trim(), toolCalls }
}

// ---- Anthropic (Messages API) ----
async function anthropicChat({ system, messages, tools, toolChoice, maxTokens, model }) {
  const KEY = process.env.ANTHROPIC_API_KEY
  // NB: no temperature/top_p/top_k and no thinking/budget_tokens — those 400 on
  // Opus 4.8 / Fable 5. Omitting `thinking` is correct (Opus runs without it;
  // Fable always thinks).
  const body = {
    model,
    max_tokens: maxTokens || 1024,
    system,
    messages,
  }
  if (tools && tools.length) {
    body.tools = tools.map(t => ({ name: t.name, description: t.description, input_schema: t.schema }))
    body.tool_choice = toolChoice === 'none' ? { type: 'none' }
      : (toolChoice && toolChoice.name) ? { type: 'tool', name: toolChoice.name }
      : { type: 'auto' }
  }
  const res = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: { 'x-api-key': KEY, 'anthropic-version': '2023-06-01', 'content-type': 'application/json' },
    body: JSON.stringify(body)
  })
  if (!res.ok) throw new Error(`anthropic ${res.status}: ${(await res.text()).slice(0, 200)}`)
  const j = await res.json()
  if (j.stop_reason === 'refusal') return { text: '(the model declined that request)', toolCalls: [] }
  let text = ''
  const toolCalls = []
  for (const block of (j.content || [])) {
    if (block.type === 'text') text += block.text
    else if (block.type === 'tool_use') toolCalls.push({ id: block.id, name: block.name, args: block.input })
  }
  return { text: text.trim(), toolCalls }
}

const BACKENDS = { xai: xaiChat, anthropic: anthropicChat }

// Factory: createLLM({ provider }) → { provider, chat }
function createLLM({ provider }) {
  const backend = BACKENDS[provider]
  if (!backend) throw new Error(`unknown LLM provider: ${provider}`)
  return { provider, chat: (opts) => backend(opts) }
}

module.exports = { createLLM }
