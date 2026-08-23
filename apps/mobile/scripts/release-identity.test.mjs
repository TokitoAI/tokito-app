import assert from "node:assert/strict";
import test from "node:test";

import { validateReleaseIdentity } from "./release-identity.mjs";

const valid = {
  packageVersion: "0.2.2",
  appVersion: "0.2.2",
  versionCode: 8,
  releaseTag: "v0.2.2-pre.2",
  previousVersionCodes: [6, 7],
};

test("accepts aligned prerelease identity with a monotonic Android code", () => {
  assert.deepEqual(validateReleaseIdentity(valid), []);
});

test("rejects package, Expo, and tag version drift", () => {
  const failures = validateReleaseIdentity({ ...valid, appVersion: "0.2.1" });
  assert.match(failures.join("\n"), /package\.json version/);
  assert.match(failures.join("\n"), /release tag base/);
});

test("rejects a reused Android version code", () => {
  const failures = validateReleaseIdentity({ ...valid, versionCode: 7 });
  assert.match(failures.join("\n"), /must exceed prior released maximum 7/);
});

test("allows ordinary CI validation without a release tag", () => {
  assert.deepEqual(
    validateReleaseIdentity({
      packageVersion: "0.2.2",
      appVersion: "0.2.2",
      versionCode: 8,
    }),
    [],
  );
});
