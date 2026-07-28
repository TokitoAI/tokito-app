import type { Session } from "../native/types.js";

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

/**
 * Secure Store-backed session. Uses `expo-secure-store` when linked;
 * falls back to rejecting on missing native module so we never silently
 * write tokens to AsyncStorage.
 */
export class SecureSessionStore implements SessionStore {
  private readonly key: string;

  constructor(key = "tokito.cloud.session") {
    this.key = key;
  }

  private async secureStore(): Promise<{
    getItemAsync: (k: string) => Promise<string | null>;
    setItemAsync: (k: string, v: string) => Promise<void>;
    deleteItemAsync: (k: string) => Promise<void>;
  }> {
    try {
      // Dynamic import keeps Node CI free of the native module.
      const mod = await import("expo-secure-store");
      return mod;
    } catch {
      throw new Error(
        "expo-secure-store is required for SecureSessionStore; use MemorySessionStore in tests",
      );
    }
  }

  async getSession(): Promise<Session | null> {
    const store = await this.secureStore();
    const raw = await store.getItemAsync(this.key);
    if (raw === null || raw.trim() === "") {
      return null;
    }
    const parsed = JSON.parse(raw) as Session;
    if (
      typeof parsed.token !== "string" ||
      typeof parsed.email !== "string" ||
      typeof parsed.plan !== "string"
    ) {
      await store.deleteItemAsync(this.key);
      return null;
    }
    return parsed;
  }

  async setSession(session: Session): Promise<void> {
    const store = await this.secureStore();
    await store.setItemAsync(this.key, JSON.stringify(session));
  }

  async clear(): Promise<void> {
    const store = await this.secureStore();
    await store.deleteItemAsync(this.key);
  }
}
