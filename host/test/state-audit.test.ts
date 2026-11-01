import assert from 'node:assert/strict';
import fs from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { test } from 'node:test';
import { HostState, hashSecret } from '../src/state.ts';

function fixture(t: any) {
  const dir = fs.mkdtempSync(join(tmpdir(), 'state-audit-')), path = join(dir, 'host.sqlite');
  let state = new HostState(path);
  const device = state.consumePairing(state.createPairing({ readWorkspaceIds: ['a'], executeWorkspaceIds: ['a'] }).pairingToken, 'Synthetic');
  t.after(() => { state.close(); fs.rmSync(dir, { recursive: true, force: true }); });
  return { dir, path, device, get state() { return state; }, reopen() { state.close(); state = new HostState(path); return state; } };
}

test('H05 recovery release rotates beyond surviving witness epoch and lost admission', t => {
  const f = fixture(t), backup = join(f.dir, 'old.sqlite');
  f.state.close(); fs.copyFileSync(f.path, backup); f.reopen();
  f.state.setRecoveryHold(); f.state.releaseRecovery();
  const id = randomUUID(); f.state.admitCommand(f.device.deviceId, id, 'prompt', hashSecret('synthetic'), 'a'); f.state.finishCommand(f.device.deviceId, id, 'accepted');
  f.state.close(); fs.copyFileSync(backup, f.path); f.reopen();
  assert.equal(f.state.ledgerStatus().hold, true);
  const released = f.state.releaseRecovery();
  assert.equal(released.epoch, 3); assert.equal(released.witnessProblem, 'none'); assert.equal(released.hold, false);
  assert.equal(f.reopen().ledgerStatus().hold, false);
});

test('H05 failed release witness write fails loudly and leaves durable hold', t => {
  const f = fixture(t); f.state.setRecoveryHold();
  const rename = fs.renameSync;
  fs.renameSync = ((...args: Parameters<typeof fs.renameSync>) => { if (String(args[1]) === f.path + '.ledger.json') throw new Error('synthetic witness write failure'); return rename(...args); }) as typeof fs.renameSync;
  syncBuiltinESMExports();
  try { assert.throws(() => f.state.releaseRecovery(), /synthetic witness write failure/); }
  finally { fs.renameSync = rename; syncBuiltinESMExports(); }
  assert.equal(f.reopen().ledgerStatus().hold, true);
  assert.equal(fs.readdirSync(f.dir).some(p => p.includes('.tmp-')), false);
});

test('H06 admin open rechecks current row before a sticky rollback hold', t => {
  const f = fixture(t), read = fs.readFileSync; let armed = true;
  fs.readFileSync = ((path: any, ...args: any[]) => {
    if (armed && String(path) === f.path + '.ledger.json') { armed = false; f.state.admitCommand(f.device.deviceId, randomUUID(), 'prompt', hashSecret('synthetic'), 'a'); }
    return (read as any)(path, ...args);
  }) as typeof fs.readFileSync;
  syncBuiltinESMExports(); let admin: HostState | undefined;
  try { admin = new HostState(f.path); } finally { fs.readFileSync = read; syncBuiltinESMExports(); admin?.close(); }
  const status = f.state.ledgerStatus(); assert.equal(status.hold, false); assert.equal(status.admittedCount, 1); assert.equal(status.witnessProblem, 'none');
});

test('H06 status compares witness with the same current ledger cut', t => {
  const f = fixture(t), read = fs.readFileSync; let armed = true;
  fs.readFileSync = ((path: any, ...args: any[]) => {
    if (armed && String(path) === f.path + '.ledger.json') { armed = false; f.state.admitCommand(f.device.deviceId, randomUUID(), 'prompt', hashSecret('synthetic'), 'a'); }
    return (read as any)(path, ...args);
  }) as typeof fs.readFileSync;
  syncBuiltinESMExports();
  try { const status = f.state.ledgerStatus(); assert.equal(status.admittedCount, 1); assert.equal(status.witnessProblem, 'none'); }
  finally { fs.readFileSync = read; syncBuiltinESMExports(); }
});

test('H09 enabled membership uses an indexed search despite disabled history', t => {
  const f = fixture(t), db = new DatabaseSync(f.path);
  try {
    db.exec('BEGIN'); const insert = db.prepare('INSERT INTO notification_settings VALUES (?,0,?)');
    for (let i = 0; i < 20000; i++) insert.run('disabled-' + i, '{}'); db.exec('COMMIT');
    const plan = db.prepare('EXPLAIN QUERY PLAN SELECT s.device_id FROM notification_settings s JOIN devices d ON d.id=s.device_id WHERE s.enabled=1 AND d.revoked_at IS NULL').all();
    assert.ok(plan.some(r => /SEARCH s .*notification_settings_enabled/.test(String(r.detail))), JSON.stringify(plan));
    assert.equal(f.state.notificationEnabledDeviceCount(), 0);
  } finally { db.close(); }
});

test('H09 legacy settings backfill runs once and does not deserialize journals on reopen', t => {
  const f = fixture(t); f.state.close();
  const db = new DatabaseSync(f.path);
  // Model a pre-index database, then use a trap to detect any repeated startup scan/upsert.
  db.exec("PRAGMA user_version=4; DELETE FROM notification_settings;");
  if (db.prepare("SELECT name FROM sqlite_master WHERE name='host_migrations'").get()) db.exec('DELETE FROM host_migrations');
  db.prepare('INSERT INTO notification_state VALUES (?,?)').run('device:' + f.device.deviceId, JSON.stringify({ settings: { enabled: true } })); db.close();
  f.reopen(); assert.deepEqual(f.state.notificationEnabledDeviceIds(), [f.device.deviceId]); f.state.close();
  const trap = new DatabaseSync(f.path);
  trap.exec("CREATE TABLE backfill_writes (id INTEGER); CREATE TRIGGER no_settings_backfill BEFORE UPDATE ON notification_settings BEGIN INSERT INTO backfill_writes VALUES (1); END");
  trap.prepare('UPDATE notification_state SET value=? WHERE id=?').run(JSON.stringify({ settings: { enabled: false } }), 'device:' + f.device.deviceId); trap.close();
  assert.doesNotThrow(() => f.reopen()); assert.equal(f.state.notificationEnabledDeviceCount(), 1, 'migration must not rescan and overwrite indexed settings');
  const verify = new DatabaseSync(f.path);
  try { assert.equal(verify.prepare('SELECT count(*) AS total FROM backfill_writes').get()?.total, 0); }
  finally { verify.close(); }
});

test('H09 backfill storage failure rolls back marker and remains retryable', t => {
  const f = fixture(t); f.state.close(); const db = new DatabaseSync(f.path);
  if (db.prepare("SELECT name FROM sqlite_master WHERE name='host_migrations'").get()) db.exec('DELETE FROM host_migrations');
  db.prepare('INSERT INTO notification_state VALUES (?,?)').run('device:' + f.device.deviceId, JSON.stringify({ settings: { enabled: true } }));
  db.exec("CREATE TRIGGER fail_settings BEFORE INSERT ON notification_settings BEGIN SELECT RAISE(ABORT,'synthetic storage failure'); END"); db.close();
  assert.throws(() => f.reopen(), /synthetic storage failure/);
  const inspect = new DatabaseSync(f.path);
  try { assert.equal(inspect.prepare('SELECT count(*) AS total FROM host_migrations').get()?.total, 0); inspect.exec('DROP TRIGGER fail_settings'); }
  finally { inspect.close(); }
  assert.equal(f.reopen().notificationEnabledDeviceCount(), 1);
});
