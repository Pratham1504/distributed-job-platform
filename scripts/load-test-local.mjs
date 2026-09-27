import { readFile } from "node:fs/promises";

const baseUrl = (process.env.LOAD_BASE_URL ?? "http://localhost:8088").replace(/\/$/, "");
const targetFile = process.env.LOAD_TARGETS_FILE ?? "scripts/load-targets.json";
const ratePerSecond = Number(process.env.LOAD_RATE_PER_SECOND ?? "1");
const durationSeconds = Number(process.env.LOAD_DURATION_SECONDS ?? "60");
const p95TargetMilliseconds = Number(process.env.LOAD_P95_TARGET_MS ?? "500");

if (!Number.isInteger(ratePerSecond) || ratePerSecond < 1 || !Number.isInteger(durationSeconds) || durationSeconds < 1) {
  throw new Error("LOAD_RATE_PER_SECOND and LOAD_DURATION_SECONDS must be positive integers.");
}

let targets;
try {
  targets = JSON.parse(await readFile(targetFile, "utf8"));
} catch (error) {
  throw new Error(`Cannot read ${targetFile}. Copy scripts/load-targets.example.json and add local test credentials.`, { cause: error });
}
if (!Array.isArray(targets) || targets.length === 0 || targets.some(target => !target.projectId || !target.apiKey)) {
  throw new Error("Each load target must include a projectId and apiKey.");
}

const count = ratePerSecond * durationSeconds;
const intervalMilliseconds = 1_000 / ratePerSecond;
const runId = crypto.randomUUID();
const outcomes = [];

async function submit(index) {
  const target = targets[index % targets.length];
  const startedAt = performance.now();
  try {
    const response = await fetch(`${baseUrl}/api/v1/projects/${target.projectId}/jobs`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-API-Key": target.apiKey,
        "Idempotency-Key": `load-${runId}-${index}`
      },
      body: JSON.stringify({
        jobType: "GENERATE_REPORT",
        payload: { template: "SALES_SUMMARY", periodStart: "2026-09-01", periodEnd: "2026-09-30" },
        priority: "DEFAULT",
        maxAttempts: 1
      })
    });
    outcomes.push({ status: response.status, milliseconds: performance.now() - startedAt });
  } catch {
    outcomes.push({ status: 0, milliseconds: performance.now() - startedAt });
  }
}

await Promise.all(Array.from({ length: count }, (_, index) => new Promise(resolve => {
  setTimeout(() => resolve(submit(index)), Math.round(index * intervalMilliseconds));
})));

const latencies = outcomes.map(outcome => outcome.milliseconds).sort((left, right) => left - right);
const percentile = fraction => latencies[Math.min(latencies.length - 1, Math.ceil(latencies.length * fraction) - 1)] ?? 0;
const accepted = outcomes.filter(outcome => outcome.status === 202).length;
const limited = outcomes.filter(outcome => outcome.status === 429).length;
const failures = outcomes.filter(outcome => outcome.status !== 202 && outcome.status !== 429).length;
const p95 = percentile(.95);

console.table({
  submitted: count,
  accepted,
  rateLimited: limited,
  otherFailures: failures,
  p50Milliseconds: Math.round(percentile(.5)),
  p95Milliseconds: Math.round(p95),
  p99Milliseconds: Math.round(percentile(.99))
});

if (failures > 0 || p95 > p95TargetMilliseconds) {
  process.exitCode = 1;
}
