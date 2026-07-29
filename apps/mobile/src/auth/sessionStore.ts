import type { Session } from "../native/types";

/** Persist the Tokito Cloud JWT off the JS heap. */
export interface SessionStore {
  getSession(): Promise<Session | null>;
  setSession(session: Session): Promise<void>;
  clear(): Promise<void>;
}

/** In-memory store for unit tests and Node CI. */
export class MemorySessionStore implements SessionStore {
  private session: Session | null = null;

  async getSession(): Promise<Session | null> {
    return this.session;
  }

  async setSession(session: Session): Promise<void> {
    this.session = { ...session };
  }

  async clear(): Promise<void> {
    this.session = null;
  }
}
