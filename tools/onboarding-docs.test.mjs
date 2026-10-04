import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const setup = await readFile(new URL('../docs/SETUP.md', import.meta.url), 'utf8');
const build = await readFile(new URL('../docs/BUILD.md', import.meta.url), 'utf8');
const remote = await readFile(new URL('../docs/REMOTE_SETUP.md', import.meta.url), 'utf8');
const canary = await readFile(new URL('../docs/DSH_CANARY.md', import.meta.url), 'utf8');

test('recipient runbook provides official prerequisites, installation consent and independent new-shell verification', () => {
  const prerequisites = setup.slice(setup.indexOf('## 2.'), setup.indexOf('## 3.'));
  assert.match(prerequisites, /https:\/\/nodejs\.org\/en\/download/);
  assert.match(prerequisites, /Windows.*(?:installer|\.msi)/);
  assert.match(prerequisites, /https:\/\/gitforwindows\.org\//);
  assert.match(prerequisites, /ASK THE USER[^\n]+(?:install|upgrade)/i);
  assert.match(prerequisites, /new (?:PowerShell )?shell/i);
  assert.match(prerequisites, /node --version/);
  assert.match(prerequisites, /openssl version/);
  assert.match(prerequisites, /Get-CimInstance Win32_Process/);
  assert.match(prerequisites, /bundled|own Node/);
});

test('operator docs make pairing output opt-in and never promise raw invitation stdout', () => {
  for (const [name, text] of [['SETUP', setup], ['BUILD', build], ['REMOTE_SETUP', remote]]) {
    assert.match(text, /Invitation JSON is never printed to stdout/, name);
    assert.match(text, /--qr[\s\S]*--output/, name);
  }
});

test('canary docs use both exact supported versions and a runtime-independent canonical receipt root', () => {
  assert.match(canary, /0\.2\.0-rc\.2/);
  assert.match(canary, /0\.2\.1-alpha\.1/);
  assert.doesNotMatch(canary, /manifests are checked for exact `0\.2\.0-rc\.2`/);
  assert.match(canary, /independent of `--runtime-root`/);
  assert.match(canary, /cache\/dsh-mobile\/canary\/run-<[^>]+>\/receipt\.json/);
});
