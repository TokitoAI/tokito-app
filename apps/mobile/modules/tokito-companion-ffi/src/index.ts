import { requireNativeModule } from "expo-modules-core";

export type NativeEventSubscription = { remove(): void };

export type SyncNativeEventMap = {
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

type TokitoCompanionFfiNative = {
  configureBoardSymbolService(baseUrl: string | null, token: string | null): Promise<void>;
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
