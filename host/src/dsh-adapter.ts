import { realpath, stat } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import { createWorkspaceSource, workspacePathKey } from './workspace-source.ts';
import type { WorkspaceSource } from './workspace-source.ts';
import { HostError } from './errors.ts';
import { presentUserText } from './message-presentation.ts';
import type { ChatMessage, HostAdapter, HostSession, HostSnapshot, Preset, WorkspaceConfig } from './types.ts';

import type { NotificationEvidence, NotificationSource, NotificationSources } from './notifications.ts';
import { isCompatibleDshVersion } from './compatibility.ts';
export { COMPATIBLE_DSH_VERSIONS } from './compatibility.ts';
const MAX_MESSAGES = 100;
const isUuid = (value: string): boolean => /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);

/** Narrow in-process seams, derived from inspected rc.2 declarations, not an RPC proxy. */
export interface DshSessionSummary {
  readonly sessionId: string;
  readonly updatedAt: number;
  readonly running: boolean;
  readonly agentAvailable: boolean;
  readonly cwd?: string;
  readonly origin?: string;
  readonly parentSessionId?: string;
  readonly projections?: { readonly values: Readonly<Record<string, unknown>> };
}
export interface DshSessionController {
  list(request: Record<string, never>, signal: AbortSignal): Promise<{ readonly items: readonly DshSessionSummary[] }>;
  follow(request: { address: { kind: 'session'; sessionId: string }; assistantStream: true; maxMessages: number }, signal: AbortSignal): AsyncIterable<unknown>;
  projections(request: { sessionId: string }, signal: AbortSignal): Promise<unknown>;
  create(request: ({ cwd: string; workspaceId?: never } | { workspaceId: string; cwd?: never }) & { sessionId: string; agentPreset?: string }): Promise<{ sessionId: string }>;
  prompt(request: { requestId: string; sessionId: string; mode: 'queue'; content: readonly { type: 'text'; text: string }[] }, signal: AbortSignal): Promise<unknown>;
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
}
export interface DshAdapterOptions {
  /** Explicit operator-declared version, NOT automatically discovered or inferred from method names. */
  dshVersion: string;
  sessionController: DshSessionController;
  agentPresets?: DshAgentPresets;
  /** Legacy factory input; do not combine with workspaceSource. */
  workspaces?: readonly WorkspaceConfig[];
  workspaceSource?: WorkspaceSource;
  /** Bound request-local cwd deduplication; no resolution survives a list call. */
  realpathCacheMaxEntries?: number;
  /** Filesystem seam for deterministic authorization tests. */
  realpath?: (cwd: string) => Promise<string>;
  events?: DshEvents;
  pollIntervalMs?: number;
  throttleMs?: number;
}
interface CanonicalWorkspace extends WorkspaceConfig { key: string }
interface WireEvent { type: string; seq: number; time: number; data: unknown; ignorable?: true; surfaceOp?: unknown }
interface SurfaceNode { seq: number; message?: ChatMessage; answerTurn?: number }
interface Provisional { id: string; turn: number; step: number; nextIndex: number; createdAt: number; blocks: Map<number, string> }
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
  'web/deepseek-search-llm-request', 'workspace/changes',
]);
const WAIT_NOTICE = 'A question or approval needs attention in DSH on the desktop. Mobile answers are not supported.';
const UNKNOWN_NOTICE = 'This DSH history contains an unsupported event. Open the desktop to view the authoritative conversation.';
const GAP_NOTICE = 'Live observation was interrupted. Refreshing the authoritative DSH snapshot.';
const HISTORY_CUT_NOTICE = 'This bounded history crosses a DSH surface replacement. Open the desktop to view the authoritative conversation.';

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
function messageOf(event: WireEvent): ChatMessage | undefined {
  const data = object(event.data);
  const message = event.type === 'user/message' ? data : event.type === 'assistant/message' ? object(data?.message) : undefined;
  if (!message || typeof message.id !== 'string') return undefined;
  const text = textContent(message.content);
  if (!text) return undefined;
  const source = object(message.source);
  return {
    id: message.id, role: event.type === 'user/message' ? 'user' : 'assistant',
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
  // Endpoints identify current surface POSITIONS, not a numeric seq range.
  // A bounded cut cannot reconstruct missing endpoints: suppress the ambiguous
  // conversation rather than leave old answers that a later rewrite shadowed.
  if (start < 0 || end < 0) return false;
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
  private revision = 0;
  private terminals: { turn: number; sourceSeq: number }[] = [];
  private openProjectedQuestions = false;
  notificationEvidence(): NotificationEvidence {
    const ambiguous = this.unsupported || this.historyCut || this.gap;
    return { sessionId: this.session.id, workspaceId: this.session.workspaceId, cursor: this.cursor, valid: !ambiguous,
      pending: !ambiguous && this.running && (this.approvals.size > 0 || this.questions.size > 0 || this.openProjectedQuestions),
      completed: ambiguous ? [] : this.terminals.filter(t => this.nodes.some(n => n.answerTurn === t.turn)) };
  }
  constructor(session: HostSession) { this.session = { ...session }; this.running = session.running; }
  open(frame: Record<string, unknown>): void {
    if (!Number.isSafeInteger(frame.cursor) || (frame.cursor as number) < -1 || !Array.isArray(frame.records)) throw new HostError('unavailable');
    this.cursor = frame.cursor as number;
    this.hasMore = frame.hasMore === true;
    const values = object(object(frame.projections)?.values);
    if (typeof values?.title === 'string') this.session.title = values.title;
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
    if (active && typeof active.attemptId === 'string' && Number.isSafeInteger(active.nextIndex)) {
      this.attempt = { id: active.attemptId, turn: active.turn as number, step: active.step as number, nextIndex: active.nextIndex as number, createdAt: this.session.updatedAt, blocks: new Map() };
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
      this.openProjectedQuestions = activeQuestions.some(q => object(q)?.state === 'open');
      for (const q of activeQuestions) if (object(q)?.state === 'continued' && typeof object(q)?.callId === 'string') this.questions.delete(object(q)!.callId as string);
    }
    this.trim();
  }
  markGap(): void { this.gap = true; this.attempt = undefined; }
  get needsResync(): boolean { return this.gap; }
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
    if (this.unsupported || this.gap || this.historyCut) return;
    if (!applySurface(this.nodes, event)) { this.historyCut = true; this.attempt = undefined; return; }
    const data = object(event.data);
    if (event.type === 'turn/start') {
      this.calls.clear();
      this.turn = Number.isSafeInteger(data?.turn) ? { number: data!.turn as number, startedAt: event.time } : undefined;
    }
    if (event.type === 'turn/end') {
      if (object(data?.reason)?.kind === 'completed' && Number.isSafeInteger(data?.turn)) this.terminals.push({ turn: data!.turn as number, sourceSeq: event.seq });
      this.turn = undefined; this.calls.clear();
    }
    if (event.type === 'tool/call' && this.turn?.number === data?.turn && typeof data?.callId === 'string' && typeof data.name === 'string' && /^[A-Za-z0-9_.:/-]{1,128}$/.test(data.name)) {
      if (this.calls.size < 1000) this.calls.set(data.callId, data.name);
      else { this.turn = undefined; this.calls.clear(); } // Never grow unbounded or report an unproven latest call.
    }
    if (event.type === 'tool/result') {
      const message = object(data?.message);
      if (typeof message?.toolCallId === 'string') this.calls.delete(message.toolCallId);
    }
    if (event.type === 'session/title' && typeof data?.title === 'string') this.session.title = data.title;
    if (event.type === 'user/message') this.session.updatedAt = Math.max(this.session.updatedAt, event.time);
    if (live && event.type === 'turn/start') this.running = true;
    if (live && event.type === 'turn/end') { this.running = false; this.attempt = undefined; }
    if ((event.type === 'assistant/message' || event.type === 'assistant/attempt') && this.attempt?.turn === data?.turn && this.attempt?.step === data?.step) this.attempt = undefined;
    if (event.type === 'approval/asked' && typeof data?.id === 'string') this.approvals.add(data.id);
    if (event.type === 'approval/decided' && typeof data?.id === 'string') this.approvals.delete(data.id);
    if (event.type === 'tool/call' && data?.name === 'ask_user_question' && typeof data.callId === 'string') this.questions.add(data.callId);
    if (event.type === 'tool/result') {
      const message = object(data?.message);
      if (typeof message?.toolCallId === 'string') this.questions.delete(message.toolCallId);
    }
  }
  private stream(value: unknown): void {
    const frame = object(value);
    if (!frame || typeof frame.revision !== 'number' || typeof frame.attemptId !== 'string' || this.unsupported || this.historyCut || this.gap) return;
    if (frame.type === 'start' && frame.revision === 1) this.revision = 0;
    if (frame.revision <= this.revision) return;
    if (frame.revision !== this.revision + 1) { this.markGap(); return; }
    this.revision = frame.revision;
    if (frame.type === 'start') {
      this.attempt = { id: frame.attemptId, turn: frame.turn as number, step: frame.step as number, nextIndex: 0, createdAt: this.session.updatedAt, blocks: new Map() };
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
      attempt.blocks.set(index, (attempt.blocks.get(index) ?? '') + chunk.text);
    } else if (chunk.type === 'block-end') {
      const block = object(chunk.block);
      if (block?.type === 'text' && typeof block.text === 'string') {
        if (attempt.blocks.size === 0 && typeof time === 'number') attempt.createdAt = time;
        attempt.blocks.set(index, block.text); // block-end replaces accumulated deltas, never doubles them
      }
    }
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
    const ambiguous = this.unsupported || this.historyCut || this.gap;
    const messages = ambiguous ? [] : this.nodes.flatMap((node) => node.message ? [{ ...node.message }] : []);
    const provisionalText = this.attempt ? [...this.attempt.blocks].sort(([a], [b]) => a - b).map(([, text]) => text).join('') : '';
    if (this.attempt && provisionalText && !ambiguous) messages.push({ id: `provisional:${this.attempt.id}`, role: 'assistant', text: provisionalText, createdAt: this.attempt.createdAt, provisional: true });
    const waiting = this.approvals.size > 0 || this.questions.size > 0 || this.projectedQuestions;
    return {
      session: { ...this.session, running: this.running }, messages: messages.slice(-MAX_MESSAGES), cursor: this.cursor,
      hasMore: this.hasMore || messages.length > MAX_MESSAGES,
      activity: ambiguous ? 'unknown' : waiting ? 'waiting' : this.running ? 'running' : 'idle',
      ...(!ambiguous && this.running && this.turn ? { activityDetail: { turnStartedAt: this.turn.startedAt, ...([...this.calls.values()].at(-1) ? { tool: [...this.calls.values()].at(-1)! } : {}) } } : {}),
      ...(this.unsupported ? { notice: UNKNOWN_NOTICE } : this.historyCut ? { notice: HISTORY_CUT_NOTICE } : this.gap ? { notice: GAP_NOTICE } : waiting ? { notice: WAIT_NOTICE } : {}),
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
  constructor(options: DshAdapterOptions, source: WorkspaceSource) { this.options = options; this.source = source; this.upstreamVersion = options.dshVersion; }
  notifications(): NotificationSources | undefined {
    const subscribe = this.options.events?.subscribeNotifications;
    if (!subscribe) return undefined;
    return { subscribe, list: signal => this.listSessions(signal), evidence: async (id, signal, listed) => (await this.open(id, this.signal(signal), undefined, listed ? { ...listed, running: listed.running ?? false, title: '', updatedAt: 0 } : undefined)).transcript.notificationEvidence() };
  }
  dispose(): void { this.lifetime.abort(); }
  private signal(signal: AbortSignal): AbortSignal { return AbortSignal.any([signal, this.lifetime.signal]); }
  private async workspaceMap(): Promise<{ workspaces: ReadonlyMap<string, CanonicalWorkspace>; view: readonly WorkspaceConfig[] }> {
    const view = await this.source.list();
    return { view, workspaces: new Map(view.map(item => [workspacePathKey(item.path), { ...item, key: workspacePathKey(item.path) }])) };
  }
  private checkCurrent(view: readonly WorkspaceConfig[], sessionId?: string): void {
    if (this.source.isCurrent && !this.source.isCurrent(view)) throw new HostError('not_found');
    if (sessionId && this.source.archivedSessionIds().has(sessionId)) throw new HostError('not_found');
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
    return { id: row.sessionId, title: typeof row.projections?.values.title === 'string' ? row.projections.values.title : 'Untitled session', workspaceId: workspace.id, updatedAt: row.updatedAt, running: row.running };
  }
  private async permitted(sessionId: string, signal: AbortSignal, expectedWorkspaceId?: string): Promise<{ row: DshSessionSummary; session: HostSession; view: readonly WorkspaceConfig[] }> {
    signal.throwIfAborted();
    const rows = await this.options.sessionController.list({}, signal);
    const row = rows.items.find((item) => item.sessionId === sessionId && item.origin !== 'subagent');
    const { workspaces, view } = await this.workspaceMap();
    const workspace = row && !this.source.archivedSessionIds().has(sessionId) && await this.workspace(row.cwd, workspaces);
    signal.throwIfAborted();
    this.checkCurrent(view, sessionId);
    if (!row || !workspace || (expectedWorkspaceId !== undefined && workspace.id !== expectedWorkspaceId)) throw new HostError('not_found');
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
    return sessions.sort((a, b) => b.updatedAt - a.updatedAt);
  }
  async snapshot(sessionId: string, signal: AbortSignal): Promise<HostSnapshot> {
    const { transcript, view } = await this.open(sessionId, this.signal(signal));
    this.checkCurrent(view, sessionId);
    return transcript.snapshot();
  }
  private async open(sessionId: string, active: AbortSignal, expectedWorkspaceId?: string, listed?: HostSession): Promise<{ transcript: Transcript; view: readonly WorkspaceConfig[] }> {
    const session = listed ?? (await this.permitted(sessionId, active, expectedWorkspaceId)).session;
    const cleanup = new AbortController();
    const readSignal = AbortSignal.any([active, cleanup.signal]);
    const iterator = this.options.sessionController.follow({ address: { kind: 'session', sessionId }, assistantStream: true, maxMessages: MAX_MESSAGES }, readSignal)[Symbol.asyncIterator]();
    let transcript: Transcript | undefined, view: readonly WorkspaceConfig[] | undefined;
    try {
      const first = await iterator.next();
      active.throwIfAborted();
      const frame = object(first.value);
      const header = object(frame?.header);
      if (first.done || frame?.type !== 'snapshot' || header?.id !== sessionId || header.origin === 'subagent') throw new HostError('unavailable');
      const scope = await this.workspaceMap(); view = scope.view;
      const workspace = await this.workspace(header.cwd, scope.workspaces);
      this.checkCurrent(view, sessionId);
      if (!workspace || workspace.id !== session.workspaceId) throw new HostError('not_found');
      transcript = new Transcript(session);
      transcript.open(frame);
    } finally {
      // rc.2 promotes a prepared cold session only AFTER the first yield. Never
      // request a second frame, even when list() previously showed a live Agent.
      cleanup.abort();
      await iterator.return?.();
    }
    active.throwIfAborted();
    if (!transcript || !view) throw new HostError('unavailable');
    this.checkCurrent(view, sessionId); // Iterator cleanup itself may yield.
    return { transcript, view };
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
        try { transcript.accept(observation); } catch { transcript.markGap(); }
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
      for (const observation of queue) { try { transcript.accept(observation); } catch { transcript.markGap(); } }
      queue = [];
      resync ||= transcript.needsResync;
      lastOpened = Date.now(); nextPoll = lastOpened + pollMs;
      const initial = transcript.snapshot();
      lastValue = JSON.stringify(initial);
      lastPublished = Date.now();
      yield initial;
      while (!active.aborted) {
        const now = Date.now();
        if ((resync && now - lastOpened >= pollMs) || now >= nextPoll) {
          // List + first opening frame remain cold-safe even if an Agent was
          // detached in between. No second pull or automatic prompt replay.
          transcript = undefined;
          const fresh = await this.open(sessionId, active);
          this.checkCurrent(fresh.view, sessionId);
          transcript = fresh.transcript;
          for (const observation of queue) { try { transcript.accept(observation); } catch { transcript.markGap(); } }
          queue = [];
          resync = transcript.needsResync;
          lastOpened = Date.now(); nextPoll = lastOpened + pollMs;
          changed = true;
        }
        if (changed && Date.now() - lastPublished >= throttleMs) {
          const snapshot = transcript.snapshot();
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
  async prompt(sessionId: string, text: string, requestId: string, signal: AbortSignal, expectedWorkspaceId: string): Promise<void> {
    const active = this.signal(signal);
    if (!isUuid(requestId) || typeof text !== 'string' || !text.trim()) throw new HostError('invalid_request');
    if (Buffer.byteLength(text, 'utf8') > 32768) throw new HostError('payload_too_large');
    if (!expectedWorkspaceId) throw new HostError('not_found');
    const { view } = await this.permitted(sessionId, active, expectedWorkspaceId);
    active.throwIfAborted();
    this.checkCurrent(view, sessionId);
    await this.options.sessionController.prompt({ requestId, sessionId, mode: 'queue', content: [{ type: 'text', text }] }, active);
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
