/**
 * Wire types matching `tokito_companion_ffi` UniFFI records / errors.
 * Keep in lockstep with `crates/ffi/src/lib.rs`.
 */

import { formatDuration, retryClause } from "./errorCopy";

/**
 * Plan names are server-owned and open-ended (new tiers ship without an app
 * release). Never narrow this to a union: unknown values must decode and be
 * displayed verbatim (see `planLabel`).
 */
export type Plan = string;

export interface Session {
  token: string;
  email: string;
  plan: Plan;
}

export interface Account {
  email: string;
  plan: Plan;
}

export type CompanionErrorKind =
  | "Cooldown"
  | "RateLimited"
  | "SendFailed"
  | "InvalidCode"
  | "CodeBurned"
  | "NetworkRateLimited"
  | "DailyCapReached"
  | "Unauthorized"
  | "Forbidden"
  | "Unavailable"
  | "Network"
  | "NativeCompatibility"
  | "Unexpected";

export class CompanionError extends Error {
  readonly kind: CompanionErrorKind;
  readonly networkMessage?: string;

  constructor(kind: CompanionErrorKind, message: string, networkMessage?: string) {
    super(message);
    this.name = "CompanionError";
    this.kind = kind;
    if (networkMessage !== undefined) {
      this.networkMessage = networkMessage;
    }
  }

  static cooldown(retryAfterSecs?: number): CompanionError {
    const wait =
      retryAfterSecs === undefined ? "a moment" : formatDuration(retryAfterSecs);
    return new CompanionError(
      "Cooldown",
      `Please wait ${wait} before requesting another code.`,
    );
  }

  /** Hourly cap on code requests. */
  static rateLimited(retryAfterSecs?: number): CompanionError {
    return new CompanionError(
      "RateLimited",
      `Too many code requests. Try again ${retryClause(retryAfterSecs)}.`,
    );
  }

  /** Per-IP cap on verify attempts. */
  static networkRateLimited(retryAfterSecs?: number): CompanionError {
    return new CompanionError(
      "NetworkRateLimited",
      `Too many attempts from this network. Try again ${retryClause(retryAfterSecs)}.`,
    );
  }

  /** Per-email daily cap on failed attempts. */
  static dailyCapReached(retryAfterSecs?: number): CompanionError {
    return new CompanionError(
      "DailyCapReached",
      `Too many failed attempts for this email. Sign in with Google or GitHub, or try again ${retryClause(retryAfterSecs)}.`,
    );
  }

  /** The code was burned by too many wrong attempts. */
  static codeBurned(): CompanionError {
    return new CompanionError(
      "CodeBurned",
      "Too many attempts on that code. Request a new code.",
    );
  }

  static forbidden(): CompanionError {
    return new CompanionError(
      "Forbidden",
      "You don't have access to this. Ask an admin of the organization.",
    );
  }

  static sendFailed(): CompanionError {
    return new CompanionError("SendFailed", "could not send the code; try again");
  }

  static invalidCode(): CompanionError {
    return new CompanionError("InvalidCode", "that code is invalid or expired");
  }

  static unauthorized(): CompanionError {
    return new CompanionError("Unauthorized", "session expired");
  }

  static unavailable(message?: string): CompanionError {
    return new CompanionError(
      "Unavailable",
      message ?? "this sign-in method is not available right now",
    );
  }

  static network(msg: string): CompanionError {
    return new CompanionError("Network", `network error: ${msg}`, msg);
  }

  static nativeCompatibility(): CompanionError {
    return new CompanionError(
      "NativeCompatibility",
      "App components are out of sync. Install the latest Tokito build.",
    );
  }

  static unexpected(): CompanionError {
    return new CompanionError("Unexpected", "unexpected server response");
  }
}

/** Port matching UniFFI `CompanionClient` methods (blocking on the Rust side). */
export interface CompanionClientPort {
  baseUrl(): string;
  otpRequest(email: string): Promise<void>;
  otpVerify(email: string, code: string): Promise<Session>;
  oauthExchange(code: string): Promise<Session>;
  me(token: string): Promise<Account>;
}
