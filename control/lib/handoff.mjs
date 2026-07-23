import { clip } from "./common.mjs";

export function formatJobHandoff(job) {
  const lines = [
    "# Bubble Sky Minecraft handoff",
    "",
    `- Job: \`${job.id}\``,
    `- Type: ${job.kind || "chat"}`,
    `- Agent: ${job.provider || "unknown"} on ${job.nodeId || "unknown node"}`,
    `- Requested by: ${job.requester || "unknown"}`,
    `- Status: ${job.status || "unknown"}`,
    ...(job.branch ? [`- Branch: \`${job.branch}\``] : []),
    ...(job.prUrl ? [`- PR: ${job.prUrl}`] : []),
    "",
  ];
  if (job.conversation?.length) {
    lines.push("## Conversation");
    for (const turn of job.conversation) {
      lines.push("", `Player: ${turn.user}`);
      if (turn.assistant) lines.push("", `${job.provider || "Agent"}: ${turn.assistant}`);
    }
  } else {
    lines.push("## Player request", "", job.prompt || "(request unavailable)");
    if (job.result) lines.push("", "## Agent result", "", job.result);
  }
  if (job.error) lines.push("", "## Error", "", job.error);
  lines.push(
    "",
    "## Continue in this chat app",
    "",
    "Inspect the current repository and verify this job's branch/PR state before changing anything.",
    "Continue from the context above, preserve unrelated work, run proportionate tests, and report",
    "the result back to Minecraft with:",
    "",
    "```sh",
    `./scripts/station.mjs announce "${escapeShellSummary(job)}"`,
    "```",
  );
  return `${lines.join("\n")}\n`;
}

function escapeShellSummary(job) {
  return clip(`${job.provider || "Agent"} app picked up ${job.id}; update: <replace with a short status>`, 160)
    .replace(/["\\$`]/g, "\\$&");
}
