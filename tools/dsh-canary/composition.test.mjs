import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, realpath, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { composeRuntime, officialLoader } from './composition.mjs';
import { installIsolation, sanitizeEnvironment } from './isolation.mjs';
import { assertOwnedPath } from './safety.mjs';
import { subagentChecks } from './subagent-checks.mjs';
import { fileURLToPath } from 'node:url';

// A separate node --test process owns this graph; never attach to the live host.
test('official composition activates controller, remote access and isolated working directory without live services', async () => {
  const installation = path.join(os.homedir(), 'Documents', 'DeepSeekHarness');
  const runtimeRoot = path.join(installation, 'runtime');
  const cache = path.join(installation, 'cache', 'dsh-mobile', 'canary');
  await mkdir(cache, { recursive: true });
  const root = await mkdtemp(path.join(cache, 'composition-test-'));
  const directories = Object.fromEntries(['home', 'workspace', 'temp'].map(name => [name, assertOwnedPath(root, path.join(root, name))]));
  directories.sessions = path.join(directories.home, 'sessions');
  directories.storages = path.join(directories.home, 'storages');
  for (const directory of Object.values(directories)) await mkdir(directory, { recursive: true });
  sanitizeEnvironment(directories.home, directories.temp);
  const isolation = installIsolation(cache, { serve: false });
  const loader = officialLoader(runtimeRoot);
  let runtime;
  try {
    await loader.version();
    runtime = await composeRuntime(loader, directories);
    assert.ok(runtime.ctx.get('sessionController'));
    assert.ok(runtime.ctx.get('remoteAccess'));
    assert.equal(runtime.ctx.get('workingDirectory').defaultDirectory, directories.workspace);
    assert.ok(runtime.mounted.includes('dsh-api-remote-access'));
    assert.ok(runtime.mounted.includes('dsh-working-directory'));
    for (const name of ['webServer', 'loader', 'profileContext', 'subprocess', 'deepseekAccount']) assert.equal(runtime.ctx.get(name), undefined);
    // Exercise failure interleavings with real managed activations: one held
    // child, and a held child plus descendant. Cleanup must release all three
    // registries/streams before this same Context can be reused.
    const projectRoot = fileURLToPath(new URL('../..', import.meta.url));
    for (const failAtCount of [1, 2]) {
      await assert.rejects(subagentChecks(runtime, loader, projectRoot, directories.workspace, async (_id, count) => {
        if (count === failAtCount) throw new Error(`Synthetic observer failure at count ${count}`);
      }), new RegExp(`Synthetic observer failure at count ${failAtCount}`));
      assert.equal(runtime.deterministic.active, 0);
      assert.equal(runtime.deterministic.controls.size, 0);
      assert.deepEqual(runtime.ctx.agents.list(), []);
      assert.equal(runtime.ctx.get('subagents'), undefined);
    }
    assert.deepEqual(isolation, { networkAttempts: 0, subprocessAttempts: 0, outsideWrites: 0, listeners: 0, activeListeners: 0 });
  } finally {
    await runtime?.dispose();
    assert.equal(await realpath(root), root);
    assertOwnedPath(cache, root);
    await rm(root, { recursive: true, force: true });
  }
});
