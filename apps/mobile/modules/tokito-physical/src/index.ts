import { requireNativeModule } from "expo-modules-core";

export type PhysicalSession = {
  id: string;
  state: "created";
};

export type PhysicalCapabilities = {
  contractVersion: string;
  deviceProfile: string;
  operations: string[];
};

type TokitoPhysicalNative = {
  capabilities(): Promise<PhysicalCapabilities>;
  openSession(): Promise<PhysicalSession>;
};

export const TokitoPhysical = requireNativeModule<TokitoPhysicalNative>("TokitoPhysical");
