import { PhoneProjection, sessionModel, mutationAnswer } from './phone-projection.ts';
import { createHash } from 'node:crypto';
import { attachmentRefs, questionsOf, queueOf, record, validateAnswer, safeFileMime, UPLOAD_LIMIT, IMAGE_MIMES } from './phone.ts';
import type { PendingQuestion, SessionMutation, ModelCatalog, ModelSelection, PromptOptions, UploadedAttachment, AttachmentRef, ImageMime } from './phone.ts';
import type { QuestionBridge } from './question-bridge.ts';
import { realpath, stat } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import { createWorkspaceSource, workspacePathKey } from './workspace-source.ts';
import type { WorkspaceSource } from './workspace-source.ts';
import { HostError } from './errors.ts';
import { presentUserText } from './message-presentation.ts';
import type { ChatMessage, FileUploadReservation, HostAdapter, HostSession, HostSnapshot, Preset, WorkspaceConfig } from './types.ts';

import type { NotificationEvidence, NotificationSource, NotificationSources } from './notifications.ts';
import { isCompatibleDshVersion } from './compatibility.ts';
export { COMPATIBLE_DSH_VERSIONS } from './compatibility.ts';
const MAX_MESSAGES = 100;
// Includes the opening page. page() is message-aligned, so event/byte counts vary.
const MAX_HISTORY_PAGES = 16;
const MAX_HISTORY_BYTES = 16 * 1024 * 1024;
// Live deltas bypass the history-opening budget. Keep provisional text below the
// wire snapshot ceiling and cap distinct assistant blocks before joining them.
const MAX_PROVISIONAL_BYTES = 2 * 1024 * 1024;
const MAX_PROVISIONAL_BLOCKS = 1024;
const MAX_HISTORY_CACHE_ENTRIES = 16;
const MAX_HISTORY_CACHE_BYTES = 32 * 1024 * 1024;
const MAX_HISTORY_WAITERS = 64;
type OpeningWaiter = (frame?: Record<string, unknown>, error?: { reason: unknown }) => void;
interface OpeningFlight { controller: AbortController; waiters: Set<OpeningWaiter> }
// Only in-flight immutable opening frames are shared, not grants, cwd bindings,
// mutable Transcript folds, or a completed snapshot across later requests.
type HistoryWaiter = (error?: { reason: unknown }) => void;
interface HistoryFlight { controller: AbortController; waiters: Set<HistoryWaiter>; view: readonly WorkspaceConfig[] }
interface HistoryCacheEntry {
  view: readonly WorkspaceConfig[]; scope: string; firstSeq: number; throughSeq: number;
  json: string; bytes: number; hasMore: boolean; pages: number; exhausted: boolean;
}
const isUuid = (value: string): boolean => /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);

/** Narrow in-process seams, derived from inspected rc.2 declarations, not an RPC proxy. */
export interface DshSessionSummary {
  readonly sessionId: string;
  readonly updatedAt: number;
  readonly running: boolean;
  readonly blank?: boolean;
  readonly agentAvailable: boolean;
  readonly cwd?: string;
  readonly origin?: string;
  readonly parentSessionId?: string;
  readonly projections?: { readonly values: Readonly<Record<string, unknown>> };
}
export interface DshSessionController {
  list(request: Record<string, never>, signal: AbortSignal): Promise<{ readonly items: readonly DshSessionSummary[] }>;
  follow(request: { address: { kind: 'session'; sessionId: string }; assistantStream: true; maxMessages: number }, signal: AbortSignal): AsyncIterable<unknown>;
  /** Cold-safe rc.2 backward page, pinned to the corresponding follow cursor. */
  page(request: { address: { kind: 'session'; sessionId: string }; throughSeq: number; beforeSeq: number; maxMessages: number }, signal: AbortSignal): Promise<unknown>;
  projections(request: { sessionId: string }, signal: AbortSignal): Promise<unknown>;
  create(request: ({ cwd: string; workspaceId?: never } | { workspaceId: string; cwd?: never }) & { sessionId: string; agentPreset?: string }): Promise<{ sessionId: string }>;
  prompt(request: { requestId: string; sessionId: string; mode: 'queue' | 'steer'; content: readonly ({ type: 'text'; text: string } | { type: 'image'; mediaType: ImageMime; data: string; name?: string } | { type: 'file'; receiptId: string })[] }, signal: AbortSignal): Promise<unknown>;
  resolveAgent?(sessionId: string): Promise<{ agent: unknown } | { error: unknown }>;
  rename?(request: { sessionId: string; title: string }): Promise<unknown>;
  updateQueue?(request: { sessionId: string; itemId: string; action: { kind: 'remove' | 'steer' } }): Promise<unknown>;
  modelCatalog?(): Promise<{ default: ModelSelection; groups: readonly { id: string; models: readonly { id: string; name: string; reasoning?: { efforts: readonly { id: string; name: string }[] } }[] }[] }>;
  selectModel?(request: ModelSelection & { sessionId: string }): Promise<unknown>;
  attachment?(request: { sessionId: string; attachmentId: string }): Promise<{ attachment: { attachmentId: string; mediaType: ImageMime; bytes: number; name?: string }; data: string }>;
  cancel(request: { sessionId: string }): unknown;
}
export interface DshAgentPresets {
  remoteExportList(): Promise<{ readonly presets: readonly { id: string; name?: string; broken?: string }[] }>;
}
export type DshObservation = { type: 'event'; event: unknown } | { type: 'assistant-stream'; frame: unknown } | { type: 'status'; running: boolean };
export interface DshEvents {
  /** Only this session's data may reach the listener. The disposer must remove all subscriptions. */
  subscribe(sessionId: string, listener: (observation: DshObservation) => void): () => void;
  subscribeNotifications?(listener: (id: string, event: NotificationSource) => void): () => void;
  /** Inspected session/disposed lifecycle, separate from cold follow cleanup. */
  subscribeDisposed?(listener: (id: string) => void): () => void;
}
export interface DshAdapterOptions {
  /** Explicit operator-declared version, NOT automatically discovered or inferred from method names. */
  dshVersion: string;
  sessionController: DshSessionController;
  agentPresets?: DshAgentPresets;
  questionBridge?: QuestionBridge;
  answerContinued?: (sessionId: string, callId: string, answer: import('./phone.ts').QuestionAnswer) => Promise<boolean>;
  modelInfo?: (provider: string, model: string, signal: AbortSignal) => Promise<{ inputModalities?: readonly string[] }>;
  fileUploads?: { resolve?(agent: unknown, receiptId: string): unknown; uploadStream(input: { sessionId: string; data: AsyncIterable<Uint8Array>; signal?: AbortSignal; name?: string }): Promise<{ receiptId: string; file: { attachmentId: string; name: string; bytes: number } }> };
  attachments?: { readImage?(ref: { attachmentId: string; mediaType: ImageMime; bytes: number; width: number; height: number; name?: string }, signal?: AbortSignal): Promise<{ data: Uint8Array }>; validateImage(input: { data: Uint8Array; mediaType: ImageMime; name?: string }): Promise<void>; readFileStream?(ref: { attachmentId: string; name: string; bytes: number }, signal?: AbortSignal): AsyncIterable<Uint8Array> };
  /** Legacy factory input; do not combine with workspaceSource. */
  workspaces?: readonly WorkspaceConfig[];
  workspaceSource?: WorkspaceSource;
  /** Bound request-local cwd deduplication; no resolution survives a list call. */
  realpathCacheMaxEntries?: number;
  /** Filesystem seam for deterministic authorization tests. */
  realpath?: (cwd: string) => Promise<string>;
  events?: DshEvents;
  /** Inspected synchronous live Session/Agent state. The opening header owns
   * scope/title; this seam avoids a whole corpus listing for point reads.
   * Compositions without live status keep the controller-list fallback.
   */
  sessionStatus?: (sessionId: string) => { running: boolean; updatedAt: number };
  /** Inspected SessionPersistence.stat header-only read (live Session preferred).
   * Used only to decide scope when a point opening fails before its first frame.
   */
  sessionHeader?: (sessionId: string, signal: AbortSignal) => Promise<unknown>;
  /** Synchronous live-only descendant reduction; no persisted catalog traversal. */
  runningSubagentCounts?: () => ReadonlyMap<string, number>;
  pollIntervalMs?: number;
  throttleMs?: number;
}
const phoneProjections = new WeakMap<Transcript, PhoneProjection>();
// No upstream API discards an unbound receipt. Keep a service-lifetime budget,
// including mobile-expired/revoked receipts and uncertain upload failures.
type ReceiptBudget = Map<object, { sessionId: string; receiptId?: string }>;
const RECEIPT_BUDGET = Symbol.for('dsh-mobile.receipt-budget.v1');
const FILE_MIMES = Symbol.for('dsh-mobile.file-mimes.v1');
function fileMimes(service: object): Map<string, string> {
  const identity = (Reflect.get(service, Symbol.for('cordis.original')) ?? service) as object;
  let mimes = Reflect.get(identity, FILE_MIMES) as Map<string, string> | undefined;
  if (!mimes) { mimes = new Map(); Object.defineProperty(identity, FILE_MIMES, { value: mimes }); }
  return mimes;
}
function receiptBudget(service: object): ReceiptBudget {
  const identity = (Reflect.get(service, Symbol.for('cordis.original')) ?? service) as object;
  let budget = Reflect.get(identity, RECEIPT_BUDGET) as ReceiptBudget | undefined;
  if (!budget) {
    budget = new Map();
    // Inspected rc.2 stores staged receipts by live Agent.session. Never assume an
    // empty service when an older module's private WeakMap cannot be recovered.
    try {
      const staged = Reflect.get(identity, 'stagedFiles');
      const ctx = Reflect.get(identity, 'ctx');
      // Cordis property access requires inject; its public get() explicitly does not.
      // Enumerate Sessions, not Agents: detached Agents can leave live staged receipts.
      const sessions = (typeof ctx?.get === 'function' ? ctx.get('sessions') : ctx?.sessions)?.list();
      if (!(staged instanceof WeakMap) || !Array.isArray(sessions) || sessions.length > 4096) throw new Error('unknown receipt inventory');
      for (const session of sessions) {
        if (typeof session?.id !== 'string' || typeof session !== 'object' || session === null) throw new Error('unknown receipt scope');
        const receipts = staged.get(session);
        if (receipts === undefined) continue;
        if (!(receipts instanceof Map)) throw new Error('unknown receipt inventory');
        for (const id of receipts.keys()) {
          if (typeof id !== 'string' || !id) throw new Error('unknown receipt id');
          const entry = { sessionId: session.id, receiptId: id }; budget.set(entry, entry);
          if (budget.size > 64) throw new Error('receipt inventory beyond bound');
        }
      }
    } catch {
      // Fail closed for this upstream service lifetime; restart with an inspected
      // empty service is required when its existing inventory cannot be proved.
      budget.clear();
      for (let i = 0; i < 64; i++) { const entry = { sessionId: '' }; budget.set(entry, entry); }
    }
    Object.defineProperty(identity, RECEIPT_BUDGET, { value: budget });
  }
  return budget;
}
interface CanonicalWorkspace extends WorkspaceConfig { key: string }
interface WireEvent { type: string; seq: number; time: number; data: unknown; ignorable?: true; surfaceOp?: unknown }
interface SurfaceNode { seq: number; message?: ChatMessage; answerTurn?: number }
interface Provisional { id: string; turn: number; step: number; nextIndex: number; createdAt: number; bytes: number; blocks: Map<number, string> }
const KNOWN_LOG_TYPES = new Set([
  'agent-preset/selected', 'agent/inbox/spliced', 'approval/asked', 'approval/decided', 'approval/policy',
  'assistant/attempt', 'command/done', 'command/run', 'compaction/end', 'compaction/prune', 'compaction/start',
  'compaction/summary', 'deliverables/presented', 'feedback/message-delete', 'feedback/message-put', 'feedback/record',
  'goal/change', 'hook/invoked', 'hook/result', 'image/offload', 'llm/retry', 'llm/retry-started', 'model/selection',
  'permission/preset', 'plan/mode', 'request/context', 'request/header', 'sandbox/mode', 'schedule/change',
  'session-log-deepseek/delivery-accepted', 'session/end-seed', 'session/title', 'session/title-llm-request',
  'step/end', 'step/start', 'subagent/catalog', 'subagent/descriptor', 'subagent/model-selection-policy',
  'team/member', 'team/message/delivered', 'team/message/queued', 'team/task', 'todo/write',
  'tool-workflow/agent-end', 'tool-workflow/agent-start', 'tool-workflow/run-end', 'tool-workflow/run-start',
  'tool/call', 'tool/ptc-dispatch', 'tool/ptc-dispatch-start', 'turn/end', 'turn/start',
  // rc.4/rc.5 (identical server packages) non-surface durable facts: preserve cursor, never expose cwd/job metadata.
  'working-directory/change', 'job/registered', 'job/settled',
  'web/deepseek-search-llm-request', 'workspace/changes',
]);
const WAIT_NOTICE = 'A question or approval needs attention in DSH on the desktop. Mobile answers are not supported.';
const UNKNOWN_NOTICE = 'This DSH history contains an unsupported event. Open the desktop to view the authoritative conversation.';
const GAP_NOTICE = 'Live observation was interrupted. Refreshing the authoritative DSH snapshot.';
export const HISTORY_CUT_NOTICE = 'This bounded history crosses a DSH surface replacement. Open the desktop to view the authoritative conversation.';

function phoneError(error: unknown): unknown {
  const code = record(error)?.code;
  if (['session/queue-item-not-found', 'session/steer-unavailable', 'REPLY_QUEUED'].includes(code as string)) return new HostError('conflict');
  if (['session/not-found', 'CALLER_NOT_LIVE', 'DELEGATED_CALLER'].includes(code as string)) return new HostError('not_found');
  if (['session/model-unavailable', 'session/attachment-invalid', 'gateway/bad-request', 'BAD_ANSWER', 'TOO_MANY_IMAGES', 'IMAGES_TOO_LARGE', 'UNSUPPORTED_IMAGE_TYPE', 'INVALID_IMAGE_BASE64', 'INVALID_IMAGE', 'IMAGE_TYPE_MISMATCH', 'IMAGE_TOO_LARGE', 'IMAGE_TOO_MANY_PIXELS', 'IMAGE_DIMENSION_TOO_LARGE'].includes(code as string)) return new HostError('invalid_request');
  return error;
}
function object(value: unknown): Record<string, unknown> | undefined {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}
function textContent(content: unknown): string {
  if (!Array.isArray(content)) return '';
  return content.map((part: unknown) => {
    const block = object(part);
    return block?.type === 'text' && typeof block.text === 'string' ? block.text : '';
  }).join('');
}
function wireEvent(value: unknown): WireEvent {
  const event = object(value);
  if (!event || typeof event.type !== 'string' || !Number.isSafeInteger(event.seq) || (event.seq as number) < 0 || !Number.isSafeInteger(event.time) || (event.time as number) < 0) throw new HostError('unavailable');
  return event as unknown as WireEvent;
}
function boundedTitle(value: string): string { return Buffer.from(value.slice(0, 256), 'utf8').toString('utf8'); }
function messageOf(event: WireEvent): ChatMessage | undefined {
  const data = object(event.data);
  const message = event.type === 'user/message' ? data : event.type === 'assistant/message' ? object(data?.message) : undefined;
  if (!message || typeof message.id !== 'string') return undefined;
  const text = textContent(message.content);
  const attachments = attachmentRefs(message.content);
  if (!text && !attachments.length) return undefined;
  const source = object(message.source);
  return {
    id: message.id, role: event.type === 'user/message' ? 'user' : 'assistant', ...(attachments.length ? { attachments } : {}),
    ...(event.type === 'user/message' ? presentUserText(text, typeof source?.kind === 'string' ? source.kind : undefined) : { text }), createdAt: event.time,
    ...(event.type === 'user/message' && source?.kind === 'user' && typeof source.rpcId === 'string' ? { requestId: source.rpcId } : {}),
  };
}
const SURFACE_TYPES = new Set(['user/message', 'assistant/message', 'system/message', 'developer/message', 'tool/result']);
function applySurface(nodes: SurfaceNode[], event: WireEvent): boolean {
  if (!SURFACE_TYPES.has(event.type)) return true;
  const message = messageOf(event);
  const data = object(event.data);
  const node = { seq: event.seq, ...(message ? { message } : {}), ...(event.type === 'assistant/message' && message && data?.interrupted !== true && Number.isSafeInteger(data?.turn) ? { answerTurn: data!.turn as number } : {}) };
  if (event.surfaceOp === 'append') { nodes.push(node); return true; }
  const op = object(event.surfaceOp);
  if (op?.op !== 'replace' || !Number.isSafeInteger(op.startSeq) || !Number.isSafeInteger(op.endSeq)) throw new HostError('unavailable');
  const start = nodes.findIndex((entry) => entry.seq === op.startSeq);
  const end = nodes.findIndex((entry) => entry.seq === op.endSeq);
  // nodes is a certain SUFFIX of the current surface, not a numeric seq range.
  // Missing endpoints belong to its omitted prefix. If the end is known, drop
  // the affected prefix; if both are missing, never append the replacement copy
  // at the tail. A known start with no end cannot prove any surviving suffix.
  if (start < 0 || end < 0) {
    if (end >= 0) nodes.splice(0, end + 1, node);
    else if (start >= 0) nodes.splice(0);
    return false;
  }
  if (end < start) throw new HostError('unavailable');
  nodes.splice(start, end - start + 1, node);
  return true;
}

class Transcript {
  session: HostSession;
  cursor = -1;
  hasMore = false;
  nodes: SurfaceNode[] = [];
  private running: boolean;
  private turn: { number: number; startedAt: number } | undefined;
  private readonly calls = new Map<string, string>();
  private unsupported = false;
  private historyCut = false;
  private gap = false;
  private readonly approvals = new Set<string>();
  private readonly questions = new Set<string>();
  private projectedQuestions = false;
  private attempt: Provisional | undefined;
  private failure: { code?: string; message: string } | undefined;
  private revision = 0;
  private terminals: { turn: number; sourceSeq: number }[] = [];
  private openProjectedQuestions = false;
  private questionProjectionPresent = false;
  private attentionStopped = false;
  private activityEstablished = false;
  private retentionCut = false;
  private phoneInbox: Record<string, unknown> = {};
  private phoneQuestions: PendingQuestion[] = [];
  phoneSnapshot(): HostSnapshot {
    const snapshot = this.snapshot();
    if (this.unsupported || this.gap || this.historyCut) return snapshot;
    return { ...snapshot, pendingQueue: queueOf(this.phoneInbox), pendingQuestions: this.phoneQuestions, ...(this.phoneQuestions.length ? { activity: 'waiting' as const } : {}) };
  }
  notificationEvidence(): NotificationEvidence {
    const ambiguous = this.unsupported || this.gap || this.historyCut;
    const completed = ambiguous ? [] : this.terminals.filter(t => this.nodes.some(n => n.answerTurn === t.turn));
    return { sessionId: this.session.id, workspaceId: this.session.workspaceId, cursor: this.cursor, valid: !ambiguous,
      pending: !ambiguous && !this.attentionStopped && ((!!this.turn && this.approvals.size > 0) || (this.questionProjectionPresent ? this.openProjectedQuestions : this.questions.size > 0)),
      completed };
  }
  constructor(session: HostSession) { this.session = { ...session }; this.running = session.running; }
  open(frame: Record<string, unknown>, retentionCut = false): void {
    if (!Number.isSafeInteger(frame.cursor) || (frame.cursor as number) < -1 || !Array.isArray(frame.records)) throw new HostError('unavailable');
    this.cursor = frame.cursor as number;
    this.hasMore = frame.hasMore === true;
    // A complete log starts with no pending turn. An unresolved bounded surface
    // must still prove a newer control boundary before enabling the composer.
    this.activityEstablished = !this.hasMore;
    this.retentionCut = retentionCut;
    if (retentionCut) { this.historyCut = true; this.hasMore = true; }
    const values = object(object(frame.projections)?.values);
    this.phoneInbox = retentionCut ? {} : structuredClone(object(values?.inbox) ?? {});
    this.phoneQuestions = retentionCut ? [] : questionsOf(object(values?.userQuestions)?.active);
    if (typeof values?.title === 'string') this.session.title = retentionCut ? boundedTitle(values.title) : values.title;
    this.projectedQuestions = Array.isArray(object(values?.userQuestions)?.active) && (object(values?.userQuestions)!.active as unknown[]).length > 0;
    let previous: number | undefined;
    for (const record of frame.records) {
      const entry = object(record);
      if (entry?.type !== 'event') throw new HostError('unavailable');
      const event = wireEvent(entry.event);
      if ((previous !== undefined && event.seq !== previous + 1) || event.seq > this.cursor) this.markGap();
      this.event(event, false);
      previous = event.seq;
    }
    if ((previous ?? -1) !== this.cursor) this.markGap();
    // A cold read can contain repaired/orphaned brackets; the live summary owns
    // initial liveness. A later turn/start/status establishes current execution.
    this.running = this.session.running;
    const baseline = object(frame.assistantStream);
    this.revision = typeof baseline?.revision === 'number' ? baseline.revision : 0;
    const active = object(baseline?.activeAttempt);
    if (!retentionCut && active && typeof active.attemptId === 'string' && Number.isSafeInteger(active.nextIndex)) {
      this.attempt = { id: active.attemptId, turn: active.turn as number, step: active.step as number, nextIndex: active.nextIndex as number, createdAt: this.session.updatedAt, bytes: 0, blocks: new Map() };
      if (Array.isArray(active.stream)) {
        for (const value of active.stream) {
          const record = object(value);
          if (record?.type === 'text-chunks' && Number.isSafeInteger(record.index) && Array.isArray(record.texts)) {
            this.textChunk({ type: 'text-delta', index: record.index, text: record.texts.filter((text) => typeof text === 'string').join('') }, record.time0);
          } else if (record?.type === 'chunk') this.textChunk(record.chunk, record.time);
        }
      }
    }
    const activeQuestions = object(values?.userQuestions)?.active;
    if (Array.isArray(activeQuestions)) {
      // The runtime fold is authoritative for timed open/continued/settled calls;
      // an unmatched historic tool call cannot override its empty/continued view.
      this.questionProjectionPresent = true;
      this.openProjectedQuestions = !this.attentionStopped && activeQuestions.some(q => object(q)?.state === 'open');
      for (const q of activeQuestions) if (object(q)?.state === 'continued' && typeof object(q)?.callId === 'string') this.questions.delete(object(q)!.callId as string);
    }
    this.trim();
  }
  markGap(): void { this.gap = true; this.attempt = undefined; }
  get needsResync(): boolean { return this.gap; }
  get needsHistory(): boolean { return this.historyCut && !this.unsupported && !this.gap; }
  accept(observation: DshObservation): void {
    if (observation.type === 'status') {
      this.running = observation.running;
      if (!this.running) { this.turn = undefined; this.calls.clear(); }
      return;
    }
    if (observation.type === 'event') {
      const event = wireEvent(observation.event);
      if (event.seq <= this.cursor) return;
      if (event.seq !== this.cursor + 1) { this.markGap(); return; }
      this.cursor = event.seq;
      this.event(event, true);
      this.trim();
      return;
    }
    this.stream(observation.frame);
  }
  private event(event: WireEvent, live: boolean): void {
    if (!SURFACE_TYPES.has(event.type) && !KNOWN_LOG_TYPES.has(event.type)) {
      if (event.ignorable !== true) this.unsupported = true;
      return;
    }
    if (this.unsupported || this.gap) return;
    if (!this.retentionCut && !applySurface(this.nodes, event)) { this.historyCut = true; this.hasMore = true; }
    // Surface ancestry is independent of newest control events. Continue folding
    // turn/approval/question state even when an old replacement exceeds budget.
    const data = object(event.data);
    if (live && event.type === 'agent/inbox/spliced' && ['next-turn', 'next-step'].includes(data?.target as string)) {
      const target = data!.target as string;
      if (Number.isSafeInteger(data?.start) && Array.isArray(data?.inserted)) {
        const list = Array.isArray(this.phoneInbox[target]) ? this.phoneInbox[target] as unknown[] : [];
        list.splice(data!.start as number, typeof data!.removedCount === 'number' ? data!.removedCount : 0, ...data!.inserted);
        this.phoneInbox[target] = list;
      }
    }
    if (live && (event.type === 'tool/result' || event.type === 'user/message')) {
      const callId = event.type === 'tool/result' ? object(data?.message)?.toolCallId : object(data?.source)?.kind === 'user-question-reply' ? object(data?.source)?.callId : undefined;
      if (typeof callId === 'string') this.phoneQuestions = this.phoneQuestions.filter(q => q.callId !== callId);
    }
    if (event.type === 'turn/start') {
      this.activityEstablished = true;
      this.attentionStopped = false; this.approvals.clear(); this.questions.clear(); this.openProjectedQuestions = false;
      this.calls.clear(); this.failure = undefined;
      this.turn = Number.isSafeInteger(data?.turn) ? { number: data!.turn as number, startedAt: event.time } : undefined;
    }
    if (event.type === 'turn/end') {
      const reason = object(data?.reason);
      if (reason?.kind === 'error') this.setFailure(reason.error);
      else this.failure = undefined;
      if (!this.retentionCut && reason?.kind === 'completed' && Number.isSafeInteger(data?.turn)) this.terminals.push({ turn: data!.turn as number, sourceSeq: event.seq });
      this.activityEstablished = true;
      this.attentionStopped = true; this.approvals.clear(); this.questions.clear(); this.openProjectedQuestions = false;
      this.turn = undefined; this.calls.clear();
    }
    if (event.type === 'tool/call' && this.turn?.number === data?.turn && typeof data?.callId === 'string' && typeof data.name === 'string' && /^[A-Za-z0-9_.:/-]{1,128}$/.test(data.name)) {
      if (this.calls.size < 1000 && (!this.retentionCut || Buffer.byteLength(data.callId) <= 256)) this.calls.set(data.callId, data.name);
      else { this.turn = undefined; this.calls.clear(); } // Never grow unbounded or report an unproven latest call.
    }
    if (event.type === 'tool/result') {
      const message = object(data?.message);
      if (typeof message?.toolCallId === 'string') this.calls.delete(message.toolCallId);
    }
    if (event.type === 'session/title' && typeof data?.title === 'string') this.session.title = this.historyCut ? boundedTitle(data.title) : data.title;
    if (event.type === 'user/message') this.session.updatedAt = Math.max(this.session.updatedAt, event.time);
    if (live && event.type === 'turn/start') this.running = true;
    if (live && event.type === 'turn/end') { this.running = false; this.attempt = undefined; }
    if (event.type === 'assistant/message') this.failure = undefined;
    if (event.type === 'assistant/attempt') {
      const stream = Array.isArray(data?.stream) ? data.stream : [];
      for (const entry of stream) {
        const chunk = object(object(entry)?.chunk);
        const reason = object(chunk?.reason);
        if (chunk?.type !== 'finish' || reason?.kind !== 'error') continue;
        this.setFailure(reason.failure ?? reason.error);
      }
    }
    if ((event.type === 'assistant/message' || event.type === 'assistant/attempt') && this.attempt?.turn === data?.turn && this.attempt?.step === data?.step) this.attempt = undefined;
    // Keep the desktop historical notice; notification evidence additionally
    // requires an open turn for the runtime's turn-enclosed approval audit pair.
    if (event.type === 'approval/asked' && typeof data?.id === 'string') {
      if (this.retentionCut && (this.approvals.size >= 1000 || Buffer.byteLength(data.id) > 256)) this.unsupported = true;
      else this.approvals.add(data.id);
    }
    if (event.type === 'approval/decided' && typeof data?.id === 'string') this.approvals.delete(data.id);
    if (event.type === 'tool/call' && data?.name === 'ask_user_question' && typeof data.callId === 'string') {
      if (this.retentionCut && (this.questions.size >= 1000 || Buffer.byteLength(data.callId) > 256)) this.unsupported = true;
      else this.questions.add(data.callId);
    }
    if (event.type === 'tool/result') {
      const message = object(data?.message);
      if (typeof message?.toolCallId === 'string') this.questions.delete(message.toolCallId);
    }
  }
  private stream(value: unknown): void {
    const frame = object(value);
    if (!frame || typeof frame.revision !== 'number' || typeof frame.attemptId !== 'string' || this.unsupported || this.retentionCut || this.gap) return;
    if (frame.type === 'start' && frame.revision === 1) this.revision = 0;
    if (frame.revision <= this.revision) return;
    if (frame.revision !== this.revision + 1) { this.markGap(); return; }
    this.revision = frame.revision;
    if (frame.type === 'start') {
      this.attempt = { id: frame.attemptId, turn: frame.turn as number, step: frame.step as number, nextIndex: 0, createdAt: this.session.updatedAt, bytes: 0, blocks: new Map() };
      this.running = true;
      return;
    }
    const attempt = this.attempt;
    const outcome = object(frame.outcome);
    // Settlement is durable BEFORE the matching end notification. Its event
    // has already removed provisional text; that normal end is not a gap.
    if (!attempt && frame.type === 'end' && outcome?.kind === 'committed' && Number.isSafeInteger(outcome.seq) && (outcome.seq as number) <= this.cursor) return;
    if (!attempt || attempt.id !== frame.attemptId || frame.index !== attempt.nextIndex) { this.markGap(); return; }
    if (frame.type === 'chunk') { attempt.nextIndex++; this.textChunk(frame.chunk, frame.time); }
    if (frame.type === 'end') this.attempt = undefined;
  }
  private textChunk(value: unknown, time: unknown): void {
    const chunk = object(value);
    const attempt = this.attempt;
    if (!chunk || !attempt || !Number.isSafeInteger(chunk.index)) return;
    const index = chunk.index as number;
    if (chunk.type === 'text-delta' && typeof chunk.text === 'string') {
      if (attempt.blocks.size === 0 && typeof time === 'number') attempt.createdAt = time;
      const current = attempt.blocks.get(index) ?? '';
      const next = current + chunk.text;
      if (!this.storeProvisional(attempt, index, current, next)) return;
    } else if (chunk.type === 'block-end') {
      const block = object(chunk.block);
      if (block?.type === 'text' && typeof block.text === 'string') {
        if (attempt.blocks.size === 0 && typeof time === 'number') attempt.createdAt = time;
        const current = attempt.blocks.get(index) ?? '';
        if (!this.storeProvisional(attempt, index, current, block.text)) return; // block-end replaces accumulated deltas, never doubles them
      }
    }
  }
  private setFailure(value: unknown): void {
    const error = object(value);
    const code = typeof error?.code === 'string' && /^[A-Za-z0-9_.:-]{1,64}$/.test(error.code) ? error.code : undefined;
    const message = typeof error?.message === 'string'
      ? error.message.replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 512) : '';
    this.failure = { ...(code ? { code } : {}), message: message || 'The turn failed.' };
  }
  /** Bounds one attempt before joining/sorting; overflow invalidates provisional text and demands a fresh cut. */
  private storeProvisional(attempt: Provisional, index: number, previous: string, next: string): boolean {
    if (!attempt.blocks.has(index) && attempt.blocks.size >= MAX_PROVISIONAL_BLOCKS) { this.markGap(); return false; }
    const delta = Buffer.byteLength(next, 'utf8') - Buffer.byteLength(previous, 'utf8');
    if (attempt.bytes + delta > MAX_PROVISIONAL_BYTES) { this.markGap(); return false; }
    attempt.bytes += delta;
    attempt.blocks.set(index, next);
    return true;
  }
  private trim(): void {
    const visible = this.nodes.filter((entry) => entry.message);
    if (visible.length > MAX_MESSAGES) {
      const first = visible[visible.length - MAX_MESSAGES]!;
      this.nodes = this.nodes.slice(this.nodes.indexOf(first));
      this.hasMore = true;
    }
    if (this.nodes.length > 1000) { this.nodes = this.nodes.slice(-1000); this.hasMore = true; }
  }
  snapshot(): HostSnapshot {
    const ambiguous = this.unsupported || this.retentionCut || this.gap;
    const messages = ambiguous ? [] : this.nodes.flatMap((node) => node.message ? [{ ...node.message }] : []);
    const provisionalText = this.attempt ? [...this.attempt.blocks].sort(([a], [b]) => a - b).map(([, text]) => text).join('') : '';
    if (this.attempt && provisionalText && !ambiguous) messages.push({ id: `provisional:${this.attempt.id}`, role: 'assistant', text: provisionalText, createdAt: this.attempt.createdAt, provisional: true });
    const waiting = this.approvals.size > 0 || this.questions.size > 0 || this.projectedQuestions;
    const knownActivity = !this.unsupported && !this.gap && (!this.historyCut || this.activityEstablished);
    return {
      session: { ...this.session, running: this.running }, ...(this.session.runningSubagents !== undefined ? { runningSubagents: this.session.runningSubagents } : {}), messages: messages.slice(-MAX_MESSAGES), cursor: this.cursor,
      hasMore: this.hasMore || messages.length > MAX_MESSAGES,
      activity: !knownActivity ? 'unknown' : waiting ? 'waiting' : this.running ? 'running' : 'idle',
      ...(knownActivity && this.running && this.turn ? { activityDetail: { turnStartedAt: this.turn.startedAt, ...([...this.calls.values()].at(-1) ? { tool: [...this.calls.values()].at(-1)! } : {}) } } : {}),
      ...(this.unsupported ? { notice: UNKNOWN_NOTICE } : this.historyCut ? { notice: HISTORY_CUT_NOTICE } : this.gap ? { notice: GAP_NOTICE } : waiting ? { notice: WAIT_NOTICE } : {}),
      ...(this.failure ? { failure: { ...this.failure } } : {}),
    };
  }
}

function waitForWake(register: (wake: () => void) => void, timeoutMs: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    let timer: ReturnType<typeof setTimeout>;
    const done = () => { clearTimeout(timer); signal.removeEventListener('abort', done); register(() => {}); resolve(); };
    register(done);
    timer = setTimeout(done, timeoutMs);
    signal.addEventListener('abort', done, { once: true });
    if (signal.aborted) done();
  });
}

export async function createDshAdapter(options: DshAdapterOptions): Promise<DshAdapter> {
  if (!isCompatibleDshVersion(options.dshVersion)) throw new HostError('unsupported_dsh_version');
  if (options.workspaceSource && options.workspaces?.length) throw new HostError('invalid_config');
  if (options.realpathCacheMaxEntries !== undefined && (!Number.isInteger(options.realpathCacheMaxEntries) || options.realpathCacheMaxEntries < 1 || options.realpathCacheMaxEntries > 4096)) throw new HostError('invalid_config');
  const source = options.workspaceSource ?? await createWorkspaceSource({ workspaces: [...options.workspaces ?? []] });
  return new DshAdapter(options, source);
}

export class DshAdapter implements HostAdapter {
  readonly upstreamVersion: string;
  private readonly options: DshAdapterOptions;
  private readonly source: WorkspaceSource;
  private readonly lifetime = new AbortController();
  private readonly history = new Map<string, HistoryCacheEntry>();
  private historyBytes = 0;
  private historyScope = '';
  private readonly historyFlights = new Map<string, HistoryFlight>();
  private readonly openingFlights = new Map<string, OpeningFlight>();
  private readonly disposeHistory: (() => void) | undefined;
  private readonly evidenceScopes = new WeakMap<NotificationEvidence, { view: readonly WorkspaceConfig[]; revision: number; lifecycle: object }>();
  private evidenceRevision = 0;
  private readonly lifecycles = new Map<string, object>();
  private lifecycleToken(id: string): object {
    let token = this.lifecycles.get(id);
    if (!token) {
      // Bounded invalidation registry; rotation fails closed for old tokens.
      if (this.lifecycles.size >= 10000) this.lifecycles.clear();
      token = {}; this.lifecycles.set(id, token);
    }
    return token;
  }
  private readonly openingCapacity = new Set<() => void>();
  private readonly historyCapacity = new Set<() => void>();
  // Saturation is backpressure, not a failed authoritative read. Waiters are
  // bounded, abortable and wake only when a flight releases capacity.
  private waitCapacity(waiters: Set<() => void>, active: AbortSignal): Promise<void> {
    active.throwIfAborted();
    if (waiters.size >= 512) throw new HostError('unavailable');
    return new Promise((resolve, reject) => {
      const wake = () => { waiters.delete(wake); active.removeEventListener('abort', abort); resolve(); };
      const abort = () => { waiters.delete(wake); active.removeEventListener('abort', abort); reject(active.reason); };
      waiters.add(wake); active.addEventListener('abort', abort, { once: true });
    });
  }
  private releaseCapacity(waiters: Set<() => void>): void { for (const wake of [...waiters]) wake(); }
  private checkLifecycle(id: string, revision: object): void { if (revision !== this.lifecycles.get(id)) throw new HostError('not_found'); }
  private evictHistory(id: string): void {
    const entry = this.history.get(id);
    if (entry) { this.historyBytes -= entry.bytes; this.history.delete(id); }
  }
  private detachHistory(id: string, flight: HistoryFlight): void {
    if (this.historyFlights.get(id) === flight) this.historyFlights.delete(id);
    this.releaseCapacity(this.historyCapacity);
    flight.controller.abort(); // Late upstream settlement cannot admit cache or remove a replacement flight.
    for (const waiter of [...flight.waiters]) waiter({ reason: flight.controller.signal.reason });
  }
  private cacheHistory(id: string, entry: HistoryCacheEntry): void {
    this.evictHistory(id);
    while (this.history.size >= MAX_HISTORY_CACHE_ENTRIES || this.historyBytes + entry.bytes > MAX_HISTORY_CACHE_BYTES) this.evictHistory(this.history.keys().next().value!);
    this.history.set(id, entry); this.historyBytes += entry.bytes;
  }
  constructor(options: DshAdapterOptions, source: WorkspaceSource) {
    this.options = options; this.source = source; this.upstreamVersion = options.dshVersion;
    this.disposeHistory = options.events?.subscribeDisposed?.(id => { this.lifecycles.delete(id); this.evictHistory(id); const flight = this.historyFlights.get(id); if (flight) this.detachHistory(id, flight); const opening = this.openingFlights.get(id); if (opening) this.detachOpening(id, opening); });
  }
  notifications(): NotificationSources | undefined {
    const subscribe = this.options.events?.subscribeNotifications;
    if (!subscribe) return undefined;
    const bindings = new WeakMap<object, { view: readonly WorkspaceConfig[]; revision: object }>();
    return { subscribe, list: signal => this.listSessions(signal),
      ...(this.options.sessionHeader ? { lookup: async (id: string, signal: AbortSignal) => {
        // Header stat is membership authority, not a cached catalog or phone index.
        try {
          const revision = this.lifecycleToken(id);
          const { workspaceId, view } = await this.pointScope(id, this.signal(signal));
          this.checkLifecycle(id, revision);
          const listed = { id, workspaceId }; bindings.set(listed, { view, revision }); return listed;
        } catch (error) { if (error instanceof HostError && error.code === 'not_found') return undefined; throw error; }
      }, lookupCurrent: (listed: { id: string; workspaceId: string }) => {
        const binding = bindings.get(listed);
        return !!binding && binding.revision === this.lifecycles.get(listed.id) && !this.lifetime.signal.aborted && (!this.source.isCurrent || this.source.isCurrent(binding.view)) && !this.source.archivedSessionIds().has(listed.id);
      } } : {}), evidence: async (id, signal, listed) => {
      const lifecycle = this.lifecycleToken(id);
      const { transcript, view } = await this.open(id, this.signal(signal), undefined, listed ? { ...listed, running: listed.running ?? false, title: '', updatedAt: 0 } : undefined);
      this.checkLifecycle(id, lifecycle);
      const proof = transcript.notificationEvidence(); this.evidenceScopes.set(proof, { view, revision: this.evidenceRevision, lifecycle }); return proof;
    }, evidenceCurrent: proof => {
      const binding = this.evidenceScopes.get(proof);
      return !!binding && !this.lifetime.signal.aborted && binding.revision === this.evidenceRevision && binding.lifecycle === this.lifecycles.get(proof.sessionId) && (!this.source.isCurrent || this.source.isCurrent(binding.view)) && JSON.stringify(binding.view) === this.historyScope && !this.source.archivedSessionIds().has(proof.sessionId);
    } };
  }
  dispose(): void {
    this.lifetime.abort(); this.disposeHistory?.();
    for (const [id, flight] of this.historyFlights) this.detachHistory(id, flight);
    for (const [id, flight] of this.openingFlights) this.detachOpening(id, flight);
    this.history.clear(); this.historyBytes = 0;
  }
  private signal(signal: AbortSignal): AbortSignal { return AbortSignal.any([signal, this.lifetime.signal]); }
  private async workspaceMap(): Promise<{ workspaces: ReadonlyMap<string, CanonicalWorkspace>; view: readonly WorkspaceConfig[] }> {
    const view = await this.source.list();
    const signature = JSON.stringify(view);
    if (signature !== this.historyScope) {
      this.history.clear(); this.historyBytes = 0; this.historyScope = signature; this.evidenceRevision++;
      for (const [id, flight] of this.historyFlights) this.detachHistory(id, flight);
    }
    const archived = this.source.archivedSessionIds();
    for (const [id, entry] of this.history) if ((this.source.isCurrent && !this.source.isCurrent(entry.view)) || archived.has(id)) this.evictHistory(id);
    for (const [id, flight] of this.historyFlights) if ((this.source.isCurrent && !this.source.isCurrent(flight.view)) || archived.has(id)) this.detachHistory(id, flight);
    return { view, workspaces: new Map(view.map(item => [workspacePathKey(item.path), { ...item, key: workspacePathKey(item.path) }])) };
  }
  private checkCurrent(view: readonly WorkspaceConfig[], sessionId?: string): void {
    if ((this.source.isCurrent && !this.source.isCurrent(view)) || (sessionId && this.source.archivedSessionIds().has(sessionId))) {
      if (sessionId) this.evictHistory(sessionId);
      throw new HostError('not_found');
    }
  }
  private canonical(cwd: unknown, paths?: Map<string, Promise<string | undefined>>): Promise<string | undefined> {
    if (typeof cwd !== 'string' || !isAbsolute(cwd)) return Promise.resolve(undefined);
    const cached = paths?.get(cwd);
    if (cached) { paths!.delete(cwd); paths!.set(cwd, cached); return cached; }
    const value = (this.options.realpath ?? realpath)(cwd).then(workspacePathKey, () => undefined);
    if (paths) {
      const maxEntries = this.options.realpathCacheMaxEntries ?? 1024;
      while (paths.size >= maxEntries) paths.delete(paths.keys().next().value!);
      paths.set(cwd, value);
    }
    return value;
  }
  private async workspace(cwd: unknown, workspaces: ReadonlyMap<string, CanonicalWorkspace>, paths?: Map<string, Promise<string | undefined>>): Promise<CanonicalWorkspace | undefined> {
    const key = await this.canonical(cwd, paths);
    return key === undefined ? undefined : workspaces.get(key);
  }
  private toSession(row: DshSessionSummary, workspace: CanonicalWorkspace): HostSession {
    return { id: row.sessionId, title: typeof row.projections?.values.title === 'string' ? row.projections.values.title : '', workspaceId: workspace.id, updatedAt: row.updatedAt, running: row.running, ...(typeof row.blank === 'boolean' ? { blank: row.blank } : {}), waitingForAnswer: questionsOf(record(row.projections?.values.userQuestions)?.active).length > 0 || (this.options.questionBridge?.pending(row.sessionId).length ?? 0) > 0, ...(row.projections?.values.modelSelection !== undefined ? { model: sessionModel(record(row.projections.values.modelSelection)?.next) } : {}) };
  }
  private async permitted(sessionId: string, signal: AbortSignal, expectedWorkspaceId?: string): Promise<{ row: DshSessionSummary; session: HostSession; view: readonly WorkspaceConfig[] }> {
    const revision = this.lifecycleToken(sessionId);
    signal.throwIfAborted();
    const rows = await this.options.sessionController.list({}, signal);
    const row = rows.items.find((item) => item.sessionId === sessionId && item.origin !== 'subagent');
    const { workspaces, view } = await this.workspaceMap();
    const workspace = row && !this.source.archivedSessionIds().has(sessionId) && await this.workspace(row.cwd, workspaces);
    signal.throwIfAborted();
    this.checkCurrent(view, sessionId);
    if (!row || !workspace || (expectedWorkspaceId !== undefined && workspace.id !== expectedWorkspaceId)) { this.evictHistory(sessionId); throw new HostError('not_found'); }
    this.checkLifecycle(sessionId, revision);
    return { row, session: this.toSession(row, workspace), view };
  }
  async listPresets(signal: AbortSignal): Promise<Preset[]> {
    const active = this.signal(signal); active.throwIfAborted();
    if (!this.options.agentPresets) return [];
    const roster = await this.options.agentPresets.remoteExportList();
    active.throwIfAborted();
    return roster.presets.filter((item) => item.broken === undefined).map((item) => ({ id: item.id, name: item.name || item.id }));
  }
  async listSessions(signal: AbortSignal): Promise<HostSession[]> {
    const active = this.signal(signal);
    active.throwIfAborted();
    const rows = await this.options.sessionController.list({}, active);
    const { workspaces, view } = await this.workspaceMap(), archived = this.source.archivedSessionIds();
    const sessions: HostSession[] = [];
    // Fresh per request: a junction retarget must not combine new metadata with
    // an old authorization binding. Many sessions share a cwd, so deduplicate
    // only within this bounded list operation, never across requests.
    const paths = new Map<string, Promise<string | undefined>>();
    for (const row of rows.items) {
      if (row.origin === 'subagent' || archived.has(row.sessionId)) continue;
      const workspace = await this.workspace(row.cwd, workspaces, paths);
      if (workspace) sessions.push(this.toSession(row, workspace));
    }
    active.throwIfAborted();
    this.checkCurrent(view);
    const visible = new Set(sessions.map(session => session.id));
    for (const id of this.history.keys()) if (!visible.has(id)) this.evictHistory(id);
    for (const [id, flight] of this.historyFlights) if (!visible.has(id)) this.detachHistory(id, flight);
    const counts = this.options.runningSubagentCounts?.();
    if (counts) for (const session of sessions) session.runningSubagents = counts.get(session.id) ?? 0;
    return sessions.sort((a, b) => b.updatedAt - a.updatedAt);
  }
  async sessionScope(sessionId: string, signal: AbortSignal): Promise<string> {
    return (await this.pointScope(sessionId, this.signal(signal))).workspaceId;
  }
  private async pointScope(sessionId: string, active: AbortSignal): Promise<{ workspaceId: string; view: readonly WorkspaceConfig[]; revision: object }> {
    const revision = this.lifecycleToken(sessionId);
    active.throwIfAborted();
    if (this.source.archivedSessionIds().has(sessionId)) throw new HostError('not_found');
    // Legacy controller-only compositions retain their original list authority;
    // the installed plugin supplies a fresh point stat, never a catalog scan.
    if (!this.options.sessionHeader) { const { session, view } = await this.permitted(sessionId, active); this.checkLifecycle(sessionId, revision); return { workspaceId: session.workspaceId, view, revision }; }
    const header = object(await this.options.sessionHeader(sessionId, active));
    active.throwIfAborted();
    if (!header || header.id !== sessionId || header.origin === 'subagent') throw new HostError('not_found');
    const { workspaces, view } = await this.workspaceMap();
    const workspace = await this.workspace(header.cwd, workspaces);
    active.throwIfAborted(); this.checkCurrent(view, sessionId);
    if (!workspace) throw new HostError('not_found');
    this.checkLifecycle(sessionId, revision);
    return { workspaceId: workspace.id, view, revision };
  }
  async snapshot(sessionId: string, signal: AbortSignal): Promise<HostSnapshot> {
    const { transcript, view } = await this.open(sessionId, this.signal(signal));
    this.checkCurrent(view, sessionId);
    return this.phoneSnapshot(transcript);
  }
  private detachOpening(id: string, flight: OpeningFlight): void {
    if (this.openingFlights.get(id) === flight) this.openingFlights.delete(id);
    this.releaseCapacity(this.openingCapacity);
    flight.controller.abort();
    for (const waiter of [...flight.waiters]) waiter(undefined, { reason: flight.controller.signal.reason });
  }
  private async readOpening(sessionId: string, active: AbortSignal, admit?: (frame: Record<string, unknown>) => Promise<void>): Promise<Record<string, unknown>> {
    const cleanup = new AbortController();
    const readSignal = AbortSignal.any([active, cleanup.signal]);
    const iterator = this.options.sessionController.follow({ address: { kind: 'session', sessionId }, assistantStream: true, maxMessages: MAX_MESSAGES }, readSignal)[Symbol.asyncIterator]();
    try {
      const first = await iterator.next(); active.throwIfAborted();
      const frame = object(first.value);
      if (first.done || frame?.type !== 'snapshot' || object(frame.header)?.id !== sessionId) throw new HostError('unavailable');
      await admit?.(frame);
      return frame;
    } catch (error) {
      const remote = object(error);
      if (remote?.isDSHRemoteError === true && (remote.code === 'session/not-found' ||
          (remote.code === 'session/agent-busy' && object(remote.details)?.reason === 'use subagent delivery for this child session'))) throw new HostError('not_found');
      throw error;
    } finally {
      // rc.2 promotes only AFTER its first yield: never request a second frame.
      cleanup.abort(); await iterator.return?.();
    }
  }
  private async opening(sessionId: string, active: AbortSignal): Promise<Record<string, unknown>> {
    active.throwIfAborted();
    while (!this.openingFlights.has(sessionId) && this.openingFlights.size >= MAX_HISTORY_CACHE_ENTRIES) {
      await this.waitCapacity(this.openingCapacity, active); active.throwIfAborted();
    }
    let flight = this.openingFlights.get(sessionId);
    const fresh = !flight;
    if (!flight) {
      flight = { controller: new AbortController(), waiters: new Set() };
      this.openingFlights.set(sessionId, flight);
    }
    const owned = flight;
    if (owned.waiters.size >= MAX_HISTORY_WAITERS) throw new HostError('unavailable');
    const waiting = new Promise<Record<string, unknown>>((resolve, reject) => {
      const finish: OpeningWaiter = (frame, error) => {
        if (!owned.waiters.delete(finish)) return;
        active.removeEventListener('abort', aborted);
        if (error) reject(error.reason); else resolve(frame!);
        if (!owned.waiters.size && this.openingFlights.get(sessionId) === owned) this.detachOpening(sessionId, owned);
      };
      const aborted = () => finish(undefined, { reason: active.reason });
      owned.waiters.add(finish); active.addEventListener('abort', aborted, { once: true });
    });
    if (fresh) {
      const finish = (frame?: Record<string, unknown>, error?: { reason: unknown }) => {
        if (this.openingFlights.get(sessionId) !== owned) return;
        this.openingFlights.delete(sessionId);
        this.releaseCapacity(this.openingCapacity);
        for (const waiter of [...owned.waiters]) waiter(frame, error);
      };
      void this.readOpening(sessionId, this.signal(owned.controller.signal)).then(frame => finish(frame), reason => finish(undefined, { reason }));
    }
    return waiting;
  }
  private async open(sessionId: string, active: AbortSignal, expectedWorkspaceId?: string, listed?: HostSession): Promise<{ transcript: Transcript; view: readonly WorkspaceConfig[] }> {
    const revision = this.lifecycleToken(sessionId);
    let session = listed ?? (!this.options.sessionStatus || expectedWorkspaceId !== undefined ? (await this.permitted(sessionId, active, expectedWorkspaceId)).session : undefined);
    // Mutations and legacy controller-only compositions keep their original
    // admission/cleanup ordering. Coalesced openings carry no scope bindings.
    let transcript: Transcript | undefined, view: readonly WorkspaceConfig[] | undefined;
    let retentionCut = false;
    const admit = async (frame: Record<string, unknown>) => {
      active.throwIfAborted();
      const header = object(frame.header)!;
      if (header.origin === 'subagent') throw new HostError('not_found');
      if (!session) {
        const status = this.options.sessionStatus!(sessionId);
        if (typeof status.running !== 'boolean' || !Number.isSafeInteger(status.updatedAt) || status.updatedAt < 0 || !Number.isSafeInteger(header.createdAt) || (header.createdAt as number) < 0) throw new HostError('unavailable');
        session = { id: sessionId, title: '', workspaceId: '', running: status.running, updatedAt: Math.max(header.createdAt as number, status.updatedAt) };
      }
      const scope = await this.workspaceMap(); view = scope.view;
      const workspace = await this.workspace(header.cwd, scope.workspaces);
      this.checkCurrent(view, sessionId);
      if (!workspace || (session.workspaceId && workspace.id !== session.workspaceId)) { this.evictHistory(sessionId); throw new HostError('not_found'); }
      const counts = this.options.runningSubagentCounts?.();
      session = { ...session, workspaceId: workspace.id, ...(counts ? { runningSubagents: counts.get(sessionId) ?? 0 } : {}) };
      const metadata = object(object(object(frame.projections)?.values)?.sessionListMetadata);
      if (typeof metadata?.blank === 'boolean') session.blank = metadata.blank;
      if (typeof metadata?.lastPromptAt === 'number') session.updatedAt = Math.max(session.updatedAt, metadata.lastPromptAt);
      if (!Array.isArray(frame.records)) throw new HostError('unavailable');
      retentionCut = Buffer.byteLength(JSON.stringify(frame), 'utf8') > MAX_HISTORY_BYTES;
      if (retentionCut) this.evictHistory(sessionId);
      transcript = new Transcript(session);
      transcript.open(frame, retentionCut); // Oversized input folds control only, never surface text/stream.
    };
    const shared = expectedWorkspaceId === undefined && this.options.sessionStatus;
    const frame = await (shared ? this.opening(sessionId, active) : this.readOpening(sessionId, active, admit));
    if (shared) await admit(frame);
    active.throwIfAborted();
    if (!transcript || !view || !session) throw new HostError('unavailable');
    this.checkCurrent(view, sessionId); // Iterator cleanup itself may yield.
    if (transcript.needsHistory && !retentionCut) transcript = await this.recoverShared(session, frame, view, active);
    active.throwIfAborted(); this.checkCurrent(view, sessionId);
    const phone = new PhoneProjection(); if (!retentionCut) phone.open(frame); phoneProjections.set(transcript, phone);
    this.checkLifecycle(sessionId, revision);
    return { transcript, view };
  }
  private joinHistory(id: string, flight: HistoryFlight, active: AbortSignal): Promise<void> {
    active.throwIfAborted();
    if (flight.controller.signal.aborted || this.historyFlights.get(id) !== flight) throw new HostError('unavailable');
    if (flight.waiters.size >= MAX_HISTORY_WAITERS) throw new HostError('unavailable');
    return new Promise<void>((resolve, reject) => {
      const finish: HistoryWaiter = error => {
        if (!flight.waiters.delete(finish)) return;
        active.removeEventListener('abort', aborted);
        if (error) reject(error.reason); else resolve();
        if (flight.waiters.size === 0 && this.historyFlights.get(id) === flight) this.detachHistory(id, flight);
      };
      const aborted = () => finish({ reason: active.reason });
      flight.waiters.add(finish);
      active.addEventListener('abort', aborted, { once: true });
    });
  }
  private async recoverShared(session: HostSession, frame: Record<string, unknown>, view: readonly WorkspaceConfig[], active: AbortSignal): Promise<Transcript> {
    // Callers keep their own opening/projections. Only immutable ancestry work is shared.
    for (;;) {
      const current = this.historyFlights.get(session.id);
      if (!current) {
        if (this.historyFlights.size < MAX_HISTORY_CACHE_ENTRIES) break;
        await this.waitCapacity(this.historyCapacity, active); active.throwIfAborted(); this.checkCurrent(view, session.id); continue;
      }
      if (current.controller.signal.aborted) { this.detachHistory(session.id, current); continue; }
      await this.joinHistory(session.id, current, active); active.throwIfAborted(); this.checkCurrent(view, session.id);
    }
    active.throwIfAborted(); this.checkCurrent(view, session.id);
    const flight: HistoryFlight = { controller: new AbortController(), waiters: new Set(), view };
    this.historyFlights.set(session.id, flight);
    const waiting = this.joinHistory(session.id, flight, active);
    let transcript!: Transcript;
    const finish = (error?: { reason: unknown }) => {
      if (this.historyFlights.get(session.id) !== flight) return;
      this.historyFlights.delete(session.id);
      this.releaseCapacity(this.historyCapacity);
      for (const waiter of [...flight.waiters]) waiter(error);
    };
    // One completion handler per flight, not per waiter; aborted waiters release registrations immediately.
    void this.recoverHistory(session, frame, view, this.signal(flight.controller.signal)).then(value => { transcript = value; finish(); }, reason => finish({ reason }));
    await waiting;
    return transcript;
  }
  private async recoverHistory(session: HostSession, frame: Record<string, unknown>, view: readonly WorkspaceConfig[], active: AbortSignal): Promise<Transcript> {
    const sessionId = session.id;
    let transcript = new Transcript(session); transcript.open(frame);
    let records = frame.records as unknown[];
    let hasMore = frame.hasMore === true;
    let bytes = Buffer.byteLength(JSON.stringify(records), 'utf8');
    let pages = 1;
    let exhausted = false;
    const scope = JSON.stringify([session.workspaceId, object(frame.header)?.cwd, view]);
    const cached = this.history.get(sessionId);
    // Exhaustion is proved only for the exact cut and recovery budget attempted.
    if (cached && cached.scope === scope && cached.throughSeq <= transcript.cursor && (!cached.exhausted || cached.throughSeq === transcript.cursor)) {
      const prefix = JSON.parse(cached.json) as unknown[];
      const firstSeq = wireEvent(object(records[0])?.event).seq;
      // Immutable raw-log overlap validates both the scope binding and the cut.
      if (firstSeq >= cached.firstSeq && firstSeq <= cached.throughSeq && records.filter(r => wireEvent(object(r)?.event).seq <= cached.throughSeq).every(r => {
        const seq = wireEvent(object(r)?.event).seq;
        return JSON.stringify(r) === JSON.stringify(prefix[seq - cached.firstSeq]);
      })) {
        const combined = [...prefix.filter(r => wireEvent(object(r)?.event).seq < firstSeq), ...records];
        const combinedBytes = Buffer.byteLength(JSON.stringify(combined), 'utf8');
        if (combinedBytes <= MAX_HISTORY_BYTES) {
          records = combined; bytes = combinedBytes; hasMore = cached.hasMore; pages = cached.pages; exhausted = cached.exhausted;
          transcript = new Transcript(session); transcript.open({ ...frame, records, hasMore });
          this.history.delete(sessionId); this.history.set(sessionId, cached);
        } else this.evictHistory(sessionId); // An oversized candidate says nothing about recovery from this smaller opening.
      } else this.evictHistory(sessionId);
    } else if (cached) this.evictHistory(sessionId);
    while (transcript.needsHistory && !exhausted && hasMore && pages < MAX_HISTORY_PAGES && bytes <= MAX_HISTORY_BYTES) {
      active.throwIfAborted();
      const beforeSeq = wireEvent(object(records[0])?.event).seq;
      if (beforeSeq === 0) throw new HostError('unavailable');
      const page = object(await this.options.sessionController.page({ address: { kind: 'session', sessionId }, throughSeq: transcript.cursor, beforeSeq, maxMessages: MAX_MESSAGES }, active));
      active.throwIfAborted();
      this.checkCurrent(view, sessionId);
      if (!page || !Array.isArray(page.records) || page.records.length === 0) throw new HostError('unavailable');
      // Never merge overlapping, skipped or forward records into this exact cut.
      let expected: number | undefined;
      for (const record of page.records) {
        const entry = object(record);
        if (entry?.type !== 'event') throw new HostError('unavailable');
        const event = wireEvent(entry.event);
        if (event.seq >= beforeSeq || (expected !== undefined && event.seq !== expected)) throw new HostError('unavailable');
        expected = event.seq + 1;
      }
      const firstSeq = wireEvent(object(page.records[0])?.event).seq;
      if (expected !== beforeSeq || typeof page.hasMore !== 'boolean' || page.hasMore !== (firstSeq > 0)) throw new HostError('unavailable');
      bytes += Buffer.byteLength(JSON.stringify(page.records), 'utf8');
      if (bytes > MAX_HISTORY_BYTES) { exhausted = true; break; } // Keep the last bounded, honest cut.
      pages++;
      records = [...page.records, ...records];
      hasMore = page.hasMore;
      transcript = new Transcript(session);
      transcript.open({ ...frame, records, hasMore });
    }
    if (transcript.needsHistory && !hasMore) throw new HostError('unavailable'); // Complete ancestry must resolve every replacement.
    active.throwIfAborted(); this.checkCurrent(view, sessionId);
    const json = JSON.stringify(records);
    const retainedBytes = Buffer.byteLength(json, 'utf8');
    if (retainedBytes <= MAX_HISTORY_BYTES && !transcript.needsResync) this.cacheHistory(sessionId, { view, scope, firstSeq: wireEvent(object(records[0])?.event).seq, throughSeq: transcript.cursor, json, bytes: retainedBytes, hasMore, pages, exhausted: exhausted || (transcript.needsHistory && pages >= MAX_HISTORY_PAGES) });
    return transcript;
  }
  async *watch(sessionId: string, signal: AbortSignal): AsyncIterable<HostSnapshot> {
    const active = this.signal(signal);
    const pollMs = this.options.pollIntervalMs ?? 1000;
    const throttleMs = this.options.throttleMs ?? 100;
    let transcript: Transcript | undefined;
    let changed = false;
    let resync = false;
    let wake: () => void = () => {};
    let queue: DshObservation[] = [];
    const unsubscribe = this.options.events?.subscribe(sessionId, (observation) => {
      if (active.aborted) return;
      if (!transcript) {
        if (queue.length < 500) queue.push(observation); else resync = true;
      } else {
        try { const before = transcript.cursor; transcript.accept(observation); if (observation.type === 'event' && transcript.cursor > before) phoneProjections.get(transcript)?.event(observation.event); } catch { transcript.markGap(); }
        resync ||= transcript.needsResync;
        changed = true;
      }
      wake();
    });
    let lastPublished = 0;
    let lastOpened = Date.now();
    let nextPoll = lastOpened + pollMs;
    let lastValue = '';
    try {
      const initialView = await this.open(sessionId, active);
      this.checkCurrent(initialView.view, sessionId);
      transcript = initialView.transcript;
      for (const observation of queue) { try { const before = transcript.cursor; transcript.accept(observation); if (observation.type === 'event' && transcript.cursor > before) phoneProjections.get(transcript)?.event(observation.event); } catch { transcript.markGap(); } }
      queue = [];
      resync ||= transcript.needsResync;
      lastOpened = Date.now(); nextPoll = lastOpened + pollMs;
      const initial = this.phoneSnapshot(transcript);
      lastValue = JSON.stringify(initial);
      lastPublished = Date.now();
      yield initial;
      while (!active.aborted) {
        const now = Date.now();
        if ((resync && now - lastOpened >= pollMs) || now >= nextPoll) {
          // Fresh first opening frame remains cold-safe even if an Agent was
          // detached in between. No second pull or automatic prompt replay.
          transcript = undefined;
          const fresh = await this.open(sessionId, active);
          this.checkCurrent(fresh.view, sessionId);
          transcript = fresh.transcript;
          for (const observation of queue) { try { const before = transcript.cursor; transcript.accept(observation); if (observation.type === 'event' && transcript.cursor > before) phoneProjections.get(transcript)?.event(observation.event); } catch { transcript.markGap(); } }
          queue = [];
          resync = transcript.needsResync;
          lastOpened = Date.now(); nextPoll = lastOpened + pollMs;
          changed = true;
        }
        if (changed && Date.now() - lastPublished >= throttleMs) {
          const snapshot = this.phoneSnapshot(transcript);
          const serialized = JSON.stringify(snapshot);
          changed = false;
          if (serialized !== lastValue) {
            lastValue = serialized;
            lastPublished = Date.now();
            yield snapshot;
            continue;
          }
        }
        const remaining = changed ? Math.max(1, throttleMs - (Date.now() - lastPublished)) : Math.max(1, nextPoll - Date.now());
        await waitForWake((callback) => { wake = callback; }, Math.min(remaining, Math.max(1, nextPoll - Date.now())), active);
      }
    } catch (error) { if (!active.aborted) throw error; }
    finally { unsubscribe?.(); wake = () => {}; queue = []; }
  }
  async createSession(input: { workspaceId: string; presetId?: string; requestId: string }, signal: AbortSignal, expectedWorkspaceId: string): Promise<{ sessionId: string }> {
    const active = this.signal(signal); active.throwIfAborted();
    if (!isUuid(input.requestId)) throw new HostError('invalid_request');
    if (!expectedWorkspaceId || input.workspaceId !== expectedWorkspaceId) throw new HostError('not_found');
    if (input.presetId !== undefined && !(await this.listPresets(active)).some((item) => item.id === input.presetId)) throw new HostError('invalid_request');
    // Resolve the owner source AFTER asynchronous preset preparation, at admission.
    const view = await this.source.list();
    const workspace = view.find((item) => item.id === expectedWorkspaceId);
    if (!workspace) throw new HostError('not_found');
    try {
      const path = await (this.options.realpath ?? realpath)(workspace.path);
      if (workspacePathKey(path) !== workspacePathKey(workspace.path) || !(await stat(path)).isDirectory()) throw new Error();
    } catch { throw new HostError('not_found'); }
    active.throwIfAborted();
    this.checkCurrent(view);
    // rc.2 attaches a new session to the sidebar ONLY on its workspaceId lane.
    // Explicit mobile IDs are not DSH IDs and must retain the legacy cwd lane.
    const target = this.source.kind === 'dsh-registry' ? { workspaceId: workspace.id } : { cwd: workspace.path };
    return this.options.sessionController.create({ ...target, sessionId: `session-${input.requestId.toLowerCase()}`, ...(input.presetId !== undefined ? { agentPreset: input.presetId } : {}) });
  }
  private phoneSnapshot(transcript: Transcript): HostSnapshot {
    const snapshot = transcript.phoneSnapshot();
    const phone = phoneProjections.get(transcript);
    snapshot.model = phone?.model ?? null; snapshot.resolvedQuestions = phone?.resolvedQuestions ?? [];
    snapshot.session = { ...snapshot.session, model: snapshot.model };
    snapshot.messages = snapshot.messages.map(m => { const delivery = m.role === 'user' ? phone?.delivery(m.id) : undefined; return delivery ? { ...m, delivery } : m; });
    const live = this.options.questionBridge?.pending(snapshot.session.id) ?? [];
    if (snapshot.pendingQuestions && live.length) { snapshot.pendingQuestions = [...snapshot.pendingQuestions.filter(q => !live.some(p => p.callId === q.callId)), ...live]; snapshot.activity = 'waiting'; }
    if (this.options.fileUploads) {
      const mimes = fileMimes(this.options.fileUploads);
      const metadata = (ref: AttachmentRef): AttachmentRef => ref.kind === 'file' ? { ...ref, mime: safeFileMime(ref.name, mimes.get(ref.id) ?? ref.mime) } : ref;
      snapshot.messages = snapshot.messages.map(message => ({ ...message, ...(message.attachments ? { attachments: message.attachments.map(metadata) } : {}) }));
      if (snapshot.pendingQueue) snapshot.pendingQueue = snapshot.pendingQueue.map(item => ({ ...item, ...(item.attachments ? { attachments: item.attachments.map(metadata) } : {}) }));
    }
    snapshot.session.waitingForAnswer = (snapshot.pendingQuestions?.length ?? 0) > 0;
    return snapshot;
  }
  phoneCapabilities() {
    const c = this.options.sessionController;
    return { steer: true, queue: !!c.updateQueue, rename: !!c.rename, models: !!c.modelCatalog && !!c.selectModel, questions: !!this.options.questionBridge && !!this.options.answerContinued, questionSkip: !!this.options.questionBridge && !!this.options.answerContinued, fileDownload: !!this.options.attachments?.readFileStream, attachments: !!this.options.fileUploads && !!this.options.attachments && !!c.attachment };
  }
  async models(signal: AbortSignal): Promise<ModelCatalog> {
    const active = this.signal(signal), c = this.options.sessionController;
    if (!c.modelCatalog) throw new HostError('unavailable');
    const catalog = await c.modelCatalog(); active.throwIfAborted();
    const items: ModelCatalog['items'] = [];
    for (const group of catalog.groups) for (const model of group.models) {
      if (items.length >= 1000) throw new HostError('payload_too_large');
      let supportsImages: boolean | null = null;
      if (this.options.modelInfo) { try { const info = await this.options.modelInfo(group.id, model.id, active); supportsImages = info.inputModalities ? info.inputModalities.includes('image') : null; } catch { active.throwIfAborted(); } }
      items.push({ provider: group.id, id: model.id, name: model.name, supportsImages, ...(model.reasoning ? { reasoningEfforts: model.reasoning.efforts.map(e => ({ id: e.id, name: e.name })) } : {}) });
    }
    return { items, default: catalog.default };
  }
  async mutateSession(sessionId: string, mutation: SessionMutation, signal: AbortSignal, expectedWorkspaceId: string): Promise<void> {
    const active = this.signal(signal), c = this.options.sessionController;
    const { view } = await this.permitted(sessionId, active, expectedWorkspaceId);
    active.throwIfAborted(); this.checkCurrent(view, sessionId);
    try {
      switch (mutation.kind) {
        case 'queue': if (!c.updateQueue) throw new HostError('unavailable'); await c.updateQueue({ sessionId, itemId: mutation.itemId, action: { kind: mutation.action } }); break;
        case 'rename': if (!c.rename) throw new HostError('unavailable'); await c.rename({ sessionId, title: mutation.title }); break;
        case 'model': {
          if (!c.selectModel) throw new HostError('unavailable');
          const catalog = await this.models(active), model = catalog.items.find(m => m.id === mutation.model && m.provider === mutation.provider);
          if (!model || (mutation.reasoningEffort !== undefined && !model.reasoningEfforts?.some(e => e.id === mutation.reasoningEffort))) throw new HostError('invalid_request');
          await this.permitted(sessionId, active, expectedWorkspaceId); active.throwIfAborted(); this.checkCurrent(view, sessionId);
          await c.selectModel({ sessionId, provider: mutation.provider, model: mutation.model, ...(mutation.reasoningEffort ? { reasoningEffort: mutation.reasoningEffort } : {}) }); break;
        }
        case 'answer': {
          const snapshot = await this.snapshot(sessionId, active), pending = snapshot.pendingQuestions?.find(q => q.callId === mutation.callId);
          if (!pending) throw new HostError('question_expired'); const answer = mutationAnswer(mutation, pending); validateAnswer(pending, answer);
          await this.permitted(sessionId, active, expectedWorkspaceId); active.throwIfAborted(); this.checkCurrent(view, sessionId);
          if (this.options.questionBridge?.answer(sessionId, mutation.callId, answer)) return;
          if (pending.state !== 'continued' || !await this.options.answerContinued?.(sessionId, mutation.callId, answer)) throw new HostError('question_expired');
        }
      }
    } catch (error) { throw phoneError(error); }
  }
  private readonly fileReservations = new WeakMap<FileUploadReservation, { sessionId: string; slots: object[]; budget: Map<object, { sessionId: string; receiptId?: string }> }>();
  async reserveFileUploads(sessionId: string, count: number, signal: AbortSignal, expectedWorkspaceId: string): Promise<FileUploadReservation> {
    const active = this.signal(signal), service = this.options.fileUploads;
    if (!service || !Number.isSafeInteger(count) || count < 1 || count > 20) throw new HostError('invalid_request');
    await this.permitted(sessionId, active, expectedWorkspaceId);
    const budget = receiptBudget(service);
    // At most 64 reservations and 64 scopes. Unknown/failed resolve never
    // proves retirement, so retain its quota; never refresh transcript history.
    if (service.resolve && this.options.sessionController.resolveAgent) {
      for (const id of new Set([...budget.values()].filter(e => e.receiptId).map(e => e.sessionId))) {
        active.throwIfAborted();
        try {
          const resolved = await this.options.sessionController.resolveAgent(id);
          if ('error' in resolved) continue;
          for (const [key, entry] of budget) if (entry.sessionId === id && entry.receiptId && service.resolve(resolved.agent, entry.receiptId) === undefined) budget.delete(key);
        } catch { active.throwIfAborted(); }
      }
    }
    active.throwIfAborted(); await this.permitted(sessionId, active, expectedWorkspaceId); active.throwIfAborted();
    if (budget.size + count > 64 || [...budget.values()].filter(e => e.sessionId === sessionId).length + count > 20) throw new HostError('rate_limited');
    const slots = Array.from({ length: count }, () => { const slot = { sessionId }; budget!.set(slot, slot); return slot; });
    const token: FileUploadReservation = { release: () => { for (const slot of slots) budget!.delete(slot); slots.length = 0; this.fileReservations.delete(token); } };
    this.fileReservations.set(token, { sessionId, slots, budget }); return token;
  }
  async upload(sessionId: string, input: { data: AsyncIterable<Uint8Array>; size: number; mime: string; name: string; reservation?: FileUploadReservation }, signal: AbortSignal, expectedWorkspaceId: string): Promise<UploadedAttachment> {
    const active = this.signal(signal);
    if (!this.phoneCapabilities().attachments || input.size > UPLOAD_LIMIT) throw new HostError('payload_too_large');
    const { view } = await this.permitted(sessionId, active, expectedWorkspaceId); active.throwIfAborted(); this.checkCurrent(view, sessionId);
    try {
      if (IMAGE_MIMES.includes(input.mime as ImageMime)) {
        const chunks: Buffer[] = []; let size = 0;
        for await (const chunk of input.data) { active.throwIfAborted(); size += chunk.length; if (size > input.size || size > UPLOAD_LIMIT) throw new HostError('payload_too_large'); chunks.push(Buffer.from(chunk)); }
        if (size !== input.size) throw new HostError('invalid_request');
        const data = Buffer.concat(chunks);
        await this.options.attachments!.validateImage({ data, mediaType: input.mime as ImageMime, name: input.name });
        await this.permitted(sessionId, active, expectedWorkspaceId); active.throwIfAborted(); this.checkCurrent(view, sessionId);
        return { ref: { id: createHash('sha256').update(data).digest('hex'), name: input.name, size, mime: input.mime, kind: 'image' }, upstream: { type: 'image', mediaType: input.mime as ImageMime, data: data.toString('base64'), name: input.name } };
      }
      const service = this.options.fileUploads!;
      const token = input.reservation ?? await this.reserveFileUploads(sessionId, 1, active, expectedWorkspaceId);
      const reserved = this.fileReservations.get(token);
      if (!reserved || reserved.sessionId !== sessionId || !reserved.slots.length) throw new HostError('invalid_request');
      const key = reserved.slots.shift()!, budget = reserved.budget;
      const reservation = budget.get(key)!;
      if (!input.reservation) token.release();
      // The inspected service stores only after reading the source. No byte
      // delivered (and no completed empty source) proves it never reached storage.
      let reachedDsh = false;
      const data = (async function* () {
        for await (const chunk of input.data) { if (chunk.byteLength) reachedDsh = true; yield chunk; }
        if (input.size === 0) reachedDsh = true;
      })();
      let result: Awaited<ReturnType<typeof service.uploadStream>>;
      try { result = await service.uploadStream({ sessionId, data, signal: active, name: input.name }); }
      catch (error) { if (!reachedDsh) budget.delete(key); throw error; }
      reservation.receiptId = result.receiptId;
      // DSH file refs omit MIME. Keep bounded passive metadata through hot reload;
      // older/evicted refs still use the safe extension fallback.
      const mimes = fileMimes(service);
      mimes.delete(result.file.attachmentId); mimes.set(result.file.attachmentId, safeFileMime(result.file.name, input.mime));
      while (mimes.size > 4096) mimes.delete(mimes.keys().next().value!);
      return { ref: { id: result.file.attachmentId, name: result.file.name, size: result.file.bytes, mime: input.mime, kind: 'file' }, upstream: { type: 'file', receiptId: result.receiptId } };
    } catch (error) { throw phoneError(error); }
  }
  async file(sessionId: string, attachmentId: string, signal: AbortSignal, expectedWorkspaceId: string): Promise<{ data: Uint8Array; ref: AttachmentRef }> {
    const active = this.signal(signal);
    if (!this.options.attachments?.readFileStream) throw new HostError('unavailable');
    const { view } = await this.permitted(sessionId, active, expectedWorkspaceId);
    // Only a journal-owned message reference is authority, never an upload ID,
    // a queue occurrence, a supplied filename, or a guessed content hash.
    const snapshot = await this.snapshot(sessionId, active);
    const ref = snapshot.messages.slice(-100).flatMap(m => m.attachments ?? []).find(a => a.kind === 'file' && a.id === attachmentId);
    if (!ref) throw new HostError('not_found');
    if (!Number.isSafeInteger(ref.size) || ref.size < 0 || ref.size > UPLOAD_LIMIT || !ref.name || /[\\/\x00-\x1f\x7f]/.test(ref.name)) throw new HostError('payload_too_large');
    const chunks: Buffer[] = []; let size = 0;
    try {
      for await (const chunk of this.options.attachments.readFileStream({ attachmentId: ref.id, name: ref.name, bytes: ref.size }, active)) {
        active.throwIfAborted(); size += chunk.length;
        if (size > ref.size || size > UPLOAD_LIMIT) throw new HostError('payload_too_large');
        chunks.push(Buffer.from(chunk));
      }
      if (size !== ref.size) throw new HostError('unavailable');
      await this.permitted(sessionId, active, expectedWorkspaceId); active.throwIfAborted(); this.checkCurrent(view, sessionId);
      return { data: Buffer.concat(chunks), ref };
    } catch (error) { throw phoneError(error); }
  }
  async image(sessionId: string, attachmentId: string, signal: AbortSignal, expectedWorkspaceId: string): Promise<{ data: Uint8Array; ref: AttachmentRef }> {
    const active = this.signal(signal), c = this.options.sessionController;
    if (!c.attachment) throw new HostError('unavailable');
    const { view } = await this.permitted(sessionId, active, expectedWorkspaceId); active.throwIfAborted(); this.checkCurrent(view, sessionId);
    const opened = await this.open(sessionId, active); this.checkCurrent(opened.view, sessionId);
    const visible = this.phoneSnapshot(opened.transcript).messages.slice(-100).flatMap(m => m.attachments ?? []).find(a => a.kind === 'image' && a.id === attachmentId);
    if (!visible) throw new HostError('not_found');
    if (visible.size > UPLOAD_LIMIT) throw new HostError('payload_too_large');
    try {
      const storedRef = phoneProjections.get(opened.transcript)?.imageRef(attachmentId);
      if (storedRef && this.options.attachments?.readImage) {
        if (storedRef.bytes !== visible.size || storedRef.mediaType !== visible.mime) throw new HostError('unavailable');
        const result = await this.options.attachments.readImage(storedRef, active); active.throwIfAborted();
        if (result.data.byteLength !== visible.size) throw new HostError('unavailable');
        await this.permitted(sessionId, active, expectedWorkspaceId); active.throwIfAborted(); this.checkCurrent(view, sessionId);
        return { data: result.data, ref: visible };
      }
      const result = await c.attachment({ sessionId, attachmentId }); active.throwIfAborted();
      const a = result.attachment;
      if (a.attachmentId !== visible.id || a.bytes !== visible.size || a.mediaType !== visible.mime) throw new HostError('unavailable');
      if (a.bytes > UPLOAD_LIMIT || result.data.length > Math.ceil(UPLOAD_LIMIT / 3) * 4 || !IMAGE_MIMES.includes(a.mediaType)) throw new HostError('payload_too_large');
      const data = Buffer.from(result.data, 'base64'); if (data.length !== a.bytes) throw new HostError('unavailable');
      await this.permitted(sessionId, active, expectedWorkspaceId); active.throwIfAborted(); this.checkCurrent(view, sessionId);
      return { data, ref: { id: a.attachmentId, name: a.name ?? 'image', size: a.bytes, mime: a.mediaType, kind: 'image' } };
    } catch (error) { throw phoneError(error); }
  }
  async prompt(sessionId: string, text: string, requestId: string, signal: AbortSignal, expectedWorkspaceId: string, options?: PromptOptions): Promise<void> {
    const active = this.signal(signal);
    if (!isUuid(requestId) || typeof text !== 'string' || (!text.trim() && !options?.attachments?.length)) throw new HostError('invalid_request');
    if (Buffer.byteLength(text, 'utf8') > 32768) throw new HostError('payload_too_large');
    if (!expectedWorkspaceId) throw new HostError('not_found');
    // The installed composition has fresh stat + synchronous liveness seams.
    // Legacy adapters keep their catalog admission; no authority is cached.
    const point = this.options.sessionHeader && this.options.sessionStatus;
    const authority = point ? await this.pointScope(sessionId, active) : undefined;
    const legacy = authority ? undefined : await this.permitted(sessionId, active, expectedWorkspaceId);
    const view = authority?.view ?? legacy!.view;
    if (authority) this.checkLifecycle(sessionId, authority.revision);
    if (authority && authority.workspaceId !== expectedWorkspaceId) throw new HostError('not_found');
    active.throwIfAborted();
    this.checkCurrent(view, sessionId);
    if (options?.delivery === 'steer' && !(point ? this.options.sessionStatus!(sessionId).running : legacy!.row.running)) throw new HostError('conflict');
    const content = [{ type: 'text' as const, text }, ...(options?.attachments ?? []).map(a => a.upstream)];
    try {
      await this.options.sessionController.prompt({ requestId, sessionId, mode: options?.delivery ?? 'queue', content }, active);
      // ACK can be a requestId dedup hit before attachment binding. Only public
      // resolve proving retirement can release this conservative reservation.
      // Post-ACK bookkeeping must never rewrite an admission that DSH already accepted.
      try {
        const service = this.options.fileUploads;
        if (service?.resolve && this.options.sessionController.resolveAgent) {
          const resolved = await this.options.sessionController.resolveAgent(sessionId);
          if ('error' in resolved) return;
          const budget = receiptBudget(service);
          const accepted = new Set((options?.attachments ?? []).flatMap(a => a.upstream.type === 'file' ? [a.upstream.receiptId] : []));
          if (budget) for (const [key, entry] of budget) if (entry.sessionId === sessionId && entry.receiptId && accepted.has(entry.receiptId) && service.resolve(resolved.agent, entry.receiptId) === undefined) budget.delete(key);
        }
      } catch { /* Receipt retirement is diagnostic cleanup; the prompt itself is already admitted. */ }
    } catch (error) { throw phoneError(error); }
  }
  async cancel(sessionId: string, signal: AbortSignal, expectedCursor: number, expectedWorkspaceId: string): Promise<void> {
    const active = this.signal(signal);
    if (!Number.isSafeInteger(expectedCursor) || expectedCursor < -1) throw new HostError('invalid_request');
    if (!expectedWorkspaceId) throw new HostError('not_found');
    const { transcript, view } = await this.open(sessionId, active, expectedWorkspaceId);
    active.throwIfAborted();
    this.checkCurrent(view, sessionId);
    const current = transcript.snapshot();
    if (!current.session.running || current.cursor !== expectedCursor) throw new HostError('conflict');
    // No await between the conservative freshness guard and the synchronous rc.2
    // cancellation call. Upstream still offers no atomic target-turn compare.
    const result = this.options.sessionController.cancel({ sessionId });
    if (object(result)?.accepted !== true) throw new HostError('unavailable');
  }
}
