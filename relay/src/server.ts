import { randomBytes, randomUUID } from 'node:crypto';
import { createServer as createHttpServer } from 'node:http';
import type { IncomingMessage, Server as HttpServer } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import type { Server as HttpsServer } from 'node:https';
import { isIP } from 'node:net';
import type { Socket } from 'node:net';
import type { Duplex } from 'node:stream';
import WebSocket, { WebSocketServer } from 'ws';
import type { RawData } from 'ws';
import { DIGEST, equalDigest, hashSecret, RelayState, ROUTE_ID, UUID, validToken } from './state.ts';

export const MESSAGE_LIMIT = 32 * 1024;
const QUEUE_MESSAGES = 8;
const QUEUE_BYTES = QUEUE_MESSAGES * MESSAGE_LIMIT;
export interface RelayLimits {
  pendingPerRoute: number; activePerRoute: number; totalActive: number; controlRoutes: number;
  upgradesPerIpPerMinute: number; opensPerRoutePerMinute: number; messagesPerSecond: number;
}
export interface RelayTimings { pendingMs: number; stallMs: number; heartbeatMs: number; staleMs: number; }
export interface RelayOptions {
  state?: RelayState; statePath?: string; bind?: string; port?: number; pathPrefix?: string;
  /** Explicit acknowledgement of an independently configured trusted HTTPS ingress. */
  externalTls?: boolean;
  tls?: { cert: string; key: string };
  /** May only lower policy limits; useful for isolated public-boundary tests. */
  limits?: Partial<RelayLimits>; timings?: Partial<RelayTimings>;
}
export interface RelayServer {
  readonly server: HttpServer | HttpsServer; readonly state: RelayState;
  start(): Promise<{ baseUrl: string }>; close(): Promise<void>;
  stats(): { controls: number; pending: number; active: number; sockets: number };
}
interface Grant { accessId: string; tokenHash: string; deviceId: string | null; expiresAt: number; maxStreams: 2 | 8; }
interface Route {
  id: string; generation: string; control: WebSocket; ready: boolean;
  grants: Map<string, Grant>; streams: Map<string, Tunnel>; lastSnapshotId?: string; snapshotRequests: number;
  expiryTimer?: ReturnType<typeof setTimeout>; readyTimer?: ReturnType<typeof setTimeout>;
}
interface Tunnel {
  id: string; route: Route; generation: string; accessId: string; joinHash: string | undefined;
  mobile: WebSocket; host?: WebSocket; active: boolean; closed: boolean; timer: ReturnType<typeof setTimeout>;
}
interface PeerMeta {
  ws: WebSocket; close: () => void; queued: number; bytes: number; blockedAt: number;
  lastPong: number; lastActivity: number; rateStart: number; rateCount: number;
  smallStart: number; smallCount: number; pingStart: number; pingCount: number; pongPending: boolean;
}
const DEFAULT_LIMITS: RelayLimits = { pendingPerRoute: 4, activePerRoute: 16, totalActive: 256, controlRoutes: 64,
  upgradesPerIpPerMinute: 120, opensPerRoutePerMinute: 120, messagesPerSecond: 4096 };
const DEFAULT_TIMINGS: RelayTimings = { pendingMs: 10000, stallMs: 10000, heartbeatMs: 15000, staleMs: 45000 };
function policy<T extends Record<string, number>>(defaults: T, supplied: Partial<T> | undefined): T {
  const result = { ...defaults };
  for (const [key, value] of Object.entries(supplied ?? {})) {
    if (!(key in defaults) || !Number.isInteger(value) || value < 1 || value > defaults[key]!) throw new Error('invalid_config');
    (result as Record<string, number>)[key] = value;
  }
  return result;
}
class RateBounds {
  private readonly values = new Map<string, { until: number; count: number }>();
  take(key: string, maximum: number, windowMs = 60000): boolean {
    const now = Date.now(); let value = this.values.get(key);
    if (!value || value.until <= now) {
      if (this.values.size >= 4096) {
        for (const [id, entry] of this.values) if (entry.until <= now) this.values.delete(id);
        if (this.values.size >= 4096) return false;
      }
      value = { until: now + windowMs, count: 0 }; this.values.set(key, value);
    }
    return ++value.count <= maximum;
  }
}
function binary(data: RawData): Buffer {
  if (!Buffer.isBuffer(data)) throw new Error('invalid_message');
  return data;
}
function object(value: unknown, names: string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('invalid_message');
  const record = value as Record<string, unknown>;
  if (Object.keys(record).length !== names.length || names.some(key => !(key in record))) throw new Error('invalid_message');
  return record;
}
function parseGrants(bytes: Buffer): { requestId: string; grants: Map<string, Grant> } {
  const value = object(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)), ['type', 'version', 'requestId', 'grants']);
  if (value.type !== 'grants' || value.version !== 1 || typeof value.requestId !== 'string' || !UUID.test(value.requestId) || !Array.isArray(value.grants) || value.grants.length > 64) throw new Error('invalid_message');
  const grants = new Map<string, Grant>(); const now = Date.now();
  for (const item of value.grants) {
    const grant = object(item, ['accessId', 'tokenHash', 'deviceId', 'expiresAt', 'maxStreams']);
    if (typeof grant.accessId !== 'string' || !UUID.test(grant.accessId) || grants.has(grant.accessId) ||
        typeof grant.tokenHash !== 'string' || !DIGEST.test(grant.tokenHash) ||
        (grant.deviceId !== null && (typeof grant.deviceId !== 'string' || !UUID.test(grant.deviceId))) ||
        !Number.isSafeInteger(grant.expiresAt) || Number(grant.expiresAt) <= now || Number(grant.expiresAt) > now + 366 * 86400000 ||
        grant.maxStreams !== (grant.deviceId === null ? 2 : 8)) throw new Error('invalid_message');
    grants.set(grant.accessId, { accessId: grant.accessId, tokenHash: grant.tokenHash, deviceId: grant.deviceId as string | null,
      expiresAt: Number(grant.expiresAt), maxStreams: grant.maxStreams as 2 | 8 });
  }
  return { requestId: value.requestId, grants };
}
function sameGrant(a: Grant | undefined, b: Grant | undefined): boolean {
  return Boolean(a && b && a.tokenHash === b.tokenHash && a.deviceId === b.deviceId && a.expiresAt === b.expiresAt && a.maxStreams === b.maxStreams);
}
function header(req: IncomingMessage, name: string): string {
  const value = req.headers[name];
  if (typeof value !== 'string') throw new Error('unauthorized');
  return value;
}
function token(req: IncomingMessage): string {
  const value = header(req, 'authorization');
  if (!value.startsWith('Bearer ') || !validToken(value.slice(7))) throw new Error('unauthorized');
  return value.slice(7);
}
function reject(socket: Duplex, status: number): void {
  const labels: Record<number, string> = { 400: 'Bad Request', 401: 'Unauthorized', 403: 'Forbidden', 404: 'Not Found', 409: 'Conflict', 429: 'Too Many Requests', 503: 'Service Unavailable' };
  if (!socket.destroyed) {
    socket.end(`HTTP/1.1 ${status} ${labels[status] ?? 'Bad Request'}\r\nConnection: close\r\nContent-Length: 0\r\nCache-Control: no-store\r\n\r\n`);
    (socket as Socket).setTimeout(1000, () => socket.destroy());
  }
}

export function createRelayServer(options: RelayOptions): RelayServer {
  const bind = options.bind ?? '127.0.0.1', port = options.port ?? 8088, prefix = options.pathPrefix ?? '';
  if ((!isIP(bind) && bind !== 'localhost') || !Number.isInteger(port) || port < 0 || port > 65535 ||
      !/^$|^(?:\/[A-Za-z0-9_-]{1,64}){1,8}$/.test(prefix) || prefix.length > 256 ||
      (options.tls && options.externalTls) ||
      (!options.tls && !options.externalTls && !['127.0.0.1', '::1', 'localhost'].includes(bind))) throw new Error('invalid_config');
  const limits = policy(DEFAULT_LIMITS as unknown as Record<string, number>, options.limits as Record<string, number> | undefined) as unknown as RelayLimits;
  const timings = policy(DEFAULT_TIMINGS as unknown as Record<string, number>, options.timings as Record<string, number> | undefined) as unknown as RelayTimings;
  if (timings.staleMs < timings.heartbeatMs || timings.heartbeatMs > 15000) throw new Error('invalid_config');
  const state = options.state ?? new RelayState(options.statePath ?? '');
  const routes = new Map<string, Route>(), peers = new Map<WebSocket, PeerMeta>();
  const rates = new RateBounds(); let closing = false, started: Promise<{ baseUrl: string }> | undefined;
  let sweep: ReturnType<typeof setInterval> | undefined; let heartbeat: ReturnType<typeof setInterval> | undefined;
  const webSockets = new WebSocketServer({ noServer: true, perMessageDeflate: false, autoPong: false,
    maxPayload: MESSAGE_LIMIT, maxFragments: 1, maxBufferedChunks: 8, allowSynchronousEvents: false });
  const handler = (req: IncomingMessage, res: import('node:http').ServerResponse) => {
    if (req.method === 'GET' && req.url === `${prefix}/healthz`) {
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end('{"ok":true}');
    } else { res.writeHead(404, { 'Content-Length': '0', 'Cache-Control': 'no-store' }); res.end(); }
  };
  const server = options.tls ? createHttpsServer({ cert: options.tls.cert, key: options.tls.key, minVersion: 'TLSv1.2', maxHeaderSize: 8192 }, handler) : createHttpServer({ maxHeaderSize: 8192 }, handler);
  server.headersTimeout = 10000; server.requestTimeout = 10000; server.keepAliveTimeout = 1000; server.maxHeadersCount = 32;
  server.maxConnections = 2048;
  server.on('connection', socket => {
    if (closing || !rates.take(`tcp:${socket.remoteAddress ?? 'unknown'}`, limits.upgradesPerIpPerMinute * 2)) { socket.destroy(); return; }
    (socket as Socket).setTimeout(10000, () => socket.destroy());
  });
  server.on('clientError', (_error, socket) => reject(socket, 400));

  function stats() {
    let active = 0, pending = 0;
    for (const route of routes.values()) for (const stream of route.streams.values()) stream.active ? active++ : pending++;
    return { controls: routes.size, pending, active, sockets: peers.size };
  }
  function endTunnel(stream: Tunnel): void {
    if (stream.closed) return; stream.closed = true;
    clearTimeout(stream.timer); stream.joinHash = undefined; stream.route.streams.delete(stream.id);
    peers.delete(stream.mobile); stream.mobile.terminate();
    if (stream.host) { peers.delete(stream.host); stream.host.terminate(); }
  }
  function endRoute(route: Route): void {
    if (routes.get(route.id) !== route) return;
    routes.delete(route.id); route.ready = false; route.grants.clear();
    if (route.expiryTimer) clearTimeout(route.expiryTimer);
    if (route.readyTimer) clearTimeout(route.readyTimer);
    for (const stream of route.streams.values()) endTunnel(stream);
    peers.delete(route.control); route.control.terminate();
  }
  function track(ws: WebSocket, fail: () => void): PeerMeta {
    const now = Date.now();
    const meta: PeerMeta = { ws, close: fail, queued: 0, bytes: 0, blockedAt: 0, lastPong: now, lastActivity: now,
      rateStart: now, rateCount: 0, smallStart: now, smallCount: 0, pingStart: now, pingCount: 0, pongPending: false };
    peers.set(ws, meta);
    ws.on('error', fail);
    ws.once('close', () => { peers.delete(ws); fail(); });
    ws.on('pong', () => { if (!smallRate(meta, true)) fail(); else meta.lastPong = Date.now(); });
    ws.on('ping', data => {
      if (!smallRate(meta, true) || meta.pongPending || ws.bufferedAmount > QUEUE_BYTES || ws.readyState !== WebSocket.OPEN) { fail(); return; }
      meta.pongPending = true;
      ws.pong(data, false, error => { meta.pongPending = false; if (error) fail(); });
    });
    return meta;
  }
  function smallRate(meta: PeerMeta, controlFrame = false): boolean {
    const now = Date.now();
    if (controlFrame) {
      if (now - meta.pingStart >= 1000) { meta.pingStart = now; meta.pingCount = 0; }
      return ++meta.pingCount <= 8;
    }
    if (now - meta.smallStart >= 1000) { meta.smallStart = now; meta.smallCount = 0; }
    return ++meta.smallCount <= 64;
  }
  function messageAllowed(meta: PeerMeta, bytes: Buffer, isBinary: boolean): boolean {
    const now = Date.now(); meta.lastActivity = now;
    if (now - meta.rateStart >= 1000) { meta.rateStart = now; meta.rateCount = 0; }
    return isBinary && bytes.length > 0 && bytes.length <= MESSAGE_LIMIT && ++meta.rateCount <= limits.messagesPerSecond && (bytes.length >= 256 || smallRate(meta));
  }
  function send(ws: WebSocket, bytes: Buffer, source?: WebSocket): boolean {
    const meta = peers.get(ws);
    if (!meta || ws.readyState !== WebSocket.OPEN || meta.queued >= QUEUE_MESSAGES || meta.bytes + bytes.length > QUEUE_BYTES || ws.bufferedAmount + bytes.length > QUEUE_BYTES + 128) { meta?.close(); return false; }
    meta.queued++; meta.bytes += bytes.length;
    if (source && meta.queued >= 2) { source.pause(); meta.blockedAt ||= Date.now(); }
    meta.blockedAt ||= Date.now();
    ws.send(bytes, { binary: true, fin: true, compress: false }, error => {
      meta.queued--; meta.bytes -= bytes.length;
      if (error) { meta.close(); return; }
      if (meta.queued === 0) meta.blockedAt = 0;
      if (source && meta.queued < 2 && source.readyState === WebSocket.OPEN) source.resume();
    });
    return true;
  }
  function json(ws: WebSocket, value: unknown): boolean { return send(ws, Buffer.from(JSON.stringify(value), 'utf8')); }
  function expireGrants(route: Route): void {
    const now = Date.now();
    for (const [id, grant] of route.grants) if (grant.expiresAt <= now) {
      route.grants.delete(id);
      for (const stream of route.streams.values()) if (stream.accessId === id) endTunnel(stream);
    }
  }
  function scheduleExpiry(route: Route): void {
    if (route.expiryTimer) clearTimeout(route.expiryTimer);
    const deadline = Math.min(...[...route.grants.values()].map(grant => grant.expiresAt));
    if (!Number.isFinite(deadline)) { route.expiryTimer = undefined; return; }
    route.expiryTimer = setTimeout(() => {
      if (routes.get(route.id) !== route) return;
      expireGrants(route); scheduleExpiry(route);
    }, Math.min(2147483647, Math.max(1, deadline - Date.now())));
    route.expiryTimer.unref();
  }
  function control(ws: WebSocket, routeId: string) {
    const route: Route = { id: routeId, generation: randomUUID(), control: ws, ready: false, grants: new Map(), streams: new Map(), snapshotRequests: 0 };
    route.readyTimer = setTimeout(() => endRoute(route), timings.pendingMs); route.readyTimer.unref();
    routes.set(routeId, route);
    const meta = track(ws, () => endRoute(route));
    ws.on('message', (data, isBinary) => {
      try {
        const bytes = binary(data);
        if (!messageAllowed(meta, bytes, isBinary) || !rates.take(`snapshot:${routeId}`, 120) || ++route.snapshotRequests > 1000000) throw new Error();
        const snapshot = parseGrants(bytes);
        if (snapshot.requestId === route.lastSnapshotId) throw new Error();
        route.lastSnapshotId = snapshot.requestId;
        for (const stream of route.streams.values()) if (!sameGrant(route.grants.get(stream.accessId), snapshot.grants.get(stream.accessId))) endTunnel(stream);
        route.grants = snapshot.grants; scheduleExpiry(route);
        if (!json(ws, { type: 'ack', requestId: snapshot.requestId })) return;
        route.ready = true;
        if (route.readyTimer) { clearTimeout(route.readyTimer); route.readyTimer = undefined; }
      } catch { endRoute(route); }
    });
    json(ws, { type: 'hello', version: 1, generation: route.generation });
  }
  function mobile(ws: WebSocket, route: Route, grant: Grant) {
    const stream: Tunnel = { id: randomUUID(), route, generation: route.generation, accessId: grant.accessId,
      joinHash: undefined, mobile: ws, active: false, closed: false, timer: setTimeout(() => endTunnel(stream), timings.pendingMs) };
    const joinToken = randomBytes(32).toString('base64url'); stream.joinHash = hashSecret(joinToken);
    stream.timer.unref(); route.streams.set(stream.id, stream);
    const meta = track(ws, () => endTunnel(stream));
    ws.on('message', (data, isBinary) => {
      try {
        const bytes = binary(data);
        if (!stream.active || !stream.host || !messageAllowed(meta, bytes, isBinary)) { endTunnel(stream); return; }
        send(stream.host, bytes, ws);
      } catch { endTunnel(stream); }
    });
    if (!json(route.control, { type: 'open', streamId: stream.id, joinToken, generation: route.generation })) endTunnel(stream);
  }
  function host(ws: WebSocket, stream: Tunnel) {
    stream.host = ws; stream.joinHash = undefined; clearTimeout(stream.timer);
    const meta = track(ws, () => endTunnel(stream));
    ws.on('message', (data, isBinary) => {
      try {
        const bytes = binary(data);
        if (!stream.active || !messageAllowed(meta, bytes, isBinary)) { endTunnel(stream); return; }
        send(stream.mobile, bytes, ws);
      } catch { endTunnel(stream); }
    });
    // Both READY writes are queued synchronously before any message event can forward bytes.
    if (!json(stream.mobile, { type: 'ready', version: 1 }) || !json(ws, { type: 'ready', version: 1 })) { endTunnel(stream); return; }
    stream.active = true;
  }
  server.on('upgrade', (req, socket, head) => {
    let status = 401;
    try {
      const ip = req.socket.remoteAddress ?? 'unknown';
      if (closing) { reject(socket, 503); return; }
      if (!rates.take(`upgrade:${ip}`, limits.upgradesPerIpPerMinute) || !rates.take('upgrade:global', 1200)) { reject(socket, 429); return; }
      if (req.rawHeaders.length / 2 > 32) { reject(socket, 400); return; }
      if (req.method !== 'GET' || req.headers.origin !== undefined || Object.keys(req.headers).some(key => key.startsWith('sec-fetch-')) || req.headers['sec-websocket-protocol'] !== undefined) { reject(socket, 403); return; }
      const credentialHeaders = new Set(['authorization', 'x-dsh-route', 'x-dsh-access', 'x-dsh-stream', 'x-dsh-join']);
      const seen = new Set<string>();
      for (let i = 0; i < req.rawHeaders.length; i += 2) {
        const name = req.rawHeaders[i]!.toLowerCase();
        if (credentialHeaders.has(name) && seen.has(name)) { reject(socket, 400); return; }
        seen.add(name);
      }
      const routeId = header(req, 'x-dsh-route'), secret = token(req);
      if (!ROUTE_ID.test(routeId)) throw new Error();
      const path = req.url;
      if (![`${prefix}/v1/control`, `${prefix}/v1/mobile`, `${prefix}/v1/host`].includes(path ?? '')) { reject(socket, 404); return; }
      if (head.length > 64 * 1024) { reject(socket, 400); return; }
      const counts = stats(); const route = routes.get(routeId);
      if (path === `${prefix}/v1/control`) {
        if (seen.has('x-dsh-access') || seen.has('x-dsh-stream') || seen.has('x-dsh-join') || !state.authenticateRoute(routeId, secret)) throw new Error();
        if (route) { reject(socket, 409); return; }
        if (counts.controls >= limits.controlRoutes) { reject(socket, 429); return; }
        (socket as Socket).setTimeout(0);
        webSockets.handleUpgrade(req, socket, head, ws => control(ws, routeId));
      } else if (path === `${prefix}/v1/mobile`) {
        if (seen.has('x-dsh-stream') || seen.has('x-dsh-join')) throw new Error();
        if (!route?.ready || !state.routeExists(routeId)) { reject(socket, 503); return; }
        const accessId = header(req, 'x-dsh-access'), grant = route.grants.get(accessId);
        if (!UUID.test(accessId) || !grant || grant.expiresAt <= Date.now() || !equalDigest(hashSecret(secret), grant.tokenHash)) throw new Error();
        let active = 0, pending = 0, own = 0;
        for (const stream of route.streams.values()) { stream.active ? active++ : pending++; if (stream.accessId === accessId) own++; }
        if (own >= grant.maxStreams || pending >= limits.pendingPerRoute || active + pending >= limits.activePerRoute || counts.active + counts.pending >= limits.totalActive || !rates.take(`open:${routeId}`, limits.opensPerRoutePerMinute)) { reject(socket, 429); return; }
        (socket as Socket).setTimeout(0);
        webSockets.handleUpgrade(req, socket, head, ws => mobile(ws, route, grant));
      } else {
        if (seen.has('x-dsh-access') || !state.authenticateRoute(routeId, secret) || !route?.ready) throw new Error();
        const id = header(req, 'x-dsh-stream'), join = header(req, 'x-dsh-join'), stream = route.streams.get(id);
        const grant = stream ? route.grants.get(stream.accessId) : undefined;
        if (!UUID.test(id) || !validToken(join) || !stream || stream.closed || stream.active || stream.host || stream.generation !== route.generation || !stream.joinHash || !equalDigest(hashSecret(join), stream.joinHash) || !grant || grant.expiresAt <= Date.now()) throw new Error();
        (socket as Socket).setTimeout(0);
        // handleUpgrade is synchronous: the join is consumed before another request can race it.
        webSockets.handleUpgrade(req, socket, head, ws => host(ws, stream));
      }
    } catch { reject(socket, status); }
  });
  const disposeRevoke = state.onRevoke(id => { const route = routes.get(id); if (route) endRoute(route); });
  function tick() {
    const now = Date.now();
    for (const route of routes.values()) {
      if (!state.routeExists(route.id)) { endRoute(route); continue; }
      for (const [id, grant] of route.grants) if (grant.expiresAt <= now) {
        route.grants.delete(id);
        for (const stream of route.streams.values()) if (stream.accessId === id) endTunnel(stream);
      }
    }
    for (const meta of peers.values()) {
      if ((meta.blockedAt && now - meta.blockedAt >= timings.stallMs) || now - meta.lastPong >= timings.staleMs) meta.close();
    }
  }
  const relay: RelayServer = { server, state, stats,
    start() {
      if (closing) return Promise.reject(new Error('relay_closed'));
      started ??= new Promise((resolve, rejectStart) => {
        const failed = () => rejectStart(new Error('relay_unavailable'));
        server.once('error', failed);
        server.listen(port, bind, () => {
          server.off('error', failed);
          const address = server.address();
          if (!address || typeof address === 'string') { rejectStart(new Error('relay_unavailable')); return; }
          sweep = setInterval(tick, Math.min(250, timings.stallMs, timings.staleMs)); sweep.unref();
          heartbeat = setInterval(() => {
            tick();
            for (const meta of peers.values()) {
              if (meta.ws.readyState === WebSocket.OPEN && meta.ws.bufferedAmount <= QUEUE_BYTES) meta.ws.ping(undefined, false, error => { if (error) meta.close(); });
            }
          }, timings.heartbeatMs); heartbeat.unref();
          resolve({ baseUrl: `${options.tls ? 'wss' : 'ws'}://${bind === '::1' ? '[::1]' : bind}:${address.port}${prefix}` });
        });
      });
      return started;
    },
    async close() {
      if (closing) return; closing = true;
      if (sweep) clearInterval(sweep); if (heartbeat) clearInterval(heartbeat); disposeRevoke();
      for (const route of routes.values()) endRoute(route);
      for (const meta of peers.values()) meta.ws.terminate();
      await new Promise<void>(resolve => webSockets.close(() => resolve()));
      if (server.listening) await new Promise<void>(resolve => { server.close(() => resolve()); server.closeAllConnections(); });
      if (!options.state) state.close();
    },
  };
  return relay;
}
export async function startRelayServer(options: RelayOptions): Promise<RelayServer> {
  const relay = createRelayServer(options);
  try { await relay.start(); return relay; } catch (error) { await relay.close(); throw error; }
}
