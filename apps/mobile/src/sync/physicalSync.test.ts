import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { mergePhysicalSessionSnapshot } from "./physicalSync";

describe("physical session sync", () => {
  it("merges one authorized snapshot without discarding other devices", () => {
    const merged = mergePhysicalSessionSnapshot(
      { schema_version: 1, sessions: { remote: { revision: 3 } } },
      { sessionId: "local", payloadJson: "{\"revision\":2,\"observations\":[]}" },
    );
    assert.deepEqual(merged, {
      schema_version: 1,
      sessions: {
        remote: { revision: 3 },
        local: { revision: 2, observations: [] },
      },
    });
  });

  it("normalizes malformed prior state instead of losing the local snapshot", () => {
    assert.deepEqual(
      mergePhysicalSessionSnapshot(null, {
        sessionId: "local",
        payloadJson: "{\"status\":\"finished\"}",
      }),
      {
        schema_version: 1,
        sessions: { local: { status: "finished" } },
      },
    );
  });
});
