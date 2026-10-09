#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { verifiedDependencyRepairs } from "./dependency-patches.mjs";

function advisoryId(advisory) {
  const match = advisory.url?.match(/\/advisories\/(GHSA-[A-Za-z0-9-]+)$/);
  return match?.[1] ?? `source:${advisory.source}`;
}

function rootAdvisories(report, name, visited = new Set()) {
  if (visited.has(name)) return [];
  const vulnerability = report.vulnerabilities?.[name];
  if (!vulnerability) return [{ id: `dependency:${name}`, name, severity: "unknown" }];
  const nextVisited = new Set(visited).add(name);
  const roots = [];
  for (const via of vulnerability.via ?? []) {
    if (typeof via === "string") {
      roots.push(...rootAdvisories(report, via, nextVisited));
    } else {
      roots.push({
        id: advisoryId(via),
        name: via.name,
        severity: via.severity,
        title: via.title,
      });
    }
  }
  return roots;
}

export function evaluateAudit(report, repairs = new Map()) {
  if (report.error || !report.vulnerabilities || typeof report.vulnerabilities !== "object") {
    return { failures: ["npm audit did not return a valid vulnerability report"] };
  }
  const failures = [];
  for (const [name, vulnerability] of Object.entries(report.vulnerabilities ?? {})) {
    if (!new Set(["high", "critical"]).has(vulnerability.severity)) continue;
    const roots = rootAdvisories(report, name).filter((advisory) =>
      new Set(["high", "critical", "unknown"]).has(advisory.severity),
    );
    if (roots.length === 0) {
      failures.push(`${name}: high-severity dependency cycle has no attributable advisory`);
      continue;
    }
    for (const advisory of roots) {
      if (repairs.get(advisory.id) === advisory.name) continue;
      failures.push(`${name}: unresolved ${advisory.id} (${advisory.title ?? advisory.name})`);
    }
  }
  return { failures: [...new Set(failures)] };
}

function loadAuditReport() {
  const inputIndex = process.argv.indexOf("--input");
  if (inputIndex >= 0) {
    const input = process.argv[inputIndex + 1];
    if (!input) throw new Error("--input requires a JSON path");
    return JSON.parse(readFileSync(input, "utf8"));
  }
  const npmCli = process.env.npm_execpath;
  const command = npmCli ? process.execPath : "npm";
  const args = npmCli ? [npmCli, "audit", "--json"] : ["audit", "--json"];
  const result = spawnSync(command, args, {
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  if (!result.stdout.trim()) {
    throw new Error(`npm audit returned no JSON: ${result.stderr.trim()}`);
  }
  return JSON.parse(result.stdout);
}

function main() {
  const report = loadAuditReport();
  const repairs = verifiedDependencyRepairs(report);
  const { failures } = evaluateAudit(report, repairs);
  for (const [id, name] of repairs) console.log(`REPAIRED ${id} package=${name}: installed source SHA-256 verified`);
  if (failures.length > 0) {
    for (const failure of failures) console.error(`BLOCKED ${failure}`);
    return 1;
  }
  const counts = report.metadata?.vulnerabilities ?? {};
  console.log(
    `Dependency audit passed: no unresolved high/critical advisories; upstream report critical=${counts.critical ?? 0} high=${counts.high ?? 0}; ` +
      `${repairs.size} source-verified repairs.`,
  );
  return 0;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    process.exitCode = main();
  } catch (error) {
    console.error(`Dependency audit failed: ${error.message}`);
    process.exitCode = 1;
  }
}
