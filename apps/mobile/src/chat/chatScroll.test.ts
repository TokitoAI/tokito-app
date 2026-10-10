import assert from "node:assert/strict";
import test from "node:test";
import { ChatScrollIntent } from "./chatScroll";

test("history layout cannot disable latest-on-open before scrolling finishes", () => {
  const intent = new ChatScrollIntent();
  intent.scroll(2000, 500, 0);
  assert.equal(intent.following, true);
  intent.drag(); intent.scroll(2000, 500, 100);
  assert.equal(intent.following, false);
  intent.open(); intent.scroll(3000, 500, 0);
  assert.equal(intent.following, true);
});
test("streaming respects a reader and earlier-message pagination", () => {
  const intent = new ChatScrollIntent();
  intent.earlier(); intent.scroll(4000, 500, 1200);
  assert.equal(intent.following, false);
  intent.drag(); intent.scroll(4000, 500, 3480);
  assert.equal(intent.following, true);
  intent.latest(); assert.equal(intent.following, true);
});
test("layout growth and programmatic scroll cannot interrupt following after a drag", () => {
  const intent = new ChatScrollIntent();
  intent.drag(); intent.scroll(2000, 500, 1500); intent.end();
  intent.scroll(4000, 500, 1500);
  assert.equal(intent.following, true);
});
