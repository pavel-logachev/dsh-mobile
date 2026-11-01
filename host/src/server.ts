import { FileStaging } from './file-staging.ts';
import { sessionModel, settledQuestions, mutationAnswer } from './phone-projection.ts';
import { gzipSync } from 'node:zlib';
import { HOST_VERSION, parseMutation, validateAnswer, UPLOAD_LIMIT, UPLOAD_MIMES, identifier, safeAttachmentRefs, safeQueue, questionsOf } from './phone.ts';
import type { SessionMutation, PromptOptions, UploadedAttachment } from './phone.ts';
import { NotificationFeed } from './notifications.ts';
import { HISTORY_CUT_NOTICE } from './dsh-adapter.ts';
import { createHash, randomBytes } from 'node:crypto';
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
import type { FileUploadReservation, HostAdapter, HostConfiguration, PreparedHostConfiguration, HostSession, HostSnapshot } from './types.ts';
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
  /** Test-only bound for one admitted dispatch; production defaults to ten minutes. */
  dispatchTimeoutMs?: number;
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
  const body = Buffer.from(JSON.stringify(value));
  if (body.length > 2 * 1024 * 1024) throw new HostError('payload_too_large');
  const gzip = (res.req.headers['accept-encoding'] ?? '').split(',').map(part => /^gzip(?:\s*;\s*q=(1(?:\.0{0,3})?|0(?:\.\d{0,3})?))?$/i.exec(part.trim())).filter(match => match !== null);
  const accepted = gzip.length > 0 && gzip.every(match => Number(match![1] ?? 1) > 0);
  const compressed = accepted && body.length >= 1024 ? gzipSync(body, { level: 1 }) : undefined;
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', Vary: 'Accept-Encoding', ...(compressed ? { 'Content-Encoding': 'gzip' } : {}), 'Content-Length': String((compressed ?? body).length) });
  res.end(compressed ?? body);
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
  if (options.dispatchTimeoutMs !== undefined && (!Number.isInteger(options.dispatchTimeoutMs) || options.dispatchTimeoutMs < 10 || options.dispatchTimeoutMs > 600_000)) throw new HostError('invalid_config');
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
  const files = new FileStaging(config.statePath);
  type StagedUpload = { deviceId: string; sessionId: string; workspaceId: string; expiresAt: number; value: UploadedAttachment; filePath?: string; claimed?: boolean };
  const uploads = new Map<string, StagedUpload>();
  let claimedImageBytes = 0, attachmentDispatches = 0, activeDispatches = 0;
  const cleanups = new Set<Promise<void>>();
  function removeUpload(id: string): void {
    const upload = uploads.get(id); if (!upload) return; uploads.delete(id);
    if (upload.filePath && !upload.claimed) {
      const cleanup = files.remove(upload.filePath); cleanups.add(cleanup);
      void cleanup.catch(() => {}).finally(() => cleanups.delete(cleanup));
    }
  }
  let activeUploads = 0, reservedImageBytes = 0, activeFileReads = 0, activeImageReads = 0;
  const uploadsByDevice = new Map<string, number>();
  const uploadWaiters: { deviceId: string; resolve: (release: () => void) => void; reject: (error: unknown) => void }[] = [];
  function releaseUpload(deviceId: string): void {
    activeUploads--; const count = (uploadsByDevice.get(deviceId) ?? 1) - 1;
    if (count) uploadsByDevice.set(deviceId, count); else uploadsByDevice.delete(deviceId);
    pumpUploads();
  }
  function pumpUploads(): void {
    for (let i = 0; i < uploadWaiters.length;) {
      const waiter = uploadWaiters[i]!;
      if (closing) { uploadWaiters.splice(i, 1); waiter.reject(new HostError('unavailable')); continue; }
      if (activeUploads >= 4) return;
      if ((uploadsByDevice.get(waiter.deviceId) ?? 0) >= 2) { i++; continue; }
      uploadWaiters.splice(i, 1); activeUploads++; uploadsByDevice.set(waiter.deviceId, (uploadsByDevice.get(waiter.deviceId) ?? 0) + 1);
      waiter.resolve(() => releaseUpload(waiter.deviceId));
    }
  }
  function acquireSendUpload(deviceId: string): Promise<() => void> {
    return new Promise((resolve, reject) => { uploadWaiters.push({ deviceId, resolve, reject }); pumpUploads(); });
  }
  const imageReadsByDevice = new Map<string, number>();
  function assertOpen(res: ServerResponse, signal: AbortSignal): void { signal.throwIfAborted(); if (res.destroyed || res.closed) throw new HostError('unavailable'); }
  function sendBytes(res: ServerResponse, data: Uint8Array): Promise<void> {
    return new Promise(resolve => {
      const done = () => { res.off('finish', done); res.off('close', done); resolve(); };
      res.once('finish', done); res.once('close', done);
      if (res.destroyed || res.closed || res.writableFinished) { done(); return; }
      res.end(data);
    });
  }
  function pruneUploads(deviceId?: string): void { for (const [id, u] of uploads) if (u.expiresAt <= Date.now() || u.deviceId === deviceId || !state.getDevice(u.deviceId)) removeUpload(id); }
  const uploadSweep = setInterval(() => pruneUploads(), 30_000); uploadSweep.unref();
  type Command = { operation: 'create'; workspaceId: string; presetId?: string; requestId: string } | { operation: 'prompt'; sessionId: string; text: string; requestId: string; workspaceId: string; options?: PromptOptions; staged?: StagedUpload[]; fileReservation?: FileUploadReservation } | { operation: 'cancel'; sessionId: string; requestId: string; workspaceId: string; expectedCursor: number } | { operation: 'mutate'; sessionId: string; requestId: string; workspaceId: string; mutation: SessionMutation };
  // RFC UUID-shaped deterministic identity: upstream dedup is per session, not
  // per device. Mobile receipts and HTTP request IDs remain device-local.
  function upstreamRequestId(deviceId: string, requestId: string): string {
    const hash = createHash('sha256').update(JSON.stringify(['dsh-mobile-prompt-v1', deviceId, requestId])).digest('hex');
    return `${hash.slice(0, 8)}-${hash.slice(8, 12)}-5${hash.slice(13, 16)}-8${hash.slice(17, 20)}-${hash.slice(20, 32)}`;
  }
  function dispatch(deviceId: string, command: Command): Promise<StoredCommand | undefined> {
    const controller = new AbortController();
    const deadline = setTimeout(() => controller.abort(new Error('dispatch_timeout')), options.dispatchTimeoutMs ?? 600_000);
    deadline.unref();
    return (async () => {
      let dispatched = false;
      try {
        if (closing) throw new HostError('unavailable');
        // This signal is host-owned, not tied to HTTP lifetime. Admission is already durable.
        await checkScope(deviceId, command.workspaceId, true, command.operation !== 'create');
        if (command.operation !== 'create') {
          const snapshot = await sessionSnapshot(command.sessionId, deviceId, controller.signal, command.operation !== 'prompt');
          if (snapshot.session.workspaceId !== command.workspaceId) throw new HostError('forbidden');
          if (command.operation === 'cancel' && (!snapshot.session.running || snapshot.cursor !== command.expectedCursor)) throw new HostError('conflict');
        }
        if (closing) throw new HostError('unavailable');
        await checkScope(deviceId, command.workspaceId, true, command.operation !== 'create');
        dispatched = true;
        let result: { sessionId?: string } | undefined;
        if (command.operation === 'create') {
          // Record both the requested and returned IDs only after the adapter
          // settles: a host stop closes SQLite, and a failed create must not leave
          // a phantom blank-session exception behind.
          result = await adapter.createSession({ workspaceId: command.workspaceId, requestId: command.requestId, ...(command.presetId ? { presetId: command.presetId } : {}) }, controller.signal, command.workspaceId);
          if (closing) return undefined;
          state.rememberMobileSession('session-' + command.requestId.toLowerCase());
          state.rememberMobileSession(result.sessionId!);
        }
        else if (command.operation === 'prompt') {
          const attachments: UploadedAttachment[] = [];
          for (const staged of command.staged ?? []) {
            await checkScope(deviceId, command.workspaceId, true, true);
            if (staged.filePath) {
              if (!adapter.upload) throw new HostError('unavailable');
              const release = await acquireSendUpload(deviceId);
              try {
                await checkScope(deviceId, command.workspaceId, true, true);
                attachments.push(await adapter.upload(command.sessionId, { ...(command.fileReservation ? { reservation: command.fileReservation } : {}), data: files.read(staged.filePath, controller.signal), size: staged.value.ref.size, mime: staged.value.ref.mime, name: staged.value.ref.name }, controller.signal, command.workspaceId));
              } finally { release(); }
            } else attachments.push(staged.value);
          }
          await checkScope(deviceId, command.workspaceId, true, true);
          await adapter.prompt(command.sessionId, command.text, upstreamRequestId(deviceId, command.requestId), controller.signal, command.workspaceId, { delivery: command.options?.delivery ?? 'queue', ...(attachments.length ? { attachments } : {}) });
        }
        else if (command.operation === 'mutate') {
          if (!adapter.mutateSession) throw new HostError('unavailable');
          await adapter.mutateSession(command.sessionId, command.mutation, controller.signal, command.workspaceId);
        }
        else await adapter.cancel(command.sessionId, controller.signal, command.expectedCursor, command.workspaceId);
        if (closing) return undefined;
        if (command.operation === 'prompt') await Promise.all((command.staged ?? []).flatMap(u => { if (!u.filePath) return []; files.release(u.filePath); return [files.remove(u.filePath)]; })).catch(() => {});
        return state.finishCommand(deviceId, command.requestId, 'accepted', { ...(result?.sessionId ? { result: { sessionId: result.sessionId } } : {}), httpStatus: command.operation === 'create' ? 201 : 200 });
      } catch (error) {
        if (closing) return undefined;
        if (command.operation === 'prompt') await Promise.all((command.staged ?? []).flatMap(u => { if (!u.filePath) return []; files.release(u.filePath); return [files.remove(u.filePath)]; })).catch(() => {});
        if ((!dispatched || error instanceof HostError) && error instanceof HostError && error.status >= 400 && error.status < 500) {
          return state.finishCommand(deviceId, command.requestId, 'rejected', { error: { code: error.code, message: error.message }, httpStatus: error.status });
        }
        return state.finishCommand(deviceId, command.requestId, 'uncertain', { error: { code: 'delivery_uncertain', message: 'Delivery could not be confirmed. Do not automatically resend this action.' }, httpStatus: 202 });
      } finally {
        clearTimeout(deadline);
        activeDispatches--;
        if (command.operation === 'prompt') command.fileReservation?.release();
        if (command.operation === 'prompt') await Promise.all((command.staged ?? []).flatMap(u => { if (!u.filePath) return []; files.release(u.filePath); return [files.remove(u.filePath)]; })).catch(() => {});
        if (command.operation === 'prompt' && command.staged?.length) {
          attachmentDispatches--; claimedImageBytes -= command.staged.reduce((n, u) => n + (u.value.ref.kind === 'image' ? u.value.ref.size : 0), 0);
          command.staged = [];
        }
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

  const cursors = new Map<string, { deviceId: string; workspaceId: string | undefined; after: { updatedAt: number; id: string }; expiresAt: number }>();
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
  function childCount(value: unknown): number | undefined {
    if (value === undefined) return undefined;
    if (!Number.isSafeInteger(value) || (value as number) < 0) throw new HostError('internal_error');
    return value as number;
  }
  function sessionForDevice(session: HostSession, deviceId: string, ids: ReadonlySet<string>) {
    const device = checkKnownScope(deviceId, session.workspaceId, ids, false, true);
    if (typeof session.id !== 'string' || typeof session.title !== 'string' || !Number.isFinite(session.updatedAt) || typeof session.running !== 'boolean') throw new HostError('internal_error');
    const runningSubagents = childCount(session.runningSubagents);
    return { ...(session.model !== undefined ? { model: sessionModel(session.model) } : {}), id: session.id, title: session.title.slice(0, 256), workspaceId: session.workspaceId, updatedAt: session.updatedAt, running: session.running, ...(typeof session.blank === 'boolean' ? { blank: session.blank } : {}), waitingForAnswer: session.waitingForAnswer === true, ...(runningSubagents !== undefined ? { runningSubagents } : {}), canExecute: allowsWorkspace(device.grants.executeWorkspaceIds, session.workspaceId) };
  }
  const correlationCache = new Map<string, Map<string, string>>();
  function correlationForDevice(deviceId: string): Map<string, string> {
    let map = correlationCache.get(deviceId);
    if (!map) {
      map = new Map(state.promptCommandIds(deviceId).map(id => [upstreamRequestId(deviceId, id), id]));
      if (correlationCache.size >= 64) correlationCache.delete(correlationCache.keys().next().value!);
      correlationCache.set(deviceId, map);
    }
    return map;
  }
  async function snapshotForDevice(snapshot: HostSnapshot, deviceId: string, expectedId: string) {
    if (!snapshot || snapshot.session.id !== expectedId || !Array.isArray(snapshot.messages) || !Number.isSafeInteger(snapshot.cursor) || snapshot.cursor < -1 || !['idle', 'running', 'waiting', 'unknown'].includes(snapshot.activity)) throw new HostError('internal_error');
    const ids = await workspaceIds();
    if (workspaceSource.archivedSessionIds().has(expectedId)) throw new HostError('not_found');
    const session = sessionForDevice(snapshot.session, deviceId, ids);
    const correlation = correlationForDevice(deviceId);
    const mobileId = (id: string): string => correlation.get(id) ?? id;
    const messages = snapshot.messages.slice(-100).map(message => {
      if (!message || typeof message.id !== 'string' || typeof message.text !== 'string' || !['user', 'assistant', 'system'].includes(message.role) || !Number.isFinite(message.createdAt)) throw new HostError('internal_error');
      if ((message.kind !== undefined && !['message', 'agent_event', 'context'].includes(message.kind)) ||
          (message.serviceText !== undefined && typeof message.serviceText !== 'string')) throw new HostError('internal_error');
      if (message.delivery !== undefined && !['queue', 'steer'].includes(message.delivery)) throw new HostError('internal_error');
      return { ...(message.delivery !== undefined ? { delivery: message.delivery } : {}), ...(message.attachments ? { attachments: safeAttachmentRefs(message.attachments) } : {}), ...(message.kind !== undefined ? { kind: message.kind } : {}), ...(message.serviceText !== undefined ? { serviceText: message.serviceText } : {}), id: message.id, role: message.role, text: message.text, createdAt: message.createdAt, ...(message.requestId && isRequestId(message.requestId) ? { requestId: mobileId(message.requestId) } : {}), ...(message.provisional === true ? { provisional: true } : {}) };
    });
    const detail = snapshot.activityDetail;
    if (detail !== undefined && (!detail || !Number.isSafeInteger(detail.turnStartedAt) || detail.turnStartedAt < 0 ||
        (detail.tool !== undefined && (typeof detail.tool !== 'string' || !/^[A-Za-z0-9_.:/-]{1,128}$/.test(detail.tool))))) throw new HostError('internal_error');
    const activityDetail = detail && ['running', 'waiting'].includes(snapshot.activity)
      ? { turnStartedAt: detail.turnStartedAt, ...(detail.tool !== undefined ? { tool: detail.tool } : {}) } : undefined;
    const runningSubagents = childCount(snapshot.runningSubagents);
    if (runningSubagents !== undefined && session.runningSubagents !== undefined && runningSubagents !== session.runningSubagents) throw new HostError('internal_error');
    const count = runningSubagents ?? session.runningSubagents;
    const result = { ...(snapshot.model !== undefined ? { model: sessionModel(snapshot.model) } : {}), ...(snapshot.resolvedQuestions !== undefined ? { resolvedQuestions: settledQuestions(snapshot.resolvedQuestions) } : {}), ...(snapshot.pendingQueue ? { pendingQueue: safeQueue(snapshot.pendingQueue).map(q => ({ ...q, ...(q.requestId ? { requestId: mobileId(q.requestId) } : {}) })) } : {}), ...(snapshot.pendingQuestions ? { pendingQuestions: questionsOf(snapshot.pendingQuestions) } : {}), ...(count !== undefined ? { runningSubagents: count } : {}), ...(activityDetail ? { activityDetail } : {}), session, messages, cursor: snapshot.cursor, hasMore: snapshot.hasMore === true || snapshot.messages.length > 100, activity: snapshot.activity, ...(snapshot.notice === HISTORY_CUT_NOTICE ? { notice: HISTORY_CUT_NOTICE } : snapshot.activity === 'unknown' || (snapshot.activity === 'waiting' && !snapshot.pendingQuestions?.length) ? { notice: 'Return to the desktop to check this task.' } : {}), ...(snapshot.failure ? { failure: snapshot.failure } : {}) };
    // Keep complete messages and Unicode intact. Observation is a bounded latest-history view.
    while (Buffer.byteLength(JSON.stringify(result), 'utf8') > 2 * 1024 * 1024) {
      if (result.messages.length <= 1) throw new HostError('payload_too_large');
      result.messages.shift(); result.hasMore = true;
      result.notice = 'Only recent complete messages are shown; open the desktop for earlier history.';
    }
    return result;
  }
  async function permittedSession(sessionId: string, deviceId: string, signal: AbortSignal): Promise<HostSession> {
    const list = await adapter.listSessions(signal);
    signal.throwIfAborted();
    const session = list.find(item => item.id === sessionId);
    if (!session) throw new HostError('not_found');
    if (workspaceSource.archivedSessionIds().has(sessionId)) throw new HostError('not_found');
    await checkScope(deviceId, session.workspaceId, false, true);
    return session;
  }
  async function failedPointRead(sessionId: string, deviceId: string, signal: AbortSignal, error: unknown): Promise<never> {
    // An event/projection failure must not disclose whether a hidden journal is
    // corrupt. Re-resolve only its current header scope, separately per device.
    signal.throwIfAborted(); freshDevice(deviceId);
    try {
      if (workspaceSource.archivedSessionIds().has(sessionId)) throw new HostError('not_found');
      const workspaceId = adapter.sessionScope
        ? await adapter.sessionScope(sessionId, signal)
        : (await permittedSession(sessionId, deviceId, signal)).workspaceId;
      signal.throwIfAborted();
      await checkScope(deviceId, workspaceId, false, true);
      if (workspaceSource.archivedSessionIds().has(sessionId)) throw new HostError('not_found');
    } catch (resolutionError) {
      // Old listing omits unreadable headers. Failure admission must hide them
      // too, including stat/scope errors, without swallowing cancellation/revoke.
      signal.throwIfAborted(); freshDevice(deviceId);
      if (resolutionError instanceof Error && resolutionError.name === 'AbortError') throw resolutionError;
      throw new HostError('not_found');
    }
    // Only an authorized, non-archived target keeps its original safe error.
    throw error;
  }
  async function sessionSnapshot(sessionId: string, deviceId: string, signal: AbortSignal, catalogPreflight = true): Promise<HostSnapshot> {
    // Non-prompt mutations retain their independent catalog preflight. Prompts
    // and point observation use the adapter's fresh opening header, not a
    // complete corpus scan (rc.2 reads every persisted header on list()).
    const session = catalogPreflight ? await permittedSession(sessionId, deviceId, signal) : undefined;
    let snapshot: HostSnapshot;
    try { snapshot = await adapter.snapshot(sessionId, signal); }
    catch (error) { if (catalogPreflight) throw error; return failedPointRead(sessionId, deviceId, signal, error); }
    signal.throwIfAborted();
    if (session && snapshot.session.workspaceId !== session.workspaceId) throw new HostError('not_found');
    if (workspaceSource.archivedSessionIds().has(sessionId)) throw new HostError('not_found');
    await checkScope(deviceId, snapshot.session.workspaceId, false, true);
    return snapshot;
  }

  const streams = new Set<{ deviceId: string; stop: () => void }>();
  const streamCounts = new Map<string, number>();
  const onRevoke = state.onRevoke(deviceId => { pruneUploads(deviceId); for (const stream of streams) if (stream.deviceId === deviceId) stream.stop(); });
  async function streamSnapshots(sessionId: string, deviceId: string, res: ServerResponse, requestSignal: AbortSignal): Promise<void> {
    if ((streamCounts.get(deviceId) ?? 0) >= 3 || streams.size >= 32) throw new HostError('rate_limited');
    streamCounts.set(deviceId, (streamCounts.get(deviceId) ?? 0) + 1);
    const controller = new AbortController();
    let timer: ReturnType<typeof setInterval> | undefined, closed = false;
    let iterator: AsyncIterator<HostSnapshot> | undefined;
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
      let opening: IteratorResult<HostSnapshot>;
      try {
        iterator = adapter.watch(sessionId, controller.signal)[Symbol.asyncIterator]();
        opening = await iterator.next();
        if (opening.done) throw new HostError('unavailable');
      } catch (error) { return await failedPointRead(sessionId, deviceId, controller.signal, error); }
      const first = opening.value;
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
        if (closed) return;
        // Liveness is independent of registry/SQLite scope I/O: a held check must not
        // starve the heartbeat that keeps the phone's stream open.
        if (!blockedSince && Date.now() - lastHeartbeat >= heartbeatMs) {
          lastHeartbeat = Date.now();
          if (!res.write(': heartbeat\n\n')) blockedSince = Date.now();
        }
        if (rechecking) return;
        rechecking = true;
        void (async () => {
          let deadline: ReturnType<typeof setTimeout> | undefined;
          try {
            const timedOut = new Promise<never>((_, reject) => {
              deadline = setTimeout(() => reject(new HostError('unavailable')), 10_000);
            });
            await Promise.race([checkScope(deviceId, first.session.workspaceId, false, true), timedOut]);
            if (workspaceSource.archivedSessionIds().has(sessionId)) throw new HostError('not_found');
            if (blockedSince && Date.now() - blockedSince >= 5_000) { stop(); return; }
            if (latest && !blockedSince) await frame(latest, true);
            if (closed) return;
          } catch { stop(); }
          finally { if (deadline) clearTimeout(deadline); rechecking = false; }
        })();
      }, Math.min(heartbeatMs, 1000));
      timer.unref();
      void (async () => {
        try {
          while (!closed) {
            const next = await iterator!.next();
            if (next.done || closed) break;
            const snapshot = next.value;
            if (snapshot.session.workspaceId !== first.session.workspaceId) throw new HostError('not_found');
            await frame(snapshot);
          }
        } catch { /* Never stream raw upstream errors, stacks, tokens, or host paths. */ }
        finally { stop(); try { await iterator?.return?.(); } catch { /* Observation cleanup cannot leak upstream errors. */ } }
      })();
    } catch (error) {
      stop(); try { await iterator?.return?.(); } catch { /* Preserve the safe admission error. */ }
      // Local revoke can abort an opening before its fresh scope check. Preserve
      // the same auth envelope as an external durable revoke, not generic 500.
      if (!res.headersSent && !closing && !state.getDevice(deviceId)) throw new HostError('unauthorized');
      throw error;
    }
  }

  async function streamNotifications(deviceId: string, after: string | undefined, res: ServerResponse, signal: AbortSignal) {
    if (!notifications) throw new HostError('unavailable');
    if (notificationStreams.has(deviceId) || (streamCounts.get(deviceId) ?? 0) >= 3 || streams.size >= 32) throw new HostError('rate_limited');
    notificationStreams.add(deviceId); streamCounts.set(deviceId, (streamCounts.get(deviceId) ?? 0) + 1);
    let closed = false, timer: ReturnType<typeof setInterval> | undefined, working = false, dirty = false;
    let unsubscribe: (() => void) | undefined;
    const stop = () => {
      if (closed) return; closed = true;
      unsubscribe?.();
      if (timer) clearInterval(timer); streams.delete(registration); notificationStreams.delete(deviceId);
      const count = (streamCounts.get(deviceId) ?? 1) - 1; if (count) streamCounts.set(deviceId, count); else streamCounts.delete(deviceId);
      signal.removeEventListener('abort', stop); res.off('close', stop); if (res.headersSent && !res.destroyed) res.end();
    };
    const registration = { deviceId, stop }; streams.add(registration);
    signal.addEventListener('abort', stop, { once: true }); res.once('close', stop);
    let cursor = after, heartbeat = Date.now(), lastCoverage = '';
    async function send(first = false) {
      if (closed) return;
      if (working) { dirty = true; return; }
      working = true; dirty = false;
      try {
        const page = await notifications!.page(deviceId, cursor, 100, signal);
        signal.throwIfAborted(); if (closed) return;
        if (!res.headersSent) res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store', 'X-Accel-Buffering': 'no' });
        if (first || page.items.length || page.resetRequired || page.hasMore || page.coverage !== lastCoverage) {
          const frame = `event: notification-page\nid: ${page.nextCursor}\ndata: ${JSON.stringify(page)}\n\n`;
          if (Buffer.byteLength(frame) > 128 * 1024 || res.writableLength > 128 * 1024 || !res.write(frame)) { stop(); return; }
          cursor = page.nextCursor; lastCoverage = page.coverage;
          notifications!.tracePage('sse_sent', page.items);
          dirty ||= page.hasMore;
        }
      } catch (error) { stop(); if (first) throw error; }
      finally { working = false; if (dirty && !closed) queueMicrotask(() => { void send(); }); }
    }
    // Subscribe before the first read: a commit racing an in-flight page gets one
    // coalesced follow-up, never concurrent sends or a missed notification.
    unsubscribe = notifications.onChange(() => { void send(); });
    try {
      await send(true);
      if (!closed) {
        timer = setInterval(() => {
          // Liveness and revocation never wait behind membership/proof I/O.
          if (closed) return;
          if (!state.getDevice(deviceId)) { stop(); return; }
          if (Date.now() - heartbeat >= (options.heartbeatMs ?? 15000)) {
            heartbeat = Date.now();
            if (res.writableLength > 128 * 1024 || !res.write(': heartbeat\n\n')) { stop(); return; }
          }
          void send();
        }, Math.min(1000, options.heartbeatMs ?? 15000)); timer.unref();
      }
    }
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
    pruneUploads(); await Promise.all([...cleanups]);
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
      sendJson(res, 200, await notifications.page(device.deviceId, after, Number(raw), signal)); return;
    }
    if (req.method === 'GET' && url.pathname === '/v1/workspaces') {
      const workspaces = await workspaceSource.list(); signal.throwIfAborted();
      const current = freshDevice(device.deviceId);
      sendJson(res, 200, { items: workspaces.filter(workspace => allowsWorkspace(current.grants.readWorkspaceIds, workspace.id)).map(workspace => ({ id: workspace.id, name: workspace.name, canExecute: allowsWorkspace(current.grants.executeWorkspaceIds, workspace.id) })) }); return;
    }
    if (req.method === 'GET' && url.pathname === '/v1/capabilities') {
      const cleanup = files.cleanupStats();
      sendJson(res, 200, { protocolVersion: 1, hostVersion: HOST_VERSION, hostName: config.hostName, upstreamVersion: adapter.upstreamVersion, capabilities: { sessions: true, textPrompt: true, cancel: true, liveSnapshots: true, questionSkip: false, fileDownload: false, ...(adapter.phoneCapabilities?.() ?? { attachments: false, questions: false, steer: false, queue: false, rename: false, models: false }), approvals: false, push: false, notifications: !!notifications }, notificationCapabilities: { version: 1, coverage: notifications?.coverage ?? 'degraded', feed: !!notifications, unifiedPush: false, retentionMs: 604800000 }, storage: { pendingCleanups: cleanup.pending, cleanupFailures: cleanup.failures } }); return;
    }
    if (req.method === 'GET' && url.pathname === '/v1/models') {
      if (!adapter.models) throw new HostError('unavailable');
      const catalog = await adapter.models(signal); signal.throwIfAborted(); freshDevice(device.deviceId);
      sendJson(res, 200, catalog); return;
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
      let after: { updatedAt: number; id: string } | undefined;
      if (cursor !== null) {
        const stored = cursors.get(cursor);
        if (!stored || stored.deviceId !== device.deviceId || stored.workspaceId !== workspaceId || stored.expiresAt <= Date.now()) throw new HostError('invalid_request');
        after = stored.after;
      }
      const sessions = await adapter.listSessions(signal); signal.throwIfAborted();
      const ids = await workspaceIds(); signal.throwIfAborted();
      const archived = workspaceSource.archivedSessionIds(), current = freshDevice(device.deviceId);
      const scoped = sessions.filter(session => state.mobileSessionVisible(session.id, session.blank) && ids.has(session.workspaceId) && !archived.has(session.id) && allowsWorkspace(current.grants.readWorkspaceIds, session.workspaceId) && (workspaceId === undefined || session.workspaceId === workspaceId)).sort((a, b) => b.updatedAt - a.updatedAt || a.id.localeCompare(b.id));
      // Keyset, not a numeric offset: concurrent updatedAt changes must not repeat a
      // previous page item. A session that moves above the cut is left for a refresh.
      const start = after ? scoped.findIndex(session => session.updatedAt < after.updatedAt || (session.updatedAt === after.updatedAt && session.id > after.id)) : 0;
      const from = start < 0 ? scoped.length : start;
      const items = scoped.slice(from, from + limit).map(session => sessionForDevice(session, current.deviceId, ids));
      let nextCursor: string | null = null;
      if (from + limit < scoped.length) {
        for (const [id, value] of cursors) if (value.expiresAt <= Date.now()) cursors.delete(id);
        if (cursors.size >= 1024) cursors.delete(cursors.keys().next().value!);
        const last = scoped[from + limit - 1]!;
        nextCursor = randomBytes(24).toString('base64url');
        cursors.set(nextCursor, { deviceId: current.deviceId, workspaceId, after: { updatedAt: last.updatedAt, id: last.id }, expiresAt: Date.now() + 300_000 });
      }
      sendJson(res, 200, { items, nextCursor }); return;
    }
    if (req.method === 'DELETE' && segments[0] === 'sessions' && segments.length === 4 && segments[2] === 'uploads') {
      const upload = uploads.get(segments[3]!);
      if (!upload || upload.deviceId !== device.deviceId || upload.sessionId !== segments[1]) throw new HostError('not_found');
      await checkScope(device.deviceId, upload.workspaceId, true, true);
      removeUpload(segments[3]!); await Promise.all([...cleanups]);
      res.writeHead(204, { 'Cache-Control': 'no-store' }); res.end(); return;
    }
    if (req.method === 'POST' && segments[0] === 'sessions' && segments.length === 3 && segments[2] === 'uploads') {
      if (!adapter.upload || !adapter.phoneCapabilities?.().attachments) throw new HostError('unavailable');
      limits.take(`upload:${device.deviceId}`, 12);
      const snapshot = await sessionSnapshot(segments[1]!, device.deviceId, signal);
      await checkScope(device.deviceId, snapshot.session.workspaceId, true, true);
      const mime = req.headers['content-type'] ?? '', length = req.headers['content-length'];
      if (req.headers['content-encoding'] && req.headers['content-encoding'] !== 'identity') throw new HostError('unsupported_media_type');
      if (!UPLOAD_MIMES.includes(mime)) throw new HostError('unsupported_media_type');
      if (!length || !/^[1-9][0-9]{0,8}$/.test(length)) throw new HostError('invalid_request');
      const size = Number(length); if (size > UPLOAD_LIMIT) throw new HostError('payload_too_large');
      let name: string; try { name = decodeURIComponent(String(req.headers['x-file-name'] ?? 'upload')); } catch { throw new HostError('invalid_request'); }
      if (!name || Buffer.byteLength(name) > 256 || /[\\/\x00-\x1f\x7f]/.test(name) || name === '.' || name === '..') throw new HostError('invalid_request');
      pruneUploads(); await Promise.all([...cleanups]);
      // Images retain validated base64; files stay private on disk until send.
      // Reserve image bytes synchronously before concurrent intake.
      const imageBytes = UPLOAD_MIMES.slice(0, 4).includes(mime) ? size : 0;
      const deviceUploads = uploadsByDevice.get(device.deviceId) ?? 0;
      if (activeUploads >= 4 || deviceUploads >= 2 || uploads.size + activeUploads >= 64 || [...uploads.values()].filter(u => u.deviceId === device.deviceId).length + deviceUploads >= 20 || [...uploads.values()].reduce((n, u) => n + (u.value.ref.kind === 'image' ? u.value.ref.size : 0), 0) + reservedImageBytes + claimedImageBytes + imageBytes > 200 * 1024 * 1024) throw new HostError('rate_limited');
      activeUploads++; uploadsByDevice.set(device.deviceId, deviceUploads + 1); reservedImageBytes += imageBytes;
      let filePath: string | undefined, staged = false;
      try {
        let received = 0;
        const data = (async function* () {
          for await (const chunk of req.iterator({ destroyOnReturn: false })) {
            signal.throwIfAborted(); await checkScope(device.deviceId, snapshot.session.workspaceId, true, true);
            const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk as string); received += bytes.length;
            if (received > size || received > UPLOAD_LIMIT) throw new HostError('payload_too_large');
            yield bytes;
          }
          if (received !== size) throw new HostError('invalid_request');
        })();
        let value: UploadedAttachment;
        if (imageBytes) value = await adapter.upload(segments[1]!, { data, size, mime, name }, signal, snapshot.session.workspaceId);
        else {
          files.assertOutside(await workspaceSource.list()); signal.throwIfAborted();
          filePath = await files.save(data, size, signal);
          value = { ref: { id: randomBytes(24).toString('hex'), name, size, mime, kind: 'file' }, upstream: { type: 'file', receiptId: '' } };
        }
        signal.throwIfAborted(); await checkScope(device.deviceId, snapshot.session.workspaceId, true, true); signal.throwIfAborted();
        const uploadId = randomBytes(24).toString('base64url'), expiresAt = Date.now() + 10 * 60_000;
        const ref = safeAttachmentRefs([value.ref])[0]!;
        if (ref.size !== size || ref.mime !== mime) throw new HostError('internal_error');
        uploads.set(uploadId, { deviceId: device.deviceId, sessionId: segments[1]!, workspaceId: snapshot.session.workspaceId, value: { ref, upstream: value.upstream }, ...(filePath ? { filePath } : {}), expiresAt }); staged = true;
        sendJson(res, 201, { uploadId, expiresAt, attachment: ref }); return;
      } finally { if (filePath && !staged) await files.remove(filePath); reservedImageBytes -= imageBytes; releaseUpload(device.deviceId); }
    }
    if (req.method === 'GET' && segments[0] === 'sessions' && segments.length === 4 && segments[2] === 'files') {
      if (!adapter.file || !adapter.phoneCapabilities?.().fileDownload) throw new HostError('unavailable');
      if (activeFileReads >= 2) throw new HostError('rate_limited');
      limits.take(`file:${device.deviceId}`, 30); activeFileReads++;
      try {
        const visible = await snapshotForDevice(await sessionSnapshot(segments[1]!, device.deviceId, signal), device.deviceId, segments[1]!); assertOpen(res, signal);
        if (!visible.messages.some(m => m.attachments?.some(a => a.kind === 'file' && a.id === segments[3]))) throw new HostError('not_found');
        const session = visible.session;
        const value = await adapter.file(session.id, segments[3]!, signal, session.workspaceId); assertOpen(res, signal);
        await checkScope(device.deviceId, session.workspaceId, false, true); assertOpen(res, signal);
        const ref = safeAttachmentRefs([value.ref])[0]!;
        if (ref.kind !== 'file' || ref.id !== segments[3] || value.data.length !== ref.size || ref.size > UPLOAD_LIMIT) throw new HostError('payload_too_large');
        const name = encodeURIComponent(ref.name).replace(/['()*]/g, c => '%' + c.charCodeAt(0).toString(16).toUpperCase());
        res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': String(ref.size), 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Content-Disposition': `attachment; filename="download"; filename*=UTF-8''${name}` });
        await sendBytes(res, value.data); return;
      } finally { activeFileReads--; }
    }
    if (req.method === 'GET' && segments[0] === 'sessions' && segments.length === 4 && segments[2] === 'attachments') {
      if (!adapter.image) throw new HostError('unavailable');
      const deviceReads = imageReadsByDevice.get(device.deviceId) ?? 0;
      if (activeImageReads >= 4 || deviceReads >= 2) throw new HostError('rate_limited');
      activeImageReads++; imageReadsByDevice.set(device.deviceId, deviceReads + 1);
      try {
        const visible = await snapshotForDevice(await sessionSnapshot(segments[1]!, device.deviceId, signal), device.deviceId, segments[1]!); assertOpen(res, signal);
        if (!visible.messages.some(m => m.attachments?.some(a => a.kind === 'image' && a.id === segments[3]))) throw new HostError('not_found');
        const session = visible.session;
        const value = await adapter.image(session.id, segments[3]!, signal, session.workspaceId); assertOpen(res, signal);
        await checkScope(device.deviceId, session.workspaceId, false, true); assertOpen(res, signal);
        if (value.data.length > UPLOAD_LIMIT || value.data.length !== value.ref.size || value.ref.id !== segments[3] || !UPLOAD_MIMES.slice(0, 4).includes(value.ref.mime) || value.ref.kind !== 'image') throw new HostError('payload_too_large');
        res.writeHead(200, { 'Content-Type': value.ref.mime, 'Content-Length': String(value.data.length), 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Content-Disposition': 'inline' }); await sendBytes(res, value.data); return;
      } finally {
        activeImageReads--; const count = (imageReadsByDevice.get(device.deviceId) ?? 1) - 1;
        if (count) imageReadsByDevice.set(device.deviceId, count); else imageReadsByDevice.delete(device.deviceId);
      }
    }
    if (req.method === 'GET' && segments[0] === 'sessions' && segments.length === 3 && segments[2] === 'events') {
      await streamSnapshots(segments[1]!, device.deviceId, res, signal); return;
    }
    if (req.method === 'GET' && segments[0] === 'sessions' && segments.length === 2) {
      const snapshot = await sessionSnapshot(segments[1]!, device.deviceId, signal, false);
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
      let mutation: SessionMutation | undefined;
      if (segments.length === 1) {
        keys(body, ['requestId', 'workspaceId', 'presetId'], ['requestId', 'workspaceId']);
        if (typeof body.workspaceId !== 'string' || (body.presetId !== undefined && (typeof body.presetId !== 'string' || !body.presetId || body.presetId.length > 256 || /[\x00-\x1f\x7f]/.test(body.presetId)))) throw new HostError('invalid_request');
        operation = 'create'; canonicalPayload = { workspaceId: body.workspaceId, presetId: body.presetId ?? null };
      } else if (segments.length === 3 && segments[2] === 'messages') {
        keys(body, ['requestId', 'text', 'delivery', 'uploadIds'], ['requestId', 'text']);
        if (typeof body.text !== 'string' || (!body.text.trim() && !(Array.isArray(body.uploadIds) && body.uploadIds.length)) || Buffer.byteLength(body.text, 'utf8') > 32768 || /\x00/.test(body.text) || (body.delivery !== undefined && !['queue', 'steer'].includes(body.delivery as string)) || (body.uploadIds !== undefined && (!Array.isArray(body.uploadIds) || body.uploadIds.length > 20 || body.uploadIds.some(id => !identifier(id)) || new Set(body.uploadIds).size !== body.uploadIds.length))) throw new HostError(typeof body.text === 'string' && Buffer.byteLength(body.text, 'utf8') > 32768 ? 'payload_too_large' : 'invalid_request');
        operation = 'prompt'; canonicalPayload = { sessionId: segments[1], text: body.text, ...(body.delivery && body.delivery !== 'queue' ? { delivery: body.delivery } : {}), ...(Array.isArray(body.uploadIds) && body.uploadIds.length ? { uploadIds: body.uploadIds } : {}) };
      } else if (segments.length === 3 && segments[2] === 'cancellations') {
        keys(body, ['requestId', 'expectedCursor']);
        if (!Number.isSafeInteger(body.expectedCursor) || (body.expectedCursor as number) < -1) throw new HostError('invalid_request');
        operation = 'cancel'; canonicalPayload = { sessionId: segments[1], expectedCursor: body.expectedCursor };
      } else if (segments.length === 3) {
        mutation = parseMutation(segments[2]!, body); operation = 'mutate';
        canonicalPayload = { sessionId: segments[1], mutation };
      } else throw new HostError('not_found');
      const payloadHash = hashSecret(JSON.stringify(canonicalPayload));
      // Refuse a fresh command before any expensive preparation; known receipts still replay below.
      if (!state.getCommand(device.deviceId, requestId)) state.assertMutationAllowed();
      const replay = async (): Promise<void> => {
        const prior = state.getCommand(device.deviceId, requestId)!;
        const repeated = state.admitCommand(device.deviceId, requestId, operation, payloadHash);
        // A receipt replay never calls upstream, even if the session vanished or DSH is offline.
        // Its internal admission scope still has to be configured/readable/executable now.
        if (!prior.workspaceId) throw new HostError('forbidden');
        await checkScope(device.deviceId, prior.workspaceId, true, operation !== 'create');
        sendJson(res, repeated.httpStatus, repeated.receipt);
      };
      if (state.getCommand(device.deviceId, requestId)) { await replay(); return; }
      let admittedHere = false;
      try {
      if (body.delivery === 'steer' && !adapter.phoneCapabilities?.().steer) throw new HostError('unavailable');
      if (mutation) { const capability = mutation.kind === 'answer' ? 'questions' : mutation.kind === 'model' ? 'models' : mutation.kind; if (!adapter.phoneCapabilities?.()[capability] || (mutation.kind === 'answer' && mutation.skip && !adapter.phoneCapabilities?.().questionSkip)) throw new HostError('unavailable'); }
      let command: Command;
      if (segments.length === 1) {
        keys(body, ['requestId', 'workspaceId', 'presetId'], ['requestId', 'workspaceId']);
        if (typeof body.workspaceId !== 'string' || (body.presetId !== undefined && (typeof body.presetId !== 'string' || !body.presetId || body.presetId.length > 256 || /[\x00-\x1f\x7f]/.test(body.presetId)))) throw new HostError('invalid_request');
        command = { operation: 'create', requestId, workspaceId: body.workspaceId, ...(body.presetId !== undefined ? { presetId: body.presetId as string } : {}) };
      } else if (segments.length === 3) {
        const snapshot = await sessionSnapshot(segments[1]!, device.deviceId, signal, operation !== 'prompt');
        await checkScope(device.deviceId, snapshot.session.workspaceId, true, true);
        if (state.getCommand(device.deviceId, requestId)) { await replay(); return; }
        const base = { requestId, sessionId: segments[1]!, workspaceId: snapshot.session.workspaceId };
        if (mutation) {
          if (mutation.kind === 'answer') { const pending = snapshot.pendingQuestions?.find(q => q.callId === mutation!.callId); if (!pending) throw new HostError('question_expired'); validateAnswer(pending, mutationAnswer(mutation, pending)); }
          command = { ...base, operation: 'mutate', mutation };
        } else if (operation === 'prompt') {
          if (body.delivery === 'steer' && !snapshot.session.running) throw new HostError('conflict');
          const attachments = ((body.uploadIds ?? []) as string[]).map(id => { const u = uploads.get(id); if (!u || u.expiresAt <= Date.now() || u.deviceId !== device.deviceId || u.sessionId !== base.sessionId || u.workspaceId !== base.workspaceId) throw new HostError('not_found'); if (u.claimed) throw new HostError('conflict'); return u; });
          if (attachments.reduce((sum, a) => sum + a.value.ref.size, 0) > 200 * 1024 * 1024) throw new HostError('payload_too_large');
          command = { ...base, operation: 'prompt', text: body.text as string, staged: attachments, options: { delivery: body.delivery === 'steer' ? 'steer' : 'queue' } };
        } else command = { ...base, operation: 'cancel', expectedCursor: body.expectedCursor as number };
      } else throw new HostError('not_found');
      await checkScope(device.deviceId, command.workspaceId, true, command.operation !== 'create');
      if (command.operation === 'create' && command.presetId) {
        const presets = await adapter.listPresets(signal); signal.throwIfAborted();
        if (!presets.some(preset => preset.id === command.presetId)) throw new HostError('invalid_request');
      }
      signal.throwIfAborted();
      await checkScope(device.deviceId, command.workspaceId, true, command.operation !== 'create');
      let reservation: FileUploadReservation | undefined, transferred = false;
      try {
      if (!state.getCommand(device.deviceId, requestId) && command.operation === 'prompt') {
        const count = command.staged?.filter(u => u.filePath).length ?? 0;
        if (count && adapter.reserveFileUploads) {
          reservation = await adapter.reserveFileUploads(command.sessionId, count, signal, command.workspaceId);
          command.fileReservation = reservation;
          signal.throwIfAborted(); await checkScope(device.deviceId, command.workspaceId, true, true);
        }
      }
      if (state.getCommand(device.deviceId, requestId)) { await replay(); return; }
      if (closing) throw new HostError('unavailable');
      // Bound every admitted-but-unsettled dispatch, including commands without
      // attachments. A refused admission creates no receipt and never dispatches.
      if (activeDispatches >= 32) throw new HostError('rate_limited');
      if (command.operation === 'prompt' && command.staged?.length && attachmentDispatches >= 16) throw new HostError('rate_limited');
      if (command.operation === 'prompt' && command.staged?.some(u => u.claimed || ![...uploads.values()].includes(u) || u.expiresAt <= Date.now())) throw new HostError('conflict');
      const admitted = state.admitCommand(device.deviceId, requestId, command.operation, payloadHash, command.workspaceId, command.operation === 'create' ? null : command.sessionId);
      if (!admitted.fresh) { sendJson(res, admitted.httpStatus, admitted.receipt); return; }
      admittedHere = true;
      if (command.operation === 'prompt') correlationCache.delete(device.deviceId);
      if (command.operation === 'prompt' && command.staged?.length) { attachmentDispatches++; claimedImageBytes += command.staged.reduce((n, u) => n + (u.value.ref.kind === 'image' ? u.value.ref.size : 0), 0); }
      if (command.operation === 'prompt') for (const u of command.staged ?? []) { if (u.filePath) files.claim(u.filePath); u.claimed = true; for (const [id, candidate] of uploads) if (candidate === u) uploads.delete(id); }
      transferred = true;
      activeDispatches++;
      const completion = dispatch(device.deviceId, command);
      const key = `${device.deviceId}:${requestId}`;
      dispatches.set(key, { deviceId: device.deviceId, requestId, completion });
      void completion.finally(() => dispatches.delete(key)).catch(() => {});
      const receipt = await responseReceipt(device.deviceId, requestId, completion);
      freshDevice(device.deviceId);
      sendJson(res, receipt.httpStatus, receipt.receipt); return;
      } finally { if (!transferred) reservation?.release(); }
      } catch (error) {
        // Another exact POST can win admission while this one awaits scope or
        // quota. Its durable result wins over any stale pre-admission failure;
        // admitCommand still detects operation/payload mismatch as conflict.
        if (!admittedHere && state.getCommand(device.deviceId, requestId)) { await replay(); return; }
        throw error;
      }
    }
    if (req.method === 'DELETE' && url.pathname === '/v1/device') {
      state.revokeDevice(device.deviceId); await Promise.all([...cleanups]); res.writeHead(204, { 'Cache-Control': 'no-store' }); res.end(); return;
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
  // Reuse authenticated TLS connections on mobile; still bound slow headers and upload lifetime.
  server.requestTimeout = 120_000;
  server.headersTimeout = 65_000;
  server.keepAliveTimeout = 60_000;
  server.maxHeadersCount = 32;
  server.on('clientError', (_error, socket) => { if (socket.writable) socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n'); });
  const host: MobileHostServer = {
    server, state, config, ...(relay ? { relay } : {}),
    start() {
      if (closing) return Promise.reject(new HostError('unavailable'));
      started ??= (async () => {
        state.claimRuntime(); claimed = true;
        await files.startup(await workspaceSource.list());
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
      closing = true; pumpUploads(); shutdown.abort(); clearInterval(uploadSweep); for (const id of uploads.keys()) removeUpload(id);
      await Promise.all([...cleanups]).catch(() => {}); await files.close().catch(() => {});
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
