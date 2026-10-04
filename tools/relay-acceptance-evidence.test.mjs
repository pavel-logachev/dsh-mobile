import assert from 'node:assert/strict';
import { test } from 'node:test';
import { checkedOuterUrl, migrationEvidence } from './relay-acceptance-evidence.mjs';

test('public relay requires explicit mode and exact approved WSS origin/prefix', () => {
  const approved = 'wss://relay.example.com/dsh-mobile-relay';
  assert.throws(() => checkedOuterUrl(approved, true, ''));
  assert.throws(() => checkedOuterUrl(approved));
  assert.equal(checkedOuterUrl(approved, true, approved).protocol, 'wss:');
  for (const url of [approved + '?x=1', approved + '/', 'wss://other.invalid/dsh-mobile-relay', 'ws://127.0.0.1:19446']) assert.throws(() => checkedOuterUrl(url, true, approved));
  for (const unsafe of ['ws://relay.example.com', 'wss://relay.example.com/a/../b', approved + '?secret=x', 'wss://user@relay.example.com']) {
    assert.throws(() => checkedOuterUrl(unsafe, true, unsafe));
  }
  const saved = process.env.RELAY_PUBLIC_URL;
  try {
    process.env.RELAY_PUBLIC_URL = approved;
    assert.equal(checkedOuterUrl(approved, true).href, approved);
    assert.throws(() => checkedOuterUrl('wss://other.invalid/dsh-mobile-relay', true));
  } finally {
    if (saved === undefined) delete process.env.RELAY_PUBLIC_URL; else process.env.RELAY_PUBLIC_URL = saved;
  }
  assert.equal(checkedOuterUrl('ws://127.0.0.1:19446').hostname, '127.0.0.1');
});

test('public migration requires actual per-device inner success plus host ACK, not fictitious remote counters', () => {
  const status = { deviceGrantsPublished: 1, deviceCapabilityOpens: 0, bootstrapOpens: 0 };
  assert.throws(() => migrationEvidence(status, { publicWss: false, authenticatedDeviceRequest: true }));
  assert.throws(() => migrationEvidence(status, { publicWss: true, authenticatedDeviceRequest: false }));
  assert.throws(() => migrationEvidence({ ...status, deviceGrantsPublished: 0 }, { publicWss: true, authenticatedDeviceRequest: true }));
  assert.equal(migrationEvidence(status, { publicWss: true, authenticatedDeviceRequest: true }).remoteUpgradeCounts, 'not-observed');
  assert.deepEqual(migrationEvidence({ ...status, deviceCapabilityOpens: 2, bootstrapOpens: 1 }, { publicWss: false, authenticatedDeviceRequest: true }).localUpgradeCounts, { bootstrap: 1, device: 2 });
});
