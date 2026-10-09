import assert from "node:assert/strict";
import test from "node:test";
import { mergeProjectList } from "./projectList";

const old = { id: "old", name: "Old", slug: "old", updated_at: "2025-01-01" };
const recent = { id: "new", name: "Recent", slug: "new", updated_at: "2026-10-10" };
test("latest projects sort first and a slow index cannot replace journal names", () => {
  const resources = [{ project_id: "old", design_id: null, resource: "project", payload: { name: "Renamed" } }];
  const result = mergeProjectList([old, recent], resources);
  assert.deepEqual(result.map(item => item.name), ["Recent", "Renamed"]);
  assert.equal(result[1].updated_at, old.updated_at);
});
test("tombstones win over published snapshots without discarding unrelated projects", () => {
  assert.deepEqual(mergeProjectList([old, recent], [{ project_id: "old", design_id: null, resource: "project", payload: { deleted: true } }]), [recent]);
});
test("missing dates never become fictitious just-now updates", () => {
  assert.equal(mergeProjectList([], [{ project_id: "old", design_id: null, resource: "project", payload: { name: "Old" } }])[0].updated_at, "");
});
