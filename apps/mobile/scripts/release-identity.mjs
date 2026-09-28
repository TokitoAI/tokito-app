import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const mobileDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repoDir = path.resolve(mobileDir, "../..");

export function validateReleaseIdentity({
  packageVersion,
  appVersion,
  versionCode,
  releaseTag,
  previousVersionCodes = [],
}) {
  const failures = [];
  if (packageVersion !== appVersion) {
    failures.push(`package.json version ${packageVersion} != app.json version ${appVersion}`);
  }
  if (!Number.isSafeInteger(versionCode) || versionCode <= 0) {
    failures.push(`android.versionCode must be a positive safe integer, got ${versionCode}`);
  }
  if (releaseTag) {
    const match = /^v(\d+\.\d+\.\d+)(?:[.-][0-9A-Za-z.-]+)?$/.exec(releaseTag);
    if (!match) {
      failures.push(`release tag ${releaseTag} is not a supported vX.Y.Z tag`);
    } else if (match[1] !== appVersion) {
      failures.push(`release tag base ${match[1]} != app version ${appVersion}`);
    }
    const previousMax = previousVersionCodes.length > 0 ? Math.max(...previousVersionCodes) : 0;
    if (versionCode <= previousMax) {
      failures.push(`android.versionCode ${versionCode} must exceed prior released maximum ${previousMax}`);
    }
  }
  return failures;
}

function releasedVersionCodes(currentTag, currentPackage) {
  let tags = [];
  try {
    tags = execFileSync("git", ["tag", "--list", "v*"], {
      cwd: repoDir,
      encoding: "utf8",
    })
      .split(/\r?\n/)
      .filter((tag) => tag && tag !== currentTag);
  } catch (error) {
    throw new Error(`could not enumerate release tags: ${error.message}`);
  }
  const codes = [];
  for (const tag of tags) {
    try {
      const raw = execFileSync("git", ["show", `${tag}:apps/mobile/app.json`], {
        cwd: repoDir,
        encoding: "utf8",
        stdio: ["ignore", "pipe", "ignore"],
      });
      const android = JSON.parse(raw)?.expo?.android;
      if (android?.package !== currentPackage) continue;
      const code = android?.versionCode;
      if (Number.isSafeInteger(code) && code > 0) codes.push(code);
    } catch {
      // Early tags predate the mobile app or its Expo metadata.
    }
  }
  return codes;
}

function main() {
  const tagIndex = process.argv.indexOf("--release-tag");
  const releaseTag = tagIndex >= 0 ? process.argv[tagIndex + 1] : process.env.RELEASE_TAG;
  if (tagIndex >= 0 && !releaseTag) throw new Error("--release-tag requires a value");
  const packageJson = JSON.parse(fs.readFileSync(path.join(mobileDir, "package.json"), "utf8"));
  const appJson = JSON.parse(fs.readFileSync(path.join(mobileDir, "app.json"), "utf8"));
  const failures = validateReleaseIdentity({
    packageVersion: packageJson.version,
    appVersion: appJson.expo.version,
    versionCode: appJson.expo.android.versionCode,
    releaseTag,
    previousVersionCodes: releaseTag
      ? releasedVersionCodes(releaseTag, appJson.expo.android.package)
      : [],
  });
  if (failures.length > 0) {
    for (const failure of failures) console.error(`ERROR: ${failure}`);
    process.exitCode = 1;
    return;
  }
  console.log(
    `release identity valid: version=${appJson.expo.version} versionCode=${appJson.expo.android.versionCode}${releaseTag ? ` tag=${releaseTag}` : ""}`,
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main();
