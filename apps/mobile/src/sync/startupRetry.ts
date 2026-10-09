import { CompanionError } from "../native/types";

/** Only transient transport failures retry; authorization/protocol failures stop. */
export function retryableStartup(error: unknown): boolean {
  return error instanceof CompanionError &&
    (error.kind === "Network" || error.kind === "Unavailable" || error.kind === "RateLimited");
}

export function startupRetryDelay(attempt: number): number {
  return Math.min(30_000, 3_000 * 2 ** Math.min(Math.max(attempt, 0), 4));
}
