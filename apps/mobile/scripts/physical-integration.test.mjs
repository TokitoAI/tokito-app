import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const mobileDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repoDir = path.resolve(mobileDir, "../..");

const read = (relativePath) => readFile(path.join(repoDir, relativePath), "utf8");

test("Tokito owns the only production launcher", async () => {
  const [appConfig, physicalManifest] = await Promise.all([
    read("apps/mobile/app.json"),
    read("physical-runtime/app/src/main/AndroidManifest.xml"),
  ]);

  const expo = JSON.parse(appConfig).expo;
  assert.equal(expo.name, "Tokito");
  assert.equal(expo.slug, "tokito");
  assert.equal(expo.scheme, "tokito");
  assert.equal(expo.android.package, "dev.tokito.app");
  assert.doesNotMatch(physicalManifest, /android\.intent\.action\.MAIN/);
  assert.doesNotMatch(physicalManifest, /android\.intent\.category\.LAUNCHER/);
  assert.match(physicalManifest, /android:exported="false"/);
});

test("Physical runtime is embedded as libraries in the Tokito APK", async () => {
  const [plugin, physicalGradle] = await Promise.all([
    read("apps/mobile/plugins/withTokitoPhysical.js"),
    read("physical-runtime/app/build.gradle.kts"),
  ]);

  assert.match(plugin, /implementation project\(':tokito-physical-shell'\)/);
  for (const module of [
    "physical-runtime-core",
    "physical-ar-runtime",
    "measurement-runtime",
    "voice-runtime",
    "dialogue-runtime",
    "tokito-physical-shell",
  ]) {
    assert.match(plugin, new RegExp(`include ':${module}'`));
  }
  assert.match(physicalGradle, /id\("com\.android\.library"\)/);
  assert.doesNotMatch(physicalGradle, /applicationId\s*=/);
});

test("Expo bridge remains a low-rate control plane", async () => {
  const [bridge, bridgeTypes] = await Promise.all([
    read("apps/mobile/modules/tokito-physical/android/src/main/java/dev/tokito/physical/bridge/TokitoPhysicalModule.kt"),
    read("apps/mobile/modules/tokito-physical/src/index.ts"),
  ]);

  for (const method of ["capabilities", "openSession"]) {
    assert.match(bridge, new RegExp(`AsyncFunction\\("${method}"\\)`));
    assert.match(bridgeTypes, new RegExp(`${method}\\(`));
  }
  for (const forbidden of ["frame", "bitmap", "audioBuffer", "tensor", "trackingPose"]) {
    assert.doesNotMatch(bridgeTypes, new RegExp(forbidden, "i"));
  }
});
