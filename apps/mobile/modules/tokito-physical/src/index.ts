import { requireNativeModule } from "expo-modules-core";

export type PhysicalSession = {
  id: string;
  state: "created";
};

export type PhysicalSessionContext = {
  projectId?: string;
  designId?: string;
};

export type PhysicalCapabilities = {
  contractVersion: string;
  deviceProfile: string;
  operations: string[];
};

type TokitoPhysicalNative = {
  capabilities(): Promise<PhysicalCapabilities>;
  openSession(context?: PhysicalSessionContext): Promise<PhysicalSession>;
};

export const TokitoPhysical = requireNativeModule<TokitoPhysicalNative>("TokitoPhysical");
