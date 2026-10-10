import assert from "node:assert/strict";
import test from "node:test";
import { chatSendStep, type ChatSendStage } from "./sendDiagnostics";

test("send diagnostics identify exact failed boundary without private error data", async () => {
  const lines: string[] = [];
  const error = new SyntaxError("token=private user prompt and project identity");
  await assert.rejects(chatSendStep("startChat.receipt", async () => { throw error; }, line => lines.push(line)), candidate => candidate === error);
  assert.deepEqual(lines, ["[TokitoChatSend] stage=startChat.receipt status=begin", "[TokitoChatSend] stage=startChat.receipt status=failed category=parse"]);
});

test("successful durable queue boundary logs no receipt content", async () => {
  const lines: string[] = [];
  const result = { content: "private", token: "secret" };
  assert.equal(await chatSendStep("queued", async () => result, line => lines.push(line)), result);
  assert.deepEqual(lines, ["[TokitoChatSend] stage=queued status=begin", "[TokitoChatSend] stage=queued status=done"]);
});

test("arbitrary stages cannot inject sensitive data into logs", async () => {
  const lines: string[] = [];
  await assert.rejects(chatSendStep("private-token" as ChatSendStage, async () => 1, line => lines.push(line)));
  assert.deepEqual(lines, []);
});
