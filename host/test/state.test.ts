import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { HostState, hashSecret, validateGrants } from '../src/state.ts';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { once } from 'node:events';

const grants = { readWorkspaceIds: ['alpha'], executeWorkspaceIds: ['alpha'] };

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
