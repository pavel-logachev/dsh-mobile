import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { test } from 'node:test';
import WebSocket from 'ws';
import { RelayState, hashSecret } from '../src/state.ts';
import { createRelayServer } from '../src/server.ts';

class Peer {
  readonly ws: WebSocket;
  private messages: Buffer[] = [];
  private waiters: { resolve: (value: Buffer) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }[] = [];
  constructor(url: string, headers: Record<string, string> = {}) {
    this.ws = new WebSocket(url, { headers, perMessageDeflate: false, maxPayload: 32 * 1024 });
    this.ws.on('error', () => {});
    this.ws.on('message', (value, binary) => {
      assert.equal(binary, true);
      const buffer = Buffer.isBuffer(value) ? value : Buffer.from(value as ArrayBuffer);
      const next = this.waiters.shift();
      if (next) { clearTimeout(next.timer); next.resolve(buffer); } else this.messages.push(buffer);
    });
    this.ws.on('close', () => { for (const waiter of this.waiters.splice(0)) { clearTimeout(waiter.timer); waiter.reject(new Error('peer_closed')); } });
  }
  async opened(): Promise<void> { if (this.ws.readyState !== WebSocket.OPEN) await once(this.ws, 'open'); }
  next(): Promise<Buffer> {
    const value = this.messages.shift(); if (value) return Promise.resolve(value);
    return new Promise((resolve, reject) => {
      const waiter = { resolve, reject, timer: setTimeout(() => { this.waiters = this.waiters.filter(v => v !== waiter); reject(new Error('message_timeout')); }, 3000) };
      this.waiters.push(waiter);
    });
  }
  async json(): Promise<any> { return JSON.parse((await this.next()).toString('utf8')); }
  send(value: unknown): void { this.ws.send(Buffer.from(JSON.stringify(value)), { binary: true, fin: true, compress: false }); }
  async closed(): Promise<void> { if (this.ws.readyState !== WebSocket.CLOSED) await once(this.ws, 'close'); }
}

async function fixture(options: Record<string, unknown> = {}) {
  const state = new RelayState(':memory:');
  const owner = state.provisionRoute();
  const relay = createRelayServer({ state, port: 0, ...options });
  const { baseUrl } = await relay.start();
  const peers: Peer[] = [];
  const peer = (path: string, headers: Record<string, string>) => { const value = new Peer(baseUrl + path, headers); peers.push(value); return value; };
  const control = peer('/v1/control', { Authorization: `Bearer ${owner.connectorToken}`, 'X-DSH-Route': owner.routeId });
  await control.opened();
  const hello = await control.json();
  assert.equal(hello.type, 'hello'); assert.match(hello.generation, /^[a-f0-9-]{36}$/);
  const token = randomBytes(32).toString('base64url'), accessId = randomUUID();
  const grant = { accessId, tokenHash: hashSecret(token), deviceId: randomUUID(), expiresAt: Date.now() + 60000, maxStreams: 8 };
  const publish = async (grants: unknown[] = [grant]) => {
    const requestId = randomUUID(); control.send({ type: 'grants', version: 1, requestId, grants });
    assert.deepEqual(await control.json(), { type: 'ack', requestId });
  };
  const mobile = () => peer('/v1/mobile', { Authorization: `Bearer ${token}`, 'X-DSH-Route': owner.routeId, 'X-DSH-Access': accessId });
  const join = (open: any, routeId = owner.routeId, connectorToken = owner.connectorToken) => peer('/v1/host', {
    Authorization: `Bearer ${connectorToken}`, 'X-DSH-Route': routeId, 'X-DSH-Stream': open.streamId, 'X-DSH-Join': open.joinToken,
  });
  return { state, owner, relay, baseUrl, peers, peer, control, hello, token, accessId, grant, publish, mobile, join,
    close: async () => { peers.forEach(p => p.ws.terminate()); await relay.close(); state.close(); } };
}

async function rejected(peer: Peer, status: number): Promise<void> {
  const response = await new Promise<number>((resolve, reject) => {
    peer.ws.once('unexpected-response', (_request, res) => { const code = res.statusCode!; res.resume(); peer.ws.terminate(); resolve(code); });
    peer.ws.once('open', () => reject(new Error('unexpected_open')));
  });
  assert.equal(response, status);
}

test('relay requires an acknowledged grant and joins one opaque bidirectional stream', async () => {
  const f = await fixture();
  try {
    await rejected(f.mobile(), 503);
    await f.publish();
    const mobile = f.mobile(); await mobile.opened();
    const open = await f.control.json(); assert.equal(open.type, 'open'); assert.equal(open.generation, f.hello.generation);
    const host = f.join(open); await host.opened();
    assert.deepEqual(await mobile.json(), { type: 'ready', version: 1 });
    assert.deepEqual(await host.json(), { type: 'ready', version: 1 });
    const opaque = Buffer.from([0x16, 3, 3, 0, 4, 0xff, 0, 0x80]);
    mobile.ws.send(opaque); assert.deepEqual(await host.next(), opaque);
    host.ws.send(opaque); assert.deepEqual(await mobile.next(), opaque);
    assert.deepEqual(f.relay.stats(), { controls: 1, pending: 0, active: 1, sockets: 3 });
    mobile.ws.terminate(); await host.closed();
    assert.equal(f.relay.stats().active, 0);
  } finally { await f.close(); }
});

test('second control cannot seize a route; cross-route and double joins cannot splice sockets', async () => {
  const f = await fixture();
  try {
    await f.publish();
    await rejected(f.peer('/v1/control', { Authorization: `Bearer ${f.owner.connectorToken}`, 'X-DSH-Route': f.owner.routeId }), 409);
    const other = f.state.provisionRoute();
    const otherControl = f.peer('/v1/control', { Authorization: `Bearer ${other.connectorToken}`, 'X-DSH-Route': other.routeId });
    await otherControl.opened(); await otherControl.json();
    const id = randomUUID(); otherControl.send({ type: 'grants', version: 1, requestId: id, grants: [] }); await otherControl.json();
    const mobile = f.mobile(); await mobile.opened(); const open = await f.control.json();
    await rejected(f.join(open, other.routeId, other.connectorToken), 401);
    await rejected(f.peer('/v1/mobile', { Authorization: `Bearer ${f.token}`, 'X-DSH-Route': other.routeId, 'X-DSH-Access': f.accessId }), 401);
    const host = f.join(open); await host.opened(); await host.json(); await mobile.json();
    await rejected(f.join(open), 401);
    mobile.ws.send(Buffer.from('opaque-one')); assert.equal((await host.next()).toString(), 'opaque-one');
  } finally { await f.close(); }
});

test('acknowledged snapshot revoke, grant change, and control generation loss close existing tunnels', async () => {
  const f = await fixture();
  try {
    await f.publish();
    const mobile = f.mobile(); await mobile.opened(); const open = await f.control.json();
    const host = f.join(open); await host.opened(); await host.json(); await mobile.json();
    await f.publish([]); await mobile.closed(); await host.closed();
    await rejected(f.mobile(), 401);
    await f.publish();
    const nextMobile = f.mobile(); await nextMobile.opened(); const nextOpen = await f.control.json();
    await f.publish([{ ...f.grant, tokenHash: hashSecret(randomBytes(32).toString('base64url')) }]);
    await nextMobile.closed(); await rejected(f.join(nextOpen), 401);
    await f.publish();
    const pending = f.mobile(); await pending.opened(); const oldOpen = await f.control.json();
    f.control.ws.terminate(); await pending.closed();
    const replacement = f.peer('/v1/control', { Authorization: `Bearer ${f.owner.connectorToken}`, 'X-DSH-Route': f.owner.routeId });
    await replacement.opened(); const hello = await replacement.json(); assert.notEqual(hello.generation, f.hello.generation);
    await rejected(f.mobile(), 503);
    const requestId = randomUUID(); replacement.send({ type: 'grants', version: 1, requestId, grants: [f.grant] }); await replacement.json();
    await rejected(f.join(oldOpen), 401);
    assert.equal(f.relay.stats().pending, 0);
  } finally { await f.close(); }
});

test('bootstrap quota includes pending reservations and releases after disconnect', async () => {
  const f = await fixture();
  try {
    await f.publish([{ ...f.grant, deviceId: null, maxStreams: 2 }]);
    const a = f.mobile(); await a.opened(); await f.control.json();
    const b = f.mobile(); await b.opened(); await f.control.json();
    await rejected(f.mobile(), 429);
    a.ws.terminate(); await a.closed();
    const c = f.mobile(); await c.opened(); await f.control.json();
    assert.equal(f.relay.stats().pending, 2);
  } finally { await f.close(); }
});

test('expired grants close active streams and pending joins time out', async () => {
  const f = await fixture({ timings: { pendingMs: 80 } });
  try {
    await f.publish([{ ...f.grant, expiresAt: Date.now() + 160 }]);
    const mobile = f.mobile(); await mobile.opened(); const open = await f.control.json();
    const host = f.join(open); await host.opened(); await host.json(); await mobile.json();
    await mobile.closed(); await host.closed(); await rejected(f.mobile(), 401);
    await f.publish();
    const pending = f.mobile(); await pending.opened(); const stale = await f.control.json(); await pending.closed();
    await rejected(f.join(stale), 401);
    assert.equal(f.relay.stats().pending, 0);
  } finally { await f.close(); }
});

test('raw stream crosses the 2-MiB API snapshot size without a tunnel lifetime cap', async () => {
  const f = await fixture();
  try {
    await f.publish(); const mobile = f.mobile(); await mobile.opened(); const open = await f.control.json();
    const host = f.join(open); await host.opened(); await mobile.json(); await host.json();
    const chunk = Buffer.alloc(32768, 0x7d);
    for (let index = 0; index < 80; index++) { host.ws.send(chunk); assert.deepEqual(await mobile.next(), chunk); }
    assert.equal(f.relay.stats().active, 1);
  } finally { await f.close(); }
});

test('malformed control, text payload, oversized payload and fragmented payload fail closed', async () => {
  for (const kind of ['control', 'text', 'oversize', 'fragment'] as const) {
    const f = await fixture();
    try {
      await f.publish();
      if (kind === 'control') {
        f.control.send({ type: 'grants', version: 1, requestId: randomUUID(), grants: [{ ...f.grant, maxStreams: 99 }] });
        await f.control.closed(); assert.equal(f.relay.stats().controls, 0); continue;
      }
      const mobile = f.mobile(); await mobile.opened(); const open = await f.control.json();
      const host = f.join(open); await host.opened(); await mobile.json(); await host.json();
      if (kind === 'text') mobile.ws.send('not opaque binary');
      if (kind === 'oversize') mobile.ws.send(Buffer.alloc(32769));
      if (kind === 'fragment') { mobile.ws.send(Buffer.alloc(20), { fin: false }); mobile.ws.send(Buffer.alloc(20), { fin: true }); }
      await mobile.closed(); await host.closed(); assert.equal(f.relay.stats().active, 0);
    } finally { await f.close(); }
  }
});

test('data before rendezvous, zero-length message flood and ping flood release reservations', async () => {
  for (const kind of ['early', 'zero', 'ping'] as const) {
    const f = await fixture();
    try {
      await f.publish(); const mobile = f.mobile(); await mobile.opened(); await f.control.json();
      if (kind === 'early') mobile.ws.send(Buffer.from('before-ready'));
      if (kind === 'zero') mobile.ws.send(Buffer.alloc(0));
      if (kind === 'ping') for (let i = 0; i < 20; i++) mobile.ws.ping();
      await mobile.closed(); assert.equal(f.relay.stats().pending, 0);
    } finally { await f.close(); }
  }
});

test('browser, query credential, duplicate capability headers and oversized upgrade headers are denied', async () => {
  const f = await fixture();
  try {
    await rejected(f.peer('/v1/control', { Authorization: `Bearer ${f.owner.connectorToken}`, 'X-DSH-Route': f.owner.routeId, Origin: 'https://attacker.invalid' }), 403);
    await rejected(f.peer('/v1/control?token=no', { Authorization: `Bearer ${f.owner.connectorToken}`, 'X-DSH-Route': f.owner.routeId }), 404);
    await rejected(f.peer('/v1/control', { Authorization: `Bearer ${f.owner.connectorToken}`, 'X-DSH-Route': f.owner.routeId, 'X-Too-Large': 'x'.repeat(9000) }), 400);
    const many: Record<string, string> = { Authorization: `Bearer ${f.owner.connectorToken}`, 'X-DSH-Route': f.owner.routeId };
    for (let i = 0; i < 36; i++) many[`X-Filler-${i}`] = 'x';
    many.Origin = 'https://attacker.invalid';
    await rejected(f.peer('/v1/control', many), 400);
    const duplicate = new Peer(f.baseUrl + '/v1/control', { Authorization: [`Bearer ${f.owner.connectorToken}`, `Bearer ${f.owner.connectorToken}`] as unknown as string, 'X-DSH-Route': f.owner.routeId });
    f.peers.push(duplicate); await rejected(duplicate, 400);
  } finally { await f.close(); }
});

test('compression is never negotiated and a compressed wire frame is rejected', async () => {
  const f = await fixture();
  try {
    await f.publish();
    const mobile = new Peer(f.baseUrl + '/v1/mobile', { Authorization: `Bearer ${f.token}`, 'X-DSH-Route': f.owner.routeId, 'X-DSH-Access': f.accessId });
    f.peers.push(mobile); await mobile.opened(); assert.equal(mobile.ws.extensions, '');
    const open = await f.control.json(); const host = f.join(open); await host.opened(); await mobile.json(); await host.json();
    // Public raw TCP socket is tested separately below; no private ws receiver/sender mutation.
    const net = await import('node:net');
    const url = new URL(f.baseUrl); const socket = net.connect(Number(url.port), url.hostname);
    await once(socket, 'connect');
    const key = randomBytes(16).toString('base64');
    socket.write(`GET /v1/mobile HTTP/1.1\r\nHost: ${url.host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer ${f.token}\r\nX-DSH-Route: ${f.owner.routeId}\r\nX-DSH-Access: ${f.accessId}\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\n`);
    const [upgrade] = await once(socket, 'data'); assert.match(upgrade.toString(), /^HTTP\/1\.1 101/); assert.equal(upgrade.toString().includes('Sec-WebSocket-Extensions:'), false);
    const rawOpen = await f.control.json(); const rawHost = f.join(rawOpen); await rawHost.opened(); await rawHost.json();
    socket.on('error', () => {});
    // FIN|RSV1|BINARY with masked one-byte payload, forbidden without an extension.
    socket.write(Buffer.from([0xc2, 0x81, 0, 0, 0, 0, 0x01]));
    await rawHost.closed(); socket.destroy();
  } finally { await f.close(); }
});

test('public upgrade and per-route open rate limits reject excess before admitting a socket', async () => {
  const f = await fixture({ limits: { upgradesPerIpPerMinute: 5, opensPerRoutePerMinute: 1 } });
  try {
    await f.publish();
    const a = f.mobile(); await a.opened(); await f.control.json();
    await rejected(f.mobile(), 429);
    await rejected(f.peer('/v1/control', { Authorization: 'Bearer ' + randomBytes(32).toString('base64url'), 'X-DSH-Route': f.owner.routeId }), 401);
    await rejected(f.peer('/v1/control', { Authorization: 'Bearer ' + randomBytes(32).toString('base64url'), 'X-DSH-Route': f.owner.routeId }), 401);
    await rejected(f.peer('/v1/control', { Authorization: 'Bearer ' + randomBytes(32).toString('base64url'), 'X-DSH-Route': f.owner.routeId }), 429);
  } finally { await f.close(); }
});

test('programmatic route revoke and service shutdown clear control and data resources', async () => {
  const f = await fixture();
  try {
    await f.publish(); const mobile = f.mobile(); await mobile.opened(); await f.control.json();
    f.state.revokeRoute(f.owner.routeId); await f.control.closed(); await mobile.closed();
    assert.deepEqual(f.relay.stats(), { controls: 0, pending: 0, active: 0, sockets: 0 });
    await f.relay.close(); assert.equal(f.relay.server.listening, false);
  } finally { await f.close(); }
});

test('a slow nonreading receiver is reset within the configured stall budget without unbounded forwarding', async () => {
  const f = await fixture({ timings: { stallMs: 80 } });
  const net = await import('node:net'); let socket: import('node:net').Socket | undefined;
  try {
    await f.publish();
    const url = new URL(f.baseUrl); socket = net.connect(Number(url.port), url.hostname); socket.on('error', () => {});
    await once(socket, 'connect');
    socket.write(`GET /v1/mobile HTTP/1.1\r\nHost: ${url.host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${randomBytes(16).toString('base64')}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer ${f.token}\r\nX-DSH-Route: ${f.owner.routeId}\r\nX-DSH-Access: ${f.accessId}\r\n\r\n`);
    const [upgrade] = await once(socket, 'data'); assert.match(upgrade.toString(), /^HTTP\/1\.1 101/);
    socket.pause();
    const open = await f.control.json(); const host = f.join(open); await host.opened(); await host.json();
    const chunk = Buffer.alloc(32768, 0xaa);
    await new Promise<void>((resolve, reject) => {
      let timer: ReturnType<typeof setImmediate>; const deadline = setTimeout(() => { clearImmediate(timer); reject(new Error('stall_not_closed')); }, 3000);
      host.ws.once('close', () => { clearImmediate(timer); clearTimeout(deadline); resolve(); });
      let sent = 0;
      const pump = () => {
        if (host.ws.readyState !== WebSocket.OPEN) return;
        // Keep the client library bounded too: this test is not an unbounded-memory producer.
        if (host.ws.bufferedAmount < 131072 && sent < 1024) { host.ws.send(chunk); sent++; }
        timer = setImmediate(pump);
      }; pump();
    });
    assert.equal(f.relay.stats().active, 0);
  } finally { socket?.destroy(); await f.close(); }
});

test('control readiness and missed pongs have bounded lifetimes', async () => {
  const f = await fixture({ timings: { pendingMs: 80, heartbeatMs: 20, staleMs: 100 } });
  try {
    // This first control intentionally never publishes a snapshot.
    await f.control.closed(); assert.equal(f.relay.stats().controls, 0);
    const owner = f.state.provisionRoute();
    const control = new WebSocket(f.baseUrl + '/v1/control', { headers: { Authorization: `Bearer ${owner.connectorToken}`, 'X-DSH-Route': owner.routeId }, autoPong: false, perMessageDeflate: false });
    control.on('error', () => {});
    await once(control, 'open');
    const requestId = randomUUID(); control.send(Buffer.from(JSON.stringify({ type: 'grants', version: 1, requestId, grants: [] })));
    await once(control, 'close'); assert.equal(f.relay.stats().controls, 0);
  } finally { await f.close(); }
});

test('relay refuses non-loopback plaintext and exposes only minimal prefixed health', async () => {
  const state = new RelayState(':memory:');
  try {
    assert.throws(() => createRelayServer({ state, bind: '0.0.0.0', port: 0 }), /invalid_config/);
    assert.throws(() => createRelayServer({ state, pathPrefix: '/..' }), /invalid_config/);
    const relay = createRelayServer({ state, port: 0, pathPrefix: '/opaque' });
    try {
      const { baseUrl } = await relay.start(); const url = baseUrl.replace('ws:', 'http:');
      const response = await fetch(url + '/healthz'); assert.equal(response.status, 200); assert.deepEqual(await response.json(), { ok: true });
      assert.equal((await fetch(url + '/routes')).status, 404);
    } finally { await relay.close(); }
  } finally { state.close(); }
});

test('revocation through a separate local database connection closes live ownership', async () => {
  const fs = await import('node:fs/promises'), os = await import('node:os'), path = await import('node:path');
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'dsh-relay-revoke-'));
  const state = new RelayState(path.join(root, 'relay.sqlite')), owner = state.provisionRoute();
  const relay = createRelayServer({ state, port: 0 });
  let peer: Peer | undefined;
  try {
    const { baseUrl } = await relay.start();
    peer = new Peer(baseUrl + '/v1/control', { Authorization: `Bearer ${owner.connectorToken}`, 'X-DSH-Route': owner.routeId });
    await peer.opened(); await peer.json(); const requestId = randomUUID(); peer.send({ type: 'grants', version: 1, requestId, grants: [] }); await peer.json();
    const admin = new RelayState(state.path); admin.revokeRoute(owner.routeId); admin.close();
    await peer.closed(); assert.equal(relay.stats().controls, 0);
  } finally { peer?.ws.terminate(); await relay.close(); state.close(); await fs.rm(root, { recursive: true, force: true }); }
});

export { Peer, fixture, rejected };
