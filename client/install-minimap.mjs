#!/usr/bin/env node
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { pipeline } from "node:stream/promises";

const minecraftVersion = process.env.MINECRAFT_VERSION || "1.21.6";
const instanceDir = expandHome(
  process.env.PRISM_INSTANCE_DIR ||
    "~/Library/Application Support/PrismLauncher/instances/bubble-sky-1.21.6",
);
const minecraftDir = path.join(instanceDir, ".minecraft");
const modsDir = path.join(minecraftDir, "mods");
const packPath = path.join(instanceDir, "mmc-pack.json");

const modrinthProjects = [
  { name: "Fabric API", slug: "fabric-api" },
  { name: "Xaero's Minimap", slug: "xaeros-minimap" },
];

await ensureFabricLoader();
fs.mkdirSync(modsDir, { recursive: true });

for (const project of modrinthProjects) {
  await installModrinthProject(project);
}

console.log(`Minimap client mods installed for Minecraft ${minecraftVersion}.`);
console.log(`Instance: ${instanceDir}`);
console.log("Restart Minecraft from Prism so Fabric and the minimap mods load.");

async function ensureFabricLoader() {
  const pack = JSON.parse(fs.readFileSync(packPath, "utf8"));
  const loader = await fetchJson(`https://meta.fabricmc.net/v2/versions/loader/${minecraftVersion}`);
  const stable = loader.find((entry) => entry.loader?.stable) || loader[0];

  if (!stable?.loader?.version) {
    throw new Error(`No Fabric loader version found for Minecraft ${minecraftVersion}`);
  }

  const component = {
    cachedName: "Fabric Loader",
    cachedVersion: stable.loader.version,
    important: true,
    uid: "net.fabricmc.fabric-loader",
    version: stable.loader.version,
  };

  const existing = pack.components.findIndex((entry) => entry.uid === component.uid);
  if (existing >= 0) {
    pack.components[existing] = { ...pack.components[existing], ...component };
  } else {
    pack.components.push(component);
  }

  fs.writeFileSync(packPath, `${JSON.stringify(pack, null, 4)}\n`);
  console.log(`Fabric Loader ${stable.loader.version} configured.`);
}

async function installModrinthProject(project) {
  const versions = await fetchJson(
    `https://api.modrinth.com/v2/project/${project.slug}/version?${new URLSearchParams({
      game_versions: JSON.stringify([minecraftVersion]),
      loaders: JSON.stringify(["fabric"]),
    })}`,
  );
  const version = versions.find((entry) => entry.version_type === "release") || versions[0];
  const file = version?.files?.find((entry) => entry.primary) || version?.files?.[0];

  if (!file?.url || !file?.filename) {
    throw new Error(`No ${project.name} Fabric file found for Minecraft ${minecraftVersion}`);
  }

  removeOlderProjectFiles(project.slug);
  const destination = path.join(modsDir, file.filename);
  await downloadFile(file.url, destination);
  console.log(`${project.name} installed: ${file.filename}`);
}

function removeOlderProjectFiles(slug) {
  const prefixes = {
    "fabric-api": ["fabric-api-"],
    "xaeros-minimap": ["Xaeros_Minimap_", "Xaeros_Minimap-"],
  }[slug] || [];

  for (const file of fs.readdirSync(modsDir, { withFileTypes: true })) {
    if (!file.isFile()) continue;
    if (prefixes.some((prefix) => file.name.startsWith(prefix))) {
      fs.rmSync(path.join(modsDir, file.name));
    }
  }
}

async function fetchJson(url) {
  const response = await fetch(url, {
    headers: { "User-Agent": "bubble-sky-client-installer/1.0 (github.com/anderote/bubble-sky)" },
  });

  if (!response.ok) {
    throw new Error(`GET ${url} failed: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

async function downloadFile(url, destination) {
  const response = await fetch(url, {
    headers: { "User-Agent": "bubble-sky-client-installer/1.0 (github.com/anderote/bubble-sky)" },
  });

  if (!response.ok || !response.body) {
    throw new Error(`download failed: ${url}`);
  }

  await pipeline(response.body, fs.createWriteStream(destination));
}

function expandHome(value) {
  if (value === "~") return os.homedir();
  if (value.startsWith("~/")) return path.join(os.homedir(), value.slice(2));
  return value;
}
