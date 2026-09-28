import { requireNativeModule } from "expo-modules-core";

export type PhysicalSession = {
  id: string;
  state: "created";
};

export type PhysicalSessionContext = {
  projectId?: string;
  designId?: string;
  contextRevision?: string;
};

export type PhysicalCapabilities = {
  contractVersion: string;
  deviceProfile: string;
  operations: string[];
};

export type PhysicalSyncSnapshot = {
  sessionId: string;
  projectId: string;
  designId: string | null;
  revision: number;
  payloadJson: string;
};

type TokitoPhysicalNative = {
  capabilities(): Promise<PhysicalCapabilities>;
  openSession(context?: PhysicalSessionContext): Promise<PhysicalSession>;
  pendingSyncSnapshots(): Promise<PhysicalSyncSnapshot[]>;
  markSyncEnqueued(sessionId: string): Promise<void>;
};

export const TokitoPhysical = requireNativeModule<TokitoPhysicalNative>("TokitoPhysical");
