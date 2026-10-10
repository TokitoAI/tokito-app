import assert from "node:assert/strict";
import test from "node:test";
import { chatCardLayout } from "./chatLayout";

test("assistant fills available width without forcing short user bubbles wide", () => {
  assert.equal(chatCardLayout.assistant.alignSelf, "stretch");
  assert.equal(chatCardLayout.assistant.maxWidth, "100%");
  assert.equal(chatCardLayout.user.alignSelf, "flex-end");
  assert.equal(chatCardLayout.user.maxWidth, "85%");
  assert.equal("width" in chatCardLayout.assistant, false);
  assert.equal("width" in chatCardLayout.user, false);
});
test("Markdown height is natural and list text only flexes horizontally", () => {
  assert.equal(chatCardLayout.document.flexShrink, 0);
  assert.equal(chatCardLayout.document.alignSelf, "stretch");
  assert.equal("height" in chatCardLayout.document, false);
  assert.equal("maxHeight" in chatCardLayout.document, false);
  assert.equal(chatCardLayout.listText.flexBasis, 0);
  assert.equal(chatCardLayout.listText.minWidth, 0);
});
