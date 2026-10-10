import assert from "node:assert/strict";
import test from "node:test";
import { mergeCommittedResourceEvent } from "./resourceActivity";
import type { SyncEvent } from "./RealtimeWorkspace";

const event: SyncEvent = { project_id: "p", design_id: "d", resource: "bom", version: 2, payload: { lines: [2] }, seq: 2, command_id: "c2", actor_device_id: "phone", created_at: 90 };

test("new revision with older server stamp preserves bootstrap project activity", () => {
  const previous = { ...event, version: 1, payload: { lines: [1] }, last_activity_at: 100 };
  const next = mergeCommittedResourceEvent(previous, event);
  assert.equal(next.version, 2);
  assert.deepEqual(next.payload, { lines: [2] });
  assert.equal(next.last_activity_at, 100);
  assert.equal(mergeCommittedResourceEvent(next, { ...event, version: 3, created_at: 110 }).last_activity_at, 110);
});

test("duplicate or older revisions cannot replace content or aggregated recency", () => {
  const current = { ...event, last_activity_at: 120 };
  assert.equal(mergeCommittedResourceEvent(current, { ...event, created_at: 200 }), current);
  assert.equal(mergeCommittedResourceEvent(current, { ...event, version: 1, created_at: 200 }), current);
});

test("first committed event uses only its server stamp", () => {
  assert.equal(mergeCommittedResourceEvent(undefined, event).last_activity_at, 90);
});
