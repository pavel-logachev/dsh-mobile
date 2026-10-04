import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { request as httpRequest } from 'node:http';
import { mkdtempSync, mkdirSync, rmSync, readFileSync, writeFileSync } from 'node:fs';
import { runAdminCli } from '../src/cli.ts';
import { prepareConfiguration, validateBaseUrl } from '../src/config.ts';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import type { TestContext } from 'node:test';
import { startHostServer } from '../src/server.ts';
import { HostState } from '../src/state.ts';
import type { HostAdapter, HostSnapshot, DeviceGrants, HostConfiguration } from '../src/types.ts';

const snapshot: HostSnapshot = { session: { id: 's-alpha', title: 'Synthetic conversation', workspaceId: 'alpha', updatedAt: 1, running: false }, messages: [{ id: 'm1', role: 'assistant', text: 'Fixture only', createdAt: 1 }], cursor: 1, hasMore: false, activity: 'idle' };

class TestAdapter implements HostAdapter {
  readonly upstreamVersion = 'synthetic-test';
  prompts: string[] = [];
  snapshots = new Map<string, HostSnapshot>([['s-alpha', structuredClone(snapshot)], ['s-beta', { ...structuredClone(snapshot), session: { ...snapshot.session, id: 's-beta', workspaceId: 'beta' } }]]);
  async listPresets() { return [{ id: 'default', name: 'Default' }]; }
  async listSessions() { return [...this.snapshots.values()].map(item => item.session); }
  async snapshot(id: string) { const value = this.snapshots.get(id); if (!value) throw new Error('Unknown fixture'); return structuredClone(value); }
  async *watch(id: string, signal: AbortSignal) { yield await this.snapshot(id); await new Promise<void>(resolve => signal.addEventListener('abort', () => resolve(), { once: true })); }
  async createSession(input: { workspaceId: string; requestId: string }) {
    const sessionId = `new-${input.requestId}`;
    this.snapshots.set(sessionId, { ...structuredClone(snapshot), session: { ...snapshot.session, id: sessionId, workspaceId: input.workspaceId }, messages: [] });
    return { sessionId };
  }
  async prompt(id: string, text: string, requestId: string, signal: AbortSignal) {
    signal.throwIfAborted(); this.prompts.push(requestId);
    const item = this.snapshots.get(id)!;
    item.messages.push({ id: requestId, role: 'user', text, requestId, createdAt: 2 }); item.cursor++;
  }
  async cancel() {}
}

async function setup(t: TestContext, adapter: HostAdapter = new TestAdapter()) {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-mobile-server-'));
  mkdirSync(join(dir, 'alpha')); mkdirSync(join(dir, 'beta'));
  const config: HostConfiguration = { hostName: 'Test fixture only', bind: '127.0.0.1', port: 0, statePath: join(dir, 'host.sqlite'), allowInsecureLoopback: true, workspaces: ['alpha', 'beta'].map(id => ({ id, name: id, path: join(dir, id) })) };
  const host = await startHostServer({ config, adapter });
  const baseUrl = (await host.start()).baseUrl;
  const hosts = [host];
  t.after(async () => { for (const item of hosts) await item.close(); rmSync(dir, { recursive: true, force: true }); });
  async function request(path: string, token?: string, method = 'GET', body?: unknown, headers: Record<string, string> = {}) {
    return fetch(`${baseUrl}/v1${path}`, { method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...headers }, ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
  }
  async function pair(grants: DeviceGrants = { readWorkspaceIds: ['alpha'], executeWorkspaceIds: ['alpha'] }) {
    const offer = host.state.createPairing(grants);
    const response = await request('/pairings', undefined, 'POST', { pairingToken: offer.pairingToken, deviceName: 'Fixture phone' });
    assert.equal(response.status, 201);
    return { ...await response.json() as { deviceId: string; deviceToken: string }, offer };
  }
  return { host, hosts, config, adapter, baseUrl, request, pair };
}

test('chunked body overflow returns the protocol envelope without command admission', { timeout: 5000 }, async t => {
  const { host, baseUrl, pair } = await setup(t);
  const { deviceId, deviceToken } = await pair();
  const requestId = randomUUID();
  const response = await new Promise<{ status: number; body: unknown }>((resolve, reject) => {
    const request = httpRequest(`${baseUrl}/v1/sessions/s-alpha/messages`, { method: 'POST', headers: { Authorization: `Bearer ${deviceToken}`, 'Content-Type': 'application/json', 'Transfer-Encoding': 'chunked' } }, incoming => {
      const chunks: Buffer[] = [];
      incoming.on('data', chunk => chunks.push(chunk));
      incoming.once('error', reject);
      incoming.once('end', () => resolve({ status: incoming.statusCode!, body: JSON.parse(Buffer.concat(chunks).toString('utf8')) }));
    });
    request.once('error', reject);
    request.write(`{"requestId":"${requestId}","text":"`);
    request.write('a'.repeat(40 * 1024));
    request.end('a'.repeat(40 * 1024) + '"}');
  });
  assert.equal(response.status, 413);
  assert.equal((response.body as { error: { code: string } }).error.code, 'payload_too_large');
  assert.equal(host.state.getCommand(deviceId, requestId), undefined);
});

test('exact admitted replay survives deleted upstream sessions while conflicts and current workspace scope stay enforced', async t => {
  const adapter = new TestAdapter();
  const { host, hosts, config, request, pair } = await setup(t, adapter);
  const { deviceToken } = await pair();
  const requestId = randomUUID(), body = { requestId, text: 'Recorded fixture action' };
  const first = await request('/sessions/s-alpha/messages', deviceToken, 'POST', body);
  const originalReceipt = await first.json();
  assert.equal(originalReceipt.status, 'accepted');
  adapter.snapshots.delete('s-alpha');
  const repeated = await request('/sessions/s-alpha/messages', deviceToken, 'POST', body);
  assert.equal(repeated.status, 200);
  assert.deepEqual(await repeated.json(), originalReceipt);
  assert.equal((await request('/sessions/s-alpha/messages', deviceToken, 'POST', { ...body, text: 'Conflicting action' })).status, 409);
  assert.equal(adapter.prompts.length, 1);
  await host.close();
  const restarted = await startHostServer({ config: { ...config, workspaces: config.workspaces.filter(workspace => workspace.id === 'beta') }, adapter }); hosts.push(restarted);
  const base = (await restarted.start()).baseUrl;
  const denied = await fetch(`${base}/v1/sessions/s-alpha/messages`, { method: 'POST', headers: { Authorization: `Bearer ${deviceToken}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  assert.equal(denied.status, 404);
  assert.equal(adapter.prompts.length, 1);
});

test('oversized initial observation returns an explicit safe error before starting SSE', async t => {
  const adapter = new TestAdapter();
  adapter.snapshots.get('s-alpha')!.messages = [{ id: 'large', role: 'assistant', text: 'x'.repeat(2 * 1024 * 1024), createdAt: 1 }];
  const { request, pair } = await setup(t, adapter);
  const { deviceToken } = await pair();
  const response = await request('/sessions/s-alpha/events', deviceToken);
  assert.equal(response.status, 413);
  assert.equal(response.headers.get('content-type'), 'application/json; charset=utf-8');
  assert.equal((await response.json()).error.code, 'payload_too_large');
});

test('empty DSH cursor -1 is preserved and long message text is not silently truncated', async t => {
  const adapter = new TestAdapter();
  adapter.snapshots.get('s-alpha')!.cursor = -1;
  adapter.snapshots.get('s-alpha')!.messages = [];
  const { request, pair } = await setup(t, adapter);
  const { deviceToken } = await pair();
  const initial = await request('/sessions/s-alpha', deviceToken);
  assert.equal(initial.status, 200);
  assert.equal((await initial.json()).cursor, -1);
  const text = 'Длинный ответ '.repeat(6000);
  adapter.snapshots.get('s-alpha')!.messages = [{ id: 'long', role: 'assistant', text, createdAt: 2 }];
  const full = await request('/sessions/s-alpha', deviceToken);
  assert.equal((await full.json()).messages[0].text, text);
});

test('local CLI initializes state and issues only intentional invitation output; devices/revoke never disclose credentials', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-mobile-cli-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const configPath = join(dir, 'private.config.json');
  let output = '', errors = '';
  const io = { out: (value: string) => { output += value; }, error: (value: string) => { errors += value; } };
  const code = await runAdminCli(['init', '--config', configPath, '--workspace', dir, '--dev-http'], io);
  assert.equal(code, 0);
  assert.equal(errors, '');
  const config = JSON.parse(readFileSync(configPath, 'utf8'));
  assert.equal(config.bind, '127.0.0.1');
  assert.equal(config.allowInsecureLoopback, true);
  assert.equal(config.workspaces[0].id, 'default');
  output = '';
  assert.equal(await runAdminCli(['pair', '--config', configPath, '--read', 'default', '--execute', 'default'], io), 0);
  const invitation = JSON.parse(output);
  assert.equal(invitation.version, 1);
  assert.equal(invitation.baseUrl, 'http://127.0.0.1:9443');
  assert.equal(Buffer.from(invitation.pairingToken, 'base64url').length, 32);
  const state = new HostState(config.statePath);
  const paired = state.consumePairing(invitation.pairingToken, 'Admin fixture');
  output = '';
  assert.equal(await runAdminCli(['devices', '--config', configPath], io), 0);
  assert.match(output, /Admin fixture/);
  assert.doesNotMatch(output, new RegExp(`${invitation.pairingToken}|${paired.deviceToken}|token_hash`));
  assert.equal(await runAdminCli(['revoke', '--config', configPath, '--device', paired.deviceId], io), 0);
  assert.equal(state.authenticate(paired.deviceToken), undefined);
  // Local security administration does not depend on a currently valid TLS certificate.
  delete config.allowInsecureLoopback;
  config.tls = { certPath: join(dir, 'missing-expired.pem'), keyPath: join(dir, 'missing-key.pem') };
  config.publicUrl = 'https://localhost:9443';
  writeFileSync(configPath, JSON.stringify(config));
  assert.equal(await runAdminCli(['devices', '--config', configPath], io), 0);
  state.close();
  output = ''; errors = '';
  assert.equal(await runAdminCli(['init', '--config', configPath, '--workspace', dir, '--dev-http'], io), 1);
  assert.doesNotMatch(errors, new RegExp(dir.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
});

test('grant CLI replaces active grants without pairing, validates explicit IDs and narrows idle SSE and requests', { timeout: 8000 }, async t => {
  const { config, request, pair } = await setup(t);
  const configPath = join(config.statePath, '..', 'private.config.json');
  writeFileSync(configPath, JSON.stringify(config));
  const device = await pair({ readWorkspaceIds: ['*'], executeWorkspaceIds: ['*'] });
  const stream = await request('/sessions/s-alpha/events', device.deviceToken);
  const reader = stream.body!.getReader();
  t.after(async () => { await reader.cancel(); });
  assert.match(new TextDecoder().decode((await reader.read()).value), /"canExecute":true/);
  let output = '', errors = '';
  const io = { out: (text: string) => { output += text; }, error: (text: string) => { errors += text; } };
  const grant = (read: string, execute?: string) => runAdminCli(['grant', '--config', configPath, '--device', device.deviceId, '--read', read, ...(execute ? ['--execute', execute] : [])], io);
  assert.equal(await grant('all'), 0); // Omitted execute removes execute, not "keep old grants".
  const changed = new TextDecoder().decode((await reader.read()).value);
  assert.match(changed, /"canExecute":false/);
  assert.equal((await request('/sessions/s-alpha/messages', device.deviceToken, 'POST', { requestId: randomUUID(), text: 'Narrowed' })).status, 403);
  assert.equal(await grant('beta', 'beta'), 0);
  assert.equal((await reader.read()).done, true);
  assert.equal((await request('/sessions/s-alpha', device.deviceToken)).status, 404);
  assert.equal((await request('/sessions/s-beta', device.deviceToken)).status, 200);
  assert.equal(await grant('unknown'), 1);
  assert.deepEqual(await (await request('/workspaces', device.deviceToken)).json(), { items: [{ id: 'beta', name: 'beta', canExecute: true }] });
  assert.equal(await grant('alpha', 'all'), 1);
  assert.doesNotMatch(output + errors, new RegExp(device.deviceToken));
  assert.doesNotMatch(output + errors, /token_hash|pairingToken|accessToken/);
  assert.equal(await runAdminCli(['revoke', '--config', configPath, '--device', device.deviceId], io), 0);
  assert.equal(await grant('all', 'all'), 1);
  assert.equal(await runAdminCli(['grant', '--config', configPath, '--device', randomUUID(), '--read', 'all'], io), 1);
});

test('local configuration rejects unsafe transport, ambiguous workspaces and invitation URL bypasses', async t => {
  const { config } = await setup(t);
  await assert.rejects(prepareConfiguration({ ...config, allowInsecureLoopback: false }), { code: 'invalid_config' });
  await assert.rejects(prepareConfiguration({ ...config, bind: '0.0.0.0' }), { code: 'invalid_config' });
  await assert.rejects(prepareConfiguration({ ...config, bind: '::1' }), { code: 'invalid_config' });
  await assert.rejects(prepareConfiguration({ ...config, workspaces: [config.workspaces[0]!, { ...config.workspaces[0]!, id: 'alias' }] }), { code: 'invalid_config' });
  await assert.rejects(prepareConfiguration({ ...config, workspaces: [{ ...config.workspaces[0]!, path: 'relative' }] }), { code: 'invalid_config' });
  for (const url of ['http://127.1:9443', 'http://2130706433:9443', 'http://localhost.evil:9443', 'http://[::1]:9443', 'http://localhost:9443/path', 'https://user:pass@example.test', 'https://example.test?secret=1', 'https://example.test#fragment', 'https://@example.test', 'https://example.test?', 'https://example.test#', 'https://example.test/.', 'https://example.test/a/..']) assert.throws(() => validateBaseUrl(url, true), { code: 'invalid_config' });
  assert.equal(validateBaseUrl('https://example.test:9443', false).hostname, 'example.test');
});

test('client disconnect after dispatch does not cancel host execution; before admission it creates no receipt', async t => {
  const adapter = new TestAdapter();
  let entered!: () => void, release!: () => void, completed!: () => void;
  const started = new Promise<void>(resolve => { entered = resolve; });
  const gate = new Promise<void>(resolve => { release = resolve; });
  const done = new Promise<void>(resolve => { completed = resolve; });
  let dispatchedSignal: AbortSignal | undefined;
  const original = adapter.prompt.bind(adapter);
  adapter.prompt = async (...args) => { dispatchedSignal = args[3]; entered(); await gate; await original(...args); completed(); };
  const { request, pair, baseUrl } = await setup(t, adapter);
  const { deviceToken } = await pair();
  const requestId = randomUUID();
  const controller = new AbortController();
  const sending = fetch(`${baseUrl}/v1/sessions/s-alpha/messages`, { method: 'POST', signal: controller.signal, headers: { Authorization: `Bearer ${deviceToken}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ requestId, text: 'Continue independently' }) });
  await started; controller.abort();
  await assert.rejects(sending, { name: 'AbortError' });
  release(); await done;
  const receipt = await (await request(`/commands/${requestId}`, deviceToken)).json();
  assert.equal(receipt.status, 'accepted');
  assert.equal(dispatchedSignal?.aborted, false);

  const secondAdapter = new TestAdapter();
  let readEntered!: () => void, readRelease!: () => void, readDone!: () => void;
  const readStarted = new Promise<void>(resolve => { readEntered = resolve; });
  const readGate = new Promise<void>(resolve => { readRelease = resolve; });
  const readCompleted = new Promise<void>(resolve => { readDone = resolve; });
  let serverDisconnected!: () => void;
  const disconnected = new Promise<void>(resolve => { serverDisconnected = resolve; });
  secondAdapter.snapshot = async (_id: string, signal: AbortSignal) => { signal.addEventListener('abort', () => serverDisconnected(), { once: true }); readEntered(); await readGate; readDone(); return structuredClone(snapshot); };
  const second = await setup(t, secondAdapter);
  const secondDevice = await second.pair();
  const preRequestId = randomUUID(), before = new AbortController();
  const preSend = fetch(`${second.baseUrl}/v1/sessions/s-alpha/messages`, { method: 'POST', signal: before.signal, headers: { Authorization: `Bearer ${secondDevice.deviceToken}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ requestId: preRequestId, text: 'Do not admit' }) });
  await readStarted; before.abort(); await assert.rejects(preSend, { name: 'AbortError' });
  await disconnected;
  readRelease(); await readCompleted;
  assert.equal((await second.request(`/commands/${preRequestId}`, secondDevice.deviceToken)).status, 404);
  assert.equal(secondAdapter.prompts.length, 0);
});

test('revocation during pre-dispatch authorization persists rejection without upstream execution', async t => {
  const adapter = new TestAdapter();
  let reads = 0, entered!: () => void, release!: () => void, finished!: () => void;
  const started = new Promise<void>(resolve => { entered = resolve; });
  const gate = new Promise<void>(resolve => { release = resolve; });
  const done = new Promise<void>(resolve => { finished = resolve; });
  const original = adapter.snapshot.bind(adapter);
  adapter.snapshot = async id => { if (++reads === 2) { entered(); await gate; finished(); } return original(id); };
  const { host, request, pair, config } = await setup(t, adapter);
  const { deviceId, deviceToken } = await pair();
  const requestId = randomUUID();
  const sending = request('/sessions/s-alpha/messages', deviceToken, 'POST', { requestId, text: 'Must not dispatch' });
  await started;
  const external = new HostState(config.statePath);
  external.revokeDevice(deviceId); external.close();
  release(); await done;
  assert.equal((await sending).status, 401);
  assert.equal(host.state.getCommand(deviceId, requestId)?.receipt.status, 'rejected');
  assert.equal(adapter.prompts.length, 0);
});

test('grant narrowing while pre-dispatch observation is pending prevents execution and receipt replay stays denied', async t => {
  const adapter = new TestAdapter();
  let reads = 0, entered!: () => void, release!: () => void;
  const started = new Promise<void>(resolve => { entered = resolve; }), gate = new Promise<void>(resolve => { release = resolve; });
  const original = adapter.snapshot.bind(adapter);
  adapter.snapshot = async id => { if (++reads === 2) { entered(); await gate; } return original(id); };
  const { host, request, pair, config } = await setup(t, adapter);
  const paired = await pair({ readWorkspaceIds: ['*'], executeWorkspaceIds: ['*'] });
  const requestId = randomUUID(), body = { requestId, text: 'Never dispatch after narrowing' };
  const sending = request('/sessions/s-alpha/messages', paired.deviceToken, 'POST', body);
  await started;
  const external = new HostState(config.statePath);
  external.replaceDeviceGrants(paired.deviceId, { readWorkspaceIds: ['*'], executeWorkspaceIds: [] }); external.close();
  release();
  assert.equal((await sending).status, 403);
  assert.equal(host.state.getCommand(paired.deviceId, requestId)?.receipt.status, 'rejected');
  assert.equal(adapter.prompts.length, 0);
  assert.equal((await request('/sessions/s-alpha/messages', paired.deviceToken, 'POST', body)).status, 403);
});

test('unfinished dispatch becomes uncertain after restart and is never retried', async t => {
  const adapter = new TestAdapter();
  let entered!: () => void, release!: () => void, done!: () => void;
  const dispatched = new Promise<void>(resolve => { entered = resolve; });
  const gate = new Promise<void>(resolve => { release = resolve; });
  const completed = new Promise<void>(resolve => { done = resolve; });
  let calls = 0;
  adapter.prompt = async () => { calls++; entered(); await gate; done(); };
  const { host, hosts, config, request, pair } = await setup(t, adapter);
  const { deviceToken } = await pair();
  const requestId = randomUUID();
  const response = await request('/sessions/s-alpha/messages', deviceToken, 'POST', { requestId, text: 'Interrupted transport' });
  await dispatched;
  assert.equal((await response.json()).status, 'pending');
  await host.close();
  const restarted = await startHostServer({ config, adapter }); hosts.push(restarted);
  const base = (await restarted.start()).baseUrl;
  const duplicate = await fetch(`${base}/v1/sessions/s-alpha/messages`, { method: 'POST', headers: { Authorization: `Bearer ${deviceToken}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ requestId, text: 'Interrupted transport' }) });
  assert.equal((await duplicate.json()).status, 'uncertain');
  assert.equal(calls, 1);
  release(); await completed;
});

test('body/text/media limits and strict mutation fields reject before admission', async t => {
  const { host, request, pair, baseUrl } = await setup(t);
  const { deviceId, deviceToken } = await pair();
  const requestId = randomUUID();
  assert.equal((await request('/sessions/s-alpha/messages', deviceToken, 'POST', { requestId, text: 'я'.repeat(16385) })).status, 413);
  assert.equal((await request('/sessions/s-alpha/messages', deviceToken, 'POST', { requestId, text: ' ' })).status, 400);
  assert.equal((await request('/sessions/s-alpha/messages', deviceToken, 'POST', { requestId, text: 'ok', cwd: '/arbitrary' })).status, 400);
  const tooBig = await request('/sessions/s-alpha/messages', deviceToken, 'POST', { requestId, text: 'a'.repeat(65536) });
  assert.equal(tooBig.status, 413);
  const wrongType = await fetch(`${baseUrl}/v1/sessions/s-alpha/messages`, { method: 'POST', headers: { Authorization: `Bearer ${deviceToken}`, 'Content-Type': 'text/plain' }, body: '{}' });
  assert.equal(wrongType.status, 415);
  assert.equal(host.state.getCommand(deviceId, requestId), undefined);
  assert.equal((await request('/rpc', deviceToken, 'POST', { method: 'exec' })).status, 404);
  assert.equal((await request('/sessions/%2fprivate', deviceToken)).status, 400);
  assert.equal((await request('/sessions/s-beta/messages', deviceToken, 'POST', { requestId, text: 'not allowed' })).status, 404);
});

test('uncertain delivery remains persisted across restart and exact repeats never automatically dispatch again', async t => {
  const adapter = new TestAdapter();
  let calls = 0;
  adapter.prompt = async () => { calls++; throw new Error('C:\\private\\host credential-secret-token stack'); };
  const { host, hosts, config, request, pair } = await setup(t, adapter);
  const { deviceToken } = await pair();
  const requestId = randomUUID();
  const response = await request('/sessions/s-alpha/messages', deviceToken, 'POST', { requestId, text: 'Uncertain fixture task' });
  assert.equal(response.status, 202);
  const receipt = await response.json();
  assert.equal(receipt.status, 'uncertain');
  assert.doesNotMatch(JSON.stringify(receipt), /private|credential-secret|stack/);
  await host.close();
  const restarted = await startHostServer({ config, adapter });
  hosts.push(restarted);
  const base = (await restarted.start()).baseUrl;
  const repeated = await fetch(`${base}/v1/sessions/s-alpha/messages`, { method: 'POST', headers: { Authorization: `Bearer ${deviceToken}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ text: 'Uncertain fixture task', requestId }) });
  assert.equal(repeated.status, 202);
  assert.deepEqual(await repeated.json(), receipt);
  assert.equal(calls, 1);
});

test('idle permission rechecks cannot republish obsolete history across an asynchronous workspace lookup', { timeout: 5000 }, async t => {
  let releaseCheck!: () => void, checkStarted!: () => void, publish!: () => void;
  const started = new Promise<void>(resolve => { checkStarted = resolve; });
  const blocked = new Promise<void>(resolve => { releaseCheck = resolve; });
  let watchStarted!: () => void;
  const watching = new Promise<void>(resolve => { watchStarted = resolve; });
  const publishUpdate = new Promise<void>(resolve => { publish = resolve; });
  let reads = 0, watchOpen = false;
  const dir = mkdtempSync(join(tmpdir(), 'dsh-mobile-stream-order-'));
  const adapter = new TestAdapter();
  const current = structuredClone(snapshot);
  current.messages = [{ id: 'new', role: 'assistant', text: 'NEW AUTHORITATIVE TEXT', createdAt: 2 }]; current.cursor = 2;
  adapter.watch = async function* (_id, signal) {
    watchOpen = true; watchStarted();
    await publishUpdate;
    if (!signal.aborted) yield current;
    await new Promise<void>(resolve => { if (signal.aborted) resolve(); else signal.addEventListener('abort', () => resolve(), { once: true }); });
  };
  const workspaceSource = { kind: 'dsh-registry' as const, async list() {
    if (watchOpen && ++reads === 2) { checkStarted(); await blocked; }
    return [{ id: 'alpha', name: 'Synthetic alpha', path: dir }];
  }, archivedSessionIds() { return new Set<string>(); } };
  const host = await startHostServer({ config: { workspaceSource: 'dsh-registry', hostName: 'Synthetic stream order', bind: '127.0.0.1', port: 0, statePath: join(dir, 'host.sqlite'), allowInsecureLoopback: true }, adapter, workspaceSource, heartbeatMs: 10 });
  t.after(async () => { releaseCheck(); publish(); await host.close(); rmSync(dir, { recursive: true, force: true }); });
  const device = host.state.consumePairing(host.state.createPairing({ readWorkspaceIds: ['*'], executeWorkspaceIds: ['*'] }).pairingToken, 'Synthetic phone');
  const response = await fetch(`${(await host.start()).baseUrl}/v1/sessions/s-alpha/events`, { headers: { authorization: `Bearer ${device.deviceToken}` } });
  const reader = response.body!.getReader(); await reader.read(); await watching;
  await started; // The timer captured the old frame, then its live source check blocked.
  publish();
  const fresh = new TextDecoder().decode((await reader.read()).value);
  assert.match(fresh, /NEW AUTHORITATIVE TEXT/);
  releaseCheck();
  const next = new TextDecoder().decode((await reader.read()).value);
  assert.doesNotMatch(next, /Fixture only/, 'a permission-only recheck must not overwrite a newer watch replacement');
  await reader.cancel();
});

test('SSE reconnect replaces history and both local and external revocation close bounded streams', async t => {
  const adapter = new TestAdapter();
  const { host, request, pair, config } = await setup(t, adapter);
  const { deviceId, deviceToken } = await pair();
  const readers: ReadableStreamDefaultReader<Uint8Array>[] = [];
  const stream = await request('/sessions/s-alpha/events', deviceToken);
  assert.equal(stream.status, 200);
  const reader = stream.body!.getReader();
  const first = new TextDecoder().decode((await reader.read()).value);
  assert.match(first, /^event: snapshot\ndata: /);
  assert.match(first, /Fixture only/);
  await reader.cancel();
  adapter.snapshots.get('s-alpha')!.messages = [{ id: 'replacement', role: 'assistant', text: 'Authoritative replacement', createdAt: 3 }];
  for (let index = 0; index < 3; index++) {
    const resumed = await request('/sessions/s-alpha/events', deviceToken, 'GET', undefined, { 'Last-Event-ID': 'obsolete-process-cursor' });
    assert.equal(resumed.status, 200);
    const resumedReader = resumed.body!.getReader();
    const replacement = new TextDecoder().decode((await resumedReader.read()).value);
    assert.match(replacement, /Authoritative replacement/);
    assert.doesNotMatch(replacement, /Fixture only/);
    readers.push(resumedReader);
  }
  assert.equal((await request('/sessions/s-alpha/events', deviceToken)).status, 429);
  const external = new HostState(config.statePath);
  assert.equal(external.revokeDevice(deviceId), true);
  external.close();
  assert.equal((await request('/capabilities', deviceToken)).status, 401);
  // Revocation must close within a bounded interval even from another SQLite connection.
  for (const reader of readers) assert.equal((await reader.read()).done, true);
  const local = await pair();
  const live = await request('/sessions/s-alpha/events', local.deviceToken);
  const liveReader = live.body!.getReader(); await liveReader.read();
  host.state.revokeDevice(local.deviceId);
  assert.equal((await liveReader.read()).done, true);
});

test('concurrent duplicate commands return one persisted receipt and conflicting payload never dispatches', async t => {
  const adapter = new TestAdapter();
  let release!: () => void;
  const blocked = new Promise<void>(resolve => { release = resolve; });
  const original = adapter.prompt.bind(adapter);
  adapter.prompt = async (...args) => { await blocked; await original(...args); };
  const { request, pair } = await setup(t, adapter);
  const { deviceToken } = await pair();
  const requestId = randomUUID();
  const send = () => request('/sessions/s-alpha/messages', deviceToken, 'POST', { requestId, text: 'Synthetic task' });
  const [first, repeated] = await Promise.all([send(), send()]);
  assert.equal(first.status, 202);
  assert.equal(repeated.status, 202);
  assert.deepEqual(await first.json(), await repeated.json());
  const conflict = await request('/sessions/s-alpha/messages', deviceToken, 'POST', { requestId, text: 'Different task' });
  assert.equal(conflict.status, 409);
  assert.equal((await conflict.json()).error.code, 'conflict');
  release();
  const accepted = await send();
  assert.equal(accepted.status, 200);
  const receipt = await accepted.json();
  assert.equal(receipt.status, 'accepted');
  assert.deepEqual((await request(`/commands/${requestId}`, deviceToken)).status, 200);
  assert.equal(adapter.prompts.length, 1);
  const other = await pair();
  assert.equal((await request(`/commands/${requestId}`, other.deviceToken)).status, 404);
  assert.equal((await request(`/commands/${randomUUID()}`, deviceToken)).status, 404);
  const cancellation = await request('/sessions/s-alpha/cancellations', deviceToken, 'POST', { requestId: randomUUID(), expectedCursor: 1 });
  assert.equal(cancellation.status, 409);
  assert.equal((await cancellation.json()).status, 'rejected');
  adapter.snapshots.get('s-alpha')!.session.running = true;
  const stop = await request('/sessions/s-alpha/cancellations', deviceToken, 'POST', { requestId: randomUUID(), expectedCursor: 2 });
  assert.equal((await stop.json()).status, 'accepted');
  const created = await request('/sessions', deviceToken, 'POST', { requestId: randomUUID(), workspaceId: 'alpha', presetId: 'default' });
  assert.equal(created.status, 201);
  assert.match((await created.json()).result.sessionId, /^new-/);
});

test('wildcard read and execute grants cover all configured scope, while read-only and unknown roots stay denied', async t => {
  const { host, request, pair } = await setup(t);
  const all = await pair({ readWorkspaceIds: ['*'], executeWorkspaceIds: ['*'] });
  assert.deepEqual(await (await request('/workspaces', all.deviceToken)).json(), { items: [{ id: 'alpha', name: 'alpha', canExecute: true }, { id: 'beta', name: 'beta', canExecute: true }] });
  const page = await (await request('/sessions?limit=1', all.deviceToken)).json();
  assert.equal(page.items[0].canExecute, true);
  const next = await (await request(`/sessions?limit=1&cursor=${page.nextCursor}`, all.deviceToken)).json();
  assert.equal(next.items[0].id, 's-beta'); assert.equal(next.items[0].canExecute, true);
  assert.equal((await request('/sessions/s-beta/messages', all.deviceToken, 'POST', { requestId: randomUUID(), text: 'Synthetic all-project task' })).status, 200);
  assert.equal((await request('/sessions', all.deviceToken, 'POST', { requestId: randomUUID(), workspaceId: 'beta' })).status, 201);
  const deniedId = randomUUID();
  assert.equal((await request('/sessions', all.deviceToken, 'POST', { requestId: deniedId, workspaceId: 'unknown' })).status, 403);
  assert.equal(host.state.getCommand(all.deviceId, deniedId), undefined);
  const readOnly = await pair({ readWorkspaceIds: ['*'], executeWorkspaceIds: [] });
  assert.equal((await request('/sessions/s-beta', readOnly.deviceToken)).status, 200);
  assert.equal((await request('/sessions/s-beta/messages', readOnly.deviceToken, 'POST', { requestId: randomUUID(), text: 'Denied' })).status, 403);
  assert.equal((await (await request('/sessions', readOnly.deviceToken)).json()).items.every((item: { canExecute: boolean }) => !item.canExecute), true);
});

test('read grants filter every session and pagination; execute grants and self revocation are enforced', async t => {
  const { request, pair } = await setup(t);
  const paired = await pair({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] });
  const list = await request('/sessions?limit=1', paired.deviceToken);
  assert.equal(list.status, 200);
  const page = await list.json();
  assert.equal(page.items.length, 1);
  assert.equal(page.items[0].id, 's-alpha');
  assert.equal(page.items[0].canExecute, false);
  assert.equal(page.nextCursor, null);
  assert.equal((await request('/sessions/s-alpha', paired.deviceToken)).status, 200);
  assert.equal((await request('/sessions/s-beta', paired.deviceToken)).status, 404);
  assert.equal((await request('/sessions?workspaceId=beta', paired.deviceToken)).status, 403);
  assert.equal((await request('/sessions?limit=101', paired.deviceToken)).status, 400);
  assert.equal((await request('/sessions?cursor=forged', paired.deviceToken)).status, 400);
  const forbidden = await request('/sessions/s-alpha/messages', paired.deviceToken, 'POST', { requestId: randomUUID(), text: 'Do not run' });
  assert.equal(forbidden.status, 403);
  assert.equal((await request('/sessions', paired.deviceToken, 'POST', { requestId: randomUUID(), workspaceId: 'alpha' })).status, 403);
  assert.equal((await request('/device', paired.deviceToken, 'DELETE')).status, 204);
  assert.equal((await request('/sessions/s-alpha', paired.deviceToken)).status, 401);
});

test('all reads require device bearer auth; one-use pairing returns individual access without CORS', async t => {
  const { request, pair } = await setup(t);
  const unauthorized = await request('/workspaces');
  assert.equal(unauthorized.status, 401);
  assert.deepEqual(await unauthorized.json(), { error: { code: 'unauthorized', message: 'Device authorization or pairing offer is invalid.', retryable: false } });
  const paired = await pair();
  const workspaces = await request('/workspaces', paired.deviceToken);
  assert.deepEqual(await workspaces.json(), { items: [{ id: 'alpha', name: 'alpha', canExecute: true }] });
  assert.equal(workspaces.headers.get('access-control-allow-origin'), null);
  assert.equal((await request('/pairings', undefined, 'POST', { pairingToken: paired.offer.pairingToken, deviceName: 'Other' })).status, 401);
  const browser = await request('/workspaces', paired.deviceToken, 'GET', undefined, { Origin: 'http://localhost' });
  assert.equal(browser.status, 403);
  assert.equal((await request('/workspaces?token=not-a-credential', paired.deviceToken)).status, 400);
});
