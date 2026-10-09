import type { TokitoCompanionFfiNative } from "../../modules/tokito-companion-ffi/src";
export type Proposal = { run_id: string; event_seq: number; project_id: string; design_id: string; active_sheet_id: string | null; status: string; base_schematic_version: number; base_bom_version: number; payload: { kind: "schematic" | "bom"; ops: Record<string, unknown>[]; failure_note?: string | null; summary?: string | undefined } };
export type ReviewCommand = { project: string; design: string; action: string; state: "pending" | "blocked" | "confirmed"; request: { command_id: string; run_id: string; event_seq: number; selected_indices: number[] }; result: unknown };
export type Receipt = { command_id: string; run_id: string; event_seq: number; result: { status: "applied" | "rejected"; applied: number[]; unchanged: number[]; erc: { message: string; severity: string }[] | null } };
function record(value: unknown): Record<string, unknown> { if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Invalid review response"); return value as Record<string, unknown>; }
function integer(value: unknown): number { if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) throw new Error("Invalid review revision"); return value; }
function text(value: unknown): string { if (typeof value !== "string") throw new Error("Invalid review response"); return value; }
function indices(value: unknown): number[] { if (!Array.isArray(value) || value.length > 5000) throw new Error("Invalid review selection"); const result = value.map(integer); if (new Set(result).size !== result.length) throw new Error("Invalid review selection"); return result; }
export function parseProposals(value: unknown, project: string, design: string): Proposal[] {
  if (!Array.isArray(value) || value.length > 200) throw new Error("Invalid proposal list");
  return value.map(item => { const p = record(item), payload = record(p.payload);
    if (p.project_id !== project || p.design_id !== design || !["pending", "applied", "rejected"].includes(text(p.status)) || !["schematic", "bom"].includes(text(payload.kind)) || !Array.isArray(payload.ops) || payload.ops.length > 5000) throw new Error("Proposal belongs to another design or is invalid");
    return { run_id: text(p.run_id), event_seq: integer(p.event_seq), project_id: project, design_id: design, active_sheet_id: p.active_sheet_id == null ? null : text(p.active_sheet_id), status: text(p.status), base_schematic_version: integer(p.base_schematic_version), base_bom_version: integer(p.base_bom_version), payload: { kind: payload.kind as "schematic" | "bom", ops: payload.ops.map(record), failure_note: payload.failure_note == null ? null : text(payload.failure_note), summary: payload.summary === undefined ? undefined : text(payload.summary) } };
  });
}
export function parseReceipt(value: unknown): Receipt {
  const r = record(value), result = record(r.result);
  if (result.status === "rejected") return { command_id: text(r.command_id), run_id: text(r.run_id), event_seq: integer(r.event_seq), result: { status: "rejected", applied: [], unchanged: [], erc: [] } };
  if (result.status !== "applied" || !Array.isArray(result.rejected) || result.rejected.length !== 0) throw new Error("Server did not confirm an atomic application");
  const applied = indices(result.applied), unchanged = indices(result.unchanged);
  if (applied.some(i => unchanged.includes(i))) throw new Error("Invalid application receipt");
  const erc = result.erc === undefined ? null : result.erc;
  if (erc !== null && (!Array.isArray(erc) || erc.length > 10000)) throw new Error("Invalid ERC result");
  return { command_id: text(r.command_id), run_id: text(r.run_id), event_seq: integer(r.event_seq), result: { status: "applied", applied, unchanged, erc: erc === null ? null : (erc as unknown[]).map(v => { const item = record(v); return { message: typeof item.message === "string" ? item.message : typeof item.code === "string" ? item.code : "Electrical check issue", severity: typeof item.severity === "string" ? item.severity : "warning" }; }) } };
}
export function parseCommands(value: unknown, project: string, design: string): ReviewCommand[] {
  if (!Array.isArray(value) || value.length > 200) throw new Error("Invalid review queue");
  return value.map(v => { const c = record(v), request = record(c.request); if (c.project !== project || c.design !== design || !["pending", "blocked", "confirmed"].includes(text(c.state))) throw new Error("Review scope mismatch"); return { project, design, action: text(c.action), state: c.state as ReviewCommand["state"], request: { command_id: text(request.command_id), run_id: text(request.run_id), event_seq: integer(request.event_seq), selected_indices: indices(request.selected_indices) }, result: c.result }; });
}
export function reviewFailure(value: unknown): string {
  const v = record(value), error = typeof v.error === "object" && v.error !== null ? record(v.error) : {};
  if (error.code === "proposal_stale") return "The design changed. Nothing was applied. Ask Tokito for an updated proposal, then review it again.";
  if (error.code === "proposal_already_decided") return "This proposal was already reviewed on another device. Refresh to see the result.";
  if (error.code === "catalog_resolution_failed") return "A required symbol could not be verified. Nothing was applied. Retry after the catalog is available.";
  if (error.code === "application_rejected") return "The selected changes failed validation. Nothing was applied. Review the selection or ask Tokito to correct the proposal.";
  return "The server rejected this review. Nothing was applied. Refresh and review the current proposal.";
}
let counter = 0;
export class ReviewClient {
  readonly id = `review-${Date.now()}-${++counter}`;
  private closed = false;
  constructor(private native: TokitoCompanionFfiNative) {}
  async open(base: string, token: string): Promise<void> { await this.native.openReviews(base, token, this.id); if (this.closed) await this.native.closeReviews(this.id); }
  async close(): Promise<void> { this.closed = true; await this.native.closeReviews(this.id); }
  async load(project: string, design: string): Promise<{ proposals: Proposal[]; commands: ReviewCommand[] }> { const data = record(JSON.parse(await this.native.loadReviews(this.id, project, design))); return { proposals: parseProposals(data.proposals, project, design), commands: parseCommands(data.commands, project, design) }; }
  async local(project: string, design: string): Promise<ReviewCommand[]> { return parseCommands(JSON.parse(await this.native.localReviewCommands(this.id, project, design)), project, design); }
  async decide(project: string, design: string, p: Proposal, action: "apply" | "reject", selected: number[]): Promise<{ receipt?: Receipt; failure?: string }> {
    if (p.project_id !== project || p.design_id !== design) throw new Error("Review scope mismatch");
    const data = record(JSON.parse(await this.native.decideReview(this.id, project, design, p.run_id, String(p.event_seq), action, JSON.stringify(selected))));
    if (data.state === "blocked") return { failure: reviewFailure(data.receipt) };
    if (data.state !== "confirmed") throw new Error("Review has not been confirmed");
    const receipt = parseReceipt(data.receipt);
    if (receipt.run_id !== p.run_id || receipt.event_seq !== p.event_seq) throw new Error("Review receipt scope mismatch");
    return { receipt };
  }
}
