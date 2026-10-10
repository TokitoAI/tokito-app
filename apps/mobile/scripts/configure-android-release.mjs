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
 *   TOKITO_RELEASE_SIGNING_DIR (private temporary directory; caller cleans up)
 */
import { writeFileSync, readFileSync, existsSync } from "node:fs";
import { join } from "node:path";

const root = process.cwd();
const gradlePath = join(root, "android", "app", "build.gradle");
const propsPath = join(root, "android", "gradle.properties");

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
requireEnv("ANDROID_UPLOAD_STORE_PASSWORD");
requireEnv("ANDROID_UPLOAD_KEY_ALIAS");
requireEnv("ANDROID_UPLOAD_KEY_PASSWORD");
const signingDir = requireEnv("TOKITO_RELEASE_SIGNING_DIR");
const keystoreFile = join(signingDir, "upload.keystore");

writeFileSync(keystoreFile, Buffer.from(b64, "base64"), { mode: 0o600 });
console.log("configure-android-release: wrote upload keystore");

const marker = "// tokito-companion: play-quality upload signing";
let gradle = readFileSync(gradlePath, "utf8");
if (gradle.includes(marker)) {
  if (!gradle.includes("storePassword System.getenv('ANDROID_UPLOAD_STORE_PASSWORD')")) {
    console.error("configure-android-release: stale signing configuration — run a clean Expo prebuild");
    process.exit(1);
  }
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
            storeFile file(System.getenv('TOKITO_RELEASE_SIGNING_DIR') + '/upload.keystore')
            storePassword System.getenv('ANDROID_UPLOAD_STORE_PASSWORD')
            keyAlias System.getenv('ANDROID_UPLOAD_KEY_ALIAS')
            keyPassword System.getenv('ANDROID_UPLOAD_KEY_PASSWORD')
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

// Only non-secret optimization properties belong in the generated checkout.
let props = existsSync(propsPath) ? readFileSync(propsPath, "utf8") : "";
if (/^tokitoUpload(?:StorePassword|KeyAlias|KeyPassword)=/m.test(props)) {
  console.error("configure-android-release: stale persisted signing credentials — run a clean Expo prebuild");
  process.exit(1);
}
const propLines = [
  "android.enableMinifyInReleaseBuilds=true",
  "android.enableShrinkResourcesInReleaseBuilds=true",
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
console.log("configure-android-release: configured release optimization");
