import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const main = path.join(
  root,
  "apps/mobile/modules/tokito-companion-ffi/android/src/main",
);
const kotlinRelative = "java/uniffi/tokito_companion_ffi/tokito_companion_ffi.kt";
const jniRoot = path.join(main, "jniLibs");

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

const libraries = {};
for (const abi of fs.existsSync(jniRoot) ? fs.readdirSync(jniRoot).sort() : []) {
  const relative = `jniLibs/${abi}/libtokito_companion_ffi.so`;
  const absolute = path.join(main, relative);
  if (fs.existsSync(absolute)) libraries[relative] = sha256(absolute);
}

if (Object.keys(libraries).length === 0) {
  throw new Error(`no Android UniFFI library found under ${jniRoot}`);
}
const kotlin = path.join(main, kotlinRelative);
if (!fs.existsSync(kotlin)) {
  throw new Error(`generated Kotlin binding is missing: ${kotlin}`);
}

const manifest = {
  schema: 1,
  kotlin: { path: kotlinRelative, sha256: sha256(kotlin) },
  libraries,
};
fs.writeFileSync(
  path.join(main, "ffi-artifact-manifest.json"),
  `${JSON.stringify(manifest, null, 2)}\n`,
);
