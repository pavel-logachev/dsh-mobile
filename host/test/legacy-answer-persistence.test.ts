import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, realpathSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:net';
import { DatabaseSync } from 'node:sqlite';
import { createQuestionBridge } from '../src/question-bridge.ts';
import { MemoryQuestionHistory } from '../src/question-history.ts';
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
