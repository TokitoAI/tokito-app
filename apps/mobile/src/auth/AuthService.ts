import type { Account, CompanionClientPort, Session } from "../native/types";
import { CompanionError } from "../native/types";
import type { SessionStore } from "./sessionStore";

export type AuthState =
  | { status: "signed_out" }
  | { status: "signed_in"; session: Session; account: Account; online: boolean };

/** An older operation lost ownership to sign-out, sign-in, or another restore. */
export class AuthOperationSuperseded extends Error {
  constructor() {
    super("Authentication operation superseded");
    this.name = "AuthOperationSuperseded";
  }
}

/**
 * OTP sign-in + session restore against Tokito Cloud via the UniFFI client port.
 */
export class AuthService {
  private readonly client: CompanionClientPort;
  private readonly store: SessionStore;
  private generation = 0;
  private storeTail: Promise<void> = Promise.resolve();

  private assertCurrent(generation: number): void {
    if (generation !== this.generation) throw new AuthOperationSuperseded();
  }

  /** Serialize secure-store I/O, without holding the queue over network calls. */
  private storeOperation<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.storeTail.then(operation);
    this.storeTail = result.then(() => undefined, () => undefined);
    return result;
  }

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
    const generation = ++this.generation;
    const session = await this.client.otpVerify(email.trim(), code.trim());
    await this.storeOperation(async () => {
      this.assertCurrent(generation);
      await this.store.setSession(session);
    });
    this.assertCurrent(generation);
    return session;
  }

  /**
   * Restore from secure store and probe `/v1/auth/me`.
   * Clears the store on unauthorized.
   */
  async restore(): Promise<AuthState> {
    const generation = ++this.generation;
    const session = await this.storeOperation(() => this.store.getSession());
    this.assertCurrent(generation);
    if (session === null) {
      return { status: "signed_out" };
    }
    try {
      const { account, online } = await this.client.resolveIdentity(session.token);
      this.assertCurrent(generation);
      const refreshed: Session = {
        token: session.token,
        email: account.email,
        plan: account.plan,
      };
      await this.storeOperation(async () => {
        this.assertCurrent(generation);
        await this.store.setSession(refreshed);
      });
      this.assertCurrent(generation);
      return { status: "signed_in", session: refreshed, account, online };
    } catch (err) {
      this.assertCurrent(generation);
      if (err instanceof CompanionError && err.kind === "Unauthorized") {
        await this.storeOperation(async () => {
          this.assertCurrent(generation);
          await this.store.clear();
        });
        this.assertCurrent(generation);
        return { status: "signed_out" };
      }
      throw err;
    }
  }

  async signOut(): Promise<void> {
    ++this.generation;
    const session = await this.storeOperation(async () => {
      const previous = await this.store.getSession();
      await this.store.clear();
      return previous;
    });
    // The old identity can be forgotten after a new sign-in; it must never
    // clear that newer secure session when the native call finishes.
    if (session) await this.client.forgetIdentity(session.token);
  }

  async currentAccount(session: Session): Promise<Account> {
    return this.client.me(session.token);
  }
}
