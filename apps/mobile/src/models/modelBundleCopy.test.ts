import assert from "node:assert/strict";
import test from "node:test";
import { modelBundleErrorMessage } from "./modelBundleCopy";

test("model error copy explains recovery without exposing native paths", () => {
  const result = modelBundleErrorMessage(new Error("java.lang.UnsatisfiedLinkError: dlopen failed /data/user/0/private/lib.so cannot locate symbol OrtGetApiBase"));
  assert.match(result, /Update Tokito/);
  assert.doesNotMatch(result, /\/data|OrtGetApiBase|lib\.so/);
});

test("model rejection and rollback copy do not invent missing or successful state", () => {
  assert.match(modelBundleErrorMessage(new Error("model license is not admitted")), /license is not allowed/);
  assert.match(modelBundleErrorMessage(new Error("model artifact digest mismatch: private.bin")), /failed verification/);
  assert.match(modelBundleErrorMessage(new Error("verified rollback is unavailable")), /No verified previous/);
  const unknown = modelBundleErrorMessage({ message: "private /data/raw" });
  assert.match(unknown, /could not finish/);
  assert.doesNotMatch(unknown, /unchanged|installed|private|\/data/);
});
