import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { request } from 'node:https';
import { once } from 'node:events';
import { createRelayServer, RelayState } from '../../../relay/src/index.ts';
import plugin, { apply } from '../../src/plugin.ts';
import { HostState } from '../../src/state.ts';
const root = process.argv[2]!, cordisModule = process.argv[3]!, routeId = process.argv[4]!, connectorToken = process.argv[5]!, port = Number(process.argv[6]);
const { Context } = await import(pathToFileURL(cordisModule).href);
const state = new RelayState(join(root, 'relay.sqlite'));
const relay = createRelayServer({ state, port: 0, tls: { cert: readFileSync(join(root, 'outer.pem'), 'utf8'), key: readFileSync(join(root, 'outer.key'), 'utf8') } });
const url = (await relay.start()).baseUrl;
let lists = 0, presets = 0, mutations = 0;
const controller = { async list() { lists++; return { items: [] }; }, async *follow() {}, async projections() { return {}; }, async create() { mutations++; return { sessionId: 'none' }; }, async prompt() { mutations++; }, cancel() { mutations++; } };
const ctx = new Context(); ctx.provide('sessionController', controller); ctx.provide('agentPresets', { async remoteExportList() { presets++; return { presets: [{ id: 'safe', name: 'Harmless preset' }] }; } });
const config = { hostName: 'Isolated plugin fixture', bind: '127.0.0.1', port, statePath: join(root, 'host.sqlite'), workspaces: [{ id: 'alpha', name: 'Alpha', path: root }], publicUrl: `https://h-${routeId}.dsh.invalid`, tls: { certPath: join(root, 'inner.pem'), keyPath: join(root, 'inner.key') }, relay: { url, routeId, connectorToken } };
const configPath = join(root, 'private.json'); writeFileSync(configPath, JSON.stringify(config), { mode: 0o600 });
let fiber: any, admin: HostState | undefined;
async function call(path: string, token?: string, body?: unknown) {
  return new Promise<{ status: number; body: any }>((resolve, reject) => {
    const req = request(`https://127.0.0.1:${port}/v1${path}`, { ca: readFileSync(join(root, 'inner.pem')), servername: `h-${routeId}.dsh.invalid`, method: body ? 'POST' : 'GET', headers: { Connection: 'close', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) } }, res => { const chunks: Buffer[] = []; res.on('data', chunk => chunks.push(chunk)); res.once('end', () => resolve({ status: res.statusCode!, body: JSON.parse(Buffer.concat(chunks).toString()) })); });
    req.once('error', reject); req.end(body ? JSON.stringify(body) : undefined);
  });
}
try {
  await assert.rejects(apply(ctx, { dshVersion: '0.2.0-rc.2', configPath, ...config } as any), { code: 'invalid_config' });
  await assert.rejects(call('/capabilities'), { code: 'ECONNREFUSED' });
  fiber = ctx.plugin(plugin, { dshVersion: '0.2.0-rc.2', configPath }); await fiber;
  admin = new HostState(config.statePath);
  const deadline = Date.now() + 5000;
  while (!admin.relayStatus(routeId).ready && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 25));
  assert.equal(admin.relayStatus(routeId).ready, true, 'configPath started a trusted WSS connector');
  assert.equal(relay.stats().controls, 1);
  const offer = admin.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] });
  const paired = await call('/pairings', undefined, { pairingToken: offer.pairingToken, deviceName: 'Plugin fixture phone' }); assert.equal(paired.status, 201);
  assert.equal((await call('/sessions', paired.body.deviceToken)).status, 200);
  assert.equal((await call('/presets', paired.body.deviceToken)).body.items[0].id, 'safe');
  assert.equal(lists, 1, 'no producer baseline without opt-in; one client session index read'); assert.equal(presets, 1); assert.equal(mutations, 0);
  await fiber.dispose();
  await assert.rejects(call('/capabilities'), { code: 'ECONNREFUSED' });
  const end = Date.now() + 1000; while (relay.stats().controls && Date.now() < end) await new Promise(resolve => setTimeout(resolve, 25));
  assert.equal(relay.stats().controls, 0); assert.equal(admin.relayStatus(routeId).connected, false);
  assert.equal(ctx.sessionController, controller, 'Companion disposal did not replace or stop the owner service');
  assert.equal(mutations, 0);
  console.log('PASS installed Cordis configPath HTTPS/WSS lifecycle, injected services and mixed-variant rejection');
} finally { await fiber?.dispose(); admin?.close(); await ctx.fiber.dispose(); await relay.close(); state.close(); }
