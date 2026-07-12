#!/usr/bin/env node
import { createSign } from "node:crypto";
import { readFileSync } from "node:fs";
import { basename } from "node:path";
import { parseArgs } from "node:util";

const { values } = parseArgs({
  options: {
    owner: { type: "string", default: "anderote" },
    repo: { type: "string", default: "bubble-sky" },
    appId: { type: "string" },
    installationId: { type: "string", default: "146003999" },
    privateKey: { type: "string" },
    commit: { type: "string" },
    issue: { type: "string" },
    body: { type: "string" },
  },
});

const appId = values.appId || process.env.GITHUB_APP_ID;
const installationId = values.installationId || process.env.GITHUB_APP_INSTALLATION_ID;
const privateKeyPath = values.privateKey || process.env.GITHUB_APP_PRIVATE_KEY_PATH;
const body = values.body || process.env.GITHUB_COMMENT_BODY;

if (!appId || !installationId || !privateKeyPath || !body) {
  fail(`Missing required config.

Usage examples:
  ${basename(process.argv[1])} --appId 123 --privateKey ./codex-bubble-sky.pem --commit <sha> --body "LGTM"
  ${basename(process.argv[1])} --appId 123 --privateKey ./codex-bubble-sky.pem --issue 1 --body "Comment text"

Required:
  --appId or GITHUB_APP_ID
  --installationId or GITHUB_APP_INSTALLATION_ID
  --privateKey or GITHUB_APP_PRIVATE_KEY_PATH
  --body or GITHUB_COMMENT_BODY
  one of --commit or --issue`);
}

if (!values.commit && !values.issue) {
  fail("Pass either --commit <sha> or --issue <number>.");
}

const privateKey = readFileSync(privateKeyPath, "utf8");
const jwt = signJwt({ iss: appId }, privateKey);
const installationToken = await createInstallationToken(installationId, jwt);

const endpoint = values.commit
  ? `/repos/${values.owner}/${values.repo}/commits/${values.commit}/comments`
  : `/repos/${values.owner}/${values.repo}/issues/${values.issue}/comments`;

const comment = await github(endpoint, installationToken, {
  method: "POST",
  body: JSON.stringify({ body }),
});

console.log(comment.html_url);

function signJwt(payload, key) {
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: "RS256", typ: "JWT" };
  const claims = { iat: now - 60, exp: now + 9 * 60, ...payload };
  const encoded = `${base64url(header)}.${base64url(claims)}`;
  const signature = createSign("RSA-SHA256").update(encoded).sign(key, "base64url");
  return `${encoded}.${signature}`;
}

async function createInstallationToken(id, jwt) {
  const data = await github(`/app/installations/${id}/access_tokens`, jwt, {
    method: "POST",
  });
  return data.token;
}

async function github(path, token, init = {}) {
  const response = await fetch(`https://api.github.com${path}`, {
    ...init,
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      "X-GitHub-Api-Version": "2022-11-28",
      ...init.headers,
    },
  });

  const text = await response.text();
  const data = text ? JSON.parse(text) : {};
  if (!response.ok) {
    fail(`${response.status} ${response.statusText}: ${JSON.stringify(data)}`);
  }
  return data;
}

function base64url(value) {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

function fail(message) {
  console.error(message);
  process.exit(1);
}
