import fs from "node:fs";
import path from "node:path";
import { clip, shortId, writeJson } from "./common.mjs";
import { run } from "./process.mjs";

export class AgentRunner {
  constructor(config, hooks = {}) {
    this.config = config;
    this.hooks = hooks;
    this.jobsDir = path.join(config.runtimeDir, "jobs");
    this.worktreesDir = path.join(config.runtimeDir, "worktrees");
    fs.mkdirSync(this.jobsDir, { recursive: true });
    fs.mkdirSync(this.worktreesDir, { recursive: true });
    this.queue = [];
    this.running = false;
  }

  submit(input) {
    const provider = this.config.providers.find((item) => item.name === input.provider);
    if (!provider) throw Object.assign(new Error(`provider ${input.provider} is not available on ${this.config.nodeId}`), { statusCode: 400 });
    const job = {
      id: input.id || shortId(input.kind === "dev" ? "dev" : "chat"),
      nodeId: this.config.nodeId,
      provider: input.provider,
      kind: input.kind || "chat",
      prompt: input.prompt,
      requester: input.requester || "unknown",
      continuationOf: input.continuationOf || null,
      status: "queued",
      createdAt: new Date().toISOString(),
    };
    this.save(job);
    this.queue.push(job);
    queueMicrotask(() => this.drain());
    return job;
  }

  get(id) {
    try { return JSON.parse(fs.readFileSync(path.join(this.jobsDir, `${id}.json`), "utf8")); }
    catch (error) { if (error.code === "ENOENT") return null; throw error; }
  }

  list() {
    return fs.readdirSync(this.jobsDir).filter((name) => name.endsWith(".json"))
      .map((name) => this.get(name.slice(0, -5))).filter(Boolean)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 50);
  }

  async drain() {
    if (this.running) return;
    this.running = true;
    while (this.queue.length) {
      const job = this.queue.shift();
      try { await this.execute(job); }
      catch (error) {
        job.status = "failed";
        job.error = clip(error.stack || error.message, 1800);
        job.finishedAt = new Date().toISOString();
        this.save(job);
        this.hooks.event?.("job.failed", job, `${job.provider} failed ${job.id}: ${clip(error.message, 240)}`);
      }
    }
    this.running = false;
  }

  async execute(job) {
    job.status = "running";
    job.startedAt = new Date().toISOString();
    this.save(job);
    this.hooks.event?.("job.started", job, `${job.provider} started ${job.kind} job ${job.id}.`);
    if (job.kind === "dev") await this.executeDev(job);
    else await this.executeChat(job);
    job.status = "complete";
    job.finishedAt = new Date().toISOString();
    this.save(job);
    this.hooks.event?.("job.complete", job, `${job.provider}: ${clip(job.result, 500)}${job.prUrl ? ` PR: ${job.prUrl}` : ""}`);
  }

  async executeChat(job) {
    const previous = job.continuationOf ? this.get(job.continuationOf) : null;
    job.conversation = [
      ...(previous?.conversation || (previous ? [{ user: previous.prompt, assistant: previous.result }] : [])),
      { user: job.prompt, assistant: null },
    ].slice(-12);
    const nativeSession = previous?.sessionId || null;
    const invokeJob = nativeSession || !previous ? job : {
      ...job,
      prompt: job.conversation.map((turn) => `User: ${turn.user}${turn.assistant ? `\nAssistant: ${turn.assistant}` : ""}`).join("\n\n"),
    };
    const result = await this.invoke(invokeJob, this.config.repoRoot, nativeSession, "read-only");
    job.result = result.message;
    job.sessionId = result.sessionId || previous?.sessionId || null;
    job.conversation[job.conversation.length - 1].assistant = job.result;
  }

  async executeDev(job) {
    const branch = `agent/${job.provider}/${job.id}`.replace(/[^a-zA-Z0-9/_-]/g, "-");
    const worktree = path.join(this.worktreesDir, job.id);
    await run("git", ["fetch", "origin", "main"], { cwd: this.config.repoRoot, timeoutMs: 120_000 });
    await run("git", ["worktree", "add", "-b", branch, worktree, "origin/main"], { cwd: this.config.repoRoot, timeoutMs: 120_000 });
    job.branch = branch;
    job.worktree = worktree;
    this.save(job);
    const prompt = [
      "You are implementing a Bubble Sky development request in an isolated git worktree.",
      "Inspect the repository, make the requested change, run proportionate tests, and leave all intended edits in this worktree.",
      "Do not push, merge, deploy, or modify other checkouts. Summarize changes and tests in your final response.",
      `Request from Minecraft player ${job.requester}: ${job.prompt}`,
    ].join("\n\n");
    const result = await this.invoke({ ...job, prompt }, worktree, null, "workspace-write");
    job.result = result.message;
    job.sessionId = result.sessionId || null;
    const status = await run("git", ["status", "--porcelain"], { cwd: worktree });
    if (!status.stdout.trim()) return;
    await run("git", ["add", "-A"], { cwd: worktree });
    await run("git", ["commit", "-m", `agent: ${clip(job.prompt, 65)}`], { cwd: worktree, timeoutMs: 120_000 });
    await run("git", ["push", "-u", "origin", branch], { cwd: worktree, timeoutMs: 120_000 });
    const title = clip(job.prompt, 72);
    const body = `Requested in Minecraft by **${job.requester}** via **${job.provider}** on **${this.config.nodeId}**.\n\n${job.result}\n\nStation job: \`${job.id}\``;
    const pr = await run("gh", ["pr", "create", "--base", "main", "--head", branch, "--title", title, "--body", body], { cwd: worktree, timeoutMs: 120_000 });
    job.prUrl = pr.stdout.trim().split(/\s+/).find((item) => item.startsWith("http")) || pr.stdout.trim();
    await run("gh", ["pr", "merge", "--auto", "--squash", job.prUrl], { cwd: worktree, timeoutMs: 120_000, allowFailure: true });
  }

  async invoke(job, cwd, sessionId, sandbox) {
    const provider = this.config.providers.find((item) => item.name === job.provider);
    if (provider.adapter === "codex" || provider.name === "codex") return this.invokeCodex(job, cwd, sessionId, sandbox, provider);
    return this.invokeTemplate(job, cwd, sessionId, provider);
  }

  async invokeCodex(job, cwd, sessionId, sandbox, provider) {
    const output = path.join(this.jobsDir, `${job.id}.last.txt`);
    const args = sessionId
      ? ["exec", "resume", sessionId, "-", "-o", output]
      : ["exec", "-a", "never", "-s", sandbox, "-C", cwd, "-o", output, "--json", "-"];
    if (provider.profile && !sessionId) args.splice(1, 0, "-p", provider.profile);
    if (provider.model) args.splice(sessionId ? 3 : 1, 0, "-m", provider.model);
    const result = await run(provider.command || "codex", args, { cwd, stdin: job.prompt, timeoutMs: provider.timeoutMs || 1_800_000 });
    let foundSession = sessionId;
    for (const line of result.stdout.split("\n")) {
      try {
        const event = JSON.parse(line);
        foundSession ||= event.thread_id || (event.type === "thread.started" ? event.thread_id : null);
      } catch {}
    }
    return { message: fs.existsSync(output) ? fs.readFileSync(output, "utf8").trim() : clip(result.stdout, 4000), sessionId: foundSession };
  }

  async invokeTemplate(job, cwd, sessionId, provider) {
    if (!provider.command) throw new Error(`${provider.name} needs a command in station.json`);
    const replacements = { "{prompt}": job.prompt, "{cwd}": cwd, "{sessionId}": sessionId || "" };
    const template = job.kind === "dev" ? (provider.devArgs || provider.args) : (provider.chatArgs || provider.args);
    const args = (template || ["-p", "{prompt}"]).map((arg) => replacements[arg] ?? arg);
    const result = await run(provider.command, args, { cwd, timeoutMs: provider.timeoutMs || 1_800_000 });
    return { message: clip(result.stdout || result.stderr, 4000), sessionId };
  }

  save(job) { writeJson(path.join(this.jobsDir, `${job.id}.json`), job); }
}
