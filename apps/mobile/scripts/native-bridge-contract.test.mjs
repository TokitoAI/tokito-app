import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import path from "node:path";
import { fileURLToPath } from "node:url";

const mobileDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const bridge = fs.readFileSync(
  path.join(
    mobileDir,
    "modules/tokito-companion-ffi/android/src/main/java/dev/tokito/companion/ffi/TokitoCompanionFfiModule.kt",
  ),
  "utf8",
);

test("serializes nested project records once before crossing the Expo JS boundary", () => {
  assert.match(bridge, /JSONObject\(mapOf\(/);
  assert.match(bridge, /\)\)\.toString\(\)/);
  for (const field of ["d.sheetCount", "line.qty", "a.bytes"]) {
    assert.match(bridge, new RegExp(`${field.replace(".", "\\.")}\\.toDouble\\(\\)`));
    assert.doesNotMatch(bridge, new RegExp(`to ${field.replace(".", "\\.")},`));
  }
});
