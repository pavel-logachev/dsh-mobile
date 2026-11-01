import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { TestContext } from 'node:test';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, mkdirSync, realpathSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { startHostServer } from '../src/server.ts';
import { createDshAdapter } from '../src/dsh-adapter.ts';
import type { DshObservation } from '../src/dsh-adapter.ts';
import { opening, summary } from './fixtures/rc2.ts';

async function setup(t: TestContext) {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-outcomes-'))); mkdirSync(join(dir, 'проект с пробелами'));
  const workspace = { id: 'alpha', name: 'Synthetic', path: join(dir, 'проект с пробелами') };
  const frame = structuredClone(opening); frame.header.cwd = workspace.path;
  const listeners = new Set<(e: DshObservation) => void>(), journal: unknown[] = [], calls: { offset: number; length: number }[] = [], prompted: string[] = [];
  let beforeJournalRead = async () => {};
  const row = { ...structuredClone(summary), cwd: workspace.path };
  const options = {
    dshVersion: '0.2.1-rc.5', workspaces: [workspace], sessionHeader: async () => frame.header,
    sessionController: {
      async list() { return { items: [row] }; },
      async *follow(_req: unknown, signal: AbortSignal) { try { yield structuredClone(frame); throw new Error('must not resume'); } finally { assert.ok(signal.aborted); } },
      async page() { return { records: [], hasMore: false }; }, async projections() { return frame.projections; },
      async create() { return { sessionId: 'synthetic' }; }, async prompt(input: { requestId: string }) { prompted.push(input.requestId); }, cancel() {},
    },
    promptJournal: { async open() { return { async read(offset: number, length: number) { calls.push({ offset, length }); await beforeJournalRead(); return { events: journal.slice(offset, offset + length) }; }, async close() {} }; } },
    events: { subscribe(_id: string, listener: (e: DshObservation) => void) { listeners.add(listener); return () => { listeners.delete(listener); }; } },
  };
  let adapter = await createDshAdapter(options);
  const config = { hostName: 'Synthetic', bind: '127.0.0.1', port: 0, allowInsecureLoopback: true, statePath: join(dir, 'host.sqlite'), workspaces: [workspace] };
  let host = await startHostServer({ config, adapter }), baseUrl = (await host.start()).baseUrl;
  t.after(async () => { await host.close(); adapter.dispose(); rmSync(dir, { recursive: true, force: true }); });
  async function request(path: string, token?: string, method = 'GET', body?: unknown, abort?: AbortSignal) {
    return fetch(baseUrl + '/v1' + path, { method, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}), signal: abort ?? AbortSignal.timeout(5000) });
  }
  function pair(read = ['alpha']) {
    const offer = host.state.createPairing({ readWorkspaceIds: read, executeWorkspaceIds: read });
    const result = host.state.consumePairing(offer.pairingToken, 'Synthetic phone');
    return result;
  }
  function append(type: string, data: unknown) {
    const e = { type, data, seq: journal.length, time: 1000 + journal.length }; journal.push(e);
    for (const listener of listeners) listener({ type: 'event', event: e });
  }
  function admitted(deviceId: string, id: string, sessionId = row.sessionId, operation = 'prompt', status: 'accepted' | 'rejected' | 'uncertain' = 'accepted') {
    host.state.admitCommand(deviceId, id, operation, 'a'.repeat(64), 'alpha', sessionId);
    host.state.finishCommand(deviceId, id, status);
  }
  const url = (ids: string[], extra = '') => '/sessions/' + row.sessionId + '/prompt-outcomes?' + ids.map(id => 'requestId=' + encodeURIComponent(id)).join('&') + extra;
  async function send(token: string, id: string) { return request('/sessions/' + row.sessionId + '/messages', token, 'POST', { requestId: id, text: 'Synthetic' }); }
  return { request, pair, append, admitted, send, url, calls, listeners, journal, frame, prompted, host: () => host,
    beforeRead(fn: () => Promise<void>) { beforeJournalRead = fn; },
    async restart() { await host.close(); adapter.dispose(); adapter = await createDshAdapter(options); host = await startHostServer({ config, adapter }); baseUrl = (await host.start()).baseUrl; },
  };
}

async function json(response: Response) { assert.equal(response.status, 200); return response.json(); }

test('capabilities.promptOutcomes and HTTP 0/1/32/33 UUIDs, duplicates, case, non-UUID and order', async t => {
  const h = await setup(t), phone = h.pair(), ids = Array.from({ length: 33 }, () => randomUUID());
  const capabilities = await json(await h.request('/capabilities', phone.deviceToken));
  assert.equal(capabilities.hostVersion, '0.9.0'); assert.equal(capabilities.capabilities.promptOutcomes, true);
  for (const values of [[], ids, ['not-uuid'], ['１２３４５６７８-0000-4000-8000-000000000001'], ['\uFEFF' + ids[0]], [ids[0] + '\r'], [ids[0]!.slice(0, 4) + '\uFEFF' + ids[0]!.slice(4)]]) {
    const response = await h.request(h.url(values), phone.deviceToken); assert.equal(response.status, 400); assert.equal((await response.json()).error.code, 'invalid_request');
  }
  for (const values of [[ids[0]!], ids.slice(0, 32), [ids[2]!.toUpperCase(), ids[0]!, ids[2]!]]) {
    const result = await json(await h.request(h.url(values), phone.deviceToken));
    assert.deepEqual(result.outcomes.map((o: any) => o.requestId), [...new Set(values.map(id => id.toLowerCase()))]);
    assert.ok(result.outcomes.every((o: any) => o.status === 'unknown' && o.updatedAt === '1970-01-01T00:00:00.000Z'));
  }
});

test('outcomes use snapshot auth errors; unknown/foreign/wrong-session/non-prompt ids do not read journal', async t => {
  const h = await setup(t), phone = h.pair(), other = h.pair(), denied = h.pair([]), ids = Array.from({ length: 4 }, () => randomUUID());
  h.admitted(other.deviceId, ids[0]!); h.admitted(phone.deviceId, ids[1]!, 'other-session'); h.admitted(phone.deviceId, ids[2]!, undefined, 'cancel');
  assert.equal((await h.request(h.url(ids))).status, 401);
  assert.equal((await h.request(h.url(ids), denied.deviceToken)).status, 404);
  const missing = h.url(ids).replace('/' + summary.sessionId + '/', '/missing/');
  assert.equal((await h.request(missing, phone.deviceToken)).status, 404);
  const page = await json(await h.request(h.url(ids), phone.deviceToken));
  assert.ok(page.outcomes.every((o: any) => o.status === 'unknown')); assert.equal(h.calls.length, 0);
});

test('proven rejection is rejected, accepted/uncertain without journal are unknown, receipt scoped across restart', async t => {
  const h = await setup(t), phone = h.pair(), ids = Array.from({ length: 3 }, () => randomUUID());
  h.admitted(phone.deviceId, ids[0]!, undefined, 'prompt', 'rejected'); h.admitted(phone.deviceId, ids[1]!); h.admitted(phone.deviceId, ids[2]!, undefined, 'prompt', 'uncertain');
  const before = await json(await h.request(h.url(ids), phone.deviceToken));
  assert.deepEqual(before.outcomes.map((o: any) => o.status), ['rejected', 'unknown', 'unknown']);
  await h.restart();
  assert.deepEqual(await json(await h.request(h.url(ids), phone.deviceToken)), before);
});

test('HTTP admitted derived rpcId resolves journal beyond evicted transcript and host restart', async t => {
  const h = await setup(t), phone = h.pair(), requestId = randomUUID();
  assert.equal((await h.send(phone.deviceToken, requestId)).status, 200);
  const rpcId = h.prompted[0]!; assert.notEqual(rpcId, requestId);
  h.append('turn/start', { turn: 42 }); h.append('user/message', { id: 'phone', source: { kind: 'user', rpcId } }); h.append('turn/end', { turn: 42, reason: { kind: 'completed' } });
  const page = await json(await h.request(h.url([requestId.toUpperCase()]), phone.deviceToken));
  assert.deepEqual(page.outcomes, [{ requestId, status: 'completed', turnId: '42', updatedAt: '1970-01-01T00:00:01.002Z' }]);
  assert.ok(!JSON.stringify(h.frame).includes(rpcId), 'bounded transcript has no phone message');
  await h.restart(); const calls = h.calls.length;
  assert.deepEqual(await json(await h.request(h.url([requestId]), phone.deviceToken)), page);
  assert.equal(h.calls.length, calls, 'durable terminal checkpoint avoids rescanning');
});

test('long-poll timeout, no-since, mismatched-since, waitSeconds 0/25 and malformed bounds', async t => {
  const h = await setup(t), phone = h.pair(), ids = [randomUUID()];
  const page = await json(await h.request(h.url(ids), phone.deviceToken));
  for (const wait of ['-1', '26', '1.5', '', '１', '999999999999999999999', '1%0A']) assert.equal((await h.request(h.url(ids, '&waitSeconds=' + wait), phone.deviceToken)).status, 400);
  for (const extra of ['&waitSeconds=25', '&waitSeconds=25&since=other', '&waitSeconds=0&since=' + page.token]) assert.deepEqual(await json(await h.request(h.url(ids, extra), phone.deviceToken)), page);
  const start = Date.now();
  assert.deepEqual(await json(await h.request(h.url(ids, '&waitSeconds=1&since=' + page.token), phone.deviceToken)), page);
  assert.ok(Date.now() - start >= 900);
});

test('long-poll supersede and shutdown clean all listeners', async t => {
  const h = await setup(t), phone = h.pair(), requestId = randomUUID();
  h.admitted(phone.deviceId, requestId);
  const page = await json(await h.request(h.url([requestId]), phone.deviceToken));
  const first = h.request(h.url([requestId], '&waitSeconds=25&since=' + page.token), phone.deviceToken);
  await new Promise<void>(resolve => { const timer = setInterval(() => { if (h.listeners.size) { clearInterval(timer); resolve(); } }, 1); });
  const second = h.request(h.url([requestId], '&waitSeconds=1&since=' + page.token), phone.deviceToken);
  assert.deepEqual(await json(await first), page);
  assert.deepEqual(await json(await second), page);
  const held = h.request(h.url([requestId], '&waitSeconds=25&since=' + page.token), phone.deviceToken);
  await new Promise<void>(resolve => { const timer = setInterval(() => { if (h.listeners.size) { clearInterval(timer); resolve(); } }, 1); });
  await h.host().close(); const stopped = await held; assert.equal(stopped.status, 503); assert.equal((await stopped.json()).error.code, 'unavailable'); assert.equal(h.listeners.size, 0);
});

test('held HTTP poll wakes on outcome change, token stays stable on unrelated events and disconnect cleans up', async t => {
  const h = await setup(t), phone = h.pair(), id = randomUUID();
  assert.equal((await h.send(phone.deviceToken, id)).status, 200);
  h.append('turn/start', { turn: 1 }); h.append('user/message', { id: 'phone', source: { kind: 'user', rpcId: h.prompted[0] } });
  const initial = await json(await h.request(h.url([id]), phone.deviceToken));
  assert.equal(initial.outcomes[0].status, 'running');
  assert.equal((await json(await h.request(h.url([id]), phone.deviceToken))).outcomes[0].status, 'running');
  assert.ok(h.calls.some(call => call.offset === 2), 'incremental reads start after known message, never at transcript start');
  h.append('session/title', { title: 'unrelated' });
  assert.deepEqual(await json(await h.request(h.url([id]), phone.deviceToken)), initial);
  const poll = h.request(h.url([id], '&waitSeconds=25&since=' + initial.token), phone.deviceToken);
  await new Promise<void>(resolve => { const timer = setInterval(() => { if (h.listeners.size) { clearInterval(timer); resolve(); } }, 1); });
  h.append('approval/asked', { id: 'approval' });
  const waiting = await json(await poll); assert.equal(waiting.outcomes[0].status, 'waiting'); assert.notEqual(waiting.token, initial.token);
  const abort = new AbortController();
  const canceled = h.request(h.url([id], '&waitSeconds=25&since=' + waiting.token), phone.deviceToken, 'GET', undefined, abort.signal);
  await new Promise<void>(resolve => { const timer = setInterval(() => { if (h.listeners.size) { clearInterval(timer); resolve(); } }, 1); });
  abort.abort(); await assert.rejects(canceled);
  await new Promise<void>(resolve => { const timer = setInterval(() => { if (!h.listeners.size) { clearInterval(timer); resolve(); } }, 1); });
});

test('device same UUID isolation, failed HTTP summary and old receipts outside 1024-id transcript map', async t => {
  const h = await setup(t), phone = h.pair(), other = h.pair(), id = randomUUID();
  assert.equal((await h.send(phone.deviceToken, id)).status, 200);
  assert.equal((await h.send(other.deviceToken, id)).status, 200);
  assert.notEqual(h.prompted[0], h.prompted[1]);
  h.append('turn/start', { turn: 1 }); h.append('user/message', { id: 'phone', source: { kind: 'user', rpcId: h.prompted[0] } });
  h.append('turn/end', { turn: 1, reason: { kind: 'error', error: { message: 'Ошибка\n' + '😀'.repeat(1000) } } });
  for (let n = 0; n < 1025; n++) h.admitted(phone.deviceId, randomUUID());
  const own = await json(await h.request(h.url([id]), phone.deviceToken));
  assert.equal(own.outcomes[0].status, 'failed'); assert.ok(Buffer.byteLength(own.outcomes[0].summary) <= 2048);
  assert.equal((await json(await h.request(h.url([id]), other.deviceToken))).outcomes[0].status, 'unknown');
  await h.restart(); assert.deepEqual(await json(await h.request(h.url([id]), phone.deviceToken)), own);
});

test('held outcome polls do not consume the three per-device SSE slots; grant removal returns snapshot-equivalent 404', async t => {
  const h = await setup(t), phone = h.pair(), id = randomUUID();
  const streams: ReadableStreamDefaultReader<Uint8Array>[] = [];
  for (let n = 0; n < 3; n++) {
    const response = await h.request('/sessions/' + summary.sessionId + '/events', phone.deviceToken);
    assert.equal(response.status, 200); const reader = response.body!.getReader(); await reader.read(); streams.push(reader);
  }
  try {
    const page = await json(await h.request(h.url([id]), phone.deviceToken));
    const poll = h.request(h.url([id], '&waitSeconds=25&since=' + page.token), phone.deviceToken);
    await new Promise<void>(resolve => { const timer = setInterval(() => { if (h.listeners.size) { clearInterval(timer); resolve(); } }, 1); });
    h.host().state.replaceDeviceGrants(phone.deviceId, { readWorkspaceIds: [], executeWorkspaceIds: [] });
    const response = await poll; assert.equal(response.status, 404);
  } finally { for (const reader of streams) await reader.cancel(); }
});

test('grant revoked during journal I/O is rechecked before outcomes or checkpoints can leak', async t => {
  const h = await setup(t), phone = h.pair(), id = randomUUID();
  assert.equal((await h.send(phone.deviceToken, id)).status, 200);
  h.append('turn/start', { turn: 1 }); h.append('user/message', { id: 'phone', source: { kind: 'user', rpcId: h.prompted[0] } });
  h.beforeRead(async () => { h.host().state.replaceDeviceGrants(phone.deviceId, { readWorkspaceIds: [], executeWorkspaceIds: [] }); });
  const response = await h.request(h.url([id]), phone.deviceToken); assert.equal(response.status, 404);
  assert.equal(h.host().state.outcomeCheckpoint(phone.deviceId, id, h.prompted[0]!), undefined);
});
