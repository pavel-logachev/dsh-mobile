import { createHash, createHmac, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { HostError } from './errors.ts';
import { allowsWorkspace, HostState } from './state.ts';

export type Coverage = 'initializing' | 'ready' | 'degraded';
export interface NotificationSource {
  seq: number; type: string; time: number; turn?: number; completed?: boolean;
  /** Metadata only: this event can open an attention episode. */
  attention?: boolean;
}
export interface NotificationEvidence { sessionId: string; workspaceId: string; cursor: number; pending: boolean; valid?: boolean; completed: { turn: number; sourceSeq: number }[] }
export interface NotificationSources {
  subscribe(listener: (id: string, event: NotificationSource) => void): () => void;
  list(signal: AbortSignal): Promise<{ id: string; workspaceId: string; running?: boolean }[]>;
  /** Fresh point authority, including origin/archive/canonical workspace checks. Absence is exclusion, not failure. */
  lookup?(id: string, signal: AbortSignal): Promise<{ id: string; workspaceId: string; running?: boolean } | undefined>;
  /** Synchronous final scope check after a page's parallel point reads yield. */
  lookupCurrent?(listed: { id: string; workspaceId: string }): boolean;
  evidence(id: string, signal: AbortSignal, listed?: { id: string; workspaceId: string; running?: boolean }): Promise<NotificationEvidence>;
  /** Revalidate a coalesced proof against the currently active source scope. */
  evidenceCurrent?(proof: NotificationEvidence): boolean;
}
export interface NotificationEvent {
  version: 1; eventId: string; sequence: number; occurredAt: number; expiresAt: number;
  workspaceId: string; sessionId: string; kind: 'answer-finished' | 'attention-needed' | 'attention-cleared';
  sourceSeq: number; turn?: number; attentionId?: string;
}
export interface NotificationSettings { revision: number; enabled: boolean; projects: { workspaceId: string; enabled: boolean }[]; chats: { sessionId: string; enabled: boolean }[] }
interface Journal { epoch: string; sequence: number; settings: NotificationSettings; items: NotificationEvent[]; scope?: string; attentionSessions?: string[] }
interface SourceState { seq: number; attentionId?: string }
type TraceStage = 'source_received' | 'producer_begin' | 'producer_end' | 'journal_committed' | 'sse_sent';
/** Opt-in diagnostics: fixed labels, numeric metadata and a one-way event correlation only. */
export function notificationTrace(stage: TraceStage, fields: { seq?: number; queue?: number; durationMs?: number; event?: string; count?: number } = {}): void {
  if (process.env.DSH_MOBILE_NOTIFICATION_TRACE !== '1') return;
  console.debug(JSON.stringify({ component: 'DSHNotification', stage, timeMs: Date.now(), ...fields }));
}
const RETENTION = 7 * 86400_000;
const relevant = new Set(['turn/end', 'approval/asked', 'approval/decided', 'tool/call', 'tool/result', 'user/message']);
const idValid = (s: unknown): s is string => typeof s === 'string' && Buffer.byteLength(s) <= 256 && !!s && !['__proto__', 'constructor', 'prototype'].includes(s) && !/[\/\\\x00-\x1f\x7f]/.test(s);

/** Per-session ordered preparation; synchronous journal+watermark commits never interleave. */
export class NotificationFeed {
  coverage: Coverage = 'initializing';
  private readonly state: HostState;
  private readonly source: NotificationSources;
  private readonly key: string;
  private readonly lifetime = new AbortController();
  private watermarks: Record<string, SourceState> = {};
  private queue: { id: string; event: NotificationSource }[] = [];
  private work: Promise<void> = Promise.resolve();
  private dispose: (() => void) | undefined;
  private timer: ReturnType<typeof setInterval> | undefined;
  private started = false;
  private initializing = false;
  private activated = false;
  private baselineAt = 0;
  private lazyIdle: string[] = [];
  private readonly lazyRetry = new Map<string, { failures: number; after: number }>();
  private recoveryPass = 0;
  private activation: AbortController | undefined;
  private readonly lanes = new Map<string, Promise<void>>();
  private reconciling = false;
  private overflow = false;
  private coverageRevision = 0;
  private recoveryScan = false;
  private readonly recoveryPending = new Set<string>();
  // Every rejected authoritative proof blocks readiness until a valid recovery.
  private readonly blockingRecovery = new Set<string>();
  // A real sequence discontinuity is a delivery gap and stays sticky until a
  // valid authoritative proof succeeds. It is separate from an unprovable old
  // history, which cannot produce a new notification by itself.
  private readonly deliveryGaps = new Set<string>();
  // Last delivered envelope per source, including ignored/non-alerting kinds.
  // This is the continuity checkpoint, not the proven producer watermark.
  private readonly observed = new Map<string, number>();
  private readonly continuityIds = new Set<string>();
  // A sequence discontinuity seen on any envelope, including ignored kinds.
  private readonly gaps = new Set<string>();
  // Irrelevant envelopes are not membership evidence. Bounded, transient sequence cuts are
  // reconciled only after the next relevant event passes a fresh membership check.
  private readonly skipped = new Map<string, { first: number; last: number; gap: boolean }>();
  private skippedEvicted = false; // Without a prior admitted watermark, eviction cannot prove sequence continuity.
  private readonly listeners = new Set<() => void>();
  private wakeQueued = false;
  onChange(listener: () => void): () => void { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; }
  private changed(): void {
    if (this.wakeQueued || this.lifetime.signal.aborted) return;
    this.wakeQueued = true;
    queueMicrotask(() => { this.wakeQueued = false; if (!this.lifetime.signal.aborted) for (const listener of this.listeners) { try { listener(); } catch { /* Observers cannot roll back a committed journal. */ } } });
  }
  tracePage(stage: 'sse_sent', items: NotificationEvent[]): void {
    if (process.env.DSH_MOBILE_NOTIFICATION_TRACE !== '1') return;
    for (const event of items) notificationTrace(stage, { event: createHash('sha256').update(event.eventId).digest('hex').slice(0, 12), seq: event.sequence });
  }
  private degrade(reason: string, detail?: { type?: string; seq?: number; previous?: number | undefined; valid?: boolean | undefined; pending?: boolean; cursor?: number; completed?: number; running?: boolean | undefined }): void {
    const diagnostic = this.state.readNotificationState<{ counts: Record<string, number> }>('diagnostics') ?? { counts: {} };
    diagnostic.counts[reason] = (diagnostic.counts[reason] ?? 0) + 1;
    this.state.writeNotificationState('diagnostics', { ...diagnostic, lastReason: reason, lastAt: Date.now(), queueDepth: this.queue.length, ...(detail ? { lastDetail: detail } : {}) });
    const previous = this.coverage; this.coverage = 'degraded'; this.coverageRevision++; this.recoveryScan = true;
    if (previous !== this.coverage) this.changed();
  }
  baselineReady = false;
  private batch: Map<string, Journal> | undefined;
  private readonly emissions = new Map<string, { count: number; until: number }>();
  private readonly baselineBudgetMs: number;
  constructor(state: HostState, source: NotificationSources, baselineBudgetMs = 10_000) {
    this.baselineBudgetMs = baselineBudgetMs;
    this.state = state; this.source = source;
    this.key = state.readNotificationState<string>('cursor-key') ?? randomBytes(32).toString('base64url');
    state.writeNotificationState('cursor-key', this.key);
  }
  async start(): Promise<void> {
    this.dispose = this.source.subscribe((id, event) => {
      if (this.lifetime.signal.aborted || !this.activated || !this.enabledDevices()) return;
      if (!idValid(id) || !Number.isSafeInteger(event.seq) || event.seq < 0 || !Number.isSafeInteger(event.time) || event.time < 0 || typeof event.type !== 'string' || event.type.length > 128) { this.degrade('invalid_envelope'); return; }
      if (this.queue.length >= 4096) { this.degrade('queue_overflow'); this.overflow = true; return; }
      notificationTrace('source_received', { seq: event.seq, queue: this.queue.length });
      this.queue.push({ id, event });
      if (this.started) this.schedule();
    });
    this.watermarks = this.state.readNotificationState<Record<string, SourceState>>('producer') ?? {};
    this.started = true;
    this.activate();
    this.timer = setInterval(() => {
      if (this.reconciling || this.initializing || !this.activated || !this.enabledDevices() || this.lifetime.signal.aborted) return; this.reconciling = true;
      this.work = this.work.then(() => this.reconcile()).catch(() => { this.degrade('reconcile_failed'); }).finally(() => { this.reconciling = false; });
    }, 60_000);
    this.timer.unref();
  }
  private enabledCache: { value: boolean; at: number } | undefined;
  private readonly journalCache = new Map<string, { revision: number; value: Journal }>();
  private enabledDevices(): boolean {
    const now = Date.now();
    if (this.enabledCache && now - this.enabledCache.at < 1_000) return this.enabledCache.value;
    const value = this.state.notificationEnabledDeviceCount() > 0;
    this.enabledCache = { value, at: now }; return value;
  }
  private activate(): void {
    if (this.activated || !this.started || !this.enabledDevices()) return;
    const activation = this.activation = new AbortController();
    this.activated = true; this.initializing = true; this.baselineReady = false; this.baselineAt = Date.now();
    this.lazyIdle = []; this.lazyRetry.clear(); this.recoveryPass = 0; this.recoveryPending.clear(); this.blockingRecovery.clear(); this.deliveryGaps.clear(); this.observed.clear(); this.continuityIds.clear(); this.gaps.clear(); this.recoveryScan = false;
    this.coverage = 'initializing';
    this.work = this.work.then(async () => {
      if (activation.signal.aborted) return;
      const pendingBefore = this.pendingCut();
      const budget = AbortSignal.any([this.lifetime.signal, activation.signal, AbortSignal.timeout(this.baselineBudgetMs)]);
      try {
        const sessions = await this.source.list(budget);
        budget.throwIfAborted();
        if (sessions.length > 10000) throw new HostError('unavailable');
        const active = new Set(sessions.map(s => s.id));
        for (const id of active) this.continuityIds.add(id);
        for (const id of Object.keys(this.watermarks)) if (!active.has(id)) delete this.watermarks[id];
        // Keep the entire cut retryable even if this baseline exhausts its budget.
        this.lazyIdle = sessions.map(s => s.id);
        for (const session of sessions) {
          budget.throwIfAborted();
          // Idle history is pre-baseline. Only running or previously pending
          // sessions need authoritative attention recovery on hot-upgrade.
          if (!session.running && !this.watermarks[session.id]?.attentionId) { delete this.watermarks[session.id]; continue; }
          const evidence = await this.source.evidence(session.id, budget, session);
          budget.throwIfAborted();
          if (evidence.valid === false || evidence.sessionId !== session.id || evidence.workspaceId !== session.workspaceId || (this.source.evidenceCurrent && !this.source.evidenceCurrent(evidence))) {
            this.blockingRecovery.add(session.id); this.degrade('baseline_proof_invalid');
            continue;
          }
          const queued = this.queue.filter(e => e.id === session.id).map(e => e.event.seq);
          this.watermarks[session.id] = { seq: queued.length ? Math.min(...queued) - 1 : evidence.cursor, ...(evidence.pending ? { attentionId: this.watermarks[session.id]?.attentionId ?? randomUUID() } : {}) };
        }
        if (pendingBefore !== this.pendingCut()) this.resetJournals();
        this.state.writeNotificationState('producer', this.watermarks);
        if (this.queue.length) this.degrade('baseline_queue_pending');
        this.coverage = this.coverage === 'degraded' ? 'degraded' : 'ready'; this.changed();
      } catch { if (!activation.signal.aborted) { this.degrade('baseline_failed'); this.resetJournals(); } }
      finally { if (this.activation === activation && !activation.signal.aborted) { this.baselineReady = true; this.initializing = false; this.schedule(); } }
    });
  }
  private pendingCut(): string { return JSON.stringify(Object.entries(this.watermarks).filter(([, s]) => s.attentionId).map(([id, s]) => [id, s.attentionId]).sort(([a], [b]) => a!.localeCompare(b!))); }
  private signal(producer = true): AbortSignal { return AbortSignal.any([this.lifetime.signal, ...(producer && this.activation ? [this.activation.signal] : []), AbortSignal.timeout(10_000)]); }
  private schedule(): void {
    if (this.initializing || this.lifetime.signal.aborted || !this.activated) return;
    // Only I/O overlaps. Each lane retains its envelope until settlement so a
    // concurrent recovery cut cannot consume an uncommitted terminal event.
    for (const id of new Set(this.queue.map(e => e.id))) {
      if (this.lanes.has(id) || this.lanes.size >= 256) continue;
      const activation = this.activation;
      const lane = (async () => {
        const proofs = new Map<string, NotificationEvidence>();
        // Yield once so the lane is registered before any synchronous completion.
        await Promise.resolve();
        for (;;) {
          if (this.lifetime.signal.aborted || activation !== this.activation || activation?.signal.aborted) return;
          const next = this.queue.find(e => e.id === id); if (!next) return;
          const begin = performance.now();
          notificationTrace('producer_begin', { seq: next.event.seq, queue: this.queue.length });
          try { await this.accept(next.id, next.event, proofs); }
          catch { if (!activation?.signal.aborted && !this.lifetime.signal.aborted) this.degrade('accept_failed'); }
          finally {
            const index = this.queue.indexOf(next); if (index >= 0) this.queue.splice(index, 1);
            notificationTrace('producer_end', { seq: next.event.seq, durationMs: Math.round(performance.now() - begin) });
          }
        }
      })().finally(() => {
        if (this.lanes.get(id) === lane) this.lanes.delete(id);
        if (this.queue.length) this.schedule();
        else if (!this.lanes.size && this.overflow && !this.lifetime.signal.aborted) {
          this.overflow = false; this.resetJournals();
          this.work = this.work.then(() => this.reconcile()).catch(() => this.degrade('overflow_recovery_failed'));
        }
      });
      this.lanes.set(id, lane);
    }
  }
  async idle(): Promise<void> {
    do { await this.work; await Promise.all(this.lanes.values()); }
    while ((this.initializing || this.lanes.size || this.queue.length) && !this.lifetime.signal.aborted);
  }
  private async lookup(id: string, signal = this.signal()): Promise<{ id: string; workspaceId: string; running?: boolean } | undefined> {
    return this.source.lookup ? this.source.lookup(id, signal) : (await this.source.list(signal)).find(s => s.id === id);
  }
  private reserveContinuity(id: string): boolean {
    if (!this.continuityIds.has(id) && this.continuityIds.size >= 10000) {
      if (!this.skippedEvicted) { this.skippedEvicted = true; this.degrade('continuity_limit'); this.resetJournals(); }
      return false;
    }
    this.continuityIds.add(id); return true;
  }
  private async accept(id: string, event: NotificationSource, proofs: Map<string, NotificationEvidence>): Promise<void> {
    if (event.time < this.baselineAt) return;
    const previous = this.watermarks[id];
    if (previous && event.seq <= previous.seq) return;
    // One shared admission budget covers even excluded/irrelevant IDs while list IO is held.
    if (!this.reserveContinuity(id)) return;
    const seen = this.observed.get(id) ?? previous?.seq;
    if (seen !== undefined && event.seq <= seen) return; // Duplicate/older envelope cannot rewind an accepted checkpoint.
    const continuityGap = seen !== undefined && event.seq !== seen + 1;
    this.observed.set(id, event.seq);
    if (!relevant.has(event.type)) {
      const skipped = this.skipped.get(id);
      if (continuityGap || skipped?.gap) this.gaps.add(id);
      if (!skipped) {
        if (this.skipped.size >= 10000) { this.skippedEvicted = true; this.skipped.delete(this.skipped.keys().next().value!); }
        this.skipped.set(id, { first: event.seq, last: event.seq, gap: continuityGap });
      } else if (event.seq > skipped.last) { skipped.gap ||= continuityGap || event.seq !== skipped.last + 1; skipped.last = event.seq; }
      return;
    }
    // Only relevant events pay for fresh membership/permission authority. Excluded
    // sessions never introduce producer watermarks or gaps in the ordinary feed.
    const activation = this.activation;
    const listed = await this.lookup(id);
    if (activation !== this.activation || activation?.signal.aborted) return;
    if (!listed) { proofs.delete(id); this.skipped.delete(id); this.observed.delete(id); this.gaps.delete(id); return; }
    if (!this.reserveContinuity(id)) return; // Membership IO can overlap authoritative slot retirement too.
    const old = this.watermarks[id];
    if (event.time < this.baselineAt || (old && event.seq <= old.seq)) return;
    const skipped = this.skipped.get(id); this.skipped.delete(id);
    const gap = this.gaps.has(id) || (seen !== undefined
      ? continuityGap || !!skipped?.gap
      : (this.skippedEvicted && !old) || !!skipped?.gap
        || (skipped ? (event.seq !== skipped.last + 1 || (!!old && skipped.first !== old.seq + 1)) : !!old && event.seq !== old.seq + 1));
    if (gap) { this.deliveryGaps.add(id); this.degrade('sequence_gap', { type: event.type, seq: event.seq, previous: old?.seq }); this.resetJournals(); }
    if (!old && Object.keys(this.watermarks).length >= 10000) { this.degrade('watermark_limit'); return; }
    let proof = proofs.get(id);
    if (!proof || proof.workspaceId !== listed.workspaceId || proof.cursor < event.seq || (this.source.evidenceCurrent && !this.source.evidenceCurrent(proof))) {
      proof = await this.source.evidence(id, this.signal(), listed);
      if (proof.sessionId === id && proof.workspaceId === listed.workspaceId && proof.cursor >= event.seq) {
        if (proofs.size >= 256 && !proofs.has(id)) proofs.delete(proofs.keys().next().value!);
        proofs.set(id, proof);
      } else proofs.delete(id);
    }
    this.lifetime.signal.throwIfAborted();
    if (activation !== this.activation || activation?.signal.aborted) return;
    if (!this.reserveContinuity(id)) return; // Reconciliation may have retired this slot during proof IO.
    if (proof.valid === false || proof.sessionId !== id || proof.workspaceId !== listed.workspaceId || proof.cursor < event.seq || (this.source.evidenceCurrent && !this.source.evidenceCurrent(proof))) {
      this.deliveryGaps.add(id); this.degrade('proof_invalid', { type: event.type, seq: event.seq, previous: old?.seq, valid: proof.valid, pending: proof.pending, cursor: proof.cursor, completed: proof.completed.length }); return;
    }
    // Other lanes may have filled the bounded watermark set during evidence IO.
    if (!this.watermarks[id] && Object.keys(this.watermarks).length >= 10000) { this.degrade('watermark_limit'); return; }
    if (this.source.lookupCurrent && !this.source.lookupCurrent(listed)) { this.deliveryGaps.add(id); this.degrade('proof_invalid'); return; }
    this.batch = new Map();
    try {
    // Unknown newly-created sessions are eligible by a fresh cold opening, not phone pagination.
    const current = { ...(this.watermarks[id] ?? { seq: -1 }) };
    if (proof.pending && !current.attentionId) {
      current.attentionId = randomUUID(); this.emit(proof, event, 'attention-needed', current.attentionId);
    } else if (!proof.pending && current.attentionId) {
      this.emit(proof, event, 'attention-cleared', current.attentionId); delete current.attentionId;
    }
    if (!gap && event.type === 'turn/end' && event.completed && proof.completed.some(e => e.turn === event.turn && e.sourceSeq === event.seq)) this.emit(proof, event, 'answer-finished');
    this.watermarks[id] = { ...current, seq: event.seq };
    this.state.writeNotificationStates([['producer', this.watermarks], ...[...this.batch].map(([deviceId, journal]): [string, unknown] => ['device:' + deviceId, journal])]);
    if (this.batch.size) {
      if (process.env.DSH_MOBILE_NOTIFICATION_TRACE === '1') for (const journal of this.batch.values()) for (const item of journal.items.filter(e => e.sessionId === id && e.sourceSeq === event.seq))
        notificationTrace('journal_committed', { event: createHash('sha256').update(item.eventId).digest('hex').slice(0, 12), seq: item.sequence });
      this.changed();
    }
    } finally { this.batch = undefined; }
  }
  private async reconcile(): Promise<void> {
    const activation = this.activation;
    const revision = this.coverageRevision;
    const beforeList = new Map(Object.entries(this.watermarks));
    const sessions = await this.source.list(this.signal());
    if (activation !== this.activation || activation?.signal.aborted || this.lifetime.signal.aborted) return;
    if (sessions.length > 10000) { this.degrade('session_limit'); return; }
    const visible = new Set(sessions.map(s => s.id));
    // Capture a whole authoritative recovery set once per new failure, not just
    // the eight lazy sources probed in this bounded pass. Removed sources retire.
    for (const id of this.recoveryPending) if (!visible.has(id)) this.recoveryPending.delete(id);
    for (const id of this.lazyRetry.keys()) if (!visible.has(id)) this.lazyRetry.delete(id);
    for (const id of this.blockingRecovery) if (!visible.has(id)) this.blockingRecovery.delete(id);
    for (const id of this.deliveryGaps) if (!visible.has(id)) this.deliveryGaps.delete(id);
    for (const id of this.gaps) if (!visible.has(id)) this.gaps.delete(id);
    for (const id of this.observed.keys()) if (!visible.has(id)) this.observed.delete(id);
    for (const id of this.skipped.keys()) if (!visible.has(id)) this.skipped.delete(id);
    for (const id of this.continuityIds) if (!visible.has(id)) this.continuityIds.delete(id);
    // This current authoritative set has at most 10,000 IDs; reserve its slots
    // before yielding again so live excluded traffic cannot crowd out recovery.
    for (const id of visible) this.continuityIds.add(id);
    if (this.recoveryScan) {
      for (const id of visible) this.recoveryPending.add(id);
      this.recoveryScan = false;
    }
    for (const id of Object.keys(this.watermarks)) if (!visible.has(id) && beforeList.get(id) === this.watermarks[id] && !this.queue.some(e => e.id === id)) delete this.watermarks[id];
    // Rotate before I/O, retaining failures and unprobed entries. A bad first
    // session cannot restart every pass at the head or consume everyone else's retry.
    const pass = ++this.recoveryPass;
    // Preserve temporarily excluded IDs unless current membership needs their
    // bounded slot. Long-lived deleted IDs cannot grow this queue past 10,000.
    if (new Set([...this.lazyIdle, ...visible]).size > 10000) {
      this.lazyIdle = this.lazyIdle.filter(id => visible.has(id));
      for (const id of this.lazyRetry.keys()) if (!visible.has(id)) this.lazyRetry.delete(id);
    }
    const known = new Set(this.lazyIdle);
    for (const session of sessions) if (!known.has(session.id)) { this.lazyIdle.push(session.id); known.add(session.id); }
    const lazy: string[] = [];
    for (let left = this.lazyIdle.length; left > 0 && lazy.length < 8; left--) {
      const id = this.lazyIdle.shift()!; this.lazyIdle.push(id);
      if ((this.lazyRetry.get(id)?.after ?? 0) <= pass) lazy.push(id);
    }
    const budget = AbortSignal.any([this.lifetime.signal, ...(activation ? [activation.signal] : []), AbortSignal.timeout(10_000)]);
    const byId = new Map(sessions.map(s => [s.id, s]));
    const ids = new Set([...lazy, ...sessions.filter(s => s.running || this.watermarks[s.id]?.attentionId).map(s => s.id)]);
    for (const id of ids) {
      const session = byId.get(id);
      if (!session) { this.lazyRetry.delete(id); this.recoveryPending.delete(id); this.blockingRecovery.delete(id); continue; }
      const observedSeq = this.watermarks[id]?.seq;
      let proof: NotificationEvidence;
      try {
        budget.throwIfAborted();
        proof = await this.source.evidence(id, budget, session);
        budget.throwIfAborted();
        if (proof.valid === false) throw new HostError('unavailable');
        if (proof.sessionId !== id || proof.workspaceId !== session.workspaceId || (this.source.evidenceCurrent && !this.source.evidenceCurrent(proof))) throw new HostError('unavailable');
      } catch {
        if (activation?.signal.aborted || this.lifetime.signal.aborted) return;
        this.blockingRecovery.add(id); this.degrade('reconcile_proof_failed', { running: session.running }); this.retryLazy(id, pass);
        if (budget.aborted) break; // Unprobed IDs are still in the rotated queue.
        continue;
      }
      if (activation !== this.activation || activation?.signal.aborted || this.lifetime.signal.aborted) return;
      const old = this.watermarks[id];
      // A live lane committed while this recovery read was in flight. Its newer
      // pending episode owns the cut; do not replay stale attention over it.
      if (old?.seq !== observedSeq || this.queue.some(e => e.id === id)) continue;
      if (!old && Object.keys(this.watermarks).length >= 10000) { this.degrade('watermark_limit'); this.retryLazy(id, pass); continue; }
      this.lazyRetry.delete(id); this.recoveryPending.delete(id); this.blockingRecovery.delete(id); this.deliveryGaps.delete(id); this.gaps.delete(id);
      const attentionId = proof.pending ? old?.attentionId ?? randomUUID() : undefined;
      if (proof.pending && !old?.attentionId) this.emit(proof, { seq: proof.cursor, time: Date.now(), type: 'tool/call' }, 'attention-needed', attentionId);
      if (old?.attentionId && !proof.pending) this.emit(proof, { seq: proof.cursor, time: Date.now(), type: 'tool/result' }, 'attention-cleared', old.attentionId);
      // Reconciliation owns pending state, not live event admission. In particular
      // its snapshot can already contain a terminal still waiting in our queue.
      const queued = this.queue.filter(e => e.id === session.id).map(e => e.event.seq);
      const seq = queued.length ? Math.min(...queued) - 1 : Math.max(old?.seq ?? -1, proof.cursor);
      this.watermarks[session.id] = { seq, ...(attentionId ? { attentionId } : {}) };
      if (this.observed.has(session.id) || this.observed.size < 10000) this.observed.set(session.id, seq);
      const skipped = this.skipped.get(session.id);
      if (skipped && skipped.last <= seq) this.skipped.delete(session.id);
      else if (skipped && skipped.first <= seq) skipped.first = seq + 1;
    }
    this.state.writeNotificationState('producer', this.watermarks);
    if (!this.baselineReady) { this.baselineReady = true; this.degrade('baseline_incomplete'); }
    if (this.coverage === 'degraded' && revision === this.coverageRevision && !this.recoveryScan && !this.recoveryPending.size && !this.lazyRetry.size && !this.blockingRecovery.size && !this.deliveryGaps.size && !this.gaps.size && !this.overflow && !this.queue.length && !budget.aborted) { this.skippedEvicted = false; this.coverage = 'ready'; this.changed(); }
  }
  private retryLazy(id: string, pass: number): void {
    const failures = Math.min((this.lazyRetry.get(id)?.failures ?? 0) + 1, 4);
    this.lazyRetry.set(id, { failures, after: pass + 2 ** failures }); // 2–16 minute passes, no permanent abandonment.
  }
  private journal(deviceId: string): Journal {
    if (!this.state.getDevice(deviceId)) throw new HostError('unauthorized');
    const batched = this.batch?.get(deviceId);
    if (batched) return batched;
    const key = 'device:' + deviceId, revision = this.state.notificationRevision(key);
    const cached = this.journalCache.get(deviceId);
    if (cached?.revision === revision) return cached.value;
    const value = this.state.readNotificationState<Journal>(key) ?? { epoch: randomUUID(), sequence: -1, settings: { revision: 0, enabled: false, projects: [], chats: [] }, items: [] };
    if (!this.journalCache.has(deviceId) && this.journalCache.size >= 128) this.journalCache.delete(this.journalCache.keys().next().value!);
    this.journalCache.set(deviceId, { revision, value }); return value;
  }
  private save(deviceId: string, journal: Journal): void {
    if (this.batch) { this.batch.set(deviceId, journal); return; }
    this.state.writeNotificationState('device:' + deviceId, journal);
    if (!this.journalCache.has(deviceId) && this.journalCache.size >= 128) this.journalCache.delete(this.journalCache.keys().next().value!);
    this.journalCache.set(deviceId, { revision: this.state.notificationRevision('device:' + deviceId), value: journal });
  }
  pruneSettings(deviceId: string, workspaceIds: ReadonlySet<string>, sessions: { id: string; workspaceId: string }[]): NotificationSettings {
    const j = this.journal(deviceId), d = this.state.getDevice(deviceId)!;
    const projects = j.settings.projects.filter(p => workspaceIds.has(p.workspaceId) && allowsWorkspace(d.grants.readWorkspaceIds, p.workspaceId));
    const chats = j.settings.chats.filter(c => sessions.some(s => s.id === c.sessionId && workspaceIds.has(s.workspaceId) && allowsWorkspace(d.grants.readWorkspaceIds, s.workspaceId)));
    if (projects.length !== j.settings.projects.length || chats.length !== j.settings.chats.length) j.settings = { ...j.settings, revision: j.settings.revision + 1, projects, chats };
    this.save(deviceId, j); return j.settings;
  }
  settings(deviceId: string): NotificationSettings { const j = this.journal(deviceId); this.save(deviceId, j); return j.settings; }
  putSettings(deviceId: string, input: unknown): NotificationSettings {
    const b = input as Record<string, unknown>;
    if (!b || Object.keys(b).some(k => !['expectedRevision', 'enabled', 'projects', 'chats'].includes(k)) || !Number.isSafeInteger(b.expectedRevision) || (b.expectedRevision as number) < 0 || typeof b.enabled !== 'boolean') throw new HostError('invalid_request');
    for (const [name, field, max] of [['projects', 'workspaceId', 100], ['chats', 'sessionId', 256]] as const) {
      const values = b[name];
      if (!Array.isArray(values) || values.length > max || values.some(v => !v || Object.keys(v).length !== 2 || !idValid(v[field]) || typeof v.enabled !== 'boolean') || new Set(values.map(v => v[field])).size !== values.length) throw new HostError('invalid_request');
    }
    const hadEnabled = this.enabledDevices();
    const j = this.journal(deviceId);
    const policy = { enabled: b.enabled, projects: (b.projects as NotificationSettings['projects']).slice().sort((a,b) => a.workspaceId.localeCompare(b.workspaceId)), chats: (b.chats as NotificationSettings['chats']).slice().sort((a,b) => a.sessionId.localeCompare(b.sessionId)) };
    const same = JSON.stringify({ enabled: j.settings.enabled, projects: j.settings.projects, chats: j.settings.chats }) === JSON.stringify(policy);
    if (b.expectedRevision !== j.settings.revision && !(same && b.expectedRevision === j.settings.revision - 1)) throw new HostError('conflict');
    if (!same) {
      if (policy.enabled && this.state.notificationEnabledDeviceCount() >= 64 && !j.settings.enabled) throw new HostError('rate_limited');
      j.settings = { revision: j.settings.revision + 1, ...policy };
      if (!policy.enabled) j.items = [];
    }
    this.save(deviceId, j);
    this.enabledCache = undefined;
    if (!this.enabledDevices() || !hadEnabled) {
      this.activation?.abort(); this.activation = undefined; this.activated = false; this.initializing = false; this.baselineReady = false;
      this.queue = []; this.skipped.clear(); this.skippedEvicted = false; this.lazyIdle = []; this.lazyRetry.clear();
    }
    this.activate(); if (!same) this.changed(); return j.settings;
  }
  private emissionAllowed(key: string, limit: number): boolean {
    const now = Date.now();
    for (const [id, entry] of this.emissions) if (entry.until <= now) this.emissions.delete(id);
    const entry = this.emissions.get(key) ?? { count: 0, until: now + 60000 };
    if (!this.emissions.has(key) && this.emissions.size >= 1024) return false;
    this.emissions.set(key, entry); return ++entry.count <= limit;
  }
  private emit(proof: NotificationEvidence, source: NotificationSource, kind: NotificationEvent['kind'], attentionId?: string): void {
    if (!this.emissionAllowed('host', 1000) || !this.emissionAllowed('session:' + proof.sessionId, 30)) { this.degrade('emission_rate_limit'); this.resetJournals(); return; }
    for (const deviceId of this.state.notificationEnabledDeviceIds()) {
      const device = this.state.getDevice(deviceId);
      if (!device || !allowsWorkspace(device.grants.readWorkspaceIds, proof.workspaceId)) continue;
      const j = this.journal(deviceId), s = j.settings;
      if (!s.enabled || !(s.chats.find(c => c.sessionId === proof.sessionId)?.enabled ?? s.projects.find(p => p.workspaceId === proof.workspaceId)?.enabled ?? true)) continue;
      if (j.items.some(e => e.sessionId === proof.sessionId && e.sourceSeq === source.seq && e.kind === kind)) continue;
      if (!this.emissionAllowed('device:' + deviceId, 120)) { this.degrade('device_rate_limit'); j.epoch = randomUUID(); j.items = []; this.save(deviceId, j); continue; }
      j.items = j.items.filter(e => e.occurredAt > Date.now() - RETENTION);
      j.items.push({ version: 1, eventId: randomUUID(), sequence: ++j.sequence, occurredAt: source.time, expiresAt: Date.now() + 86400_000, workspaceId: proof.workspaceId, sessionId: proof.sessionId, kind, sourceSeq: source.seq, ...(attentionId ? { attentionId } : {}), ...(kind === 'answer-finished' ? { turn: source.turn! } : {}) });
      j.items = j.items.slice(-2000); this.save(deviceId, j); if (!this.batch) this.changed();
    }
  }
  private resetJournals(): void {
    for (const deviceId of this.state.notificationEnabledDeviceIds()) {
      const j = this.journal(deviceId); if (!j.settings.enabled) continue;
      j.epoch = randomUUID(); j.items = []; this.save(deviceId, j);
    }
    if (!this.batch) this.changed();
  }
  private cursor(deviceId: string, epoch: string, sequence: number): string {
    const body = `${epoch}:${sequence}`;
    return Buffer.from(JSON.stringify([epoch, sequence, createHmac('sha256', this.key).update(`${deviceId}:${body}`).digest('base64url')])).toString('base64url');
  }
  private parseCursor(deviceId: string, value: string): [string, number] {
    try {
      if (value.length > 256 || !/^[A-Za-z0-9_-]+$/.test(value)) throw new Error();
      const bytes = Buffer.from(value, 'base64url'); if (bytes.toString('base64url') !== value) throw new Error();
      const a = JSON.parse(bytes.toString());
      if (!Array.isArray(a) || a.length !== 3 || typeof a[0] !== 'string' || !Number.isSafeInteger(a[1]) || a[1] < -1 || typeof a[2] !== 'string') throw new Error();
      const expected = this.cursor(deviceId, a[0], a[1]);
      if (expected.length !== value.length || !timingSafeEqual(Buffer.from(expected), Buffer.from(value))) throw new Error();
      return [a[0], a[1]];
    } catch { throw new HostError('invalid_request'); }
  }
  async page(deviceId: string, after?: string, limit = 50, signal?: AbortSignal) {
    if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new HostError('invalid_request');
    const cut = after ? this.parseCursor(deviceId, after) : undefined;
    // Only retained journal/pending IDs need current authority. Never scan the
    // unrelated persisted corpus on a one-second SSE tick. Legacy sources keep
    // the original catalog fallback. Do not retain a pre-await journal copy.
    const initial = this.journal(deviceId);
    const ceiling = initial.sequence;
    // Read lifetime is independent of producer activation. Disable must still
    // deliver its v1 reset, including for legacy sources that honor cancellation.
    const readSignal = AbortSignal.any([this.signal(false), ...(signal ? [signal] : [])]);
    readSignal.throwIfAborted();
    const ids = initial.settings.enabled ? [...new Set([...initial.items.map(e => e.sessionId), ...(initial.attentionSessions ?? []), ...Object.keys(this.watermarks).filter(id => this.watermarks[id]?.attentionId)])] : [];
    const found: { id: string; workspaceId: string; running?: boolean }[] = [];
    let index = 0;
    const list = !initial.settings.enabled ? found : this.source.lookup ? (await Promise.all(Array.from({ length: Math.min(16, ids.length) }, async () => {
      while (index < ids.length) { readSignal.throwIfAborted(); const listed = await this.lookup(ids[index++]!, readSignal); if (listed) found.push(listed); }
    })), found) : await this.source.list(readSignal);
    // Listing yields: never overwrite producer writes with a pre-await journal copy.
    readSignal.throwIfAborted();
    if (this.source.lookupCurrent && list.some(s => !this.source.lookupCurrent!(s))) throw new HostError('unavailable');
    const j = this.journal(deviceId);
    j.items = j.items.filter(e => e.occurredAt > Date.now() - RETENTION); this.save(deviceId, j);
    let resetRequired = !cut || cut[0] !== j.epoch || cut[1] > j.sequence || cut[1] < (j.items[0]?.sequence ?? j.sequence + 1) - 1;
    const allowed = new Map(list.map(s => [s.id, s.workspaceId]));
    const device = this.state.getDevice(deviceId); if (!device) throw new HostError('unauthorized');
    const scope = createHash('sha256').update(JSON.stringify([device.grants.readWorkspaceIds, j.settings])).digest('hex');
    const currentAttention = Object.keys(this.watermarks).filter(id => !!this.watermarks[id]?.attentionId && allowed.has(id) && allowsWorkspace(device.grants.readWorkspaceIds, allowed.get(id)!));
    if ((j.scope && j.scope !== scope) || j.attentionSessions?.some(id => !allowed.has(id) || !allowsWorkspace(device.grants.readWorkspaceIds, allowed.get(id)!))) { j.epoch = randomUUID(); resetRequired = true; }
    j.scope = scope; j.attentionSessions = currentAttention.slice(0,1000);
    j.items = j.items.filter(e => e.occurredAt > Date.now() - RETENTION); this.save(deviceId, j);
    // A commit during point lookups is left for the coalesced next page; never
    // advance its cursor without having checked that session's authority.
    const candidates = resetRequired ? [] : j.items.filter(e => e.sequence > cut![1] && e.sequence <= ceiling);
    let sequence = resetRequired ? Math.min(ceiling, j.sequence) : cut![1], examined = 0;
    const items: NotificationEvent[] = [];
    for (const e of candidates) {
      if (examined++ >= 400 || items.length >= limit) break;
      sequence = e.sequence;
      if (e.occurredAt <= Date.now() - RETENTION || allowed.get(e.sessionId) !== e.workspaceId || !allowsWorkspace(device.grants.readWorkspaceIds, e.workspaceId)) continue;
      items.push(e);
    }
    // Reset is a current-state cut, not history replay. Phone cancels its old attention alerts.
    const pending = resetRequired && j.settings.enabled ? Object.entries(this.watermarks).filter(([id, s]) => s.attentionId && allowed.has(id) && allowsWorkspace(device.grants.readWorkspaceIds, allowed.get(id)!) && (j.settings.chats.find(c => c.sessionId === id)?.enabled ?? j.settings.projects.find(p => p.workspaceId === allowed.get(id))?.enabled ?? true)).slice(0,1000).map(([sessionId, s]) => ({ sessionId, workspaceId: allowed.get(sessionId)!, attentionId: s.attentionId! })) : [];
    const page = { version: 1, epoch: j.epoch, items, pending, nextCursor: this.cursor(deviceId, j.epoch, sequence), hasMore: sequence < j.sequence, resetRequired, coverage: this.coverage };
    if (currentAttention.length > 1000) { this.degrade('attention_limit'); page.coverage = 'degraded'; }
    if (Buffer.byteLength(JSON.stringify(page)) > 128 * 1024 - 512) throw new HostError('payload_too_large');
    return page;
  }
  async close(): Promise<void> { this.lifetime.abort(); this.listeners.clear(); this.dispose?.(); if (this.timer) clearInterval(this.timer); await this.work; await Promise.all(this.lanes.values()); this.journalCache.clear(); this.enabledCache = undefined; }
}
