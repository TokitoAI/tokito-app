import assert from "node:assert/strict";
import test from "node:test";
import { belongsToDisplayedRun, chatHistoryProjection, emptyRun, parseHistory, reduceRun, type ChatRow } from "./conversationModel";

test("a settled run keeps its live answer until refreshed history actually arrives", () => {
  const rows: ChatRow[] = [
    { id: "u", role: "user", content: "Check R1", runId: "r" },
    { id: "a", role: "assistant", content: "Checking parts.", runId: "r" },
  ];
  const run = { run_id: "r", thread_id: "t", project_id: "p", design_id: "d", status: "completed" };
  assert.equal(chatHistoryProjection(rows, run, null).hasSavedAnswer, false);
  assert.deepEqual(chatHistoryProjection(rows, run, null).rows.map(row => row.id), ["u"]);
  assert.equal(chatHistoryProjection(rows, run, "r").hasSavedAnswer, true);
  assert.deepEqual(chatHistoryProjection(rows, run, "r").rows, rows);
  assert.deepEqual(chatHistoryProjection(rows, null, null).rows, rows);
});

test("late previous-run events cannot populate a new user turn", () => {
  assert.equal(belongsToDisplayedRun("old", "new"), false);
  assert.equal(belongsToDisplayedRun("old", null), false);
  assert.equal(belongsToDisplayedRun(undefined, "new"), false);
  assert.equal(belongsToDisplayedRun("new", "new"), true);
});
test("tool-separated response rounds preserve prefaces without duplicating deltas", () => {
  let state = reduceRun(emptyRun(), { seq: 1, event: { type: "assistant_text_delta", token: "Checking" } });
  state = reduceRun(state, { seq: 2, event: { type: "assistant_text_done", body: "Checking parts." } });
  state = reduceRun(state, { seq: 3, event: { type: "tool_call" } });
  state = reduceRun(state, { seq: 4, event: { type: "assistant_text_delta", token: "R1 is " } });
  assert.equal(state.text, "Checking parts.\n\nR1 is ");
  state = reduceRun(state, { seq: 5, event: { type: "assistant_text_done", body: "R1 is 2.2kΩ." } });
  assert.equal(state.text, "Checking parts.\n\nR1 is 2.2kΩ.");
  assert.equal(state.draftText, "");
});

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
