import assert from "node:assert/strict";
import test from "node:test";
import { parseCanonicalPreview } from "./canonicalPreview";
test("preview responses must match project, design, sheet and revision", () => {
  const value = { project_id: "p", design_id: "d", sheet_id: "root", schematic_version: 2, source_sha256: "a".repeat(64), sha256: "b".repeat(64), svg: "<svg/>" };
  assert.equal(parseCanonicalPreview(JSON.stringify(value), "p", "d", "root", 2).svg, "<svg/>");
  for (const changed of [{ project_id: "other" }, { design_id: "other" }, { sheet_id: "other" }, { schematic_version: 1 }, { sha256: "bad" }]) assert.throws(() => parseCanonicalPreview(JSON.stringify({ ...value, ...changed }), "p", "d", "root", 2));
});
