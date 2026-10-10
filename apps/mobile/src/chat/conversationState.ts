/** Native UniFFI errors currently arrive as messages, not typed HTTP responses. */
export function chatFailure(error: unknown): string {
  const native = error !== null && typeof error === "object" ? error as { code?: unknown; message?: unknown } : null;
  // UniFFI fieldless variants can carry an empty message. Expo retains their
  // stable code, which must take precedence over localized/empty prose.
  const message = `${typeof native?.code === "string" ? native.code : ""} ${typeof native?.message === "string" ? native.message : String(error)}`;
  if (/session expired|unauthorized/i.test(message)) return "Your session expired. Sign in again to use chat.";
  if (/not available|unavailable/i.test(message)) return "Design chat is unavailable on the server right now. Project sync can still be connected. Try again after the service is updated.";
  if (/too many|rate.?limit|cooldown/i.test(message)) return "Chat is receiving too many requests. Wait a moment, then try again.";
  if (/resource changed|conflict/i.test(message)) return "This design changed on another device. Refresh the design before sending again.";
  if (/network error|timed? ?out|connection/i.test(message)) return "Chat could not reach the server. Your saved messages are safe; check your connection and retry.";
  return "Chat could not complete this request. Your saved messages are safe. Try again; if it persists, the service needs attention.";
}

/** Tickets invalidate even A → B → A navigation and same-thread reloads. */
export class ConversationScope {
  private revision = 0;
  private drafts = new Map<string | null, string>();
  private firstMessage: { intent: string; command: string; version: number } | null = null;
  next(): number { return ++this.revision; }
  current(ticket: number): boolean { return ticket === this.revision; }
  ticket(): number { return this.revision; }
  draft(thread: string | null): string { return this.drafts.get(thread) ?? ""; }
  save(thread: string | null, text: string): void { this.drafts.set(thread, text); }
  firstMessageCommand(intent: string, allocate: () => string, version = 0): string {
    if (this.firstMessage?.intent !== intent) this.firstMessage = { intent, command: allocate(), version };
    return this.firstMessage.command;
  }
  firstMessageVersion(command: string): number {
    if (this.firstMessage?.command !== command) throw new Error("Conversation command was superseded");
    return this.firstMessage.version;
  }
  finishFirstMessage(command: string): boolean {
    if (this.firstMessage?.command !== command) return false;
    this.firstMessage = null;
    return true;
  }
  reset(): void { this.next(); this.drafts.clear(); this.firstMessage = null; }
}
