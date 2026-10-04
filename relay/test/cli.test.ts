import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { runRelayCli } from '../src/cli.ts';
import { RelayState } from '../src/state.ts';

test('local provisioning writes an exclusive private capability file, never secret stdout', async () => {
  const root = await mkdtemp(join(tmpdir(), 'dsh-relay-cli-'));
  const statePath = join(root, 'relay.sqlite'), output = join(root, 'connector.private.json');
  const out: string[] = [], errors: string[] = [];
  const io = { out: (text: string) => out.push(text), error: (text: string) => errors.push(text) };
  try {
    assert.equal(await runRelayCli(['init', '--state', statePath], io), 0);
    assert.equal(await runRelayCli(['provision', '--state', statePath, '--output', output], io), 0);
    const credential = JSON.parse(await readFile(output, 'utf8'));
    assert.equal(out.join('').includes(credential.connectorToken), false);
    const state = new RelayState(statePath);
    assert.equal(state.authenticateRoute(credential.routeId, credential.connectorToken), true); state.close();
    assert.equal(await runRelayCli(['provision', '--state', statePath, '--output', output], io), 1);
    const unchanged = new RelayState(statePath); assert.equal(unchanged.listRoutes().length, 1); unchanged.close();
    assert.equal(await runRelayCli(['revoke-route', '--state', statePath, '--route', credential.routeId], io), 0);
    const revoked = new RelayState(statePath); assert.equal(revoked.authenticateRoute(credential.routeId, credential.connectorToken), false); revoked.close();
    if (process.platform !== 'win32') assert.equal((await stat(output)).mode & 0o777, 0o600);
    assert.equal(errors.join('').includes(credential.connectorToken), false);
  } finally { await rm(root, { recursive: true, force: true }); }
});
