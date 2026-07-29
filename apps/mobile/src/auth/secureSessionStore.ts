import * as SecureStore from "expo-secure-store";
import type { Session } from "../native/types";
import type { SessionStore } from "./sessionStore";

/**
 * Secure Store-backed session (`expo-secure-store`).
 * Kept in a separate module so Node CI tests can import `MemorySessionStore`
 * without loading the native Expo module.
 */
export class SecureSessionStore implements SessionStore {
  private readonly key: string;

  constructor(key = "tokito.cloud.session") {
    this.key = key;
  }

  async getSession(): Promise<Session | null> {
    const raw = await SecureStore.getItemAsync(this.key);
    if (raw === null || raw.trim() === "") {
      return null;
    }
    try {
      const parsed = JSON.parse(raw) as Session;
      if (
        typeof parsed.token !== "string" ||
        typeof parsed.email !== "string" ||
        typeof parsed.plan !== "string"
      ) {
        await SecureStore.deleteItemAsync(this.key);
        return null;
      }
      return parsed;
    } catch {
      await SecureStore.deleteItemAsync(this.key);
      return null;
    }
  }

  async setSession(session: Session): Promise<void> {
    await SecureStore.setItemAsync(this.key, JSON.stringify(session));
  }

  async clear(): Promise<void> {
    await SecureStore.deleteItemAsync(this.key);
  }
}
