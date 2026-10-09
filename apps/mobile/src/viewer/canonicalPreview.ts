export interface CanonicalPreview { project_id: string; design_id: string; sheet_id: string; schematic_version: number; source_sha256: string; sha256: string; svg: string; cached?: boolean }
/** Native Rust verifies bytes and digest; this gate also protects JS response races. */
export function parseCanonicalPreview(json: string, project: string, design: string, sheet: string, version: number): CanonicalPreview {
  const value = JSON.parse(json) as Partial<CanonicalPreview>;
  if (value.project_id !== project || value.design_id !== design || value.sheet_id !== sheet || value.schematic_version !== version || typeof value.svg !== "string" || value.svg.length > 8 * 1024 * 1024 || !value.svg.includes("<svg") || typeof value.sha256 !== "string" || !/^[a-f0-9]{64}$/.test(value.sha256) || typeof value.source_sha256 !== "string" || !/^[a-f0-9]{64}$/.test(value.source_sha256)) throw new Error("Snapshot does not match this revision");
  return value as CanonicalPreview;
}
