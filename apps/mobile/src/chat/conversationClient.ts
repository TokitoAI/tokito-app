import type { TokitoCompanionFfiNative, NativeEventSubscription } from "../../modules/tokito-companion-ffi/src";
import { object, string, parseHistory, parseJson, parseRun, parseThread, type ChatHistory, type ChatRun, type ChatThread } from "./conversationModel";

let instance = 0;
/** One native session per signed-in project view; sign-out disposes all workers. */
export class ConversationClient {
  constructor(private readonly native: TokitoCompanionFfiNative) {}
  readonly id = `chat-${Date.now()}-${++instance}`;
  private closed = false;
  private subscription: NativeEventSubscription | null = null;
  private watchId: string | null = null;
  private generation = 0;

  async open(base: string, token: string, onChange: (change: Record<string, unknown>) => void): Promise<void> {
    if (this.closed) throw new Error("Conversation session is closed");
    this.subscription = this.native.addListener("onConversationChange", event => {
      if (this.closed || event.sessionId !== this.id || event.watchId !== "" && event.watchId !== this.watchId) return;
      try { onChange(object(parseJson(event.payloadJson))); }
      catch { onChange({ type: "invalid_event" }); }
    });
    try {
      await this.native.openConversations(base, token, this.id);
      // Opening can finish after navigation/sign-out. Dispose that late result.
      if (this.closed) await this.native.closeConversations(this.id);
    } catch (error) {
      this.subscription?.remove();
      this.subscription = null;
      throw error;
    }
  }

  async threads(project: string, design: string): Promise<ChatThread[]> {
    const value = parseJson(await this.native.conversationThreads(this.id, project, design));
    if (!Array.isArray(value) || value.length > 100) throw new Error("Invalid conversation list");
    return value.map(parseThread);
  }
  async create(project: string, design: string, title: string): Promise<ChatThread> {
    return parseThread(parseJson(await this.native.createConversation(this.id, project, design, title)));
  }
  async history(project: string, design: string, thread: string, before: number | null = null): Promise<ChatHistory> {
    const page = parseHistory(await this.native.conversationHistory(this.id, project, design, thread, before === null ? null : String(before)));
    if (page.thread.project_id !== project || page.thread.design_id !== design || page.thread.id !== thread) throw new Error("Conversation belongs to a different design");
    return page;
  }
  async send(project: string, design: string, thread: string, content: string, version: number, kind: "planner" | "schematic" | "bom"): Promise<Record<string, unknown>> {
    if (!Number.isSafeInteger(version) || version < 0) throw new Error("Refresh the design before sending");
    return object(parseJson(await this.native.sendConversationMessage(this.id, project, design, thread, content, String(version), kind)));
  }
  async cancel(run: string): Promise<ChatRun> {
    return parseRun(parseJson(await this.native.cancelConversationRun(this.id, run)));
  }
  async blocked(thread: string): Promise<{ id: string; content: string }[]> {
    const items = parseJson(await this.native.blockedConversationMessages(this.id, thread));
    if (!Array.isArray(items) || items.length > 100) throw new Error("Invalid pending message list");
    return items.map(item => { const request = object(object(item).request); return { id: string(request.message_id), content: string(request.content) }; });
  }
  async queued(thread: string): Promise<{ id: string; runId: string; content: string; blocked: boolean } | null> {
    const value = parseJson(await this.native.queuedConversationMessage(this.id, thread));
    if (value === null) return null;
    const item = object(value), message = object(item.message), request = object(message.request);
    if (message.thread_id !== thread || typeof item.blocked !== "boolean") throw new Error("Invalid queued message");
    return { id: string(request.message_id), runId: string(request.run_id), content: string(request.content), blocked: item.blocked };
  }
  async recoveredDraft(thread: string): Promise<string | null> {
    return this.native.recoveredConversationDraft(this.id, thread);
  }
  async restoreBlocked(messageId: string): Promise<string> {
    return this.native.restoreBlockedMessage(this.id, messageId);
  }
  async watch(run: string): Promise<void> {
    if (this.closed) return;
    const old = this.watchId;
    this.watchId = `${this.id}-${++this.generation}`;
    const id = this.watchId;
    if (old !== null) await this.native.stopConversationRunWatch(old);
    if (this.closed || this.watchId !== id) return;
    // Replay from zero builds the same visible state after navigation/restart.
    await this.native.watchConversationRun(this.id, run, "0", id);
    if (this.closed || this.watchId !== id) await this.native.stopConversationRunWatch(id);
  }
  async stopWatching(): Promise<void> {
    const id = this.watchId;
    this.watchId = null;
    if (id !== null) await this.native.stopConversationRunWatch(id);
  }
  async close(): Promise<void> {
    this.closed = true;
    this.subscription?.remove();
    this.subscription = null;
    await this.stopWatching();
    await this.native.closeConversations(this.id);
  }
}
