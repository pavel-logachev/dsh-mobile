import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { HostState, hashSecret } from '../src/state.ts';
import { randomUUID } from 'node:crypto';
const routeId = 'a'.repeat(32), grants = { readWorkspaceIds: ['alpha'], executeWorkspaceIds: ['alpha'] };
function setup(t: { after: (fn: () => void) => void }) { const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-relay-state-'))); const state = new HostState(join(dir, 'state.sqlite')); t.after(() => { state.close(); rmSync(dir, { recursive: true, force: true }); }); return state; }

test('failed or abandoned publication revokes new grants/devices and stale ACK cannot re-enable them', t => {
  const state = setup(t), offer = state.createRemotePairing(grants, routeId);
  state.acknowledgeRelaySnapshot(routeId, state.relayGrantSnapshot(routeId), 'gen-1');
  const pending = state.consumeRemotePairing(offer.pairingToken, 'Phone', routeId);
  const stale = state.relayGrantSnapshot(routeId);
  state.revokeRelayGrant(pending.relayAccess.accessId);
  state.acknowledgeRelaySnapshot(routeId, stale, 'gen-1');
  assert.equal(state.authenticate(pending.deviceToken), undefined);
  assert.equal(state.relayPublication(pending.relayAccess.accessId)?.revoked, true);
  const blockedDirect = state.createRemotePairing(grants, routeId);
  state.revokeRelayGrant(blockedDirect.relayAccess.accessId);
  assert.throws(() => state.consumePairing(blockedDirect.pairingToken, 'No relay fallback'), { code: 'unauthorized' });
  const second = state.createRemotePairing(grants, routeId);
  state.acknowledgeRelaySnapshot(routeId, state.relayGrantSnapshot(routeId), 'gen-1');
  const abandoned = state.consumeRemotePairing(second.pairingToken, 'Abandoned', routeId);
  const abandonedOffer = state.createRemotePairing(grants, routeId);
  assert.throws(() => state.consumePairing(abandonedOffer.pairingToken, 'No direct consumption while pending'), { code: 'unauthorized' });
  state.recoverPendingRelayPublications();
  assert.throws(() => state.consumePairing(abandonedOffer.pairingToken, 'No direct consumption after recovery'), { code: 'unauthorized' });
  assert.equal(state.authenticate(abandoned.deviceToken), undefined);
  assert.equal(state.relayGrantSnapshot(routeId).some(grant => grant.deviceId === abandoned.deviceId), false);
});

test('grant replacement keeps remote device identity, relay capability hashes/publication and pending receipts unchanged', t => {
  const state = setup(t), all = { readWorkspaceIds: ['*'], executeWorkspaceIds: ['*'] };
  const offer = state.createRemotePairing(all, routeId);
  state.acknowledgeRelaySnapshot(routeId, state.relayGrantSnapshot(routeId), 'gen-1');
  const paired = state.consumeRemotePairing(offer.pairingToken, 'Synthetic phone', routeId);
  state.acknowledgeRelaySnapshot(routeId, state.relayGrantSnapshot(routeId), 'gen-1');
  const before = state.relayGrantSnapshot(routeId), publication = state.relayPublication(paired.relayAccess.accessId);
  const requestId = randomUUID();
  state.admitCommand(paired.deviceId, requestId, 'prompt', hashSecret('Synthetic pending receipt'), 'alpha');
  const receipt = state.getCommand(paired.deviceId, requestId);
  state.replaceDeviceGrants(paired.deviceId, { readWorkspaceIds: ['*'], executeWorkspaceIds: [] });
  assert.deepEqual(state.relayGrantSnapshot(routeId), before);
  assert.deepEqual(state.relayPublication(paired.relayAccess.accessId), publication);
  assert.deepEqual(state.getCommand(paired.deviceId, requestId), receipt);
  assert.equal(state.authenticate(paired.deviceToken)?.deviceId, paired.deviceId);
  assert.deepEqual(state.getDevice(paired.deviceId)?.grants, { readWorkspaceIds: ['*'], executeWorkspaceIds: [] });
});

test('outstanding bootstrap offers are bounded transactionally and wrong-route consumption is denied', t => {
  const state = setup(t);
  const offers = Array.from({ length: 16 }, () => state.createRemotePairing(grants, routeId));
  assert.throws(() => state.createRemotePairing(grants, routeId), { code: 'rate_limited' });
  state.acknowledgeRelaySnapshot(routeId, state.relayGrantSnapshot(routeId), 'gen-1');
  assert.throws(() => state.consumeRemotePairing(offers[0]!.pairingToken, 'Wrong', 'b'.repeat(32)), { code: 'unauthorized' });
  state.revokeRelayGrant(offers[0]!.relayAccess.accessId);
  assert.doesNotThrow(() => state.createRemotePairing(grants, routeId));
});

test('expired relay offers are cleaned without ever gaining a direct pairing lane', t => {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-relay-expiry-'))); let now = Date.now();
  const state = new HostState(join(dir, 'host.sqlite'), { now: () => now }); t.after(() => { state.close(); rmSync(dir, { recursive: true, force: true }); });
  const remote = state.createRemotePairing(grants, routeId, 1000), direct = state.createPairing(grants, 5000);
  state.acknowledgeRelaySnapshot(routeId, state.relayGrantSnapshot(routeId), 'generation');
  now += 1001;
  assert.deepEqual(state.relayGrantSnapshot(routeId), []);
  assert.equal(state.relayPublication(remote.relayAccess.accessId), undefined, 'Expired records are pruned, not retained forever');
  assert.throws(() => state.consumePairing(remote.pairingToken, 'No downgrade after cleanup'), { code: 'unauthorized' });
  assert.ok(state.authenticate(state.consumePairing(direct.pairingToken, 'Direct remains direct').deviceToken));
});

test('expired remote device credentials are denied at the inner API as well as omitted from outer snapshots', t => {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-relay-expiry-'))); let now = Date.now();
  const state = new HostState(join(dir, 'host.sqlite'), { now: () => now }); t.after(() => { state.close(); rmSync(dir, { recursive: true, force: true }); });
  const offer = state.createRemotePairing(grants, routeId); state.acknowledgeRelaySnapshot(routeId, state.relayGrantSnapshot(routeId), 'generation');
  const device = state.consumeRemotePairing(offer.pairingToken, 'Expires', routeId); state.acknowledgeRelaySnapshot(routeId, state.relayGrantSnapshot(routeId), 'generation');
  now = device.relayAccess.expiresAt;
  assert.equal(state.authenticate(device.deviceToken), undefined);
  assert.equal(state.getDevice(device.deviceId), undefined);
  assert.deepEqual(state.relayGrantSnapshot(routeId), []);
  assert.equal(state.authenticate(device.deviceToken), undefined);
});

test('remote offer and bootstrap grant are one transaction, hashed, bound and unpublished until exact snapshot ACK', t => {
  const state = setup(t);
  const offer = state.createRemotePairing(grants, routeId);
  assert.equal(Buffer.from(offer.pairingToken, 'base64url').length, 32);
  assert.equal(Buffer.from(offer.relayAccess.accessToken, 'base64url').length, 32);
  const snapshot = state.relayGrantSnapshot(routeId);
  assert.deepEqual(snapshot, [{ accessId: offer.relayAccess.accessId, tokenHash: hashSecret(offer.relayAccess.accessToken), deviceId: null, expiresAt: offer.expiresAt, maxStreams: 2 }]);
  assert.equal(state.relayPublication(offer.relayAccess.accessId)?.published, false);
  state.acknowledgeRelaySnapshot(routeId, snapshot, 'gen-1');
  assert.equal(state.relayPublication(offer.relayAccess.accessId)?.published, true);
  const paired = state.consumeRemotePairing(offer.pairingToken, 'Phone', routeId);
  assert.equal(state.authenticate(paired.deviceToken), undefined, 'Pending publication is not an authorized device');
  assert.equal(state.relayPublication(paired.relayAccess.accessId)?.published, false);
  state.acknowledgeRelaySnapshot(routeId, state.relayGrantSnapshot(routeId), 'gen-1');
  assert.equal(state.authenticate(paired.deviceToken)?.deviceId, paired.deviceId);
  assert.throws(() => state.consumeRemotePairing(offer.pairingToken, 'Other', routeId), { code: 'unauthorized' });
  assert.equal(JSON.stringify(state.listDevices()).includes(paired.relayAccess.accessToken), false);
  state.revokeDevice(paired.deviceId);
  assert.equal(state.relayGrantSnapshot(routeId).some(grant => grant.accessId === paired.relayAccess.accessId), false);
});
