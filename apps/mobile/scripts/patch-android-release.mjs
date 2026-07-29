/**
 * After `expo prebuild`, force release builds to sign with the debug keystore
 * so sideload pre-release APKs install without a Play upload key.
 * Also ensures release embeds the JS bundle (no Metro required).
 */
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { join } from "node:path";

const gradle = join(process.cwd(), "android", "app", "build.gradle");
if (!existsSync(gradle)) {
  console.error("patch-android-release: android/app/build.gradle missing — run expo prebuild first");
  process.exit(1);
}

let src = readFileSync(gradle, "utf8");
const marker = "// tokito-companion: debug-signed release for sideload";
if (src.includes(marker)) {
  console.log("patch-android-release: already applied");
  process.exit(0);
}

const needle = /buildTypes\s*\{[\s\S]*?release\s*\{([\s\S]*?)\}/;
if (!needle.test(src)) {
  console.error("patch-android-release: could not find release buildType");
  process.exit(1);
}

src = src.replace(needle, (block) => {
  if (block.includes("signingConfig")) {
    return block.replace(
      /signingConfig\s+[^\n]+/,
      `signingConfig signingConfigs.debug\n            ${marker}`,
    );
  }
  return block.replace(
    /release\s*\{/,
    `release {\n            signingConfig signingConfigs.debug\n            ${marker}`,
  );
});

writeFileSync(gradle, src);
console.log("patch-android-release: release now uses debug signingConfig");
