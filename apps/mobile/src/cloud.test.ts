import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { DEFAULT_CLOUD_BASE, normalizeBaseUrl } from "./cloud";

describe("normalizeBaseUrl", () => {
  it("defaults empty to production", () => {
    assert.equal(normalizeBaseUrl(""), DEFAULT_CLOUD_BASE);
    assert.equal(normalizeBaseUrl("   "), DEFAULT_CLOUD_BASE);
  });

  it("strips trailing slashes", () => {
    assert.equal(normalizeBaseUrl("https://api.tokito.dev/"), "https://api.tokito.dev");
    assert.equal(
      normalizeBaseUrl("https://staging.example/v1///"),
      "https://staging.example/v1",
    );
  });
});
