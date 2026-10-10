import assert from "node:assert/strict";
import test from "node:test";
import { ChatFollowFrame, ChatScrollIntent } from "./chatScroll";

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

test("footer-to-saved row reflow coalesces following using final committed dimensions", () => {
  const intent = new ChatScrollIntent(); intent.layout(700); intent.content(2400);
  const frames: (() => void)[] = []; const offsets: number[] = [];
  const follow = new ChatFollowFrame(intent, callback => frames.push(callback), () => {}, offset => offsets.push(offset));
  follow.request();
  // Footer disappears before the virtualized saved Markdown row finishes measuring.
  intent.content(1800); follow.request();
  intent.content(3200); follow.request();
  assert.equal(frames.length, 1); assert.deepEqual(offsets, []);
  frames.shift()!(); assert.deepEqual(offsets, [2500]);
  // Same-size content replacement still has a row-layout request.
  follow.request(); frames.shift()!(); assert.deepEqual(offsets, [2500, 2500]);
});

test("a native clamp is corrected without stealing a reader drag", () => {
  const intent = new ChatScrollIntent(); intent.layout(700); intent.content(2000);
  intent.scroll(3200, 700, 1300);
  assert.equal(intent.target(), 2500); assert.equal(intent.needsFollow(1300), true);
  intent.scroll(3200, 700, 2500); assert.equal(intent.needsFollow(2500), false);
  const frames: (() => void)[] = []; const offsets: number[] = [];
  const follow = new ChatFollowFrame(intent, callback => frames.push(callback), () => {}, offset => offsets.push(offset));
  follow.request(); intent.drag(); frames.shift()!(); assert.deepEqual(offsets, []);
  intent.scroll(3200, 700, 1000); intent.end(); follow.request();
  frames.shift()!(); assert.deepEqual(offsets, []);
  intent.latest(); follow.request(); frames.shift()!(); assert.deepEqual(offsets, [2500]);
});

test("unmounted list cancels queued follow work", () => {
  const intent = new ChatScrollIntent(); intent.layout(700); intent.content(2000);
  const cancelled: number[] = [];
  const follow = new ChatFollowFrame(intent, () => 42, frame => cancelled.push(frame), () => assert.fail("Disposed list scrolled"));
  follow.request(); follow.cancel(); assert.deepEqual(cancelled, [42]);
});
