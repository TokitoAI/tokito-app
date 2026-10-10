/** Fixed vocabulary only: never log chat text, identity, credentials or errors. */
const stages = ["prepare", "stopWatching", "startChat.native", "startChat.receipt", "sendChat.native", "sendChat.receipt", "receipt", "queued", "watch", "send"] as const;
export type ChatSendStage = typeof stages[number];

export function chatSendFailure(stage: ChatSendStage, error: unknown, sink: (line: string) => void = console.info): void {
  if (!stages.includes(stage)) throw new Error("Unknown chat send diagnostic stage");
  const category = error instanceof SyntaxError ? "parse" : error instanceof TypeError ? "type" : error instanceof Error ? "rejected" : "unknown";
  sink(`[TokitoChatSend] stage=${stage} status=failed category=${category}`);
}

export async function chatSendStep<T>(stage: ChatSendStage, work: () => Promise<T>, sink: (line: string) => void = console.info): Promise<T> {
  if (!stages.includes(stage)) throw new Error("Unknown chat send diagnostic stage");
  sink(`[TokitoChatSend] stage=${stage} status=begin`);
  try {
    const result = await work();
    sink(`[TokitoChatSend] stage=${stage} status=done`);
    return result;
  } catch (error) {
    chatSendFailure(stage, error, sink);
    throw error;
  }
}
