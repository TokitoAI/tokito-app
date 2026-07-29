/**
 * Bridge to the UniFFI-backed native module.
 *
 * Production: TurboModule / ubrn installs `TokitoCompanionFfi` with methods
 * matching `CompanionClient`. Until the native binary is linked (device build),
 * `isNativeCompanionAvailable()` is false and the UI shows a link-native hint.
 */

import type { Account, CompanionClientPort, Session } from "./types";
import { CompanionError } from "./types";

type NativeClient = {
  baseUrl: () => string;
  otpRequest: (email: string) => void;
  otpVerify: (email: string, code: string) => Session;
  oauthExchange: (code: string) => Session;
  me: (token: string) => Account;
};

type NativeModuleShape = {
  CompanionClient: {
    new: (baseUrl: string) => NativeClient;
  };
};

function readNativeModule(): NativeModuleShape | null {
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { NativeModules } = require("react-native") as {
      NativeModules: Record<string, unknown>;
    };
    const mod = NativeModules.TokitoCompanionFfi;
    if (mod === undefined || mod === null) {
      return null;
    }
    return mod as NativeModuleShape;
  } catch {
    return null;
  }
}

export function isNativeCompanionAvailable(): boolean {
  return readNativeModule() !== null;
}

function mapNativeError(err: unknown): CompanionError {
  if (err instanceof CompanionError) {
    return err;
  }
  const message = err instanceof Error ? err.message : String(err);
  const lower = message.toLowerCase();
  if (lower.includes("cooldown")) return CompanionError.cooldown();
  if (lower.includes("rate")) return CompanionError.rateLimited();
  if (lower.includes("send")) return CompanionError.sendFailed();
  if (lower.includes("invalid")) return CompanionError.invalidCode();
  if (lower.includes("unauthorized") || lower.includes("expired")) {
    return CompanionError.unauthorized();
  }
  if (lower.includes("unavailable")) return CompanionError.unavailable();
  if (lower.includes("network")) return CompanionError.network(message);
  return CompanionError.unexpected();
}

/** UniFFI `CompanionClient` wrapped as async for the RN JS thread. */
export class NativeCompanionClient implements CompanionClientPort {
  private readonly inner: NativeClient;

  constructor(baseUrl: string) {
    const mod = readNativeModule();
    if (mod === null) {
      throw new Error("TokitoCompanionFfi native module is not linked");
    }
    this.inner = mod.CompanionClient.new(baseUrl);
  }

  baseUrl(): string {
    return this.inner.baseUrl();
  }

  async otpRequest(email: string): Promise<void> {
    try {
      this.inner.otpRequest(email);
    } catch (err) {
      throw mapNativeError(err);
    }
  }

  async otpVerify(email: string, code: string): Promise<Session> {
    try {
      return this.inner.otpVerify(email, code);
    } catch (err) {
      throw mapNativeError(err);
    }
  }

  async oauthExchange(code: string): Promise<Session> {
    try {
      return this.inner.oauthExchange(code);
    } catch (err) {
      throw mapNativeError(err);
    }
  }

  async me(token: string): Promise<Account> {
    try {
      return this.inner.me(token);
    } catch (err) {
      throw mapNativeError(err);
    }
  }
}
