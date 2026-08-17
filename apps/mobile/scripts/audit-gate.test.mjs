import assert from "node:assert/strict";
import test from "node:test";

import { evaluateAudit } from "./audit-gate.mjs";

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

test("accepts only the bounded unpatched build-time advisory", () => {
  const result = evaluateAudit(allowed, new Date("2026-08-18T00:00:00Z"));
  assert.deepEqual(result.failures, []);
  assert.equal(result.acknowledged.size, 1);
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
  const result = evaluateAudit(report, new Date("2026-08-18T00:00:00Z"));
  assert.match(result.failures.join("\n"), /GHSA-aaaa-bbbb-cccc/);
});

test("rejects an expired exception", () => {
  const result = evaluateAudit(allowed, new Date("2026-10-01T00:00:00Z"));
  assert.match(result.failures.join("\n"), /expired/);
});
