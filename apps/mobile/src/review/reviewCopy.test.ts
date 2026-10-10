import assert from "node:assert/strict";
import test from "node:test";
import { applyChangeLabel, changeCount, proposedChangeCount, remainingElectricalIssues, savedDecisionCount } from "./reviewCopy";

test("proposal and selection labels agree for zero, one and multiple changes", () => {
  for (const [count, noun] of [[0, "changes"], [1, "change"], [2, "changes"], [100, "changes"]] as const) {
    assert.equal(changeCount(count), `${count} ${noun}`);
    assert.equal(proposedChangeCount(count), `${count} proposed ${noun}`);
    assert.equal(applyChangeLabel(count), `Apply ${count} ${noun}`);
  }
});

test("queued decisions and electrical results use proper singular and plural grammar", () => {
  assert.equal(savedDecisionCount(0), "0 saved decisions");
  assert.equal(savedDecisionCount(1), "1 saved decision");
  assert.equal(savedDecisionCount(3), "3 saved decisions");
  assert.equal(remainingElectricalIssues(0), "No electrical check issues reported.");
  assert.equal(remainingElectricalIssues(1), "1 electrical check issue remains.");
  assert.equal(remainingElectricalIssues(13), "13 electrical check issues remain.");
});
