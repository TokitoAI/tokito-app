export type ChatSheet = { id: string; name: string };
/** Only canonical synchronized sheets may provide edit context. */
export function chatSheets(payload: unknown): ChatSheet[] {
  if (!payload || typeof payload !== "object") return [];
  const sheets = (payload as Record<string, unknown>).sheets;
  if (!Array.isArray(sheets)) return [];
  const ids = new Set<string>();
  return sheets.flatMap(value => {
    if (!value || typeof value !== "object") return [];
    const sheet = value as Record<string, unknown>;
    if (typeof sheet.id !== "string" || !sheet.id || ids.has(sheet.id)) return [];
    ids.add(sheet.id);
    return [{ id: sheet.id, name: typeof sheet.name === "string" && sheet.name.trim() ? sheet.name : sheet.id }];
  });
}
export function selectedSheet(sheets: ChatSheet[], selected: string | null): string | null {
  return sheets.some(sheet => sheet.id === selected) ? selected : sheets.length === 1 ? sheets[0]!.id : null;
}
