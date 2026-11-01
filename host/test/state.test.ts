import assert from 'node:assert/strict';
import { copyFileSync, existsSync, mkdtempSync, rmSync, realpathSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { HostState, hashSecret, validateGrants } from '../src/state.ts';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { legacyModule } from './fixtures/legacy-module.ts';

const grants = { readWorkspaceIds: ['alpha'], executeWorkspaceIds: ['alpha'] };

test('0.7.0 accepted creates seed bounded blank exceptions exactly once on upgrade', async t => {
  const { HostState: OldState } = await legacyModule('state', t);
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'mobile-upgrade-'))), path = join(dir, 'host.sqlite');
  let now = 1_800_000_000_000;
  const old = new OldState(path, { now: () => now });
  const device = old.consumePairing(old.createPairing(grants).pairingToken, 'Synthetic phone');
  const record = (id: string, status = 'accepted') => { const request = randomUUID(); old.admitCommand(device.deviceId, request, 'create', hashSecret(id), 'alpha'); old.finishCommand(device.deviceId, request, status, { result: { sessionId: id } }); };
  record('expired'); now += 7 * 24 * 60 * 60_000;
  for (let i = 0; i < 257; i++) { now++; record('legacy-' + i); }
  record('rejected', 'rejected'); old.close();
  let updated = new HostState(path, { now: () => now });
  t.after(() => { updated.close(); rmSync(dir, { recursive: true, force: true }); });
  assert.equal(updated.mobileSessionVisible('legacy-256', true), true);
  assert.equal(updated.mobileSessionVisible('legacy-1', true), true);
  for (const id of ['legacy-0', 'expired', 'rejected']) assert.equal(updated.mobileSessionVisible(id, true), false);
  updated.mobileSessionVisible('legacy-256', false); updated.close();
  updated = new HostState(path, { now: () => now });
  assert.equal(updated.mobileSessionVisible('legacy-256', true), false, 'reopen must not resurrect engaged exceptions');
  now += 7 * 24 * 60 * 60_000;
  assert.equal(updated.mobileSessionVisible('legacy-1', true), false);
});

test('mobile-created blank exceptions expire, retire on engagement, and remain bounded', t => {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-created-'))); let now = 1000;
  const state = new HostState(join(dir, 'host.sqlite'), { now: () => now });
  t.after(() => { state.close(); rmSync(dir, { recursive: true, force: true }); });
  state.rememberMobileSession('engaged'); assert.equal(state.mobileSessionVisible('engaged', true), true);
  assert.equal(state.mobileSessionVisible('engaged', false), true); assert.equal(state.mobileSessionVisible('engaged', true), false);
  state.rememberMobileSession('expired'); now += 7 * 24 * 60 * 60_000;
  assert.equal(state.mobileSessionVisible('expired', true), false); assert.equal(state.mobileSessionVisible('old-host', undefined), true);
  for (let i = 0; i < 257; i++) state.rememberMobileSession('bounded-' + i);
  assert.equal(state.mobileSessionVisible('bounded-0', true), false); assert.equal(state.mobileSessionVisible('bounded-256', true), true);
});

test('OS-released runtime lock excludes another process; crash restart safely recovers without blocking admin access', async t => {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-lock-')));
  const path = join(dir, 'host.sqlite'), requestId = randomUUID();
  const child = spawn(process.execPath, [new URL('./fixtures/runtime-child.ts', import.meta.url).pathname.replace(/^\/(?=[A-Za-z]:)/, ''), path, requestId], { stdio: ['ignore', 'ignore', 'inherit', 'ipc'] });
  let state: HostState | undefined;
  t.after(async () => { if (child.exitCode === null && child.signalCode === null) { child.kill(); await once(child, 'exit'); } state?.close(); rmSync(dir, { recursive: true, force: true }); });
  const [message] = await once(child, 'message') as [{ deviceId: string }];
  state = new HostState(path);
  assert.throws(() => state!.claimRuntime(), { code: 'conflict' });
  assert.equal(state.getCommand(message.deviceId, requestId)?.receipt.status, 'pending');
  assert.equal(state.listDevices().length, 1);
  state.createPairing(grants);
  child.kill(); await once(child, 'exit');
  state.claimRuntime();
  assert.equal(state.getCommand(message.deviceId, requestId)?.receipt.status, 'uncertain');
  assert.equal(state.admitCommand(message.deviceId, requestId, 'prompt', hashSecret('crash-fixture')).fresh, false);
});

test('wildcard grants persist all future scope, permit explicit execute under all read and reject execute without all read', t => {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-wildcard-')));
  const state = new HostState(join(dir, 'host.sqlite'));
  t.after(() => { state.close(); rmSync(dir, { recursive: true, force: true }); });
  const all = { readWorkspaceIds: ['*'], executeWorkspaceIds: ['*'] };
  const device = state.consumePairing(state.createPairing(all).pairingToken, 'Synthetic phone');
  assert.deepEqual(state.authenticate(device.deviceToken)?.grants, all);
  assert.deepEqual(validateGrants({ readWorkspaceIds: ['*'], executeWorkspaceIds: ['alpha'] }), { readWorkspaceIds: ['*'], executeWorkspaceIds: ['alpha'] });
  assert.throws(() => validateGrants({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: ['*'] }), { code: 'invalid_request' });
  assert.throws(() => validateGrants({ readWorkspaceIds: [], executeWorkspaceIds: ['*'] }), { code: 'invalid_request' });
  assert.throws(() => validateGrants({ readWorkspaceIds: ['*', 'alpha'], executeWorkspaceIds: [] }), { code: 'invalid_request' });
});

test('expiry and revocation deny credentials and execute grants must be a subset of read scope', t => {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-state-')));
  let now = 1000;
  const state = new HostState(join(dir, 'host.sqlite'), { now: () => now });
  t.after(() => { state.close(); rmSync(dir, { recursive: true, force: true }); });
  const expired = state.createPairing(grants, 10);
  now = 1010;
  assert.throws(() => state.consumePairing(expired.pairingToken, 'Phone'), { code: 'unauthorized' });
  assert.throws(() => state.consumePairing('x'.repeat(43), 'Phone'), { code: 'unauthorized' });
  assert.throws(() => state.createPairing({ readWorkspaceIds: [], executeWorkspaceIds: ['alpha'] }), { code: 'invalid_request' });
  const offer = state.createPairing(grants);
  const paired = state.consumePairing(offer.pairingToken, 'Phone');
  assert.equal(state.revokeDevice(paired.deviceId), true);
  assert.equal(state.authenticate(paired.deviceToken), undefined);
  assert.equal(state.listDevices()[0]?.revokedAt, 1010);
  assert.equal(state.revokeDevice(paired.deviceId), false);
});

test('operator grant replacement is atomic, keeps credentials/receipts intact and refuses revoked or unknown devices', t => {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-grant-')));
  const path = join(dir, 'host.sqlite'), state = new HostState(path), admin = new HostState(path);
  t.after(() => { admin.close(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  const paired = state.consumePairing(state.createPairing(grants).pairingToken, 'Synthetic phone');
  const before = state.authenticate(paired.deviceToken)!;
  const requestId = randomUUID();
  state.admitCommand(paired.deviceId, requestId, 'prompt', hashSecret('synthetic'), 'alpha');
  state.finishCommand(paired.deviceId, requestId, 'accepted');
  const receipt = state.getCommand(paired.deviceId, requestId);
  admin.replaceDeviceGrants(paired.deviceId, { readWorkspaceIds: ['*'], executeWorkspaceIds: ['*'] });
  assert.deepEqual(state.authenticate(paired.deviceToken), { ...before, grants: { readWorkspaceIds: ['*'], executeWorkspaceIds: ['*'] } });
  assert.throws(() => admin.replaceDeviceGrants(paired.deviceId, { readWorkspaceIds: ['alpha'], executeWorkspaceIds: ['*'] }), { code: 'invalid_request' });
  assert.deepEqual(state.getDevice(paired.deviceId)?.grants, { readWorkspaceIds: ['*'], executeWorkspaceIds: ['*'] });
  admin.replaceDeviceGrants(paired.deviceId, { readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] });
  assert.deepEqual(state.getCommand(paired.deviceId, requestId), receipt);
  state.revokeDevice(paired.deviceId);
  assert.throws(() => admin.replaceDeviceGrants(paired.deviceId, grants), { code: 'not_found' });
  assert.throws(() => admin.replaceDeviceGrants(randomUUID(), grants), { code: 'not_found' });
});

test('only server-start recovers unfinished receipts; independent admin opens never alter dispatching state', t => {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-state-')));
  const path = join(dir, 'host.sqlite');
  const state = new HostState(path);
  const admin = new HostState(path);
  t.after(() => { admin.close(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  const device = state.consumePairing(state.createPairing(grants).pairingToken, 'Phone');
  state.claimRuntime();
  assert.throws(() => admin.claimRuntime(), { code: 'conflict' });
  const requestId = randomUUID();
  const admitted = state.admitCommand(device.deviceId, requestId, 'prompt', hashSecret('synthetic-payload'));
  assert.equal(admitted.fresh, true);
  assert.equal(admin.getCommand(device.deviceId, requestId)?.receipt.status, 'pending');
  admin.createPairing(grants);
  assert.equal(state.getCommand(device.deviceId, requestId)?.receipt.status, 'pending');
  state.releaseRuntime();
  admin.claimRuntime();
  assert.equal(admin.getCommand(device.deviceId, requestId)?.receipt.status, 'uncertain');
  assert.equal(admin.admitCommand(device.deviceId, requestId, 'prompt', hashSecret('synthetic-payload')).fresh, false);
  assert.throws(() => admin.admitCommand(device.deviceId, requestId, 'prompt', hashSecret('different')), { code: 'conflict' });
});

test('a pairing offer issues an individual credential once and cannot be reused', (t) => {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-state-')));
  const state = new HostState(join(dir, 'host.sqlite'));
  t.after(() => { state.close(); rmSync(dir, { recursive: true, force: true }); });
  const offer = state.createPairing(grants);
  assert.equal(Buffer.from(offer.pairingToken, 'base64url').length, 32);
  const paired = state.consumePairing(offer.pairingToken, 'Phone');
  assert.notEqual(paired.deviceToken, offer.pairingToken);
  assert.equal(Buffer.from(paired.deviceToken, 'base64url').length, 32);
  assert.deepEqual(state.authenticate(paired.deviceToken)?.grants, grants);
  assert.throws(() => state.consumePairing(offer.pairingToken, 'Other'), { code: 'unauthorized' });
  assert.equal(state.authenticate(offer.pairingToken), undefined);
});

test('restoring an older ledger blocks fresh commands while known receipts stay readable, then an explicit new epoch releases it', t => {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-ledger-')));
  const path = join(dir, 'host.sqlite'), backup = join(dir, 'backup.sqlite');
  const state = new HostState(path);
  let reopened: HostState | undefined;
  t.after(() => { state.close(); reopened?.close(); rmSync(dir, { recursive: true, force: true }); });
  const device = state.consumePairing(state.createPairing(grants).pairingToken, 'Phone');
  const first = randomUUID();
  state.admitCommand(device.deviceId, first, 'prompt', hashSecret('first'), 'alpha', 'session-first');
  state.finishCommand(device.deviceId, first, 'accepted', { result: { sessionId: 'session-first' } });
  state.close();
  copyFileSync(path, backup);
  const later = new HostState(path);
  const second = randomUUID();
  later.admitCommand(device.deviceId, second, 'prompt', hashSecret('second'), 'alpha', 'session-second');
  later.finishCommand(device.deviceId, second, 'accepted');
  assert.equal(later.ledgerStatus().admittedCount, 2);
  later.close();
  for (const suffix of ['-wal', '-shm']) if (existsSync(path + suffix)) rmSync(path + suffix);
  copyFileSync(backup, path);
  const restored = new HostState(path);
  const status = restored.ledgerStatus();
  assert.equal(status.hold, true); assert.equal(status.holdReason, 'rollback'); assert.equal(status.witnessProblem, 'rollback');
  assert.equal(restored.getCommand(device.deviceId, first)?.receipt.status, 'accepted');
  assert.equal(restored.getCommand(device.deviceId, second), undefined, 'the exact command lost by the restore has no receipt');
  assert.throws(() => restored.admitCommand(device.deviceId, randomUUID(), 'prompt', hashSecret('fresh'), 'alpha', 'session-fresh'), { code: 'recovery_hold' });
  assert.equal(restored.admitCommand(device.deviceId, first, 'prompt', hashSecret('first'), 'alpha', 'session-first').fresh, false, 'a known receipt still replays during hold');
  const released = restored.releaseRecovery();
  assert.equal(released.hold, false); assert.equal(released.epoch, status.epoch + 1);
  const third = randomUUID();
  assert.equal(restored.admitCommand(device.deviceId, third, 'prompt', hashSecret('third'), 'alpha', 'session-third').fresh, true);
  restored.finishCommand(device.deviceId, third, 'accepted');
  restored.close();
  reopened = new HostState(path);
  assert.equal(reopened.ledgerStatus().hold, false, 'the explicit release must not re-hold on reopen');
  assert.equal(reopened.getCommand(device.deviceId, first)?.receipt.status, 'accepted');
  assert.equal(reopened.getCommand(device.deviceId, third)?.receipt.status, 'accepted');
});

test('an explicit recovery hold blocks only fresh admissions and never rewrites a contradictory witness', t => {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-hold-')));
  const path = join(dir, 'host.sqlite'), witness = path + '.ledger.json';
  const state = new HostState(path);
  t.after(() => { state.close(); rmSync(dir, { recursive: true, force: true }); });
  const device = state.consumePairing(state.createPairing(grants).pairingToken, 'Phone');
  const known = randomUUID();
  state.admitCommand(device.deviceId, known, 'prompt', hashSecret('known'), 'alpha', 'session-known');
  state.finishCommand(device.deviceId, known, 'accepted');
  const before = state.ledgerStatus();
  state.setRecoveryHold('operator_request');
  const held = state.ledgerStatus();
  assert.equal(held.hold, true); assert.equal(held.holdReason, 'operator_request');
  assert.throws(() => state.admitCommand(device.deviceId, randomUUID(), 'prompt', hashSecret('blocked'), 'alpha'), { code: 'recovery_hold' });
  assert.equal(state.admitCommand(device.deviceId, known, 'prompt', hashSecret('known'), 'alpha', 'session-known').fresh, false);
  assert.equal(state.getCommand(device.deviceId, known)?.receipt.status, 'accepted');
  const released = state.releaseRecovery();
  assert.equal(released.hold, false); assert.equal(released.epoch, before.epoch + 1);
  assert.throws(() => state.releaseRecovery(), { code: 'conflict' }, 'a released ledger cannot be released twice');
  assert.equal(existsSync(witness), true);
});

test('missing or corrupt ledger witnesses fail closed without inventing a rollback for a first run', t => {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-witness-')));
  const path = join(dir, 'host.sqlite'), witness = path + '.ledger.json';
  const first = new HostState(path);
  let seeded: HostState | undefined, corrupt: HostState | undefined;
  t.after(() => { first.close(); seeded?.close(); corrupt?.close(); rmSync(dir, { recursive: true, force: true }); });
  const device = first.consumePairing(first.createPairing(grants).pairingToken, 'Phone');
  assert.equal(first.ledgerStatus().witnessProblem, 'none');
  first.close();
  // Losing the sidecar cannot distinguish a first run from a restore of both files; it seeds, never holds.
  rmSync(witness);
  seeded = new HostState(path);
  assert.equal(seeded.ledgerStatus().hold, false); assert.equal(seeded.ledgerStatus().witnessProblem, 'none');
  seeded.close();
  writeFileSync(witness, '{not json');
  corrupt = new HostState(path);
  assert.equal(corrupt.ledgerStatus().hold, true); assert.equal(corrupt.ledgerStatus().witnessProblem, 'witness_corrupt');
  assert.throws(() => corrupt.admitCommand(device.deviceId, randomUUID(), 'prompt', hashSecret('x'), 'alpha'), { code: 'recovery_hold' });
  assert.throws(() => corrupt.releaseRecovery(), { code: 'recovery_hold' });
  assert.equal(corrupt.ledgerStatus().hold, true); assert.equal(corrupt.ledgerStatus().witnessProblem, 'witness_corrupt');
});
