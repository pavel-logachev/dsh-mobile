import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import net from 'node:net';
import http from 'node:http';
import { spawn } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import { installIsolation } from './isolation.mjs';

// Separate node --test process: guard must never run inside the user's DSH process.
test('process-local isolation denies sockets, HTTP, subprocesses and outside writes before effects', async () => {
  const approvedCache = path.join(os.homedir(), 'Documents', 'DeepSeekHarness', 'cache', 'dsh-mobile', 'canary');
  await fs.mkdir(approvedCache, { recursive: true });
  const root = await fs.mkdtemp(path.join(approvedCache, 'guard-test-'));
  const scratch = path.join(root, 'scratch');
  await fs.mkdir(scratch);
  const stats = installIsolation(approvedCache, { serve: false });
  try {
    assert.throws(() => new net.Socket().connect({ host: '127.0.0.1', port: 1 }), /prohibited networkAttempts/);
    assert.throws(() => http.get('http://127.0.0.1:1'), /prohibited networkAttempts/);
    assert.throws(() => globalThis.fetch('http://127.0.0.1:1'), /prohibited networkAttempts/);
    assert.throws(() => spawn('nonexistent-canary-command'), /prohibited subprocessAttempts/);
    assert.throws(() => fs.writeFile(path.join(path.dirname(approvedCache), 'must-not-exist.txt'), 'denied'), /outside/);
    const server = net.createServer();
    assert.throws(() => server.listen(19443, '127.0.0.1'), /prohibited networkAttempts/);
    await fs.writeFile(path.join(scratch, 'owned.txt'), 'synthetic');
    assert.equal(await fs.readFile(path.join(scratch, 'owned.txt'), 'utf8'), 'synthetic');
    assert.deepEqual(stats, { networkAttempts: 4, subprocessAttempts: 1, outsideWrites: 1, listeners: 0, activeListeners: 0 });
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
});
