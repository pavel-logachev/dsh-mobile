import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { test } from 'node:test';
import { HostState } from '../src/state.ts';
import { startHostServer } from '../src/server.ts';

test('0.9.0 retired feed returns existing not-found and preserves unavailable capability shape', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'retired-feed-')); mkdirSync(join(dir, 'w'));
  let oldSourceCalls = 0;
  const adapter = { upstreamVersion: 'synthetic', notifications() { oldSourceCalls++; throw Error('retired source called'); }, async listSessions() { return []; }, async listPresets() { return []; }, async snapshot() { throw Error('unused'); }, async *watch() {}, async createSession() { return { sessionId: 'new' }; }, async prompt() {}, async cancel() {} };
  // Legacy feed sources must not be consulted, even when a caller still supplies one.
  const host = await startHostServer({ config: { hostName: 'Synthetic', bind: '127.0.0.1', port: 0, statePath: join(dir, 'host.sqlite'), allowInsecureLoopback: true, workspaces: [{ id: 'w', name: 'Synthetic', path: join(dir, 'w') }] }, adapter });
  t.after(async () => { await host.close(); rmSync(dir, { recursive: true, force: true }); });
  const base = (await host.start()).baseUrl, device = host.state.consumePairing(host.state.createPairing({ readWorkspaceIds: ['w'], executeWorkspaceIds: [] }).pairingToken, 'Synthetic');
  const request = (path: string, method = 'GET', body?: unknown) => fetch(base + '/v1' + path, { method, headers: { Authorization: 'Bearer ' + device.deviceToken, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) }, ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
  const missing = await request('/retired'); assert.equal(missing.status, 404); const envelope = await missing.json();
  for (const [path, method, body] of [['/notification-settings', 'GET'], ['/notification-settings', 'PUT', {}], ['/notification-events', 'GET'], ['/notification-events/stream', 'GET']] as const) {
    const response = await request(path, method, body); assert.equal(response.status, 404); assert.deepEqual(await response.json(), envelope);
  }
  const capabilities = await (await request('/capabilities')).json();
  assert.equal(capabilities.hostVersion, '0.9.3'); assert.equal(capabilities.capabilities.notifications, false);
  assert.deepEqual(capabilities.notificationCapabilities, { version: 1, coverage: 'degraded', feed: false, unifiedPush: false, retentionMs: 604800000 });
  assert.equal(oldSourceCalls, 0);
  const db = new DatabaseSync(host.state.path); try { assert.deepEqual(db.prepare("SELECT name FROM sqlite_master WHERE name IN ('notification_state','notification_settings','host_migrations')").all(), []); } finally { db.close(); }
});

test('retired notification tables and unindexed legacy data remain untouched on open and runtime claim', t => {
  const dir = mkdtempSync(join(tmpdir(), 'retired-tables-')), path = join(dir, 'host.sqlite'), db = new DatabaseSync(path);
  db.exec(`CREATE TABLE notification_state (id TEXT PRIMARY KEY, value TEXT NOT NULL) STRICT;
    CREATE TABLE notification_settings (device_id TEXT PRIMARY KEY, enabled INTEGER NOT NULL, settings TEXT NOT NULL) STRICT;
    CREATE TABLE host_migrations (id TEXT PRIMARY KEY) STRICT;
    INSERT INTO notification_settings VALUES ('one',1,'untouched');
    CREATE TRIGGER no_feed_insert BEFORE INSERT ON notification_settings BEGIN SELECT RAISE(ABORT,'retired table written'); END;
    CREATE TRIGGER no_feed_state_insert BEFORE INSERT ON notification_state BEGIN SELECT RAISE(ABORT,'retired state written'); END;`);
  db.exec('DROP TRIGGER no_feed_state_insert');
  const insert = db.prepare('INSERT INTO notification_state VALUES (?,?)');
  for (const [id, value] of [['device:one', '{"settings":{"enabled":false}}'], ['device:two', '{"settings":{"enabled":true}}'], ['empty', ''], ['bom', '\ufeff{}'], ['unicode', 'Кириллица 😀\r\n\ufeff']]) insert.run(id, value);
  db.exec("CREATE TRIGGER no_feed_state_insert BEFORE INSERT ON notification_state BEGIN SELECT RAISE(ABORT,'retired state written'); END;");
  const schema = db.prepare("SELECT sql FROM sqlite_master WHERE name LIKE 'notification_%' OR name='host_migrations' ORDER BY name").all();
  const rows = db.prepare('SELECT * FROM notification_state ORDER BY id').all(), settings = db.prepare('SELECT * FROM notification_settings').all(); db.close();
  let state: HostState | undefined;
  t.after(() => { state?.close(); rmSync(dir, { recursive: true, force: true }); });
  state = new HostState(path); state.claimRuntime(); state.close(); state = new HostState(path);
  const inspect = new DatabaseSync(path); try {
    assert.deepEqual(inspect.prepare("SELECT sql FROM sqlite_master WHERE name LIKE 'notification_%' OR name='host_migrations' ORDER BY name").all(), schema);
    assert.deepEqual(inspect.prepare('SELECT * FROM notification_state ORDER BY id').all(), rows);
    assert.deepEqual(inspect.prepare('SELECT * FROM notification_settings').all(), settings);
    assert.deepEqual(inspect.prepare('SELECT * FROM host_migrations').all(), []);
  } finally { inspect.close(); }
});
