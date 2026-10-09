import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';

const require = createRequire(import.meta.url);
const repairs = [
  {
    id: 'GHSA-vfj7-8cjw-p6xm', name: 'braces', version: '3.0.3',
    files: {
      'lib/parse.js': '719c5f3e8325fd0aca00b25156ffba56128953667bc3a89b57926ba1b7b44edb',
      'lib/compile.js': '6a58fa7e90d8627dcdecb2d4b354bc045ad978783a595db78496e0d94ff400b8',
      'lib/expand.js': '54b2fc840931ff159aa93cf767518507d00cc3f9f7cb832d849e9b0f8d79eb12',
      'lib/stringify.js': 'b1e090505c9e09830d1a3c5ccf51094b69b837abfd2c9357aa69920fb9a62b06',
    },
  },
  {
    id: 'GHSA-86w9-cpqp-85rv', name: 'node-forge', version: '1.4.0',
    files: { 'lib/rsa.js': '15987d3e5e0d4d53f1a46c6b9d4bbed6482106932a5cb12d14ae049ca62ed438' },
  },
];

/** Attest installed source, not just a checked-in patch or a package version. */
export function verifiedDependencyRepairs(report) {
  const verified = new Map();
  for (const repair of repairs) {
    try {
      const manifestPath = require.resolve(`${repair.name}/package.json`);
      if (JSON.parse(readFileSync(manifestPath)).version !== repair.version) continue;
      const root = path.dirname(manifestPath);
      const nodes = report?.vulnerabilities?.[repair.name]?.nodes;
      if (nodes && nodes.some(node => path.resolve(process.cwd(), node) !== root)) continue;
      if (!Object.entries(repair.files).every(([file, hash]) =>
        createHash('sha256').update(readFileSync(path.join(root, file))).digest('hex') === hash)) continue;
      verified.set(repair.id, repair.name);
    } catch { /* Missing or drifted source must fail the audit. */ }
  }
  return verified;
}
