/** Bounded product view of durable server events, not a second agent runtime. */
export type ChatRow = { id: string; role: "user" | "assistant"; content: string; runId: string };
export type ChatThread = { id: string; title: string; project_id: string; design_id: string };
export type ChatRun = { run_id: string; thread_id: string; project_id: string; design_id: string; status: string };
export type ChatHistory = { thread: ChatThread; messages: ChatRow[]; olderCursor: number | null; latestRun: ChatRun | null };
export type Proposal = { turnId: string; payload: Record<string, unknown> };
export type RunView = { cursor: number; text: string; completedText: string; draftText: string; activity: string | null; proposals: Proposal[]; error: string | null; done: boolean };
export const emptyRun = (): RunView => ({ cursor: 0, text: "", completedText: "", draftText: "", activity: null, proposals: [], error: null, done: false });
const MAX_JSON = 2 * 1024 * 1024;
const MAX_TEXT = 512 * 1024;

export function object(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error("Invalid conversation response");
  return value as Record<string, unknown>;
}
export function string(value: unknown): string {
  if (typeof value !== "string" || value.length > MAX_TEXT) throw new Error("Invalid conversation text");
  return value;
}
export function sequence(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) throw new Error("Invalid conversation sequence");
  return value;
}
export function parseJson(json: string): unknown {
  if (json.length > MAX_JSON) throw new Error("Conversation response exceeds the display limit");
  return JSON.parse(json) as unknown;
}
export function parseThread(value: unknown): ChatThread {
  const t = object(value);
  return { id: string(t.id), title: string(t.title), project_id: string(t.project_id), design_id: string(t.design_id) };
}
export function parseRun(value: unknown): ChatRun {
  const r = object(value);
  return { run_id: string(r.run_id), thread_id: string(r.thread_id), project_id: string(r.project_id), design_id: string(r.design_id), status: string(r.status) };
}
export function parseHistory(json: string): ChatHistory {
  const page = object(parseJson(json));
  if (!Array.isArray(page.messages) || page.messages.length > 100) throw new Error("Invalid history page");
  return {
    thread: parseThread(page.thread),
    messages: page.messages.map((raw): ChatRow => {
      const message = object(raw);
      if (message.role !== "user" && message.role !== "assistant") throw new Error("Unexpected message role");
      sequence(message.seq);
      return { id: string(message.id), role: message.role, content: string(message.content), runId: string(message.run_id) };
    }),
    olderCursor: page.older_cursor == null ? null : sequence(page.older_cursor),
    latestRun: page.latest_run == null ? null : parseRun(page.latest_run),
  };
}
export function isActive(run: ChatRun | null): boolean {
  return run !== null && (run.status === "running" || run.status === "cancel_requested");
}
export function belongsToDisplayedRun(eventRun: unknown, displayedRun: string | null): boolean {
  return displayedRun !== null && eventRun === displayedRun;
}
export function chatHistoryProjection(rows: ChatRow[], run: ChatRun | null, savedRun: string | null): { rows: ChatRow[]; hasSavedAnswer: boolean } {
  return {
    rows: run !== null && savedRun !== run.run_id ? rows.filter(row => row.role !== "assistant" || row.runId !== run.run_id) : rows,
    hasSavedAnswer: run !== null && savedRun === run.run_id && rows.some(row => row.role === "assistant" && row.runId === run.run_id),
  };
}
function responseText(completed: string, draft: string): string {
  const text = completed && draft ? `${completed}\n\n${draft}` : completed || draft;
  if (text.length > MAX_TEXT) throw new Error("Response exceeds the display limit; reopen the saved conversation");
  return text;
}

/** Activity is summarized; raw tool arguments/results stay out of main chat. */
export function reduceRun(previous: RunView, raw: unknown): RunView {
  const envelope = object(raw);
  const seq = sequence(envelope.seq);
  if (seq <= previous.cursor) return previous;
  if (seq !== previous.cursor + 1) throw new Error("Conversation event missing; reconnect to recover");
  const event = object(envelope.event);
  const next = { ...previous, cursor: seq };
  switch (event.type) {
    case "assistant_text_delta": {
      const draftText = previous.draftText + string(event.token);
      return { ...next, text: responseText(previous.completedText, draftText), draftText, activity: null };
    }
    case "assistant_text_done": {
      const completedText = responseText(previous.completedText, string(event.body));
      return { ...next, text: completedText, completedText, draftText: "", activity: null };
    }
    case "tool_call": return { ...next, activity: "Checking project details…" };
    case "tool_result": return { ...next, activity: "Preparing the response…" };
    case "progress": return { ...next, activity: "Working on your design…" };
    case "suggestion": {
      const turnId = string(event.turn_id);
      const proposal = { turnId, payload: object(event.payload) };
      const proposals = previous.proposals.filter(p => p.turnId !== turnId);
      if (proposals.length >= 100) throw new Error("Too many proposals in one run");
      return { ...next, proposals: [...proposals, proposal], activity: "Changes ready for review" };
    }
    case "error": return { ...next, error: "Tokito could not complete this step. Your saved design has not been changed." };
    case "done": return { ...next, done: true, activity: null };
    default: return next;
  }
}
