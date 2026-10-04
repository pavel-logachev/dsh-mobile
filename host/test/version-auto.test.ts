import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { declaredDshVersion } from '../src/setup-direct.ts';

test('setup auto executes the actual PATH dsh launcher and rejects suffixes, ranges and failure', { skip: process.platform !== 'win32' }, async t => {
  const root = realpathSync.native(await mkdtemp(join(tmpdir(), 'dsh-mobile-version-')));
  const oldPath = process.env.PATH;
  t.after(async () => { process.env.PATH = oldPath; await rm(root, { recursive: true, force: true }); });
  const launcher = join(root, 'dsh.cmd');
  process.env.PATH = root + ';' + oldPath;
  await writeFile(launcher, '@echo off\r\necho 0.2.1-alpha.1\r\n');
  assert.equal(declaredDshVersion(), '0.2.1-alpha.1');
  await writeFile(launcher, '@echo off\r\necho 0.2.0-rc.2\r\n');
  assert.equal(declaredDshVersion(), '0.2.0-rc.2');
  for (const text of ['0.2.1-alpha.1 modified', '^0.2.1-alpha.1', '9.0.0']) {
    await writeFile(launcher, `@echo off\r\necho ${text.replace('^', '^^')}\r\n`);
    assert.throws(() => declaredDshVersion(), { code: 'unsupported_dsh_version' });
  }
  await writeFile(launcher, '@echo off\r\necho 0.2.1-alpha.1\r\nexit /b 1\r\n');
  assert.throws(() => declaredDshVersion(), { code: 'unsupported_dsh_version' });
});
