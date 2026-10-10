import assert from "node:assert/strict";
import test from "node:test";
import { ConversationScope } from "./conversationState";

test("late edits from a retired native input cannot restore an already sent draft", () => {
  const scope = new ConversationScope();
  scope.saveInput("thread-a", "thread-a", "Explain a capacitor", 1, 1);
  scope.save("thread-a", "");
  assert.equal(scope.saveInput("thread-a", "thread-a", "Explain a", 1, 2), false);
  assert.equal(scope.draft("thread-a"), "");
  assert.equal(scope.saveInput("thread-a", "thread-a", "Next question", 2, 2), true);
  assert.equal(scope.draft("thread-a"), "Next question");
});

test("input events keep their original conversation identity across navigation", () => {
  const scope = new ConversationScope();
  scope.save(null, "Unsent new conversation");
  assert.equal(scope.saveInput("thread-a", null, "Already sent prefix", 1, 1), false);
  assert.equal(scope.draft(null), "Unsent new conversation");
  assert.equal(scope.draft("thread-a"), "");
});

test("typing during asynchronous hydration must retain the New chat draft", () => {
  const scope = new ConversationScope();
  assert.equal(scope.saveInput(null, null, "New question while loading", 0, 0), true);
  // ProjectChat only auto-selects existing history while this draft is empty.
  assert.notEqual(scope.draft(null), "");
  assert.equal(scope.saveInput(null, "hydrated-thread", "A late prefix", 0, 0), false);
  assert.equal(scope.draft("hydrated-thread"), "");
});
