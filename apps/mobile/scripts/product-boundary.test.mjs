import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

async function sourceFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = await Promise.all(entries.map((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? sourceFiles(path) : [path];
  }));
  return files.flat().filter((path) => path.endsWith(".ts") || path.endsWith(".tsx"));
}

test("mobile UI cannot regress into a CAD or workspace-management client", async () => {
  const files = await sourceFiles(fileURLToPath(new URL("../src", import.meta.url)));
  const uiFiles = files.filter((path) => !path.endsWith("RealtimeWorkspace.tsx"));
  const source = (await Promise.all(uiFiles.map((path) => readFile(path, "utf8")))).join("\n");

  for (const resource of ["project", "design", "schematic", "bom"]) {
    assert.doesNotMatch(source, new RegExp(`realtime\\.update\\([^)]*[\"']${resource}[\"']`, "s"));
  }
  assert.doesNotMatch(source, /LiveSchematicEditor/);
  assert.doesNotMatch(source, /Touch-friendly tools|Edit canonical schematics/);
});

test("realtime authoring is explicitly allowlisted", async () => {
  const source = await readFile(new URL("../src/sync/RealtimeWorkspace.tsx", import.meta.url), "utf8");
  assert.match(source, /"chat" \| "suggestions"/);
  assert.match(source, /resource !== "chat" && resource !== "suggestions"/);
});
