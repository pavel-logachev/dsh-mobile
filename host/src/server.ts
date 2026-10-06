import { NotificationFeed } from './notifications.ts';
import { randomBytes } from 'node:crypto';
import { createServer as createHttpServer } from 'node:http';
import type { IncomingMessage, Server as HttpServer, ServerResponse } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import type { Server as HttpsServer } from 'node:https';
import { prepareConfiguration } from './config.ts';
import { errorEnvelope, HostError, publicError } from './errors.ts';
import { HostState, hashSecret, allowsWorkspace } from './state.ts';
import { createWorkspaceSource } from './workspace-source.ts';
import type { WorkspaceSource } from './workspace-source.ts';
import type { AuthorizedDevice, StoredCommand } from './state.ts';
import type { HostAdapter, HostConfiguration, PreparedHostConfiguration, HostSession, HostSnapshot } from './types.ts';
import { isRequestId } from './state.ts';
import { createRelayConnector } from './relay-connector.ts';
import type { RelayConnector } from './relay-connector.ts';

export interface HostServerOptions {
  config: HostConfiguration;
  adapter: HostAdapter;
  /** Shared owner source, required for registry mode (no implicit discovery). */
  workspaceSource?: WorkspaceSource;
  /** Injected state is caller-owned; omitted state is closed by host.close(). */
  state?: HostState;
  /** Tests may shorten this; production heartbeats never exceed twenty seconds. */
  heartbeatMs?: number;
  /** Test-only loopback outer WS. Inner listener always remains HTTPS in relay mode. */
  allowInsecureRelayLoopback?: boolean;
}
export interface MobileHostServer {
  readonly server: HttpServer | HttpsServer;
  readonly state: HostState;
  readonly config: PreparedHostConfiguration;
  readonly relay?: RelayConnector;
  start(): Promise<{ baseUrl: string }>;
  close(): Promise<void>;
}
const BODY_LIMIT = 64 * 1024;

class RateLimiter {
  private readonly entries = new Map<string, { count: number; until: number }>();
  take(key: string, limit: number, windowMs = 60_000): void {
    const now = Date.now();
    let entry = this.entries.get(key);
    if (!entry || entry.until <= now) {
      if (this.entries.size >= 1024) {
        for (const [id, value] of this.entries) if (value.until <= now) this.entries.delete(id);
        if (this.entries.size >= 1024) throw new HostError('rate_limited');
      }
      entry = { count: 0, until: now + windowMs }; this.entries.set(key, entry);
    }
    if (++entry.count > limit) throw new HostError('rate_limited');
  }
}

function sendJson(res: ServerResponse, status: number, value: unknown): void {
  if (res.destroyed || res.writableEnded) return;
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  res.end(JSON.stringify(value));
}
function keys(body: Record<string, unknown>, allowed: string[], required = allowed): void {
  if (Object.keys(body).some(key => !allowed.includes(key)) || required.some(key => !(key in body))) throw new HostError('invalid_request');
}
async function bodyJson(req: IncomingMessage, signal: AbortSignal): Promise<Record<string, unknown>> {
  if (req.headers['content-encoding'] && req.headers['content-encoding'] !== 'identity') throw new HostError('unsupported_media_type');
  if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(req.headers['content-type'] ?? '')) throw new HostError('unsupported_media_type');
  const length = req.headers['content-length'];
  if (length && (!/^\d+$/.test(length) || Number(length) > BODY_LIMIT)) throw new HostError('payload_too_large');
  const chunks: Buffer[] = []; let size = 0;
  // Early overflow must not auto-destroy IncomingMessage before the safe 413 is sent.
  for await (const chunk of req.iterator({ destroyOnReturn: false })) {
    signal.throwIfAborted();
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk as string);
    size += buffer.length;
    if (size > BODY_LIMIT) throw new HostError('payload_too_large');
    chunks.push(buffer);
  }
  signal.throwIfAborted();
  try {
    const text = new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks));
    const body = JSON.parse(text) as unknown;
    if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error();
    return body as Record<string, unknown>;
  } catch { throw new HostError('invalid_request'); }
}

/** Prepares a private server without binding; safe for in-process plugin composition. */
export async function createHostServer(options: HostServerOptions): Promise<MobileHostServer> {
  const prepared = await prepareConfiguration(options.config, { allowInsecureRelayLoopback: options.allowInsecureRelayLoopback === true });
  const config = prepared.config, adapter = options.adapter;
  const workspaceSource = options.workspaceSource ?? await createWorkspaceSource(config);
  if (options.heartbeatMs !== undefined && (!Number.isInteger(options.heartbeatMs) || options.heartbeatMs < 10 || options.heartbeatMs > 20_000)) throw new HostError('invalid_config');
  const state = options.state ?? new HostState(config.statePath);
  if (state.path !== config.statePath) { if (!options.state) state.close(); throw new HostError('invalid_config'); }
  const relay = config.relay ? createRelayConnector({ relay: config.relay, state, targetPort: config.port, allowInsecureLoopback: options.allowInsecureRelayLoopback === true }) : undefined;
  let notifications: NotificationFeed | undefined;
  const notificationStreams = new Set<string>();
  const limits = new RateLimiter();
  const shutdown = new AbortController();
  let closing = false, started: Promise<{ baseUrl: string }> | undefined;
  let claimed = false;

  const dispatches = new Map<string, { deviceId: string; requestId: string; completion: Promise<StoredCommand | undefined> }>();
  type Command = { operation: 'create'; workspaceId: string; presetId?: string; requestId: string } | { operation: 'prompt'; sessionId: string; text: string; requestId: string; workspaceId: string } | { operation: 'cancel'; sessionId: string; requestId: string; workspaceId: string; expectedCursor: number };
  function dispatch(deviceId: string, command: Command): Promise<StoredCommand | undefined> {
    const controller = new AbortController();
    return (async () => {
      let dispatched = false;
      try {
        if (closing) throw new HostError('unavailable');
        // This signal is host-owned, not tied to HTTP lifetime. Admission is already durable.
        await checkScope(deviceId, command.workspaceId, true, command.operation !== 'create');
        if (command.operation !== 'create') {
          const snapshot = await sessionSnapshot(command.sessionId, deviceId, controller.signal);
          if (snapshot.session.workspaceId !== command.workspaceId) throw new HostError('forbidden');
          if (command.operation === 'cancel' && (!snapshot.session.running || snapshot.cursor !== command.expectedCursor)) throw new HostError('conflict');
        }
        if (closing) throw new HostError('unavailable');
        await checkScope(deviceId, command.workspaceId, true, command.operation !== 'create');
        dispatched = true;
        let result: { sessionId?: string } | undefined;
        if (command.operation === 'create') result = await adapter.createSession({ workspaceId: command.workspaceId, requestId: command.requestId, ...(command.presetId ? { presetId: command.presetId } : {}) }, controller.signal, command.workspaceId);
        else if (command.operation === 'prompt') await adapter.prompt(command.sessionId, command.text, command.requestId, controller.signal, command.workspaceId);
        else await adapter.cancel(command.sessionId, controller.signal, command.expectedCursor, command.workspaceId);
        if (closing) return undefined;
        return state.finishCommand(deviceId, command.requestId, 'accepted', { ...(result?.sessionId ? { result: { sessionId: result.sessionId } } : {}), httpStatus: command.operation === 'create' ? 201 : 200 });
      } catch (error) {
        if (closing) return undefined;
        if ((!dispatched || error instanceof HostError) && error instanceof HostError && error.status >= 400 && error.status < 500) {
          return state.finishCommand(deviceId, command.requestId, 'rejected', { error: { code: error.code, message: error.message }, httpStatus: error.status });
        }
        return state.finishCommand(deviceId, command.requestId, 'uncertain', { error: { code: 'delivery_uncertain', message: 'Delivery could not be confirmed. Do not automatically resend this action.' }, httpStatus: 202 });
      }
    })();
  }
  async function responseReceipt(deviceId: string, requestId: string, completion: Promise<StoredCommand | undefined>): Promise<StoredCommand> {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      await Promise.race([completion, new Promise<void>(resolve => { timer = setTimeout(resolve, 25); })]);
      return state.getCommand(deviceId, requestId)!;
    } finally { if (timer) clearTimeout(timer); }
  }

  const cursors = new Map<string, { deviceId: string; workspaceId: string | undefined; offset: number; expiresAt: number }>();
  function freshDevice(deviceId: string): AuthorizedDevice {
    const device = state.getDevice(deviceId);
    if (!device) throw new HostError('unauthorized');
    return device;
  }
  function checkKnownScope(deviceId: string, workspaceId: string, workspaceIds: ReadonlySet<string>, execute = false, hidden = false): AuthorizedDevice {
    const device = freshDevice(deviceId);
    if (!workspaceIds.has(workspaceId) || !allowsWorkspace(device.grants.readWorkspaceIds, workspaceId)) throw new HostError(hidden ? 'not_found' : 'forbidden');
    if (execute && !allowsWorkspace(device.grants.executeWorkspaceIds, workspaceId)) throw new HostError('forbidden');
    return device;
  }
  async function workspaceIds(): Promise<ReadonlySet<string>> { return new Set((await workspaceSource.list()).map(item => item.id)); }
  async function checkScope(deviceId: string, workspaceId: string, execute = false, hidden = false): Promise<AuthorizedDevice> {
    return checkKnownScope(deviceId, workspaceId, await workspaceIds(), execute, hidden);
  }
  function sessionForDevice(session: HostSession, deviceId: string, ids: ReadonlySet<string>) {
    const device = checkKnownScope(deviceId, session.workspaceId, ids, false, true);
    if (typeof session.id !== 'string' || typeof session.title !== 'string' || !Number.isFinite(session.updatedAt) || typeof session.running !== 'boolean') throw new HostError('internal_error');
    return { id: session.id, title: session.title.slice(0, 256), workspaceId: session.workspaceId, updatedAt: session.updatedAt, running: session.running, canExecute: allowsWorkspace(device.grants.executeWorkspaceIds, session.workspaceId) };
  }
  async function snapshotForDevice(snapshot: HostSnapshot, deviceId: string, expectedId: string) {
    if (!snapshot || snapshot.session.id !== expectedId || !Array.isArray(snapshot.messages) || !Number.isSafeInteger(snapshot.cursor) || snapshot.cursor < -1 || !['idle', 'running', 'waiting', 'unknown'].includes(snapshot.activity)) throw new HostError('internal_error');
    const ids = await workspaceIds();
    if (workspaceSource.archivedSessionIds().has(expectedId)) throw new HostError('not_found');
    const session = sessionForDevice(snapshot.session, deviceId, ids);
    const messages = snapshot.messages.slice(-100).map(message => {
      if (!message || typeof message.id !== 'string' || typeof message.text !== 'string' || !['user', 'assistant', 'system'].includes(message.role) || !Number.isFinite(message.createdAt)) throw new HostError('internal_error');
      if ((message.kind !== undefined && !['message', 'agent_event', 'context'].includes(message.kind)) ||
          (message.serviceText !== undefined && typeof message.serviceText !== 'string')) throw new HostError('internal_error');
      return { ...(message.kind !== undefined ? { kind: message.kind } : {}), ...(message.serviceText !== undefined ? { serviceText: message.serviceText } : {}), id: message.id, role: message.role, text: message.text, createdAt: message.createdAt, ...(message.requestId && isRequestId(message.requestId) ? { requestId: message.requestId } : {}), ...(message.provisional === true ? { provisional: true } : {}) };
    });
    const detail = snapshot.activityDetail;
    if (detail !== undefined && (!detail || !Number.isSafeInteger(detail.turnStartedAt) || detail.turnStartedAt < 0 ||
        (detail.tool !== undefined && (typeof detail.tool !== 'string' || !/^[A-Za-z0-9_.:/-]{1,128}$/.test(detail.tool))))) throw new HostError('internal_error');
    const activityDetail = detail && ['running', 'waiting'].includes(snapshot.activity)
      ? { turnStartedAt: detail.turnStartedAt, ...(detail.tool !== undefined ? { tool: detail.tool } : {}) } : undefined;
    const result = { ...(activityDetail ? { activityDetail } : {}), session, messages, cursor: snapshot.cursor, hasMore: snapshot.hasMore === true || snapshot.messages.length > 100, activity: snapshot.activity, ...(snapshot.activity === 'waiting' || snapshot.activity === 'unknown' ? { notice: 'Return to the desktop to check this task.' } : {}) };
    // Keep complete messages and Unicode intact. Observation is a bounded latest-history view.
    while (Buffer.byteLength(JSON.stringify(result), 'utf8') > 2 * 1024 * 1024) {
      if (result.messages.length <= 1) throw new HostError('payload_too_large');
      result.messages.shift(); result.hasMore = true;
      result.notice = 'Only recent complete messages are shown; open the desktop for earlier history.';
    }
    return result;
  }
  async function sessionSnapshot(sessionId: string, deviceId: string, signal: AbortSignal): Promise<HostSnapshot> {
    const list = await adapter.listSessions(signal);
    signal.throwIfAborted();
    const session = list.find(item => item.id === sessionId);
    if (!session) throw new HostError('not_found');
    if (workspaceSource.archivedSessionIds().has(sessionId)) throw new HostError('not_found');
    await checkScope(deviceId, session.workspaceId, false, true);
    const snapshot = await adapter.snapshot(sessionId, signal);
    signal.throwIfAborted();
    if (snapshot.session.workspaceId !== session.workspaceId) throw new HostError('not_found');
    if (workspaceSource.archivedSessionIds().has(sessionId)) throw new HostError('not_found');
    await checkScope(deviceId, snapshot.session.workspaceId, false, true);
    return snapshot;
  }

  const streams = new Set<{ deviceId: string; stop: () => void }>();
  const streamCounts = new Map<string, number>();
  const onRevoke = state.onRevoke(deviceId => { for (const stream of streams) if (stream.deviceId === deviceId) stream.stop(); });
  async function streamSnapshots(sessionId: string, deviceId: string, res: ServerResponse, requestSignal: AbortSignal): Promise<void> {
    if ((streamCounts.get(deviceId) ?? 0) >= 3 || streams.size >= 32) throw new HostError('rate_limited');
    streamCounts.set(deviceId, (streamCounts.get(deviceId) ?? 0) + 1);
    const controller = new AbortController();
    let timer: ReturnType<typeof setInterval> | undefined, closed = false;
    const stop = () => {
      if (closed) return;
      closed = true; controller.abort();
      if (timer) clearInterval(timer);
      streams.delete(registration);
      const remaining = (streamCounts.get(deviceId) ?? 1) - 1;
      if (remaining) streamCounts.set(deviceId, remaining); else streamCounts.delete(deviceId);
      requestSignal.removeEventListener('abort', stop);
      res.off('close', stop);
      if (res.headersSent && !res.destroyed) res.end();
    };
    const registration = { deviceId, stop }; streams.add(registration);
    requestSignal.addEventListener('abort', stop, { once: true }); res.once('close', stop);
    if (requestSignal.aborted) { stop(); return; }
    let lastFrame = '';
    let blockedSince = 0;
    let latest: HostSnapshot | undefined, frameVersion = 0;
    async function frame(snapshot: HostSnapshot, permissionRecheck = false) {
      controller.signal.throwIfAborted();
      // Record authoritative watch replacements before asynchronous source checks.
      // A slower timer recheck must never restore an obsolete transcript/frame.
      if (!permissionRecheck) latest = snapshot;
      const version = ++frameVersion;
      const safe = await snapshotForDevice(snapshot, deviceId, sessionId);
      controller.signal.throwIfAborted();
      if (version !== frameVersion || snapshot !== latest) return;
      const json = JSON.stringify(safe);
      if (json === lastFrame) return;
      const data = `event: snapshot\ndata: ${json}\n\n`;
      if (res.writableLength + Buffer.byteLength(data) > 2 * 1024 * 1024 + 1024) throw new HostError('payload_too_large');
      lastFrame = json;
      if (!res.write(data)) {
        blockedSince = Date.now();
        await new Promise<void>((resolve, reject) => {
          const cleanup = () => { clearTimeout(wait); res.off('drain', drained); controller.signal.removeEventListener('abort', aborted); };
          const drained = () => { cleanup(); blockedSince = 0; resolve(); };
          const aborted = () => { cleanup(); reject(new HostError('unavailable')); };
          const wait = setTimeout(() => { cleanup(); reject(new HostError('unavailable')); }, 5_000);
          res.once('drain', drained); controller.signal.addEventListener('abort', aborted, { once: true });
        });
      }
    }
    try {
      const first = await sessionSnapshot(sessionId, deviceId, controller.signal);
      controller.signal.throwIfAborted();
      await checkScope(deviceId, first.session.workspaceId, false, true);
      await snapshotForDevice(first, deviceId, sessionId);
      res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Accel-Buffering': 'no' });
      await frame(first);
      const heartbeatMs = options.heartbeatMs ?? 15_000;
      let lastHeartbeat = Date.now();
      // SQLite/source rechecks see independent CLI grant narrowing or registry removal.
      // Republish cached authoritative text when canExecute changes, even on an idle watch.
      let rechecking = false;
      timer = setInterval(() => {
        if (rechecking || closed) return;
        rechecking = true;
        void (async () => {
          try {
            await checkScope(deviceId, first.session.workspaceId, false, true);
            if (workspaceSource.archivedSessionIds().has(sessionId)) throw new HostError('not_found');
            if (blockedSince && Date.now() - blockedSince >= 5_000) { stop(); return; }
            if (latest && !blockedSince) await frame(latest, true);
            if (closed) return;
            if (Date.now() - lastHeartbeat >= heartbeatMs && !blockedSince) {
              lastHeartbeat = Date.now();
              if (!res.write(': heartbeat\n\n')) blockedSince = Date.now();
            }
          } catch { stop(); }
          finally { rechecking = false; }
        })();
      }, Math.min(heartbeatMs, 1000));
      timer.unref();
      void (async () => {
        try {
          for await (const snapshot of adapter.watch(sessionId, controller.signal)) {
            if (closed) break;
            if (snapshot.session.workspaceId !== first.session.workspaceId) throw new HostError('not_found');
            await frame(snapshot);
          }
        } catch { /* Never stream raw upstream errors, stacks, tokens, or host paths. */ }
        finally { stop(); }
      })();
    } catch (error) { stop(); throw error; }
  }

  async function streamNotifications(deviceId: string, after: string | undefined, res: ServerResponse, signal: AbortSignal) {
    if (!notifications) throw new HostError('unavailable');
    if (notificationStreams.has(deviceId) || (streamCounts.get(deviceId) ?? 0) >= 3 || streams.size >= 32) throw new HostError('rate_limited');
    notificationStreams.add(deviceId); streamCounts.set(deviceId, (streamCounts.get(deviceId) ?? 0) + 1);
    let closed = false, timer: ReturnType<typeof setInterval> | undefined, working = false;
    const stop = () => {
      if (closed) return; closed = true;
      if (timer) clearInterval(timer); streams.delete(registration); notificationStreams.delete(deviceId);
      const count = (streamCounts.get(deviceId) ?? 1) - 1; if (count) streamCounts.set(deviceId, count); else streamCounts.delete(deviceId);
      signal.removeEventListener('abort', stop); res.off('close', stop); if (res.headersSent && !res.destroyed) res.end();
    };
    const registration = { deviceId, stop }; streams.add(registration);
    signal.addEventListener('abort', stop, { once: true }); res.once('close', stop);
    let cursor = after, heartbeat = Date.now(), lastCoverage = '';
    async function send(first = false) {
      if (working || closed) return; working = true;
      try {
        const page = await notifications!.page(deviceId, cursor, 100);
        signal.throwIfAborted(); if (closed) return;
        if (!res.headersSent) res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store', 'X-Accel-Buffering': 'no' });
        if (first || page.items.length || page.resetRequired || page.hasMore || page.coverage !== lastCoverage) {
          const frame = `event: notification-page\nid: ${page.nextCursor}\ndata: ${JSON.stringify(page)}\n\n`;
          if (Buffer.byteLength(frame) > 128 * 1024 || res.writableLength > 128 * 1024 || !res.write(frame)) { stop(); return; }
          cursor = page.nextCursor; lastCoverage = page.coverage;
        }
        if (Date.now() - heartbeat >= (options.heartbeatMs ?? 15000)) { heartbeat = Date.now(); if (!res.write(': heartbeat\n\n')) stop(); }
      } catch (error) { stop(); if (first) throw error; }
      finally { working = false; }
    }
    try { await send(true); if (!closed) { timer = setInterval(() => { void send(); }, 1000); timer.unref(); } }
    catch (error) { stop(); throw error; }
  }

  function authorized(req: IncomingMessage): AuthorizedDevice {
    const header = req.headers.authorization;
    if (typeof header !== 'string' || !/^Bearer [A-Za-z0-9_-]{43}$/.test(header)) throw new HostError('unauthorized');
    const device = state.authenticate(header.slice(7));
    if (!device) throw new HostError('unauthorized');
    return device;
  }

  async function route(req: IncomingMessage, res: ServerResponse, signal: AbortSignal): Promise<void> {
    if (closing) throw new HostError('unavailable');
    // No browser surface, cookies, CORS or query credentials. A native client has no Origin.
    if (req.headers.origin !== undefined || req.headers['sec-fetch-site'] !== undefined) throw new HostError('forbidden');
    if (!req.url?.startsWith('/v1/') || req.url.length > 2048 || /[#\x00-\x20\\]/.test(req.url)) throw new HostError('invalid_request');
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname + url.search !== req.url) throw new HostError('invalid_request');
    const allowedQuery = req.method === 'GET' ? url.pathname === '/v1/sessions' ? ['workspaceId', 'limit', 'cursor'] : url.pathname === '/v1/notification-events' ? ['after', 'limit'] : url.pathname === '/v1/notification-events/stream' ? ['after'] : [] : [];
    for (const key of url.searchParams.keys()) if (!allowedQuery.includes(key) || url.searchParams.getAll(key).length !== 1) throw new HostError('invalid_request');
    const segments = url.pathname.split('/').slice(2).map(segment => {
      try {
        const decoded = decodeURIComponent(segment);
        if (!decoded || Buffer.byteLength(decoded) > 256 || /[\/\\\x00-\x1f\x7f]/.test(decoded) || decoded === '.' || decoded === '..') throw new Error();
        return decoded;
      } catch { throw new HostError('invalid_request'); }
    });
    if (req.method === 'POST' && url.pathname === '/v1/pairings') {
      limits.take('pair-global', 30); limits.take(`pair:${req.socket.remoteAddress ?? 'unknown'}`, 10);
      const body = await bodyJson(req, signal); keys(body, ['pairingToken', 'deviceName']);
      if (typeof body.pairingToken !== 'string' || typeof body.deviceName !== 'string') throw new HostError('invalid_request');
      const remoteRoute = state.remotePairingRoute(body.pairingToken);
      if (remoteRoute) {
        if (!relay || remoteRoute !== config.relay?.routeId) throw new HostError('unauthorized');
        const result = state.consumeRemotePairing(body.pairingToken, body.deviceName, remoteRoute);
        try { await relay.waitPublished(result.relayAccess.accessId); }
        catch { state.revokeRelayGrant(result.relayAccess.accessId); throw new HostError('unavailable'); }
        sendJson(res, 201, { ...result, hostName: config.hostName, protocolVersion: 1 }); return;
      }
      const result = state.consumePairing(body.pairingToken, body.deviceName);
      sendJson(res, 201, { ...result, hostName: config.hostName, protocolVersion: 1 }); return;
    }
    const device = authorized(req);
    limits.take(`device:${device.deviceId}`, 120);
    if (url.pathname === '/v1/notification-settings' && ['GET', 'PUT'].includes(req.method ?? '')) {
      if (!notifications) throw new HostError('unavailable');
      if (req.method === 'GET') {
        limits.take(`notification:${device.deviceId}`, 60);
        const sessions = (await adapter.listSessions(signal)).filter(s => !workspaceSource.archivedSessionIds().has(s.id));
        const ids = await workspaceIds(); signal.throwIfAborted();
        sendJson(res, 200, notifications.pruneSettings(device.deviceId, ids, sessions)); return;
      }
      limits.take(`notification-policy:${device.deviceId}`, 6);
      const body = await bodyJson(req, signal);
      if (Buffer.byteLength(JSON.stringify(body)) > 32768) throw new HostError('payload_too_large');
      // Parse first without committing; unauthorized override IDs are hidden.
      const sessions = await adapter.listSessions(signal), ids = await workspaceIds();
      for (const p of Array.isArray(body.projects) ? body.projects : []) {
        if (!p || typeof p.workspaceId !== 'string') throw new HostError('invalid_request');
        checkKnownScope(device.deviceId, p.workspaceId, ids, false, true);
      }
      for (const c of Array.isArray(body.chats) ? body.chats : []) {
        const session = sessions.find(s => s.id === c?.sessionId);
        if (!session || workspaceSource.archivedSessionIds().has(session.id)) throw new HostError('not_found');
        checkKnownScope(device.deviceId, session.workspaceId, ids, false, true);
      }
      sendJson(res, 200, notifications.putSettings(device.deviceId, body)); return;
    }
    if (req.method === 'GET' && ['/v1/notification-events', '/v1/notification-events/stream'].includes(url.pathname)) {
      if (!notifications) throw new HostError('unavailable');
      limits.take(`notification:${device.deviceId}`, 60);
      const after = url.searchParams.get('after') ?? undefined;
      if (url.pathname.endsWith('/stream')) {
        limits.take(`notification-open:${device.deviceId}`, 6);
        if (req.headers['last-event-id'] !== undefined && req.headers['last-event-id'] !== after) throw new HostError('invalid_request');
        await streamNotifications(device.deviceId, after, res, signal); return;
      }
      const raw = url.searchParams.get('limit') ?? '50';
      if (!/^[1-9][0-9]{0,2}$/.test(raw) || Number(raw) > 100) throw new HostError('invalid_request');
      sendJson(res, 200, await notifications.page(device.deviceId, after, Number(raw))); return;
    }
    if (req.method === 'GET' && url.pathname === '/v1/workspaces') {
      const workspaces = await workspaceSource.list(); signal.throwIfAborted();
      const current = freshDevice(device.deviceId);
      sendJson(res, 200, { items: workspaces.filter(workspace => allowsWorkspace(current.grants.readWorkspaceIds, workspace.id)).map(workspace => ({ id: workspace.id, name: workspace.name, canExecute: allowsWorkspace(current.grants.executeWorkspaceIds, workspace.id) })) }); return;
    }
    if (req.method === 'GET' && url.pathname === '/v1/capabilities') {
      sendJson(res, 200, { protocolVersion: 1, hostName: config.hostName, upstreamVersion: adapter.upstreamVersion, capabilities: { sessions: true, textPrompt: true, cancel: true, liveSnapshots: true, attachments: false, questions: false, approvals: false, push: false, notifications: !!notifications }, notificationCapabilities: { version: 1, coverage: notifications?.coverage ?? 'degraded', feed: !!notifications, unifiedPush: false, retentionMs: 604800000 } }); return;
    }
    if (req.method === 'GET' && url.pathname === '/v1/presets') {
      const presets = await adapter.listPresets(signal); signal.throwIfAborted(); freshDevice(device.deviceId);
      sendJson(res, 200, { items: presets.slice(0, 100).map(preset => ({ id: preset.id, name: preset.name })) }); return;
    }
    if (req.method === 'GET' && url.pathname === '/v1/sessions') {
      const workspaceId = url.searchParams.get('workspaceId') ?? undefined;
      if (workspaceId !== undefined) await checkScope(device.deviceId, workspaceId);
      const rawLimit = url.searchParams.get('limit') ?? '50';
      if (!/^[1-9][0-9]{0,2}$/.test(rawLimit) || Number(rawLimit) > 100) throw new HostError('invalid_request');
      const limit = Number(rawLimit), cursor = url.searchParams.get('cursor');
      let offset = 0;
      if (cursor !== null) {
        const stored = cursors.get(cursor);
        if (!stored || stored.deviceId !== device.deviceId || stored.workspaceId !== workspaceId || stored.expiresAt <= Date.now()) throw new HostError('invalid_request');
        offset = stored.offset;
      }
      const sessions = await adapter.listSessions(signal); signal.throwIfAborted();
      const ids = await workspaceIds(); signal.throwIfAborted();
      const archived = workspaceSource.archivedSessionIds(), current = freshDevice(device.deviceId);
      const scoped = sessions.filter(session => ids.has(session.workspaceId) && !archived.has(session.id) && allowsWorkspace(current.grants.readWorkspaceIds, session.workspaceId) && (workspaceId === undefined || session.workspaceId === workspaceId)).sort((a, b) => b.updatedAt - a.updatedAt || a.id.localeCompare(b.id));
      const items = scoped.slice(offset, offset + limit).map(session => sessionForDevice(session, current.deviceId, ids));
      let nextCursor: string | null = null;
      if (offset + limit < scoped.length) {
        for (const [id, value] of cursors) if (value.expiresAt <= Date.now()) cursors.delete(id);
        if (cursors.size >= 1024) cursors.delete(cursors.keys().next().value!);
        nextCursor = randomBytes(24).toString('base64url');
        cursors.set(nextCursor, { deviceId: current.deviceId, workspaceId, offset: offset + limit, expiresAt: Date.now() + 300_000 });
      }
      sendJson(res, 200, { items, nextCursor }); return;
    }
    if (req.method === 'GET' && segments[0] === 'sessions' && segments.length === 3 && segments[2] === 'events') {
      await streamSnapshots(segments[1]!, device.deviceId, res, signal); return;
    }
    if (req.method === 'GET' && segments[0] === 'sessions' && segments.length === 2) {
      const snapshot = await sessionSnapshot(segments[1]!, device.deviceId, signal);
      sendJson(res, 200, await snapshotForDevice(snapshot, device.deviceId, segments[1]!)); return;
    }
    if (req.method === 'GET' && segments[0] === 'commands' && segments.length === 2) {
      if (!isRequestId(segments[1])) throw new HostError('invalid_request');
      const receipt = state.getCommand(device.deviceId, segments[1]);
      if (!receipt) throw new HostError('not_found');
      sendJson(res, 200, receipt.receipt); return;
    }
    if (req.method === 'POST' && segments[0] === 'sessions') {
      limits.take(`mutation:${device.deviceId}`, 30);
      const body = await bodyJson(req, signal);
      if (!isRequestId(body.requestId)) throw new HostError('invalid_request');
      const requestId = body.requestId.toLowerCase();
      let operation: Command['operation'], canonicalPayload: Record<string, unknown>;
      if (segments.length === 1) {
        keys(body, ['requestId', 'workspaceId', 'presetId'], ['requestId', 'workspaceId']);
        if (typeof body.workspaceId !== 'string' || (body.presetId !== undefined && (typeof body.presetId !== 'string' || !body.presetId || body.presetId.length > 256 || /[\x00-\x1f\x7f]/.test(body.presetId)))) throw new HostError('invalid_request');
        operation = 'create'; canonicalPayload = { workspaceId: body.workspaceId, presetId: body.presetId ?? null };
      } else if (segments.length === 3 && segments[2] === 'messages') {
        keys(body, ['requestId', 'text']);
        if (typeof body.text !== 'string' || !body.text.trim() || Buffer.byteLength(body.text, 'utf8') > 32768 || /\x00/.test(body.text)) throw new HostError(typeof body.text === 'string' && Buffer.byteLength(body.text, 'utf8') > 32768 ? 'payload_too_large' : 'invalid_request');
        operation = 'prompt'; canonicalPayload = { sessionId: segments[1], text: body.text };
      } else if (segments.length === 3 && segments[2] === 'cancellations') {
        keys(body, ['requestId', 'expectedCursor']);
        if (!Number.isSafeInteger(body.expectedCursor) || (body.expectedCursor as number) < -1) throw new HostError('invalid_request');
        operation = 'cancel'; canonicalPayload = { sessionId: segments[1], expectedCursor: body.expectedCursor };
      } else throw new HostError('not_found');
      const payloadHash = hashSecret(JSON.stringify(canonicalPayload));
      const prior = state.getCommand(device.deviceId, requestId);
      if (prior) {
        const repeated = state.admitCommand(device.deviceId, requestId, operation, payloadHash);
        // A receipt replay never calls upstream, even if the session vanished or DSH is offline.
        // Its internal admission scope still has to be configured/readable/executable now.
        if (!prior.workspaceId) throw new HostError('forbidden');
        await checkScope(device.deviceId, prior.workspaceId, true, operation !== 'create');
        sendJson(res, repeated.httpStatus, repeated.receipt); return;
      }
      let command: Command;
      if (segments.length === 1) {
        keys(body, ['requestId', 'workspaceId', 'presetId'], ['requestId', 'workspaceId']);
        if (typeof body.workspaceId !== 'string' || (body.presetId !== undefined && (typeof body.presetId !== 'string' || !body.presetId || body.presetId.length > 256 || /[\x00-\x1f\x7f]/.test(body.presetId)))) throw new HostError('invalid_request');
        command = { operation: 'create', requestId, workspaceId: body.workspaceId, ...(body.presetId !== undefined ? { presetId: body.presetId as string } : {}) };
      } else if (segments.length === 3 && (segments[2] === 'messages' || segments[2] === 'cancellations')) {
        keys(body, segments[2] === 'messages' ? ['requestId', 'text'] : ['requestId', 'expectedCursor']);
        if (segments[2] === 'cancellations' && (!Number.isSafeInteger(body.expectedCursor) || (body.expectedCursor as number) < -1)) throw new HostError('invalid_request');
        if (segments[2] === 'messages' && (typeof body.text !== 'string' || !body.text.trim() || Buffer.byteLength(body.text, 'utf8') > 32768 || /\x00/.test(body.text))) throw new HostError(typeof body.text === 'string' && Buffer.byteLength(body.text, 'utf8') > 32768 ? 'payload_too_large' : 'invalid_request');
        // Authorize before admission and again immediately before dispatch.
        const snapshot = await sessionSnapshot(segments[1]!, device.deviceId, signal);
        await checkScope(device.deviceId, snapshot.session.workspaceId, true, true);
        command = segments[2] === 'messages' ? { operation: 'prompt', requestId, sessionId: segments[1]!, text: body.text as string, workspaceId: snapshot.session.workspaceId } : { operation: 'cancel', requestId, sessionId: segments[1]!, workspaceId: snapshot.session.workspaceId, expectedCursor: body.expectedCursor as number };
      } else throw new HostError('not_found');
      await checkScope(device.deviceId, command.workspaceId, true, command.operation !== 'create');
      if (command.operation === 'create' && command.presetId) {
        const presets = await adapter.listPresets(signal); signal.throwIfAborted();
        if (!presets.some(preset => preset.id === command.presetId)) throw new HostError('invalid_request');
      }
      signal.throwIfAborted();
      await checkScope(device.deviceId, command.workspaceId, true, command.operation !== 'create');
      const admitted = state.admitCommand(device.deviceId, requestId, command.operation, payloadHash, command.workspaceId);
      if (!admitted.fresh) { sendJson(res, admitted.httpStatus, admitted.receipt); return; }
      const completion = dispatch(device.deviceId, command);
      const key = `${device.deviceId}:${requestId}`;
      dispatches.set(key, { deviceId: device.deviceId, requestId, completion });
      void completion.finally(() => dispatches.delete(key)).catch(() => {});
      const receipt = await responseReceipt(device.deviceId, requestId, completion);
      freshDevice(device.deviceId);
      sendJson(res, receipt.httpStatus, receipt.receipt); return;
    }
    if (req.method === 'DELETE' && url.pathname === '/v1/device') {
      state.revokeDevice(device.deviceId); res.writeHead(204, { 'Cache-Control': 'no-store' }); res.end(); return;
    }
    throw new HostError('not_found');
  }

  function handle(req: IncomingMessage, res: ServerResponse): void {
    const controller = new AbortController();
    const abort = () => controller.abort();
    req.once('aborted', abort);
    res.once('close', abort);
    shutdown.signal.addEventListener('abort', abort, { once: true });
    void route(req, res, controller.signal).catch(error => {
      if (controller.signal.aborted || res.destroyed) return;
      const safe = publicError(error);
      if (safe.code === 'rate_limited') res.setHeader('Retry-After', '60');
      if (safe.code === 'unauthorized') res.setHeader('WWW-Authenticate', 'Bearer');
      if (res.headersSent) { res.end(); return; }
      // Close instead of resetting an unread socket: the client must receive the safe envelope.
      if (!req.complete || !req.readableEnded) { res.setHeader('Connection', 'close'); }
      sendJson(res, safe.status, errorEnvelope(safe));
    }).finally(() => {
      req.off('aborted', abort);
      shutdown.signal.removeEventListener('abort', abort);
    });
  }
  const server = prepared.tls ? createHttpsServer(prepared.tls, handle) : createHttpServer(handle);
  server.requestTimeout = 15_000;
  server.headersTimeout = 10_000;
  server.keepAliveTimeout = 5_000;
  server.maxHeadersCount = 32;
  server.on('clientError', (_error, socket) => { if (socket.writable) socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n'); });
  const host: MobileHostServer = {
    server, state, config, ...(relay ? { relay } : {}),
    start() {
      if (closing) return Promise.reject(new HostError('unavailable'));
      started ??= (async () => {
        state.claimRuntime(); claimed = true;
        if (relay) state.recoverPendingRelayPublications();
        const sources = adapter.notifications?.();
        if (sources) { notifications = new NotificationFeed(state, sources); await notifications.start(); }
        await new Promise<void>((resolve, reject) => {
          const failed = () => { state.releaseRuntime(); claimed = false; reject(new HostError('unavailable')); };
          server.once('error', failed);
          server.listen(config.port, config.bind, () => { server.off('error', failed); resolve(); });
        });
        relay?.start();
        const address = server.address();
        if (!address || typeof address === 'string') throw new HostError('unavailable');
        return { baseUrl: `${prepared.tls ? 'https' : 'http'}://${config.bind === '::1' ? '[::1]' : config.bind}:${address.port}` };
      })();
      return started;
    },
    async close() {
      if (closing) return;
      closing = true; shutdown.abort();
      await notifications?.close();
      await relay?.close();
      for (const stream of streams) stream.stop();
      onRevoke();
      // A host stop must not cancel upstream work. Preserve all in-flight outcomes as uncertain
      // before closing SQLite; completion handlers observe closing and never access a closed DB.
      for (const operation of dispatches.values()) state.finishCommand(operation.deviceId, operation.requestId, 'uncertain', { error: { code: 'delivery_uncertain', message: 'Delivery could not be confirmed. Do not automatically resend this action.' }, httpStatus: 202 });
      if (server.listening) await new Promise<void>(resolve => { server.close(() => resolve()); server.closeAllConnections(); });
      if (claimed) { state.releaseRuntime(); claimed = false; }
      if (!options.state) state.close();
    },
  };
  return host;
}
export async function startHostServer(options: HostServerOptions): Promise<MobileHostServer> {
  const host = await createHostServer(options);
  try { await host.start(); return host; } catch (error) { await host.close(); throw error; }
}
