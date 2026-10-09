import { test } from "node:test";
import assert from "node:assert/strict";
import { CompanionError } from "../native/types";
import { retryableStartup, startupRetryDelay } from "./startupRetry";

test("startup retries only transient errors with bounded backoff", () => {
  for (const error of [CompanionError.network("offline"), CompanionError.unavailable(), CompanionError.rateLimited()]) assert.equal(retryableStartup(error), true);
  for (const error of [CompanionError.unauthorized(), CompanionError.unexpected(), CompanionError.nativeCompatibility(), new Error("unknown")]) assert.equal(retryableStartup(error), false);
  assert.deepEqual([0, 1, 2, 3, 4, 50].map(startupRetryDelay), [3000, 6000, 12000, 24000, 30000, 30000]);
});
