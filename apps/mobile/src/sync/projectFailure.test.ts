import assert from "node:assert/strict";
import test from "node:test";
import { projectFailure } from "./projectFailure";

test("project failures use structured codes without exposing bridge internals", () => {
  assert.match(projectFailure({ code: "Unavailable", message: "" }), /aren't available/);
  assert.match(projectFailure({ kind: "Unauthorized" }), /Sign in again/);
  assert.match(projectFailure({ code: "Network", message: "secret internal address" }), /Check your connection/);
  const text = projectFailure(new Error("Call to TokitoCompanionFfi.getProject rejected with secret"));
  assert.doesNotMatch(text, /TokitoCompanionFfi|secret|rejected/);
  assert.match(text, /Try again/);
});
