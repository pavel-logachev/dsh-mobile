import { randomUUID } from 'node:crypto';
import { connect } from 'node:net';
import type { Socket } from 'node:net';
import { WebSocket } from 'ws';
import type { RawData, ClientOptions } from 'ws';
import { HostError } from './errors.ts';
import { isRequestId } from './state.ts';
import type { HostState } from './state.ts';
import type { RelayConfiguration, RelayGrant } from './types.ts';

const MAX_CHUNK = 32768, MAX_QUEUE = 8;
export interface RelayConnectorOptions { relay: RelayConfiguration; state: HostState; targetPort: number; /** Programmatic test-only WS numeric-loopback escape. Never private config/release default. */ allowInsecureLoopback?: boolean }
export interface RelayConnectorStatus { connected: boolean; ready: boolean; generation: string | null; activeStreams: number; pendingStreams: number; lastAckAt: number | null }
export interface RelayConnector { start(): void; close(): Promise<void>; waitPublished(accessId: string, deadlineMs?: number): Promise<void>; status(): RelayConnectorStatus }
export function validateRelayUrl(value: string, allowInsecureLoopback = false): string {
  try {
    if (typeof value !== 'string' || value.length > 1024 || /[\x00-\x20\\?#@%]/.test(value)) throw new Error();
    const match = /^(wss|ws):\/\/([A-Za-z0-9.-]+)(?::([0-9]{1,5}))?((?:\/[A-Za-z0-9_-]{1,64}){0,4})\/?$/.exec(value);
    if (!match || (match[3] && (Number(match[3]) < 1 || Number(match[3]) > 65535))) throw new Error();
    const url = new URL(value);
    if (url.protocol !== 'wss:' && !(allowInsecureLoopback && url.protocol === 'ws:' && match[2] === '127.0.0.1')) throw new Error();
    if (url.username || url.password || url.search || url.hash) throw new Error();
    return url.href.replace(/\/$/, '');
  } catch { throw new HostError('invalid_config'); }
}
function asBuffer(data: RawData): Buffer { return Buffer.isBuffer(data) ? data : Array.isArray(data) ? Buffer.concat(data) : Buffer.from(data); }
function exact(message: Record<string, unknown>, fields: string[]): void { if (Object.keys(message).length !== fields.length || fields.some(field => !(field in message))) throw new HostError('unavailable'); }
function controlJson(data: RawData, binary: boolean): Record<string, unknown> {
  if (!binary) throw new HostError('unavailable');
  const buffer = asBuffer(data);
  if (buffer.length > MAX_CHUNK) throw new HostError('unavailable');
  try { const value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(buffer)) as unknown; if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(); return value as Record<string, unknown>; }
  catch { throw new HostError('unavailable'); }
}
/** Serialized sends bound queued messages before copying; no automatic data replay. */
class Sender {
  private queue: Buffer[] = [];
  private sending = false;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private ws: WebSocket;
  private failed: () => void;
  private drained: () => void;
  constructor(ws: WebSocket, failed: () => void, drained: () => void = () => {}) { this.ws = ws; this.failed = failed; this.drained = drained; }
  send(buffer: Buffer): boolean {
    if (buffer.length > MAX_CHUNK || this.queue.length + (this.sending ? 1 : 0) >= MAX_QUEUE || this.ws.bufferedAmount > MAX_CHUNK * MAX_QUEUE) { this.failed(); return false; }
    this.queue.push(buffer); this.pump(); return true;
  }
  private pump() {
    if (this.sending || !this.queue.length) return;
    if (this.ws.readyState !== WebSocket.OPEN) { this.failed(); return; }
    this.sending = true;
    const buffer = this.queue.shift()!;
    this.timer = setTimeout(this.failed, 10_000); this.timer.unref();
    this.ws.send(buffer, { binary: true, fin: true, compress: false }, error => {
      if (this.timer) clearTimeout(this.timer);
      this.sending = false;
      if (error) { this.failed(); return; }
      this.pump(); if (!this.sending && !this.queue.length) this.drained();
    });
  }
  stop() { if (this.timer) clearTimeout(this.timer); this.queue = []; }
}

/** Outbound opaque transport. The only TCP destination is fixed numeric loopback+targetPort. */
export function createRelayConnector(options: RelayConnectorOptions): RelayConnector {
  const url = validateRelayUrl(options.relay.url, options.allowInsecureLoopback === true);
  const { routeId, connectorToken } = options.relay, state = options.state;
  if (!/^[a-f0-9]{32}$/.test(routeId) || !/^[A-Za-z0-9_-]{43}$/.test(connectorToken) || !Number.isInteger(options.targetPort) || options.targetPort < 1 || options.targetPort > 65535) throw new HostError('invalid_config');
  let control: WebSocket | undefined, controlSender: Sender | undefined, started = false, closed = false, generation: string | null = null;
  let poll: ReturnType<typeof setInterval> | undefined, reconnect: ReturnType<typeof setTimeout> | undefined, heartbeat: ReturnType<typeof setInterval> | undefined, helloDeadline: ReturnType<typeof setTimeout> | undefined;
  let attempts = 0, ready = false, lastAckAt: number | null = null, lastControlMessage = 0, pongAt = 0;
  let inflight: { requestId: string; generation: string; grants: RelayGrant[]; serialized: string; deadline: ReturnType<typeof setTimeout> } | undefined;
  let committed = '', queued: string | undefined;
  const streams = new Map<string, { stop: () => void; active: () => boolean }>();
  const waiters = new Set<{ accessId: string; resolve: () => void; reject: (error: HostError) => void; timer: ReturnType<typeof setTimeout> }>();
  const wsOptions: ClientOptions = { allowSynchronousEvents: false, perMessageDeflate: false, autoPong: false, maxPayload: MAX_CHUNK, maxFragments: 1, maxBufferedChunks: MAX_QUEUE, followRedirects: false, handshakeTimeout: 10_000, maxHeaderSize: 8192 } as ClientOptions;
  function socket(path: string, headers: Record<string, string>): WebSocket {
    const ws = new WebSocket(url + path, { ...wsOptions, headers: { Authorization: `Bearer ${connectorToken}`, 'X-DSH-Route': routeId, ...headers } });
    let smallFrames = 0, smallEpoch = Date.now(), pongPending = false, messages = 0, smallMessages = 0, messageEpoch = Date.now();
    const smallAllowed = (bytes: number) => { if (Date.now() - smallEpoch >= 1000) { smallEpoch = Date.now(); smallFrames = 0; } return bytes <= 125 && ++smallFrames <= 8; };
    ws.on('ping', data => {
      if (!smallAllowed(data.length) || pongPending || ws.bufferedAmount > MAX_CHUNK * MAX_QUEUE) { ws.terminate(); return; }
      pongPending = true; ws.pong(data, true, (error?: Error) => { pongPending = false; if (error) ws.terminate(); });
    });
    ws.on('pong', data => { if (!smallAllowed(data.length)) ws.terminate(); });
    ws.on('message', (data, binary) => {
      if (Date.now() - messageEpoch >= 1000) { messageEpoch = Date.now(); messages = 0; smallMessages = 0; }
      const bytes = Buffer.isBuffer(data) ? data.length : MAX_CHUNK + 1;
      if (!binary || !bytes || bytes > MAX_CHUNK || ++messages > 4096 || (bytes < 256 && ++smallMessages > 64)) ws.terminate();
    });
    ws.on('error', () => {});
    return ws;
  }
  function rejectWaiters() { for (const waiter of waiters) { clearTimeout(waiter.timer); waiter.reject(new HostError('unavailable')); } waiters.clear(); }
  function resolveWaiters() {
    for (const waiter of waiters) {
      const publication = state.relayPublication(waiter.accessId);
      if (publication?.published && !publication.revoked && publication.expiresAt > Date.now() && publication.generation === generation && ready) { clearTimeout(waiter.timer); waiters.delete(waiter); waiter.resolve(); }
    }
  }
  function refresh() {
    if (!control || control.readyState !== WebSocket.OPEN || !generation) return;
    state.updateRelayStatus(routeId, true, ready, generation);
    const grants = state.relayGrantSnapshot(routeId);
    if (grants.length > 64) { lost(control); return; }
    const serialized = JSON.stringify(grants);
    queued = serialized;
    if (inflight || serialized === committed) { resolveWaiters(); return; }
    const requestId = randomUUID(), currentGeneration = generation;
    inflight = { requestId, generation: currentGeneration, grants, serialized, deadline: setTimeout(() => { if (control) lost(control); }, 5_000) };
    inflight.deadline.unref();
    controlSender!.send(Buffer.from(JSON.stringify({ type: 'grants', version: 1, requestId, grants })));
  }
  function lost(ws: WebSocket) {
    if (control !== ws) return;
    control = undefined; generation = null; ready = false; committed = ''; queued = undefined;
    if (inflight) clearTimeout(inflight.deadline); inflight = undefined;
    controlSender?.stop(); controlSender = undefined;
    if (heartbeat) clearInterval(heartbeat);
    if (helloDeadline) clearTimeout(helloDeadline);
    for (const stream of streams.values()) stream.stop();
    state.updateRelayStatus(routeId, false, false, null); rejectWaiters();
    ws.terminate();
    if (!closed) { const delay = Math.min(30_000, 500 * 2 ** Math.min(attempts++, 6)) + Math.floor(Math.random() * 250); reconnect = setTimeout(openControl, delay); reconnect.unref(); }
  }
  function openControl() {
    if (closed) return;
    const ws = socket('/v1/control', {}); control = ws;
    ws.once('close', () => lost(ws));
    ws.once('unexpected-response', () => { ws.terminate(); lost(ws); });
    ws.once('open', () => {
      if (closed || control !== ws) { ws.terminate(); return; }
      helloDeadline = setTimeout(() => lost(ws), 10_000); helloDeadline.unref();
      lastControlMessage = Date.now(); pongAt = Date.now();
      controlSender = new Sender(ws, () => lost(ws));
      heartbeat = setInterval(() => {
        if (Date.now() - Math.max(lastControlMessage, pongAt) > 45_000 || ws.bufferedAmount > MAX_CHUNK * MAX_QUEUE) { lost(ws); return; }
        ws.ping(Buffer.alloc(0), true, (error?: Error) => { if (error) lost(ws); });
      }, 15_000); heartbeat.unref();
    });
    ws.on('pong', () => { pongAt = Date.now(); });
    ws.on('message', (data, binary) => {
      try {
        if (control !== ws) return;
        const message = controlJson(data, binary); lastControlMessage = Date.now();
        if (!generation) {
          exact(message, ['type', 'version', 'generation']);
          if (message.type !== 'hello' || message.version !== 1 || !isRequestId(message.generation)) throw new HostError('unavailable');
          if (helloDeadline) clearTimeout(helloDeadline);
          generation = message.generation; attempts = 0; state.updateRelayStatus(routeId, true, false, generation); refresh(); return;
        }
        if (message.type === 'ack') {
          exact(message, ['type', 'requestId']);
          if (!inflight || message.requestId !== inflight.requestId || inflight.generation !== generation) throw new HostError('unavailable');
          clearTimeout(inflight.deadline);
          state.acknowledgeRelaySnapshot(routeId, inflight.grants, generation);
          committed = inflight.serialized; inflight = undefined; lastAckAt = Date.now(); ready = true;
          state.updateRelayStatus(routeId, true, true, generation); resolveWaiters();
          if (queued !== committed) refresh(); return;
        }
        if (message.type === 'open') exact(message, ['type', 'streamId', 'joinToken', 'generation']);
        if (message.type === 'open' && ready && isRequestId(message.streamId) && message.generation === generation && typeof message.joinToken === 'string' && /^[A-Za-z0-9_-]{43}$/.test(message.joinToken)) { openTunnel(message.streamId, message.joinToken, generation); return; }
        throw new HostError('unavailable');
      } catch { lost(ws); }
    });
  }
  function openTunnel(streamId: string, joinToken: string, expectedGeneration: string) {
    if (streams.has(streamId) || streams.size >= 20 || [...streams.values()].filter(stream => !stream.active()).length >= 4 || [...streams.values()].filter(stream => stream.active()).length >= 16) { if (control) lost(control); return; }
    let ws: WebSocket | undefined, tcp: Socket | undefined, sender: Sender | undefined, active = false, stopped = false;
    let toTcp: Buffer[] = [], writing = false, stall: ReturnType<typeof setTimeout> | undefined, lastActivity = Date.now();
    const timeout = setTimeout(stop, 10_000); timeout.unref();
    const heartbeatTimer = setInterval(() => { if (ws?.readyState === WebSocket.OPEN) { if (Date.now() - lastActivity > 45_000 || ws.bufferedAmount > MAX_CHUNK * MAX_QUEUE) { stop(); return; } ws.ping(Buffer.alloc(0), true, (error?: Error) => { if (error) stop(); }); } }, 15_000); heartbeatTimer.unref();
    function stop() {
      if (stopped) return; stopped = true; clearTimeout(timeout); clearInterval(heartbeatTimer); if (stall) clearTimeout(stall);
      sender?.stop(); toTcp = []; tcp?.destroy(); ws?.terminate(); streams.delete(streamId);
    }
    function writeTcp() {
      if (writing || !toTcp.length || !tcp || stopped) return;
      writing = true; const buffer = toTcp.shift()!;
      stall = setTimeout(stop, 10_000); stall.unref();
      tcp.write(buffer, error => {
        if (stall) clearTimeout(stall); writing = false;
        if (error) { stop(); return; }
        writeTcp(); if (!writing && !toTcp.length) ws?.resume();
      });
    }
    streams.set(streamId, { stop, active: () => active });
    tcp = connect({ host: '127.0.0.1', port: options.targetPort }); tcp.pause(); tcp.setNoDelay();
    tcp.once('error', stop); tcp.once('close', stop);
    tcp.once('connect', () => {
      if (stopped || expectedGeneration !== generation) { stop(); return; }
      ws = socket('/v1/host', { 'X-DSH-Stream': streamId, 'X-DSH-Join': joinToken });
      ws.once('close', stop); ws.once('error', stop);
      ws.once('open', () => { sender = new Sender(ws!, stop, () => { if (active && !stopped) tcp?.resume(); }); });
      ws.on('pong', () => { lastActivity = Date.now(); });
      ws.on('message', (data, binary) => {
        try {
          if (stopped || expectedGeneration !== generation) { stop(); return; }
          lastActivity = Date.now();
          if (!active) {
            const message = controlJson(data, binary); exact(message, ['type', 'version']);
            if (message.type !== 'ready' || message.version !== 1 || [...streams.values()].filter(stream => stream.active()).length >= 16) throw new HostError('unavailable');
            active = true; clearTimeout(timeout); tcp!.resume(); return;
          }
          if (!binary) throw new HostError('unavailable');
          const buffer = asBuffer(data);
          if (buffer.length > MAX_CHUNK || toTcp.length + (writing ? 1 : 0) >= MAX_QUEUE || tcp!.writableLength > MAX_CHUNK * MAX_QUEUE) throw new HostError('unavailable');
          toTcp.push(buffer); ws!.pause(); writeTcp();
        } catch { stop(); }
      });
    });
    tcp.on('data', chunk => {
      if (!active || !sender || stopped) { stop(); return; }
      tcp!.pause(); lastActivity = Date.now();
      for (let offset = 0; offset < chunk.length; offset += MAX_CHUNK) { if (!sender.send(chunk.subarray(offset, offset + MAX_CHUNK))) return; }
    });
  }
  return {
    start() { if (started || closed) return; started = true; openControl(); poll = setInterval(() => { try { refresh(); } catch { if (control) lost(control); } }, 500); poll.unref(); },
    async close() { if (closed) return; closed = true; if (poll) clearInterval(poll); if (reconnect) clearTimeout(reconnect); if (heartbeat) clearInterval(heartbeat); if (control) lost(control); for (const stream of streams.values()) stream.stop(); rejectWaiters(); },
    waitPublished(accessId, deadlineMs = 5000) {
      if (closed || !isRequestId(accessId) || deadlineMs < 1 || deadlineMs > 5000) return Promise.reject(new HostError('unavailable'));
      return new Promise<void>((resolve, reject) => {
        const waiter = { accessId, resolve, reject, timer: setTimeout(() => { waiters.delete(waiter); reject(new HostError('unavailable')); }, deadlineMs) };
        waiters.add(waiter); try { refresh(); resolveWaiters(); } catch { waiters.delete(waiter); clearTimeout(waiter.timer); reject(new HostError('unavailable')); }
      });
    },
    status() { return { connected: control?.readyState === WebSocket.OPEN && generation !== null, ready, generation, activeStreams: [...streams.values()].filter(stream => stream.active()).length, pendingStreams: [...streams.values()].filter(stream => !stream.active()).length, lastAckAt }; },
  };
}
