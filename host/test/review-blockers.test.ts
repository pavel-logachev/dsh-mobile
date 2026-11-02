import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, mkdirSync, realpathSync, rmSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { test } from 'node:test';
import { HostState } from '../src/state.ts';
import { startHostServer } from '../src/server.ts';

function fixture(t: any) {
  // Match configuration's realpath canonicalization, including Windows TEMP case.
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'host-review-'))), state = new HostState(join(dir, 'host.sqlite'));
  const device = state.consumePairing(state.createPairing({ readWorkspaceIds: ['w'], executeWorkspaceIds: ['w'] }).pairingToken, 'Synthetic');
  t.after(() => { state.close(); rmSync(dir, { recursive: true, force: true }); });
  return { dir, state, device };
}
function lockAfterCommit(state: HostState, releaseInMicrotask = false) {
  const db = (state as any).db as DatabaseSync, other = new DatabaseSync(state.path), exec = db.exec.bind(db);
  db.exec('PRAGMA busy_timeout=1'); let armed = true;
  db.exec = (sql: string) => { const result = exec(sql); if (armed && sql === 'COMMIT') { armed = false; other.exec('BEGIN IMMEDIATE'); if (releaseInMicrotask) queueMicrotask(() => other.exec('ROLLBACK')); } return result; };
  return () => { db.exec = exec; if (!releaseInMicrotask) other.exec('ROLLBACK'); other.close(); };
}
test('R01 committed admission survives post-commit witness lock and dispatches once', async t => {
  const f = fixture(t); mkdirSync(join(f.dir, 'w')); let calls = 0;
  const adapter = { upstreamVersion: 'synthetic', async listPresets() { return []; }, async listSessions() { return []; }, async snapshot() { throw Error('unused'); }, async *watch() {}, async createSession() { calls++; return { sessionId: 'new' }; }, async prompt() {}, async cancel() {} };
  const host = await startHostServer({ state: f.state, config: { hostName: 'Synthetic', bind: '127.0.0.1', port: 0, statePath: f.state.path, allowInsecureLoopback: true, workspaces: [{ id: 'w', name: 'Synthetic', path: join(f.dir, 'w') }] }, adapter, dispatchTimeoutMs: 30 });
  t.after(() => host.close()); const base = (await host.start()).baseUrl, requestId = randomUUID();
  const send = () => fetch(base + '/v1/sessions', { method: 'POST', headers: { Authorization: 'Bearer ' + f.device.deviceToken, 'Content-Type': 'application/json' }, body: JSON.stringify({ requestId, workspaceId: 'w' }) });
  const restore = lockAfterCommit(f.state, true); let response: Response;
  try { response = await send(); } finally { restore(); }
  assert.equal(response.status, 201); assert.equal((await response.json()).status, 'accepted');
  assert.equal((await (await send()).json()).status, 'accepted'); assert.equal(calls, 1);
});
test('R01 runtime claim witness lock does not poison close or retain OS lock', t => {
  const f = fixture(t), restore = lockAfterCommit(f.state);
  try { assert.doesNotThrow(() => f.state.claimRuntime()); } finally { restore(); }
  assert.doesNotThrow(() => f.state.close());
  const next = new HostState(f.state.path); try { next.claimRuntime(); } finally { next.close(); }
});
test('R01 failed runtime claim closes local lock and permits safe close', t => {
  const f = fixture(t), recover = f.state.recoverUnfinished.bind(f.state);
  f.state.recoverUnfinished = () => { throw Error('synthetic recovery failure'); };
  assert.throws(() => f.state.claimRuntime(), { code: 'conflict' });
  f.state.recoverUnfinished = recover; assert.doesNotThrow(() => f.state.claimRuntime()); assert.doesNotThrow(() => f.state.close());
});
for (const settleBeforeClose of [false, true]) test('R04 failed deadline persistence remains registered for healthy close; settlement ' + (settleBeforeClose ? 'before' : 'after') + ' close', { timeout: 5000 }, async t => {
  const f = fixture(t); mkdirSync(join(f.dir, 'w')); const gate = Promise.withResolvers<void>(), entered = Promise.withResolvers<void>(), deadline = Promise.withResolvers<void>(); let calls = 0;
  const adapter = { upstreamVersion: 'synthetic', async listPresets() { return []; }, async listSessions() { return []; }, async snapshot() { throw Error('unused'); }, async *watch() {}, async createSession() { calls++; entered.resolve(); await gate.promise; return { sessionId: 'late' }; }, async prompt() {}, async cancel() {} };
  const host = await startHostServer({ state: f.state, config: { hostName: 'Synthetic', bind: '127.0.0.1', port: 0, statePath: f.state.path, allowInsecureLoopback: true, workspaces: [{ id: 'w', name: 'Synthetic', path: join(f.dir, 'w') }] }, adapter, dispatchTimeoutMs: 30 });
  t.after(async () => { gate.resolve(); await host.close(); });
  const finish = f.state.finishCommand.bind(f.state); let fail = true;
  f.state.finishCommand = (...args) => { if (fail) { deadline.resolve(); throw Error('synthetic disk unavailable'); } return finish(...args); };
  const requestId = randomUUID(), base = (await host.start()).baseUrl;
  await fetch(base + '/v1/sessions', { method: 'POST', headers: { Authorization: 'Bearer ' + f.device.deviceToken, 'Content-Type': 'application/json' }, body: JSON.stringify({ requestId, workspaceId: 'w' }) });
  await entered.promise; await deadline.promise; await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.state.getCommand(f.device.deviceId, requestId)!.receipt.status, 'pending');
  if (settleBeforeClose) { gate.resolve(); await new Promise(resolve => setImmediate(resolve)); }
  fail = false;
  await host.close();
  const db = new DatabaseSync(f.state.path); try { assert.equal(db.prepare('SELECT status FROM commands WHERE request_id=?').get(requestId)?.status, 'uncertain'); } finally { db.close(); }
  gate.resolve(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.state.getCommand(f.device.deviceId, requestId)!.receipt.status, 'uncertain'); assert.equal(calls, 1);
});
test('R05 release refuses invalid surviving witnesses without changing hold or bytes', t => {
  const f = fixture(t); f.state.setRecoveryHold(); const path = f.state.path + '.ledger.json', original = JSON.parse(readFileSync(path, 'utf8'));
  const invalid = ['', '\ufeff{}', 'null', '[]', '{', JSON.stringify({ ...original, epoch: Number.MAX_SAFE_INTEGER, admittedCount: 'bad' }), JSON.stringify({ ...original, epoch: -1 }), JSON.stringify({ ...original, epoch: 1.5 }), JSON.stringify({ ...original, epoch: '１' })];
  for (const text of invalid) {
    writeFileSync(path, text); assert.throws(() => f.state.releaseRecovery(), { code: 'recovery_hold' });
    assert.equal(readFileSync(path, 'utf8'), text); assert.equal(f.state.ledgerStatus().hold, true); assert.equal(f.state.ledgerStatus().epoch, 1);
  }
  writeFileSync(path, JSON.stringify({ ...original, epoch: Number.MAX_SAFE_INTEGER })); assert.throws(() => f.state.releaseRecovery(), { code: 'recovery_hold' });
  writeFileSync(path, JSON.stringify({ ...original, epoch: 7 })); assert.equal(f.state.releaseRecovery().epoch, 8);
});

test('R01 close releases the runtime lock and main DB even if ownership-row cleanup fails', t => {
  const f = fixture(t); f.state.claimRuntime();
  const db = new DatabaseSync(f.state.path);
  db.exec("CREATE TRIGGER fail_runtime_delete BEFORE DELETE ON runtime_lock BEGIN SELECT RAISE(ABORT,'synthetic runtime cleanup failure'); END");
  try { assert.throws(() => f.state.close(), /synthetic runtime cleanup failure/); }
  finally { db.exec('DROP TRIGGER fail_runtime_delete'); db.close(); }
  assert.throws(() => (f.state as any).db.prepare('SELECT 1'), /database is not open/);
  assert.doesNotThrow(() => f.state.close());
  const next = new HostState(f.state.path); try { next.claimRuntime(); } finally { next.close(); }
});

test('R05 unreadable surviving witness fails loudly; missing witness can rotate beyond DB', t => {
  const f = fixture(t); f.state.setRecoveryHold(); const path = f.state.path + '.ledger.json';
  rmSync(path); mkdirSync(path);
  assert.throws(() => f.state.releaseRecovery(), { code: 'recovery_hold' });
  assert.equal(f.state.ledgerStatus().hold, true); assert.equal(f.state.ledgerStatus().epoch, 1);
  rmSync(path, { recursive: true });
  assert.equal(f.state.releaseRecovery().epoch, 2); assert.equal(f.state.ledgerStatus().hold, false);
});

test('R01 failed post-commit runtime diagnostic clears published ownership before safe close', t => {
  const f = fixture(t);
  // Model an unexpected startup-stage exception even after normal diagnostic IO is best-effort.
  (f.state as any).syncLedgerWitness = () => { throw Error('synthetic startup diagnostic failure'); };
  assert.throws(() => f.state.claimRuntime(), { code: 'conflict' });
  assert.doesNotThrow(() => f.state.close());
  const next = new HostState(f.state.path); try { next.claimRuntime(); } finally { next.close(); }
});
