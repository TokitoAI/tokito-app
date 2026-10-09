import assert from "node:assert/strict";
import test from "node:test";
import { chatSheets, selectedSheet } from "./sheetContext";
test("sheet context only uses canonical IDs and never guesses across sheets", () => {
  const sheets = chatSheets({ sheets: [{ id: "root", name: "Power" }, { id: "radio", name: "Radio" }] });
  assert.equal(selectedSheet(sheets, null), null);
  assert.equal(selectedSheet(sheets, "radio"), "radio");
  assert.equal(selectedSheet(sheets, "deleted"), null);
  assert.equal(selectedSheet(sheets.slice(1), null), "radio");
  assert.deepEqual(chatSheets({ sheets: [{ name: "fake" }, null, { id: "a" }, { id: "a" }] }), [{ id: "a", name: "a" }]);
  assert.deepEqual(chatSheets(undefined), []);
});
