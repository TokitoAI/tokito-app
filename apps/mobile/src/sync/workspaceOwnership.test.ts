import assert from "node:assert/strict";
import test from "node:test";
import { ownsWorkspaceEvent } from "./workspaceOwnership";

test("revocation belongs only to the active workspace lifetime", () => {
  assert.equal(ownsWorkspaceEvent("new", "new", false), true);
  assert.equal(ownsWorkspaceEvent("new", "old", false), false);
  assert.equal(ownsWorkspaceEvent("new", "new", true), false);
  assert.equal(ownsWorkspaceEvent("new", undefined, false), false);
  assert.equal(ownsWorkspaceEvent("new", null, false), false);
  assert.equal(ownsWorkspaceEvent("new", { owner: "new" }, false), false);
});
