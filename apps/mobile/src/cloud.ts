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
