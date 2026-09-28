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

export type ModelCapability = "dialogue" | "vision";

export type InstalledModelBundle = {
  capability: ModelCapability;
  modelId: string;
  modelRevision: string;
};

export type ModelBundleStatus = {
  capability: ModelCapability;
  modelId: string | null;
  modelRevision: string | null;
};

type TokitoPhysicalNative = {
  capabilities(): Promise<PhysicalCapabilities>;
  openSession(context?: PhysicalSessionContext): Promise<PhysicalSession>;
  pendingSyncSnapshots(): Promise<PhysicalSyncSnapshot[]>;
  markSyncEnqueued(sessionId: string): Promise<void>;
  modelBundles(): Promise<ModelBundleStatus[]>;
  installModelBundle(capability: ModelCapability, uri: string): Promise<InstalledModelBundle>;
  rollbackModelBundle(capability: ModelCapability): Promise<InstalledModelBundle>;
};

export const TokitoPhysical = requireNativeModule<TokitoPhysicalNative>("TokitoPhysical");
