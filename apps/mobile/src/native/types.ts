/**
 * Wire types matching `tokito_companion_ffi` UniFFI records / errors.
 * Keep in lockstep with `crates/ffi/src/lib.rs`.
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
  | "Unauthorized"
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

  static cooldown(): CompanionError {
    return new CompanionError("Cooldown", "wait a moment before requesting another code");
  }

  static rateLimited(): CompanionError {
    return new CompanionError("RateLimited", "too many code requests; try again later");
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
