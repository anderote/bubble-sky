import assert from "node:assert/strict";
import test from "node:test";
import { formatJobHandoff } from "../lib/handoff.mjs";

test("Minecraft jobs become self-contained chat-app handoffs", () => {
  const handoff = formatJobHandoff({
    id: "chat-example",
    kind: "chat",
    provider: "claude",
    nodeId: "andrew-mac",
    requester: "claudebert",
    status: "complete",
    conversation: [
      { user: "How does the bridge work?", assistant: "It is a token-gated HTTP API." },
      { user: "Check the thread boundary too.", assistant: "World changes run on the server thread." },
    ],
  });
  assert.match(handoff, /Job: `chat-example`/);
  assert.match(handoff, /Player: How does the bridge work/);
  assert.match(handoff, /claude: It is a token-gated HTTP API/);
  assert.match(handoff, /\.\/scripts\/station\.mjs announce/);
});
