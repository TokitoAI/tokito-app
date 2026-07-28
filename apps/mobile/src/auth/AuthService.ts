import type { Account, CompanionClientPort, Session } from "../native/types.js";
import { CompanionError } from "../native/types.js";
import type { SessionStore } from "./sessionStore.js";

export type AuthState =
  | { status: "signed_out" }
  | { status: "signed_in"; session: Session; account: Account };

/**
 * OTP sign-in + session restore against Tokito Cloud via the UniFFI client port.
 */
export class AuthService {
  private readonly client: CompanionClientPort;
  private readonly store: SessionStore;

  constructor(client: CompanionClientPort, store: SessionStore) {
    this.client = client;
    this.store = store;
  }

  /** Request a one-time email code. */
  async requestOtp(email: string): Promise<void> {
    const trimmed = email.trim();
    if (trimmed.length === 0) {
      throw CompanionError.unexpected();
    }
    await this.client.otpRequest(trimmed);
  }

  /** Verify OTP, persist session, return it. */
  async verifyOtp(email: string, code: string): Promise<Session> {
    const session = await this.client.otpVerify(email.trim(), code.trim());
    await this.store.setSession(session);
    return session;
  }

  /**
   * Restore from secure store and probe `/v1/auth/me`.
   * Clears the store on unauthorized.
   */
  async restore(): Promise<AuthState> {
    const session = await this.store.getSession();
    if (session === null) {
      return { status: "signed_out" };
    }
    try {
      const account = await this.client.me(session.token);
      const refreshed: Session = {
        token: session.token,
        email: account.email,
        plan: account.plan,
      };
      await this.store.setSession(refreshed);
      return { status: "signed_in", session: refreshed, account };
    } catch (err) {
      if (err instanceof CompanionError && err.kind === "Unauthorized") {
        await this.store.clear();
        return { status: "signed_out" };
      }
      throw err;
    }
  }

  async signOut(): Promise<void> {
    await this.store.clear();
  }

  async currentAccount(session: Session): Promise<Account> {
    return this.client.me(session.token);
  }
}
