import assert from "node:assert/strict";
import { readFile, mkdtemp, rm, mkdir, writeFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const withTokitoPhysical = require("../plugins/withTokitoPhysical.js");
const { optionalCamera, splitSplashStyles } = withTokitoPhysical;
const { XML } = require("expo/config-plugins");

test("release signing script forces optimization and remains idempotent", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "tokito-release-"));
  try {
    await mkdir(path.join(root, "android/app"), { recursive: true });
    await writeFile(path.join(root, "android/app/build.gradle"), `android {
  signingConfigs {
    debug {
      storeFile file('debug.keystore')
    }
  }
  buildTypes {
    release {
      signingConfig signingConfigs.debug
      minifyEnabled enableMinifyInReleaseBuilds
    }
  }
}`);
    await writeFile(path.join(root, "android/gradle.properties"), "android.enableMinifyInReleaseBuilds=false\n");
    const env = { ...process.env,
      ANDROID_UPLOAD_KEYSTORE_BASE64: Buffer.from("test-only-keystore").toString("base64"),
      ANDROID_UPLOAD_STORE_PASSWORD: "test-only", ANDROID_UPLOAD_KEY_ALIAS: "test-only",
      ANDROID_UPLOAD_KEY_PASSWORD: "test-only",
    };
    const script = fileURLToPath(new URL("./configure-android-release.mjs", import.meta.url));
    const run = () => spawnSync(process.execPath, [script], { cwd: root, env, encoding: "utf8" });
    const first = run();
    assert.equal(first.status, 0, first.stderr);
    const gradle = await readFile(path.join(root, "android/app/build.gradle"), "utf8");
    const properties = await readFile(path.join(root, "android/gradle.properties"), "utf8");
    assert.match(gradle, /signingConfig signingConfigs\.release/);
    assert.equal(properties.match(/^android\.enableMinifyInReleaseBuilds=true$/gm)?.length, 1);
    assert.equal(properties.match(/^android\.enableShrinkResourcesInReleaseBuilds=true$/gm)?.length, 1);
    assert.equal(run().status, 0);
    assert.equal(await readFile(path.join(root, "android/app/build.gradle"), "utf8"), gradle);
    assert.equal(await readFile(path.join(root, "android/gradle.properties"), "utf8"), properties);
    assert.equal(spawnSync(process.execPath, [script], {
      cwd: root, env: { ...env, ANDROID_UPLOAD_KEY_PASSWORD: "" }, encoding: "utf8",
    }).status, 1, "missing signing credentials must fail instead of selecting the debug key");
  } finally { await rm(root, { recursive: true, force: true }); }
});

test("final APK release optimization is generated without independently shrinking libraries", async () => {
  const config = withTokitoPhysical({ name: "Tokito", slug: "tokito" });
  const result = await config.mods.android.gradleProperties({ ...config, modRequest: {}, modResults: [
    { type: "property", key: "android.enableMinifyInReleaseBuilds", value: "false" },
  ] });
  for (const key of ["android.enableMinifyInReleaseBuilds", "android.enableShrinkResourcesInReleaseBuilds"]) {
    assert.equal(result.modResults.filter(item => item.key === key).length, 1);
    assert.equal(result.modResults.find(item => item.key === key).value, "true");
  }
  const gradle = await config.mods.android.appBuildGradle({ ...config, modRequest: {}, modResults: {
    language: "groovy", contents: `dependencies {}\nproguardFiles getDefaultProguardFile('proguard-android.txt'), "proguard-rules.pro"`,
  } });
  assert.match(gradle.modResults.contents, /proguard-android-optimize\.txt/);
  assert.doesNotMatch(gradle.modResults.contents, /proguard-android\.txt/);
  const repeated = await config.mods.android.appBuildGradle({ ...config, modRequest: {}, modResults: gradle.modResults });
  assert.equal(repeated.modResults.contents, gradle.modResults.contents);
});

test("final generation mod writes the API-qualified splash after Expo's styles", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "tokito-resources-"));
  try {
    const baseFile = path.join(root, "app/src/main/res/values/styles.xml");
    const qualifiedFile = path.join(root, "app/src/main/res/values-v33/styles.xml");
    await XML.writeXMLAsync({ path: baseFile, xml: { resources: { style: [{
      $: { name: "Theme.App.SplashScreen", parent: "Theme.SplashScreen" },
      item: [
        { $: { name: "postSplashScreenTheme" }, _: "@style/AppTheme" },
        { $: { name: "android:windowSplashScreenBehavior" }, _: "icon_preferred" },
      ],
    }] } } });
    const config = withTokitoPhysical({ name: "Tokito", slug: "tokito" });
    assert.equal(config.mods.android.styles, undefined, "must not run before Expo adds splash attributes");
    const run = () => config.mods.android.finalized({ ...config,
      modRequest: { platformProjectRoot: root, introspect: false },
    });
    await run();
    const base = await XML.readXMLAsync({ path: baseFile });
    const qualified = await XML.readXMLAsync({ path: qualifiedFile });
    assert.equal(base.resources.style[0].item.length, 1);
    assert.equal(qualified.resources.style[0].item.length, 2);
    await run();
    assert.deepEqual(await XML.readXMLAsync({ path: qualifiedFile }), qualified);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("camera capability is optional and generation is idempotent", () => {
  const manifest = { manifest: { "uses-feature": [{ $: { "android:name": "android.hardware.bluetooth", "android:required": "true" } }] } };
  optionalCamera(manifest);
  const once = structuredClone(manifest);
  optionalCamera(manifest);
  assert.deepEqual(manifest, once);
  assert.equal(manifest.manifest["uses-feature"][1].$["android:required"], "false");
  assert.equal(manifest.manifest["uses-feature"][0].$["android:required"], "true");
  manifest.manifest["uses-feature"][1].$["android:required"] = "true";
  optionalCamera(manifest);
  assert.deepEqual(manifest, once);
});

test("API 33 splash behavior preserves the complete theme without leaking to older APIs", () => {
  const splash = { $: { name: "Theme.App.SplashScreen", parent: "Theme.SplashScreen" }, item: [
    { $: { name: "windowSplashScreenBackground" }, _: "@color/splashscreen_background" },
    { $: { name: "windowSplashScreenAnimatedIcon" }, _: "@drawable/splashscreen_logo" },
    { $: { name: "postSplashScreenTheme" }, _: "@style/AppTheme" },
    { $: { name: "android:windowSplashScreenBehavior" }, _: "icon_preferred" },
  ] };
  const expected = structuredClone(splash);
  const base = { resources: { style: [splash, { $: { name: "AppTheme" } }] } };
  const other = { $: { name: "Unrelated" } };
  const qualified = { resources: { style: [other] } };
  splitSplashStyles(base, qualified);
  assert.deepEqual(qualified.resources.style, [other, expected]);
  assert.equal(base.resources.style[0].item.length, 3);
  const once = structuredClone({ base, qualified });
  splitSplashStyles(base, qualified);
  assert.deepEqual({ base, qualified }, once);
  base.resources.style[0].item[0]._ = "@color/changed";
  splitSplashStyles(base, qualified);
  assert.equal(qualified.resources.style[1].item[0]._, "@color/changed");
});

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
  assert.doesNotMatch(physicalGradle, /isMinifyEnabled\s*=\s*true/,
    "An embedded library must retain classes reached from the Expo bridge; shrink only in the final APK");
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
