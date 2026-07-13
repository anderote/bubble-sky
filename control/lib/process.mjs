import { spawn } from "node:child_process";

export function run(command, args = [], options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: options.cwd,
      env: { ...process.env, ...options.env },
      stdio: ["pipe", "pipe", "pipe"],
      shell: false,
    });
    const stdout = [];
    const stderr = [];
    const limit = options.outputLimit || 2_000_000;
    const collect = (target) => (chunk) => {
      target.push(chunk);
      while (target.reduce((sum, item) => sum + item.length, 0) > limit) target.shift();
      options.onOutput?.(chunk.toString());
    };
    child.stdout.on("data", collect(stdout));
    child.stderr.on("data", collect(stderr));
    child.once("error", reject);
    const timer = options.timeoutMs ? setTimeout(() => child.kill("SIGTERM"), options.timeoutMs) : null;
    child.once("close", (code, signal) => {
      if (timer) clearTimeout(timer);
      const result = { code, signal, stdout: Buffer.concat(stdout).toString("utf8"), stderr: Buffer.concat(stderr).toString("utf8") };
      if (code === 0 || options.allowFailure) resolve(result);
      else reject(Object.assign(new Error(`${command} exited ${code ?? signal}: ${result.stderr.slice(-1200)}`), { result }));
    });
    if (options.stdin !== undefined) child.stdin.end(options.stdin);
    else child.stdin.end();
  });
}
