import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, realpathSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:net';
import { DatabaseSync } from 'node:sqlite';
import { createQuestionBridge } from '../src/question-bridge.ts';
import { MemoryQuestionHistory, snapshotSettledQuestions } from '../src/question-history.ts';
import { apply } from '../src/plugin.ts';
import { HostState } from '../src/state.ts';
import { opening, summary } from './fixtures/rc2.ts';

const answers = [{ id: 'q', selected: ['Да'], custom: '\uFEFFОтвет 🦊\r\nстрока\rконец\n\uFEFFe\u0301' }];

// Public plugin lifecycle + HTTP snapshot, with synthetic upstream sessions only.
for (const winner of ['phone', 'desktop', 'skip'] as const) test(`legacy ${winner} answer survives plugin and SQLite restart in the phone snapshot`, async t => {
  const root = realpathSync.native(mkdtempSync(join(tmpdir(), 'legacy restart Кириллица ')));
  const path = join(root, 'state.sqlite');
  const probe = createServer(); await new Promise<void>(resolve => probe.listen(0, '127.0.0.1', resolve));
  const port = (probe.address() as { port: number }).port;
  const listeners = new Map<string, Set<any>>();
  const frame = structuredClone(opening); frame.header.cwd = root;
  const row = { ...structuredClone(summary), cwd: root };
  const controller: any = {
    async list() { return { items: [row] }; }, async *follow() { yield structuredClone(frame); },
    async page() { return {}; }, async projections() { return frame.projections; },
    async resolveAgent() { return { agent: {} }; },
  };
  const ctx: any = { sessionController: controller, userQuestions: { answer() { return false; } },
    on(name: string, fn: any) { const set = listeners.get(name) ?? new Set(); listeners.set(name, set); set.add(fn); return () => set.delete(fn); } };
  const config = { dshVersion: '0.2.1-rc.5', hostName: 'Synthetic', bind: '127.0.0.1', port, allowInsecureLoopback: true,
    statePath: path, workspaces: [{ id: 'w', name: 'Synthetic', path: root }] };
  let dispose: (() => Promise<void>) | undefined;
  t.after(async () => { if (probe.listening) await new Promise<void>(resolve => probe.close(() => resolve())); await dispose?.(); rmSync(root, { recursive: true, force: true }); });
  // Failed bind must dispose the bridge and release the caller-owned state/runtime lock.
  await assert.rejects(apply(ctx, config), { code: 'unavailable' });
  assert.equal(listeners.get('user-questions/request')!.size, 0);
  await new Promise<void>(resolve => probe.close(() => resolve()));
  dispose = await apply(ctx, config);
  let state = new HostState(path);
  const paired = state.consumePairing(state.createPairing({ readWorkspaceIds: ['w'], executeWorkspaceIds: ['w'] }).pairingToken, 'Synthetic phone');
  state.close();
  const snapshot = async () => {
    const response = await fetch(`http://127.0.0.1:${port}/v1/sessions/${row.sessionId}`, { headers: { authorization: `Bearer ${paired.deviceToken}` } });
    assert.equal(response.status, 200); return response.json() as Promise<any>;
  };
  const listener = [...listeners.get('user-questions/request')!][0];
  let desktop!: (value: any) => void;
  const upstream = listener({ agent: { session: { id: row.sessionId } }, questions: [{ id: 'q', question: 'Выбор', options: [{ label: 'Да' }], allowFreeText: true, multiSelect: true }], signal: new AbortController().signal },
    () => new Promise(resolve => { desktop = resolve; }));
  const pending = await snapshot(), callId = pending.pendingQuestions[0].callId;
  const expected = winner === 'skip' ? [{ id: 'q', selected: [] }] : answers;
  if (winner === 'desktop') desktop({ answers: expected, private: 'must-not-persist' });
  else {
    const response = await fetch(`http://127.0.0.1:${port}/v1/sessions/${row.sessionId}/answers`, { method: 'POST', headers: { authorization: `Bearer ${paired.deviceToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ requestId: crypto.randomUUID(), callId, ...(winner === 'skip' ? { skip: true } : { answer: { answers } }) }) });
    assert.equal(response.status, 200);
  }
  assert.deepEqual((await upstream).answers, expected);
  await dispose(); dispose = undefined;
  dispose = await apply(ctx, config);
  const reopened = await snapshot();
  assert.deepEqual(reopened.pendingQuestions, []);
  assert.deepEqual(reopened.resolvedQuestions, [{ callId, outcome: winner === 'skip' ? 'skipped' : 'answered', answers: expected }]);
  assert.doesNotMatch(JSON.stringify(reopened.resolvedQuestions), /must-not-persist/);
});

for (const durable of [false, true]) test(`${durable ? 'SQLite' : 'memory'} legacy answer history has per-session/global caps, stable order, isolation and exact 24h expiry`, t => {
  const root = realpathSync.native(mkdtempSync(join(tmpdir(), 'legacy-bounds-'))), path = join(root, 'state.sqlite');
  let now = 1_800_000_000_000;
  let history = durable ? new HostState(path, { now: () => now }) : new MemoryQuestionHistory(() => now);
  const reopen = () => { if (history instanceof HostState) { history.close(); history = new HostState(path, { now: () => now }); } };
  t.after(() => { if (history instanceof HostState) history.close(); rmSync(root, { recursive: true, force: true }); });
  const put = (session: string, callId: string) => history.rememberSettledQuestion(session, { callId, outcome: 'answered', answers });
  assert.deepEqual(history.settledQuestions('s'), []);
  put('s', 'same'); put('other', 'same');
  assert.equal(history.settledQuestions('s').length, 1);
  const copy = history.settledQuestions('s'); copy[0]!.answers[0]!.custom = 'mutated';
  assert.deepEqual(history.settledQuestions('s')[0]!.answers, answers);
  for (let i = 0; i < 20; i++) put('s', `call-${i}`);
  reopen();
  assert.deepEqual(history.settledQuestions('s').map(q => q.callId), Array.from({ length: 20 }, (_, i) => `call-${i}`));
  assert.deepEqual(history.settledQuestions('other').map(q => q.callId), ['same']);
  put('s', 'call-0'); // Duplicate replaces rather than increasing size; newest completion moves to the end.
  assert.deepEqual(history.settledQuestions('s').map(q => q.callId), [...Array.from({ length: 19 }, (_, i) => `call-${i + 1}`), 'call-0']);
  for (let i = 0; i < 128; i++) put(`global-${i}`, 'same');
  reopen();
  assert.deepEqual(history.settledQuestions('s'), []);
  assert.deepEqual(history.settledQuestions('other'), []);
  assert.equal(Array.from({ length: 128 }, (_, i) => history.settledQuestions(`global-${i}`).length).reduce((a, b) => a + b, 0), 128);
  put('last', 'same'); reopen();
  assert.deepEqual(history.settledQuestions('global-0'), []);
  assert.equal(history.settledQuestions('global-1').length, 1);
  now += 24 * 60 * 60_000 - 1; reopen();
  assert.equal(history.settledQuestions('last').length, 1, 'reopening does not renew expiry');
  now++; reopen();
  assert.deepEqual(history.settledQuestions('last'), []);
  assert.deepEqual(history.settledQuestions('global-127'), []);
  reopen(); assert.deepEqual(history.settledQuestions('last'), []);
});

test('schema 4 state without the additive answer table opens and retains existing state', t => {
  const root = realpathSync.native(mkdtempSync(join(tmpdir(), 'legacy-old-state-'))), path = join(root, 'state.sqlite');
  let state = new HostState(path);
  t.after(() => { state.close(); rmSync(root, { recursive: true, force: true }); });
  const paired = state.consumePairing(state.createPairing({ readWorkspaceIds: ['w'], executeWorkspaceIds: ['w'] }).pairingToken, 'Synthetic phone');
  state.close();
  const db = new DatabaseSync(path);
  db.exec('DROP TABLE legacy_question_answers');
  assert.equal(db.prepare('PRAGMA user_version').get()!.user_version, 4); db.close();
  state = new HostState(path);
  assert.equal(state.authenticate(paired.deviceToken)!.deviceId, paired.deviceId);
  assert.deepEqual(state.settledQuestions('s'), []);
  state.rememberSettledQuestion('s', { callId: 'c', outcome: 'answered', answers });
  state.close(); state = new HostState(path);
  assert.deepEqual(state.settledQuestions('s'), [{ callId: 'c', outcome: 'answered', answers }]);
  const version = new DatabaseSync(path); assert.equal(version.prepare('PRAGMA user_version').get()!.user_version, 4); version.close();
});

test('durable history rejects late settlements after close/abort, and a late desktop cannot replace the phone winner', async t => {
  const root = realpathSync.native(mkdtempSync(join(tmpdir(), 'legacy-interleaving-'))), path = join(root, 'state.sqlite');
  let state = new HostState(path);
  t.after(() => { state.close(); rmSync(root, { recursive: true, force: true }); });
  for (const winner of ['phone', 'close', 'phone-then-close', 'abort', 'error', 'timed'] as const) {
    let listener: any, release!: (value: any) => void;
    const bridge = createQuestionBridge(fn => { listener = fn; return () => {}; }, state);
    const abort = new AbortController();
    const result = listener({ agent: { session: { id: winner } }, questions: [{ id: 'q', question: 'Pick', options: [{ label: 'Да' }], allowFreeText: true, multiSelect: true }], signal: abort.signal,
      ...(winner === 'timed' ? { wait: { callId: 'timed' } } : {}) }, () => winner === 'error' ? Promise.reject(new Error('desktop failed')) : new Promise(resolve => { release = resolve; }));
    const rejected = ['close', 'abort', 'error'].includes(winner) ? assert.rejects(result) : undefined;
    await new Promise(resolve => setImmediate(resolve));
    const callId = bridge.pending(winner)[0]?.callId;
    if (winner === 'phone' || winner === 'phone-then-close' || winner === 'timed') bridge.answer(winner, callId!, { answers });
    if (winner === 'close' || winner === 'phone-then-close') bridge.close();
    if (winner === 'abort') abort.abort();
    if (rejected) await rejected; else assert.deepEqual(await result, { answers });
    bridge.close();
    state.close(); state = new HostState(path); // Late callback must never use the old closed database.
    release?.({ answers: [{ id: 'q', selected: [] }] });
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(state.settledQuestions(winner), winner === 'phone' ? [{ callId, outcome: 'answered', answers }] : []);
  }
});

test('four maximum desktop legacy completions cannot poison HTTP snapshots after SQLite reopen (review F1)', async t => {
  const root = realpathSync.native(mkdtempSync(join(tmpdir(), 'legacy-byte-restart-'))), path = join(root, 'state.sqlite');
  const frame = structuredClone(opening); frame.header.cwd = root;
  const row = { ...structuredClone(summary), cwd: root };
  const probe = createServer(); await new Promise<void>(resolve => probe.listen(0, '127.0.0.1', resolve));
  const port = (probe.address() as { port: number }).port; await new Promise<void>(resolve => probe.close(() => resolve()));
  let listener: any, dispose: (() => Promise<void>) | undefined;
  const ctx: any = { sessionController: {
    async list() { return { items: [row] }; }, async *follow() { yield structuredClone(frame); },
    async page() { return {}; }, async projections() { return frame.projections; }, async resolveAgent() { return { agent: {} }; },
  }, userQuestions: { answer() { return false; } }, on(name: string, fn: any) { if (name === 'user-questions/request') listener = fn; return () => {}; } };
  const config = { dshVersion: '0.2.1-rc.5', hostName: 'Synthetic', bind: '127.0.0.1', port, allowInsecureLoopback: true,
    statePath: path, workspaces: [{ id: 'w', name: 'Synthetic', path: root }] };
  t.after(async () => { await dispose?.(); rmSync(root, { recursive: true, force: true }); });
  dispose = await apply(ctx, config);
  let state = new HostState(path);
  const paired = state.consumePairing(state.createPairing({ readWorkspaceIds: ['w'], executeWorkspaceIds: [] }).pairingToken, 'Synthetic phone'); state.close();
  const largeAnswers = Array.from({ length: 20 }, (_, i) => ({ id: `q${i}`, selected: [], custom: 'a'.repeat(32768) }));
  for (let i = 0; i < 4; i++) {
    const result = await listener({ agent: { session: { id: row.sessionId } }, questions: largeAnswers.map(a => ({ id: a.id, question: 'Synthetic' })), signal: new AbortController().signal }, async () => ({ answers: largeAnswers }));
    assert.deepEqual(result, { answers: largeAnswers }, 'retention must not change the selected upstream answer');
  }
  await dispose(); dispose = undefined;
  // The already-oversized database must remain readable without migration or deletion.
  state = new HostState(path); const stored = state.settledQuestions(row.sessionId); state.close();
  assert.equal(stored.length, 4);
  const get = async () => {
    const response = await fetch(`http://127.0.0.1:${port}/v1/sessions/${row.sessionId}`, { headers: { authorization: `Bearer ${paired.deviceToken}` } });
    assert.equal(response.status, 200, 'bounded valid answer history must not make the session unreadable');
    const body = await response.text(); assert.ok(Buffer.byteLength(body) <= 2 * 1024 * 1024); return JSON.parse(body);
  };
  dispose = await apply(ctx, config);
  assert.deepEqual((await get()).resolvedQuestions, stored.slice(-1), 'only the newest complete record fits the 1MiB answer budget');
  await dispose(); dispose = undefined;
  const latest = frame.records.at(-2)!.event as any;
  latest.data.message.content = [{ type: 'text', text: '🦊'.repeat(384 * 1024) }]; // 1.5MiB, one complete message.
  dispose = await apply(ctx, config);
  const snapshot = await get();
  assert.deepEqual(snapshot.resolvedQuestions, [], 'history yields room for the newest complete message and metadata');
  assert.equal(snapshot.messages.at(-1).text, latest.data.message.content[0].text);
  await dispose(); dispose = undefined;
  state = new HostState(path);
  state.rememberSettledQuestion(row.sessionId, { callId: 'new-small', outcome: 'answered', answers });
  state.close();
  dispose = await apply(ctx, config);
  const withSmall = await get();
  assert.deepEqual(withSmall.resolvedQuestions, [{ callId: 'new-small', outcome: 'answered', answers }]);
  assert.equal(withSmall.messages.at(-1).text, latest.data.message.content[0].text);
  const response = await fetch(`http://127.0.0.1:${port}/v1/sessions/${row.sessionId}/events`, { headers: { authorization: `Bearer ${paired.deviceToken}` } });
  assert.equal(response.status, 200);
  const reader = response.body!.getReader();
  let event = ''; const decoder = new TextDecoder();
  try {
    while (!event.includes('\n\n')) { const chunk = await reader.read(); assert.equal(chunk.done, false); event += decoder.decode(chunk.value!, { stream: true }); }
    const data = event.split('\n').find(line => line.startsWith('data: '))!;
    assert.deepEqual(JSON.parse(data.slice(6)).resolvedQuestions, withSmall.resolvedQuestions, 'SSE uses the same bounded projection');
  } finally { await reader.cancel(); }
});

test('settled snapshot byte budget counts JSON escaping and multi-byte UTF-8 at the exact edge', () => {
  // Independent hand-count: []=2, fixed record/answer JSON=84, custom adds 23
  // bytes: quote/backslash/LF/CR/tab/backspace/formfeed=14, Cyrillic=2,
  // astral=4, ASCII=1 and combining mark=2.
  const custom = '"\\\n\r\t\b\fЯ🦊e\u0301';
  const record = { callId: 'c', outcome: 'answered' as const, answers: [{ id: 'q', selected: [], custom }] };
  const serializedBytes = Buffer.byteLength(JSON.stringify([record]), 'utf8');
  assert.equal(serializedBytes, 109);
  assert.deepEqual(snapshotSettledQuestions([record], 109), [record]);
  assert.deepEqual(snapshotSettledQuestions([record], 108), []);
  assert.deepEqual(snapshotSettledQuestions([record], 110), [record]);
  assert.equal(snapshotSettledQuestions([record], 109)[0]!.answers[0]!.custom, custom);
  assert.deepEqual(snapshotSettledQuestions([], 0), []);
  assert.deepEqual(snapshotSettledQuestions([record], -1), []);
  const control = { ...record, answers: [{ id: 'q', selected: [], custom: '\u0001' }] };
  assert.equal(Buffer.byteLength(JSON.stringify([control])), 92, 'six-byte JSON control escape');
  assert.deepEqual(snapshotSettledQuestions([control], 91), []);
  assert.deepEqual(snapshotSettledQuestions([control], 92), [control]);
  const old = { ...record, callId: 'a' }, newer = { ...record, callId: 'b' };
  assert.deepEqual(snapshotSettledQuestions([old, newer], 216), [newer]);
  assert.deepEqual(snapshotSettledQuestions([old, newer], 217), [old, newer], 'array comma is part of the budget');
  const huge = { ...record, callId: 'large', answers: [{ id: 'q', selected: [], custom: 'x'.repeat(1024 * 1024) }] };
  assert.deepEqual(snapshotSettledQuestions([old, huge, newer]), [old, newer], 'skip an unfit complete record, retain newest fitting records');
  assert.equal(snapshotSettledQuestions(Array.from({ length: 21 }, (_, i) => ({ ...record, callId: String(i) }))).length, 20);
  const exact = { ...record, answers: Array.from({ length: 16 }, (_, i) => ({ id: `q${i}`, selected: [], custom: i < 15 ? '"'.repeat(32768) : '' })) };
  const padding = 1024 * 1024 - Buffer.byteLength(JSON.stringify([exact]));
  exact.answers[15]!.custom = '"'.repeat(Math.floor(padding / 2)) + (padding % 2 ? 'a' : '');
  assert.ok(Buffer.byteLength(exact.answers[15]!.custom) <= 32768);
  assert.equal(Buffer.byteLength(JSON.stringify([exact])), 1024 * 1024);
  assert.deepEqual(snapshotSettledQuestions([exact]), [exact]);
  const over = structuredClone(exact); over.answers[15]!.custom += 'a';
  assert.deepEqual(snapshotSettledQuestions([over], 2 * 1024 * 1024), [], '1MiB ceiling is enforced even if caller supplies a larger budget');
});

test('history write failure never changes an upstream answer, and a closed bridge does not touch storage', async () => {
  let listener: any, reads = 0, writes = 0;
  const bridge = createQuestionBridge(fn => { listener = fn; return () => {}; }, {
    rememberSettledQuestion() { writes++; throw new Error('synthetic storage failure'); },
    settledQuestions() { reads++; return []; },
  });
  const result = await listener({ agent: { session: { id: 's' } }, questions: [{ id: 'q', question: 'Pick', options: [{ label: 'Да' }] }], signal: new AbortController().signal }, async () => ({ answers }));
  assert.deepEqual(result, { answers }); assert.equal(writes, 1);
  bridge.close(); assert.deepEqual(bridge.settled('s'), []); assert.equal(reads, 0);
});
