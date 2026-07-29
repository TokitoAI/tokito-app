import { Platform } from "react-native";
import * as NavigationBar from "expo-navigation-bar";
import { tokens } from "./theme";

/**
 * Hide the Android system navigation bar (gesture / 3-button) while the app
 * is foregrounded. Swipe from the edge temporarily reveals it (overlay).
 *
 * **Why:** Product chrome should own the bottom edge; showing the OS bar on a
 * dark CAD-review UI looks unfinished.
 * **How to apply:** Call once at app root mount. Native defaults also set via
 * `app.json` `androidNavigationBar` + `expo-navigation-bar` plugin (prebuild).
 */
export async function applySystemChrome(): Promise<void> {
  if (Platform.OS !== "android") {
    return;
  }
  try {
    await NavigationBar.setVisibilityAsync("hidden");
    await NavigationBar.setBehaviorAsync("overlay-swipe");
    await NavigationBar.setBackgroundColorAsync(tokens.bg);
    await NavigationBar.setButtonStyleAsync("light");
  } catch {
    // Expo Go / missing native module — ignore; release builds include the package.
  }
}
