/**
 * After `expo prebuild`, configure Play-quality release signing + minify.
 *
 * Reads credentials from env (CI secrets) or fails closed — never falls back
 * to the debug keystore for release builds.
 *
 * Required env:
 *   ANDROID_UPLOAD_KEYSTORE_BASE64
 *   ANDROID_UPLOAD_STORE_PASSWORD
 *   ANDROID_UPLOAD_KEY_ALIAS
 *   ANDROID_UPLOAD_KEY_PASSWORD
 */
import { mkdirSync, writeFileSync, readFileSync, existsSync } from "node:fs";
import { join } from "node:path";

const root = process.cwd();
const gradlePath = join(root, "android", "app", "build.gradle");
const propsPath = join(root, "android", "gradle.properties");
const keystoreDir = join(root, "android", "app");
const keystoreFile = join(keystoreDir, "tokito-companion-upload.keystore");

function requireEnv(name) {
  const v = process.env[name];
  if (v === undefined || v.trim() === "") {
    console.error(`configure-android-release: missing required env ${name}`);
    process.exit(1);
  }
  return v;
}

if (!existsSync(gradlePath)) {
  console.error("configure-android-release: android/app/build.gradle missing — run expo prebuild first");
  process.exit(1);
}

const b64 = requireEnv("ANDROID_UPLOAD_KEYSTORE_BASE64");
const storePassword = requireEnv("ANDROID_UPLOAD_STORE_PASSWORD");
const keyAlias = requireEnv("ANDROID_UPLOAD_KEY_ALIAS");
const keyPassword = requireEnv("ANDROID_UPLOAD_KEY_PASSWORD");

mkdirSync(keystoreDir, { recursive: true });
writeFileSync(keystoreFile, Buffer.from(b64, "base64"));
console.log("configure-android-release: wrote upload keystore");

const marker = "// tokito-companion: play-quality upload signing";
let gradle = readFileSync(gradlePath, "utf8");
if (gradle.includes(marker)) {
  console.log("configure-android-release: gradle already configured");
} else {
  // Inject release signingConfigs after debug block (Expo-generated).
  if (!/signingConfigs\s*\{[\s\S]*?debug\s*\{/.test(gradle)) {
    console.error("configure-android-release: could not find signingConfigs.debug");
    process.exit(1);
  }
  gradle = gradle.replace(
    /(signingConfigs\s*\{[\s\S]*?debug\s*\{[\s\S]*?\n\s*\})/,
    `$1
        release {
            ${marker}
            storeFile file('tokito-companion-upload.keystore')
            storePassword project.findProperty('tokitoUploadStorePassword')
            keyAlias project.findProperty('tokitoUploadKeyAlias')
            keyPassword project.findProperty('tokitoUploadKeyPassword')
        }`,
  );

  // Point buildTypes.release at signingConfigs.release (not debug).
  if (!/buildTypes\s*\{[\s\S]*?release\s*\{[\s\S]*?signingConfig\s+signingConfigs\.debug/.test(gradle)) {
    // Expo sometimes omits an explicit signingConfig line — force one.
    gradle = gradle.replace(
      /(buildTypes\s*\{[\s\S]*?release\s*\{)/,
      `$1
            signingConfig signingConfigs.release
            ${marker}
`,
    );
  } else {
    gradle = gradle.replace(
      /(buildTypes\s*\{[\s\S]*?release\s*\{[\s\S]*?)signingConfig\s+signingConfigs\.debug/,
      `$1signingConfig signingConfigs.release\n            ${marker}`,
    );
  }

  writeFileSync(gradlePath, gradle);
  console.log("configure-android-release: patched app/build.gradle for upload signing + minify");
}

// Passwords via gradle.properties (generated; gitignored android/ tree).
let props = existsSync(propsPath) ? readFileSync(propsPath, "utf8") : "";
const propLines = [
  "android.enableMinifyInReleaseBuilds=true",
  "android.enableShrinkResourcesInReleaseBuilds=true",
  `tokitoUploadStorePassword=${storePassword}`,
  `tokitoUploadKeyAlias=${keyAlias}`,
  `tokitoUploadKeyPassword=${keyPassword}`,
];
for (const line of propLines) {
  const key = line.split("=")[0];
  if (props.includes(`${key}=`)) {
    props = props.replace(new RegExp(`^${key}=.*$`, "m"), line);
  } else {
    props += `\n${line}\n`;
  }
}
writeFileSync(propsPath, props);
console.log("configure-android-release: wrote signing properties");
