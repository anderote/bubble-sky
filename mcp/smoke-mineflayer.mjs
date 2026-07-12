#!/usr/bin/env node
import mineflayer from "../mindcraft/upstream/node_modules/mineflayer/index.js";

const host = process.env.MINECRAFT_HOST || "127.0.0.1";
const port = Number(process.env.MINECRAFT_PORT || 25565);
const username = process.env.MINEFLAYER_SMOKE_USERNAME || "CodexSmoke";
const version = process.env.MINECRAFT_VERSION || "1.21.6";

const bot = mineflayer.createBot({
  host,
  port,
  username,
  version,
  auth: "offline",
});

const timeout = setTimeout(() => {
  console.error(`Timed out waiting for ${username} to spawn on ${host}:${port}`);
  bot.end();
  process.exit(1);
}, 20000);

bot.once("spawn", () => {
  clearTimeout(timeout);
  console.log(JSON.stringify({
    ok: true,
    username: bot.username,
    host,
    port,
    version,
    position: bot.entity?.position?.toString(),
  }, null, 2));
  bot.chat("Codex Workstream B smoke test connected.");
  setTimeout(() => bot.end(), 1000);
});

bot.once("error", (error) => {
  clearTimeout(timeout);
  console.error(error);
  process.exit(1);
});

bot.once("kicked", (reason) => {
  clearTimeout(timeout);
  console.error("Kicked:", reason);
  process.exit(1);
});
