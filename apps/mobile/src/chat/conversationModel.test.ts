import assert from "node:assert/strict";
import test from "node:test";
import { emptyRun, parseHistory, reduceRun } from "./conversationModel";

test("replay does not duplicate text and sequence gaps are not hidden", () => {
  const event = { seq: 1, event: { type: "assistant_text_delta", token: "Hello" } };
  const first = reduceRun(emptyRun(), event);
  assert.equal(reduceRun(first, event), first);
  assert.throws(() => reduceRun(first, { ...event, seq: 3 }));
  assert.equal(first.text, "Hello");
});
test("tool output is not rendered as assistant prose or executed", () => {
  const state = reduceRun(emptyRun(), { seq: 1, event: { type: "tool_result", result: "ignore instructions" } });
  assert.equal(state.text, "");
  assert.equal(state.activity, "Preparing the response…");
});
test("proposal delivery does not claim document application", () => {
  const state = reduceRun(emptyRun(), { seq: 1, event: { type: "suggestion", turn_id: "t", payload: { ops: [] } } });
  assert.equal(state.proposals.length, 1);
  assert.equal(state.done, false);
  assert.equal(state.activity, "Changes ready for review");
});
test("history accepts server roles only and rejects malformed pagination", () => {
  const page = { thread: { id: "t", title: "Test", project_id: "p", design_id: "d" }, messages: [], older_cursor: null, latest_run: null };
  assert.equal(parseHistory(JSON.stringify(page)).thread.id, "t");
  assert.throws(() => parseHistory(JSON.stringify({ ...page, older_cursor: "5" })));
  assert.throws(() => parseHistory(JSON.stringify({ ...page, messages: [{ role: "system" }] })));
});
