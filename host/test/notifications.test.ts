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
  assert.equal(f.state.readNotificationState<any>('producer').chat.seq, -1);
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
