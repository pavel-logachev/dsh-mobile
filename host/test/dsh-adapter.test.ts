import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, realpath } from 'node:fs/promises';
import { realpathSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createServer } from 'node:net';
import plugin from '../src/plugin.ts';
import { test } from 'node:test';
import { createDshAdapter } from '../src/dsh-adapter.ts';
import { runDemo } from '../src/demo.ts';
import { opening, summary, requestId, streamFrames, committed } from './fixtures/rc2.ts';
import type { DshObservation } from '../src/dsh-adapter.ts';
import { FixtureAdapter, FIXTURE, MULTI_PROJECT_FIXTURE } from '../src/fixture-adapter.ts';
import { createWorkspaceSource } from '../src/workspace-source.ts';
import { HostState } from '../src/state.ts';
import { NotificationFeed } from '../src/notifications.ts';

async function harness(t: { after: (fn: () => Promise<void>) => void }, pollIntervalMs = 10000, throttleMs = 5) {
  const dir = realpathSync.native(await mkdtemp(join(tmpdir(), 'dsh-mobile-adapter-')));
  const alpha = join(dir, 'alpha');
  await mkdir(alpha);
  const frame = structuredClone(opening);
  frame.header.cwd = alpha;
  const row = { ...structuredClone(summary), cwd: alpha };
  let closed = 0;
  let resumed = 0;
  let rows = [row];
  let created: unknown;
  let prompted: unknown;
  let cancelled: unknown;
  let presets = [{ id: 'working', name: 'Working preset' }, { id: 'broken', name: 'Broken preset', broken: 'PRIVATE LOADING DIAGNOSTIC' }];
  const controller = {
    async list() { return { items: rows }; },
    async *follow(_request: unknown, signal: AbortSignal) {
      try { yield structuredClone(frame); resumed++; throw new Error('A read must not activate DSH'); }
      finally { assert.equal(signal.aborted, true); closed++; }
    },
    async page() { return { records: [], hasMore: false }; },
    async projections() { return structuredClone(frame.projections); },
    async create(input: { sessionId: string }) { created = input; return { sessionId: input.sessionId }; },
    async prompt(input: unknown) { prompted = input; return { accepted: true as const }; },
    cancel(input: unknown) { cancelled = input; return { accepted: true as const }; },
  };
  const listeners = new Set<(value: DshObservation) => void>();
  const adapter = await createDshAdapter({
    dshVersion: '0.2.0-rc.2', sessionController: controller,
    agentPresets: { async remoteExportList() { return { presets }; } },
    workspaces: [{ id: 'alpha', name: 'Alpha', path: alpha }],
    events: { subscribeNotifications() { return () => {}; }, subscribe(_id, listener) { listeners.add(listener); return () => { listeners.delete(listener); }; } },
    pollIntervalMs, throttleMs,
  });
  const cleanupResources: (() => Promise<unknown>)[] = [];
  t.after(async () => { for (const cleanup of cleanupResources.toReversed()) await cleanup(); adapter.dispose(); await rm(dir, { recursive: true, force: true }); });
  return { adapter, controller, frame, row, alpha, dir, closeBeforeCleanup: (close: () => Promise<unknown>) => cleanupResources.push(close), cleanup: () => ({ closed, resumed }),
    rows: (value: typeof rows) => { rows = value; }, calls: () => ({ created, prompted, cancelled }),
    emit: (value: DshObservation) => { for (const listener of listeners) listener(value); }, listeners: () => listeners.size };
}

test('point observation resolves its fresh opening header without catalog I/O and reads current liveness', async t => {
  const h = await harness(t); let catalogs = 0;
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', workspaces: [{ id: 'alpha', name: 'Alpha', path: h.alpha }],
    sessionController: { ...h.controller, async list() { catalogs++; throw new Error('Point read scanned corpus'); } },
    sessionStatus: () => ({ running: h.row.running, updatedAt: h.row.updatedAt }),
  });
  t.after(async () => { adapter.dispose(); });
  const signal = new AbortController().signal;
  const first = await adapter.snapshot('session-fixture', signal);
  assert.equal(first.session.title, 'Synthetic example');
  assert.equal(first.session.workspaceId, 'alpha'); assert.equal(catalogs, 0);
  h.row.running = true;
  assert.equal((await adapter.snapshot('session-fixture', signal)).session.running, true, 'status is read at each exact opening cut');
  h.frame.header.cwd = h.dir;
  await assert.rejects(adapter.snapshot('session-fixture', signal), { code: 'not_found' }, 'opening header must resolve uncached even after an earlier authorized read');
  assert.equal(h.cleanup().resumed, 0);
});

test('a failed turn is surfaced without inventing an assistant answer', async t => {
  const h = await harness(t);
  (h.frame.records as any[]).push({
    type: 'event',
    event: { seq: 8, time: 1700000000008, type: 'turn/end', data: { turn: 2, reason: { kind: 'error', error: { code: 'HTTP_400', message: 'messages: text content blocks must be non-empty' } } } },
  });
  h.frame.cursor = 8; h.frame.projections.asOfSeq = 8;
  const snap = await h.adapter.snapshot('session-fixture', new AbortController().signal);
  assert.deepEqual(snap.failure, { code: 'HTTP_400', message: 'messages: text content blocks must be non-empty' });
  assert.equal(snap.messages.some(message => message.role === 'assistant' && message.text.includes('non-empty')), false);
});

test('an attempt failure is surfaced even when no turn/end record is delivered', async t => {
  const h = await harness(t);
  (h.frame.records as any[]).push({
    type: 'event',
    event: { seq: 8, time: 1700000000008, type: 'assistant/attempt', data: { turn: 2, step: 1, stream: [{ type: 'chunk', chunk: { type: 'finish', reason: { kind: 'error', failure: { code: 'HTTP_502', message: 'Provider connection failed' } } } }] } },
  });
  h.frame.cursor = 8; h.frame.projections.asOfSeq = 8;
  const snap = await h.adapter.snapshot('session-fixture', new AbortController().signal);
  assert.deepEqual(snap.failure, { code: 'HTTP_502', message: 'Provider connection failed' });
});

test('concurrent GET-style snapshot and watch share one cold opening with independent cancellation', async t => {
  const h = await harness(t), entered = Promise.withResolvers<void>(), release = Promise.withResolvers<void>();
  let openings = 0;
  const follow = h.controller.follow.bind(h.controller);
  h.controller.follow = async function* (request, signal) { openings++; entered.resolve(); await release.promise; yield* follow(request, signal); };
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: h.controller, workspaces: [{ id: 'alpha', name: 'Alpha', path: h.alpha }],
    sessionStatus: () => ({ running: false, updatedAt: h.row.updatedAt }), pollIntervalMs: 10000,
  });
  t.after(async () => { adapter.dispose(); });
  const gone = new AbortController(), staying = new AbortController();
  const abandoned = adapter.snapshot('session-fixture', gone.signal);
  const watcher = adapter.watch('session-fixture', staying.signal)[Symbol.asyncIterator](), wanted = watcher.next();
  const stillWanted = adapter.snapshot('session-fixture', staying.signal);
  await entered.promise; gone.abort(); release.resolve(); await assert.rejects(abandoned, { name: 'AbortError' });
  try {
    assert.equal((await wanted).value!.messages.at(-1)!.text, 'The authoritative answer.');
    assert.equal((await stillWanted).messages.at(-1)!.text, 'The authoritative answer.');
    assert.equal(openings, 1, 'only one upstream restore/projection for the concurrent burst');
  } finally { staying.abort(); await watcher.return?.(); }
  assert.equal(h.cleanup().resumed, 0); assert.equal(h.cleanup().closed, 1);
  h.frame.records.push({ type: 'event', event: { seq: 8, time: 1700000000008, type: 'session/title', data: { title: 'New title' } } } as any); h.frame.cursor = 8;
  assert.equal((await adapter.snapshot('session-fixture', new AbortController().signal)).session.title, 'New title');
  assert.equal(openings, 2, 'a completed burst is not cached across events or later requests');
});

test('fast point reads hide missing upstream sessions and subagents without catalog authority', async t => {
  const h = await harness(t);
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: h.controller, workspaces: [{ id: 'alpha', name: 'Alpha', path: h.alpha }],
    sessionStatus: () => ({ running: false, updatedAt: 0 }),
  });
  t.after(async () => { adapter.dispose(); });
  h.controller.follow = async function* () { throw Object.assign(new Error('PRIVATE UPSTREAM DETAIL'), { isDSHRemoteError: true, code: 'session/not-found' }); };
  await assert.rejects(adapter.snapshot('missing', new AbortController().signal), { code: 'not_found' });
  h.controller.follow = async function* () { throw Object.assign(new Error('PRIVATE CHILD DETAIL'), { isDSHRemoteError: true, code: 'session/agent-busy', details: { reason: 'use subagent delivery for this child session' } }); };
  await assert.rejects(adapter.snapshot('hidden-child', new AbortController().signal), { code: 'not_found' });
  h.controller.follow = async function* () { throw new Error('Storage unavailable'); };
  await assert.rejects(adapter.snapshot('missing', new AbortController().signal), /Storage unavailable/, 'generic faults are not disguised as absence');
  h.controller.follow = async function* (_request, signal) { try { yield { ...h.frame, header: { ...h.frame.header, origin: 'subagent' } }; } finally { assert.equal(signal.aborted, true); } };
  await assert.rejects(adapter.snapshot('session-fixture', new AbortController().signal), { code: 'not_found' });
});

test('child count changes on normal list and watch refresh while an idle main remains idle', { timeout: 5000 }, async t => {
  const h = await harness(t); let count = 1;
  h.rows([h.row, { ...h.row, sessionId: 'hidden-child', origin: 'subagent', parentSessionId: 'session-fixture', running: true }]);
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: h.controller, workspaces: [{ id: 'alpha', name: 'Alpha', path: h.alpha }],
    sessionStatus: () => ({ running: false, updatedAt: h.row.updatedAt }), runningSubagentCounts: () => new Map([['session-fixture', count]]), pollIntervalMs: 10, throttleMs: 1,
  });
  t.after(async () => { adapter.dispose(); });
  const rows = await adapter.listSessions(new AbortController().signal);
  assert.equal(rows.length, 1); assert.equal(rows[0]!.runningSubagents, 1); assert.equal(rows[0]!.running, false);
  const active = new AbortController(), iterator = adapter.watch('session-fixture', active.signal)[Symbol.asyncIterator]();
  try {
    const first = (await iterator.next()).value!;
    assert.equal(first.runningSubagents, 1); assert.equal(first.activity, 'idle');
    count = 0;
    const settled = (await iterator.next()).value!;
    assert.equal(settled.runningSubagents, 0); assert.equal(settled.session.runningSubagents, 0);
    assert.equal(settled.activity, 'idle'); assert.equal(settled.session.running, false);
    assert.equal((await adapter.listSessions(new AbortController().signal))[0]!.runningSubagents, 0);
  } finally { active.abort(); await iterator.return?.(); }
  assert.equal(h.cleanup().resumed, 0);
});

for (const reason of ['last-waiter', 'lifecycle', 'adapter'] as const) test(`an abandoned ${reason} opening cannot hold a slot or overwrite a newer cold read`, async t => {
  const h = await harness(t), entered = Promise.withResolvers<void>();
  let disposed: (id: string) => void = () => {}, openings = 0, stalledSignal: AbortSignal | undefined;
  const follow = h.controller.follow.bind(h.controller);
  h.controller.follow = async function* (request, signal) {
    if (++openings === 1) { stalledSignal = signal; entered.resolve(); await new Promise<void>(() => {}); }
    yield* follow(request, signal);
  };
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: h.controller, workspaces: [{ id: 'alpha', name: 'Alpha', path: h.alpha }],
    sessionStatus: () => ({ running: false, updatedAt: 0 }), events: { subscribe() { return () => {}; }, subscribeDisposed(listener) { disposed = listener; return () => {}; } },
  });
  t.after(async () => { adapter.dispose(); });
  const active = new AbortController(), opening = adapter.snapshot('session-fixture', active.signal);
  await entered.promise;
  if (reason === 'last-waiter') active.abort(); else if (reason === 'lifecycle') disposed('session-fixture'); else adapter.dispose();
  await assert.rejects(opening, { name: 'AbortError' }); assert.equal(stalledSignal!.aborted, true);
  if (reason !== 'adapter') {
    const fresh = await adapter.snapshot('session-fixture', new AbortController().signal);
    assert.equal(fresh.messages.at(-1)!.text, 'The authoritative answer.'); assert.equal(openings, 2);
  }
});

test('coalesced openings enforce sixty-four waiters and sixteen active-session slots', async t => {
  const h = await harness(t); let openings = 0;
  h.controller.follow = async function* (_request, signal) { openings++; await new Promise<void>(resolve => signal.addEventListener('abort', () => resolve(), { once: true })); signal.throwIfAborted(); };
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: h.controller, workspaces: [{ id: 'alpha', name: 'Alpha', path: h.alpha }], sessionStatus: () => ({ running: false, updatedAt: 0 }) });
  t.after(async () => { adapter.dispose(); });
  const active = new AbortController();
  const waiters = Array.from({ length: 64 }, () => adapter.snapshot('held', active.signal));
  const rejected = waiters.map(waiter => assert.rejects(waiter, { name: 'AbortError' }));
  await assert.rejects(adapter.snapshot('held', active.signal), { code: 'unavailable' });
  assert.equal(openings, 1); active.abort(); await Promise.all(rejected);
  const many = new AbortController();
  const sessions = Array.from({ length: 16 }, (_, i) => adapter.snapshot(`held-${i}`, many.signal));
  const stopped = sessions.map(session => assert.rejects(session, { name: 'AbortError' }));
  const overflow = adapter.snapshot('overflow', many.signal);
  stopped.push(assert.rejects(overflow, { name: 'AbortError' }));
  assert.equal(openings, 17, 'the seventeenth session waits without opening upstream');
  many.abort(); await Promise.all(stopped);
  assert.equal(openings, 17, 'an aborted capacity waiter never starts an opening');
});

test('coalescing never shares authorization bindings across a changed registry provider or retargeted cwd', async t => {
  const h = await harness(t), entered = Promise.withResolvers<void>(), release = Promise.withResolvers<void>();
  let registry = { list: () => [{ id: 'alpha', path: h.alpha, title: 'Alpha', async status() { return 'ok' as const; } }], archivedSessionIds: [] as string[] };
  const source = await createWorkspaceSource({ workspaceSource: 'dsh-registry' }, () => registry);
  let resolved = h.alpha;
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: h.controller, workspaceSource: source, sessionStatus: () => ({ running: false, updatedAt: 0 }), realpath: async () => { entered.resolve(); await release.promise; return resolved; } });
  t.after(async () => { adapter.dispose(); });
  const pending = adapter.snapshot('session-fixture', new AbortController().signal);
  await entered.promise; registry = { ...registry }; release.resolve();
  await assert.rejects(pending, { code: 'not_found' }, 'same rows with a new provider invalidate the awaiting scope');
  assert.equal((await adapter.snapshot('session-fixture', new AbortController().signal)).session.workspaceId, 'alpha');
  resolved = h.dir;
  await assert.rejects(adapter.snapshot('session-fixture', new AbortController().signal), { code: 'not_found' });
  resolved = h.alpha; registry.archivedSessionIds = ['session-fixture'];
  await assert.rejects(adapter.snapshot('session-fixture', new AbortController().signal), { code: 'not_found' });
});

test('failure scope uses fresh point headers, hides missing/child/archive and invalidates an awaited provider view', async t => {
  const h = await harness(t);
  let registry = { list: () => [{ id: 'alpha', path: h.alpha, title: 'Alpha', async status() { return 'ok' as const; } }], archivedSessionIds: [] as string[] };
  const source = await createWorkspaceSource({ workspaceSource: 'dsh-registry' }, () => registry);
  let header: unknown = h.frame.header, reads = 0;
  let hold: { entered: ReturnType<typeof Promise.withResolvers<void>>; release: ReturnType<typeof Promise.withResolvers<void>> } | undefined;
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', workspaceSource: source,
    sessionController: { ...h.controller, async list() { throw new Error('Header admission scanned the catalog'); }, async *follow() { throw new Error('Header admission read event log'); } },
    sessionHeader: async () => { reads++; return header; }, realpath: async cwd => { hold?.entered.resolve(); if (hold) await hold.release.promise; return realpath(cwd); },
  });
  t.after(async () => { hold?.release.resolve(); adapter.dispose(); });
  const signal = new AbortController().signal;
  assert.equal(await adapter.sessionScope('session-fixture', signal), 'alpha');
  header = { ...h.frame.header, cwd: h.dir };
  await assert.rejects(adapter.sessionScope('session-fixture', signal), { code: 'not_found' });
  header = { ...h.frame.header, origin: 'subagent' };
  await assert.rejects(adapter.sessionScope('session-fixture', signal), { code: 'not_found' });
  header = undefined;
  await assert.rejects(adapter.sessionScope('session-fixture', signal), { code: 'not_found' });
  header = h.frame.header; registry.archivedSessionIds = ['session-fixture'];
  const before = reads;
  await assert.rejects(adapter.sessionScope('session-fixture', signal), { code: 'not_found' });
  assert.equal(reads, before, 'archive is checked even before point metadata read');
  registry.archivedSessionIds = []; hold = { entered: Promise.withResolvers<void>(), release: Promise.withResolvers<void>() };
  const pending = adapter.sessionScope('session-fixture', signal);
  await hold.entered.promise; registry = { ...registry }; hold.release.resolve();
  await assert.rejects(pending, { code: 'not_found' }, 'a new provider cannot authorize an old awaited view');
  assert.equal(await adapter.sessionScope('session-fixture', signal), 'alpha');
  assert.equal(h.cleanup().resumed, 0); assert.equal(h.cleanup().closed, 0);
});

test('notification point authority rejects child/archive/remap and proves completion without corpus IO', async t => {
  const h = await harness(t); let header: unknown = h.frame.header;
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', workspaces: [{ id: 'alpha', name: 'Alpha', path: h.alpha }],
    sessionController: { ...h.controller, async list() { throw Error('notification scanned corpus'); } },
    sessionHeader: async () => header, sessionStatus: () => ({ running: false, updatedAt: 0 }),
    events: { subscribe() { return () => {}; }, subscribeNotifications() { return () => {}; } },
  });
  t.after(() => adapter.dispose()); const source = adapter.notifications()!, signal = new AbortController().signal;
  const listed = await source.lookup!('session-fixture', signal);
  assert.equal(listed?.workspaceId, 'alpha');
  assert.deepEqual((await source.evidence('session-fixture', signal, listed)).completed, [{ turn: 1, sourceSeq: 7 }]);
  header = { ...h.frame.header, origin: 'subagent' }; assert.equal(await source.lookup!('session-fixture', signal), undefined);
  header = { ...h.frame.header, cwd: h.dir }; assert.equal(await source.lookup!('session-fixture', signal), undefined);
  header = undefined; assert.equal(await source.lookup!('session-fixture', signal), undefined);
  assert.equal(h.cleanup().resumed, 0, 'point notification read must not promote the cold session');
});

test('notification evidence is cold-safe and demands authoritative completed turn and pending episode', async t => {
  const h = await harness(t); const signal = new AbortController().signal;
  const source = h.adapter.notifications()!;
  assert.deepEqual((await source.evidence('session-fixture', signal)).completed, [{ turn: 1, sourceSeq: 7 }]);
  (h.frame.records[7]!.event.data as any).reason.kind = 'cancelled';
  assert.equal((await source.evidence('session-fixture', signal)).completed.length, 0);
  h.frame.records.pop(); h.frame.cursor = 6; h.row.running = true;
  assert.equal((await source.evidence('session-fixture', signal)).completed.length, 0);
  (h.frame.records as any[]).push({ type: 'event', event: { seq: 7, time: 1700000000008, type: 'approval/asked', data: { id: 'approval', toolName: 'write' } } }); h.frame.cursor = 7;
  assert.equal((await source.evidence('session-fixture', signal)).pending, true);
  (h.frame.records as any[]).push({ type: 'event', event: { seq: 8, time: 1700000000009, type: 'approval/decided', data: { id: 'approval', outcome: 'allowed-once' } } }); h.frame.cursor = 8;
  assert.equal((await source.evidence('session-fixture', signal)).pending, false);
  assert.equal(h.cleanup().resumed, 0);
});

for (const kind of ['question', 'approval'] as const) test(`adapter to feed recovers idle open ${kind} but suppresses continued and stale stopped requests`, async t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const h = await harness(t); h.row.running = false;
  h.frame.records = h.frame.records.slice(0, 1); h.frame.cursor = 0;
  (h.frame.records as any[]).push({ type: 'event', event: { seq: 1, time: 1700000000001, type: kind === 'question' ? 'tool/call' : 'approval/asked', data: kind === 'question' ? { turn: 1, step: 1, callId: 'q', name: 'ask_user_question', arguments: '{"questions":[{"id":"one","question":"Synthetic"}]}' } : { id: 'a', toolName: 'read' } } });
  h.frame.cursor = 1; h.frame.projections.asOfSeq = 1;
  if (kind === 'question') (h.frame.projections.values.userQuestions.active as unknown[]).push({ callId: 'q', state: 'open', questions: [{ id: 'one', question: 'Synthetic' }] });
  const state = new HostState(join(h.dir, 'notification-test.sqlite'));
  const device = state.consumePairing(state.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] }).pairingToken, 'Synthetic');
  const feed = new NotificationFeed(state, h.adapter.notifications()!); h.closeBeforeCleanup(async () => { await feed.close(); state.close(); });
  await feed.start(); feed.putSettings(device.deviceId, { expectedRevision: 0, enabled: true, projects: [], chats: [] }); await feed.idle();
  const head = (await feed.page(device.deviceId)).nextCursor;
  t.mock.timers.tick(60000); await feed.idle();
  assert.equal((await h.adapter.notifications()!.evidence('session-fixture', new AbortController().signal)).pending, true, 'running:false must not suppress an authoritative open request');
  const live = await feed.page(device.deviceId, head);
  assert.equal((await feed.page(device.deviceId)).pending.length, 1);
  assert.equal(live.items.filter(e => e.kind === 'attention-needed').length, 1);
  if (kind === 'question') {
    (h.frame.projections.values.userQuestions.active as any[])[0].state = 'continued';
    t.mock.timers.tick(60000); await feed.idle();
    assert.equal((await feed.page(device.deviceId)).pending.length, 0, 'continued-only is never an alert');
    (h.frame.projections.values.userQuestions.active as any[])[0].state = 'open';
  }
  (h.frame.records as any[]).push({ type: 'event', event: { seq: 2, time: 1700000000002, type: 'turn/end', data: { turn: 1, reason: { kind: 'cancelled' } } } });
  h.frame.cursor = 2; h.frame.projections.asOfSeq = 2;
  t.mock.timers.tick(60000); await feed.idle();
  const stopped = await feed.page(device.deviceId, live.nextCursor);
  assert.equal(stopped.pending.length, 0, 'historical unmatched request after stop is stale even with a leftover open projection');
  assert.equal(stopped.items.filter(e => e.kind === 'attention-needed').length, 0);
  assert.equal(h.cleanup().resumed, 0); assert.deepEqual(h.calls(), { created: undefined, prompted: undefined, cancelled: undefined });
});

test('registry sessions map by canonical cwd, hide archived/missing roots, and create uses live admission scope', async t => {
  const h = await harness(t);
  const beta = join(h.dir, 'beta'); await mkdir(beta);
  let rows = [{ id: 'alpha', path: h.alpha, title: 'Альфа (демо)', async status() { return 'ok' as const; } }];
  let archived: string[] = [];
  const registry = { list: () => rows, get archivedSessionIds() { return archived; } };
  const source = await createWorkspaceSource({ workspaceSource: 'dsh-registry' }, () => registry);
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: h.controller, workspaceSource: source });
  t.after(async () => { adapter.dispose(); });
  const signal = new AbortController().signal;
  h.rows([h.row, { ...h.row, sessionId: 'beta-session', cwd: join(beta, '..', 'beta') }]);
  assert.deepEqual((await adapter.listSessions(signal)).map(item => item.id), ['session-fixture']);
  rows.unshift({ id: 'beta', path: beta, title: 'Бета (демо)', async status() { return 'ok' as const; } });
  assert.deepEqual((await adapter.listSessions(signal)).map(item => item.workspaceId), ['alpha', 'beta']);
  archived = ['session-fixture'];
  assert.deepEqual((await adapter.listSessions(signal)).map(item => item.id), ['beta-session']);
  await assert.rejects(adapter.snapshot('session-fixture', signal), { code: 'not_found' });
  await assert.rejects(adapter.prompt('session-fixture', 'Not visible', requestId, signal, 'alpha'), { code: 'not_found' });
  await assert.rejects(adapter.cancel('session-fixture', signal, 7, 'alpha'), { code: 'not_found' });
  assert.equal(h.calls().cancelled, undefined, 'archived cancellation must not reach upstream');
  rows = rows.filter(item => item.id !== 'alpha');
  await assert.rejects(adapter.createSession({ workspaceId: 'alpha', requestId }, signal, 'alpha'), { code: 'not_found' });
  await adapter.createSession({ workspaceId: 'beta', requestId }, signal, 'beta');
  assert.deepEqual(h.calls().created, { workspaceId: 'beta', sessionId: 'session-7b15f469-52f6-4f44-a0a1-c2b8575e3f90' }, 'registry create must attach through the inspected controller workspaceId lane, never combine it with cwd');
  rows = [];
  await assert.rejects(adapter.createSession({ workspaceId: 'beta', requestId }, signal, 'beta'), { code: 'not_found' });
});

test('authorized mutations reject identity changes during adapter preparation and cold iterator cleanup', async t => {
  const h = await harness(t);
  h.row.running = true;
  const path = await realpath(h.alpha);
  for (const operation of ['prompt', 'cancel', 'create'] as const) {
    let id = 'alpha';
    const registry = { list: () => [{ id, path, title: 'Synthetic scope', async status() { return 'ok' as const; } }], archivedSessionIds: [] };
    const source = await createWorkspaceSource({ workspaceSource: 'dsh-registry' }, () => registry);
    const controller = { ...h.controller, async *follow(_request: unknown, signal: AbortSignal) {
      try { yield structuredClone(h.frame); }
      finally { assert.equal(signal.aborted, true); id = 'beta'; }
    } };
    const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: controller, workspaceSource: source, realpath: async () => {
      if (operation !== 'cancel') id = 'beta';
      return path;
    } });
    t.after(async () => { adapter.dispose(); });
    const signal = new AbortController().signal;
    const mutation = operation === 'prompt' ? adapter.prompt('session-fixture', 'Must remain in alpha', requestId, signal, 'alpha') : operation === 'cancel' ? adapter.cancel('session-fixture', signal, 7, 'alpha') : adapter.createSession({ workspaceId: 'alpha', requestId }, signal, 'alpha');
    await assert.rejects(mutation, { code: 'not_found' }, operation);
  }
  assert.deepEqual(h.calls(), { prompted: undefined, cancelled: undefined, created: undefined });
});

test('canonical case-distinct cwd results cannot authorize an unregistered path in registry or explicit mode', async t => {
  const h = await harness(t);
  const registered = join(h.dir, 'CaseRoot'); await mkdir(registered);
  const canonicalRegistered = await realpath(registered);
  const canonicalOther = canonicalRegistered.slice(0, -8) + 'caseroot';
  const registry = { list: () => [{ id: 'alpha', title: 'Synthetic Alpha', path: canonicalRegistered, async status() { return 'ok' as const; } }], archivedSessionIds: [] };
  const source = await createWorkspaceSource({ workspaceSource: 'dsh-registry' }, () => registry);
  h.rows([{ ...h.row, cwd: join(h.dir, 'synthetic-case-sensitive-cwd') }]);
  for (const scope of [{ workspaceSource: source }, { workspaces: [{ id: 'alpha', name: 'Alpha', path: registered }] }]) {
    const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: h.controller, ...scope, realpath: async () => canonicalOther });
    t.after(async () => { adapter.dispose(); });
    assert.deepEqual(await adapter.listSessions(new AbortController().signal), []);
    await assert.rejects(adapter.prompt('session-fixture', 'Never cross canonical identity', requestId, new AbortController().signal, 'alpha'), { code: 'not_found' });
  }
  assert.equal(h.calls().prompted, undefined);
});

test('noise fixtures project service input without changing human text or request correlation', async t => {
  const h = await harness(t);
  const vectors = JSON.parse(readFileSync(new URL('../../fixtures/message-noise.json', import.meta.url), 'utf8')) as {name: string; text: string; kind: string; cleanText?: string}[];
  for (const vector of vectors) {
    h.frame.records = [{ type: 'event', event: { seq: 0, time: 1700000000000, type: 'user/message', surfaceOp: 'append', data: { id: 'noise', role: 'user', source: { kind: 'legacy' }, content: [{ type: 'text', text: vector.text }] } } }] as typeof h.frame.records;
    h.frame.cursor = 0;
    const snapshot = await h.adapter.snapshot('session-fixture', new AbortController().signal);
    assert.equal(snapshot.messages[0]?.kind ?? 'message', vector.kind, vector.name);
    assert.equal(snapshot.messages[0]?.text, vector.cleanText ?? vector.text, vector.name);
  }
  const human = h.frame.records[0]!.event.data as { source: unknown; content: unknown };
  human.source = { kind: 'user', rpcId: requestId };
  human.content = [{ type: 'text', text: vectors.find(v => v.name === 'mixed')!.text }];
  const mixed = (await h.adapter.snapshot('session-fixture', new AbortController().signal)).messages[0]!;
  assert.equal(mixed.text, vectors.find(v => v.name === 'mixed')!.text, 'human attribution preserves the complete original');
  assert.equal(mixed.requestId, requestId);
  human.content = [{ type: 'text', text: vectors[0]!.text }];
  assert.equal((await h.adapter.snapshot('session-fixture', new AbortController().signal)).messages[0]!.kind, 'message', 'explicit human origin wins');
  for (const [source, kind] of Object.entries({ 'agent-message': 'agent_event', 'subagent-settled': 'agent_event', 'tool-jobs': 'agent_event', 'compact-checkpoint': 'context', 'runtime-context': 'context', 'time-context': 'context', 'agent-instructions': 'context', 'skill-catalog': 'context', 'plugin:hindsight': 'context' })) {
    const data = h.frame.records[0]!.event.data as { source: unknown; content: unknown };
    data.source = { kind: source }; data.content = [{ type: 'text', text: 'No text-pattern hint.' }];
    assert.equal((await h.adapter.snapshot('session-fixture', new AbortController().signal)).messages[0]?.kind, kind, source);
  }
});

test('activity detail tracks current turn and unmatched calls without tool payloads', async t => {
  const h = await harness(t);
  const signal = new AbortController().signal;
  h.row.running = true;
  h.frame.records.pop(); h.frame.cursor = 6;
  assert.deepEqual((await h.adapter.snapshot('session-fixture', signal)).activityDetail, { turnStartedAt: 1700000000000 });
  const append = (type: string, data: unknown) => {
    const seq = ++h.frame.cursor;
    h.frame.records.push({ type: 'event', event: { type, seq, time: 1700000000000 + seq, data, ...(type === 'tool/result' ? { surfaceOp: 'append' } : {}) } } as typeof h.frame.records[number]);
  };
  append('tool/call', { turn: 1, callId: 'a', name: 'read', arguments: 'PRIVATE' });
  append('tool/call', { turn: 1, callId: 'b', name: 'grep', arguments: 'PRIVATE' });
  assert.deepEqual((await h.adapter.snapshot('session-fixture', signal)).activityDetail, { turnStartedAt: 1700000000000, tool: 'grep' });
  append('tool/result', { turn: 1, message: { toolCallId: 'b', content: [{ type: 'text', text: 'PRIVATE' }] } });
  assert.equal((await h.adapter.snapshot('session-fixture', signal)).activityDetail?.tool, 'read');
  append('turn/end', { turn: 1 });
  assert.equal((await h.adapter.snapshot('session-fixture', signal)).activityDetail, undefined);
  append('turn/start', { turn: 2 });
  append('tool/call', { turn: 1, callId: 'old', name: 'old_turn' });
  assert.deepEqual((await h.adapter.snapshot('session-fixture', signal)).activityDetail, { turnStartedAt: 1700000000011 });
  h.frame.records = h.frame.records.slice(-1); // A bounded cut cannot establish the current turn.
  assert.equal((await h.adapter.snapshot('session-fixture', signal)).activityDetail, undefined);
});

test('a cold snapshot returns the authoritative text surface without resuming an agent', async (t) => {
  const h = await harness(t);
  const result = await h.adapter.snapshot('session-fixture', new AbortController().signal);
  assert.deepEqual(result.messages, [
    { id: 'user-1', role: 'user', kind: 'message', text: 'A synthetic question.', createdAt: 1700000000002, requestId: '7b15f469-52f6-4f44-a0a1-c2b8575e3f90' },
    { id: 'assistant-2', role: 'assistant', text: 'The authoritative answer.', createdAt: 1700000000006 },
  ]);
  assert.equal(result.session.title, 'Synthetic example');
  assert.equal(result.activity, 'idle');
  assert.equal(JSON.stringify(result).includes('PRIVATE'), false);
  assert.equal(JSON.stringify(result).includes(h.alpha), false);
  assert.deepEqual(h.cleanup(), { closed: 1, resumed: 0 });
});

test('only owner-configured canonical workspace roots and ordinary sessions are permitted', async (t) => {
  const h = await harness(t);
  await mkdir(join(h.alpha, 'child'));
  h.rows([
    h.row,
    { ...h.row, sessionId: 'child', origin: 'subagent' },
    { ...h.row, sessionId: 'nested', cwd: join(h.alpha, 'child') },
    { ...h.row, sessionId: 'unknown', cwd: h.dir },
    { ...h.row, sessionId: 'alias', cwd: join(h.alpha, '..', 'alpha') },
    { ...h.row, sessionId: 'missing', cwd: join(h.dir, 'missing') },
  ]);
  assert.deepEqual((await h.adapter.listSessions(new AbortController().signal)).map((item) => item.id), ['session-fixture', 'alias']);
  await assert.rejects(h.adapter.snapshot('unknown', new AbortController().signal), { code: 'not_found' });
  await assert.rejects(h.adapter.prompt('child', 'Text', requestId, new AbortController().signal, 'alpha'), { code: 'not_found' });
  assert.deepEqual(h.cleanup(), { closed: 0, resumed: 0 });
  assert.deepEqual(h.calls(), { created: undefined, prompted: undefined, cancelled: undefined });
});

test('create adopts a stable UUID-derived identity at the configured root and prompt uses queue mode', async (t) => {
  const h = await harness(t);
  const signal = new AbortController().signal;
  assert.deepEqual(await h.adapter.listPresets(signal), [{ id: 'working', name: 'Working preset' }]);
  await assert.rejects(h.adapter.createSession({ workspaceId: h.alpha, requestId }, signal, h.alpha), { code: 'not_found' });
  await assert.rejects(h.adapter.createSession({ workspaceId: 'alpha', presetId: 'broken', requestId }, signal, 'alpha'), { code: 'invalid_request' });
  assert.deepEqual(await h.adapter.createSession({ workspaceId: 'alpha', presetId: 'working', requestId }, signal, 'alpha'), { sessionId: 'session-7b15f469-52f6-4f44-a0a1-c2b8575e3f90' });
  await h.adapter.prompt('session-fixture', 'Hello', requestId, signal, 'alpha');
  assert.deepEqual(h.calls().created, { cwd: h.alpha, agentPreset: 'working', sessionId: 'session-7b15f469-52f6-4f44-a0a1-c2b8575e3f90' });
  assert.deepEqual(h.calls().prompted, { sessionId: 'session-fixture', requestId, mode: 'queue', content: [{ type: 'text', text: 'Hello' }] });
});

test('watch publishes the first text immediately, then coalesces subsequent text at the original throttle', async t => {
  const h = await harness(t, 10000, 100);
  const abort = new AbortController();
  const iterator = h.adapter.watch('session-fixture', abort.signal)[Symbol.asyncIterator]();
  h.closeBeforeCleanup(async () => { abort.abort(); await iterator.return!(); });
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1700000100000 });
  await iterator.next();
  let ready = false;
  const first = iterator.next().then(item => { ready = true; return item.value; });
  h.emit(streamFrames[0] as DshObservation);
  h.emit(streamFrames[1] as DshObservation);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(ready, true, 'first text must arrive without advancing the 100ms clock');
  assert.equal((await first).messages.at(-1)?.text, 'Hello ');

  ready = false;
  const next = iterator.next().then(item => { ready = true; return item.value; });
  h.emit({ type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'attempt-fixture', revision: 3, index: 1, chunk: { type: 'text-delta', index: 0, text: 'world' } } });
  await new Promise(resolve => setImmediate(resolve));
  t.mock.timers.tick(99);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(ready, false, 'subsequent text still waits for the throttle');
  h.emit({ type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'attempt-fixture', revision: 4, index: 2, chunk: { type: 'text-delta', index: 0, text: '.' } } });
  await new Promise(resolve => setImmediate(resolve));
  t.mock.timers.tick(1);
  assert.equal((await next).messages.at(-1)?.text, 'Hello world.', 'one replacement includes both subsequent deltas');
});

test('watch waits through empty and non-text chunks but gives each new attempt a text leading edge', async t => {
  const h = await harness(t, 10000, 100), abort = new AbortController();
  const iterator = h.adapter.watch('session-fixture', abort.signal)[Symbol.asyncIterator]();
  h.closeBeforeCleanup(async () => { abort.abort(); await iterator.return!(); });
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1700000100000 });
  await iterator.next();
  let ready = false;
  const first = iterator.next().then(item => { ready = true; return item.value; });
  h.emit(streamFrames[0] as DshObservation);
  h.emit({ type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'attempt-fixture', revision: 2, index: 0, chunk: { type: 'text-delta', index: 0, text: '' } } });
  h.emit({ type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'attempt-fixture', revision: 3, index: 1, chunk: { type: 'reasoning-delta', index: 1, text: 'PRIVATE' } } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(ready, false, 'start, empty text and reasoning do not bypass the throttle');
  t.mock.timers.tick(100);
  assert.equal((await first).messages.some(m => m.provisional), false);
  const text = iterator.next();
  h.emit({ type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'attempt-fixture', revision: 4, index: 2, time: 1700000000010, chunk: { type: 'block-end', index: 0, block: { type: 'text', text: '\uFEFFЯ🌍\r\n\r\n' } } } });
  assert.equal((await text).value.messages.at(-1)?.text, '\uFEFFЯ🌍\r\n\r\n', 'block-end may supply the first visible text');
  const secondAttempt = iterator.next();
  h.emit({ type: 'assistant-stream', frame: { type: 'start', attemptId: 'next-attempt', revision: 1, turn: 2, step: 2 } });
  h.emit({ type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'next-attempt', revision: 2, index: 0, chunk: { type: 'text-delta', index: 0, text: 'Next' } } });
  assert.equal((await secondAttempt).value.messages.at(-1)?.id, 'provisional:next-attempt', 'a new attempt need not wait for the previous attempt throttle');
});

test('text already included in the opening snapshot does not acquire another leading edge', async t => {
  const h = await harness(t, 10000, 100), abort = new AbortController();
  Object.assign(h.frame.assistantStream, { revision: 2, activeAttempt: { attemptId: 'active', turn: 2, step: 1, nextIndex: 1, stream: [{ type: 'text-chunks', index: 0, texts: ['Opening'] }] } });
  const iterator = h.adapter.watch('session-fixture', abort.signal)[Symbol.asyncIterator]();
  h.closeBeforeCleanup(async () => { abort.abort(); await iterator.return!(); });
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1700000100000 });
  assert.equal((await iterator.next()).value.messages.at(-1)?.text, 'Opening');
  let ready = false;
  const next = iterator.next().then(item => { ready = true; return item.value; });
  h.emit({ type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'active', revision: 3, index: 1, chunk: { type: 'text-delta', index: 0, text: ' tail' } } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(ready, false);
  t.mock.timers.tick(100);
  assert.equal((await next).messages.at(-1)?.text, 'Opening tail');
});

test('text queued during cold opening is included once and its next delta stays throttled', async t => {
  const h = await harness(t, 10000, 100), abort = new AbortController();
  const follow = h.controller.follow;
  let enter!: () => void, release!: () => void;
  const entered = new Promise<void>(resolve => { enter = resolve; });
  const gate = new Promise<void>(resolve => { release = resolve; });
  h.controller.follow = async function* (request, signal) { enter(); await gate; yield* follow(request, signal); };
  const iterator = h.adapter.watch('session-fixture', abort.signal)[Symbol.asyncIterator]();
  h.closeBeforeCleanup(async () => { release(); abort.abort(); await iterator.return!(); });
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1700000100000 });
  const opening = iterator.next();
  await entered;
  h.emit(streamFrames[0] as DshObservation); h.emit(streamFrames[1] as DshObservation);
  release();
  assert.equal((await opening).value.messages.at(-1)?.text, 'Hello ');
  let ready = false;
  const next = iterator.next().then(item => { ready = true; return item.value; });
  h.emit({ type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'attempt-fixture', revision: 3, index: 1, chunk: { type: 'text-delta', index: 0, text: 'tail' } } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(ready, false, 'queued opening text already consumed the leading edge');
  t.mock.timers.tick(100);
  assert.equal((await next).messages.at(-1)?.text, 'Hello tail');
});

for (const boundary of ['bytes', 'blocks'] as const) test(`leading-edge text respects the exact provisional ${boundary} bound and drops overflow`, async t => {
  const h = await harness(t, 10000, 100), abort = new AbortController();
  const iterator = h.adapter.watch('session-fixture', abort.signal)[Symbol.asyncIterator]();
  h.closeBeforeCleanup(async () => { abort.abort(); await iterator.return!(); });
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1700000100000 });
  await iterator.next();
  const pending = iterator.next();
  h.emit(streamFrames[0] as DshObservation);
  // Both cases are exactly at the declared bound. Astral UTF-8 text checks bytes,
  // not JS code units; reverse block order checks sorted assembly at the edge.
  const count = boundary === 'bytes' ? 1 : 1024;
  for (let index = 0; index < count; index++) h.emit({ type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'attempt-fixture', revision: index + 2, index, chunk: { type: 'text-delta', index: boundary === 'bytes' ? 0 : 1023 - index, text: boundary === 'bytes' ? '🌍'.repeat(524288) : index === 1023 ? 'A' : 'x' } } });
  const bounded = (await pending).value.messages.at(-1)!;
  assert.equal(bounded.provisional, true);
  if (boundary === 'bytes') { assert.equal(Buffer.byteLength(bounded.text), 2097152); assert.equal(bounded.text.isWellFormed(), true); }
  else assert.equal(bounded.text, 'A' + 'x'.repeat(1023));
  const overflow = iterator.next();
  h.emit({ type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'attempt-fixture', revision: count + 2, index: count, chunk: { type: 'text-delta', index: boundary === 'bytes' ? 0 : 1024, text: 'x' } } });
  await new Promise(resolve => setImmediate(resolve));
  t.mock.timers.tick(100);
  const invalid = await overflow;
  assert.equal(invalid.value.activity, 'unknown');
  assert.equal(invalid.value.messages.some(m => m.provisional), false);
  assert.match(invalid.value.notice!, /observation was interrupted/);
});

for (const ending of ['committed', 'revision-gap', 'overflow'] as const) test(`a first-text burst ending in ${ending} publishes only the final authoritative state`, async t => {
  const h = await harness(t, 10000, 100), abort = new AbortController();
  const iterator = h.adapter.watch('session-fixture', abort.signal)[Symbol.asyncIterator]();
  h.closeBeforeCleanup(async () => { abort.abort(); await iterator.return!(); });
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1700000100000 });
  await iterator.next();
  let ready = false;
  const next = iterator.next().then(item => { ready = true; return item.value; });
  for (const frame of streamFrames) h.emit(frame as DshObservation);
  if (ending === 'committed') {
    h.emit(committed);
    h.emit({ type: 'assistant-stream', frame: { type: 'end', attemptId: 'attempt-fixture', revision: 6, index: 4, outcome: { kind: 'committed', seq: 8 } } });
  } else h.emit({ type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'attempt-fixture', revision: ending === 'revision-gap' ? 7 : 6, index: 4, chunk: { type: 'text-delta', index: 0, text: 'x'.repeat(2097153) } } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(ready, false, 'no stale first-text signal may bypass the throttle after settlement or invalidation');
  t.mock.timers.tick(100);
  const result = await next;
  assert.equal(result.messages.some(m => m.provisional), false);
  if (ending === 'committed') { assert.equal(result.cursor, 8); assert.equal(result.messages.at(-1)?.id, 'assistant-3'); assert.notEqual(result.activity, 'unknown'); }
  else { assert.equal(result.activity, 'unknown'); assert.match(result.notice!, /observation was interrupted/); }
  assert.deepEqual(h.cleanup(), { closed: 1, resumed: 0 }, 'no early cold refresh');
});

test('watch replaces provisional text with durable text and releases cold-read subscriptions on abort', async (t) => {
  const h = await harness(t);
  const lifetime = new AbortController();
  const iterator = h.adapter.watch('session-fixture', lifetime.signal)[Symbol.asyncIterator]();
  const first = (await iterator.next()).value;
  assert.equal(first.messages.at(-1)?.text, 'The authoritative answer.');
  const pending = iterator.next();
  for (const frame of streamFrames) h.emit(frame as DshObservation);
  const live = (await pending).value;
  assert.deepEqual(live.messages.at(-1), { id: 'provisional:attempt-fixture', role: 'assistant', text: 'Hello world.', createdAt: 1700000000010, provisional: true });
  assert.equal(live.activity, 'running');
  assert.equal(JSON.stringify(live).includes('PRIVATE'), false);
  const settled = iterator.next();
  h.emit(committed);
  const durable = (await settled).value;
  assert.equal(durable.messages.at(-1)?.id, 'assistant-3');
  assert.equal(durable.messages.some((message) => message.provisional), false);
  lifetime.abort();
  assert.equal((await iterator.next()).done, true);
  assert.equal(h.listeners(), 0);
  assert.deepEqual(h.cleanup(), { closed: 1, resumed: 0 });
});

test('the explicitly labelled fixture responds locally after delay, deduplicates and keeps execution independent of watch', async () => {
  const adapter = new FixtureAdapter({ answerDelayMs: 15 });
  try {
    const signal = new AbortController().signal;
    assert.equal(adapter.upstreamVersion, 'fixture');
    assert.equal((await adapter.snapshot(FIXTURE.existingSessionId, signal)).messages[0]?.text, FIXTURE.existingMessage);
    const created = await adapter.createSession({ workspaceId: FIXTURE.workspaceId, requestId }, signal, FIXTURE.workspaceId);
    assert.equal((await adapter.snapshot(created.sessionId, signal)).session.title, FIXTURE.createdSessionTitle);
    await adapter.prompt(created.sessionId, 'DSH_MOBILE_ACCEPTANCE_SYNTHETIC_PROMPT_V1', requestId, signal, FIXTURE.workspaceId);
    await adapter.prompt(created.sessionId, 'DSH_MOBILE_ACCEPTANCE_SYNTHETIC_PROMPT_V1', requestId, signal, FIXTURE.workspaceId);
    const watching = adapter.watch(created.sessionId, signal)[Symbol.asyncIterator]();
    assert.equal((await watching.next()).value.activity, 'running');
    assert.equal((await watching.next()).value.messages.at(-1)?.text, FIXTURE.expectedAssistantText);
    await watching.return?.();
    const final = await adapter.snapshot(created.sessionId, signal);
    assert.equal(final.messages.length, 2);
    assert.equal(final.activity, 'idle');
  } finally { adapter.dispose(); }
});

test('the plugin composes and disposes with actual installed Cordis and harmless controller stubs', { skip: !process.env.DSH_MOBILE_CORDIS_MODULE }, async (t) => {
  const h = await harness(t);
  const { Context } = await import(pathToFileURL(process.env.DSH_MOBILE_CORDIS_MODULE!).href);
  const ctx = new Context();
  ctx.provide('sessionController', h.controller);
  ctx.provide('agentPresets', { async remoteExportList() { return { presets: [] }; } });
  const reserve = createServer();
  await new Promise<void>((resolve, reject) => { reserve.once('error', reject); reserve.listen(0, '127.0.0.1', resolve); });
  const port = (reserve.address() as { port: number }).port;
  await new Promise<void>((resolve, reject) => reserve.close((error) => error ? reject(error) : resolve()));
  const config = { dshVersion: '0.2.0-rc.2', hostName: 'Isolated composition test', bind: '127.0.0.1', port,
    statePath: join(h.dir, 'plugin.sqlite'), workspaces: [{ id: 'alpha', name: 'Alpha', path: h.alpha }], allowInsecureLoopback: true };
  const fiber = ctx.plugin(plugin, config);
  h.closeBeforeCleanup(async () => { await fiber.dispose(); await ctx.fiber.dispose(); });
  await fiber;
  const response = await fetch(`http://127.0.0.1:${port}/v1/capabilities`);
  assert.equal(response.status, 401);
  const admin = new (await import('../src/state.ts')).HostState(config.statePath);
  try {
    const paired = admin.consumePairing(admin.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] }).pairingToken, 'Notification stub');
    const headers = { Authorization: 'Bearer ' + paired.deviceToken, 'Content-Type': 'application/json' };
    assert.equal((await fetch(`http://127.0.0.1:${port}/v1/notification-settings`, { method: 'PUT', headers, body: JSON.stringify({ expectedRevision: 0, enabled: true, projects: [], chats: [] }) })).status, 200);
    const head = await (await fetch(`http://127.0.0.1:${port}/v1/notification-events`, { headers })).json();
    h.row.sessionId = 'pc-new-chat'; h.frame.header.id = 'pc-new-chat';
    const started = { type: 'event', event: { seq: 8, time: Date.now() + 0, type: 'turn/start', data: { turn: 2 } } };
    const answer = { type: 'event', event: { seq: 9, time: Date.now() + 1, type: 'assistant/message', surfaceOp: 'append', data: { turn: 2, step: 1, message: { id: 'pc-answer', role: 'assistant', content: [{ type: 'text', text: 'Synthetic final answer' }] } } } };
    const end = { type: 'event', event: { seq: 10, time: Date.now() + 2, type: 'turn/end', data: { turn: 2, reason: { kind: 'completed' } } } };
    (h.frame.records as any[]).push(started, answer, end); h.frame.cursor = 10;
    ctx.emit('session/event', { id: 'pc-new-chat' }, started.event);
    ctx.emit('session/event', { id: 'pc-new-chat' }, answer.event);
    ctx.emit('session/event', { id: 'pc-new-chat' }, end.event);
    const deadline = Date.now() + 3000;
    let page: any;
    do { page = await (await fetch(`http://127.0.0.1:${port}/v1/notification-events?after=${head.nextCursor}`, { headers })).json(); if (page.items?.length) break; await new Promise(r => setTimeout(r, 10)); } while (Date.now() < deadline);
    assert.equal(page.items?.[0]?.sessionId, 'pc-new-chat', 'global bridge observes a newly created PC session without phone observation');
    assert.equal(page.items?.[0]?.kind, 'answer-finished');
  } finally { admin.close(); }
  await fiber.dispose();
  await assert.rejects(fetch(`http://127.0.0.1:${port}/v1/capabilities`));
  assert.deepEqual(h.cleanup(), { closed: 1, resumed: 0 }, 'idle baseline is lazy; terminal proof closes one opening without activation');
  assert.deepEqual(h.calls(), { created: undefined, prompted: undefined, cancelled: undefined });
  // A dispose racing async realpath/server startup must await cleanup too.
  const racing = ctx.plugin(plugin, config);
  await racing.dispose();
  await assert.rejects(fetch(`http://127.0.0.1:${port}/v1/capabilities`));
});

test('installed Cordis resolves registry through get without required inject and fails closed after service loss', { skip: !process.env.DSH_MOBILE_CORDIS_MODULE }, async t => {
  const h = await harness(t);
  const { Context, Service } = await import(pathToFileURL(process.env.DSH_MOBILE_CORDIS_MODULE!).href);
  const ctx = new Context();
  ctx.provide('sessionController', h.controller);
  ctx.provide('agentPresets', { async remoteExportList() { return { presets: [] }; } });
  const reserve = createServer(); await new Promise<void>(resolve => reserve.listen(0, '127.0.0.1', resolve));
  const port = (reserve.address() as { port: number }).port; await new Promise<void>(resolve => reserve.close(() => resolve()));
  const config = { dshVersion: '0.2.0-rc.2', hostName: 'Synthetic registry composition', bind: '127.0.0.1', port,
    statePath: join(h.dir, 'registry-plugin.sqlite'), workspaceSource: 'dsh-registry' as const, allowInsecureLoopback: true };
  await assert.rejects(plugin.apply(ctx, config), { code: 'workspace_registry_unavailable' });
  // Real DSH registers a Cordis Service, not a plain object: each ctx.get()
  // returns a fresh tracing proxy even while its provider is unchanged.
  class Registry extends Service {
    constructor(context: any) { super(context, 'workspaceRegistry'); }
    list() { return [{ id: 'alpha', path: h.alpha, title: 'Alpha (demo)', async status() { return 'ok'; } }]; }
    get archivedSessionIds() { return []; }
  }
  const registryFiber = ctx.plugin(Registry); await registryFiber;
  const fiber = ctx.plugin(plugin, config);
  h.closeBeforeCleanup(async () => { await fiber.dispose(); await ctx.fiber.dispose(); });
  await fiber;
  const { HostState } = await import('../src/state.ts');
  const state = new HostState(config.statePath);
  const offer = state.createPairing({ readWorkspaceIds: ['*'], executeWorkspaceIds: ['*'] }); state.close();
  const paired = await (await fetch(`http://127.0.0.1:${port}/v1/pairings`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ pairingToken: offer.pairingToken, deviceName: 'Synthetic phone' }) })).json();
  const headers = { authorization: `Bearer ${paired.deviceToken}` };
  assert.deepEqual(await (await fetch(`http://127.0.0.1:${port}/v1/workspaces`, { headers })).json(), { items: [{ id: 'alpha', name: 'Alpha (demo)', canExecute: true }] });
  assert.equal((await fetch(`http://127.0.0.1:${port}/v1/sessions`, { headers })).status, 200);
  await registryFiber.dispose();
  const response = await fetch(`http://127.0.0.1:${port}/v1/workspaces`, { headers });
  assert.equal(response.status, 503);
  assert.equal((await response.json()).error.code, 'workspace_registry_unavailable');
});

test('installed Cordis tracing proxies keep registry revisions current but provider replacement during I/O fails closed', { skip: !process.env.DSH_MOBILE_CORDIS_MODULE }, async t => {
  const h = await harness(t);
  const { Context, Service } = await import(pathToFileURL(process.env.DSH_MOBILE_CORDIS_MODULE!).href);
  const ctx = new Context();
  h.closeBeforeCleanup(() => ctx.fiber.dispose());
  let status = async () => 'ok' as const;
  class Registry extends Service {
    constructor(context: any) { super(context, 'workspaceRegistry'); }
    list() { return [{ id: 'alpha', path: h.alpha, title: 'Synthetic alpha', status: () => status() }]; }
    get archivedSessionIds() { return []; }
  }
  let provider = ctx.plugin(Registry); await provider;
  const source = await createWorkspaceSource({ workspaceSource: 'dsh-registry' }, () => ctx.get('workspaceRegistry'));
  const before = await source.list();
  assert.equal(source.isCurrent!(before), true);
  assert.equal(source.isCurrent!(before), true, 'Repeated lookup proxy is not a new provider');
  const entered = Promise.withResolvers<void>(), release = Promise.withResolvers<void>();
  status = async () => { entered.resolve(); await release.promise; return 'ok'; };
  const pending = source.list();
  const rejected = assert.rejects(pending, { code: 'workspace_registry_unavailable' });
  await entered.promise;
  await provider.dispose(); provider = ctx.plugin(Registry); await provider;
  release.resolve(); await rejected;
  assert.equal(source.isCurrent!(before), false, 'Actual new Service instance invalidates the old view');
  status = async () => 'ok';
  const after = await source.list();
  assert.deepEqual(after, before);
  assert.equal(source.isCurrent!(after), true);
});

test('a required future event fails closed while ignorable extension data stays private', async (t) => {
  const h = await harness(t);
  const record = { type: 'event', event: { seq: 8, time: 1700000000008, type: 'future/rewrite', data: { private: 'PRIVATE EXTENSION DATA' }, ignorable: true } };
  h.frame.records.push(record as any); h.frame.cursor = 8;
  assert.equal((await h.adapter.snapshot('session-fixture', new AbortController().signal)).activity, 'idle');
  delete (record.event as any).ignorable;
  const unknown = await h.adapter.snapshot('session-fixture', new AbortController().signal);
  assert.equal(unknown.activity, 'unknown');
  assert.deepEqual(unknown.messages, []);
  assert.match(unknown.notice!, /unsupported event/);
  assert.equal(JSON.stringify(unknown).includes('PRIVATE'), false);
});

test('known pending questions and approvals show a desktop notice instead of idle', async (t) => {
  const h = await harness(t);
  (h.frame.projections.values.userQuestions.active as unknown[]).push({ callId: 'question-1', state: 'continued', questions: [{ id: 'one', question: 'PRIVATE QUESTION CONTENT' }] });
  let result = await h.adapter.snapshot('session-fixture', new AbortController().signal);
  assert.equal(result.activity, 'waiting');
  assert.match(result.notice!, /desktop/);
  h.frame.projections.values.userQuestions.active = [];
  h.frame.records.push({ type: 'event', event: { seq: 8, time: 1700000000008, type: 'approval/asked', data: { id: 'approval-1', toolName: 'read', reason: 'PRIVATE APPROVAL REASON' } } } as any); h.frame.cursor = 8;
  result = await h.adapter.snapshot('session-fixture', new AbortController().signal);
  assert.equal(result.activity, 'waiting');
  assert.equal(JSON.stringify(result).includes('PRIVATE'), false);
});

test('cancellation rejects stale observations and acknowledges a request without promising a stopped task', async (t) => {
  const h = await harness(t);
  h.row.running = true;
  const signal = new AbortController().signal;
  await assert.rejects(h.adapter.cancel('session-fixture', signal, 6, 'alpha'), { code: 'conflict' });
  assert.equal(h.calls().cancelled, undefined);
  await h.adapter.cancel('session-fixture', signal, 7, 'alpha');
  assert.deepEqual(h.calls().cancelled, { sessionId: 'session-fixture' });
  assert.equal((await h.adapter.snapshot('session-fixture', signal)).session.running, true);
});

test('history is bounded to one hundred visible messages and an empty session accepts cursor minus one', async (t) => {
  const h = await harness(t);
  h.frame.records = []; h.frame.cursor = -1;
  const empty = await h.adapter.snapshot('session-fixture', new AbortController().signal);
  assert.equal(empty.cursor, -1); assert.deepEqual(empty.messages, []);
  for (let index = 0; index < 101; index++) h.frame.records.push({ type: 'event', event: { seq: index, time: 1700000000000 + index, type: 'user/message', surfaceOp: 'append', data: { id: `user-${index}`, role: 'user', source: { kind: 'user' }, content: [{ type: 'text', text: `Message ${index}` }] } } } as any);
  h.frame.cursor = 100;
  const bounded = await h.adapter.snapshot('session-fixture', new AbortController().signal);
  assert.equal(bounded.messages.length, 100); assert.equal(bounded.messages[0]?.text, 'Message 1'); assert.equal(bounded.hasMore, true);
});

test('polling-only watch reconnects to a full cold-safe replacement and finishes promptly on abort', async (t) => {
  const h = await harness(t);
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: h.controller, workspaces: [{ id: 'alpha', name: 'Alpha', path: h.alpha }], pollIntervalMs: 50, throttleMs: 5 });
  t.after(async () => { adapter.dispose(); });
  const abort = new AbortController();
  const iterator = adapter.watch('session-fixture', abort.signal)[Symbol.asyncIterator]();
  await iterator.next();
  const update = iterator.next();
  h.frame.records.push(committed as any); h.frame.cursor = 8;
  assert.equal((await update).value.messages.at(-1)?.id, 'assistant-3');
  const pending = iterator.next(); abort.abort();
  assert.equal((await pending).done, true);
  assert.equal(h.cleanup().resumed, 0);
  const reconnect = adapter.watch('session-fixture', new AbortController().signal)[Symbol.asyncIterator]();
  assert.equal((await reconnect.next()).value.messages.at(-1)?.id, 'assistant-3');
  await reconnect.return?.();
});

test('six hundred session listings deduplicate distinct cwd only within each fresh request', async t => {
  const h = await harness(t);
  let resolutions = 0, target = h.alpha;
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: h.controller,
    workspaces: [{ id: 'alpha', name: 'Alpha', path: h.alpha }], realpath: async () => { resolutions++; return target; } });
  t.after(async () => { adapter.dispose(); });
  h.rows(Array.from({ length: 600 }, (_, index) => ({ ...h.row, sessionId: `synthetic-${index}`, cwd: join(h.alpha, `alias-${index % 5}`), updatedAt: index })));
  const signal = new AbortController().signal;
  const first = await adapter.listSessions(signal);
  assert.equal(first.length, 600); assert.equal(first[0]?.id, 'synthetic-599');
  assert.equal(resolutions, 5, '600 sessions across five cwd bindings need five resolutions per list');
  assert.equal((await adapter.listSessions(signal)).length, 600);
  assert.equal(resolutions, 10, 'another list must resolve every distinct cwd anew');
  target = h.dir;
  assert.deepEqual(await adapter.listSessions(signal), []);
  await assert.rejects(adapter.prompt('synthetic-0', 'No stale admission', requestId, signal, 'alpha'), { code: 'not_found' });
  assert.equal(h.calls().prompted, undefined);
  target = h.alpha;
  assert.equal((await adapter.listSessions(signal)).length, 600);
});

test('request-local cwd dedup is bounded and retries missing paths on the next list', async t => {
  const h = await harness(t);
  let resolutions = 0, missing = false;
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: h.controller,
    workspaces: [{ id: 'alpha', name: 'Alpha', path: h.alpha }], realpathCacheMaxEntries: 2,
    realpath: async () => { resolutions++; if (missing) throw new Error('Synthetic missing path'); return h.alpha; } });
  t.after(async () => { adapter.dispose(); });
  const signal = new AbortController().signal;
  h.rows([0, 1, 2, 0].map(index => ({ ...h.row, cwd: join(h.alpha, `alias-${index}`) })));
  assert.equal((await adapter.listSessions(signal)).length, 4);
  assert.equal(resolutions, 4, 'bounded request map evicts the oldest of three raw cwd entries');
  h.rows([{ ...h.row, cwd: join(h.alpha, 'alias-0') }]); missing = true;
  assert.deepEqual(await adapter.listSessions(signal), []);
  missing = false;
  assert.equal((await adapter.listSessions(signal)).length, 1, 'a prior failed lookup cannot hide a newly valid binding');
});

test('operator-declared unsupported versions are rejected and no preset registry means host default only', async (t) => {
  const h = await harness(t);
  const options = { sessionController: h.controller, workspaces: [{ id: 'alpha', name: 'Alpha', path: h.alpha }] };
  await assert.rejects(createDshAdapter({ ...options, dshVersion: '0.2.0-rc.3' }), { code: 'unsupported_dsh_version', message: 'Supported DSH versions: 0.2.0-rc.2, 0.2.1-alpha.1, 0.2.1-rc.4, 0.2.1-rc.5. Check dsh --version before declaring dshVersion.' });
  const alpha = await createDshAdapter({ ...options, dshVersion: '0.2.1-alpha.1' });
  t.after(async () => { alpha.dispose(); });
  assert.equal(alpha.upstreamVersion, '0.2.1-alpha.1');
  assert.equal((await alpha.listSessions(new AbortController().signal)).length, 1);
  const rc5 = await createDshAdapter({ ...options, dshVersion: '0.2.1-rc.5' });
  t.after(async () => { rc5.dispose(); });
  assert.equal(rc5.upstreamVersion, '0.2.1-rc.5');
  assert.equal((await rc5.listSessions(new AbortController().signal)).length, 1);
  const noPresets = await createDshAdapter({ ...options, dshVersion: '0.2.0-rc.2' });
  t.after(async () => { noPresets.dispose(); });
  assert.deepEqual(await noPresets.listPresets(new AbortController().signal), []);
  await assert.rejects(noPresets.createSession({ workspaceId: 'alpha', presetId: 'unknown', requestId }, new AbortController().signal, 'alpha'), { code: 'invalid_request' });
});

test('multi-project timestamps are relative to one demo start and keep default fixture dates unchanged', async t => {
  const h = await harness(t);
  // Freeze only Date (not timers) and use a Thursday to exercise all screenshot
  // buckets regardless of the host's date, timezone offset or week boundary.
  const startedAt = new Date(2030, 4, 23, 12, 30).getTime();
  t.mock.timers.enable({ apis: ['Date'], now: startedAt });
  const demo = new FixtureAdapter({ multiProject: true, answerDelayMs: 0 }), plain = new FixtureAdapter();
  h.closeBeforeCleanup(async () => { demo.dispose(); plain.dispose(); });
  const signal = new AbortController().signal, sessions = await demo.listSessions(signal);
  const day = (time: number) => { const date = new Date(time); return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`; };
  const yesterday = new Date(startedAt); yesterday.setDate(yesterday.getDate() - 1);
  const twoDaysAgo = new Date(startedAt); twoDaysAgo.setDate(twoDaysAgo.getDate() - 2);
  assert.ok(sessions.some(item => day(item.updatedAt) === day(startedAt)), 'today');
  assert.ok(sessions.some(item => day(item.updatedAt) === day(yesterday.getTime())), 'yesterday');
  assert.ok(sessions.some(item => day(item.updatedAt) === day(twoDaysAgo.getTime())), 'earlier this week');
  assert.ok(sessions.some(item => item.updatedAt < startedAt - 8 * 24 * 3600_000), 'older');
  for (const session of sessions.filter(item => item.running)) {
    assert.equal(day(session.updatedAt), day(startedAt));
    const snapshot = await demo.snapshot(session.id, signal);
    const lastUserAt = snapshot.messages.findLast(item => item.role === 'user')!.createdAt;
    assert.ok(startedAt - lastUserAt >= 2 * 60_000 && startedAt - lastUserAt <= 6 * 60_000, 'running elapsed must stay within 2–6 minutes');
    assert.ok(snapshot.messages.every(item => item.createdAt <= startedAt && item.createdAt >= startedAt - 10 * 60_000));
  }
  t.mock.timers.setTime(startedAt + 2 * 3600_000);
  assert.deepEqual(await demo.listSessions(signal), sessions, 'existing timestamps are anchored once, not regenerated on reads');
  const created = await demo.createSession({ workspaceId: 'demo-fund', requestId }, signal, 'demo-fund');
  assert.equal((await demo.snapshot(created.sessionId, signal)).session.updatedAt, startedAt + 1, 'subsequent multi-project mutations use the same start epoch');
  const defaultSnapshot = await plain.snapshot(FIXTURE.existingSessionId, signal);
  assert.equal(defaultSnapshot.session.updatedAt, 1700000000000);
  assert.deepEqual(defaultSnapshot.messages.map(item => item.createdAt), [1700000000000, 1700000000001]);
  const defaultCreated = await plain.createSession({ workspaceId: FIXTURE.workspaceId, requestId }, signal, FIXTURE.workspaceId);
  assert.equal((await plain.snapshot(defaultCreated.sessionId, signal)).session.updatedAt, 1700000000001);
});

test('multi-project demo is opt-in, provides twelve varied Russian sessions and stable running Markdown screenshot anchors', async t => {
  const h = await harness(t);
  const plain = new FixtureAdapter();
  assert.deepEqual((await plain.listSessions(new AbortController().signal)).map(item => item.id), ['demo-session']); plain.dispose();
  const demo = await runDemo({ stateDirectory: join(h.dir, 'multi-demo'), multiProject: true });
  h.closeBeforeCleanup(() => demo.close());
  assert.deepEqual(demo.invitation.demoFixture, { markdownSessionId: 'demo-fund-plan', markdownAnchor: 'План портала фонда', filterWorkspaceId: 'demo-fund' });
  assert.equal(MULTI_PROJECT_FIXTURE.markdownSessionId, 'demo-fund-plan');
  const signal = new AbortController().signal;
  const sessions = await demo.adapter.listSessions(signal);
  assert.equal(sessions.length, 12); assert.equal(sessions.filter(item => item.running).length, 2);
  assert.equal(new Set(sessions.map(item => item.workspaceId)).size, 5);
  assert.ok(new Set(sessions.map(item => new Date(item.updatedAt).toISOString().slice(0, 10))).size >= 3, 'screenshot sessions need varied calendar dates, not only times within one day');
  assert.ok(sessions.filter(item => item.workspaceId === 'demo-fund').length >= 2);
  const current = await demo.adapter.snapshot('demo-fund-plan', signal);
  assert.equal(current.activity, 'running');
  const markdown = current.messages.filter(item => item.role === 'assistant').map(item => item.text).join('\n');
  for (const literal of ['# План портала фонда', '\n- ', '**', '`', '```kotlin', '\n1. ', '\n\n']) assert.ok(markdown.includes(literal), literal);
  const paired = await (await fetch(`${demo.invitation.baseUrl}/v1/pairings`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ pairingToken: demo.invitation.pairingToken, deviceName: 'Synthetic screenshot phone' }) })).json();
  const response = await fetch(`${demo.invitation.baseUrl}/v1/workspaces`, { headers: { authorization: `Bearer ${paired.deviceToken}` } });
  assert.deepEqual((await response.json()).items.map((item: { name: string }) => item.name), ['Портал фонда (демо)', 'Тендерный радар (демо)', 'Сайт (демо)', 'Мобильное приложение (демо)', 'Администрирование ПК (демо)']);
});

test('demo composition intentionally issues only a local fixture invitation and closes completely', async (t) => {
  const h = await harness(t);
  const demo = await runDemo({ stateDirectory: join(h.dir, 'demo'), port: 0, answerDelayMs: 10 });
  h.closeBeforeCleanup(() => demo.close());
  const baseUrl = demo.invitation.baseUrl;
  assert.equal(new URL(baseUrl).hostname, '127.0.0.1');
  const pairing = await fetch(`${baseUrl}/v1/pairings`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ pairingToken: demo.invitation.pairingToken, deviceName: 'Synthetic phone' }) });
  assert.equal(pairing.status, 201);
  const grant = await pairing.json() as { deviceToken: string };
  const capabilities = await fetch(`${baseUrl}/v1/capabilities`, { headers: { authorization: `Bearer ${grant.deviceToken}` } });
  const value = await capabilities.json() as { upstreamVersion: string; hostName: string };
  assert.equal(value.upstreamVersion, 'fixture'); assert.equal(value.hostName, FIXTURE.hostName);
  await demo.close(); await demo.close();
  await assert.rejects(fetch(`${baseUrl}/v1/capabilities`));
});

test('a replacement crossing a bounded cut cannot leave an obsolete assistant answer on the surface', async (t) => {
  const h = await harness(t);
  const assistant = (id: string, text: string) => ({ turn: 1, step: 1, message: { id, role: 'assistant', source: { kind: 'model', provider: 'fixture', model: 'none' }, content: [{ type: 'text', text }] }, stream: [] });
  h.frame.records = [
    { type: 'event', event: { seq: 1, time: 1700000000001, type: 'assistant/message', surfaceOp: 'append', data: assistant('a', 'Old A') } },
    { type: 'event', event: { seq: 2, time: 1700000000002, type: 'user/message', surfaceOp: 'append', data: { id: 'c', role: 'user', source: { kind: 'user' }, content: [{ type: 'text', text: 'Old C' }] } } },
    { type: 'event', event: { seq: 3, time: 1700000000003, type: 'assistant/message', surfaceOp: { op: 'replace', startSeq: 1, endSeq: 1 }, data: assistant('b', 'Obsolete B') } },
    { type: 'event', event: { seq: 4, time: 1700000000004, type: 'user/message', surfaceOp: { op: 'replace', startSeq: 0, endSeq: 2 }, sourceEventSeqs: [0, 3, 2], data: { id: 'summary', role: 'user', source: { kind: 'fixture-summary' }, content: [{ type: 'text', text: 'Replacement summary' }] } } },
  ] as any;
  h.frame.cursor = 4; h.frame.hasMore = true;
  await assert.rejects(h.adapter.snapshot('session-fixture', new AbortController().signal), { code: 'unavailable' }, 'an empty backwards page cannot resolve the declared older history or publish obsolete answers');
  assert.equal(h.cleanup().resumed, 0);
});

test('the normal committed assistant stream end does not trigger a false gap or cold refresh', async (t) => {
  const h = await harness(t);
  const abort = new AbortController();
  const iterator = h.adapter.watch('session-fixture', abort.signal)[Symbol.asyncIterator]();
  await iterator.next();
  const provisional = iterator.next(); for (const frame of streamFrames) h.emit(frame as DshObservation); await provisional;
  const final = iterator.next();
  h.emit(committed);
  h.emit({ type: 'assistant-stream', frame: { type: 'end', attemptId: 'attempt-fixture', revision: 6, index: 4, outcome: { kind: 'committed', eventType: 'assistant/message', seq: 8 } } });
  const result = (await final).value;
  assert.equal(result.messages.at(-1)?.id, 'assistant-3');
  assert.notEqual(result.activity, 'unknown');
  assert.deepEqual(h.cleanup(), { closed: 1, resumed: 0 });
  abort.abort(); await iterator.next();
});

test('a persistent opening gap refreshes at a bounded polling rate and releases observation on abort', async (t) => {
  const h = await harness(t, 50);
  h.frame.records.splice(4, 1);
  const abort = new AbortController();
  const iterator = h.adapter.watch('session-fixture', abort.signal)[Symbol.asyncIterator]();
  assert.equal((await iterator.next()).value.activity, 'unknown');
  const started = Date.now();
  const pending = iterator.next();
  const stop = setTimeout(() => { abort.abort(); }, 130);
  try { assert.equal((await pending).done, true); }
  finally { clearTimeout(stop); }
  assert.ok(h.cleanup().closed <= 1 + Math.ceil((Date.now() - started) / 50) + 1, 'resync must not spin on an unchanged invalid opening');
  assert.equal(h.cleanup().resumed, 0); assert.equal(h.listeners(), 0);
});

test('H10 failed turn sanitization does not split an astral character at the bound', async t => {
  const h = await harness(t);
  (h.frame.records as any[]).push({ type: 'event', event: { seq: 8, time: 1700000000008, type: 'turn/end', data: { turn: 2, reason: { kind: 'error', error: { code: 'HTTP_400', message: 'x'.repeat(511) + '😀' } } } } });
  h.frame.cursor = 8; h.frame.projections.asOfSeq = 8;
  const snap = await h.adapter.snapshot('session-fixture', new AbortController().signal);
  assert.deepEqual(snap.failure, { code: 'HTTP_400', message: 'x'.repeat(511) }); assert.equal(snap.failure!.message.isWellFormed(), true);
});
