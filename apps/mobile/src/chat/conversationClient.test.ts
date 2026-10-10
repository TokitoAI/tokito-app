import assert from "node:assert/strict";
import test from "node:test";
import type { TokitoCompanionFfiNative } from "../../modules/tokito-companion-ffi/src";
import { ConversationClient, conversationTitle } from "./conversationClient";
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

test("atomic first message retains command identity after an uncertain bridge response", async () => {
  const command = "85e67cda-1ca0-4848-9d43-c2509de069b5";
  const scope = new ConversationScope(); let allocations = 0; let attempts = 0; const calls: unknown[][] = [];
  const receipt = { thread: { id: "t", title: "First", project_id: "p", design_id: "d" }, message: { thread_id: "t", project_id: "p", design_id: "d", request: { message_id: "m", run_id: "r", content: "First", base_schematic_version: 4, kind: "planner", active_sheet_id: null } } };
  const native = { startConversation: async (...args: unknown[]) => {
    calls.push(args); if (++attempts === 1) throw new Error("bridge response interrupted after commit");
    return JSON.stringify(receipt);
  } } as unknown as TokitoCompanionFfiNative;
  const client = new ConversationClient(native);
  const allocate = () => { allocations++; return command; };
  const key = JSON.stringify(["p", "d", "Draft", "First", 4, null]);
  const first = scope.firstMessageCommand(key, allocate, 4);
  await assert.rejects(client.startChat(first, "p", "d", "First", 4, null));
  scope.next(); // Navigation does not turn an uncertain command into a new send.
  const retry = scope.firstMessageCommand(key, allocate, 5);
  assert.equal(first, retry); assert.equal(allocations, 1);
  assert.equal(scope.firstMessageVersion(retry), 4, "background revision updates must not change an uncertain command");
  assert.equal((await client.startChat(retry, "p", "d", "First", 4, null)).message.request !== undefined, true);
  assert.deepEqual(calls[0], calls[1]); assert.equal(calls[0][1], command);
  assert.equal(scope.finishFirstMessage(command), true);
  const next = scope.firstMessageCommand(key, () => "next-command"); assert.equal(next, "next-command");
  assert.equal(scope.finishFirstMessage(command), false);
});

test("atomic receipts reject foreign context and changed content without consuming the draft", async () => {
  const message = { thread_id: "t", project_id: "p", design_id: "d", request: { message_id: "m", run_id: "r", content: "First", base_schematic_version: 4, kind: "planner", active_sheet_id: null } };
  for (const replacement of [{ ...message, project_id: "foreign" }, { ...message, request: { ...message.request, content: "other" } }, { ...message, request: { ...message.request, active_sheet_id: "other" } }]) {
    const native = { startConversation: async () => JSON.stringify({ thread: { id: "t", title: "First", project_id: "p", design_id: "d" }, message: replacement }) } as unknown as TokitoCompanionFfiNative;
    await assert.rejects(new ConversationClient(native).startChat("command", "p", "d", "First", 4, null), /does not match/);
  }
  const scope = new ConversationScope(); scope.save(null, "First");
  scope.firstMessageCommand("old", () => "old-command"); scope.firstMessageCommand("new", () => "new-command");
  assert.equal(scope.finishFirstMessage("old-command"), false); assert.equal(scope.draft(null), "First");
  scope.reset(); assert.equal(scope.firstMessageCommand("new", () => "after-account-change"), "after-account-change");
});

test("conversation titles fit native UTF-8 limits without splitting emoji", () => {
  const title = conversationTitle("😀".repeat(100));
  assert.equal(Array.from(title).length, 60); assert.equal(Buffer.byteLength(title, "utf8"), 240);
  assert.equal(conversationTitle("Short title"), "Short title");
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
