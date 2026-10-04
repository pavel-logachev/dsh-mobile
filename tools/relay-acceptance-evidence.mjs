import assert from 'node:assert/strict';

export function checkedOuterUrl(value, publicWss = false, approvedRelayUrl = process.env.RELAY_PUBLIC_URL) {
  const outer = new URL(value);
  if (publicWss) {
    assert.ok(approvedRelayUrl, 'Explicit approved relay URL required (argument or RELAY_PUBLIC_URL)');
    assert.equal(value, approvedRelayUrl, 'Only the exact operator-approved relay URL is permitted');
    assert.match(value, /^wss:\/\/[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?(?::[1-9][0-9]{0,4})?(?:\/[A-Za-z0-9_-]{1,64}){0,4}$/, 'Canonical production WSS required');
    assert.equal(outer.protocol, 'wss:');
    assert.equal(outer.username, ''); assert.equal(outer.password, '');
    assert.equal(outer.search, ''); assert.equal(outer.hash, '');
  }
  else {
    assert.equal(outer.protocol, 'ws:');
    assert.equal(outer.hostname, '127.0.0.1');
    assert.equal(outer.pathname, '/');
    assert.equal(outer.search, ''); assert.equal(outer.hash, '');
    assert.equal(outer.username, ''); assert.equal(outer.password, '');
  }
  return outer;
}

export function migrationEvidence(status, { publicWss, authenticatedDeviceRequest }) {
  assert.equal(authenticatedDeviceRequest, true, 'A post-pair inner request must use the new per-device access');
  assert.ok(status.maxDeviceGrantsPublished > 0 || status.deviceGrantsPublished > 0, 'Host per-device publication must be acknowledged');
  if (!publicWss) assert.ok(status.deviceCapabilityOpens > 0 && status.bootstrapOpens > 0, 'Local accepted bootstrap/device streams must be observed');
  return {
    authenticatedDeviceRequest: true,
    hostDevicePublicationAcknowledged: true,
    remoteUpgradeCounts: publicWss ? 'not-observed' : 'not-applicable',
    localUpgradeCounts: publicWss ? 'not-applicable' : { bootstrap: status.bootstrapOpens, device: status.deviceCapabilityOpens },
  };
}
