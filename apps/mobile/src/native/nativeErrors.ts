import { CompanionError } from "./types";

/**
 * UniFFI Kotlin renders data-carrying variants as `retryAfterSecs=N` (or
 * `null`); the delay is the only integer in the message.
 */
export function retrySecsFromMessage(message: string): number | undefined {
  const m = /(\d+)/.exec(message);
  return m === null ? undefined : Number(m[1]);
}

/** Map Expo CodedException codes from Kotlin CompanionException simple names. */
export function mapNativeError(err: unknown): CompanionError {
  if (err instanceof CompanionError) return err;
  const coded = err as { code?: string; message?: string };
  const code = (coded.code ?? "").replace(/Exception$/i, "");
  const message = coded.message ?? (err instanceof Error ? err.message : String(err));
  switch (code) {
    case "Cooldown":
      return CompanionError.cooldown(retrySecsFromMessage(message));
    case "RateLimited":
      return CompanionError.rateLimited(retrySecsFromMessage(message));
    case "NetworkRateLimited":
      return CompanionError.networkRateLimited(retrySecsFromMessage(message));
    case "DailyCapReached":
      return CompanionError.dailyCapReached(retrySecsFromMessage(message));
    case "CodeBurned":
      return CompanionError.codeBurned();
    case "Forbidden":
      return CompanionError.forbidden();
    case "SendFailed":
      return CompanionError.sendFailed();
    case "InvalidCode":
      return CompanionError.invalidCode();
    case "Unauthorized":
      return CompanionError.unauthorized();
    case "Unavailable":
      return CompanionError.unavailable(message);
    case "Network":
      return CompanionError.network(message);
    case "NativeCompatibility":
      return CompanionError.nativeCompatibility();
    case "Unexpected":
      return CompanionError.unexpected();
    default:
      return CompanionError.unexpected();
  }
}
