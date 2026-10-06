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

async function harness(t: { after: (fn: () => Promise<void>) => void }, pollIntervalMs = 10000) {
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
    events: { subscribe(_id, listener) { listeners.add(listener); return () => { listeners.delete(listener); }; } },
    pollIntervalMs, throttleMs: 5,
  });
  const cleanupResources: (() => Promise<unknown>)[] = [];
  t.after(async () => { for (const cleanup of cleanupResources.toReversed()) await cleanup(); adapter.dispose(); await rm(dir, { recursive: true, force: true }); });
  return { adapter, controller, frame, row, alpha, dir, closeBeforeCleanup: (close: () => Promise<unknown>) => cleanupResources.push(close), cleanup: () => ({ closed, resumed }),
    rows: (value: typeof rows) => { rows = value; }, calls: () => ({ created, prompted, cancelled }),
    emit: (value: DshObservation) => { for (const listener of listeners) listener(value); }, listeners: () => listeners.size };
}

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
  await fiber.dispose();
  await assert.rejects(fetch(`http://127.0.0.1:${port}/v1/capabilities`));
  assert.deepEqual(h.cleanup(), { closed: 0, resumed: 0 });
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
  await assert.rejects(createDshAdapter({ ...options, dshVersion: '0.2.0-rc.3' }), { code: 'unsupported_dsh_version', message: 'Supported DSH versions: 0.2.0-rc.2, 0.2.1-alpha.1. Check dsh --version before declaring dshVersion.' });
  const alpha = await createDshAdapter({ ...options, dshVersion: '0.2.1-alpha.1' });
  t.after(async () => { alpha.dispose(); });
  assert.equal(alpha.upstreamVersion, '0.2.1-alpha.1');
  assert.equal((await alpha.listSessions(new AbortController().signal)).length, 1);
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
  const current = await h.adapter.snapshot('session-fixture', new AbortController().signal);
  assert.equal(current.activity, 'unknown');
  assert.deepEqual(current.messages, []);
  assert.match(current.notice!, /desktop/);
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
