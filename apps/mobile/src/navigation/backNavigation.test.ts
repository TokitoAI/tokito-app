import { strict as assert } from "node:assert";
import { test } from "node:test";
import { createBackRegistry, parentProjectSection, parentRoute, parentSignedOutView, type Route } from "./backNavigation";

test("all nested routes return their parent and root allows Android exit", () => {
  const routes: Route[] = [{ name: "models" }, { name: "project", id: "p" }];
  for (const route of routes) assert.deepEqual(parentRoute(route), { name: "projects" });
  for (const route of [{ name: "svg", projectId: "p", artifact: "drawing" }, { name: "live_schematic", projectId: "p", designId: "d" }] as Route[])
    assert.deepEqual(parentRoute(route), { name: "project", id: "p" });
  assert.equal(parentRoute({ name: "projects" }), null);
  assert.equal(parentSignedOutView("sign_in"), "home");
  assert.equal(parentSignedOutView("models"), "home");
  assert.equal(parentSignedOutView("home"), null);
  assert.equal(parentProjectSection("chat"), "design");
  assert.equal(parentProjectSection("review"), "design");
  assert.equal(parentProjectSection("design"), null);
});

test("section priority precedes app route independent of registration order and cleans up", () => {
  let callback = () => false;
  let added = 0; let removed = 0;
  const subscribe = createBackRegistry({ addEventListener: (_, cb) => { callback = cb; added++; return { remove: () => { removed++; } }; } });
  let section = "chat"; let route = "project";
  const disposeSection = subscribe(() => { if (section === "design") return false; section = "design"; return true; }, 10);
  const disposeApp = subscribe(() => { if (route === "projects") return false; route = "projects"; return true; });
  assert.equal(added, 1);
  assert.equal(callback(), true); assert.equal(section, "design"); assert.equal(route, "project");
  assert.equal(callback(), true); assert.equal(route, "projects");
  assert.equal(callback(), false);
  disposeSection(); disposeSection(); assert.equal(removed, 0);
  disposeApp(); disposeApp(); assert.equal(removed, 1);
  assert.equal(callback(), false);
  const disposeAgain = subscribe(() => true); assert.equal(added, 2); disposeAgain(); assert.equal(removed, 2);
});

test("reentrant Back is consumed without duplicate navigation and retired callbacks never run", () => {
  let callback = () => false; let calls = 0;
  const subscribe = createBackRegistry({ addEventListener: (_, cb) => { callback = cb; return { remove() {} }; } });
  const dispose = subscribe(() => { calls++; assert.equal(callback(), true); return true; });
  assert.equal(callback(), true); assert.equal(calls, 1);
  dispose(); assert.equal(callback(), false); assert.equal(calls, 1);
});

test("dispatch skips subscriptions retired during a higher-priority handler", () => {
  let callback = () => false; let retiredCalls = 0;
  const subscribe = createBackRegistry({ addEventListener: (_, cb) => { callback = cb; return { remove() {} }; } });
  const retire = subscribe(() => { retiredCalls++; return true; });
  const dispose = subscribe(() => { retire(); return false; }, 10);
  assert.equal(callback(), false); assert.equal(retiredCalls, 0); dispose();
});
