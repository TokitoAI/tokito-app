import assert from "node:assert/strict";
import test from "node:test";
import { canonicalBom } from "./canonicalBom";

test("canonical BOM joins part identity and schematic references without truncating quantity", () => {
  const result = canonicalBom({ lines: [{ part_id: "p", quantity: 2.5 }], parts: [{ id: "p", mpn: "REAL-MPN", package_name: "0603" }] }, { symbols: [{ part_id: "p", ref_des: "R2" }, { part_id: "p", ref_des: "R1" }] });
  assert.equal(result.error, null);
  assert.deepEqual(result.lines.map(({ ref, value, footprint, qty }) => ({ ref, value, footprint, qty })), [{ ref: "R1, R2", value: "REAL-MPN", footprint: "0603", qty: 2.5 }]);
});
test("deleted and invalid BOM do not display invented quantities or unrelated part records", () => {
  assert.deepEqual(canonicalBom({ deleted: true }, null), { lines: [], error: null });
  assert.notEqual(canonicalBom({ lines: [{ part_id: "p" }] }, null).error, null);
  const result = canonicalBom({ lines: [{ part_id: "p", quantity: 1 }], parts: [{ id: "other", mpn: "WRONG" }] }, {});
  assert.equal(result.lines[0].value, "Part details unavailable");
  assert.equal(result.lines[0].ref, "Unplaced");
});
