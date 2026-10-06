import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { createDshAdapter } from '../src/dsh-adapter.ts';
import { HostState } from '../src/state.ts';
import { NotificationFeed } from '../src/notifications.ts';
import { historyCases, historyPage, historyRecords } from './fixtures/history-surface.ts';
import type { HistoryRecord } from './fixtures/history-surface.ts';

async function recovered(t: { after(fn: () => Promise<void>): void }, records: HistoryRecord[], firstSeq: number, running = false, pollIntervalMs = 1000) {
  const dir = realpathSync.native(await mkdtemp(join(tmpdir(), 'dsh-mobile-surface-')));
  const cursor = () => records.at(-1)!.event.seq;
  let resumed = 0, closed = 0, observing = false;
  let notify: (id: string, event: import('../src/notifications.ts').NotificationSource) => void = () => {};
  let disposed: (id: string) => void = () => {};
  const reads: { throughSeq: number; beforeSeq: number; maxMessages: number }[] = [];
  const values = { userQuestions: { active: [], settled: [] } };
  const controller = {
    async list() { return { items: [{ sessionId: 'synthetic-session', cwd: dir, updatedAt: 1700000000000, running, agentAvailable: running }] }; },
    async *follow(_request: unknown, signal: AbortSignal) {
      observing = true;
      try { yield { type: 'snapshot', cursor: cursor(), hasMore: firstSeq > 0, records: records.slice(firstSeq), header: { id: 'synthetic-session', cwd: dir }, projections: { asOfSeq: cursor(), values }, assistantStream: { revision: 0 } }; resumed++; throw new Error('Reads must never promote a cold session'); }
      finally { assert.equal(signal.aborted, true); closed++; observing = false; }
    },
    async page(request: { throughSeq: number; beforeSeq: number; maxMessages: number }, signal: AbortSignal) {
      signal.throwIfAborted(); reads.push(request);
      assert.equal(observing, false, 'close the opening observation before cold pagination to avoid retaining live buffered events');
      assert.equal(request.throughSeq, cursor(), 'backward reads must stay on the immutable opening cut');
      return historyPage(records, request.throughSeq, request.beforeSeq, request.maxMessages);
    },
    async projections() { return { asOfSeq: cursor(), values }; },
    async create() { throw new Error('No mutations'); }, async prompt() { throw new Error('No mutations'); }, cancel() { throw new Error('No mutations'); },
  };
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: controller, pollIntervalMs, throttleMs: 1, workspaces: [{ id: 'synthetic-workspace', path: dir, name: 'Synthetic' }], events: { subscribeDisposed(listener) { disposed = listener; return () => { disposed = () => {}; }; }, subscribe() { return () => {}; }, subscribeNotifications(listener) { notify = listener; return () => { notify = () => {}; }; } } });
  t.after(async () => { adapter.dispose(); await rm(dir, { recursive: true, force: true }); });
  return { adapter, controller, reads, dir, disposeSession: () => disposed('synthetic-session'), notify: (event: import('../src/notifications.ts').NotificationSource) => notify('synthetic-session', event), cleanup: () => ({ resumed, closed }) };
}

test('unchanged watch refresh reuses recovered ancestry without any extra backward reads', { timeout: 5000 }, async t => {
  const fixture = historyCases[1]!, records = historyRecords(fixture);
  const h = await recovered(t, records, fixture.firstSeq, true, 5);
  const active = new AbortController(), watch = h.adapter.watch('synthetic-session', active.signal)[Symbol.asyncIterator]();
  const initial = await watch.next();
  assert.equal(initial.value!.messages.length, 100);
  assert.equal(h.reads.length, 8);
  const next = watch.next();
  let refreshed!: () => void;
  const polling = new Promise<void>(resolve => { refreshed = resolve; });
  const follow = h.controller.follow;
  h.controller.follow = async function* (request, signal) { try { yield* follow(request, signal); } finally { if (h.cleanup().closed >= 4) { active.abort(); refreshed(); } } };
  await polling; await next; await watch.return?.();
  assert.equal(h.reads.length, 8, 'two unchanged polls make zero backward reads');
});

test('concurrent opens of one session share a single backward recovery', async t => {
  const fixture = historyCases[1]!;
  const h = await recovered(t, historyRecords(fixture), fixture.firstSeq, true);
  let release!: () => void, entered!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; }), reading = new Promise<void>(resolve => { entered = resolve; });
  const page = h.controller.page;
  h.controller.page = async (request, signal) => { const result = await page(request, signal); if (request.beforeSeq === fixture.firstSeq) { entered(); await held; } return result; };
  let opened!: () => void;
  const allOpened = new Promise<void>(resolve => { opened = resolve; }), follow = h.controller.follow;
  h.controller.follow = async function* (request, signal) { try { yield* follow(request, signal); } finally { if (h.cleanup().closed === 3) opened(); } };
  const opens = Array.from({ length: 3 }, () => h.adapter.snapshot('synthetic-session', new AbortController().signal));
  await reading; await allOpened; release();
  const snapshots = await Promise.all(opens);
  assert.ok(snapshots.every(s => s.messages.length === 100 && s.activity === 'running'));
  assert.equal(h.reads.length, 8, 'three concurrent opens require one eight-page reconstruction, not three');
});

test('new contiguous control events reuse immutable ancestry without new backward reads', async t => {
  const fixture = historyCases[1]!, records = historyRecords(fixture), h = await recovered(t, records, fixture.firstSeq, true);
  await h.adapter.snapshot('synthetic-session', new AbortController().signal);
  records.push({ type: 'event', event: { seq: fixture.cursor + 1, time: 1700000003893, type: 'tool/call', data: { turn: 900, callId: 'new-call', name: 'read' } } });
  const updated = await h.adapter.snapshot('synthetic-session', new AbortController().signal);
  assert.equal(updated.cursor, fixture.cursor + 1); assert.equal(updated.messages.length, 100);
  assert.equal(h.reads.length, 8);
});

test('three queued tool events recover one cut once and later events share the snapshot cache', async t => {
  const fixture = historyCases[1]!, records = historyRecords(fixture), h = await recovered(t, records, fixture.firstSeq);
  const state = new HostState(join(h.dir, 'notifications.sqlite'));
  const device = state.consumePairing(state.createPairing({ readWorkspaceIds: ['synthetic-workspace'], executeWorkspaceIds: [] }).pairingToken, 'Synthetic');
  const source = h.adapter.notifications()!; let proofs = 0;
  const evidence = source.evidence; source.evidence = (...args) => { proofs++; return evidence(...args); };
  const feed = new NotificationFeed(state, source);
  try {
    await feed.start(); feed.putSettings(device.deviceId, { expectedRevision: 0, enabled: true, projects: [], chats: [] }); await feed.idle();
    for (let n = 1; n <= 3; n++) records.push({ type: 'event', event: { seq: fixture.cursor + n, time: Date.now(), type: 'tool/call', data: { callId: `call-${n}`, name: 'read' } } });
    for (const { event } of records.slice(-3)) h.notify({ seq: event.seq, time: event.time, type: event.type });
    await feed.idle();
    assert.equal(proofs, 1); assert.equal(h.reads.length, 8, 'three queued tool calls cause eight, not twenty-four, backward reads');
    await h.adapter.snapshot('synthetic-session', new AbortController().signal);
    assert.equal(h.reads.length, 8, 'GET shares notification recovery');
  } finally { await feed.close(); state.close(); }
});

test('session lifecycle end evicts recovered ancestry before a later same-ID opening', async t => {
  const fixture = historyCases[1]!, h = await recovered(t, historyRecords(fixture), fixture.firstSeq, true);
  await h.adapter.snapshot('synthetic-session', new AbortController().signal); assert.equal(h.reads.length, 8);
  const source = h.adapter.notifications()!, proof = await source.evidence('synthetic-session', new AbortController().signal);
  assert.equal(source.evidenceCurrent!(proof), true);
  h.disposeSession(); assert.equal(source.evidenceCurrent!(proof), false, 'ended lifecycle also invalidates coalesced evidence');
  await h.adapter.snapshot('synthetic-session', new AbortController().signal); assert.equal(h.reads.length, 16, 'disposed lifecycle cannot reuse retained history');
});

test('cached ancestry is evicted on a changed scope and rejected when overlap bytes change', async t => {
  const fixture = historyCases[2]!, records = historyRecords(fixture), h = await recovered(t, records, fixture.firstSeq);
  const scope = { id: 'synthetic-workspace', path: h.dir, name: 'Synthetic' };
  let revision = 0;
  const revisions = new WeakMap<readonly import('../src/types.ts').WorkspaceConfig[], number>();
  const source = { kind: 'dsh-registry' as const, async list() { const view = [{ ...scope }]; revisions.set(view, revision); return view; }, isCurrent(view: readonly import('../src/types.ts').WorkspaceConfig[]) { return revisions.get(view) === revision; }, archivedSessionIds() { return new Set<string>(); } };
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: h.controller, workspaceSource: source });
  try {
    await adapter.snapshot('synthetic-session', new AbortController().signal); assert.equal(h.reads.length, 3);
    revision++; // Same visible IDs/paths, different provider/revision must still evict.
    await adapter.snapshot('synthetic-session', new AbortController().signal); assert.equal(h.reads.length, 6);
    const event = records[fixture.cursor]!.event; event.time++;
    await adapter.snapshot('synthetic-session', new AbortController().signal); assert.equal(h.reads.length, 9, 'same cursor does not authorize mismatched immutable bytes');
  } finally { adapter.dispose(); }
});

test('cancelling one recovery waiter does not cancel another reader of the same cut', async t => {
  const fixture = historyCases[2]!, h = await recovered(t, historyRecords(fixture), fixture.firstSeq);
  let enter!: () => void, release!: () => void, opened!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; }), reading = new Promise<void>(resolve => { enter = resolve; }), openings = new Promise<void>(resolve => { opened = resolve; });
  const page = h.controller.page, follow = h.controller.follow;
  h.controller.page = async (request, signal) => { const value = await page(request, signal); if (request.beforeSeq === fixture.firstSeq) { enter(); await held; signal.throwIfAborted(); } return value; };
  h.controller.follow = async function* (request, signal) { try { yield* follow(request, signal); } finally { if (h.cleanup().closed === 2) opened(); } };
  const first = new AbortController(), other = new AbortController();
  const abandoned = h.adapter.snapshot('synthetic-session', first.signal), wanted = h.adapter.snapshot('synthetic-session', other.signal);
  await reading; await openings; first.abort();
  await assert.rejects(abandoned, { name: 'AbortError' }); release();
  assert.equal((await wanted).messages.length, 64); assert.equal(h.reads.length, 3);
});

for (const budget of ['entries', 'bytes'] as const) test(`recovery cache evicts the oldest session at the hard ${budget} budget`, async t => {
  const fixture = historyCases[2]!;
  const records: HistoryRecord[] = budget === 'entries' ? historyRecords(fixture) : [
    { type: 'event', event: { seq: 0, time: 1700000000000, type: 'system/message', surfaceOp: 'append', data: { content: 'x'.repeat(11 * 1024 * 1024) } } },
    { type: 'event', event: { seq: 1, time: 1700000000001, type: 'user/message', surfaceOp: { op: 'replace', startSeq: 0, endSeq: 0 }, data: { id: 'bounded-message', content: [{ type: 'text', text: 'Synthetic' }] } } },
  ];
  const h = await recovered(t, records, budget === 'entries' ? fixture.firstSeq : 1);
  const count = budget === 'entries' ? 17 : 3, perSession = budget === 'entries' ? 3 : 1;
  const rows = Array.from({ length: count }, (_, n) => ({ sessionId: `cache-${n}`, cwd: h.dir, updatedAt: 1700000000000, running: false, agentAvailable: false }));
  const controller = { ...h.controller, async list() { return { items: rows }; }, async *follow(request: { address: { sessionId: string } }, signal: AbortSignal) {
    for await (const opening of h.controller.follow(request, signal)) yield { ...opening, header: { ...opening.header, id: request.address.sessionId } };
  } };
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: controller, workspaces: [{ id: 'synthetic-workspace', path: h.dir, name: 'Synthetic' }] });
  try {
    for (const row of rows) await adapter.snapshot(row.sessionId, new AbortController().signal);
    assert.equal(h.reads.length, count * perSession);
    await adapter.snapshot(rows.at(-1)!.sessionId, new AbortController().signal); assert.equal(h.reads.length, count * perSession, 'newest entry is retained');
    await adapter.snapshot(rows[0]!.sessionId, new AbortController().signal); assert.equal(h.reads.length, (count + 1) * perSession, 'oldest entry was evicted');
  } finally { adapter.dispose(); }
});

test('removing a listed session evicts ancestry and cancelling the last waiter releases recovery', async t => {
  const fixture = historyCases[2]!, h = await recovered(t, historyRecords(fixture), fixture.firstSeq);
  await h.adapter.snapshot('synthetic-session', new AbortController().signal);
  const list = h.controller.list; h.controller.list = async () => ({ items: [] });
  assert.deepEqual(await h.adapter.listSessions(new AbortController().signal), []); h.controller.list = list;
  await h.adapter.snapshot('synthetic-session', new AbortController().signal); assert.equal(h.reads.length, 6, 'removed membership invalidates reuse');
  h.disposeSession();
  let entered!: () => void, cancelled!: () => void;
  const reading = new Promise<void>(resolve => { entered = resolve; }), stopped = new Promise<void>(resolve => { cancelled = resolve; });
  const page = h.controller.page;
  h.controller.page = async (_request, signal) => { entered(); return await new Promise<never>((_resolve, reject) => { signal.addEventListener('abort', () => { cancelled(); reject(signal.reason); }, { once: true }); }); };
  const active = new AbortController(), opening = h.adapter.snapshot('synthetic-session', active.signal);
  await reading; active.abort(); await assert.rejects(opening, { name: 'AbortError' }); await stopped;
  h.controller.page = page;
  await h.adapter.snapshot('synthetic-session', new AbortController().signal); assert.equal(h.reads.length, 9, 'cancelled cut was not cached');
});

test('oversized stream/title metadata cannot bypass opening retention admission', async t => {
  const records: HistoryRecord[] = [{ type: 'event', event: { seq: 0, time: 1700000000000, type: 'turn/end', data: { turn: 1, reason: { kind: 'completed' } } } }];
  const h = await recovered(t, records, 0), follow = h.controller.follow;
  h.controller.follow = async function* (request, signal) { for await (const opening of follow(request, signal)) yield { ...opening, projections: { ...opening.projections, values: { ...opening.projections.values, title: 'x'.repeat(9 * 1024 * 1024) } }, assistantStream: { revision: 1, activeAttempt: { attemptId: 'synthetic-attempt', nextIndex: 1, stream: [{ type: 'text-chunks', index: 0, texts: ['y'.repeat(9 * 1024 * 1024)] }] } } }; };
  const snapshot = await h.adapter.snapshot('synthetic-session', new AbortController().signal);
  assert.deepEqual(snapshot.messages, []); assert.equal(snapshot.activity, 'idle'); assert.equal(snapshot.session.title.length, 256); assert.match(snapshot.notice!, /bounded history/); assert.equal(h.reads.length, 0);
});

test('concurrent recovery is capped at sixteen sessions before additional backward I/O', async t => {
  const records: HistoryRecord[] = [
    { type: 'event', event: { seq: 0, time: 1700000000000, type: 'system/message', surfaceOp: 'append', data: {} } },
    { type: 'event', event: { seq: 1, time: 1700000000001, type: 'user/message', surfaceOp: { op: 'replace', startSeq: 0, endSeq: 0 }, data: { id: 'bounded-message', content: [{ type: 'text', text: 'Synthetic' }] } } },
  ];
  const h = await recovered(t, records, 1);
  const rows = Array.from({ length: 17 }, (_, n) => ({ sessionId: `flight-${n}`, cwd: h.dir, updatedAt: 1700000000000, running: false, agentAvailable: false }));
  let release!: () => void, entered!: () => void, reads = 0;
  const held = new Promise<void>(resolve => { release = resolve; }), allReading = new Promise<void>(resolve => { entered = resolve; });
  const controller = { ...h.controller, async list() { return { items: rows }; }, async *follow(request: { address: { sessionId: string } }, signal: AbortSignal) { for await (const frame of h.controller.follow(request, signal)) yield { ...frame, header: { ...frame.header, id: request.address.sessionId } }; }, async page(_request: unknown, signal: AbortSignal) { if (++reads === 16) entered(); await held; signal.throwIfAborted(); return { records: [records[0]], hasMore: false }; } };
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: controller, workspaces: [{ id: 'synthetic-workspace', path: h.dir, name: 'Synthetic' }] });
  try {
    const openings = rows.slice(0, 16).map(row => adapter.snapshot(row.sessionId, new AbortController().signal));
    await allReading;
    await assert.rejects(adapter.snapshot(rows[16]!.sessionId, new AbortController().signal), { code: 'unavailable' }); assert.equal(reads, 16);
    release(); assert.equal((await Promise.all(openings)).length, 16);
  } finally { release(); adapter.dispose(); }
});

for (const fixture of historyCases) test(`bounded ${fixture.name} reconstructs the canonical recent messages without activating DSH`, async t => {
  const h = await recovered(t, historyRecords(fixture), fixture.firstSeq, fixture.name === 'replacement-2');
  const snapshot = await h.adapter.snapshot('synthetic-session', new AbortController().signal);
  assert.deepEqual(snapshot.messages.map(m => Number(m.id.slice('message-'.length))), fixture.expectedMessageSeqs);
  assert.equal(snapshot.activity, fixture.name === 'replacement-2' ? 'running' : 'idle');
  assert.equal(snapshot.notice, undefined);
  assert.deepEqual(h.cleanup(), { resumed: 0, closed: 1 });
  const proof = await h.adapter.notifications()!.evidence('synthetic-session', new AbortController().signal);
  assert.equal(proof.valid, true, 'the shared cold recovery must also restore notification coverage');
  assert.ok(proof.completed.length > 0, 'completed-turn evidence uses only surviving answers');
  assert.equal(h.cleanup().resumed, 0);
});

test('recovered replacement history preserves an authoritative open approval as waiting', async t => {
  const fixture = historyCases[2]!;
  const records = historyRecords(fixture);
  records.push({ type: 'event', event: { seq: fixture.cursor + 1, time: 1700000000000 + fixture.cursor + 1, type: 'turn/start', data: { turn: 900 } } },
    { type: 'event', event: { seq: fixture.cursor + 2, time: 1700000000000 + fixture.cursor + 2, type: 'approval/asked', data: { id: 'synthetic-approval', toolName: 'read' } } });
  const h = await recovered(t, records, fixture.firstSeq, true);
  const snapshot = await h.adapter.snapshot('synthetic-session', new AbortController().signal);
  assert.deepEqual(snapshot.messages.map(m => Number(m.id.slice('message-'.length))), fixture.expectedMessageSeqs);
  assert.equal(snapshot.activity, 'waiting');
  assert.equal((await h.adapter.notifications()!.evidence('synthetic-session', new AbortController().signal)).pending, true);
});

test('a complete malformed surface is unavailable rather than falsely classified as a bounded cut', async t => {
  const records = historyRecords(historyCases[2]!);
  records[records.length - 1] = { type: 'event', event: { seq: records.length - 1, time: 1700000000000 + records.length - 1, type: 'user/message', surfaceOp: { op: 'replace', startSeq: -1, endSeq: -1 }, data: { id: 'synthetic-corrupt', content: [{ type: 'text', text: 'Not authoritative' }] } } };
  const h = await recovered(t, records, 0);
  await assert.rejects(h.adapter.snapshot('synthetic-session', new AbortController().signal), { code: 'unavailable' });
  assert.deepEqual(h.cleanup(), { resumed: 0, closed: 1 });
});

function overBudgetRecords(): HistoryRecord[] {
  const records: HistoryRecord[] = Array.from({ length: 1802 }, (_, seq) => ({ type: 'event', event: { seq, time: 1700000000000 + seq, type: 'user/message', surfaceOp: 'append', data: { id: `message-${seq}`, content: [{ type: 'text', text: 'Synthetic' }] } } }));
  records[0]!.event.type = 'system/message';
  records[1702]!.event.surfaceOp = { op: 'replace', startSeq: 0, endSeq: 0 };
  return records;
}

test('an old history cut without a newer control boundary does not invent an idle activity', async t => {
  const h = await recovered(t, overBudgetRecords(), 1702);
  const snapshot = await h.adapter.snapshot('synthetic-session', new AbortController().signal);
  assert.equal(snapshot.activity, 'unknown');
  assert.match(snapshot.notice!, /bounded history crosses/);
});

test('page budget exhaustion still preserves a newest running turn and its activity detail', async t => {
  const records = overBudgetRecords();
  records[1799] = { type: 'event', event: { seq: 1799, time: 1700000001799, type: 'turn/start', data: { turn: 900 } } };
  const h = await recovered(t, records, 1702, true);
  const snapshot = await h.adapter.snapshot('synthetic-session', new AbortController().signal);
  assert.equal(snapshot.activity, 'running');
  assert.deepEqual(snapshot.activityDetail, { turnStartedAt: 1700000001799 });
  assert.match(snapshot.notice!, /bounded history crosses/);
});

test('an oversized opening page is rejected before retaining message text but keeps proven newest activity', async t => {
  const records: HistoryRecord[] = [
    { type: 'event', event: { seq: 0, time: 1700000000000, type: 'user/message', surfaceOp: 'append', data: { id: 'large-message', content: [{ type: 'text', text: 'x'.repeat(17 * 1024 * 1024) }] } } },
    { type: 'event', event: { seq: 1, time: 1700000000001, type: 'turn/end', data: { turn: 900, reason: { kind: 'completed' } } } },
  ];
  const h = await recovered(t, records, 0);
  const snapshot = await h.adapter.snapshot('synthetic-session', new AbortController().signal);
  assert.deepEqual(snapshot.messages, [], 'never fold or publish the oversized message');
  assert.equal(snapshot.activity, 'idle'); assert.equal(snapshot.hasMore, true); assert.match(snapshot.notice!, /bounded history crosses/);
  assert.equal(h.reads.length, 0);
  assert.equal((await h.adapter.notifications()!.evidence('synthetic-session', new AbortController().signal)).valid, false);
});

test('byte recovery bound retains no oversized dependency but still reports the newest open approval', async t => {
  const records = overBudgetRecords();
  records[1600]!.event.data = { text: 'x'.repeat(16 * 1024 * 1024) };
  records[1799] = { type: 'event', event: { seq: 1799, time: 1700000001799, type: 'turn/start', data: { turn: 900 } } };
  records[1800] = { type: 'event', event: { seq: 1800, time: 1700000001800, type: 'approval/asked', data: { id: 'synthetic-approval' } } };
  const h = await recovered(t, records, 1702, true);
  const snapshot = await h.adapter.snapshot('synthetic-session', new AbortController().signal);
  assert.deepEqual(snapshot.messages, []);
  assert.equal(snapshot.activity, 'waiting');
  assert.match(snapshot.notice!, /bounded history crosses/);
  assert.equal(h.reads.length, 2, 'one oversized backward page must stop further recovery');
  assert.equal(h.cleanup().resumed, 0);
});

for (const fault of ['overlap', 'hole', 'empty', 'cancel'] as const) test(`cold backward recovery rejects ${fault} without activation or partial publication`, async t => {
  const fixture = historyCases[2]!;
  const h = await recovered(t, historyRecords(fixture), fixture.firstSeq);
  const active = new AbortController();
  const page = h.controller.page;
  h.controller.page = async (request, signal) => {
    const result = await page(request, signal);
    if (fault === 'overlap') result.records.push(historyRecords(fixture)[request.beforeSeq]!);
    if (fault === 'hole') result.records.splice(1, 1);
    if (fault === 'empty') result.records = [];
    if (fault === 'cancel') active.abort();
    return result;
  };
  await assert.rejects(h.adapter.snapshot('synthetic-session', active.signal), fault === 'cancel' ? { name: 'AbortError' } : { code: 'unavailable' });
  assert.deepEqual(h.cleanup(), { resumed: 0, closed: 1 });
});

test('page recovery stops at the hard budget but still folds the newest proven activity', async t => {
  const records = overBudgetRecords();
  records[1799] = { type: 'event', event: { seq: 1799, time: 1700000001799, type: 'turn/start', data: { turn: 900 } } };
  records[1800] = { type: 'event', event: { seq: 1800, time: 1700000001800, type: 'turn/end', data: { turn: 900, reason: { kind: 'completed' } } } };
  const h = await recovered(t, records, 1702);
  const snapshot = await h.adapter.snapshot('synthetic-session', new AbortController().signal);
  assert.deepEqual(snapshot.messages, [], 'never publish obsolete answers from an unresolved surface');
  assert.match(snapshot.notice!, /bounded history crosses/);
  assert.equal(snapshot.activity, 'idle', 'old surface ambiguity cannot hide the newest authoritative end');
  assert.equal(h.reads.length, 15, 'opening plus at most fifteen cold pages');
  assert.equal(snapshot.hasMore, true);
  assert.equal((await h.adapter.notifications()!.evidence('synthetic-session', new AbortController().signal)).valid, false, 'never invent completion evidence beyond the recovery budget');
  assert.equal(h.cleanup().resumed, 0);
});
