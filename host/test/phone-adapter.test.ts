import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, mkdir, rm, realpath } from 'node:fs/promises';
import { startHostServer } from '../src/server.ts';
import { randomUUID } from 'node:crypto';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { attachmentRefs, questionsOf } from '../src/phone.ts';
import { createDshAdapter } from '../src/dsh-adapter.ts';
import { opening, summary, requestId } from './fixtures/rc2.ts';
import { legacyModule } from './fixtures/legacy-module.ts';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

// Real upstream services and Cordis tracing/injection, isolated from the host/profile.
test('installed FileUploads seeds empty and legacy receipt budgets through its real Cordis context', { skip: !process.env.DSH_MOBILE_CORDIS_MODULE }, async t => {
  const require = createRequire(process.env.DSH_MOBILE_CORDIS_MODULE!);
  const load = (name: string) => import(pathToFileURL(require.resolve(name)).href);
  const { Context } = await load('@deepseek-ai/cordis');
  const { FileUploads } = await load('@deepseek-ai/dsh-client-file-upload');
  const { SessionStore } = await load('@deepseek-ai/dsh-session');
  const { AgentRegistry } = await load('@deepseek-ai/dsh-agent');
  const { AttachmentStore } = await load('@deepseek-ai/dsh-attachment');
  const { createScope } = await load('@deepseek-ai/dsh-scope');
  for (const prior of [0, 20]) await t.test(prior ? 'twenty actual 0.7.0 uploads survive replacement' : 'fresh service has no prior mobile module and admits files', async t => {
    const ctx = new Context();
    t.after(() => ctx.fiber.dispose());
    // No gateway/server or disk storage: only the two registration seams FileUploads requires.
    ctx.provide('commands', { registerFileReceiptResolver() { return () => {}; } });
    ctx.provide('connection', { fetch: { register() { return () => {}; } } });
    class MemoryAttachments extends AttachmentStore {
      async saveFileStream(request: any) {
        let bytes = 0; for await (const chunk of request.data) bytes += chunk.length;
        return { attachmentId: randomUUID(), name: request.name, bytes };
      }
    }
    for (const plugin of [SessionStore, AgentRegistry, MemoryAttachments, FileUploads]) await ctx.plugin(plugin);
    const sessions = ctx.get('sessions'), agents = ctx.get('agents');
    const session = sessions.create(summary.sessionId);
    const agent: any = { id: session.id, session };
    const scope = createScope(ctx, agent); agent.ctx = scope.ctx;
    const detach = agents.enter(agent);
    t.after(() => { detach(); return scope.dispose(); });
    const service = ctx.get('fileUploads');
    const identity = Reflect.get(service, Symbol.for('cordis.original'));
    assert.ok(identity, 'use actual Cordis tracing service, not a synthetic wrapper');
    assert.equal(Reflect.get(identity, Symbol.for('dsh-mobile.receipt-budget.v1')), undefined);
    // Do not inject sessions into FileUploads: test the installed plugin's own declared inject.
    assert.throws(() => Reflect.get(identity, 'ctx').sessions, /without inject/);
    assert.deepEqual(Reflect.get(identity, 'ctx').get('sessions').list(), [session]);
    const { adapter, row, frame, signal } = await setup(t, { fileUploads: service, resolveAgent: async () => ({ agent }) });
    const input = () => ({ size: 3, mime: 'text/plain', name: 'synthetic.txt', data: (async function* () { yield Buffer.from('abc'); })() });
    if (prior) {
      adapter.dispose();
      const legacy = await legacyModule('dsh-adapter', t);
      const old = await legacy.createDshAdapter({ dshVersion: '0.2.0-rc.2', workspaces: [{ id: 'w', name: 'Synthetic', path: row.cwd }],
        sessionController: { async list() { return { items: [row] }; }, async *follow() { yield frame; }, async page() {}, async projections() {}, async create() {}, async prompt() {}, cancel() {}, async attachment() {} },
        fileUploads: service, attachments: ctx.get('attachments') });
      try { for (let i = 0; i < prior; i++) await old.upload(row.sessionId, input(), signal, 'w'); }
      finally { old.dispose(); }
      // Agent teardown does not retire Session receipts. Enumerating only agents would miss all twenty.
      detach();
      assert.equal(agents.list().length, 0);
      const { adapter: next, row: nextRow, signal: nextSignal } = await setup(t, { fileUploads: ctx.get('fileUploads') });
      await assert.rejects(next.reserveFileUploads(nextRow.sessionId, 1, nextSignal, 'w'), { code: 'rate_limited' });
      const budget = Reflect.get(identity, Symbol.for('dsh-mobile.receipt-budget.v1'));
      assert.equal(budget.size, prior);
      assert.deepEqual(new Set([...budget.values()].map((entry: any) => entry.sessionId)), new Set([session.id]));
      assert.deepEqual(new Set([...budget.values()].map((entry: any) => entry.receiptId)), new Set(identity.stagedFiles.get(session).keys()));
    } else {
      const reservation = await adapter.reserveFileUploads(row.sessionId, 1, signal, 'w'); reservation.release();
      assert.equal(Reflect.get(identity, Symbol.for('dsh-mobile.receipt-budget.v1')).size, 0, 'fresh inventory must not saturate fail-closed sentinels');
      await adapter.upload(row.sessionId, input(), signal, 'w');
      assert.equal(identity.stagedFiles.get(session).size, 1, 'real FileUploads accepted bytes and minted a receipt');
      assert.equal(Reflect.get(identity, Symbol.for('dsh-mobile.receipt-budget.v1')).size, 1);
    }
  });
});

async function setup(t: any, hooks: { pollIntervalMs?: number; beforeFollow?: () => Promise<void>; events?: import('../src/dsh-adapter.ts').DshAdapterOptions['events']; fileUploads?: import('../src/dsh-adapter.ts').DshAdapterOptions['fileUploads']; resolveAgent?: () => Promise<{ agent: unknown }>; attachments?: import('../src/dsh-adapter.ts').DshAdapterOptions['attachments'] } = {}) {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'phone-adapter-'))); await mkdir(join(root, 'w'));
  const frame: any = structuredClone(opening); frame.header.cwd = join(root, 'w');
  const row = { ...structuredClone(summary), cwd: frame.header.cwd, running: true };
  const calls: any[] = [];
  const controller = { async list() { return { items: [row] }; }, async *follow(_r: any, signal: AbortSignal) { try { await hooks.beforeFollow?.(); yield structuredClone(frame); } finally { assert.ok(signal.aborted); } }, async page() { return {}; }, async projections() { return frame.projections; }, async create() { return { sessionId: row.sessionId }; }, async prompt(r: any) { calls.push(r); }, cancel() {}, async rename(r: any) { calls.push(r); }, async updateQueue(r: any) { calls.push(r); }, async selectModel(r: any) { calls.push(r); }, async modelCatalog() { return { default: { provider: 'p', model: 'm' }, groups: [{ id: 'p', models: [{ id: 'm', name: 'Synthetic model' }] }] }; }, async attachment(r: any) { calls.push(r); return { attachment: { attachmentId: 'image', mediaType: 'image/png' as const, bytes: 3 }, data: 'YWJj' }; } };
  let answered: any;
  // Synthetic services start with an inspectable empty upstream inventory.
  if (hooks.fileUploads && !Reflect.get(hooks.fileUploads, Symbol.for('cordis.original'))) Object.assign(hooks.fileUploads, { stagedFiles: new WeakMap(), ctx: { sessions: { list: () => [] } } });
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', events: hooks.events, pollIntervalMs: hooks.pollIntervalMs ?? 60000, workspaces: [{ id: 'w', name: 'Synthetic', path: frame.header.cwd }], sessionController: { ...controller, ...(hooks.resolveAgent ? { resolveAgent: hooks.resolveAgent } : {}) }, modelInfo: async () => ({ inputModalities: ['text', 'image'] }), questionBridge: { pending: () => [], answer: () => false, close() {} }, answerContinued: async (...args) => { answered = args; return true; }, fileUploads: { ...{ stagedFiles: new WeakMap(), ctx: { sessions: { list: () => [] } } }, async uploadStream(r) { let size = 0; for await (const data of r.data) size += data.length; return { receiptId: 'receipt', file: { attachmentId: 'file', name: r.name!, bytes: size } }; } }, ...(hooks.fileUploads ? { fileUploads: hooks.fileUploads } : {}), attachments: { async validateImage(r) { assert.equal(r.mediaType, 'image/png'); }, async *readFileStream(ref) { assert.equal(ref.attachmentId, 'file'); assert.equal(ref.name, 'synthetic.txt'); yield Buffer.from('abc'); } }, ...(hooks.attachments ? { attachments: hooks.attachments } : {}) });
  t.after(async () => { adapter.dispose(); await rm(root, { recursive: true, force: true }); });
  return { adapter, frame, row, calls, answered: () => answered, signal: new AbortController().signal };
}

test('list carries blank, empty localized title and open or continued question status', async t => {
  const { adapter, row, frame, signal } = await setup(t);
  row.blank = true; row.projections = frame.projections; delete row.projections.values.title;
  assert.equal((await adapter.listSessions(signal))[0]!.blank, true);
  assert.equal((await adapter.listSessions(signal))[0]!.title, '');
  for (const state of ['open', 'continued']) {
    frame.projections.values.userQuestions = { active: [{ callId: 'call', state, questions: [{ id: 'q', question: 'Pick', options: [{ label: 'Yes' }] }] }] };
    assert.equal((await adapter.listSessions(signal))[0]!.waitingForAnswer, true);
    assert.equal((await adapter.snapshot(row.sessionId, signal)).session.waitingForAnswer, true);
  }
  frame.projections.values.userQuestions.active = [];
  assert.equal((await adapter.listSessions(signal))[0]!.waitingForAnswer, false);
  assert.equal((await adapter.snapshot(row.sessionId, signal)).session.waitingForAnswer, false);
});

test('watch refresh publishes waiting when a continued question appears and clears it on reply', async t => {
  const { adapter, frame, row } = await setup(t, { pollIntervalMs: 10 });
  const abort = new AbortController(), iterator = adapter.watch(row.sessionId, abort.signal)[Symbol.asyncIterator]();
  try {
    assert.equal((await iterator.next()).value!.session.waitingForAnswer, false);
    frame.projections.values.userQuestions = { active: [{ callId: 'call', state: 'continued', questions: [{ id: 'q', question: 'Pick', options: [{ label: 'Yes' }] }] }] };
    assert.equal((await iterator.next()).value!.session.waitingForAnswer, true);
    frame.projections.values.userQuestions.active = [];
    assert.equal((await iterator.next()).value!.session.waitingForAnswer, false);
  } finally { abort.abort(); await iterator.return?.(); }
});

test('0.7.0 to current upgrade counts upstream staged receipts before admitting files', async t => {
  const legacy = await legacyModule('dsh-adapter', t);
  const agent = { session: { id: summary.sessionId } }, staged = new Map<string, unknown>();
  const original = { stagedFiles: new WeakMap([[agent.session, staged]]), ctx: { sessions: { list: () => [agent.session] } } };
  let serial = 0;
  const service = () => ({ [Symbol.for('cordis.original')]: original, resolve(_a: unknown, id: string) { return staged.has(id) ? {} : undefined; }, async uploadStream(r: any) { for await (const _chunk of r.data) {} const receiptId = 'legacy-' + serial++; staged.set(receiptId, { file: {} }); return { receiptId, file: { attachmentId: 'file', name: 'test.txt', bytes: 3 } }; } });
  const { adapter, row, frame, signal } = await setup(t, { fileUploads: service(), resolveAgent: async () => ({ agent }) });
  const options = () => ({ dshVersion: '0.2.0-rc.2', workspaces: [{ id: 'w', name: 'Synthetic', path: row.cwd }], sessionController: { async list() { return { items: [row] }; }, async *follow() { yield frame; }, async page() {}, async projections() {}, async create() { return { sessionId: row.sessionId }; }, async prompt() {}, cancel() {}, async attachment() {}, async resolveAgent() { return { agent }; } }, fileUploads: service(), attachments: { async validateImage() {} } });
  adapter.dispose();
  const old = await legacy.createDshAdapter(options());
  const input = () => ({ size: 3, mime: 'text/plain', name: 'test.txt', data: (async function* () { yield Buffer.from('abc'); })() });
  for (let i = 0; i < 20; i++) await old.upload(row.sessionId, input(), signal, 'w');
  old.dispose();
  const next = await createDshAdapter(options()); t.after(() => next.dispose());
  await assert.rejects(next.upload(row.sessionId, input(), signal, 'w'), { code: 'rate_limited' });
  assert.equal(staged.size, 20);
  staged.delete('legacy-0');
  await next.upload(row.sessionId, input(), signal, 'w');
  assert.equal(staged.size, 20, 'proven retired legacy receipt releases one slot');
});

test('uninspectable pre-upgrade receipt inventory fails closed without reading upload bytes', async t => {
  let reads = 0;
  const { adapter, row, signal } = await setup(t, { fileUploads: { [Symbol.for('cordis.original')]: {}, async uploadStream() { throw new Error('must not upload'); } } as any });
  await assert.rejects(adapter.upload(row.sessionId, { size: 3, mime: 'text/plain', name: 'test.txt', data: (async function* () { reads++; yield Buffer.from('abc'); })() }, signal, 'w'), { code: 'rate_limited' });
  assert.equal(reads, 0);
});

test('post-ACK bookkeeping failure cannot turn an admitted prompt into a rejection', async t => {
  const { adapter, row, signal } = await setup(t, {
    fileUploads: { resolve() { return {}; }, async uploadStream() { throw new Error('must not upload'); } } as any,
    resolveAgent: async () => { throw new Error('synthetic post-ACK failure'); },
  });
  // Upstream prompt already resolved; receipt retirement failing afterwards is not a rejection.
  await adapter.prompt(row.sessionId, 'Synthetic admitted prompt', requestId, signal, 'w');
});

test('receipt budget survives module reimport and fresh cordis tracing wrappers', async t => {
  const original = { stagedFiles: new WeakMap(), ctx: { sessions: { list: () => [] } } }, live = new Set<string>(); let serial = 0;
  const service = () => ({ [Symbol.for('cordis.original')]: original, resolve(_a: unknown, id: string) { return live.has(id) ? {} : undefined; }, async uploadStream(r: any) { for await (const _chunk of r.data) {} const receiptId = 'reload-' + serial++; live.add(receiptId); return { receiptId, file: { attachmentId: 'file', name: 'test.txt', bytes: 3 } }; } });
  const { adapter, row, frame, signal } = await setup(t, { fileUploads: service(), resolveAgent: async () => ({ agent: {} }) });
  const input = () => ({ size: 3, mime: 'text/plain', name: 'test.txt', data: (async function* () { yield Buffer.from('abc'); })() });
  for (let i = 0; i < 20; i++) await adapter.upload(row.sessionId, input(), signal, 'w');
  adapter.dispose();
  const generation = await import('../src/dsh-adapter.ts?generation=reload-regression');
  const second = await generation.createDshAdapter({ dshVersion: '0.2.0-rc.2', workspaces: [{ id: 'w', name: 'Synthetic', path: row.cwd }], sessionController: { async list() { return { items: [row] }; }, async *follow() { yield frame; }, async page() {}, async projections() {}, async create() { return { sessionId: row.sessionId }; }, async prompt() {}, cancel() {}, async attachment() {}, async resolveAgent() { return { agent: {} }; } }, fileUploads: service(), attachments: { async validateImage() {} } });
  t.after(() => second.dispose());
  await assert.rejects(second.upload(row.sessionId, input(), signal, 'w'), { code: 'rate_limited' }); assert.equal(live.size, 20);
});

test('definitive failures before DSH reads bytes release reservations', async t => {
  let attempts = 0;
  const { adapter, row, signal } = await setup(t, { fileUploads: { async uploadStream() { attempts++; throw new Error('Synthetic pre-storage failure'); } } });
  for (let i = 0; i < 25; i++) await assert.rejects(adapter.upload(row.sessionId, { size: 3, mime: 'text/plain', name: 'test.txt', data: (async function* () { yield Buffer.from('abc'); })() }, signal, 'w'), /pre-storage failure/);
  assert.equal(attempts, 25);
  (await adapter.reserveFileUploads(row.sessionId, 20, signal, 'w')).release();
});

test('declared PDF type survives send projection even without a filename extension', async t => {
  const { adapter, frame, row, signal } = await setup(t);
  await adapter.upload(row.sessionId, { size: 3, name: 'document', mime: 'application/pdf', data: (async function* () { yield Buffer.from('abc'); })() }, signal, 'w');
  frame.records.push({ type: 'event', event: { seq: 8, time: 1700000000008, type: 'user/message', surfaceOp: 'append', data: { id: 'pdf', source: { kind: 'user' }, content: [{ type: 'file', attachment: { attachmentId: 'file', name: 'document', bytes: 3 } }] } } }); frame.cursor = 8;
  assert.equal((await adapter.snapshot(row.sessionId, signal)).messages.at(-1)!.attachments![0]!.mime, 'application/pdf');
});

test('file references recover safe MIME metadata or extension without active content', () => {
  const ref = (name: string, mediaType?: string) => attachmentRefs([{ type: 'file', attachment: { attachmentId: 'f', name, bytes: 3, mediaType } }])[0]!.mime;
  assert.equal(ref('REPORT.PDF'), 'application/pdf'); assert.equal(ref('notes.txt'), 'text/plain');
  assert.equal(ref('no-extension', 'application/pdf'), 'application/pdf');
  for (const [name, mime] of [['index.html', 'text/html'], ['script.js', 'application/javascript'], ['program.exe', 'application/pdf'], ['page.svg', 'image/svg+xml']]) assert.equal(ref(name!, mime), 'application/octet-stream');
});

test('recommended labels have a clean display value and preserve the answer token', () => {
  for (const suffix of [' (Recommended)', '（ recommended ）', ' ( 推荐 ) ', '[ Recommended ]', '［推荐］', '(RECOMMENDED)']) {
    const label = 'Choice' + suffix;
    const option = questionsOf([{ callId: 'call', state: 'open', questions: [{ id: 'q', question: 'Pick', options: [{ label }] }] }])[0]!.questions[0]!.options[0]!;
    assert.equal(option.label, label); assert.equal(option.displayLabel, 'Choice'); assert.equal(option.recommended, true);
  }
  const option = questionsOf([{ callId: 'call', state: 'open', questions: [{ id: 'q', question: 'Pick', options: [{ label: 'Recommended in prose' }] }] }])[0]!.questions[0]!.options[0]!;
  assert.equal(option.displayLabel, 'Recommended in prose'); assert.equal(option.recommended, false);
});

test('watch does not replay buffered model or delivery events covered by opening cursor', async t => {
  let emit!: (value: any) => void, entered!: () => void, release!: () => void;
  const entry = new Promise<void>(r => { entered = r; }), gate = new Promise<void>(r => { release = r; });
  const { adapter, frame, row } = await setup(t, { beforeFollow: async () => { entered(); await gate; }, events: { subscribe(_id, listener) { emit = listener; return () => {}; } } });
  const oldModel = { seq: 8, time: 1700000000008, type: 'model/selection', data: { provider: 'p', model: 'old' } };
  const oldDelivery = { seq: 9, time: 1700000000009, type: 'agent/inbox/spliced', data: { target: 'next-turn', start: 0, inserted: [{ id: 'delivered', source: { kind: 'user' }, content: [] }] } };
  frame.records.push(...[oldModel, oldDelivery, { ...oldModel, seq: 10, data: { provider: 'p', model: 'new' } }, { ...oldDelivery, seq: 11, data: { ...oldDelivery.data, target: 'next-step' } }, { seq: 12, time: 1700000000012, type: 'user/message', surfaceOp: 'append', data: { id: 'delivered', source: { kind: 'user' }, content: [{ type: 'text', text: 'Synthetic' }] } }].map(event => ({ type: 'event', event })));
  frame.cursor = 12; frame.projections.values.modelSelection = { next: { provider: 'p', model: 'new' } };
  const abort = new AbortController(), iterator = adapter.watch(row.sessionId, abort.signal)[Symbol.asyncIterator]();
  try { const first = iterator.next(); await entry; emit({ type: 'event', event: oldModel }); emit({ type: 'event', event: oldDelivery }); release(); const snapshot = (await first).value!; assert.equal(snapshot.model!.id, 'new'); assert.equal(snapshot.messages.at(-1)!.delivery, 'steer'); }
  finally { release(); abort.abort(); await iterator.return?.(); }
});

test('reopening exposes next model, settled skipped answers and recommendation without guessing defaults', async t => {
  const { adapter, frame, row, signal } = await setup(t);
  frame.projections.values.modelSelection = { lastUsed: { provider: 'old', model: 'previous' }, next: { provider: 'p', model: 'm', reasoningEffort: 'high', private: 'hidden' } };
  frame.projections.values.userQuestions = { active: [{ callId: 'call', state: 'continued', questions: [{ id: 'q', question: 'Pick', options: [{ label: 'Yes (Recommended)' }, { label: 'No' }] }] }], settled: [{ callId: 'done', answers: [{ id: 'old-q', selected: [] }] }] };
  const result = await adapter.snapshot(row.sessionId, signal);
  assert.deepEqual(result.model, { provider: 'p', id: 'm', reasoningEffort: 'high' });
  assert.equal(result.pendingQuestions![0]!.questions[0]!.options[0]!.recommended, true);
  assert.deepEqual(result.resolvedQuestions, [{ callId: 'done', outcome: 'skipped', answers: [{ id: 'old-q', selected: [] }] }]);
  await adapter.mutateSession(row.sessionId, { kind: 'answer', callId: 'call', skip: true }, signal, 'w');
  assert.equal(adapter.phoneCapabilities().questionSkip, true);
  row.projections = frame.projections;
  assert.deepEqual((await adapter.listSessions(signal))[0]!.model, result.model);
  frame.projections.values.modelSelection = { next: null, lastUsed: null };
  assert.equal((await adapter.snapshot(row.sessionId, signal)).model, null);
  assert.doesNotMatch(JSON.stringify(result), /hidden/);
});

test('adapter translates steer, queue, rename, model and continued answer to inspected services', async t => {
  const { adapter, frame, calls, signal, answered, row } = await setup(t);
  assert.equal(adapter.phoneCapabilities().attachments, true);
  await adapter.prompt(row.sessionId, 'Synthetic steer', requestId, signal, 'w', { delivery: 'steer' }); assert.equal(calls.at(-1).mode, 'steer');
  await adapter.mutateSession(row.sessionId, { kind: 'queue', itemId: 'item', action: 'remove' }, signal, 'w'); assert.deepEqual(calls.at(-1).action, { kind: 'remove' });
  await adapter.mutateSession(row.sessionId, { kind: 'queue', itemId: 'item', action: 'steer' }, signal, 'w'); assert.deepEqual(calls.at(-1).action, { kind: 'steer' });
  await adapter.mutateSession(row.sessionId, { kind: 'rename', title: 'Synthetic new title' }, signal, 'w'); assert.equal(calls.at(-1).title, 'Synthetic new title');
  await adapter.mutateSession(row.sessionId, { kind: 'model', provider: 'p', model: 'm' }, signal, 'w'); assert.equal(calls.at(-1).model, 'm');
  assert.equal((await adapter.models(signal)).items[0]!.supportsImages, true);
  await assert.rejects(adapter.mutateSession(row.sessionId, { kind: 'model', provider: 'p', model: 'unknown' }, signal, 'w'), { code: 'invalid_request' });
  frame.projections.values.userQuestions = { active: [{ callId: 'call', state: 'continued', questions: [{ id: 'q', question: 'Synthetic?', options: [{ label: 'Yes' }] }] }], settled: [] };
  const answer = { answers: [{ id: 'q', selected: ['Yes'] }] };
  await adapter.mutateSession(row.sessionId, { kind: 'answer', callId: 'call', answer }, signal, 'w'); assert.deepEqual(answered(), [row.sessionId, 'call', answer]);
  for (const mutation of [{ kind: 'rename', title: 'Denied' }, { kind: 'queue', itemId: 'item', action: 'remove' }, { kind: 'model', provider: 'p', model: 'm' }, { kind: 'answer', callId: 'call', answer }] as const) await assert.rejects(adapter.mutateSession(row.sessionId, mutation, signal, 'wrong'), { code: 'not_found' });
  row.running = false; await assert.rejects(adapter.prompt(row.sessionId, 'Idle steer', requestId, signal, 'w', { delivery: 'steer' }), { code: 'conflict' });
});

test('snapshot exposes safe pending inbox, question schema and attachment-only messages', async t => {
  const { adapter, frame, signal, row } = await setup(t);
  frame.projections.values.inbox = { 'next-turn': [{ id: 'item', source: { kind: 'user', rpcId: requestId }, content: [{ type: 'text', text: 'Queued synthetic' }] }], 'next-step': [] };
  frame.projections.values.userQuestions = { active: [{ callId: 'call', state: 'continued', questions: [{ id: 'q', question: 'Synthetic?', header: 'Heading', options: [{ label: 'A', description: 'Synthetic option' }], multiSelect: true }] }], settled: [] };
  frame.records.push({ type: 'event', event: { seq: 8, time: 1700000000008, type: 'user/message', surfaceOp: 'append', data: { id: 'image-only', source: { kind: 'user' }, content: [{ type: 'image', attachment: { attachmentId: 'image', mediaType: 'image/png', bytes: 3, name: 'synthetic.png', privateField: 'not exported' } }] } } }); frame.cursor = 8;
  const result = await adapter.snapshot(row.sessionId, signal);
  assert.equal(result.pendingQueue![0]!.delivery, 'queue'); assert.equal(result.pendingQueue![0]!.id, 'item');
  assert.equal(result.pendingQuestions![0]!.questions[0]!.allowFreeText, true);
  assert.deepEqual(result.messages.at(-1)!.attachments, [{ id: 'image', name: 'synthetic.png', size: 3, mime: 'image/png', kind: 'image' }]);
  assert.doesNotMatch(JSON.stringify(result), /privateField|not exported/);
});

test('file download reads only journal-referenced files and rejects queue-only or foreign references', async t => {
  const { adapter, frame, row, signal } = await setup(t);
  await assert.rejects(adapter.file(row.sessionId, 'file', signal, 'w'), { code: 'not_found' });
  frame.records.push({ type: 'event', event: { seq: 8, time: 1700000000008, type: 'agent/inbox/spliced', data: { target: 'next-step', start: 0, inserted: [{ id: 'file-only', source: { kind: 'user' }, content: [] }] } } });
  frame.records.push({ type: 'event', event: { seq: 9, time: 1700000000009, type: 'user/message', surfaceOp: 'append', data: { id: 'file-only', source: { kind: 'user' }, content: [{ type: 'file', attachment: { attachmentId: 'file', bytes: 3, name: 'synthetic.txt' } }] } } }); frame.cursor = 9;
  assert.equal((await adapter.snapshot(row.sessionId, signal)).messages.at(-1)!.delivery, 'steer');
  assert.equal(Buffer.from((await adapter.file(row.sessionId, 'file', signal, 'w')).data).toString(), 'abc');
  await assert.rejects(adapter.file(row.sessionId, 'file', signal, 'wrong'), { code: 'not_found' });
  await assert.rejects(adapter.file(row.sessionId, 'unreferenced', signal, 'w'), { code: 'not_found' });
});

test('upstream file receipt budgets cap unconsumed reservations across sessions', async t => {
  const { adapter, row, frame, signal } = await setup(t);
  const input = () => ({ size: 3, name: 'synthetic.txt', mime: 'text/plain', data: (async function* () { yield Buffer.from('abc'); })() });
  for (let session = 0; session < 3; session++) {
    row.sessionId = `receipt-${session}`; frame.header.id = row.sessionId;
    for (let i = 0; i < 20; i++) await adapter.upload(row.sessionId, input(), signal, 'w');
    await assert.rejects(adapter.upload(row.sessionId, input(), signal, 'w'), { code: 'rate_limited' });
  }
  row.sessionId = 'receipt-last'; frame.header.id = row.sessionId;
  for (let i = 0; i < 4; i++) await adapter.upload(row.sessionId, input(), signal, 'w');
  await assert.rejects(adapter.upload(row.sessionId, input(), signal, 'w'), { code: 'rate_limited' });
});

test('retired receipts from other sessions release the host-wide quota without revisiting them', async t => {
  let serial = 0; const live = new Set<string>(), resolved: string[] = [];
  const { adapter, row, frame, signal } = await setup(t, { resolveAgent: async () => { resolved.push(row.sessionId); return { agent: {} }; }, fileUploads: { resolve(_agent, id) { return live.has(id) ? {} : undefined; }, async uploadStream(r) { for await (const _chunk of r.data) {} const receiptId = 'r' + serial++; live.add(receiptId); return { receiptId, file: { attachmentId: 'f', name: 'synthetic.txt', bytes: 3 } }; } } });
  const input = () => ({ size: 3, name: 'synthetic.txt', mime: 'text/plain', data: (async function* () { yield Buffer.from('abc'); })() });
  for (let session = 0; session < 4; session++) { row.sessionId = `s${session}`; frame.header.id = row.sessionId; for (let i = 0; i < (session === 3 ? 4 : 20); i++) await adapter.upload(row.sessionId, input(), signal, 'w'); }
  live.clear(); row.sessionId = 'fresh'; frame.header.id = row.sessionId;
  await adapter.upload(row.sessionId, input(), signal, 'w'); assert.equal(live.size, 1);
});

test('concurrent exact HTTP replay returns its receipt when the last real adapter quota slot was reserved', async t => {
  const { adapter, row, frame, signal, calls } = await setup(t);
  const input = () => ({ size: 3, name: 'synthetic.txt', mime: 'text/plain', data: (async function* () { yield Buffer.from('abc'); })() });
  for (let i = 0; i < 19; i++) await adapter.upload(row.sessionId, input(), signal, 'w');
  const root = dirname(frame.header.cwd), host = await startHostServer({ adapter, config: { bind: '127.0.0.1', port: 0, allowInsecureLoopback: true, hostName: 'Synthetic', statePath: join(root, 'state.sqlite'), workspaces: [{ id: 'w', name: 'Synthetic', path: frame.header.cwd }] } });
  t.after(() => host.close()); const { baseUrl } = await host.start();
  const device = host.state.consumePairing(host.state.createPairing({ readWorkspaceIds: ['w'], executeWorkspaceIds: ['w'] }).pairingToken, 'Synthetic'), headers = { Authorization: 'Bearer ' + device.deviceToken };
  const pick = await fetch(`${baseUrl}/v1/sessions/${row.sessionId}/uploads`, { method: 'POST', headers: { ...headers, 'Content-Type': 'text/plain' }, body: 'abc' }); assert.equal(pick.status, 201); const { uploadId } = await pick.json();
  let releaseFirst!: () => void, releaseSecond!: () => void, enteredFirst!: () => void, enteredSecond!: () => void;
  const firstGate = new Promise<void>(r => { releaseFirst = r; }), secondGate = new Promise<void>(r => { releaseSecond = r; });
  const firstEntry = new Promise<void>(r => { enteredFirst = r; }), secondEntry = new Promise<void>(r => { enteredSecond = r; });
  const reserve = adapter.reserveFileUploads.bind(adapter), prompt = adapter.prompt.bind(adapter); let reservations = 0;
  adapter.reserveFileUploads = async (...args) => { if (++reservations === 1) { const token = await reserve(...args); enteredFirst(); await firstGate; return token; } enteredSecond(); await secondGate; return reserve(...args); };
  adapter.prompt = async (...args) => { await prompt(...args); releaseSecond(); };
  const body = { requestId: randomUUID(), text: 'Synthetic', uploadIds: [uploadId] };
  const send = (payload = body) => fetch(`${baseUrl}/v1/sessions/${row.sessionId}/messages`, { method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
  try { const first = send(); await firstEntry; const second = send(); await secondEntry; releaseFirst(); const results = await Promise.all([first, second]); assert.ok(results.every(r => [200, 202].includes(r.status)), results.map(r => r.status).join(',')); assert.equal(calls.length, 1); assert.equal((await send()).status, 200); assert.equal((await send({ ...body, text: 'conflict' })).status, 409); }
  finally { releaseFirst(); releaseSecond(); await host.close(); }
});

test('batch file quota reservations are atomic and release only unused upload slots', async t => {
  const { adapter, row, signal } = await setup(t);
  const reservation = await adapter.reserveFileUploads(row.sessionId, 20, signal, 'w');
  await assert.rejects(adapter.reserveFileUploads(row.sessionId, 1, signal, 'w'), { code: 'rate_limited' });
  await adapter.upload(row.sessionId, { reservation, size: 3, name: 'synthetic.txt', mime: 'text/plain', data: (async function* () { yield Buffer.from('abc'); })() }, signal, 'w');
  reservation.release(); reservation.release();
  const remaining = await adapter.reserveFileUploads(row.sessionId, 19, signal, 'w');
  await assert.rejects(adapter.reserveFileUploads(row.sessionId, 1, signal, 'w'), { code: 'rate_limited' }); remaining.release();
});

test('proven retired send receipts do not consume orphan receipt lifetime quota', async t => {
  const { adapter, row, signal } = await setup(t, { resolveAgent: async () => ({ agent: {} }), fileUploads: { resolve() { return undefined; }, async uploadStream(r) { for await (const _chunk of r.data) {} return { receiptId: 'receipt', file: { attachmentId: 'f', name: 'synthetic.txt', bytes: 3 } }; } } });
  for (let i = 0; i < 25; i++) {
    const uploaded = await adapter.upload(row.sessionId, { size: 3, name: 'synthetic.txt', mime: 'text/plain', data: (async function* () { yield Buffer.from('abc'); })() }, signal, 'w');
    await adapter.prompt(row.sessionId, 'Synthetic', requestId, signal, 'w', { delivery: 'queue', attachments: [uploaded] });
  }
});

test('dedup ACK cannot release unconsumed file receipt reservations', async t => {
  const { adapter, row, signal } = await setup(t, { resolveAgent: async () => ({ agent: {} }), fileUploads: { resolve() { return {}; }, async uploadStream(r) { for await (const _chunk of r.data) {} return { receiptId: 'receipt', file: { attachmentId: 'f', name: 'synthetic.txt', bytes: 3 } }; } } });
  const input = () => ({ size: 3, name: 'synthetic.txt', mime: 'text/plain', data: (async function* () { yield Buffer.from('abc'); })() });
  for (let i = 0; i < 20; i++) { const uploaded = await adapter.upload(row.sessionId, input(), signal, 'w'); await adapter.prompt(row.sessionId, 'duplicate', requestId, signal, 'w', { attachments: [uploaded] }); }
  await assert.rejects(adapter.upload(row.sessionId, input(), signal, 'w'), { code: 'rate_limited' });
});

test('receipt budget releases only proven retired receipts and retains uncertain failures', async t => {
  let serial = 0, fail = false; const receipts = new Set<string>();
  const fileUploads = { resolve(_agent: unknown, id: string) { return receipts.has(id) ? {} : undefined; }, async uploadStream(r: any) { for await (const _chunk of r.data) {} if (fail) throw new Error('Synthetic uncertain commit'); const receiptId = 'receipt-' + serial++; receipts.add(receiptId); return { receiptId, file: { attachmentId: 'file', name: 'synthetic.txt', bytes: 3 } }; } };
  const { adapter, row, signal } = await setup(t, { fileUploads, resolveAgent: async () => ({ agent: {} }) });
  const input = () => ({ size: 3, name: 'synthetic.txt', mime: 'text/plain', data: (async function* () { yield Buffer.from('abc'); })() });
  for (let i = 0; i < 20; i++) await adapter.upload(row.sessionId, input(), signal, 'w');
  await assert.rejects(adapter.upload(row.sessionId, input(), signal, 'w'), { code: 'rate_limited' });
  receipts.delete('receipt-0'); await adapter.upload(row.sessionId, input(), signal, 'w');
  receipts.delete('receipt-1'); fail = true; await assert.rejects(adapter.upload(row.sessionId, input(), signal, 'w'), /Synthetic uncertain commit/); fail = false;
  await assert.rejects(adapter.upload(row.sessionId, input(), signal, 'w'), { code: 'rate_limited' });
});

test('visible images use verified binary store reads without controller base64 conversion', async t => {
  const data = new Uint8Array([97, 98, 99]); let reads = 0;
  const { adapter, row, frame, signal, calls } = await setup(t, { attachments: { async validateImage() {}, async readImage(ref) { reads++; assert.equal(ref.width, 1); assert.equal(ref.height, 1); return { data }; } } });
  frame.records.push({ type: 'event', event: { seq: 8, time: 1700000000008, type: 'user/message', surfaceOp: 'append', data: { id: 'image-binary', source: { kind: 'user' }, content: [{ type: 'image', attachment: { attachmentId: 'image', mediaType: 'image/png', bytes: 3, width: 1, height: 1, name: 'synthetic.png' } }] } } }); frame.cursor = 8;
  const result = await adapter.image(row.sessionId, 'image', signal, 'w'); assert.equal(result.data, data); assert.equal(reads, 1); assert.equal(calls.length, 0);
});

test('image reads require visible message refs and reject oversized metadata before upstream read', async t => {
  const { adapter, frame, row, signal, calls } = await setup(t);
  const content = [{ type: 'image', attachment: { attachmentId: 'image', mediaType: 'image/png', bytes: 3, name: 'synthetic.png' } }];
  frame.records.push({ type: 'event', event: { seq: 8, time: 1700000000008, type: 'tool/result', surfaceOp: 'append', data: { turn: 1, step: 1, message: { id: 'hidden', role: 'tool', toolCallId: 'hidden-call', content } } } }); frame.cursor = 8;
  frame.projections.values.inbox = { 'next-turn': [{ id: 'queue-only', source: { kind: 'user' }, content }], 'next-step': [] };
  await assert.rejects(adapter.image(row.sessionId, 'image', signal, 'w'), { code: 'not_found' }); assert.equal(calls.length, 0);
  frame.records.push({ type: 'event', event: { seq: 9, time: 1700000000009, type: 'user/message', surfaceOp: 'append', data: { id: 'visible', source: { kind: 'user' }, content } } }); frame.cursor = 9;
  assert.equal(Buffer.from((await adapter.image(row.sessionId, 'image', signal, 'w')).data).toString(), 'abc');
  frame.records.push({ type: 'event', event: { seq: 10, time: 1700000000010, type: 'user/message', surfaceOp: { op: 'replace', startSeq: 9, endSeq: 9 }, data: { id: 'replacement', source: { kind: 'user' }, content: [{ type: 'text', text: 'No attachment' }] } } }); frame.cursor = 10;
  await assert.rejects(adapter.image(row.sessionId, 'image', signal, 'w'), { code: 'not_found' });
  frame.records.push({ type: 'event', event: { seq: 11, time: 1700000000011, type: 'user/message', surfaceOp: 'append', data: { id: 'too-large', source: { kind: 'user' }, content: [{ type: 'image', attachment: { ...content[0]!.attachment, bytes: 20 * 1024 * 1024 + 1 } }] } } }); frame.cursor = 11;
  await assert.rejects(adapter.image(row.sessionId, 'image', signal, 'w'), { code: 'payload_too_large' }); assert.equal(calls.length, 1);
});

test('adapter uploads via plugin service, sends server-owned prompt parts and reads authorized images', async t => {
  const { adapter, signal, row, calls, frame } = await setup(t);
  frame.records.push({ type: 'event', event: { seq: 8, time: 1700000000008, type: 'user/message', surfaceOp: 'append', data: { id: 'visible-image', source: { kind: 'user' }, content: [{ type: 'image', attachment: { attachmentId: 'image', mediaType: 'image/png', bytes: 3, name: 'synthetic.png' } }] } } }); frame.cursor = 8;
  const input = { size: 3, name: 'synthetic.png', mime: 'image/png', data: (async function* () { yield Buffer.from('abc'); })() };
  const uploaded = await adapter.upload(row.sessionId, input, signal, 'w');
  await adapter.prompt(row.sessionId, '', requestId, signal, 'w', { delivery: 'queue', attachments: [uploaded] });
  assert.equal(calls.at(-1).content[1].type, 'image'); assert.equal(calls.at(-1).content[1].data, 'YWJj');
  const file = await adapter.upload(row.sessionId, { ...input, mime: 'text/plain', data: (async function* () { yield Buffer.from('abc'); })() }, signal, 'w');
  await adapter.prompt(row.sessionId, '', requestId, signal, 'w', { delivery: 'queue', attachments: [file] }); assert.deepEqual(calls.at(-1).content[1], { type: 'file', receiptId: 'receipt' });
  assert.equal(Buffer.from((await adapter.image(row.sessionId, 'image', signal, 'w')).data).toString(), 'abc');
  await assert.rejects(adapter.image(row.sessionId, 'image', signal, 'wrong'), { code: 'not_found' });
  row.origin = 'subagent'; await assert.rejects(adapter.upload(row.sessionId, input, signal, 'w'), { code: 'not_found' });
});

test('H04 prompt ACK returns without waiting for held retirement IO', { timeout: 3000 }, async t => {
  const gate = Promise.withResolvers<void>(); let resolves = 0;
  const { adapter, row } = await setup(t, { fileUploads: { resolve() { return {}; }, async uploadStream() { throw new Error('unused'); } } as any, resolveAgent: async () => { resolves++; await gate.promise; return { agent: {} }; } });
  const controller = new AbortController(); let settled = false;
  const sending = adapter.prompt(row.sessionId, 'Synthetic', requestId, controller.signal, 'w').then(() => { settled = true; });
  try { await new Promise(r => setTimeout(r, 30)); controller.abort(); assert.equal(settled, true); assert.equal(resolves, 0, 'retirement is deferred to the next quota reservation'); }
  finally { gate.resolve(); await sending; }
});

test('H04 deferred retirement is abortable and coalesces held diagnostic IO', async t => {
  const gate = Promise.withResolvers<void>(); let resolves = 0;
  const { adapter, row, signal } = await setup(t, { resolveAgent: async () => { resolves++; await gate.promise; return { agent: {} }; }, fileUploads: { resolve() { return {}; }, async uploadStream(r) { for await (const _chunk of r.data) {} return { receiptId: 'r', file: { attachmentId: 'f', name: 'synthetic.txt', bytes: 3 } }; } } });
  const input = { size: 3, name: 'synthetic.txt', mime: 'text/plain', data: (async function* () { yield Buffer.from('abc'); })() };
  await adapter.upload(row.sessionId, input, signal, 'w');
  try {
    for (let i = 0; i < 2; i++) {
      const abort = new AbortController(), pending = adapter.reserveFileUploads(row.sessionId, 1, abort.signal, 'w');
      await new Promise(r => setTimeout(r, 10)); abort.abort(new Error('stop diagnostic'));
      const result = await Promise.race([pending.then(() => 'resolved', () => 'aborted'), new Promise(r => setTimeout(() => r('held'), 40))]);
      assert.equal(result, 'aborted');
    }
    assert.equal(resolves, 1, 'same held public resolution must not create unbounded cleanup calls');
    const flights = (adapter as any).retirementReads;
    assert.equal(flights.size, 1); assert.equal([...flights.values()][0].size, 0, 'aborted observers must not accumulate on a held promise');
    const pending = adapter.reserveFileUploads(row.sessionId, 1, signal, 'w');
    const outcome = pending.then(() => 'resolved', () => 'disposed');
    await new Promise(r => setTimeout(r, 10)); adapter.dispose();
    assert.equal(await outcome, 'disposed'); assert.equal(flights.size, 0);
  } finally { gate.resolve(); }
});
