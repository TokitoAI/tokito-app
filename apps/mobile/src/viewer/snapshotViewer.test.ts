import assert from "node:assert/strict";
import test from "node:test";
import vm from "node:vm";
import { schematicSnapshots, snapshotHtml, SNAPSHOT_RUNTIME } from "./snapshotRuntime";
import type { ProjectDetail } from "../api/cloudHttp";

test("snapshots never guess between designs or accept another format", () => {
  const project: ProjectDetail = { id: "p", name: "P", slug: "p", updated_at: "", designs: [{ id: "a", name: "A", sheet_count: 1, bom_lines: [] }, { id: "b", name: "B", sheet_count: 1, bom_lines: [] }], artifact_manifest: [{ name: "main.svg", content_type: "image/svg+xml", sha256: "", bytes: 20 }] };
  assert.deepEqual(schematicSnapshots(project, "a"), []);
  project.artifact_manifest.push({ name: "designs/a/root.svg", content_type: "image/svg+xml", sha256: "", bytes: 20 });
  assert.equal(schematicSnapshots(project, "a")[0].name, "designs/a/root.svg");
  assert.equal(schematicSnapshots(project, "b").length, 0);
  project.designs.splice(1);
  project.artifact_manifest.splice(1);
  assert.equal(schematicSnapshots(project, "a").length, 1);
  project.artifact_manifest[0].content_type = "text/html";
  assert.equal(schematicSnapshots(project, "a").length, 0);
});

test("untrusted snapshot stays encoded in inert image and cannot escape script", () => {
  const html = snapshotHtml('<svg><script>bad()</script><foreignObject><iframe src="https://evil.invalid"/></foreignObject></svg>');
  assert.equal((html.match(/<script>/g) ?? []).length, 1);
  assert.equal((html.match(/<\/script>/g) ?? []).length, 1);
  assert.ok(!html.includes("<iframe"));
  assert.ok(html.includes("connect-src 'none'"));
  assert.ok(html.includes("frame-src 'none'"));
  assert.throws(() => snapshotHtml("not svg"));
  assert.throws(() => snapshotHtml("<svg>" + "x".repeat(8 * 1024 * 1024)));
});

function harness(width: number, height: number) {
  type Handler = (event: Record<string, unknown>) => void;
  const handlers = new Map<string, Handler>();
  const messages: { type: string; percent: number }[] = [];
  const surface = { clientWidth: width, clientHeight: height, addEventListener: (name: string, handler: Handler) => handlers.set(name, handler), getBoundingClientRect: () => ({ left: 0, top: 0 }), setPointerCapture: () => {} };
  const drawing = { naturalWidth: 1600, naturalHeight: 1000, style: { transform: "" }, onload: () => {} };
  const context = vm.createContext({ document: { getElementById: (name: string) => name === "surface" ? surface : drawing, addEventListener: () => {} }, window: { addEventListener: (name: string, handler: Handler) => handlers.set(name, handler), ReactNativeWebView: { postMessage: (message: string) => messages.push(JSON.parse(message)) } }, ResizeObserver: class { observe() {} } });
  vm.runInContext(SNAPSHOT_RUNTIME, context);
  drawing.onload();
  return { drawing, messages, event: (name: string, event: Record<string, unknown>) => handlers.get(name)?.(event), read: (expression: string): number => vm.runInContext(expression, context) as number };
}

test("fit respects actual portrait and landscape viewport aspect", () => {
  for (const [width, height] of [[400, 800], [1200, 500]]) {
    const h = harness(width, height);
    assert.ok(h.read("scale*drawing.naturalWidth") <= width);
    assert.ok(h.read("scale*drawing.naturalHeight") <= height);
    assert.equal(h.messages.at(-1)?.type, "ready");
    assert.equal(h.messages.at(-1)?.percent, 100);
  }
});

test("pinch uses fixed screen-space baseline; no inverse jumps on repeated move", () => {
  const h = harness(400, 800);
  h.event("pointerdown", { pointerId: 1, clientX: 100, clientY: 400 });
  h.event("pointerdown", { pointerId: 2, clientX: 200, clientY: 400 });
  h.event("pointermove", { pointerId: 2, clientX: 300, clientY: 400 });
  assert.equal(h.messages.at(-1)?.percent, 200);
  h.event("pointermove", { pointerId: 2, clientX: 300, clientY: 400 });
  assert.equal(h.messages.at(-1)?.percent, 200);
  h.event("pointercancel", { pointerId: 2 });
  const before = h.read("x");
  h.event("pointermove", { pointerId: 1, clientX: 110, clientY: 400 });
  assert.equal(h.read("x") - before, 10);
  h.event("message", { data: JSON.stringify({ action: "fit" }) });
  assert.equal(h.messages.at(-1)?.percent, 100);
});

test("zoom limits prevent losing the page or unbounded scale", () => {
  const h = harness(400, 800);
  for (let i = 0; i < 40; i++) h.event("message", { data: '{"action":"in"}' });
  assert.equal(h.messages.at(-1)?.percent, 1600);
  for (let i = 0; i < 40; i++) h.event("message", { data: '{"action":"out"}' });
  assert.equal(h.messages.at(-1)?.percent, 100);
  assert.ok(!h.drawing.style.transform.includes("NaN"));
});

test("a fresh revision preserves zoom and viewing center without reloading the viewer", () => {
  const h = harness(400, 800);
  h.event("message", { data: '{"action":"in"}' });
  h.event("message", { data: '{"action":"in"}' });
  const ratio = h.read("scale/fitScale");
  const center = h.read("(200-x)/(drawing.naturalWidth*scale)");
  h.event("message", { data: JSON.stringify({ action: "replace", source: "data:image/svg+xml;charset=utf-8,%3Csvg%2F%3E" }) });
  h.drawing.naturalWidth = 1800;
  h.drawing.onload();
  assert.equal(h.read("scale/fitScale"), ratio);
  assert.ok(Math.abs(h.read("(200-x)/(drawing.naturalWidth*scale)") - center) < 0.00001);
  h.event("message", { data: JSON.stringify({ action: "replace", source: "https://evil.invalid" }) });
  assert.equal(h.read("ready"), true);
});
