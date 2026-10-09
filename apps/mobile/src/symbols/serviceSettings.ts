import * as SecureStore from "expo-secure-store";
import { getTokitoCompanionFfi } from "../../modules/tokito-companion-ffi/src";

const KEY = "tokito.boardSymbolService.v1";
export type SymbolServiceSettings = { baseUrl: string; token: string };

export async function restoreSymbolService(): Promise<void> {
  const saved = await SecureStore.getItemAsync(KEY);
  if (saved === null) return;
  const config = JSON.parse(saved) as SymbolServiceSettings;
  await getTokitoCompanionFfi().configureBoardSymbolService(config.baseUrl, config.token);
}

export async function saveSymbolService(config: SymbolServiceSettings): Promise<void> {
  const baseUrl = config.baseUrl.trim();
  const token = config.token.trim();
  // Native/Rust repeat validation before creating a transport.
  const url = new URL(baseUrl);
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash || url.pathname !== "/") {
    throw new Error("Enter an HTTPS service address without a path.");
  }
  if (token.length < 32 || token.length > 512) throw new Error("Enter the tenant API key supplied by your operator.");
  const previous = await SecureStore.getItemAsync(KEY);
  await SecureStore.setItemAsync(KEY, JSON.stringify({ baseUrl, token }));
  try {
    await getTokitoCompanionFfi().configureBoardSymbolService(baseUrl, token);
  } catch (cause) {
    if (previous === null) await SecureStore.deleteItemAsync(KEY);
    else await SecureStore.setItemAsync(KEY, previous);
    throw cause;
  }
}

export async function removeSymbolService(): Promise<void> {
  await getTokitoCompanionFfi().configureBoardSymbolService(null, null);
  await SecureStore.deleteItemAsync(KEY);
}
