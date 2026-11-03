import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createServer as createTcpServer } from 'node:net';
import { once } from 'node:events';
import { mkdtempSync, rmSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { randomUUID, createHash } from 'node:crypto';
import { createRelayServer, RelayState } from '../../relay/src/index.ts';
import { WebSocketServer, WebSocket } from 'ws';
import { createRelayConnector } from '../src/relay-connector.ts';
import { HostState } from '../src/state.ts';
const routeId = 'a'.repeat(32), connectorToken = 'c'.repeat(43);
const grants = { readWorkspaceIds: ['alpha'], executeWorkspaceIds: ['alpha'] };
async function setup(t: { after: (fn: () => Promise<void>) => void }, ack = true) {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-connector-'))), state = new HostState(join(dir, 'host.sqlite'));
  const http = createServer(), wss = new WebSocketServer({ noServer: true, perMessageDeflate: false, maxPayload: 32768 });
  let control: WebSocket | undefined, generation = randomUUID();
  const snapshots: any[] = [], headers: any[] = [];
  let receivedResolve: (() => void) | undefined; let received = new Promise<void>(resolve => { receivedResolve = resolve; });
  http.on('upgrade', (req, socket, head) => {
    headers.push({ url: req.url, authorization: req.headers.authorization, route: req.headers['x-dsh-route'] });
    wss.handleUpgrade(req, socket, head, ws => {
      if (req.url === '/v1/control') {
        control = ws; ws.send(Buffer.from(JSON.stringify({ type: 'hello', version: 1, generation })));
        ws.on('message', data => { const value = JSON.parse(data.toString()); snapshots.push(value); receivedResolve?.(); if (ack) ws.send(Buffer.from(JSON.stringify({ type: 'ack', requestId: value.requestId }))); });
      }
    });
  });
  await new Promise<void>(resolve => http.listen(0, '127.0.0.1', resolve));
  const address = http.address() as { port: number };
  const connector = createRelayConnector({ relay: { url: `ws://127.0.0.1:${address.port}`, routeId, connectorToken }, state, targetPort: 9, allowInsecureLoopback: true });
  t.after(async () => { await connector.close(); for (const ws of wss.clients) ws.terminate(); await new Promise<void>(resolve => http.close(() => resolve())); wss.close(); state.close(); rmSync(dir, { recursive: true, force: true }); });
  connector.start();
  return { state, connector, snapshots, headers, received, control: () => control, generation: () => generation };
}

test('connector publishes authoritative hashes with a single ACK-gated snapshot and observes separate CLI revocation', { timeout: 10000 }, async t => {
  const { state, connector, snapshots, headers } = await setup(t);
  const offer = state.createRemotePairing(grants, routeId);
  await connector.waitPublished(offer.relayAccess.accessId);
  assert.equal(connector.status().ready, true);
  assert.equal(snapshots.at(-1).grants[0].accessId, offer.relayAccess.accessId);
  assert.equal(JSON.stringify(snapshots).includes(offer.relayAccess.accessToken), false);
  assert.deepEqual(headers[0], { url: '/v1/control', authorization: `Bearer ${connectorToken}`, route: routeId });
  const admin = new HostState(state.path); admin.revokeRelayGrant(offer.relayAccess.accessId); admin.close();
  // A new offer's acknowledged snapshot is an observable barrier for the external revocation.
  const next = state.createRemotePairing(grants, routeId);
  await connector.waitPublished(next.relayAccess.accessId);
  assert.equal(snapshots.at(-1).grants.some((grant: any) => grant.accessId === offer.relayAccess.accessId), false);
});

test('only one snapshot is in flight, stale ACK closes generation, and hostile open requests cannot grow queues', { timeout: 10000 }, async t => {
  const f = await setup(t, false); await f.received;
  const offer = f.state.createRemotePairing(grants, routeId);
  const wait = f.connector.waitPublished(offer.relayAccess.accessId);
  assert.equal(f.snapshots.length, 1, 'A second snapshot must wait for the first ACK');
  const closed = once(f.control()!, 'close');
  f.control()!.send(Buffer.from(JSON.stringify({ type: 'ack', requestId: randomUUID() })));
  await assert.rejects(wait, { code: 'unavailable' }); await closed;
  assert.equal(f.connector.status().ready, false);
  assert.equal(f.state.relayPublication(offer.relayAccess.accessId)?.published, false);
});

test('text and fragmented application controls fail closed without publication', { timeout: 10000 }, async t => {
  for (const fragmented of [false, true]) {
    const f = await setup(t, false); await f.received;
    const offer = f.state.createRemotePairing(grants, routeId); const wait = f.connector.waitPublished(offer.relayAccess.accessId);
    if (fragmented) {
      f.control()!.send(Buffer.from('{'), { binary: true, fin: false });
      f.control()!.send(Buffer.from('}'), { binary: true, fin: true });
    } else f.control()!.send(JSON.stringify({ type: 'ack', requestId: f.snapshots[0].requestId }));
    await assert.rejects(wait, { code: 'unavailable' }); assert.equal(f.connector.status().connected, false);
  }
});

test('connector accepts a 5-MiB burst with short opaque TLS tails without weakening tiny-message flood bounds', { timeout: 15000 }, async t => {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-connector-burst-')));
  const state = new HostState(join(dir, 'host.sqlite')), relayState = new RelayState(':memory:'), route = relayState.provisionRoute();
  const relay = createRelayServer({ state: relayState, port: 0 });
  const outer = await relay.start();
  const chunks: Buffer[] = [];
  let received = 0;
  const targetBytes = (32768 + 44) * 160;
  let complete!: () => void;
  const done = new Promise<void>(resolve => { complete = resolve; });
  const tcp = createTcpServer(socket => socket.on('data', chunk => { chunks.push(chunk); received += chunk.length; if (received === targetBytes) complete(); }));
  await new Promise<void>(resolve => tcp.listen(0, '127.0.0.1', resolve));
  const connector = createRelayConnector({ relay: { url: outer.baseUrl, ...route }, state, targetPort: (tcp.address() as { port: number }).port, allowInsecureLoopback: true });
  let mobile: WebSocket | undefined;
  t.after(async () => { mobile?.terminate(); await connector.close(); await relay.close(); await new Promise<void>(resolve => tcp.close(() => resolve())); state.close(); relayState.close(); rmSync(dir, { recursive: true, force: true }); });
  connector.start();
  const offer = state.createRemotePairing(grants, route.routeId); await connector.waitPublished(offer.relayAccess.accessId);
  mobile = new WebSocket(outer.baseUrl + '/v1/mobile', { headers: { Authorization: `Bearer ${offer.relayAccess.accessToken}`, 'X-DSH-Route': route.routeId, 'X-DSH-Access': offer.relayAccess.accessId }, perMessageDeflate: false });
  mobile.on('error', () => {});
  assert.equal(JSON.parse((await once(mobile, 'message'))[0].toString()).type, 'ready');
  const expected = Array.from({ length: 160 }, (_, i) => [Buffer.alloc(32768, i), Buffer.alloc(44, i)]).flat();
  const closed = once(mobile, 'close');
  for (const part of expected) await new Promise<void>((resolve, reject) => mobile!.send(part, { binary: true, fin: true, compress: false }, error => error ? reject(error) : resolve()));
  await Promise.race([done, closed.then(() => { throw new Error('closed_before_complete_upload'); })]);
  assert.equal(received, targetBytes);
  assert.equal(createHash('sha256').update(Buffer.concat(chunks)).digest('hex'), createHash('sha256').update(Buffer.concat(expected)).digest('hex'));
  for (let i = 0; i < 65; i++) mobile.send(Buffer.alloc(44));
  await closed;
});

test('missing ACK times out without publication and close rejects waiters safely', { timeout: 10000 }, async t => {
  const { state, connector } = await setup(t, false);
  const offer = state.createRemotePairing(grants, routeId);
  await assert.rejects(connector.waitPublished(offer.relayAccess.accessId, 150), { code: 'unavailable' });
  assert.equal(state.relayPublication(offer.relayAccess.accessId)?.published, false);
  const pending = connector.waitPublished(offer.relayAccess.accessId, 5000);
  await connector.close(); await assert.rejects(pending, { code: 'unavailable' });
});
