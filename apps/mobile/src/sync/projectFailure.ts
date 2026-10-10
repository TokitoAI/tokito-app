/** User-facing project recovery copy; never render native exception internals. */
export function projectFailure(error: unknown): string {
  const record = typeof error === "object" && error !== null ? error as Record<string, unknown> : {};
  const kind = String(record.kind ?? record.code ?? "").toLowerCase();
  if (kind.includes("unauthorized")) return "Sign in again to load this project's published previews.";
  if (kind.includes("nativecompatibility")) return "Update Tokito to load this project's published previews.";
  if (kind.includes("unavailable")) return "Published previews aren't available for this project yet. Project updates may still be connected.";
  if (kind.includes("network")) return "Couldn't load published previews. Check your connection and try again.";
  return "Couldn't load published previews. Try again; project updates are handled separately.";
}
