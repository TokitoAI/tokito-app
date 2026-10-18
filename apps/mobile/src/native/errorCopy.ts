/** User-facing copy helpers shared by the HTTP client and the native bridge. */

/**
 * Human duration for a retry delay, rounded up so we never tell the user to
 * retry before the server will accept it. Under a minute: seconds; under an
 * hour: minutes; otherwise hours plus leftover minutes.
 */
export function formatDuration(totalSecs: number): string {
  const secs = Math.max(1, Math.ceil(totalSecs));
  if (secs < 60) return plural(secs, "second");
  const minutes = Math.ceil(secs / 60);
  if (minutes < 60) return plural(minutes, "minute");
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0
    ? plural(hours, "hour")
    : `${plural(hours, "hour")} ${plural(rest, "minute")}`;
}

function plural(n: number, unit: string): string {
  return `${n} ${unit}${n === 1 ? "" : "s"}`;
}

/** "in 5 minutes" when the delay is known, otherwise "later". */
export function retryClause(retryAfterSecs?: number): string {
  return retryAfterSecs === undefined ? "later" : `in ${formatDuration(retryAfterSecs)}`;
}

/** Display label for a plan string; unknown plans are shown verbatim. */
export function planLabel(plan: string): string {
  const trimmed = plan.trim();
  return trimmed === "" ? "Unknown plan" : trimmed;
}
