import { requireNativeModule } from "expo-modules-core";

export type NativeEventSubscription = { remove(): void };

export type SyncNativeEventMap = {
  onConversationChange: { sessionId: string; watchId: string; payloadJson: string };
  onSyncStatus: { subscriptionId: string; status: string };
  onSyncBootstrap: { subscriptionId: string; cursor: string; resourcesJson: string };
  onSyncEvent: { subscriptionId: string; eventJson: string };
  onSyncConflict: {
    subscriptionId: string;
    commandJson: string;
    currentVersion: string;
    currentPayloadJson: string;
  };
  onSyncError: { subscriptionId: string; message: string };
};

export type NativeSession = {
  token: string;
  email: string;
  plan: string;
};

export type NativeAccount = {
  email: string;
  plan: string;
};

export type TokitoCompanionFfiNative = {
  openConversations(baseUrl: string, token: string, sessionId: string): Promise<string>;
  closeConversations(sessionId: string): Promise<void>;
  conversationThreads(sessionId: string, project: string, design: string): Promise<string>;
  createConversation(sessionId: string, project: string, design: string, title: string): Promise<string>;
  conversationHistory(sessionId: string, project: string, design: string, thread: string, before: string | null): Promise<string>;
  cachedConversationHistory(sessionId: string, thread: string, after: string): Promise<string>;
  sendConversationMessage(sessionId: string, project: string, design: string, thread: string, content: string, version: string, kind: string): Promise<string>;
  cancelConversationRun(sessionId: string, run: string): Promise<string>;
  deleteConversation(sessionId: string, project: string, design: string, thread: string): Promise<void>;
  discardBlockedMessage(sessionId: string, messageId: string): Promise<boolean>;
  blockedConversationMessages(sessionId: string, thread: string): Promise<string>;
  watchConversationRun(sessionId: string, run: string, after: string, watchId: string): Promise<string>;
  stopConversationRunWatch(watchId: string): Promise<void>;
  baseUrl(baseUrl: string): Promise<string>;
  otpRequest(baseUrl: string, email: string): Promise<void>;
  otpVerify(baseUrl: string, email: string, code: string): Promise<NativeSession>;
  oauthExchange(baseUrl: string, code: string): Promise<NativeSession>;
  me(baseUrl: string, token: string): Promise<NativeAccount>;
  listProjects(baseUrl: string, token: string): Promise<
    Array<{ id: string; name: string; slug: string; updated_at: string }>
  >;
  getProject(baseUrl: string, token: string, projectId: string): Promise<string>;
  listProposals(
    baseUrl: string,
    token: string,
    projectId: string,
    status: string | null,
  ): Promise<Array<Record<string, unknown>>>;
  decideProposal(
    baseUrl: string,
    token: string,
    projectId: string,
    proposalId: string,
    decision: string,
    note: string | null,
  ): Promise<Record<string, unknown>>;
  getArtifact(
    baseUrl: string,
    token: string,
    projectId: string,
    name: string,
  ): Promise<{ contentType: string; bodyBase64: string }>;
  startSync(baseUrl: string, token: string, projectId: string | null): Promise<string>;
  stopSync(subscriptionId: string): Promise<void>;
  enqueueSyncCommand(
    subscriptionId: string,
    projectId: string,
    designId: string | null,
    resource: string,
    payloadJson: string,
  ): Promise<string>;
  resolveSyncConflict(
    subscriptionId: string,
    commandId: string,
    keepLocal: boolean,
  ): Promise<void>;
  addListener<K extends keyof SyncNativeEventMap>(
    eventName: K,
    listener: (event: SyncNativeEventMap[K]) => void,
  ): NativeEventSubscription;
};

/** UniFFI-backed Expo native module (Android). Throws if not linked into the binary. */
export function getTokitoCompanionFfi(): TokitoCompanionFfiNative {
  return requireNativeModule<TokitoCompanionFfiNative>("TokitoCompanionFfi");
}

export function isTokitoCompanionFfiLinked(): boolean {
  try {
    getTokitoCompanionFfi();
    return true;
  } catch {
    return false;
  }
}
