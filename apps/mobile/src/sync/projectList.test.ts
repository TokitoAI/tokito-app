import assert from "node:assert/strict";
import test from "node:test";
import { formatProjectUpdatedAt, mergeProjectList, projectActivityTimestamp } from "./projectList";

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

test("committed approval and chat activity override stale metadata without changing it", () => {
  const resources = [
    { project_id: "old", design_id: "d", resource: "schematic", payload: {}, created_at: 1_800_000_000 },
    { project_id: "old", design_id: "d", resource: "bom", payload: {}, created_at: 1_800_000_000 },
    { project_id: "old", design_id: "d", resource: "conversations", payload: {}, created_at: 1_800_000_010 },
  ];
  const result = mergeProjectList([recent, old], resources);
  assert.equal(result[0].id, "old");
  assert.equal(result[0].updated_at, old.updated_at);
  assert.equal(result[0].last_activity_at, 1_800_000_010);
  assert.equal(result[1].last_activity_at, undefined);
  assert.equal(formatProjectUpdatedAt(result[0], 1_800_000_130_000), "Updated 2m ago");
});

test("index and cold bootstrap retain latest committed activity across independent projects", () => {
  const index = [{ ...old, last_activity_at: 1_800_000_005 }, recent];
  const resources = [
    { project_id: "old", design_id: null, resource: "project", payload: { name: old.name, updated_at: old.updated_at }, last_activity_at: 1_800_000_010 },
    { project_id: "old", design_id: "d", resource: "conversations", payload: {}, last_activity_at: 1_800_000_010 },
  ];
  const cold = mergeProjectList(index, JSON.parse(JSON.stringify(resources)));
  assert.equal(cold[0].last_activity_at, 1_800_000_010);
  assert.equal(mergeProjectList(cold, [{ project_id: "old", design_id: "d", resource: "bom", payload: {}, created_at: 1_800_000_000 }])[0].last_activity_at, 1_800_000_010);
  assert.deepEqual(mergeProjectList(cold, resources), cold);
});

test("recent child activity cannot resurrect a project tombstone or invent a project", () => {
  assert.deepEqual(mergeProjectList([old], [
    { project_id: "old", design_id: null, resource: "project", payload: { deleted: true } },
    { project_id: "old", design_id: "d", resource: "schematic", payload: {}, created_at: 1_800_000_010 },
    { project_id: "missing", design_id: "d", resource: "conversations", payload: {}, created_at: 1_800_000_020 },
  ]), []);
});

test("unknown or malformed activity stays unknown; local read time only formats elapsed age", () => {
  for (const value of [null, -1, NaN, Infinity, 1.5]) {
    const project = { ...old, updated_at: "", last_activity_at: value };
    assert.equal(projectActivityTimestamp(project), null);
    assert.equal(formatProjectUpdatedAt(project, 1_800_000_000_000), "Update time unavailable");
  }
});
