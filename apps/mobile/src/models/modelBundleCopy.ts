/** Native diagnostics can contain app-private paths; keep primary UI copy bounded. */
export function modelBundleErrorMessage(cause: unknown): string {
  const detail = cause instanceof Error ? cause.message.toLowerCase() : "";
  if (detail.includes("license")) {
    return "This bundle’s license is not allowed in this build. Import a supported Tokito-signed bundle.";
  }
  if (detail.includes("unsatisfiedlinkerror") || detail.includes("dlopen") || detail.includes("cannot locate symbol")) {
    return "The model runtime could not load. Update Tokito before trying this bundle again.";
  }
  if (detail.includes("signature") || detail.includes("digest") || detail.includes("inventory")) {
    return "This bundle failed verification. Download a fresh Tokito-signed bundle and try again.";
  }
  if (detail.includes("rollback is unavailable")) {
    return "No verified previous version is available. Import a supported bundle instead.";
  }
  return "Tokito could not finish the model operation. Check that you selected a supported Tokito-signed bundle and try again.";
}
