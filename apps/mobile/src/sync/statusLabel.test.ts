import { strict as assert } from "node:assert";
import { test } from "node:test";
import { projectUpdatesLabel } from "./statusLabel";

test("transport labels distinguish actual states without presenting hydration as connected", () => {
  assert.equal(projectUpdatesLabel("live"), "Project updates connected");
  assert.equal(projectUpdatesLabel("connecting"), "Project updates connecting…");
  assert.equal(projectUpdatesLabel("reconnecting"), "Project updates reconnecting…");
  assert.equal(projectUpdatesLabel("disabled"), "Project updates unavailable");
  assert.equal(projectUpdatesLabel("stopped"), "Project updates offline");
  assert.equal(projectUpdatesLabel("connecting", true), "Updates connecting…");
});
