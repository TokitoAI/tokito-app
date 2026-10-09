import type { BomLine } from "../api/cloudHttp";

type RecordValue = Record<string, unknown>;
const record = (value: unknown): RecordValue => value !== null && typeof value === "object" && !Array.isArray(value) ? value as RecordValue : {};
const text = (value: unknown): string | undefined => typeof value === "string" && value.trim().length > 0 ? value : undefined;
const rows = (value: unknown): RecordValue[] => Array.isArray(value) ? value.map(record) : [];

/** Canonical BOM rows reference part records; they are not published ref/value rows. */
export function canonicalBom(payload: unknown, schematic: unknown): { lines: BomLine[]; error: string | null } {
  const bom = record(payload);
  if (bom.deleted === true) return { lines: [], error: null };
  if (!Array.isArray(bom.lines)) return { lines: [], error: "The parts list could not be read. Refresh this project." };
  const parts = new Map(rows(bom.parts).map(part => [text(part.id), part]));
  const symbols = rows(record(schematic).symbols);
  const lines: BomLine[] = [];
  for (const line of rows(bom.lines).sort((a, b) => Number(a.sort_order ?? 0) - Number(b.sort_order ?? 0))) {
    if (typeof line.quantity !== "number" || !Number.isFinite(line.quantity) || line.quantity < 0) return { lines: [], error: "A part quantity could not be read. Refresh this project." };
    const id = text(line.part_id);
    const part = id === undefined ? undefined : parts.get(id);
    const placed = id === undefined ? [] : symbols.filter(symbol => symbol.part_id === id);
    const refs = [...new Set(placed.map(symbol => text(symbol.ref_des)).filter((ref): ref is string => ref !== undefined))].sort();
    lines.push({
      ...(text(line.id) !== undefined ? { id: text(line.id)! } : {}), ...(id !== undefined ? { part_id: id } : {}),
      ref: refs.length > 0 ? refs.join(", ") : "Unplaced",
      value: text(part?.mpn) ?? text(part?.description) ?? placed.map(symbol => text(symbol.value)).find(value => value !== undefined) ?? "Part details unavailable",
      footprint: text(part?.package_name) ?? placed.map(symbol => text(symbol.footprint_ref)).find(value => value !== undefined) ?? "",
      qty: line.quantity,
    });
  }
  return { lines, error: null };
}
