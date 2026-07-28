import { readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));

/** @param {string} dir @param {string[]} out */
function walk(dir, out) {
  for (const name of readdirSync(dir)) {
    if (name === "node_modules" || name === "coverage") continue;
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) {
      walk(full, out);
    } else if (name.endsWith(".js") || name.endsWith(".jsx")) {
      out.push(full);
    }
  }
}

const found = [];
walk(root, found);

if (found.length > 0) {
  console.error("JavaScript sources are forbidden under apps/mobile. Offenders:");
  for (const p of found) console.error(`  ${relative(root, p)}`);
  process.exit(1);
}

console.log("forbid-js: ok (no .js/.jsx under apps/mobile)");
