import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdir, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Exercise CLI preflight/receipt with an unsupported synthetic manifest, not a live runtime.
test('runtime staged under installation cache keeps the canonical output root and accepts --cache-root', async t => {
  const cacheRoot = path.join(os.homedir(), 'Documents', 'DeepSeekHarness', 'cache', 'dsh-mobile', 'canary');
  await mkdir(cacheRoot, { recursive: true });
  const fixture = await mkdtemp(path.join(cacheRoot, 'output-root-test-'));
  const runtimeRoot = path.join(fixture, 'runtime');
  await mkdir(path.join(runtimeRoot, 'node_modules', '@deepseek-ai', 'dsh'), { recursive: true });
  await writeFile(path.join(runtimeRoot, 'node_modules', '@deepseek-ai', 'dsh', 'package.json'), JSON.stringify({ name: '@deepseek-ai/dsh', version: '9.0.0' }));
  t.after(async () => { assert.equal(await realpath(fixture), fixture); await rm(fixture, { recursive: true, force: true }); });
  for (const flags of [[], ['--cache-root', cacheRoot]]) {
    const child = spawnSync(process.execPath, [fileURLToPath(new URL('./run.mjs', import.meta.url)), '--runtime-root', runtimeRoot, ...flags], { encoding: 'utf8', timeout: 10000 });
    assert.equal(child.status, 1, 'unsupported synthetic version fails closed');
    assert.doesNotMatch(child.stderr, /Only the installation cache/);
    assert.ok(child.stdout.trim().startsWith('{'), 'preflight must reach the receipt, not reject the canonical cache');
    const summary = JSON.parse(child.stdout);
    const runRoot = path.dirname(summary.receiptPath);
    assert.equal(path.dirname(runRoot).toLowerCase(), cacheRoot.toLowerCase());
    assert.match(path.basename(runRoot), /^run-/);
    assert.equal(summary.failure.phase, 'installed-version');
    assert.match(summary.failure.message, /Verified DSH versions only/);
    assert.equal(summary.cleanup.effectsDisposed, true);
    assert.equal(summary.cleanup.syntheticRuntimeRemoved, true);
    assert.equal(JSON.parse(await readFile(summary.receiptPath, 'utf8')).success, false);
    assert.equal(await realpath(runRoot), runRoot);
    await rm(runRoot, { recursive: true, force: true });
  }
});
