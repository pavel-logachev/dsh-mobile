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

test('proved attention coalesces and authoritative resolution clears the same episode', async t => {
  const f = await fixture(t); f.proof.pending = true;
  await f.event(0, 'approval/asked'); await f.event(1, 'tool/call');
  f.proof.pending = false; await f.event(2, 'approval/decided');
  const page = await f.feed.page(f.device.deviceId, f.head);
  assert.deepEqual(page.items.map(e => e.kind), ['attention-needed', 'attention-cleared']);
  assert.equal(page.items[0].attentionId, page.items[1].attentionId);
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
