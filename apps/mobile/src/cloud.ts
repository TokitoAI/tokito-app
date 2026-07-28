/**
 * Typed contract for the UniFFI native module (lands in M1).
 * Keep in sync with `crates/ffi` method surface.
 */

export type Plan = "free" | "pro" | string;

export interface CloudSession {
  token: string;
  email: string;
  plan: Plan;
}

export interface CloudAccount {
  email: string;
  plan: Plan;
}

export type CloudErrorCode =
  | "cooldown"
  | "rate_limited"
  | "send_failed"
  | "invalid_code"
  | "unauthorized"
  | "unavailable"
  | "network"
  | "unexpected";

export interface CompanionNativeModule {
  otpRequest(baseUrl: string, email: string): Promise<void>;
  otpVerify(baseUrl: string, email: string, code: string): Promise<CloudSession>;
  oauthExchange(baseUrl: string, code: string): Promise<CloudSession>;
  me(baseUrl: string, token: string): Promise<CloudAccount>;
}

/** Production default — matches Rust `DEFAULT_CLOUD_BASE`. */
export const DEFAULT_CLOUD_BASE = "https://api.tokito.dev";

/** Strip trailing slashes the same way as `tokito_companion_core::resolve_base_url`. */
export function normalizeBaseUrl(raw: string): string {
  const trimmed = raw.trim();
  if (trimmed.length === 0) {
    return DEFAULT_CLOUD_BASE;
  }
  return trimmed.replace(/\/+$/, "");
}
