import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { HostState } from '../src/state.ts';
import { NotificationFeed } from '../src/notifications.ts';
import type { NotificationSource } from '../src/notifications.ts';

async function fixture(t: any, valid = true) {
  const dir = mkdtempSync(join(tmpdir(), 'notification-audit-'));
  const state = new HostState(join(dir, 'state.sqlite'));
  const device = state.consumePairing(state.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] }).pairingToken, 'Synthetic');
  let listener!: (id: string, event: NotificationSource) => void;
  const proof = { sessionId: 'chat', workspaceId: 'alpha', cursor: 0, pending: false, valid, completed: [] as { turn: number; sourceSeq: number }[] };
  const source = { subscribe(fn: typeof listener) { listener = fn; return () => {}; }, async list() { return [{ id: 'chat', workspaceId: 'alpha', running: true }]; }, async evidence() { return structuredClone(proof); } };
  let feed = new NotificationFeed(state, source);
  t.after(async () => { await feed.close(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  async function start() { await feed.start(); feed.putSettings(device.deviceId, { expectedRevision: 0, enabled: true, projects: [], chats: [] }); await feed.idle(); }
  await start();
  return { state, device, proof, source, send(id: string, event: NotificationSource) { listener(id, event); }, get feed() { return feed; }, async restart() { await feed.close(); feed = new NotificationFeed(state, source); await feed.start(); await feed.idle(); }, async emit(seq: number, type = 'assistant/message', id = 'chat') { listener(id, { seq, type, time: Date.now(), ...(type === 'turn/end' ? { completed: true, turn: 2 } : {}) }); await feed.idle(); } };
}

test('H01 cold running invalid baseline never reports ready', async t => {
  const f = await fixture(t, false);
  assert.equal(f.feed.coverage, 'degraded');
  assert.equal((await f.feed.page(f.device.deviceId)).items.length, 0);
});

for (const cut of ['baseline', 'recovery', 'restart']) test(`H02 first ignored envelope preserves gap from ${cut} cut`, async t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const f = await fixture(t);
  if (cut === 'recovery') { f.proof.cursor = 10; t.mock.timers.tick(60000); await f.feed.idle(); }
  if (cut === 'restart') await f.restart();
  const anchor = cut === 'recovery' ? 10 : 0;
  const head = (await f.feed.page(f.device.deviceId)).nextCursor;
  f.proof.cursor = anchor + 3; f.proof.completed = [{ turn: 2, sourceSeq: anchor + 3 }];
  await f.emit(anchor + 2); await f.emit(anchor + 3, 'turn/end');
  assert.equal(f.feed.coverage, 'degraded');
  const page = await f.feed.page(f.device.deviceId, head);
  assert.equal(page.resetRequired, true); assert.equal(page.items.length, 0);
});

test('H08 continuity footprint is bounded while authority list is held', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const f = await fixture(t);
  let release!: () => void, entered!: () => void;
  const gate = new Promise<void>(r => { release = r; }), entry = new Promise<void>(r => { entered = r; });
  f.source.list = async () => { entered(); await gate; return [{ id: 'chat', workspaceId: 'alpha', running: true }]; };
  t.mock.timers.tick(60000); await entry;
  try {
    // Do not await idle: recovery intentionally holds the list, producer lanes still run.
    for (let i = 0; i < 10005; i++) {
      const id = 'excluded-' + i;
      f.send(id, { seq: 0, type: 'assistant/message', time: Date.now() });
      await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    }
    // Memory-bound invariant is observed directly, with public degraded behavior too.
    const internals = f.feed as any;
    for (const name of ['observed', 'gaps', 'skipped', 'deliveryGaps', 'blockingRecovery', 'recoveryPending', 'lazyRetry']) assert.ok(internals[name].size <= 10000, `${name} exceeded continuity capacity`);
    assert.equal(f.feed.coverage, 'degraded');
  } finally { release(); await f.feed.idle(); }
});

test('H08 continuity budget is reusable after authoritative exclusion and remains bounded on repeated pressure', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] }); const f = await fixture(t);
  for (let round = 0; round < 2; round++) {
    for (let i = 0; i < 10005; i++) {
      await f.emit(0, 'assistant/message', 'excluded-' + round + '-' + i);
    }
    assert.equal(f.feed.coverage, 'degraded');
    const internals = f.feed as any;
    for (const name of ['continuityIds', 'observed', 'gaps', 'skipped', 'deliveryGaps', 'blockingRecovery', 'recoveryPending', 'lazyRetry']) assert.ok(internals[name].size <= 10000, name);
    t.mock.timers.tick(60000); await f.feed.idle(); assert.equal(f.feed.coverage, 'ready');
  }
});

test('H02 duplicate ignored envelopes do not rewind the continuity anchor', async t => {
  const f = await fixture(t); const head = (await f.feed.page(f.device.deviceId)).nextCursor;
  await f.emit(1); await f.emit(1);
  f.proof.cursor = 2; f.proof.completed = [{ turn: 2, sourceSeq: 2 }]; await f.emit(2, 'turn/end');
  assert.equal(f.feed.coverage, 'ready'); assert.equal((await f.feed.page(f.device.deviceId, head)).items[0]?.kind, 'answer-finished');
});

test('H08 batch journal saves respect the same device cache bound as reads', async t => {
  const f = await fixture(t), feed = f.feed as any; feed.journalCache.clear();
  for (let i = 0; i < 140; i++) feed.save('synthetic-' + i, { epoch: 'test', sequence: -1, settings: { revision: 0, enabled: false, projects: [], chats: [] }, items: [] });
  assert.equal(feed.journalCache.size, 128);
});
