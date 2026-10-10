import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { AuthOperationSuperseded, AuthService } from "./AuthService";
import { MemorySessionStore } from "./sessionStore";
import type { Account, CompanionClientPort, Session } from "../native/types";
import { CompanionError } from "../native/types";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

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
  for (const unauthorized of [false, true]) {
    it(`ignores late restore ${unauthorized ? "401" : "success"} after sign-out`, async () => {
      const store = new MemorySessionStore();
      await store.setSession({ token: "jwt-1", email: "u@lab.dev", plan: "free" });
      const client = new FakeClient();
      const started = deferred<void>();
      const response = deferred<{ account: Account; online: boolean }>();
      client.resolveIdentity = async () => { started.resolve(); return response.promise; };
      const auth = new AuthService(client, store);
      const restoring = auth.restore();
      const rejected = assert.rejects(restoring, AuthOperationSuperseded);
      await started.promise;
      await auth.signOut();
      if (unauthorized) response.reject(CompanionError.unauthorized());
      else response.resolve({ account: { email: "u@lab.dev", plan: "free" }, online: true });
      await rejected;
      assert.equal(await store.getSession(), null);
    });

    it(`late restore ${unauthorized ? "401" : "success"} cannot replace a newer sign-in`, async () => {
      const store = new MemorySessionStore();
      await store.setSession({ token: "jwt-1", email: "u@lab.dev", plan: "free" });
      const client = new FakeClient();
      const started = deferred<void>();
      const response = deferred<{ account: Account; online: boolean }>();
      client.resolveIdentity = async () => { started.resolve(); return response.promise; };
      client.otpVerify = async (email) => ({ token: "new-token", email, plan: "pro" });
      const auth = new AuthService(client, store);
      const rejected = assert.rejects(auth.restore(), AuthOperationSuperseded);
      await started.promise;
      const next = await auth.verifyOtp("next@lab.dev", "123456");
      if (unauthorized) response.reject(CompanionError.unauthorized());
      else response.resolve({ account: { email: "u@lab.dev", plan: "free" }, online: true });
      await rejected;
      assert.deepEqual(await store.getSession(), next);
    });
  }

  it("a late OTP response cannot restore a signed-out session", async () => {
    const store = new MemorySessionStore();
    const client = new FakeClient();
    const response = deferred<Session>();
    client.otpVerify = async () => response.promise;
    const auth = new AuthService(client, store);
    const rejected = assert.rejects(auth.verifyOtp("u@lab.dev", "123456"), AuthOperationSuperseded);
    await auth.signOut();
    response.resolve({ token: "jwt-1", email: "u@lab.dev", plan: "free" });
    await rejected;
    assert.equal(await store.getSession(), null);
  });

  it("a newer restore owns state even when the older response arrives last", async () => {
    const store = new MemorySessionStore();
    await store.setSession({ token: "jwt-1", email: "u@lab.dev", plan: "free" });
    const client = new FakeClient();
    const started = deferred<void>();
    const oldResponse = deferred<{ account: Account; online: boolean }>();
    let calls = 0;
    client.resolveIdentity = async () => {
      if (++calls === 1) { started.resolve(); return oldResponse.promise; }
      return { account: { email: "u@lab.dev", plan: "pro" }, online: true };
    };
    const auth = new AuthService(client, store);
    const rejected = assert.rejects(auth.restore(), AuthOperationSuperseded);
    await started.promise;
    const current = await auth.restore();
    oldResponse.resolve({ account: { email: "u@lab.dev", plan: "free" }, online: false });
    await rejected;
    assert.equal(current.status, "signed_in");
    assert.equal((await store.getSession())?.plan, "pro");
  });

  it("a failed store write does not poison the serialized operation queue", async () => {
    class FailingStore extends MemorySessionStore {
      fail = true;
      override async setSession(session: Session): Promise<void> {
        if (this.fail) { this.fail = false; throw new Error("storage unavailable"); }
        await super.setSession(session);
      }
    }
    const store = new FailingStore();
    const auth = new AuthService(new FakeClient(), store);
    await assert.rejects(auth.verifyOtp("u@lab.dev", "123456"), /storage unavailable/);
    const current = await auth.verifyOtp("u@lab.dev", "123456");
    assert.deepEqual(await store.getSession(), current);
  });

  it("late native sign-out completion does not clear a new session", async () => {
    const store = new MemorySessionStore();
    await store.setSession({ token: "jwt-1", email: "u@lab.dev", plan: "free" });
    const client = new FakeClient();
    const started = deferred<void>();
    const response = deferred<void>();
    client.forgetIdentity = async () => { started.resolve(); return response.promise; };
    const auth = new AuthService(client, store);
    const signingOut = auth.signOut();
    await started.promise;
    const next = await auth.verifyOtp("next@lab.dev", "123456");
    response.resolve();
    await signingOut;
    assert.deepEqual(await store.getSession(), next);
  });

  it("serializes delayed secure-store writes before sign-out clearing", async () => {
    class DelayedStore extends MemorySessionStore {
      entered = deferred<void>();
      release = deferred<void>();
      override async setSession(session: Session): Promise<void> {
        this.entered.resolve();
        await this.release.promise;
        await super.setSession(session);
      }
    }
    const store = new DelayedStore();
    const auth = new AuthService(new FakeClient(), store);
    const rejected = assert.rejects(auth.verifyOtp("u@lab.dev", "123456"), AuthOperationSuperseded);
    await store.entered.promise;
    const signingOut = auth.signOut();
    store.release.resolve();
    await Promise.all([rejected, signingOut]);
    assert.equal(await store.getSession(), null);
  });

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
