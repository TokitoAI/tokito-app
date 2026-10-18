import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { formatDuration, planLabel, retryClause } from "./errorCopy";
import { mapNativeError, retrySecsFromMessage } from "./nativeErrors";
import { CompanionError } from "./types";

describe("formatDuration", () => {
  it("rounds up and pluralises", () => {
    assert.equal(formatDuration(0), "1 second");
    assert.equal(formatDuration(1), "1 second");
    assert.equal(formatDuration(59), "59 seconds");
    assert.equal(formatDuration(60), "1 minute");
    assert.equal(formatDuration(61), "2 minutes");
    assert.equal(formatDuration(3599), "1 hour");
    assert.equal(formatDuration(3600), "1 hour");
    assert.equal(formatDuration(5400), "1 hour 30 minutes");
    assert.equal(formatDuration(86400), "24 hours");
  });
  it("retryClause handles unknown delay", () => {
    assert.equal(retryClause(undefined), "later");
    assert.equal(retryClause(300), "in 5 minutes");
  });
});

describe("planLabel", () => {
  it("shows unknown plans verbatim and blank as generic", () => {
    assert.equal(planLabel("free"), "free");
    assert.equal(planLabel("team_enterprise_2027"), "team_enterprise_2027");
    assert.equal(planLabel("  "), "Unknown plan");
  });
});

describe("CompanionError copy", () => {
  it("every rate-limit message says what to do next", () => {
    assert.equal(
      CompanionError.codeBurned().message,
      "Too many attempts on that code. Request a new code.",
    );
    assert.equal(
      CompanionError.networkRateLimited(120).message,
      "Too many attempts from this network. Try again in 2 minutes.",
    );
    assert.equal(
      CompanionError.dailyCapReached(7200).message,
      "Too many failed attempts for this email. Sign in with Google or GitHub, or try again in 2 hours.",
    );
    assert.equal(
      CompanionError.cooldown(30).message,
      "Please wait 30 seconds before requesting another code.",
    );
    assert.equal(
      CompanionError.rateLimited(3600).message,
      "Too many code requests. Try again in 1 hour.",
    );
    assert.equal(
      CompanionError.forbidden().message,
      "You don't have access to this. Ask an admin of the organization.",
    );
  });
});

describe("mapNativeError", () => {
  it("maps new native codes and parses the delay", () => {
    const e = mapNativeError({ code: "NetworkRateLimitedException", message: "retryAfterSecs=90" });
    assert.equal(e.kind, "NetworkRateLimited");
    assert.match(e.message, /2 minutes/);
    assert.equal(mapNativeError({ code: "CodeBurned", message: "x" }).kind, "CodeBurned");
    assert.equal(mapNativeError({ code: "Forbidden", message: "x" }).kind, "Forbidden");
    assert.equal(mapNativeError({ code: "Unauthorized", message: "x" }).kind, "Unauthorized");
    assert.equal(mapNativeError({ code: "DailyCapReached", message: "retryAfterSecs=null" }).message.endsWith("try again later."), true);
  });
  it("retrySecsFromMessage", () => {
    assert.equal(retrySecsFromMessage("retryAfterSecs=12"), 12);
    assert.equal(retrySecsFromMessage("retryAfterSecs=null"), undefined);
  });
});
