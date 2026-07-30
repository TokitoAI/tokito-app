import { Platform } from "react-native";
import { NavigationBar } from "expo-navigation-bar";
import { tokens } from "./theme";

/**
 * Hide the Android system navigation bar (gesture / 3-button) while the app
 * is foregrounded and keep its controls legible if Android reveals it.
 *
 * **Why:** Product chrome should own the bottom edge; showing the OS bar on a
 * dark CAD-review UI looks unfinished.
 * **How to apply:** Call once at app root mount. Native defaults also set via
 * `app.json` `expo-navigation-bar` plugin (prebuild).
 */
export async function applySystemChrome(): Promise<void> {
  if (Platform.OS !== "android") {
    return;
  }
  try {
    NavigationBar.setHidden(true);
    NavigationBar.setStyle(tokens.bg === "#0C0D10" ? "dark" : "auto");
  } catch {
    // Expo Go / missing native module — ignore; release builds include the package.
  }
}
