import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { HostState } from '../src/state.ts';
import { NotificationFeed } from '../src/notifications.ts';
import type { NotificationSource, NotificationEvidence } from '../src/notifications.ts';

async function fixture(t: any) {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-notifications-'));
  const state = new HostState(join(dir, 'state.sqlite'));
  const device = state.consumePairing(state.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] }).pairingToken, 'Synthetic phone');
  let listener: (id: string, event: NotificationSource) => void = () => {};
  let proof: NotificationEvidence = { sessionId: 'chat', workspaceId: 'alpha', cursor: -1, pending: false, completed: [] };
  let listed: { id: string; workspaceId: string; running?: boolean }[] = [{ id: 'chat', workspaceId: 'alpha' }];
  const source = {
    subscribe(fn: (id: string, event: NotificationSource) => void) { listener = fn; return () => { listener = () => {}; }; },
    async evidence() { return structuredClone(proof); },
    async list() { return listed; },
  };
  const feed = new NotificationFeed(state, source);
  await feed.start();
  feed.putSettings(device.deviceId, { expectedRevision: 0, enabled: true, projects: [], chats: [] });
  const head = (await feed.page(device.deviceId)).nextCursor;
  t.after(async () => { await feed.close(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  async function event(seq: number, type: string, options: Partial<NotificationSource> = {}, id = 'chat') {
    proof.cursor = seq; listener(id, { seq, type, time: Date.now(), ...options }); await feed.idle();
  }
  return { state, feed, device, head, event, proof, source, listed: (rows: typeof listed) => { listed = rows; } };
}

test('irrelevant events do no catalog IO but preserve sequence gaps for the next admitted event', async t => {
  const f = await fixture(t); await f.feed.idle(); let lists = 0;
  const list = f.source.list; f.source.list = async () => { lists++; return list(); };
  await f.event(0, 'turn/start'); await f.event(1, 'assistant/message');
  for (let i = 0; i < 40; i++) await f.event(i, 'assistant/message', {}, 'excluded');
  assert.equal(lists, 0);
  f.proof.completed = [{ turn: 1, sourceSeq: 2 }]; await f.event(2, 'turn/end', { turn: 1, completed: true });
  assert.equal((await f.feed.page(f.device.deviceId, f.head)).items[0]?.kind, 'answer-finished');
  await f.event(4, 'assistant/message'); await f.event(5, 'tool/call');
  assert.equal(f.feed.coverage, 'degraded', 'a gap in skipped types still resets an admitted session');
});

test('disabled and revoked notification devices stay out of the event hot path', async t => {
  const f = await fixture(t); await f.feed.idle();
  const disabled = f.state.consumePairing(f.state.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] }).pairingToken, 'Disabled phone');
  const revoked = f.state.consumePairing(f.state.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] }).pairingToken, 'Revoked phone');
  const journal = (enabled: boolean) => ({ epoch: 'epoch', sequence: 0, settings: { revision: 1, enabled, projects: [], chats: [] }, items: [] });
  f.state.writeNotificationState('device:' + disabled.deviceId, journal(false));
  f.state.writeNotificationState('device:' + revoked.deviceId, journal(true));
  assert.equal(f.state.revokeDevice(revoked.deviceId), true);
  assert.equal(f.state.notificationEnabledDeviceCount(), 1);
  const reads: string[] = [], read = f.state.readNotificationState.bind(f.state);
  f.state.readNotificationState = (id: string) => { reads.push(id); return read(id); };
  f.proof.completed = [{ turn: 1, sourceSeq: 0 }]; await f.event(0, 'turn/end', { turn: 1, completed: true });
  assert.equal(reads.some(id => id === 'device:' + disabled.deviceId || id === 'device:' + revoked.deviceId), false);
  assert.equal((await f.feed.page(f.device.deviceId, f.head)).items[0]?.kind, 'answer-finished');
});

test('a gap among initial irrelevant envelopes suppresses a completion without inventing authority', async t => {
  const f = await fixture(t); await f.feed.idle();
  await f.event(0, 'turn/start'); await f.event(2, 'assistant/message');
  f.proof.completed = [{ turn: 1, sourceSeq: 3 }]; await f.event(3, 'turn/end', { turn: 1, completed: true });
  assert.equal(f.feed.coverage, 'degraded');
  assert.equal((await f.feed.page(f.device.deviceId, f.head)).resetRequired, true);
});

test('bounded irrelevant-session pressure cannot erase a known gap before admission', async t => {
  const f = await fixture(t); await f.feed.idle();
  await f.event(0, 'turn/start'); await f.event(2, 'assistant/message');
  for (let i = 0; i < 10000; i++) await f.event(0, 'assistant/message', {}, 'excluded-' + i);
  f.proof.completed = [{ turn: 1, sourceSeq: 3 }]; await f.event(3, 'turn/end', { turn: 1, completed: true });
  assert.equal(f.feed.coverage, 'degraded');
  assert.equal((await f.feed.page(f.device.deviceId, f.head)).resetRequired, true);
});

test('reconciliation retires skipped envelopes already covered by its initial watermark', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const f = await fixture(t); await f.feed.idle();
  await f.event(0, 'turn/start'); await f.event(1, 'assistant/message');
  t.mock.timers.tick(60000); await f.feed.idle();
  f.proof.completed = [{ turn: 1, sourceSeq: 2 }]; await f.event(2, 'turn/end', { turn: 1, completed: true });
  assert.equal(f.feed.coverage, 'ready');
  assert.equal((await f.feed.page(f.device.deviceId, f.head)).items[0]?.kind, 'answer-finished');
});

test('journal listeners observe only committed events and stop after unsubscribe', async t => {
  const f = await fixture(t); await f.feed.idle();
  let wakes = 0, committed = false;
  const stop = f.feed.onChange(() => { wakes++; committed = !!f.state.readNotificationState<any>('device:' + f.device.deviceId).items.length; });
  f.proof.completed = [{ turn: 1, sourceSeq: 0 }]; await f.event(0, 'turn/end', { turn: 1, completed: true });
  assert.equal(committed, true); assert.ok(wakes > 0); const before = wakes;
  await f.feed.page(f.device.deviceId, f.head); assert.equal(wakes, before, 'read-only page bookkeeping must not create a wake loop');
  stop(); f.proof.pending = true; await f.event(1, 'tool/call'); assert.equal(wakes, before);
});

test('unprovable idle history alone does not create a delivery gap', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const f = await fixture(t); await f.feed.idle(); assert.equal(f.feed.coverage, 'ready');
  f.proof.valid = false;
  for (let pass = 0; pass < 9; pass++) { t.mock.timers.tick(60000); await f.feed.idle(); }
  assert.equal(f.feed.coverage, 'ready', 'no alertable event was involved');
});

test('a running session with unprovable history remains ready without an alertable gap', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const f = await fixture(t); await f.feed.idle(); assert.equal(f.feed.coverage, 'ready');
  f.listed([{ id: 'chat', workspaceId: 'alpha', running: true }]);
  f.proof.valid = false;
  for (let pass = 0; pass < 9; pass++) { t.mock.timers.tick(60000); await f.feed.idle(); }
  assert.equal(f.feed.coverage, 'ready');
});

test('a non-alerting event with invalid history does not degrade coverage', async t => {
  const f = await fixture(t); await f.feed.idle();
  f.proof.valid = false;
  await f.event(0, 'tool/call');
  assert.equal(f.feed.coverage, 'ready');
  assert.equal((await f.feed.page(f.device.deviceId, f.head)).items.length, 0);
  assert.equal(f.state.readNotificationState<any>('producer').chat, undefined);
});

test('a running source with invalid non-alerting history also stays ready', async t => {
  const f = await fixture(t); await f.feed.idle();
  f.listed([{ id: 'chat', workspaceId: 'alpha', running: true }]);
  f.proof.valid = false;
  await f.event(0, 'tool/call');
  assert.equal(f.feed.coverage, 'ready');
});

test('an attention-flagged event with invalid proof stays degraded', async t => {
  const f = await fixture(t); await f.feed.idle();
  f.proof.valid = false;
  await f.event(0, 'tool/call', { attention: true });
  assert.equal(f.feed.coverage, 'degraded');
});

test('continuous invalid non-alerting events do not hide a later exact completion', async t => {
  const f = await fixture(t); await f.feed.idle();
  await f.event(0, 'tool/call');
  f.proof.valid = false; await f.event(1, 'tool/call'); await f.event(2, 'tool/call');
  f.proof.valid = true; f.proof.completed = [{ turn: 1, sourceSeq: 3 }];
  await f.event(3, 'turn/end', { turn: 1, completed: true });
  const page = await f.feed.page(f.device.deviceId, f.head);
  assert.deepEqual(page.items.map(e => e.kind), ['answer-finished']);
  assert.equal(f.feed.coverage, 'ready');
});

test('a live terminal without an exact completed pair remains a sticky gap', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const f = await fixture(t); await f.feed.idle();
  f.proof.valid = false;
  await f.event(0, 'turn/end', { turn: 1, completed: true });
  assert.equal((await f.feed.page(f.device.deviceId, f.head)).items.length, 0);
  for (let pass = 0; pass < 9; pass++) { t.mock.timers.tick(60000); await f.feed.idle(); }
  assert.equal(f.feed.coverage, 'degraded', 'a rejected live event is not forgotten by retry counting');
});

test('coverage recovers from a sequence gap only after a successful authoritative reconcile', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const f = await fixture(t); await f.feed.idle(); assert.equal(f.feed.coverage, 'ready');
  await f.event(0, 'tool/call'); await f.event(2, 'tool/call'); assert.equal(f.feed.coverage, 'degraded');
  t.mock.timers.tick(60000); await f.feed.idle(); assert.equal(f.feed.coverage, 'ready');
});

test('coverage stays degraded across partial and failed recovery', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const f = await fixture(t); await f.feed.idle();
  await f.event(0, 'tool/call'); await f.event(2, 'tool/call');
  f.listed(Array.from({ length: 12 }, (_, i) => ({ id: i ? 'idle-' + i : 'chat', workspaceId: 'alpha' })));
  let broken = true;
  (f.source as any).evidence = async (id: string) => { if (id === 'idle-1' && broken) throw new Error('Synthetic unavailable'); return { ...f.proof, sessionId: id }; };
  t.mock.timers.tick(60000); await f.feed.idle(); assert.equal(f.feed.coverage, 'degraded');
  broken = false;
  t.mock.timers.tick(60000); await f.feed.idle(); assert.equal(f.feed.coverage, 'degraded', 'retry backoff is not a successful proof');
  for (let pass = 0; pass < 4; pass++) { t.mock.timers.tick(60000); await f.feed.idle(); }
  assert.equal(f.feed.coverage, 'ready');
});

test('a new invalid source during reconciliation prevents a false ready publication', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] }); const f = await fixture(t); await f.feed.idle();
  await f.event(0, 'tool/call'); await f.event(2, 'tool/call');
  let release!: () => void, entered!: () => void;
  const gate = new Promise<void>(r => { release = r; }), entry = new Promise<void>(r => { entered = r; });
  f.source.evidence = async () => { entered(); await gate; return structuredClone(f.proof); };
  t.mock.timers.tick(60000); await entry;
  // Bad source identity is rejected synchronously while the good proof is held.
  const incoming = f.event(3, 'tool/call', {}, '../invalid');
  release(); await incoming; assert.equal(f.feed.coverage, 'degraded');
  f.source.evidence = async () => structuredClone(f.proof);
  t.mock.timers.tick(60000); await f.feed.idle(); assert.equal(f.feed.coverage, 'ready');
});

for (const failure of ['excluded', 'not_found', 'invalid', 'budget'] as const) test(`lazy idle recovery retries ${failure} and does not abandon unprobed peers`, async t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const dir = mkdtempSync(join(tmpdir(), 'dsh-lazy-retry-')); const state = new HostState(join(dir, 'state.sqlite'));
  const device = state.consumePairing(state.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] }).pairingToken, 'Synthetic');
  const sessions = Array.from({ length: 10 }, (_, i) => ({ id: 'idle-' + i, workspaceId: 'alpha', running: false }));
  const calls = new Map<string, number>(); let pass = 0;
  const source = {
    subscribe() { return () => {}; },
    async list() { return pass === 1 && failure === 'excluded' ? sessions.slice(1) : sessions; },
    async evidence(id: string) {
      calls.set(id, (calls.get(id) ?? 0) + 1);
      if (id === 'idle-0' && pass === 1 && (failure === 'not_found' || failure === 'budget')) throw new Error(failure);
      return { sessionId: id, workspaceId: 'alpha', cursor: -1, pending: true, valid: !(id === 'idle-0' && pass === 1 && failure === 'invalid'), completed: [] };
    },
  };
  const feed = new NotificationFeed(state, source); t.after(async () => { await feed.close(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  await feed.start(); feed.putSettings(device.deviceId, { expectedRevision: 0, enabled: true, projects: [], chats: [] }); await feed.idle();
  for (pass = 1; pass <= 5; pass++) { t.mock.timers.tick(60000); await feed.idle(); }
  const page = await feed.page(device.deviceId);
  assert.equal(page.pending.length, 10, 'failed, excluded and unprobed IDs must remain recoverable');
  assert.ok((calls.get('idle-0') ?? 0) >= 1); assert.equal(new Set(page.pending.map(p => p.sessionId)).size, 10);
});

test('an exhausted lazy pass retains every unprobed ID for a later fair pass', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const dir = mkdtempSync(join(tmpdir(), 'dsh-lazy-timeout-')); const state = new HostState(join(dir, 'state.sqlite'));
  const device = state.consumePairing(state.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] }).pairingToken, 'Synthetic');
  let first = true;
  const source = { subscribe() { return () => {}; }, async list() { return Array.from({ length: 10 }, (_, i) => ({ id: 'idle-' + i, workspaceId: 'alpha' })); },
    async evidence(id: string, signal: AbortSignal): Promise<NotificationEvidence> {
      if (first) { first = false; return await new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true })); }
      return { sessionId: id, workspaceId: 'alpha', cursor: -1, pending: true, completed: [] };
    } };
  const feed = new NotificationFeed(state, source, 25); t.after(async () => { await feed.close(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  await feed.start(); feed.putSettings(device.deviceId, { expectedRevision: 0, enabled: true, projects: [], chats: [] }); await feed.idle();
  t.mock.timers.tick(60000); await new Promise(r => setTimeout(r, 40)); await feed.idle();
  for (let pass = 0; pass < 5; pass++) { t.mock.timers.tick(60000); await feed.idle(); }
  assert.equal((await feed.page(device.deviceId)).pending.length, 10);
});

test('lazy probes rotate fairly past a persistently broken first session', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const f = await fixture(t); await f.feed.idle();
  f.source.evidence = async () => { throw new Error('synthetic broken session'); };
  f.listed([{ id: 'chat', workspaceId: 'alpha' }, { id: 'new-idle', workspaceId: 'alpha' }]);
  // Reactivation must rebuild from the authoritative list, including new idle IDs.
  f.feed.putSettings(f.device.deviceId, { expectedRevision: 1, enabled: false, projects: [], chats: [] });
  f.feed.putSettings(f.device.deviceId, { expectedRevision: 2, enabled: true, projects: [], chats: [] }); await f.feed.idle();
  const evidence = f.source as unknown as { evidence(id: string): Promise<NotificationEvidence> };
  evidence.evidence = async id => { if (id === 'chat') throw new Error('synthetic broken session'); return { ...f.proof, sessionId: id, pending: true }; };
  for (let pass = 0; pass < 5; pass++) { t.mock.timers.tick(60000); await f.feed.idle(); }
  assert.deepEqual((await f.feed.page(f.device.deviceId)).pending.map(p => p.sessionId), ['new-idle']);
});

test('disable during a held baseline cannot overwrite the next opt-in recovery cut', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] }); const f = await fixture(t); await f.feed.idle();
  let enter!: () => void, release!: () => void;
  const entered = new Promise<void>(r => { enter = r; }); const held = new Promise<void>(r => { release = r; });
  let calls = 0;
  f.source.evidence = async () => { if (++calls === 1) { enter(); await held; return { ...f.proof, pending: true }; } return { ...f.proof, pending: false }; };
  f.listed([{ id: 'chat', workspaceId: 'alpha', running: true }]);
  f.feed.putSettings(f.device.deviceId, { expectedRevision: 1, enabled: false, projects: [], chats: [] });
  f.feed.putSettings(f.device.deviceId, { expectedRevision: 2, enabled: true, projects: [], chats: [] }); await entered;
  f.feed.putSettings(f.device.deviceId, { expectedRevision: 3, enabled: false, projects: [], chats: [] });
  f.feed.putSettings(f.device.deviceId, { expectedRevision: 4, enabled: true, projects: [], chats: [] });
  const head = (await f.feed.page(f.device.deviceId)).nextCursor;
  release(); await f.feed.idle();
  const page = await f.feed.page(f.device.deviceId, head);
  assert.equal(page.resetRequired, false, 'cancelled old baseline must not introduce a false episode/reset');
  assert.equal(page.pending.length, 0); assert.equal(page.coverage, 'ready');
});

test('zero-to-one opt-in rebaselines new pending created while monitoring was off', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] }); const f = await fixture(t); await f.feed.idle();
  t.mock.timers.tick(60000); await f.feed.idle();
  f.feed.putSettings(f.device.deviceId, { expectedRevision: 1, enabled: false, projects: [], chats: [] });
  f.proof.pending = true; f.listed([{ id: 'chat', workspaceId: 'alpha' }, { id: 'during-off', workspaceId: 'alpha' }]);
  const evidence = f.source as unknown as { evidence(id: string): Promise<NotificationEvidence> };
  evidence.evidence = async id => ({ ...f.proof, sessionId: id });
  f.feed.putSettings(f.device.deviceId, { expectedRevision: 2, enabled: true, projects: [], chats: [] });
  assert.equal(f.feed.coverage, 'initializing'); await f.feed.idle();
  for (let pass = 0; pass < 5; pass++) { t.mock.timers.tick(60000); await f.feed.idle(); }
  assert.deepEqual((await f.feed.page(f.device.deviceId)).pending.map(p => p.sessionId).sort(), ['chat', 'during-off']);
});

test('zero enabled devices do no baseline IO; first opt-in initializes in background and leaves idle history lazy', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-notifications-lazy-')); const state = new HostState(join(dir, 'state.sqlite'));
  const device = state.consumePairing(state.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] }).pairingToken, 'Synthetic');
  let lists = 0, opens = 0;
  const source = { subscribe() { return () => {}; }, async list() { lists++; return Array.from({ length: 600 }, (_, i) => ({ id: 'idle-' + i, workspaceId: 'alpha', running: false })); }, async evidence(id: string) { opens++; return { sessionId: id, workspaceId: 'alpha', cursor: 100, pending: false, completed: [] }; } };
  const feed = new NotificationFeed(state, source); t.after(async () => { await feed.close(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  await feed.start(); assert.equal(lists, 0); assert.equal(opens, 0);
  feed.putSettings(device.deviceId, { expectedRevision: 0, enabled: true, projects: [], chats: [] });
  assert.equal(feed.coverage, 'initializing'); await feed.idle(); assert.equal(feed.coverage, 'ready'); assert.equal(lists, 1); assert.equal(opens, 0);
});

test('enabled startup returns before a blocked baseline and its global budget degrades coverage', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-notifications-budget-')); const state = new HostState(join(dir, 'state.sqlite'));
  const device = state.consumePairing(state.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] }).pairingToken, 'Synthetic');
  let entered = false;
  const source = { subscribe() { return () => {}; }, async list() { return [{ id: 'running', workspaceId: 'alpha', running: true }]; }, async evidence(_id: string, signal: AbortSignal) { entered = true; return await new Promise<NotificationEvidence>((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true })); } };
  const seed = new NotificationFeed(state, source); seed.putSettings(device.deviceId, { expectedRevision: 0, enabled: true, projects: [], chats: [] });
  const feed = new NotificationFeed(state, source, 25); t.after(async () => { await feed.close(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  await feed.start(); assert.equal(feed.coverage, 'initializing');
  await new Promise(r => setTimeout(r, 40)); await feed.idle(); assert.equal(entered, true); assert.equal(feed.coverage, 'degraded');
});

test('lazy idle reconciliation recovers pending without consuming a concurrently queued completion', async t => {
  const f = await fixture(t); await f.feed.idle();
  t.mock.timers.enable({ apis: ['setInterval'] });
  await f.feed.close(); const feed = new NotificationFeed(f.state, f.source); t.after(() => feed.close()); await feed.start(); await feed.idle();
  const head = (await feed.page(f.device.deviceId)).nextCursor;
  let entered!: () => void, release!: () => void;
  const reading = new Promise<void>(r => { entered = r; }); const held = new Promise<void>(r => { release = r; });
  f.source.evidence = async () => { entered(); await held; return structuredClone(f.proof); };
  t.mock.timers.tick(60000); await reading;
  f.proof.completed = [{ turn: 1, sourceSeq: 0 }]; f.proof.pending = true;
  const terminal = f.event(0, 'turn/end', { turn: 1, completed: true }); release(); await terminal; await feed.idle();
  const page = await feed.page(f.device.deviceId, head);
  assert.equal(page.items.filter(e => e.kind === 'answer-finished').length, 1);
  assert.equal(feed.coverage, 'ready');
  assert.equal(page.items.filter(e => e.kind === 'attention-needed').length, 1);
});

test('unchanged pending episode survives restart without epoch reset when only sequence moved', async t => {
  const f = await fixture(t); f.proof.pending = true; await f.event(0, 'approval/asked');
  const before = await f.feed.page(f.device.deviceId); await f.feed.close(); f.proof.cursor = 20;
  const resumed = new NotificationFeed(f.state, f.source); t.after(() => resumed.close()); await resumed.start(); await resumed.idle();
  const after = await resumed.page(f.device.deviceId, before.nextCursor); assert.equal(after.resetRequired, false);
  assert.equal(after.epoch, before.epoch);
});

test('excluded subagent events never invalidate an ordinary completion journal', async t => {
  const f = await fixture(t); f.proof.completed = [{ turn: 1, sourceSeq: 0 }]; await f.event(0, 'turn/end', { turn: 1, completed: true });
  f.source.evidence = async () => { throw new (await import('../src/errors.ts')).HostError('not_found'); };
  await f.event(0, 'turn/start', {}, 'subagent'); await f.event(1, 'tool/call', {}, 'subagent'); await f.event(2, 'step/end', {}, 'subagent');
  const page = await f.feed.page(f.device.deviceId, f.head);
  assert.equal(page.resetRequired, false); assert.equal(page.coverage, 'ready'); assert.equal(page.items[0]?.kind, 'answer-finished');
});

test('queued events share evidence through its cut but retain every terminal completion check', async t => {
  const f = await fixture(t); await f.feed.idle();
  let reads = 0; f.proof.cursor = 3; f.proof.completed = [{ turn: 1, sourceSeq: 1 }, { turn: 2, sourceSeq: 3 }];
  f.source.evidence = async () => { reads++; return structuredClone(f.proof); };
  await Promise.all([f.event(0, 'tool/call'), f.event(1, 'turn/end', { turn: 1, completed: true }), f.event(2, 'tool/call'), f.event(3, 'turn/end', { turn: 2, completed: true })]);
  const page = await f.feed.page(f.device.deviceId, f.head);
  assert.deepEqual(page.items.filter(e => e.kind === 'answer-finished').map(e => e.sourceSeq), [1, 3]);
  assert.equal(reads, 1, 'one authoritative cut covers all queued event checks');
});

test('an invalid authoritative cut is coalesced without consuming queued completion evidence', async t => {
  const f = await fixture(t); await f.feed.idle(); let reads = 0;
  f.proof.valid = false; f.proof.completed = [{ turn: 1, sourceSeq: 1 }];
  f.source.evidence = async () => { reads++; return structuredClone(f.proof); };
  await Promise.all([f.event(0, 'tool/call'), f.event(1, 'turn/end', { turn: 1, completed: true }), f.event(2, 'tool/call')]);
  assert.equal(reads, 1); assert.equal(f.feed.coverage, 'degraded'); assert.deepEqual(f.state.readNotificationState('producer'), {});
  assert.deepEqual((await f.feed.page(f.device.deviceId, f.head)).items, []);
});

test('coalesced proof is refreshed when its source scope changes even under the same workspace ID', async t => {
  const f = await fixture(t); await f.feed.idle(); let reads = 0, lists = 0, current = true;
  const source = f.source as typeof f.source & { evidenceCurrent?: (proof: NotificationEvidence) => boolean };
  source.evidenceCurrent = () => current;
  source.list = async () => { if (++lists === 2) current = false; return [{ id: 'chat', workspaceId: 'alpha' }]; };
  source.evidence = async () => { reads++; current = true; return structuredClone(f.proof); };
  await Promise.all([f.event(0, 'tool/call'), f.event(1, 'tool/call')]);
  assert.equal(reads, 2, 'scope revision change invalidates evidence, even with a covering cursor');
});

test('reconciliation does not consume the terminal arriving during its cold read', async t => {
  const f = await fixture(t); f.listed([{ id: 'chat', workspaceId: 'alpha', running: true }]);
  t.mock.timers.enable({ apis: ['setInterval'] });
  // Restart installs the timer under the test clock.
  await f.feed.close(); const feed = new NotificationFeed(f.state, f.source); t.after(() => feed.close()); await feed.start();
  const head = (await feed.page(f.device.deviceId)).nextCursor;
  let entered!: () => void, release!: () => void;
  const reading = new Promise<void>(r => { entered = r; }); const held = new Promise<void>(r => { release = r; });
  f.source.evidence = async () => { entered(); await held; return structuredClone(f.proof); };
  t.mock.timers.tick(60000); await reading;
  f.proof.completed = [{ turn: 1, sourceSeq: 0 }]; const terminal = f.event(0, 'turn/end', { turn: 1, completed: true });
  release(); await terminal; await feed.idle();
  const page = await feed.page(f.device.deviceId, head);
  assert.equal(page.items.filter(e => e.kind === 'answer-finished').length, 1); assert.equal(page.coverage, 'ready');
});

test('restart invalidates changed pending cut in both downtime directions', async t => {
  const f = await fixture(t); f.proof.pending = true; await f.event(0, 'approval/asked');
  const cursor = (await f.feed.page(f.device.deviceId)).nextCursor;
  await f.feed.close(); f.proof.pending = false;
  const resolved = new NotificationFeed(f.state, f.source); await resolved.start(); await resolved.idle();
  const cleared = await resolved.page(f.device.deviceId, cursor); assert.equal(cleared.resetRequired, true); assert.equal(cleared.pending.length, 0);
  await resolved.close(); f.proof.pending = true; f.listed([{ id: 'chat', workspaceId: 'alpha', running: true }]);
  const waiting = new NotificationFeed(f.state, f.source); t.after(() => waiting.close()); await waiting.start(); await waiting.idle();
  const pending = await waiting.page(f.device.deviceId, cleared.nextCursor); assert.equal(pending.resetRequired, true); assert.equal(pending.pending.length, 1);
});

test('restart baselines history and keeps stable cursor/settings without completion storms', async t => {
  const f = await fixture(t); f.proof.completed = [{ turn: 1, sourceSeq: 0 }]; await f.event(0, 'turn/end', { turn: 1, completed: true });
  const cursor = (await f.feed.page(f.device.deviceId, f.head)).nextCursor;
  await f.feed.close(); const restarted = new NotificationFeed(f.state, f.source); t.after(() => restarted.close()); await restarted.start();
  assert.equal((await restarted.page(f.device.deviceId, cursor)).items.length, 0);
  assert.equal(restarted.settings(f.device.deviceId).enabled, true);
});

test('current mapping, policy and retention prune safely, gaps reset instead of inventing completion', async t => {
  const f = await fixture(t); f.proof.pending = true; await f.event(0, 'approval/asked');
  const pending = await f.feed.page(f.device.deviceId); assert.equal(pending.pending.length, 1);
  f.listed([]); const removed = await f.feed.page(f.device.deviceId, pending.nextCursor); assert.equal(removed.resetRequired, true); assert.equal(removed.pending.length, 0);
  f.listed([{ id: 'chat', workspaceId: 'alpha' }]); f.proof.pending = false;
  f.proof.completed = [{ turn: 1, sourceSeq: 2 }]; await f.event(2, 'turn/end', { turn: 1, completed: true });
  assert.equal(f.feed.coverage, 'degraded'); assert.equal((await f.feed.page(f.device.deviceId, f.head)).resetRequired, true);
  const j = f.state.readNotificationState<any>('device:' + f.device.deviceId); j.items = [{ sequence: 0, occurredAt: Date.now() - 8 * 86400000 }];
  f.state.writeNotificationState('device:' + f.device.deviceId, j); await f.feed.page(f.device.deviceId);
  assert.equal(f.state.readNotificationState<any>('device:' + f.device.deviceId).items.length, 0);
  assert.throws(() => f.feed.putSettings(f.device.deviceId, { expectedRevision: 1, enabled: true, projects: Array.from({ length: 101 }, (_, i) => ({ workspaceId: 'p' + i, enabled: true })), chats: [] }), { code: 'invalid_request' });
});

test('rate overflow resets head while preserving authoritative attention recovery', async t => {
  const f = await fixture(t);
  for (let i = 0; i < 31; i++) { f.proof.pending = i % 2 === 0; await f.event(i, i % 2 ? 'approval/decided' : 'approval/asked'); }
  const page = await f.feed.page(f.device.deviceId, f.head); assert.equal(page.resetRequired, true); assert.equal(page.pending.length, 1); assert.equal(page.coverage, 'degraded');
});

test('pruned cursor resets without a stuck hasMore or backfill storm', async t => {
  const f = await fixture(t); f.proof.completed = [{ turn: 1, sourceSeq: 0 }]; await f.event(0, 'turn/end', { turn: 1, completed: true });
  const j = f.state.readNotificationState<any>('device:' + f.device.deviceId); j.items[0].occurredAt = Date.now() - 8 * 86400000; f.state.writeNotificationState('device:' + f.device.deviceId, j);
  const page = await f.feed.page(f.device.deviceId, f.head);
  assert.equal(page.resetRequired, true); assert.equal(page.hasMore, false); assert.equal(page.items.length, 0);
});

test('ambiguous evidence suppresses alerts without advancing the proof watermark', async t => {
  const f = await fixture(t); f.proof.valid = false; f.proof.completed = [{ turn: 1, sourceSeq: 0 }];
  await f.event(0, 'turn/end', { turn: 1, completed: true });
  assert.equal((await f.feed.page(f.device.deviceId, f.head)).items.length, 0);
  assert.equal(f.feed.coverage, 'degraded');
  assert.equal(f.state.readNotificationState<any>('producer').chat, undefined);
});

test('later queued-turn attention does not erase proved completion of previous turn', async t => {
  const f = await fixture(t); f.proof.pending = true; f.proof.completed = [{ turn: 1, sourceSeq: 0 }];
  await f.event(0, 'turn/end', { turn: 1, completed: true });
  const page = await f.feed.page(f.device.deviceId, f.head);
  assert.equal(page.items.filter(e => e.kind === 'answer-finished').length, 1);
});

test('completion requires completed terminal event and a committed answer in that turn; deduplicates source', async t => {
  const f = await fixture(t);
  await f.event(0, 'turn/start'); await f.event(1, 'assistant/message');
  assert.equal((await f.feed.page(f.device.deviceId, f.head)).items.length, 0);
  await f.event(2, 'turn/end', { turn: 1, completed: false });
  await f.event(3, 'turn/end', { turn: 2, completed: true });
  assert.equal((await f.feed.page(f.device.deviceId, f.head)).items.length, 0);
  f.proof.completed = [{ turn: 3, sourceSeq: 4 }];
  await f.event(4, 'turn/end', { turn: 3, completed: true });
  await f.event(4, 'turn/end', { turn: 3, completed: true });
  const page = await f.feed.page(f.device.deviceId, f.head);
  assert.deepEqual(page.items.map(e => e.kind), ['answer-finished']);
  assert.equal(page.items[0].turn, 3);
});

test('invalid evidence cannot clear an attention episode', async t => {
  const f = await fixture(t); f.proof.pending = true;
  await f.event(0, 'approval/asked');
  f.proof.pending = false; f.proof.valid = false;
  await f.event(1, 'tool/result');
  const page = await f.feed.page(f.device.deviceId, f.head);
  assert.deepEqual(page.items.map(e => e.kind), ['attention-needed']);
  assert.equal(f.feed.coverage, 'degraded');
});

test('proved attention coalesces and authoritative resolution clears the same episode', async t => {
  const f = await fixture(t); f.proof.pending = true;
  await f.event(0, 'approval/asked'); await f.event(1, 'tool/call');
  f.proof.pending = false; await f.event(2, 'approval/decided');
  const page = await f.feed.page(f.device.deviceId, f.head);
  assert.deepEqual(page.items.map(e => e.kind), ['attention-needed', 'attention-cleared']);
  assert.equal(page.items[0].attentionId, page.items[1].attentionId);
});

test('reconcile cannot prune a new-session watermark committed during its older catalog read', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const f = await fixture(t); await f.feed.idle();
  const source = f.source as typeof f.source & { lookup?: (id: string) => Promise<{ id: string; workspaceId: string }> };
  source.lookup = async id => ({ id, workspaceId: 'alpha' });
  const entered = Promise.withResolvers<void>(), gate = Promise.withResolvers<void>();
  source.list = async () => { entered.resolve(); await gate.promise; return [{ id: 'chat', workspaceId: 'alpha' }]; };
  t.mock.timers.tick(60000); await entered.promise;
  f.proof.cursor = 0; f.proof.completed = [{ turn: 1, sourceSeq: 0 }]; f.source.evidence = async () => ({ ...f.proof, sessionId: 'new-chat' });
  // event() waits for reconcile as well; observe the commit before releasing it.
  const committed = Promise.withResolvers<void>(); f.feed.onChange(() => committed.resolve());
  const live = f.event(0, 'turn/end', { turn: 1, completed: true }, 'new-chat'); await committed.promise;
  gate.resolve(); await live;
  assert.equal(f.state.readNotificationState<any>('producer')['new-chat'].seq, 0);
});

test('point page does not consume a new-session commit that races its authority cut', async t => {
  const f = await fixture(t); await f.feed.idle();
  const source = f.source as typeof f.source & { lookup?: (id: string) => Promise<{ id: string; workspaceId: string }> };
  let held = false; const entered = Promise.withResolvers<void>(), gate = Promise.withResolvers<void>();
  source.lookup = async id => { if (held && id === 'chat') { entered.resolve(); await gate.promise; } return { id, workspaceId: 'alpha' }; };
  f.proof.completed = [{ turn: 1, sourceSeq: 0 }]; await f.event(0, 'turn/end', { turn: 1, completed: true });
  held = true; const page = f.feed.page(f.device.deviceId, f.head); await entered.promise;
  f.source.evidence = async () => ({ ...f.proof, sessionId: 'new-chat' });
  await f.event(0, 'turn/end', { turn: 1, completed: true }, 'new-chat'); gate.resolve();
  const first = await page;
  assert.deepEqual(first.items.map(e => e.sessionId), ['chat']); assert.equal(first.hasMore, true);
  const second = await f.feed.page(f.device.deviceId, first.nextCursor);
  assert.deepEqual(second.items.map(e => e.sessionId), ['new-chat']);
});

test('disable cancels a held live proof and persists a safe degraded reason across feed instances', async t => {
  const f = await fixture(t); await f.feed.idle();
  f.proof.valid = false; await f.event(0, 'turn/end', { turn: 1, completed: true });
  assert.equal(f.state.readNotificationState<any>('diagnostics').lastReason, 'proof_invalid');
  const entered = Promise.withResolvers<void>(), gate = Promise.withResolvers<void>();
  f.source.evidence = async () => { entered.resolve(); await gate.promise; return { ...f.proof, valid: true, completed: [{ turn: 1, sourceSeq: 1 }] }; };
  const live = f.event(1, 'turn/end', { turn: 1, completed: true }); await entered.promise;
  f.feed.putSettings(f.device.deviceId, { expectedRevision: 1, enabled: false, projects: [], chats: [] }); gate.resolve(); await live;
  assert.equal(f.state.readNotificationState<any>('producer').chat, undefined);
  assert.equal(f.state.readNotificationState<any>('device:' + f.device.deviceId).items.length, 0);
  assert.equal(f.state.readNotificationState<any>('diagnostics').lastReason, 'proof_invalid');
});

test('missing cursor has no completion backlog; narrowed grants hide retained entries; foreign/tampered cursor rejected', async t => {
  const f = await fixture(t); f.proof.completed = [{ turn: 1, sourceSeq: 0 }];
  await f.event(0, 'turn/end', { turn: 1, completed: true });
  assert.equal((await f.feed.page(f.device.deviceId)).items.length, 0);
  const second = f.state.consumePairing(f.state.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] }).pairingToken, 'Other synthetic phone');
  await assert.rejects(f.feed.page(second.deviceId, f.head), { code: 'invalid_request' });
  await assert.rejects(f.feed.page(f.device.deviceId, f.head.slice(0, -2) + 'AA'), { code: 'invalid_request' });
  f.state.replaceDeviceGrants(f.device.deviceId, { readWorkspaceIds: [], executeWorkspaceIds: [] });
  assert.equal((await f.feed.page(f.device.deviceId, f.head)).items.length, 0);
});
