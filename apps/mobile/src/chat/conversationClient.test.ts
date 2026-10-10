import assert from "node:assert/strict";
import test from "node:test";
import type { TokitoCompanionFfiNative } from "../../modules/tokito-companion-ffi/src";
import { ConversationClient } from "./conversationClient";
import { chatFailure, ConversationScope } from "./conversationState";

test("explicit sheet context crosses the native boundary unchanged", async () => {
  let args: unknown[] = [];
  const native = { sendConversationMessage: async (...values: unknown[]) => { args = values; return "{}"; } } as unknown as TokitoCompanionFfiNative;
  await new ConversationClient(native).send("p", "d", "t", "Move R1", 7, "schematic", "radio");
  assert.deepEqual(args.slice(1), ["p", "d", "t", "Move R1", "7", "schematic", "radio"]);
});

test("ordinary chat uses the planner for both empty and existing schematics", async () => {
  const calls: unknown[][] = [];
  const native = { sendConversationMessage: async (...values: unknown[]) => { calls.push(values); return "{}"; } } as unknown as TokitoCompanionFfiNative;
  const client = new ConversationClient(native);
  await client.sendChat("p", "d", "t", "Explain LED current", 0, null);
  await client.sendChat("p", "d", "t", "Explain LED current", 9, "power");
  assert.deepEqual(calls.map(values => values.slice(1)), [
    ["p", "d", "t", "Explain LED current", "0", "planner", null],
    ["p", "d", "t", "Explain LED current", "9", "planner", "power"],
  ]);
});

test("chat distinguishes missing deployment from connectivity and authentication", () => {
  assert.match(chatFailure(new Error("this sign-in method is not available right now")), /unavailable on the server/);
  assert.match(chatFailure(new Error("session expired")), /Sign in again/);
  assert.match(chatFailure(new Error("network error: connection refused")), /check your connection/);
  assert.doesNotMatch(chatFailure(new Error("unexpected server response")), /check your connection/);
  assert.match(chatFailure({ code: "Unavailable", message: "" }), /unavailable on the server/);
  assert.match(chatFailure({ code: "Unauthorized", message: "" }), /Sign in again/);
});

test("navigation invalidates old callbacks even after returning to the same thread", () => {
  const scope = new ConversationScope();
  const a = scope.next(); scope.save("a", "Draft A");
  scope.next(); scope.save("b", "Draft B");
  scope.next();
  assert.equal(scope.current(a), false);
  assert.equal(scope.draft("a"), "Draft A");
  assert.equal(scope.draft("b"), "Draft B");
  scope.reset();
  assert.equal(scope.draft("a"), "");
});

test("a session finishing opening after sign-out is shut down again", async () => {
  let finish!: (id: string) => void;
  let closed = 0;
  let removed = 0;
  const native = {
    addListener: () => ({ remove: () => { removed++; } }),
    openConversations: () => new Promise<string>(resolve => { finish = resolve; }),
    closeConversations: async () => { closed++; },
  } as unknown as TokitoCompanionFfiNative;
  const client = new ConversationClient(native);
  const opening = client.open("https://api.example.test", "token", () => {}, "workspace-test");
  await client.close();
  finish(client.id);
  await opening;
  assert.equal(closed, 2);
  assert.equal(removed, 1);
});

test("foreign-project history cannot populate the selected conversation", async () => {
  const native = {
    conversationHistory: async () => JSON.stringify({ thread: { id: "t", project_id: "other", design_id: "d", title: "wrong" }, messages: [], older_cursor: null, latest_run: null }),
  } as unknown as TokitoCompanionFfiNative;
  await assert.rejects(new ConversationClient(native).history("p", "d", "t"), /different design/);
});

test("queued messages are recovered with identity and cannot cross threads", async () => {
  const native = {
    queuedConversationMessage: async () => JSON.stringify({ message: { thread_id: "t", request: { message_id: "m", run_id: "r", content: "Build the sensor" } }, blocked: false }),
    recoveredConversationDraft: async () => "Preserved request",
    restoreBlockedMessage: async () => "Preserved request",
  } as unknown as TokitoCompanionFfiNative;
  const client = new ConversationClient(native);
  assert.deepEqual(await client.queued("t"), { id: "m", runId: "r", content: "Build the sensor", blocked: false });
  await assert.rejects(client.queued("foreign"), /Invalid queued/);
  assert.equal(await client.restoreBlocked("m"), "Preserved request");
  assert.equal(await client.recoveredDraft("t"), "Preserved request");
});

test("late watch startup is stopped and its events cannot reach a newer conversation", async () => {
  let listener!: (event: { sessionId: string; watchId: string; payloadJson: string }) => void;
  let finishOld!: () => void;
  const watches: string[] = [];
  const stopped: string[] = [];
  const received: unknown[] = [];
  const native = {
    addListener: (_name: string, callback: typeof listener) => { listener = callback; return { remove: () => {} }; },
    openConversations: async () => "ok",
    watchConversationRun: async (_session: string, run: string, _after: string, watch: string) => {
      watches.push(watch);
      if (run === "old") await new Promise<void>(resolve => { finishOld = resolve; });
    },
    stopConversationRunWatch: async (id: string) => { stopped.push(id); },
    closeConversations: async () => {},
  } as unknown as TokitoCompanionFfiNative;
  const client = new ConversationClient(native);
  await client.open("https://api.example.test", "token", change => received.push(change), "workspace-test");
  const old = client.watch("old");
  await client.watch("new");
  listener({ sessionId: client.id, watchId: watches[0], payloadJson: '{"type":"event"}' });
  listener({ sessionId: client.id, watchId: watches[1], payloadJson: '{"type":"settled"}' });
  finishOld(); await old;
  assert.deepEqual(received, [{ type: "settled" }]);
  assert.equal(stopped.filter(id => id === watches[0]).length, 2);
  await client.close();
});
