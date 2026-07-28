import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { HttpCloudClient } from "./cloudHttp.js";

describe("HttpCloudClient", () => {
  it("normalizes base url", () => {
    const c = new HttpCloudClient("https://api.tokito.dev/");
    assert.equal(c.baseUrl(), "https://api.tokito.dev");
  });
});
