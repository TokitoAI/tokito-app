import assert from "node:assert/strict";
import test from "node:test";
import type { TokitoCompanionFfiNative } from "../../modules/tokito-companion-ffi/src";
import { ConversationClient } from "./conversationClient";

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
  const opening = client.open("https://api.example.test", "token", () => {});
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
