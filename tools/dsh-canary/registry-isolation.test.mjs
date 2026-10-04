import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { installIsolation } from './isolation.mjs';

// A separate test process: permits only this process's owned ephemeral socket.
test('registry-check loopback exception excludes live ports and expires on listener close', async () => {
  const host = http.createServer((_req, res) => res.end('synthetic owned listener'));
  host.listen(0, '127.0.0.1');
  await once(host, 'listening');
  const port = host.address().port;
  assert.ok(![3080, 3081, 19445].includes(port));
  await new Promise(resolve => host.close(resolve));
  const root = path.join(os.homedir(), 'Documents', 'DeepSeekHarness', 'cache', 'dsh-mobile', 'canary');
  const stats = installIsolation(root, { registryCheck: true, port });
  const url = new URL(`http://127.0.0.1:${port}/`);
  assert.throws(() => http.request(url), /prohibited networkAttempts/, 'Must not dial before owning a listener');
  for (const target of [3080, 3081, 19445, port + 1]) {
    assert.throws(() => http.request(`http://127.0.0.1:${target}/`), /prohibited networkAttempts/);
    assert.throws(() => new net.Socket().connect({ host: '127.0.0.1', port: target }), /prohibited networkAttempts/);
  }
  assert.throws(() => http.request(`http://localhost:${port}/`), /prohibited networkAttempts/);
  for (const target of [3080, 3081, 19445]) assert.throws(() => net.createServer().listen(target, '127.0.0.1'), /prohibited networkAttempts/);
  host.listen(port, '127.0.0.1');
  await once(host, 'listening');
  assert.equal(stats.activeListeners, 1);
  assert.throws(() => net.createConnection({ host: '127.0.0.1', port, path: '\\\\denied-pipe' }), /prohibited networkAttempts/);
  assert.throws(() => new net.Socket().connect({ host: '127.0.0.1', port, path: '\\\\denied-pipe' }), /prohibited networkAttempts/);
  const response = await new Promise((resolve, reject) => {
    const req = http.request(url, { agent: false }, res => {
      let text = '';
      res.on('data', chunk => { text += chunk; });
      res.once('end', () => resolve(text));
    });
    req.once('error', reject);
    req.end();
  });
  assert.equal(response, 'synthetic owned listener');
  await new Promise(resolve => host.close(resolve));
  assert.equal(stats.activeListeners, 0);
  assert.equal(stats.listeners, 1);
  assert.throws(() => http.request(url), /prohibited networkAttempts/, 'Closing the owned listener removes dial permission');
  assert.equal(stats.outsideWrites, 0);
  assert.equal(stats.subprocessAttempts, 0);
});
