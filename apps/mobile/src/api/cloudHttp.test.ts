import assert from "node:assert/strict";
import { describe, it, mock } from "node:test";
import { HttpCloudClient } from "./cloudHttp.js";
import { CompanionError } from "../native/types.js";

describe("HttpCloudClient", () => {
  it("normalizes base url", () => {
    const c = new HttpCloudClient("https://api.tokito.dev/");
    assert.equal(c.baseUrl(), "https://api.tokito.dev");
  });

  it("maps companion 404 to Unavailable", async () => {
    const original = globalThis.fetch;
    globalThis.fetch = mock.fn(async () =>
      new Response("", { status: 404 }),
    ) as typeof fetch;
    try {
      const c = new HttpCloudClient("https://example.test");
      await assert.rejects(
        () => c.listProjects("tok"),
        (err: unknown) =>
          err instanceof CompanionError && err.kind === "Unavailable",
      );
    } finally {
      globalThis.fetch = original;
    }
  });

  it("wraps network failures", async () => {
    const original = globalThis.fetch;
    globalThis.fetch = mock.fn(async () => {
      throw new TypeError("Failed to fetch");
    }) as typeof fetch;
    try {
      const c = new HttpCloudClient("https://example.test");
      await assert.rejects(
        () => c.me("tok"),
        (err: unknown) => err instanceof CompanionError && err.kind === "Network",
      );
    } finally {
      globalThis.fetch = original;
    }
  });
});
