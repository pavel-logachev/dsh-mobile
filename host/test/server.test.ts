import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { request as httpRequest } from 'node:http';
import { mkdtempSync, mkdirSync, rmSync, readFileSync, writeFileSync, realpathSync, copyFileSync } from 'node:fs';
import { runAdminCli } from '../src/cli.ts';
import { prepareConfiguration, validateBaseUrl } from '../src/config.ts';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import type { TestContext } from 'node:test';
import { startHostServer, createHostServer } from '../src/server.ts';
import { HostState } from '../src/state.ts';
import { createDshAdapter } from '../src/dsh-adapter.ts';
import { createWorkspaceSource } from '../src/workspace-source.ts';
import { opening } from './fixtures/rc2.ts';
import { HostError } from '../src/errors.ts';
import type { HostAdapter, HostSnapshot, DeviceGrants, HostConfiguration } from '../src/types.ts';

const snapshot: HostSnapshot = { session: { id: 's-alpha', title: 'Synthetic conversation', workspaceId: 'alpha', updatedAt: 1, running: false }, messages: [{ id: 'm1', role: 'assistant', text: 'Fixture only', createdAt: 1 }], cursor: 1, hasMore: false, activity: 'idle' };

class TestAdapter implements HostAdapter {
  notifications() { return { subscribe() { return () => {}; }, list: async () => this.listSessions(), evidence: async (id: string) => ({ sessionId: id, workspaceId: this.snapshots.get(id)!.session.workspaceId, cursor: 1, pending: false, completed: [] }) }; }
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

async function setup(t: TestContext, adapter: HostAdapter = new TestAdapter(), extra: { dispatchTimeoutMs?: number } = {}) {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-server-')));
  mkdirSync(join(dir, 'alpha')); mkdirSync(join(dir, 'beta'));
  const config: HostConfiguration = { hostName: 'Test fixture only', bind: '127.0.0.1', port: 0, statePath: join(dir, 'host.sqlite'), allowInsecureLoopback: true, workspaces: ['alpha', 'beta'].map(id => ({ id, name: id, path: join(dir, id) })) };
  const host = await startHostServer({ config, adapter, ...extra });
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

test('blank sessions are hidden only in the index, mobile creates remain visible across restart', async t => {
  const adapter = new TestAdapter();
  adapter.snapshots.get('s-alpha')!.session = { ...snapshot.session, title: '', blank: true, waitingForAnswer: true };
  // Also exercise an upstream that adopts/reuses a blank session on create.
  adapter.createSession = async () => ({ sessionId: 's-alpha' });
  const { request, pair, host, hosts, config } = await setup(t, adapter), { deviceToken } = await pair();
  assert.deepEqual((await (await request('/sessions', deviceToken)).json()).items, []);
  const direct = await (await request('/sessions/s-alpha', deviceToken)).json();
  assert.equal(direct.session.blank, true); assert.equal(direct.session.title, ''); assert.equal(direct.session.waitingForAnswer, true);
  const sse = await request('/sessions/s-alpha/events', deviceToken), reader = sse.body!.getReader();
  try { assert.equal(sse.status, 200); assert.match(new TextDecoder().decode((await reader.read()).value), /"blank":true/); } finally { await reader.cancel(); }
  const created = await request('/sessions', deviceToken, 'POST', { requestId: randomUUID(), workspaceId: 'alpha' });
  assert.equal(created.status, 201);
  assert.equal((await (await request('/sessions', deviceToken)).json()).items[0].id, 's-alpha');
  await host.close();
  const restarted = await startHostServer({ config, adapter }); hosts.push(restarted); const url = (await restarted.start()).baseUrl;
  const items = (await (await fetch(url + '/v1/sessions', { headers: { Authorization: 'Bearer ' + deviceToken } })).json()).items;
  assert.equal(items[0].id, 's-alpha');
});

test('ledger recovery hold blocks fresh commands but keeps receipt replay and reads', async t => {
  const { host, request, pair } = await setup(t), { deviceToken } = await pair();
  const known = randomUUID();
  assert.equal((await request('/sessions', deviceToken, 'POST', { requestId: known, workspaceId: 'alpha' })).status, 201);
  host.state.setRecoveryHold('synthetic-test');
  const blocked = await request('/sessions', deviceToken, 'POST', { requestId: randomUUID(), workspaceId: 'alpha' });
  assert.equal(blocked.status, 503);
  assert.equal((await blocked.json()).error.code, 'recovery_hold');
  assert.equal((await request('/sessions', deviceToken)).status, 200, 'the session index stays readable');
  assert.equal((await request('/sessions/s-alpha', deviceToken)).status, 200, 'history stays readable');
  assert.equal((await request(`/commands/${known}`, deviceToken)).status, 200, 'a receipt stays readable');
  const replay = await request('/sessions', deviceToken, 'POST', { requestId: known, workspaceId: 'alpha' });
  assert.equal(replay.status, 201); assert.equal((await replay.json()).requestId, known);
  host.state.releaseRecovery();
  assert.equal((await request('/sessions', deviceToken, 'POST', { requestId: randomUUID(), workspaceId: 'alpha' })).status, 201);
});

test('absent blank fails open and waiting boolean is projected into the index', async t => {
  const adapter = new TestAdapter(); adapter.snapshots.get('s-alpha')!.session.waitingForAnswer = true;
  const { request, pair } = await setup(t, adapter), { deviceToken } = await pair();
  const item = (await (await request('/sessions', deviceToken)).json()).items[0];
  assert.equal(item.id, 's-alpha'); assert.equal(item.waitingForAnswer, true);
});

test('notification routes authenticate, stream real pages, enforce policy revisions and stream caps', async t => {
  const { request, pair } = await setup(t); const { deviceToken } = await pair();
  assert.equal((await request('/notification-events')).status, 401);
  const settings = { expectedRevision: 0, enabled: true, projects: [], chats: [] };
  assert.equal((await request('/notification-settings', deviceToken, 'PUT', settings)).status, 200);
  assert.equal((await request('/notification-settings', deviceToken, 'PUT', { ...settings, enabled: false })).status, 409);
  const page = await (await request('/notification-events', deviceToken)).json();
  assert.equal(page.resetRequired, true);
  const sse = await request('/notification-events/stream?after=' + page.nextCursor, deviceToken);
  assert.equal(sse.status, 200); const reader = sse.body!.getReader();
  try {
    const frame = new TextDecoder().decode((await reader.read()).value);
    assert.match(frame, /event: notification-page/);
    assert.equal((await request('/notification-events/stream', deviceToken)).status, 429);
  } finally { await reader.cancel(); }
  assert.equal((await request('/notification-events?limit=101', deviceToken)).status, 400);
  assert.equal((await request('/notification-events?after=bad', deviceToken)).status, 400);
  assert.equal((await request('/notification-events?limit=1&limit=2', deviceToken)).status, 400);
});

test('notification stream wakes on commit without waiting for its fallback tick', async t => {
  let listener: any; let cursor = -1;
  const adapter = new TestAdapter();
  adapter.notifications = () => ({ subscribe(fn: any) { listener = fn; return () => {}; }, list: async () => adapter.listSessions(),
    evidence: async () => ({ sessionId: 's-alpha', workspaceId: 'alpha', cursor, pending: false, completed: [{ turn: 1, sourceSeq: 0 }] }) });
  const { request, pair } = await setup(t, adapter), { deviceToken } = await pair();
  await request('/notification-settings', deviceToken, 'PUT', { expectedRevision: 0, enabled: true, projects: [], chats: [] });
  const response = await request('/notification-events/stream', deviceToken), reader = response.body!.getReader();
  try {
    await reader.read();
    t.mock.timers.enable({ apis: ['setInterval'] });
    // The already-created interval is replaced by a frozen clock on subsequent starts;
    // sub-second deadline also excludes the real 1s fallback on this stream.
    cursor = 0; listener('s-alpha', { seq: 0, type: 'turn/end', turn: 1, completed: true, time: Date.now() });
    let timeout: ReturnType<typeof setTimeout>;
    const frame = await Promise.race([reader.read(), new Promise<never>((_, reject) => { timeout = setTimeout(() => reject(Error('commit did not wake stream')), 800); })]).finally(() => clearTimeout(timeout));
    assert.match(new TextDecoder().decode(frame.value), /answer-finished/);
  } finally { await reader.cancel(); }
});

test('notification heartbeat and revoke remain responsive while a page lookup is held', { timeout: 5000 }, async t => {
  const adapter = new TestAdapter(), gate = Promise.withResolvers<void>(), entered = Promise.withResolvers<void>();
  let hold = false;
  adapter.notifications = () => ({ subscribe() { return () => {}; }, list: async () => { if (hold) { entered.resolve(); await gate.promise; } return adapter.listSessions(); },
    evidence: async () => ({ sessionId: 's-alpha', workspaceId: 'alpha', cursor: 0, pending: false, completed: [] }) });
  const { host, request, pair } = await setup(t, adapter), { deviceId, deviceToken } = await pair();
  await request('/notification-settings', deviceToken, 'PUT', { expectedRevision: 0, enabled: true, projects: [], chats: [] });
  const response = await request('/notification-events/stream', deviceToken), reader = response.body!.getReader();
  try {
    await reader.read(); hold = true;
    t.mock.timers.enable({ apis: ['Date'], now: Date.now() });
    // Let the existing fallback enter its held read, then make heartbeat due.
    await entered.promise;
    t.mock.timers.tick(15000);
    let timeout: ReturnType<typeof setTimeout>;
    const part = await Promise.race([reader.read(), new Promise<never>((_, reject) => { timeout = setTimeout(() => reject(Error('heartbeat starved by lookup')), 1500); })]).finally(() => clearTimeout(timeout));
    const frame = new TextDecoder().decode(part.value);
    assert.match(frame, /: heartbeat/, 'an awaited page must not starve liveness');
    host.state.revokeDevice(deviceId);
  } finally { gate.resolve(); await reader.cancel(); }
});

test('disable last notification device returns HTTP reset and sends reset on its existing SSE', { timeout: 10000 }, async t => {
  const adapter = new TestAdapter(); let listener!: (id: string, event: import('../src/notifications.ts').NotificationSource) => void;
  // Use a legacy signal-aware catalog: it reproduced the same poisoned signal.
  const source = { subscribe(fn: any) { listener = fn; return () => {}; },
    async list(signal: AbortSignal) { signal.throwIfAborted(); return adapter.listSessions(); },
    async evidence(id: string) { return { sessionId: id, workspaceId: 'alpha', cursor: 0, pending: true, completed: [] }; },
  };
  adapter.notifications = () => source as any;
  const { request, pair } = await setup(t, adapter), { deviceToken } = await pair();
  await request('/notification-settings', deviceToken, 'PUT', { expectedRevision: 0, enabled: true, projects: [], chats: [] });
  listener('s-alpha', { seq: 0, type: 'tool/call', time: Date.now() });
  // Admit the pending proof before opening the stream.
  let initial: any;
  for (let i = 0; i < 20; i++) { initial = await (await request('/notification-events', deviceToken)).json(); if (initial.pending.length) break; await new Promise(resolve => setImmediate(resolve)); }
  assert.equal(initial.pending.length, 1);
  const stream = await request('/notification-events/stream', deviceToken), reader = stream.body!.getReader();
  try {
    assert.match(new TextDecoder().decode((await reader.read()).value), /notification-page/);
    assert.equal((await request('/notification-settings', deviceToken, 'PUT', { expectedRevision: 1, enabled: false, projects: [], chats: [] })).status, 200);
    const part = await reader.read(); assert.equal(part.done, false);
    const frame = new TextDecoder().decode(part.value); assert.match(frame, /"resetRequired":true/); assert.match(frame, /"pending":\[\]/);
    const response = await request('/notification-events?after=' + initial.nextCursor, deviceToken); assert.equal(response.status, 200);
    const page = await response.json(); assert.equal(page.resetRequired, true); assert.deepEqual(page.pending, []);
  } finally { await reader.cancel(); }
});

test('prompt admission bypasses a five-second catalog and retains point authorization', { timeout: 20000 }, async t => {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'prompt-latency-'))), frame = structuredClone(opening);
  frame.header.cwd = dir; let lists = 0, prompts = 0;
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', workspaces: [{ id: 'alpha', name: 'Alpha', path: dir }],
    sessionHeader: async () => frame.header, sessionStatus: () => ({ running: false, updatedAt: 0 }),
    sessionController: {
      async list() { lists++; await new Promise(r => setTimeout(r, 5000)); return { items: [{ sessionId: 'session-fixture', cwd: dir, running: false, updatedAt: 0, agentAvailable: false }] }; },
      async *follow() { yield frame; }, async page() { throw Error('unexpected page'); }, async projections() { return frame.projections; },
      async prompt() { prompts++; return { accepted: true }; }, async create() { throw Error('unexpected create'); }, cancel() {},
    },
  });
  t.after(() => { adapter.dispose(); rmSync(dir, { recursive: true, force: true }); });
  const { request, pair } = await setup(t, adapter), { deviceToken } = await pair();
  const begin = performance.now();
  const response = await request('/sessions/session-fixture/messages', deviceToken, 'POST', { requestId: randomUUID(), text: 'Synthetic admission only' });
  const ms = performance.now() - begin;
  assert.equal(response.status, 200); assert.equal(prompts, 1); assert.equal(lists, 0); assert.ok(ms < 1000, 'prompt must reach upstream under 1 second');
  t.diagnostic(JSON.stringify({ admissionMs: ms, lists, prompts, slowListMs: 5000 }));
  frame.header.cwd = join(dir, 'missing');
  assert.equal((await request('/sessions/session-fixture/messages', deviceToken, 'POST', { requestId: randomUUID(), text: 'Must not execute' })).status, 404);
  assert.equal(prompts, 1);
});

test('SSE admission validates and publishes the first watch snapshot without a separate reconstruction', async t => {
  const adapter = new TestAdapter(); let snapshots = 0, watches = 0;
  const get = adapter.snapshot.bind(adapter);
  adapter.snapshot = async id => { snapshots++; return get(id); };
  adapter.watch = async function* (id, signal) { watches++; yield await get(id); await new Promise<void>(resolve => { if (signal.aborted) resolve(); else signal.addEventListener('abort', () => resolve(), { once: true }); }); };
  const { request, pair } = await setup(t, adapter), { deviceToken } = await pair();
  const sse = await request('/sessions/s-alpha/events', deviceToken), reader = sse.body!.getReader();
  try { assert.equal(sse.status, 200); assert.match(new TextDecoder().decode((await reader.read()).value), /Fixture only/); assert.equal(watches, 1); assert.equal(snapshots, 0, 'SSE must not open the same chat once before watch'); }
  finally { await reader.cancel(); }
});

test('GET and SSE authorize the authoritative point snapshot without scanning the session catalog', async t => {
  const adapter = new TestAdapter(); let catalogs = 0;
  const list = adapter.listSessions.bind(adapter);
  adapter.listSessions = async () => { catalogs++; return list(); };
  const { host, request, pair } = await setup(t, adapter), { deviceId, deviceToken } = await pair();
  const baseline = catalogs;
  assert.equal((await request('/sessions/s-alpha', deviceToken)).status, 200);
  const sse = await request('/sessions/s-alpha/events', deviceToken), reader = sse.body!.getReader();
  try { assert.equal(sse.status, 200); await reader.read(); }
  finally { await reader.cancel(); }
  assert.equal(catalogs, baseline, 'point reads must not pay for the complete persisted catalog');
  assert.equal((await request('/sessions/s-beta', deviceToken)).status, 404, 'snapshot scope still hides unreadable sessions');
  host.state.replaceDeviceGrants(deviceId, { readWorkspaceIds: ['beta'], executeWorkspaceIds: [] });
  assert.equal((await request('/sessions/s-alpha', deviceToken)).status, 404, 'new grants are checked on every read');
});

for (const lane of ['', '/events']) test(`a broken point ${lane || 'GET'} hides unreadable and archived sessions before exposing an opening error`, async t => {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-broken-read-')));
  for (const id of ['alpha', 'beta']) mkdirSync(join(dir, id));
  const workspaces = ['alpha', 'beta'].map(id => ({ id, name: id, path: join(dir, id) }));
  const baseSource = await createWorkspaceSource({ workspaces }), archived = new Set<string>();
  const source = { ...baseSource, archivedSessionIds: () => archived };
  let headers = 0, catalogs = 0, openings = 0;
  let held: { entered: ReturnType<typeof Promise.withResolvers<void>>; release: ReturnType<typeof Promise.withResolvers<void>> } | undefined;
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', workspaceSource: source,
    sessionStatus: () => ({ running: false, updatedAt: 0 }),
    sessionHeader: async (id, signal) => { headers++; signal.throwIfAborted(); return { ...opening.header, id, cwd: join(dir, id === 's-alpha' ? 'alpha' : 'beta') }; },
    sessionController: {
      async list() { catalogs++; throw new Error('Failure admission must not scan the catalog'); },
      async *follow() { openings++; held?.entered.resolve(); if (held) await held.release.promise; throw Object.assign(new Error('PRIVATE corrupt event/projection detail'), { code: 'SESSION_QUERY_CORRUPT_SESSION' }); },
      async page() { return {}; }, async projections() { return {}; },
      async create() { throw new Error('Not used'); }, async prompt() { throw new Error('Not used'); }, cancel() {},
    },
  });
  let host: Awaited<ReturnType<typeof startHostServer>> | undefined;
  t.after(async () => { held?.release.resolve(); adapter.dispose(); await host?.close(); rmSync(dir, { recursive: true, force: true }); });
  host = await startHostServer({ config: { hostName: 'Synthetic broken read', bind: '127.0.0.1', port: 0, statePath: join(dir, 'state.sqlite'), allowInsecureLoopback: true, workspaces }, workspaceSource: source, adapter });
  const { baseUrl } = await host.start();
  const device = host.state.consumePairing(host.state.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] }).pairingToken, 'Synthetic phone');
  const get = (id: string) => fetch(`${baseUrl}/v1/sessions/${id}${lane}`, { headers: { Authorization: `Bearer ${device.deviceToken}` }, signal: AbortSignal.timeout(5000) });
  const denied = await get('s-beta');
  assert.equal(denied.status, 404); assert.equal((await denied.json()).error.code, 'not_found');
  archived.add('s-alpha');
  const hidden = await get('s-alpha');
  assert.equal(hidden.status, 404); assert.equal((await hidden.json()).error.code, 'not_found');
  archived.clear();
  const broken = await get('s-alpha');
  assert.equal(broken.status, 500); const body = await broken.json();
  assert.equal(body.error.code, 'internal_error'); assert.doesNotMatch(JSON.stringify(body), /PRIVATE|corrupt event/);
  assert.equal(catalogs, 0); assert.equal(headers, 2, 'archival short-circuits without header I/O; other failures use fresh metadata');
  // The upstream error is shared, but admission is not: denied and permitted
  // devices observing the same broken opening must receive different envelopes.
  const other = host.state.consumePairing(host.state.createPairing({ readWorkspaceIds: ['beta'], executeWorkspaceIds: [] }).pairingToken, 'Synthetic beta phone');
  const before = openings;
  held = { entered: Promise.withResolvers<void>(), release: Promise.withResolvers<void>() };
  const a = get('s-beta'); await held.entered.promise;
  const b = fetch(`${baseUrl}/v1/sessions/s-beta${lane}`, { headers: { Authorization: `Bearer ${other.deviceToken}` }, signal: AbortSignal.timeout(5000) });
  await new Promise(resolve => setTimeout(resolve, 50)); held.release.resolve();
  const [ar, br] = await Promise.all([a, b]);
  assert.equal(ar.status, 404); assert.equal(br.status, 500); await ar.body?.cancel(); await br.body?.cancel();
  assert.equal(openings - before, 1); assert.equal(headers, 4, 'each waiter resolves current scope independently'); assert.equal(catalogs, 0);
});

for (const lane of ['', '/events']) test(`failed ${lane || 'GET'} point reads hide all non-abort header or scope resolution errors`, async t => {
  const adapter = new TestAdapter();
  let failure: Error = Object.assign(new Error('PRIVATE unsupported/corrupt header'), { code: 'SESSION_FORMAT_UNSUPPORTED' });
  const scoped: HostAdapter = Object.assign(adapter, { async sessionScope() { throw failure; } });
  adapter.snapshot = async () => { throw new Error('PRIVATE opening failure'); };
  const { request, pair } = await setup(t, scoped), { deviceToken } = await pair();
  for (const error of [failure, new HostError('unavailable'), new HostError('workspace_registry_unavailable')]) {
    failure = error;
    for (const id of ['s-alpha', 's-beta']) {
      const response = await request(`/sessions/${id}${lane}`, deviceToken);
      assert.equal(response.status, 404); const body = await response.json();
      assert.equal(body.error.code, 'not_found'); assert.doesNotMatch(JSON.stringify(body), /PRIVATE|header|scope/);
    }
  }
  failure = new DOMException('Upstream header read cancelled', 'AbortError');
  const aborted = await request(`/sessions/s-alpha${lane}`, deviceToken);
  assert.equal(aborted.status, 500, 'an upstream AbortError keeps its previous safe error, not a hiding 404');
  assert.equal((await aborted.json()).error.code, 'internal_error');
});

for (const lane of ['', '/events']) test(`disconnect while resolving failed ${lane || 'GET'} scope aborts the read without publishing a hiding response`, { timeout: 5000 }, async t => {
  const adapter = new TestAdapter(), entered = Promise.withResolvers<void>(), stopped = Promise.withResolvers<unknown>();
  const scoped: HostAdapter = Object.assign(adapter, { async sessionScope(_id: string, signal: AbortSignal): Promise<string> {
    entered.resolve();
    return new Promise<never>((_resolve, reject) => signal.addEventListener('abort', () => { stopped.resolve(signal.reason); reject(signal.reason); }, { once: true }));
  } });
  adapter.snapshot = async () => { throw new Error('Synthetic opening fault'); };
  const { host, baseUrl, pair } = await setup(t, scoped), { deviceToken } = await pair();
  let published = 0;
  host.server.on('request', (_req, res) => { res.once('finish', () => { published++; }); });
  const controller = new AbortController();
  const request = fetch(`${baseUrl}/v1/sessions/s-alpha${lane}`, { headers: { Authorization: `Bearer ${deviceToken}` }, signal: controller.signal });
  await entered.promise; controller.abort();
  await assert.rejects(request, { name: 'AbortError' });
  assert.equal((await stopped.promise as Error).name, 'AbortError');
  await host.close(); assert.equal(published, 0);
});

for (const lane of ['', '/events']) test(`revocation during a pending fast ${lane || 'GET'} opening prevents private publication`, async t => {
  const adapter = new TestAdapter(), entered = Promise.withResolvers<void>(), release = Promise.withResolvers<void>();
  const snapshot = adapter.snapshot.bind(adapter);
  adapter.snapshot = async id => { entered.resolve(); await release.promise; return snapshot(id); };
  const { host, request, pair } = await setup(t, adapter), { deviceId, deviceToken } = await pair();
  const pending = request('/sessions/s-alpha' + lane, deviceToken);
  await entered.promise; host.state.revokeDevice(deviceId); release.resolve();
  const response = await pending;
  assert.equal(response.status, 401); assert.doesNotMatch(JSON.stringify(await response.json()), /Fixture only/);
});

test('runningSubagents is an additive validated summary and snapshot field, independent of main turn state', async t => {
  const adapter = new TestAdapter(), value = adapter.snapshots.get('s-alpha')!;
  (value.session as any).runningSubagents = 1; (value as any).runningSubagents = 1;
  const { request, pair } = await setup(t, adapter), { deviceToken } = await pair();
  assert.equal((await (await request('/sessions', deviceToken)).json()).items[0].runningSubagents, 1);
  const first = await (await request('/sessions/s-alpha', deviceToken)).json();
  assert.equal(first.runningSubagents, 1); assert.equal(first.session.runningSubagents, 1);
  assert.equal(first.session.running, false); assert.equal(first.activity, 'idle');
  const sse = await request('/sessions/s-alpha/events', deviceToken), reader = sse.body!.getReader();
  try { const text = new TextDecoder().decode((await reader.read()).value); assert.equal(JSON.parse(text.split('\n').find(line => line.startsWith('data: '))!.slice(6)).runningSubagents, 1); }
  finally { await reader.cancel(); }
  (value.session as any).runningSubagents = 0; (value as any).runningSubagents = 0;
  assert.equal((await (await request('/sessions/s-alpha', deviceToken)).json()).runningSubagents, 0);
  for (const invalid of [-1, 1.5, Number.MAX_SAFE_INTEGER + 1, '1']) {
    (value.session as any).runningSubagents = invalid; (value as any).runningSubagents = invalid;
    for (const suffix of ['', '/events']) assert.equal((await request('/sessions/s-alpha' + suffix, deviceToken)).status, 500);
    assert.equal((await request('/sessions', deviceToken)).status, 500);
  }
});

test('a partial history notice and its certain messages survive GET and SSE even when newest activity is known', async t => {
  const adapter = new TestAdapter(), value = adapter.snapshots.get('s-alpha')!;
  value.hasMore = true;
  value.notice = 'This bounded history crosses a DSH surface replacement. Open the desktop to view the authoritative conversation.';
  const { request, pair } = await setup(t, adapter); const { deviceToken } = await pair();
  const get = await (await request('/sessions/s-alpha', deviceToken)).json();
  assert.equal(get.activity, 'idle'); assert.equal(get.notice, value.notice);
  assert.deepEqual(get.messages, value.messages); assert.equal(get.hasMore, true);
  const sse = await request('/sessions/s-alpha/events', deviceToken); const reader = sse.body!.getReader();
  try {
    let text = ''; while (!text.includes('\n\n')) text += new TextDecoder().decode((await reader.read()).value);
    const event = JSON.parse(text.split('\n').find(line => line.startsWith('data: '))!.slice(6));
    assert.equal(event.activity, 'idle'); assert.equal(event.notice, value.notice);
    assert.deepEqual(event.messages, value.messages); assert.equal(event.hasMore, true);
  } finally { await reader.cancel(); }
  value.notice = 'PRIVATE ADAPTER DIAGNOSTIC';
  assert.equal((await (await request('/sessions/s-alpha', deviceToken)).json()).notice, undefined, 'never export arbitrary adapter diagnostics');
});

test('quiet extensions survive GET and SSE and count toward the payload ceiling', async t => {
  const adapter = new TestAdapter(), value = adapter.snapshots.get('s-alpha')!;
  value.activity = 'running'; value.session.running = true;
  value.activityDetail = { turnStartedAt: 123, tool: 'read' };
  value.messages[0] = { ...value.messages[0]!, kind: 'agent_event', serviceText: '\n\n<system-reminder>Full detail</system-reminder>' } as typeof value.messages[number];
  const { request, pair } = await setup(t, adapter); const { deviceToken } = await pair();
  const get = await (await request('/sessions/s-alpha', deviceToken)).json();
  assert.equal(get.messages[0].kind, 'agent_event'); assert.equal(get.messages[0].serviceText, value.messages[0].serviceText);
  assert.deepEqual(get.activityDetail, value.activityDetail);
  const sse = await request('/sessions/s-alpha/events', deviceToken); const reader = sse.body!.getReader();
  try {
    let text = ''; while (!text.includes('\n\n')) text += new TextDecoder().decode((await reader.read()).value);
    const event = JSON.parse(text.split('\n').find(line => line.startsWith('data: '))!.slice(6));
    assert.equal(event.messages[0].kind, 'agent_event'); assert.equal(event.messages[0].serviceText, value.messages[0].serviceText);
    assert.deepEqual(event.activityDetail, value.activityDetail);
  } finally { await reader.cancel(); }
  value.messages[0].serviceText = 'x'.repeat(2 * 1024 * 1024);
  assert.equal((await request('/sessions/s-alpha', deviceToken)).status, 413);
  assert.equal((await request('/sessions/s-alpha/events', deviceToken)).status, 413);
});

test('quiet extensions reject invalid adapter values at GET and SSE boundaries', async t => {
  const adapter = new TestAdapter(), value = adapter.snapshots.get('s-alpha')!;
  const { request, pair } = await setup(t, adapter); const { deviceToken } = await pair();
  for (const invalid of [{ kind: 'invalid' }, { serviceText: 42 }]) {
    Object.assign(value.messages[0]!, invalid);
    for (const suffix of ['', '/events']) assert.equal((await request('/sessions/s-alpha' + suffix, deviceToken)).status, 500);
    delete value.messages[0]!.kind; delete value.messages[0]!.serviceText;
  }
  value.activity = 'running';
  for (const detail of [{ turnStartedAt: -1 }, { turnStartedAt: 1, tool: 'unsafe\nargs' }]) {
    value.activityDetail = detail;
    for (const suffix of ['', '/events']) assert.equal((await request('/sessions/s-alpha' + suffix, deviceToken)).status, 500);
  }
});

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
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-cli-')));
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
  const invitationPath = join(dir, 'invitations', 'admin.private.json');
  assert.equal(await runAdminCli(['pair', '--config', configPath, '--read', 'default', '--execute', 'default', '--output', invitationPath], io), 0);
  const invitation = JSON.parse(readFileSync(invitationPath, 'utf8'));
  assert.doesNotMatch(output + errors, /pairingToken|accessToken|token_hash/);
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

for (const stopDuringUpload of [false, true]) test(stopDuringUpload ? 'host close preserves admitted file bytes until held upload settles' : 'accepted file send removes staged bytes after settlement', { timeout: 15000 }, async t => {
  const entered = Promise.withResolvers<void>(), gate = Promise.withResolvers<void>(), settled = Promise.withResolvers<void>();
  const payload = 'Файл 😀\r\n'.repeat(10000);
  const adapter: HostAdapter = new TestAdapter();
  adapter.phoneCapabilities = () => ({ steer: false, queue: false, rename: false, models: false, questions: false, attachments: true });
  adapter.upload = async (_id, input) => {
    const iterator = input.data[Symbol.asyncIterator](), first = await iterator.next();
    assert.equal(first.done, false); entered.resolve(); await gate.promise;
    const chunks: Uint8Array[] = [first.value!];
    for (let next = await iterator.next(); !next.done; next = await iterator.next()) chunks.push(next.value);
    assert.equal(Buffer.concat(chunks).toString(), payload);
    return { ref: { id: 'file', name: input.name, size: input.size, mime: input.mime, kind: 'file' }, upstream: { type: 'file', receiptId: 'receipt' } };
  };
  adapter.reserveFileUploads = async () => ({ release() { settled.resolve(); } });
  const { host, hosts, config, baseUrl, request, pair } = await setup(t, adapter), { deviceToken } = await pair();
  const fs = await import('node:fs/promises'), directory = join(config.statePath, '..', 'mobile-uploads');
  const picked = await fetch(baseUrl + '/v1/sessions/s-alpha/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + deviceToken, 'Content-Type': 'text/plain', 'X-File-Name': encodeURIComponent('Файл с пробелом.txt') }, body: payload });
  assert.equal(picked.status, 201); const { uploadId } = await picked.json();
  const [name] = await fs.readdir(directory); assert.ok(name); const path = join(directory, name);
  const sending = request('/sessions/s-alpha/messages', deviceToken, 'POST', { requestId: randomUUID(), text: 'Synthetic', uploadIds: [uploadId] });
  try {
    await entered.promise;
    if (stopDuringUpload) {
      assert.equal((await sending).status, 202);
      await host.close();
      const restarted = await startHostServer({ config, adapter }); hosts.push(restarted);
      assert.equal(await fs.readFile(path, 'utf8'), payload, 'close and reload must not remove bytes still owned by the adapter');
    }
  } finally { gate.resolve(); }
  await settled.promise;
  if (!stopDuringUpload) {
    const response = await sending;
    let receipt = await response.json();
    for (let i = 0; i < 100 && receipt.status === 'pending'; i++) {
      const result = await request('/commands/' + receipt.requestId, deviceToken); receipt = await result.json();
      if (receipt.status === 'pending') await new Promise(resolve => setImmediate(resolve));
    }
    assert.equal(receipt.status, 'accepted');
  }
  for (let i = 0; i < 100 && (await fs.readdir(directory)).length; i++) await new Promise(resolve => setTimeout(resolve, 10));
  assert.deepEqual(await fs.readdir(directory), []);
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
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-stream-order-')));
  const adapter = new TestAdapter();
  const current = structuredClone(snapshot);
  current.messages = [{ id: 'new', role: 'assistant', text: 'NEW AUTHORITATIVE TEXT', createdAt: 2 }]; current.cursor = 2;
  adapter.watch = async function* (_id, signal) {
    yield structuredClone(snapshot); // Admission now consumes this same watch opening.
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
  let pulseTimer: ReturnType<typeof setTimeout> | undefined;
  const pulse = await Promise.race([
    reader.read(),
    new Promise<never>((_, reject) => { pulseTimer = setTimeout(() => reject(Error('heartbeat starved by scope I/O')), 1500); }),
  ]).finally(() => { if (pulseTimer) clearTimeout(pulseTimer); });
  assert.match(new TextDecoder().decode(pulse.value), /: heartbeat/, 'scope I/O must not starve stream liveness');
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

test('an admitted dispatch that exceeds its deadline settles uncertain instead of staying pending', { timeout: 5000 }, async t => {
  const adapter = new TestAdapter();
  adapter.prompt = async (_id, _text, _requestId, signal) => new Promise((_, reject) => {
    signal.addEventListener('abort', () => reject(signal.reason), { once: true });
  });
  const { request, pair } = await setup(t, adapter, { dispatchTimeoutMs: 20 }), { deviceToken } = await pair();
  const requestId = randomUUID();
  const response = await request('/sessions/s-alpha/messages', deviceToken, 'POST', { requestId, text: 'Synthetic held dispatch' });
  assert.equal(response.status, 202);
  let receipt = await response.json();
  for (let i = 0; i < 100 && receipt.status === 'pending'; i++) {
    await new Promise(resolve => setTimeout(resolve, 10));
    receipt = await (await request('/commands/' + requestId, deviceToken)).json();
  }
  assert.equal(receipt.status, 'uncertain');
});

test('closing during create writes no mobile-session row after the injected SQLite remains open', { timeout: 5000 }, async t => {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-close-create-')));
  mkdirSync(join(dir, 'alpha'));
  const state = new HostState(join(dir, 'host.sqlite'));
  const entered = Promise.withResolvers<void>(), gate = Promise.withResolvers<void>();
  const adapter = new TestAdapter();
  adapter.createSession = async () => { entered.resolve(); await gate.promise; return { sessionId: 'late-session' }; };
  const config: HostConfiguration = { hostName: 'Close fixture', bind: '127.0.0.1', port: 0, statePath: join(dir, 'host.sqlite'), allowInsecureLoopback: true, workspaces: [{ id: 'alpha', name: 'alpha', path: join(dir, 'alpha') }] };
  const host = await createHostServer({ config, adapter, state });
  t.after(async () => { gate.resolve(); await host.close(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  const baseUrl = (await host.start()).baseUrl;
  const offer = state.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: ['alpha'] });
  const paired = await fetch(baseUrl + '/v1/pairings', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ pairingToken: offer.pairingToken, deviceName: 'Close phone' }) });
  const { deviceToken } = await paired.json() as { deviceToken: string };
  const requestId = randomUUID();
  const sending = fetch(baseUrl + '/v1/sessions', { method: 'POST', headers: { Authorization: 'Bearer ' + deviceToken, 'Content-Type': 'application/json' }, body: JSON.stringify({ requestId, workspaceId: 'alpha' }) }).catch(() => undefined);
  await entered.promise; await host.close();
  assert.equal(state.mobileSessionVisible('session-' + requestId.toLowerCase(), true), false, 'a closing create must not retain the requested placeholder');
  gate.resolve(); await sending;
  assert.equal(state.mobileSessionVisible('late-session', true), false, 'a closing create must not retain the returned ID');
});

test('unsettled dispatches are capped before admission and the overflow gets no receipt', { timeout: 10000 }, async t => {
  const adapter = new TestAdapter(), gate = Promise.withResolvers<void>();
  let entered = 0;
  adapter.prompt = async (_id, _text, _requestId, signal) => {
    entered++;
    await Promise.race([gate.promise, new Promise((_, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }))]);
  };
  const { request, pair } = await setup(t, adapter, { dispatchTimeoutMs: 5000 }), { deviceToken } = await pair(), second = await pair();
  const tokens = [deviceToken, second.deviceToken];
  const accepted = await Promise.all(Array.from({ length: 32 }, (_, index) => {
    const requestId = randomUUID();
    return request('/sessions/s-alpha/messages', tokens[index % 2], 'POST', { requestId, text: 'Held ' + requestId }).then(async response => ({ response, requestId }));
  }));
  for (const { response } of accepted) assert.equal(response.status, 202);
  for (let i = 0; i < 100 && entered < 32; i++) await new Promise(resolve => setImmediate(resolve));
  assert.equal(entered, 32);
  const overflowId = randomUUID();
  const overflow = await request('/sessions/s-alpha/messages', deviceToken, 'POST', { requestId: overflowId, text: 'Overflow' });
  assert.equal(overflow.status, 429);
  assert.equal((await overflow.json()).error.code, 'rate_limited');
  assert.equal((await request('/commands/' + overflowId, deviceToken)).status, 404);
  gate.resolve();
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

test('session pagination is keyset and does not repeat an item moved above the cut', async t => {
  const { adapter, request, pair } = await setup(t);
  const all = await pair({ readWorkspaceIds: ['*'], executeWorkspaceIds: ['*'] });
  adapter.snapshots.get('s-alpha')!.session.updatedAt = 100;
  adapter.snapshots.get('s-beta')!.session.updatedAt = 50;
  const first = await (await request('/sessions?limit=1', all.deviceToken)).json();
  assert.deepEqual(first.items.map((item: { id: string }) => item.id), ['s-alpha']);
  // s-beta moves above the cut before page two; an offset would repeat s-alpha.
  adapter.snapshots.get('s-beta')!.session.updatedAt = 200;
  const next = await (await request(`/sessions?limit=1&cursor=${first.nextCursor}`, all.deviceToken)).json();
  assert.deepEqual(next.items, []);
  assert.equal(next.nextCursor, null);
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

for (const operation of ['create', 'prompt', 'mutate', 'snapshot'] as const) test('H03 host deadline owns terminal receipt for held ' + operation, { timeout: 5000 }, async t => {
  const adapter = new TestAdapter(), gate = Promise.withResolvers<void>(), entered = Promise.withResolvers<void>();
  let calls = 0;
  const hold = async () => { calls++; entered.resolve(); await gate.promise; };
  if (operation === 'create') adapter.createSession = async () => { await hold(); return { sessionId: 'late-session' }; };
  if (operation === 'prompt') adapter.prompt = hold;
  if (operation === 'mutate') {
    (adapter as HostAdapter).mutateSession = hold;
    (adapter as HostAdapter).phoneCapabilities = () => ({ steer: false, queue: false, rename: true, models: false, questions: false, attachments: false });
  }
  const { request, pair, host } = await setup(t, adapter, { dispatchTimeoutMs: 30 }), { deviceToken, deviceId } = await pair();
  const requestId = randomUUID();
  if (operation === 'snapshot') {
    const snapshot = adapter.snapshot.bind(adapter);
    adapter.snapshot = async id => { if (host.state.getCommand(deviceId, requestId)) await hold(); return snapshot(id); };
  }
  const path = operation === 'create' ? '/sessions' : '/sessions/s-alpha' + (operation === 'mutate' ? '/title' : '/messages');
  const body = operation === 'create' ? { requestId, workspaceId: 'alpha' } : operation === 'mutate' ? { requestId, title: 'Synthetic' } : { requestId, text: 'Synthetic' };
  const sending = request(path, deviceToken, 'POST', body);
  t.after(() => gate.resolve());
  await entered.promise;
  await new Promise(r => setTimeout(r, 70));
  assert.equal((await (await request('/commands/' + requestId, deviceToken)).json()).status, 'uncertain');
  gate.resolve(); await sending;
  await new Promise(r => setImmediate(r));
  assert.equal((await (await request('/commands/' + requestId, deviceToken)).json()).status, 'uncertain', 'late settlement cannot overwrite deadline');
  assert.equal(host.state.mobileSessionVisible('late-session', true), false);
  assert.equal(calls, 1);
});

test('H07 equal timestamps use one total order across every keyset page', async t => {
  const adapter = new TestAdapter(); adapter.snapshots.clear();
  const ids = ['a', 'B', '!', 'é', 'e\u0301', 'Ж', '😀'];
  for (const id of ids) adapter.snapshots.set(id, { ...structuredClone(snapshot), session: { ...snapshot.session, id } });
  const { request, pair } = await setup(t, adapter), { deviceToken } = await pair();
  const seen: string[] = []; let cursor: string | null = null;
  do {
    const response = await request('/sessions?limit=1' + (cursor ? '&cursor=' + cursor : ''), deviceToken);
    assert.equal(response.status, 200); const page = await response.json(); seen.push(...page.items.map((s: any) => s.id)); cursor = page.nextCursor;
    assert.ok(seen.length <= ids.length, 'pagination must terminate');
  } while (cursor);
  assert.deepEqual(seen, ['!', 'B', 'a', 'e\u0301', 'é', 'Ж', '😀']);
});

for (const [label, input, expected] of [
  ['controls', { code: 'HTTP_400', message: '  Привет\r\n\0мир\u007f!  ', unexpected: 'internal' }, { code: 'HTTP_400', message: 'Привет мир !' }],
  ['astral boundary', { message: 'x'.repeat(511) + '😀' }, { message: 'x'.repeat(511) }],
  ['lone surrogate', { message: 'x\ud800y' }, { message: 'x�y' }],
  ['empty', { message: '', code: '' }, { message: 'The turn failed.' }],
  ['wrong types', { message: 42, code: ['bad'] }, { message: 'The turn failed.' }],
  ['huge', { message: '😀'.repeat(100000), code: 'x'.repeat(100) }, { message: '😀'.repeat(256) }],
  ['wrong object', ['bad'], { message: 'The turn failed.' }],
] as const) test('H10 snapshot failure is allowlisted and Unicode safe: ' + label, async t => {
  const adapter = new TestAdapter(); (adapter.snapshots.get('s-alpha') as any).failure = input;
  const { request, pair } = await setup(t, adapter), { deviceToken } = await pair();
  const response = await request('/sessions/s-alpha', deviceToken); assert.equal(response.status, 200);
  const result = await response.json(); assert.deepEqual(result.failure, expected); assert.equal(result.failure.message.isWellFormed(), true);
});

for (const manual of [false, true]) test('recovery startup blocks a restored lost requestId before first admission: manual=' + manual, async t => {
  const adapter = new TestAdapter();
  const { host, hosts, config, pair } = await setup(t, adapter), device = await pair();
  await host.close(); const backup = config.statePath + '.backup'; copyFileSync(config.statePath, backup);
  copyFileSync(config.statePath + '.ledger.json', backup + '.ledger.json');
  const active = await startHostServer({ config, adapter }); hosts.push(active); const activeUrl = (await active.start()).baseUrl;
  const requestId = randomUUID(), body = { requestId, text: 'Synthetic lost receipt' }, headers = { Authorization: 'Bearer ' + device.deviceToken, 'Content-Type': 'application/json' };
  assert.equal((await fetch(activeUrl + '/v1/sessions/s-alpha/messages', { method: 'POST', headers, body: JSON.stringify(body) })).status, 200);
  assert.equal(adapter.prompts.length, 1); await active.close();
  copyFileSync(backup, config.statePath);
  if (manual) {
    // Both artifacts restored: automatic detection is impossible. Operator hold is
    // persisted off-line before any listener is opened, never retroactively.
    copyFileSync(backup + '.ledger.json', config.statePath + '.ledger.json');
    const admin = new HostState(config.statePath); admin.setRecoveryHold('restore_reconciliation'); admin.close();
  }
  const restored = await createHostServer({ config, adapter }); hosts.push(restored);
  assert.equal(restored.state.ledgerStatus().hold, true, 'hold precedes listen');
  let checkedAtListen = false;
  restored.server.once('listening', () => { checkedAtListen = true; assert.equal(restored.state.ledgerStatus().hold, true); });
  const url = (await restored.start()).baseUrl;
  const send = () => fetch(url + '/v1/sessions/s-alpha/messages', { method: 'POST', headers, body: JSON.stringify(body) });
  assert.equal(checkedAtListen, true);
  assert.equal((await fetch(url + '/v1/commands/' + requestId, { headers })).status, 404);
  const blocked = await send(); assert.equal(blocked.status, 503); assert.equal((await blocked.json()).error.code, 'recovery_hold'); assert.equal(adapter.prompts.length, 1);
  restored.state.releaseRecovery();
  assert.equal((await send()).status, 200); assert.equal(adapter.prompts.length, 2, 'explicit release has no wire epoch fencing; a lost ID is a new admission');
  assert.equal((await send()).status, 200); assert.equal(adapter.prompts.length, 2, 'the new persisted receipt deduplicates subsequent replay');
});

test('H03 terminal deadline does not free capacity owned by nonsettled upstream calls', { timeout: 10000 }, async t => {
  const adapter = new TestAdapter(), gate = Promise.withResolvers<void>(); let calls = 0;
  adapter.createSession = async () => { calls++; await gate.promise; return { sessionId: 'late' }; };
  const { request, pair, host } = await setup(t, adapter, { dispatchTimeoutMs: 60 }); const devices = [await pair(), await pair()];
  const ids: { requestId: string; deviceId: string }[] = [];
  try {
    await Promise.all(Array.from({ length: 32 }, async (_, i) => { const device = devices[i % 2]!, requestId = randomUUID(); ids.push({ requestId, deviceId: device.deviceId }); await request('/sessions', device.deviceToken, 'POST', { requestId, workspaceId: 'alpha' }); }));
    await new Promise(r => setTimeout(r, 100)); assert.equal(calls, 32);
    assert.ok(ids.every(item => host.state.getCommand(item.deviceId, item.requestId)?.receipt.status === 'uncertain'));
    const overflow = randomUUID(); assert.equal((await request('/sessions', devices[0]!.deviceToken, 'POST', { requestId: overflow, workspaceId: 'alpha' })).status, 429);
    assert.equal(host.state.getCommand(devices[0]!.deviceId, overflow), undefined);
  } finally { gate.resolve(); }
  await new Promise(r => setImmediate(r)); assert.ok(ids.every(item => host.state.getCommand(item.deviceId, item.requestId)?.receipt.status === 'uncertain'));
  assert.equal((await request('/sessions', devices[0]!.deviceToken, 'POST', { requestId: randomUUID(), workspaceId: 'alpha' })).status, 201);
});

test('H03 failed deadline persistence aborts observation without an uncaught timer error or invented receipt', { timeout: 5000 }, async t => {
  const adapter = new TestAdapter(), gate = Promise.withResolvers<void>(); let seen!: AbortSignal;
  adapter.createSession = async (_input, signal) => { seen = signal; await gate.promise; return { sessionId: 'late' }; };
  const { request, pair, host } = await setup(t, adapter, { dispatchTimeoutMs: 60 }), device = await pair();
  const finish = host.state.finishCommand.bind(host.state); let fail = true;
  host.state.finishCommand = ((...args) => { if (fail) throw new Error('synthetic durable write failure'); return finish(...args); }) as typeof host.state.finishCommand;
  try {
    const id = randomUUID(); await request('/sessions', device.deviceToken, 'POST', { requestId: id, workspaceId: 'alpha' });
    await new Promise(r => setTimeout(r, 100)); assert.equal(seen.aborted, true);
    assert.equal(host.state.getCommand(device.deviceId, id)?.receipt.status, 'pending', 'a failed write cannot be represented as persisted uncertain');
  } finally { fail = false; await host.close(); gate.resolve(); }
});
