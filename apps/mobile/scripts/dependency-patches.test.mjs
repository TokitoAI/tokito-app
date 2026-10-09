import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
import { generateKeyPairSync } from 'node:crypto';
import { verifiedDependencyRepairs } from './dependency-patches.mjs';
const require = createRequire(import.meta.url);
const braces = require('braces');
const forge = require('node-forge');

test('every recorded repair matches the installed source', () => {
  assert.equal(verifiedDependencyRepairs().size, 2);
});
test('unverified nested installations cannot inherit a root repair', () => {
  const report = { vulnerabilities: { braces: { nodes: ['node_modules/braces', 'node_modules/other/node_modules/braces'] } } };
  assert.equal(verifiedDependencyRepairs(report).has('GHSA-vfj7-8cjw-p6xm'), false);
});
test('brace string entry points reject excessive nesting without stack overflow', () => {
  const input = '{'.repeat(4000) + 'a,b' + '}'.repeat(4000);
  for (const entry of ['parse', 'compile', 'expand', 'stringify']) {
    assert.throws(() => braces[entry](input), { name: 'SyntaxError', message: /depth limit/ });
  }
  assert.deepEqual(braces.expand('src/{a,b}/{1..2}.ts'), ['src/a/1.ts','src/a/2.ts','src/b/1.ts','src/b/2.ts']);
});
test('direct AST entry points also reject nested or cyclic trees', () => {
  let ast = { type: 'text', value: 'x' };
  for (let i = 0; i < 500; i++) ast = { type: 'root', nodes: [ast] };
  for (const entry of ['compile', 'expand', 'stringify']) {
    assert.throws(() => braces[entry](ast), { name: 'SyntaxError' });
  }
  const cycle = {type:'root',nodes:[]}; cycle.nodes.push(cycle);
  for (const entry of ['compile', 'expand', 'stringify']) assert.throws(() => braces[entry](cycle), {name:'SyntaxError'});
});
test('RSA verification rejects additional DigestAlgorithm elements, preserving valid signatures', () => {
  const keys = generateKeyPairSync('rsa', {modulusLength:1024,publicExponent:3});
  const privateKey = forge.pki.privateKeyFromPem(keys.privateKey.export({type:'pkcs1',format:'pem'}));
  const publicKey = forge.pki.publicKeyFromPem(keys.publicKey.export({type:'pkcs1',format:'pem'}));
  const md = forge.md.sha256.create(); md.update('Tokito integrity regression');
  const digest = md.digest().getBytes();
  assert.equal(publicKey.verify(digest, privateKey.sign(md)), true);
  const a = forge.asn1;
  const algorithm = a.create(a.Class.UNIVERSAL,a.Type.SEQUENCE,true,[
    a.create(a.Class.UNIVERSAL,a.Type.OID,false,a.oidToDer(forge.oids.sha256).getBytes()),
    a.create(a.Class.UNIVERSAL,a.Type.NULL,false,''),
    a.create(a.Class.UNIVERSAL,a.Type.NULL,false,''),
  ]);
  const info = a.create(a.Class.UNIVERSAL,a.Type.SEQUENCE,true,[algorithm,a.create(a.Class.UNIVERSAL,a.Type.OCTETSTRING,false,digest)]);
  const badSignature = privateKey.sign(a.toDer(info).getBytes(), 'NONE');
  assert.throws(() => publicKey.verify(digest,badSignature), /DigestInfo/);
});
