import assert from 'node:assert/strict';
import { test } from 'node:test';
import { RelayState } from '../src/state.ts';

test('operator route capabilities are independent, reject cross-route tokens, and can be revoked', () => {
  const state = new RelayState(':memory:');
  try {
    const a = state.provisionRoute(), b = state.provisionRoute();
    assert.match(a.routeId, /^[a-f0-9]{32}$/);
    assert.match(a.connectorToken, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(state.authenticateRoute(a.routeId, a.connectorToken), true);
    assert.equal(state.authenticateRoute(b.routeId, a.connectorToken), false);
    assert.equal(state.authenticateRoute(a.routeId, b.connectorToken), false);
    assert.equal(state.authenticateRoute('0'.repeat(32), a.connectorToken), false);
    assert.deepEqual(state.listRoutes().map(row => Object.keys(row)), [['routeId'], ['routeId']]);
    assert.equal(state.revokeRoute(a.routeId), true);
    assert.equal(state.authenticateRoute(a.routeId, a.connectorToken), false);
    assert.equal(state.authenticateRoute(b.routeId, b.connectorToken), true);
    assert.equal(state.revokeRoute(a.routeId), false);
  } finally { state.close(); }
});
