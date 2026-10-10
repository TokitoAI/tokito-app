import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import test from "node:test";

const root = fileURLToPath(new URL("../../../", import.meta.url));
const read = (path) => readFileSync(join(root, path), "utf8");
const signingScript = join(root, "apps/mobile/scripts/configure-android-release.mjs");

test("publication requires approval and the complete unified CI dependency chain", () => {
  const publication = read(".buildkite/release.yml");
  assert.match(publication, /queue: release/);
  assert.match(publication, /depends_on:\s*\n\s*- approve-release\s*\n\s*- unified-android/);
  const pipeline = read(".buildkite/pipeline.yml");
  const unified = pipeline.slice(pipeline.indexOf("key: unified-android"));
  for (const gate of ["rust", "mobile", "physical-contracts", "physical-android"]) {
    assert.match(unified, new RegExp(`- ${gate}(?:\\r?\\n|$)`));
  }
  assert.doesNotMatch(publication, /allow_dependency_failure/);
});

test("release credentials and expected certificate are isolated to the release wrapper", () => {
  const wrapper = read(".buildkite/steps/release-android.sh");
  for (const secret of ["GH_TOKEN", "ANDROID_UPLOAD_CERT_SHA256", "ANDROID_UPLOAD_KEYSTORE_BASE64",
    "ANDROID_UPLOAD_STORE_PASSWORD", "ANDROID_UPLOAD_KEY_ALIAS", "ANDROID_UPLOAD_KEY_PASSWORD"]) {
    assert.match(wrapper, new RegExp(`^  ${secret}$`, "m"));
  }
  assert.match(wrapper, /CI_PASS_ENV=.*required/);
  for (const name of ["rust", "mobile", "physical-contracts", "physical-android", "unified-android"]) {
    assert.doesNotMatch(read(`.buildkite/steps/${name}.sh`), /GH_TOKEN|ANDROID_UPLOAD_/);
  }
  const release = read(".buildkite/in-image/release-android.sh");
  assert.ok(release.indexOf("gh auth status") < release.indexOf("npm ci"));
  assert.match(release, /zipalign" -c -P 16 -v 4/);
  assert.match(release, /actual_cert,,.*ANDROID_UPLOAD_CERT_SHA256,,/);
  assert.match(release, /actual_aab_cert,,.*ANDROID_UPLOAD_CERT_SHA256,,/);
  assert.match(release, /gh release create.*--verify-tag --repo TokitoAI\/tokito-app/);
  assert.match(release, /trap .*TOKITO_RELEASE_SIGNING_DIR.* EXIT/);
});

test("release wrapper fails before Docker when publication credentials are missing", () => {
  const bash = process.platform === "win32" ? "C:/Program Files/Git/bin/bash.exe" : "bash";
  const base = {
    ...process.env,
    ANDROID_UPLOAD_KEYSTORE_BASE64: "fixture",
    ANDROID_UPLOAD_STORE_PASSWORD: "fixture",
    ANDROID_UPLOAD_KEY_ALIAS: "fixture",
    ANDROID_UPLOAD_KEY_PASSWORD: "fixture",
    ANDROID_UPLOAD_CERT_SHA256: "a".repeat(64),
    GH_TOKEN: "",
  };
  const run = spawnSync(bash, [".buildkite/steps/release-android.sh"], {
    cwd: root, env: base, encoding: "utf8",
  });
  assert.equal(run.status, 1, run.stderr);
  assert.match(run.stderr, /missing release secret: GH_TOKEN/);
  const badCert = spawnSync(bash, [".buildkite/steps/release-android.sh"], {
    cwd: root, env: { ...base, GH_TOKEN: "fixture", ANDROID_UPLOAD_CERT_SHA256: "invalid" }, encoding: "utf8",
  });
  assert.equal(badCert.status, 1, badCert.stderr);
  assert.match(badCert.stderr, /64-character hex digest/);
});

test("signing configuration never writes credentials or keystore into the checkout", () => {
  const dir = mkdtempSync(join(tmpdir(), "tokito-signing-test-"));
  try {
    const app = join(dir, "android", "app");
    const secrets = join(dir, "private");
    mkdirSync(app, { recursive: true });
    mkdirSync(secrets);
    writeFileSync(join(app, "build.gradle"), `android {
      signingConfigs { debug {
        storeFile file('debug.keystore')
      } }
      buildTypes { release {
        signingConfig signingConfigs.debug
      } }
    }`);
    const credentials = {
      ANDROID_UPLOAD_KEYSTORE_BASE64: Buffer.from("fixture keystore").toString("base64"),
      ANDROID_UPLOAD_STORE_PASSWORD: "fixture-store-secret",
      ANDROID_UPLOAD_KEY_ALIAS: "fixture-alias",
      ANDROID_UPLOAD_KEY_PASSWORD: "fixture-key-secret",
      TOKITO_RELEASE_SIGNING_DIR: secrets,
    };
    for (let i = 0; i < 2; i++) {
      const run = spawnSync(process.execPath, [signingScript], {
        cwd: dir, env: { ...process.env, ...credentials }, encoding: "utf8",
      });
      assert.equal(run.status, 0, run.stderr);
    }
    const gradle = readFileSync(join(app, "build.gradle"), "utf8");
    const properties = readFileSync(join(dir, "android", "gradle.properties"), "utf8");
    for (const value of Object.values(credentials)) {
      assert.ok(!gradle.includes(value));
      assert.ok(!properties.includes(value));
    }
    assert.match(gradle, /System.getenv\('ANDROID_UPLOAD_STORE_PASSWORD'\)/);
    assert.match(gradle, /signingConfig signingConfigs.release/);
    assert.match(properties, /android.enableMinifyInReleaseBuilds=true/);
    assert.match(properties, /android.enableShrinkResourcesInReleaseBuilds=true/);
    assert.equal(readFileSync(join(secrets, "upload.keystore"), "utf8"), "fixture keystore");
    assert.equal(existsSync(join(app, "tokito-companion-upload.keystore")), false);
    assert.equal((gradle.match(/storePassword System.getenv/g) ?? []).length, 1);
    writeFileSync(join(dir, "android", "gradle.properties"), "tokitoUploadStorePassword=legacy-secret\n");
    const stale = spawnSync(process.execPath, [signingScript], {
      cwd: dir, env: { ...process.env, ...credentials }, encoding: "utf8",
    });
    assert.equal(stale.status, 1);
    assert.match(stale.stderr, /stale persisted signing credentials/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
