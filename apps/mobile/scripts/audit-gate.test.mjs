import assert from "node:assert/strict";
import test from "node:test";

import { evaluateAudit } from "./audit-gate.mjs";

test("registry errors and missing reports fail closed", () => {
  assert.notDeepEqual(evaluateAudit({ error: { code: 'ENOTFOUND' } }).failures, []);
  assert.notDeepEqual(evaluateAudit({}).failures, []);
});

const allowed = {
  vulnerabilities: {
    "image-size": {
      severity: "high",
      via: [
        {
          source: 1138808,
          name: "image-size",
          severity: "high",
          title: "ICNS parser denial of service",
          url: "https://github.com/advisories/GHSA-w3rx-r6r6-pgpr",
        },
      ],
    },
    metro: { severity: "high", via: ["image-size"] },
  },
};

test("previously exempted upstream advisories fail without a source repair", () => {
  const result = evaluateAudit(allowed);
  assert.match(result.failures.join('\n'), /GHSA-w3rx-r6r6-pgpr/);
});

test("rejects an unacknowledged high advisory", () => {
  const report = structuredClone(allowed);
  report.vulnerabilities.malicious = {
    severity: "high",
    via: [
      {
        source: 999,
        name: "malicious",
        severity: "high",
        title: "new advisory",
        url: "https://github.com/advisories/GHSA-aaaa-bbbb-cccc",
      },
    ],
  };
  const result = evaluateAudit(report);
  assert.match(result.failures.join("\n"), /GHSA-aaaa-bbbb-cccc/);
});

test("accepts a verified repair only for its exact advisory and package", () => {
  const id = 'GHSA-w3rx-r6r6-pgpr';
  assert.deepEqual(evaluateAudit(allowed, new Map([[id, 'image-size']])).failures, []);
  assert.notDeepEqual(evaluateAudit(allowed, new Map([[id, 'wrong-package']])).failures, []);
});
