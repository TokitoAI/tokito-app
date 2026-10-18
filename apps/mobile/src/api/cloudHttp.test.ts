import assert from "node:assert/strict";
import { describe, it, mock } from "node:test";
import { HttpCloudClient } from "./cloudHttp";
import { CompanionError } from "../native/types";

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

  async function failWith(
    status: number,
    body: unknown,
    headers: Record<string, string> = {},
    call: (c: HttpCloudClient) => Promise<unknown> = (c) => c.otpVerify("a@b.c", "1234 5678"),
  ): Promise<CompanionError> {
    const original = globalThis.fetch;
    globalThis.fetch = mock.fn(async () =>
      new Response(JSON.stringify(body), { status, headers }),
    ) as typeof fetch;
    try {
      const c = new HttpCloudClient("https://example.test");
      let caught: unknown;
      try {
        await call(c);
      } catch (e) {
        caught = e;
      }
      assert.ok(caught instanceof CompanionError);
      return caught;
    } finally {
      globalThis.fetch = original;
    }
  }

  it("verify: plain wrong code keeps the existing message", async () => {
    const e = await failWith(401, { status: "invalid_code" });
    assert.equal(e.kind, "InvalidCode");
    assert.equal(e.message, "that code is invalid or expired");
  });

  it("verify: burned code", async () => {
    const e = await failWith(401, { status: "invalid_code", reason: "too_many_attempts" });
    assert.equal(e.kind, "CodeBurned");
    assert.equal(e.message, "Too many attempts on that code. Request a new code.");
  });

  it("verify: per-IP 429 uses body retry_after_secs", async () => {
    const e = await failWith(429, { status: "rate_limited", retry_after_secs: 300 });
    assert.equal(e.kind, "NetworkRateLimited");
    assert.equal(e.message, "Too many attempts from this network. Try again in 5 minutes.");
  });

  it("verify: falls back to Retry-After header", async () => {
    const e = await failWith(429, { status: "rate_limited" }, { "Retry-After": "120" });
    assert.equal(e.message, "Too many attempts from this network. Try again in 2 minutes.");
  });

  it("verify: no delay anywhere says try again later", async () => {
    const e = await failWith(429, { status: "rate_limited" });
    assert.equal(e.message, "Too many attempts from this network. Try again later.");
  });

  it("daily cap on verify and request", async () => {
    const body = { status: "rate_limited", reason: "too_many_failed_attempts", retry_after_secs: 7200 };
    const want =
      "Too many failed attempts for this email. Sign in with Google or GitHub, or try again in 2 hours.";
    assert.equal((await failWith(429, body)).message, want);
    const req = await failWith(429, body, {}, (c) => c.otpRequest("a@b.c"));
    assert.equal(req.kind, "DailyCapReached");
    assert.equal(req.message, want);
  });

  it("request: cooldown and hourly", async () => {
    const call = (c: HttpCloudClient) => c.otpRequest("a@b.c");
    const cd = await failWith(429, { status: "cooldown", retry_after_secs: 42 }, {}, call);
    assert.equal(cd.kind, "Cooldown");
    assert.equal(cd.message, "Please wait 42 seconds before requesting another code.");
    const hr = await failWith(429, { status: "rate_limited", retry_after_secs: 1800 }, {}, call);
    assert.equal(hr.kind, "RateLimited");
    assert.equal(hr.message, "Too many code requests. Try again in 30 minutes.");
  });

  it("401 (expired session) and 403 (no access) are distinct", async () => {
    const call = (c: HttpCloudClient) => c.me("tok");
    const unauth = await failWith(401, { status: "unauthorized" }, {}, call);
    assert.equal(unauth.kind, "Unauthorized");
    assert.equal(unauth.message, "session expired");
    const forbidden = await failWith(403, { status: "not_a_member" }, {}, call);
    assert.equal(forbidden.kind, "Forbidden");
    assert.equal(
      forbidden.message,
      "You don't have access to this. Ask an admin of the organization.",
    );
  });

  it("unknown plan strings decode and pass through verbatim", async () => {
    const original = globalThis.fetch;
    globalThis.fetch = mock.fn(async () =>
      new Response(JSON.stringify({ token: "t", email: "a@b.c", plan: "team_2027" }), {
        status: 200,
      }),
    ) as typeof fetch;
    try {
      const c = new HttpCloudClient("https://example.test");
      const session = await c.otpVerify("a@b.c", "12345678");
      assert.equal(session.plan, "team_2027");
      assert.equal((await c.me("t")).plan, "team_2027");
    } finally {
      globalThis.fetch = original;
    }
  });
});
