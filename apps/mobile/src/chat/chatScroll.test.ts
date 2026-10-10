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
test("async history waits for both measurements, in either callback order", () => {
  const intent = new ChatScrollIntent();
  intent.content(2500); assert.equal(intent.target(), null);
  intent.layout(700); assert.equal(intent.target(), 1800);
  intent.content(3100); assert.equal(intent.target(), 2400);
  intent.open(); assert.equal(intent.target(), null);
  intent.content(4200); assert.equal(intent.target(), 3500);
  const other = new ChatScrollIntent();
  other.layout(700); assert.equal(other.target(), null);
  other.content(3100); assert.equal(other.target(), 2400);
});
test("measured footer-only output follows and pagination does not", () => {
  const intent = new ChatScrollIntent(); intent.layout(700); intent.content(1500);
  assert.equal(intent.target(), 800);
  intent.earlier(); intent.content(2500);
  assert.equal(intent.target(), null);
  intent.latest(); assert.equal(intent.target(), 1800);
});
