import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { assertOwnedPath, parseArgs } from './safety.mjs';

test('canary ownership permits children, never the root or adjacent names', () => {
  const root = path.resolve('canary-cache');
  assert.equal(assertOwnedPath(root, path.join(root, 'run-1', 'home')), path.join(root, 'run-1', 'home'));
  for (const target of [root, path.dirname(root), `${root}-other/home`]) {
    assert.throws(() => assertOwnedPath(root, target), /outside owned canary/);
  }
});

test('normal canary mode cannot listen and unknown options fail closed', () => {
  assert.equal(parseArgs([]).serve, false);
  assert.throws(() => parseArgs(['--port', '9443']), /serve/);
  assert.throws(() => parseArgs(['--bind', '0.0.0.0']), /Unknown/);
  assert.throws(() => parseArgs(['--serve']), /port/);
  assert.throws(() => parseArgs(['--serve', '--port', '3080']), /reserved/);
  assert.throws(() => parseArgs(['--serve', '--port', '19443', '--serve-ms', '9999999']), /serve-ms/);
});

test('registry profile acceptance reserves every live port and cannot combine with serve/controller-only', () => {
  assert.equal(parseArgs(['--registry-check', '--port', '19446']).registryCheck, true);
  for (const port of ['3080', '3081', '19445']) {
    for (const mode of ['--registry-check', '--serve']) assert.throws(() => parseArgs([mode, '--port', port]), /reserved/);
  }
  assert.throws(() => parseArgs(['--registry-check']), /port/);
  assert.throws(() => parseArgs(['--registry-check', '--port', '19446', '--serve']), /separate/);
  assert.throws(() => parseArgs(['--registry-check', '--port', '19446', '--controller-only']), /separate/);
});

test('serve requires an explicit unreserved loopback port and bounded lifetime', () => {
  const options = parseArgs(['--serve', '--port', '19443', '--serve-ms', '60000']);
  assert.equal(options.port, 19443);
  assert.equal(options.serveMs, 60000);
});
