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

async function setup(t: TestContext, sessionId = summary.sessionId) {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-outcomes-'))); mkdirSync(join(dir, 'проект с пробелами'));
  const workspace = { id: 'alpha', name: 'Synthetic', path: join(dir, 'проект с пробелами') };
  mkdirSync(join(dir, 'beta')); const otherWorkspace = { id: 'beta', name: 'Other synthetic', path: join(dir, 'beta') };
  const frame = structuredClone(opening); frame.header.cwd = workspace.path; frame.header.id = sessionId;
  const listeners = new Set<(e: DshObservation) => void>(), journal: unknown[] = [], calls: { offset: number; length: number }[] = [], prompted: string[] = [];
  let beforeJournalRead: (signal?: AbortSignal) => Promise<void> = async () => {};
  let beforeJournalOpen = async () => {}, beforeJournalClose = async () => {};
  let readerCloses = 0;
  const row = { ...structuredClone(summary), sessionId, cwd: workspace.path };
  const options = {
    dshVersion: '0.2.1-rc.5', workspaces: [workspace, otherWorkspace], sessionHeader: async () => frame.header,
    sessionController: {
      async list() { return { items: [row] }; },
      async *follow(_req: unknown, signal: AbortSignal) { try { yield structuredClone(frame); throw new Error('must not resume'); } finally { assert.ok(signal.aborted); } },
      async page() { return { records: [], hasMore: false }; }, async projections() { return frame.projections; },
      async create() { return { sessionId: 'synthetic' }; }, async prompt(input: { requestId: string }) { prompted.push(input.requestId); }, cancel() {},
    },
    promptJournal: { async open() { await beforeJournalOpen(); return { async read(offset: number, length: number, readOptions?: { signal?: AbortSignal }) { calls.push({ offset, length }); await beforeJournalRead(readOptions?.signal); return { events: journal.slice(offset, offset + length) }; }, async close() { readerCloses++; await beforeJournalClose(); } }; } },
    events: { subscribe(_id: string, listener: (e: DshObservation) => void) { listeners.add(listener); return () => { listeners.delete(listener); }; } },
  };
  let adapter = await createDshAdapter(options);
  const config = { hostName: 'Synthetic', bind: '127.0.0.1', port: 0, allowInsecureLoopback: true, statePath: join(dir, 'host.sqlite'), workspaces: [workspace, otherWorkspace] };
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
  const url = (ids: string[], extra = '') => '/sessions/' + encodeURIComponent(row.sessionId) + '/prompt-outcomes?' + ids.map(id => 'requestId=' + encodeURIComponent(id)).join('&') + extra;
  async function send(token: string, id: string) { return request('/sessions/' + row.sessionId + '/messages', token, 'POST', { requestId: id, text: 'Synthetic' }); }
  return { request, pair, append, admitted, send, url, calls, listeners, journal, frame, prompted, otherWorkspace, adapter: () => adapter, host: () => host,
    beforeRead(fn: (signal?: AbortSignal) => Promise<void>) { beforeJournalRead = fn; },
    beforeOpen(fn: () => Promise<void>) { beforeJournalOpen = fn; },
    beforeClose(fn: () => Promise<void>) { beforeJournalClose = fn; },
    readerCloses: () => readerCloses,
    async restart() { await host.close(); adapter.dispose(); adapter = await createDshAdapter(options); host = await startHostServer({ config, adapter }); baseUrl = (await host.start()).baseUrl; },
  };
}

async function json(response: Response) { assert.equal(response.status, 200); return response.json(); }

test('capabilities.promptOutcomes and HTTP 0/1/32/33 UUIDs, duplicates, case, non-UUID and order', async t => {
  const h = await setup(t), phone = h.pair(), ids = Array.from({ length: 33 }, () => randomUUID());
  const capabilities = await json(await h.request('/capabilities', phone.deviceToken));
  assert.equal(capabilities.hostVersion, '0.9.2'); assert.equal(capabilities.capabilities.promptOutcomes, true);
  for (const values of [[], ids, ['not-uuid'], ['１２３４５６７８-0000-4000-8000-000000000001'], ['\uFEFF' + ids[0]], [ids[0] + '\r'], [ids[0]!.slice(0, 4) + '\uFEFF' + ids[0]!.slice(4)]]) {
    const response = await h.request(h.url(values), phone.deviceToken); assert.equal(response.status, 400); assert.equal((await response.json()).error.code, 'invalid_request');
  }
  for (const values of [[ids[0]!], ids.slice(0, 32), [ids[2]!.toUpperCase(), ids[0]!, ids[2]!]]) {
    const result = await json(await h.request(h.url(values), phone.deviceToken));
    assert.deepEqual(result.outcomes.map((o: any) => o.requestId), [...new Set(values.map(id => id.toLowerCase()))]);
    assert.ok(result.outcomes.every((o: any) => o.status === 'unknown' && o.updatedAt === '1970-01-01T00:00:00.000Z'));
  }
});

test('R10 32 UUIDs fit any legal 256-byte encoded session segment without enlarging other routes', async t => {
  for (const sessionId of ['я'.repeat(128), '😀'.repeat(64), 'a'.repeat(256), 'e\u0301'.repeat(85)]) {
    const h = await setup(t, sessionId), phone = h.pair(), ids = Array.from({ length: 32 }, () => randomUUID());
    const path = h.url(ids, '&waitSeconds=25&since=' + 'x'.repeat(43));
    const result = await json(await h.request(path, phone.deviceToken));
    assert.equal(result.outcomes.length, 32);
    assert.deepEqual(result.outcomes.map((o: any) => o.requestId), ids);
    // Percent-encoded UUID bytes are legal too; retain literal route/query names.
    const encodedQuery = '/sessions/' + encodeURIComponent(sessionId) + '/prompt-outcomes?' + ids.map(id => 'requestId=' + [...id].map(c => '%' + c.charCodeAt(0).toString(16)).join('')).join('&');
    assert.equal((await h.request(encodedQuery, phone.deviceToken)).status, 200);
    assert.equal((await h.request(path + '&since=' + 'x'.repeat(9000), phone.deviceToken)).status, 400);
    assert.equal((await h.request('/sessions?workspaceId=' + 'x'.repeat(2048), phone.deviceToken)).status, 400);
    assert.equal((await h.request(h.url(ids).replace(encodeURIComponent(sessionId), encodeURIComponent(sessionId + 'aa')), phone.deviceToken)).status, 400);
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

for (const stage of ['opening', 'held'] as const) for (const phase of ['beforeOpen', 'beforeRead', 'beforeClose'] as const) {
  test(`R06 HTTP shutdown during ${stage} ${phase} finishes host and removes subscription before IO settles`, async t => {
    const h = await setup(t), phone = h.pair(), id = randomUUID();
    h.admitted(phone.deviceId, id);
    const initial = await json(await h.request(h.url([id]), phone.deviceToken));
    const gate = Promise.withResolvers<void>(), entered = Promise.withResolvers<void>();
    let calls = 0;
    h[phase](async () => { if (++calls === (stage === 'held' ? 2 : 1)) { entered.resolve(); await gate.promise; } });
    const pending = h.request(h.url([id], '&waitSeconds=25&since=' + initial.token), phone.deviceToken);
    await entered.promise;
    try {
      await Promise.race([h.host().close(), new Promise<never>((_, reject) => { const timer = setTimeout(() => reject(new Error('host.close retained journal IO')), 500); timer.unref(); })]);
      const response = await pending; assert.equal(response.status, 503);
      assert.equal(h.listeners.size, 0);
    } finally { gate.resolve(); await pending; }
  });
}

test('R06 timeout detaches held journal read and late settlement closes its exact reader', async t => {
  const h = await setup(t), phone = h.pair(), id = randomUUID(); h.admitted(phone.deviceId, id);
  const initial = await json(await h.request(h.url([id]), phone.deviceToken));
  const gate = Promise.withResolvers<void>(), entered = Promise.withResolvers<void>();
  let reads = 0;
  h.beforeRead(async () => { if (++reads > 1) { entered.resolve(); await gate.promise; } });
  const pending = h.request(h.url([id], '&waitSeconds=2&since=' + initial.token), phone.deviceToken);
  await entered.promise;
  try {
    assert.deepEqual(await json(await pending), initial); assert.equal(h.listeners.size, 0);
    const closes = h.readerCloses();
    gate.resolve(); await new Promise<void>(resolve => setImmediate(resolve));
    assert.equal(h.readerCloses(), closes + 1, 'late reader closes once; aborted read never publishes');
  } finally { gate.resolve(); }
});

test('R06 timeout of a blocked reread reauthorizes the last page before responding', async t => {
  const h = await setup(t), phone = h.pair(), id = randomUUID(); h.admitted(phone.deviceId, id);
  const initial = await json(await h.request(h.url([id]), phone.deviceToken));
  const gate = Promise.withResolvers<void>(); let reads = 0;
  h.beforeRead(async () => { if (++reads < 2) return; h.host().state.replaceDeviceGrants(phone.deviceId, { readWorkspaceIds: [], executeWorkspaceIds: [] }); await gate.promise; });
  try {
    const response = await h.request(h.url([id], '&waitSeconds=2&since=' + initial.token), phone.deviceToken);
    assert.equal(response.status, 404); assert.equal(h.listeners.size, 0);
  } finally { gate.resolve(); }
});

test('R06 deadline forwards abort to cooperative journal read and releases its reader before late publication', async t => {
  const h = await setup(t), phone = h.pair(), id = randomUUID(); h.admitted(phone.deviceId, id);
  const initial = await json(await h.request(h.url([id]), phone.deviceToken)); let reads = 0, aborts = 0;
  h.beforeRead(async active => {
    if (++reads < 2) return;
    assert.ok(active);
    await new Promise<void>((_, reject) => { const abort = () => { aborts++; reject(active.reason); }; active.addEventListener('abort', abort, { once: true }); if (active.aborted) abort(); });
  });
  assert.deepEqual(await json(await h.request(h.url([id], '&waitSeconds=2&since=' + initial.token), phone.deviceToken)), initial);
  await new Promise<void>(resolve => setImmediate(resolve));
  assert.equal(aborts, 1); assert.equal(h.readerCloses(), 3); assert.equal(h.listeners.size, 0);
});

test('R06 aborted IO keeps all four actual adapter slots until read and close settle; capacity waiter aborts', async t => {
  const h = await setup(t), gate = Promise.withResolvers<void>(), entered = Promise.withResolvers<void>();
  const controllers = Array.from({ length: 4 }, () => new AbortController()); let reads = 0;
  h.beforeRead(async () => { if (++reads === 4) entered.resolve(); await gate.promise; });
  const inFlight = controllers.map(controller => h.adapter().promptOutcomes(summary.sessionId, [{ requestId: randomUUID() }], controller.signal, 'alpha'));
  const observed = inFlight.map(p => p.then(() => undefined, error => error));
  await entered.promise; controllers.forEach(controller => controller.abort());
  const fifth = new AbortController(), waiting = h.adapter().promptOutcomes(summary.sessionId, [{ requestId: randomUUID() }], fifth.signal, 'alpha');
  const rejected = assert.rejects(waiting);
  await new Promise<void>(resolve => setImmediate(resolve));
  assert.equal(reads, 4); assert.equal(h.readerCloses(), 0);
  fifth.abort(); await rejected;
  gate.resolve(); await Promise.all(observed); assert.equal(h.readerCloses(), 4);
  h.beforeRead(async () => {});
  assert.equal((await h.adapter().promptOutcomes(summary.sessionId, [{ requestId: randomUUID() }], new AbortController().signal, 'alpha'))[0]!.outcome.status, 'unknown');
});

test('R07 legacy false terminal HTTP checkpoint is replaced by authoritative rescan across restart', async t => {
  const h = await setup(t), phone = h.pair(), id = randomUUID();
  assert.equal((await h.send(phone.deviceToken, id)).status, 200); const rpcId = h.prompted[0]!;
  h.host().state.saveOutcomeCheckpoint(phone.deviceId, id, rpcId, { nextSeq: 8000, caughtUp: true, turn: 1, approvals: [], questions: [], outcome: { requestId: rpcId, status: 'completed', turnId: '1', updatedAt: '1970-01-01T00:00:01.000Z' } });
  h.append('agent/inbox/spliced', { target: 'next-turn', start: 0, inserted: [{ source: { kind: 'user', rpcId } }] });
  const result = await json(await h.request(h.url([id]), phone.deviceToken));
  assert.equal(result.outcomes[0].status, 'queued');
  const saved = h.host().state.outcomeCheckpoint(phone.deviceId, id, rpcId)!;
  assert.equal(saved.nextSeq, 1); assert.equal(saved.consumptionVersion, 3);
  // Late legacy writers and same-version lower cursors cannot restore false evidence.
  h.host().state.saveOutcomeCheckpoint(phone.deviceId, id, rpcId, { ...saved, consumptionVersion: undefined, nextSeq: 9000, outcome: { ...saved.outcome, status: 'completed' } });
  h.host().state.saveOutcomeCheckpoint(phone.deviceId, id, rpcId, { ...saved, nextSeq: 0, outcome: { ...saved.outcome, status: 'unknown' } });
  await h.restart(); assert.deepEqual(await json(await h.request(h.url([id]), phone.deviceToken)), result);
});

test('discarded queued prompt repairs persisted v1 unknown after restart without resending', async t => {
  const h = await setup(t), phone = h.pair(), id = randomUUID();
  assert.equal((await h.send(phone.deviceToken, id)).status, 200); const rpcId = h.prompted[0]!;
  h.append('agent/inbox/spliced', { target: 'next-turn', start: 0, inserted: [{ source: { kind: 'user', rpcId } }] });
  h.append('agent/inbox/spliced', { target: 'next-turn', start: 0, removedCount: 1, inserted: [], outcome: 'canceled' });
  h.host().state.saveOutcomeCheckpoint(phone.deviceId, id, rpcId, { consumptionVersion: 1, nextSeq: 8000, caughtUp: true,
    approvals: [], questions: [], outcome: { requestId: rpcId, status: 'unknown', updatedAt: '1970-01-01T00:00:01.001Z' } });
  await h.restart();
  const result = await json(await h.request(h.url([id]), phone.deviceToken));
  assert.deepEqual(result.outcomes, [{ requestId: id, status: 'rejected', updatedAt: '1970-01-01T00:00:01.001Z' }]);
  const saved = h.host().state.outcomeCheckpoint(phone.deviceId, id, rpcId)!;
  assert.equal(saved.consumptionVersion, 3); assert.equal(saved.nextSeq, 2);
  // Neither old proof version can resurrect the already-cleared watch.
  for (const consumptionVersion of [1, 2] as const) h.host().state.saveOutcomeCheckpoint(phone.deviceId, id, rpcId, { ...saved, consumptionVersion, nextSeq: 9000, outcome: { ...saved.outcome, status: 'unknown' } });
  assert.deepEqual(await json(await h.request(h.url([id]), phone.deviceToken)), result);
  assert.equal(h.prompted.length, 1);
});

test('known stopped turn repairs a durable v2 unknown through HTTP after restart, without resending', async t => {
  const h = await setup(t), phone = h.pair(), id = randomUUID();
  assert.equal((await h.send(phone.deviceToken, id)).status, 200); const rpcId = h.prompted[0]!;
  h.append('turn/start', { turn: 1 });
  h.append('user/message', { source: { kind: 'user', rpcId } });
  h.append('turn/end', { turn: 1, reason: { kind: 'aborted', reason: { kind: 'user' } } });
  h.host().state.saveOutcomeCheckpoint(phone.deviceId, id, rpcId, { consumptionVersion: 2, nextSeq: 3, caughtUp: true,
    turn: 1, approvals: [], questions: [], outcome: { requestId: rpcId, status: 'unknown', turnId: '1', updatedAt: '1970-01-01T00:00:01.002Z' } });
  await h.restart();
  const result = await json(await h.request(h.url([id]), phone.deviceToken));
  assert.deepEqual(result.outcomes, [{ requestId: id, status: 'failed', turnId: '1', summary: 'Задача остановлена до получения результата.', updatedAt: '1970-01-01T00:00:01.002Z' }]);
  const saved = h.host().state.outcomeCheckpoint(phone.deviceId, id, rpcId)!;
  assert.equal(saved.consumptionVersion, 3);
  h.host().state.saveOutcomeCheckpoint(phone.deviceId, id, rpcId, { ...saved, consumptionVersion: 2, nextSeq: 9000, outcome: { ...saved.outcome, status: 'unknown' } });
  assert.deepEqual(await json(await h.request(h.url([id]), phone.deviceToken)), result);
  assert.equal(h.prompted.length, 1);
});

test('R09 session remap while reader.close yields cannot publish outcome or checkpoint', async t => {
  const h = await setup(t), phone = h.pair(), id = randomUUID();
  assert.equal((await h.send(phone.deviceToken, id)).status, 200);
  h.append('turn/start', { turn: 1 }); h.append('user/message', { source: { kind: 'user', rpcId: h.prompted[0] } });
  h.append('turn/end', { turn: 1, reason: { kind: 'error', error: { message: 'Synthetic alpha-only failure' } } });
  h.beforeClose(async () => { await Promise.resolve(); h.frame.header.cwd = h.otherWorkspace.path; });
  const response = await h.request(h.url([id]), phone.deviceToken);
  assert.equal(response.status, 404); assert.equal((await response.json()).error.code, 'not_found');
  assert.equal(h.readerCloses(), 1);
  assert.equal(h.host().state.outcomeCheckpoint(phone.deviceId, id, h.prompted[0]!), undefined);
});

for (const phase of ['beforeOpen', 'beforeRead', 'beforeClose'] as const) {
  test(`R08 grant revoked during failing journal ${phase} uses snapshot not_found policy`, async t => {
    const h = await setup(t), phone = h.pair(), id = randomUUID();
    assert.equal((await h.send(phone.deviceToken, id)).status, 200);
    h[phase](async () => { h.host().state.replaceDeviceGrants(phone.deviceId, { readWorkspaceIds: [], executeWorkspaceIds: [] }); throw new Error('synthetic journal failure'); });
    const response = await h.request(h.url([id]), phone.deviceToken);
    assert.equal(response.status, 404); assert.equal((await response.json()).error.code, 'not_found');
    assert.equal(h.host().state.outcomeCheckpoint(phone.deviceId, id, h.prompted[0]!), undefined);
  });
}

test('grant revoked during journal I/O is rechecked before outcomes or checkpoints can leak', async t => {
  const h = await setup(t), phone = h.pair(), id = randomUUID();
  assert.equal((await h.send(phone.deviceToken, id)).status, 200);
  h.append('turn/start', { turn: 1 }); h.append('user/message', { id: 'phone', source: { kind: 'user', rpcId: h.prompted[0] } });
  h.beforeRead(async () => { h.host().state.replaceDeviceGrants(phone.deviceId, { readWorkspaceIds: [], executeWorkspaceIds: [] }); });
  const response = await h.request(h.url([id]), phone.deviceToken); assert.equal(response.status, 404);
  assert.equal(h.host().state.outcomeCheckpoint(phone.deviceId, id, h.prompted[0]!), undefined);
});
