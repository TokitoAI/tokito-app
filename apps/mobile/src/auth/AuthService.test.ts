import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { AuthService } from "./AuthService";
import { MemorySessionStore } from "./sessionStore";
import type { Account, CompanionClientPort, Session } from "../native/types";
import { CompanionError } from "../native/types";

class FakeClient implements CompanionClientPort {
  online = true;
  forgotten: string | null = null;
  async resolveIdentity(token: string): Promise<{ account: Account; online: boolean }> {
    return { account: await this.me(token), online: this.online };
  }
  async forgetIdentity(token: string): Promise<void> { this.forgotten = token; }
  base = "https://api.tokito.dev";
  failMe = false;
  lastOtpEmail: string | null = null;

  baseUrl(): string {
    return this.base;
  }

  async otpRequest(email: string): Promise<void> {
    this.lastOtpEmail = email;
  }

  async otpVerify(email: string, code: string): Promise<Session> {
    void code;
    return { token: "jwt-1", email, plan: "free" };
  }

  async oauthExchange(code: string): Promise<Session> {
    void code;
    return { token: "jwt-oauth", email: "o@lab.dev", plan: "pro" };
  }

  async me(token: string): Promise<Account> {
    if (this.failMe) {
      throw CompanionError.unauthorized();
    }
    if (token !== "jwt-1" && token !== "jwt-oauth") {
      throw CompanionError.unauthorized();
    }
    return { email: "u@lab.dev", plan: "free" };
  }
}

describe("AuthService", () => {
  it("verifyOtp persists session", async () => {
    const store = new MemorySessionStore();
    const client = new FakeClient();
    const auth = new AuthService(client, store);
    await auth.requestOtp("  u@lab.dev ");
    assert.equal(client.lastOtpEmail, "u@lab.dev");
    const session = await auth.verifyOtp("u@lab.dev", "123456");
    assert.equal(session.token, "jwt-1");
    const saved = await store.getSession();
    assert.deepEqual(saved, session);
  });

  it("restore returns signed_in when me succeeds", async () => {
    const store = new MemorySessionStore();
    await store.setSession({ token: "jwt-1", email: "old@lab.dev", plan: "free" });
    const auth = new AuthService(new FakeClient(), store);
    const state = await auth.restore();
    assert.equal(state.status, "signed_in");
    if (state.status === "signed_in") {
      assert.equal(state.account.email, "u@lab.dev");
      assert.equal(state.session.email, "u@lab.dev");
    }
  });

  it("restore clears store on unauthorized", async () => {
    const store = new MemorySessionStore();
    await store.setSession({ token: "jwt-1", email: "u@lab.dev", plan: "free" });
    const client = new FakeClient();
    client.failMe = true;
    const auth = new AuthService(client, store);
    const state = await auth.restore();
    assert.equal(state.status, "signed_out");
    assert.equal(await store.getSession(), null);
  });

  it("signOut clears store", async () => {
    const store = new MemorySessionStore();
    await store.setSession({ token: "jwt-1", email: "u@lab.dev", plan: "free" });
    const client = new FakeClient();
    const auth = new AuthService(client, store);
    await auth.signOut();
    assert.equal(await store.getSession(), null);
    assert.equal(client.forgotten, "jwt-1");
  });

  it("restores only the native verified identity during an outage", async () => {
    const store = new MemorySessionStore();
    await store.setSession({ token: "jwt-1", email: "untrusted@lab.dev", plan: "pro" });
    const client = new FakeClient();
    client.online = false;
    const state = await new AuthService(client, store).restore();
    assert.equal(state.status, "signed_in");
    if (state.status === "signed_in") {
      assert.equal(state.online, false);
      assert.equal(state.account.email, "u@lab.dev");
      assert.equal(state.account.plan, "free");
    }
  });
});
