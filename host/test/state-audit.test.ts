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
