import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { installIsolation, sanitizeEnvironment } from './isolation.mjs';
import { assertOwnedPath, parseArgs } from './safety.mjs';
import { composeRuntime, officialLoader, VERSION } from './composition.mjs';
import { controllerChecks, coldChecks, adapterChecks } from './checks.mjs';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const options = parseArgs(process.argv.slice(2));
if (options.registryCheck) assert.ok(process.execArgv.includes('--expose-internals'), 'registry-check requires node --expose-internals to avoid Windows-pinned native Loader cache');
const runtimeRoot = await realpath(options.runtimeRoot ?? path.join(os.homedir(), 'Documents', 'DeepSeekHarness', 'runtime'));
const approvedCache = path.join(path.dirname(runtimeRoot), 'cache', 'dsh-mobile', 'canary');
const cacheRoot = path.resolve(options.cacheRoot ?? approvedCache);
assert.equal(cacheRoot.toLowerCase(), approvedCache.toLowerCase(), 'Only the installation cache/dsh-mobile/canary root is permitted');
await mkdir(cacheRoot, { recursive: true });
const runRoot = await mkdtemp(path.join(await realpath(cacheRoot), 'run-'));
const directories = Object.fromEntries(['home', 'workspace', 'temp'].map(name => [name, assertOwnedPath(runRoot, path.join(runRoot, name))]));
directories.sessions = path.join(directories.home, 'sessions');
directories.storages = path.join(directories.home, 'storages');
for (const target of Object.values(directories)) await mkdir(target, { recursive: true });
const receiptPath = path.join(runRoot, 'receipt.json');
if (options.invitationFile) {
  options.invitationFile = assertOwnedPath(cacheRoot, path.resolve(projectRoot, options.invitationFile));
  assert.equal((await realpath(path.dirname(options.invitationFile))).toLowerCase(), path.dirname(options.invitationFile).toLowerCase(), 'Invitation parent must exist and must not be redirected');
}
sanitizeEnvironment(directories.home, directories.temp);
process.chdir(directories.workspace);
const isolation = installIsolation(runRoot, options);
const loader = officialLoader(runtimeRoot);
const receipt = { kind: 'isolated-real-dsh-canary', version: VERSION, node: process.version, startedAt: Date.now(), success: false, phases: [], installed: loader.evidence, isolation, cleanup: {} };
let runtime;
let stage = 'installed-version';
let serveCleanup;
let serveEvidence;
function mark(name) { stage = name; console.error(`canary phase: ${name}`); }
async function bounded(work) {
  let timer;
  try {
    return await Promise.race([work(), new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(`Canary timed out at ${stage}`)), options.timeoutMs); })]);
  } finally { clearTimeout(timer); }
}
try {
  await bounded(async () => {
    await loader.version();
    mark('compose-real-runtime');
    runtime = await composeRuntime(loader, directories);
    receipt.components = runtime.mounted;
    if (options.registryCheck) {
      mark('real-profile-insert-registry');
      const { registryChecks } = await import('./registry-checks.mjs');
      receipt.phases.push(await registryChecks({ runtime, loader, directories, runRoot, projectRoot, options: { ...options, runtimeRoot, isolation } }));
      receipt.mobileAdapter = 'verified: real profile-insert registry plugin';
      receipt.controllerCalls = runtime.deterministic.calls;
      receipt.remountedCalls = 0;
      return;
    }
    mark('controller-create-prompt-follow-reconnect-cancel');
    const direct = await controllerChecks(runtime, directories.workspace);
    receipt.phases.push(direct);
    receipt.controllerCalls = runtime.deterministic.calls;
    mark('dispose-first-runtime');
    await runtime.dispose(); runtime = undefined;
    mark('cold-runtime-remount');
    runtime = await composeRuntime(loader, directories);
    receipt.phases.push(await coldChecks(runtime, direct.sessionId));
    const workspace = { id: 'canary', name: 'Synthetic canary workspace', path: directories.workspace };
    if (options.controllerOnly) {
      receipt.mobileAdapter = 'not-run: explicitly controller-only';
    } else {
      mark('real-mobile-adapter');
      const adapterEntry = path.join(projectRoot, 'host', 'dist', 'dsh-adapter.js');
      await readFile(adapterEntry); // Fail if parent has not supplied the coordinated build.
      const module = await import(pathToFileURL(adapterEntry).href);
      const mobile = await adapterChecks(runtime, module, workspace, direct.sessionId);
      receipt.phases.push(mobile);
      serveEvidence = { existingSessionId: mobile.result.session.id, existingMessage: mobile.result.messages.find(message => message.role === 'assistant').text, createdSessionTitle: mobile.result.session.title };
      receipt.mobileAdapter = 'verified';
    }
    receipt.remountedCalls = runtime.deterministic.calls;
  });
  assert.equal(isolation.networkAttempts, 0);
  assert.equal(isolation.subprocessAttempts, 0);
  assert.equal(isolation.outsideWrites, 0);
  if (options.serve) {
    mark('serve-real-mobile-host');
    const { serve } = await import('./serve.mjs');
    serveCleanup = await serve({ runtime, directories, runRoot, projectRoot, options, evidence: serveEvidence });
    await serveCleanup.finished;
  }
  assert.equal(isolation.networkAttempts, 0);
  assert.equal(isolation.subprocessAttempts, 0);
  assert.equal(isolation.outsideWrites, 0);
  assert.equal(isolation.listeners, options.serve || options.registryCheck ? 1 : 0);
  receipt.success = true;
} catch (error) {
  receipt.failure = { phase: stage, name: error?.name ?? 'Error', message: error?.message ?? 'Unknown canary failure', stack: error?.stack };
  process.exitCode = 1;
} finally {
  try {
    await bounded(async () => {
      await serveCleanup?.close();
      if (runtime) {
        await runtime.dispose();
        if (options.serve) receipt.serve = { requests: runtime.deterministic.served, calls: runtime.deterministic.calls - receipt.remountedCalls };
        runtime = undefined;
      }
    });
    assert.equal(isolation.activeListeners, 0, 'No owned listener remains after cleanup');
    receipt.cleanup.effectsDisposed = true;
  } catch (error) {
    receipt.cleanup.effectsDisposed = false;
    receipt.cleanup.error = error?.message;
    receipt.success = false;
    process.exitCode = 1;
  }
  if (receipt.cleanup.effectsDisposed) {
    try {
      process.chdir(runRoot); // Windows pins the current directory: leave workspace before rm.
      for (const target of [directories.home, directories.workspace, directories.temp]) {
        assertOwnedPath(runRoot, target); // Resolve/verify the exact target before deletion.
        await rm(target, { recursive: true, force: true });
      }
      receipt.cleanup.syntheticRuntimeRemoved = true;
    } catch (error) {
      // Preserve the original acceptance failure even if Windows pins a loaded
      // native dependency in TEMP. Profile-check runs use --expose-internals
      // to avoid the loader's native-addon fallback and allow full cleanup.
      receipt.cleanup.syntheticRuntimeRemoved = false;
      receipt.cleanup.removalError = error?.message;
      receipt.success = false;
      process.exitCode = 1;
    }
  }
  receipt.finishedAt = Date.now();
  await writeFile(receiptPath, JSON.stringify(receipt, null, 2), { flag: 'wx', mode: 0o600 });
  console.log(JSON.stringify({ kind: receipt.kind, success: receipt.success, version: receipt.version, node: receipt.node, checks: receipt.phases.flatMap(phase => phase.checks), controllerCalls: receipt.controllerCalls, remountedCalls: receipt.remountedCalls, mobileAdapter: receipt.mobileAdapter, isolation, cleanup: receipt.cleanup, ...(receipt.failure ? { failure: receipt.failure } : {}), receiptPath }, null, 2));
}
