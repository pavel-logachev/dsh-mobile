import { createHash, createHmac, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { HostError } from './errors.ts';
import { allowsWorkspace, HostState } from './state.ts';

export type Coverage = 'initializing' | 'ready' | 'degraded';
export interface NotificationSource { seq: number; type: string; time: number; turn?: number; completed?: boolean }
export interface NotificationEvidence { sessionId: string; workspaceId: string; cursor: number; pending: boolean; valid?: boolean; completed: { turn: number; sourceSeq: number }[] }
export interface NotificationSources {
  subscribe(listener: (id: string, event: NotificationSource) => void): () => void;
  list(signal: AbortSignal): Promise<{ id: string; workspaceId: string; running?: boolean }[]>;
  evidence(id: string, signal: AbortSignal): Promise<NotificationEvidence>;
}
export interface NotificationEvent {
  version: 1; eventId: string; sequence: number; occurredAt: number; expiresAt: number;
  workspaceId: string; sessionId: string; kind: 'answer-finished' | 'attention-needed' | 'attention-cleared';
  sourceSeq: number; turn?: number; attentionId?: string;
}
export interface NotificationSettings { revision: number; enabled: boolean; projects: { workspaceId: string; enabled: boolean }[]; chats: { sessionId: string; enabled: boolean }[] }
interface Journal { epoch: string; sequence: number; settings: NotificationSettings; items: NotificationEvent[]; scope?: string; attentionSessions?: string[] }
interface SourceState { seq: number; attentionId?: string }
const RETENTION = 7 * 86400_000;
const relevant = new Set(['turn/end', 'approval/asked', 'approval/decided', 'tool/call', 'tool/result', 'user/message']);
const idValid = (s: unknown): s is string => typeof s === 'string' && Buffer.byteLength(s) <= 256 && !!s && !['__proto__', 'constructor', 'prototype'].includes(s) && !/[\/\\\x00-\x1f\x7f]/.test(s);

/** One serialized producer; callback retains only minimal envelopes, never arguments or text. */
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
  private scheduled = false;
  private reconciling = false;
  private overflow = false;
  baselineReady = false;
  private batch: Map<string, Journal> | undefined;
  private readonly emissions = new Map<string, { count: number; until: number }>();
  constructor(state: HostState, source: NotificationSources) {
    this.state = state; this.source = source;
    this.key = state.readNotificationState<string>('cursor-key') ?? randomBytes(32).toString('base64url');
    state.writeNotificationState('cursor-key', this.key);
  }
  async start(): Promise<void> {
    this.dispose = this.source.subscribe((id, event) => {
      if (this.lifetime.signal.aborted) return;
      if (!idValid(id) || !Number.isSafeInteger(event.seq) || event.seq < 0 || !Number.isSafeInteger(event.time) || event.time < 0 || typeof event.type !== 'string' || event.type.length > 128) { this.coverage = 'degraded'; return; }
      if (this.queue.length >= 4096) { this.coverage = 'degraded'; this.overflow = true; return; }
      this.queue.push({ id, event });
      if (this.started) this.schedule();
    });
    this.watermarks = this.state.readNotificationState<Record<string, SourceState>>('producer') ?? {};
    try {
      const sessions = await this.source.list(this.signal());
      if (sessions.length > 10000) throw new HostError('unavailable');
      const active = new Set(sessions.map(s => s.id));
      for (const id of Object.keys(this.watermarks)) if (!active.has(id)) delete this.watermarks[id];
      for (const session of sessions) {
        const evidence = await this.source.evidence(session.id, this.signal());
        if (evidence.valid === false) { this.coverage = 'degraded'; continue; }
        this.watermarks[session.id] = { seq: evidence.cursor, ...(evidence.pending ? { attentionId: this.watermarks[session.id]?.attentionId ?? randomUUID() } : {}) };
      }
      this.state.writeNotificationState('producer', this.watermarks);
      this.baselineReady = true;
      this.coverage = this.coverage === 'degraded' ? 'degraded' : 'ready';
    } catch { this.coverage = 'degraded'; }
    this.started = true; this.schedule();
    this.timer = setInterval(() => {
      if (this.reconciling || this.lifetime.signal.aborted) return; this.reconciling = true;
      this.work = this.work.then(() => this.reconcile()).catch(() => { this.coverage = 'degraded'; }).finally(() => { this.reconciling = false; });
    }, 60_000);
    this.timer.unref();
  }
  private signal(): AbortSignal { return AbortSignal.any([this.lifetime.signal, AbortSignal.timeout(10_000)]); }
  private schedule(): void {
    if (this.scheduled) return; this.scheduled = true;
    this.work = this.work.then(async () => {
    while (this.queue.length && !this.lifetime.signal.aborted) {
      const next = this.queue.shift()!;
      try { await this.accept(next.id, next.event); } catch { this.coverage = 'degraded'; }
    }
    if (!this.lifetime.signal.aborted) {
      if (this.overflow) { this.resetJournals(); this.overflow = false; await this.reconcile(); }
      this.state.writeNotificationState('producer', this.watermarks);
    }
  }).catch(() => { this.coverage = 'degraded'; }).finally(() => { this.scheduled = false; if (this.queue.length && !this.lifetime.signal.aborted) this.schedule(); }); }
  async idle(): Promise<void> {
    do { await this.work; } while ((this.scheduled || this.queue.length) && !this.lifetime.signal.aborted);
  }
  private async accept(id: string, event: NotificationSource): Promise<void> {
    // Global events include excluded subagents. Establish current membership before
    // sequence bookkeeping; absence is not source failure or a feed gap.
    const sessions = await this.source.list(this.signal());
    if (!sessions.some(s => s.id === id)) return;
    const old = this.watermarks[id];
    if (old && event.seq <= old.seq) return;
    const gap = !!old && event.seq !== old.seq + 1;
    if (gap) { this.coverage = 'degraded'; this.resetJournals(); }
    if (!old && Object.keys(this.watermarks).length >= 10000) { this.coverage = 'degraded'; return; }
    if (!relevant.has(event.type)) { this.watermarks[id] = { ...old, seq: event.seq }; return; }
    const proof = await this.source.evidence(id, this.signal());
    this.lifetime.signal.throwIfAborted();
    if (proof.valid === false) { this.coverage = 'degraded'; return; }
    this.batch = new Map();
    try {
    // Unknown newly-created sessions are eligible by a fresh cold opening, not phone pagination.
    const current = old ?? { seq: -1 };
    if (proof.pending && !current.attentionId) {
      current.attentionId = randomUUID(); this.emit(proof, event, 'attention-needed', current.attentionId);
    } else if (!proof.pending && current.attentionId) {
      this.emit(proof, event, 'attention-cleared', current.attentionId); delete current.attentionId;
    }
    if (!gap && event.type === 'turn/end' && event.completed && proof.completed.some(e => e.turn === event.turn && e.sourceSeq === event.seq)) this.emit(proof, event, 'answer-finished');
    this.watermarks[id] = { ...current, seq: event.seq };
    this.state.writeNotificationStates([['producer', this.watermarks], ...[...this.batch].map(([deviceId, journal]): [string, unknown] => ['device:' + deviceId, journal])]);
    } finally { this.batch = undefined; }
  }
  private async reconcile(): Promise<void> {
    const sessions = await this.source.list(this.signal());
    if (sessions.length > 10000) { this.coverage = 'degraded'; return; }
    const visible = new Set(sessions.map(s => s.id));
    for (const id of Object.keys(this.watermarks)) if (!visible.has(id)) delete this.watermarks[id];
    for (const session of sessions) {
      const old = this.watermarks[session.id];
      if (old && !old.attentionId && !session.running) continue;
      const proof = await this.source.evidence(session.id, this.signal());
      if (proof.valid === false) { this.coverage = 'degraded'; continue; }
      if (proof.pending && old && !old.attentionId) { old.attentionId = randomUUID(); this.emit(proof, { seq: proof.cursor, time: Date.now(), type: 'tool/call' }, 'attention-needed', old.attentionId); }
      if (old?.attentionId && !proof.pending) this.emit(proof, { seq: proof.cursor, time: Date.now(), type: 'tool/result' }, 'attention-cleared', old.attentionId);
      this.watermarks[session.id] = { seq: proof.cursor, ...(proof.pending ? { attentionId: old?.attentionId ?? randomUUID() } : {}) };
    }
    this.state.writeNotificationState('producer', this.watermarks);
    if (!this.baselineReady) { this.baselineReady = true; this.coverage = 'degraded'; }
  }
  private journal(deviceId: string): Journal {
    if (!this.state.getDevice(deviceId)) throw new HostError('unauthorized');
    return this.batch?.get(deviceId) ?? this.state.readNotificationState<Journal>('device:' + deviceId) ?? { epoch: randomUUID(), sequence: -1, settings: { revision: 0, enabled: false, projects: [], chats: [] }, items: [] };
  }
  private save(deviceId: string, journal: Journal): void { if (this.batch) this.batch.set(deviceId, journal); else this.state.writeNotificationState('device:' + deviceId, journal); }
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
    const j = this.journal(deviceId);
    const policy = { enabled: b.enabled, projects: (b.projects as NotificationSettings['projects']).slice().sort((a,b) => a.workspaceId.localeCompare(b.workspaceId)), chats: (b.chats as NotificationSettings['chats']).slice().sort((a,b) => a.sessionId.localeCompare(b.sessionId)) };
    const same = JSON.stringify({ enabled: j.settings.enabled, projects: j.settings.projects, chats: j.settings.chats }) === JSON.stringify(policy);
    if (b.expectedRevision !== j.settings.revision && !(same && b.expectedRevision === j.settings.revision - 1)) throw new HostError('conflict');
    if (!same) {
      if (policy.enabled && this.state.listDevices().filter(d => d.revokedAt === null && this.state.readNotificationState<Journal>('device:' + d.deviceId)?.settings.enabled).length >= 64 && !j.settings.enabled) throw new HostError('rate_limited');
      j.settings = { revision: j.settings.revision + 1, ...policy };
      if (!policy.enabled) j.items = [];
    }
    this.save(deviceId, j); return j.settings;
  }
  private emissionAllowed(key: string, limit: number): boolean {
    const now = Date.now();
    for (const [id, entry] of this.emissions) if (entry.until <= now) this.emissions.delete(id);
    const entry = this.emissions.get(key) ?? { count: 0, until: now + 60000 };
    if (!this.emissions.has(key) && this.emissions.size >= 1024) return false;
    this.emissions.set(key, entry); return ++entry.count <= limit;
  }
  private emit(proof: NotificationEvidence, source: NotificationSource, kind: NotificationEvent['kind'], attentionId?: string): void {
    if (!this.emissionAllowed('host', 1000) || !this.emissionAllowed('session:' + proof.sessionId, 30)) { this.coverage = 'degraded'; this.resetJournals(); return; }
    for (const device of this.state.listDevices()) {
      if (!this.state.getDevice(device.deviceId) || !allowsWorkspace(device.grants.readWorkspaceIds, proof.workspaceId)) continue;
      const j = this.journal(device.deviceId), s = j.settings;
      if (!s.enabled || !(s.chats.find(c => c.sessionId === proof.sessionId)?.enabled ?? s.projects.find(p => p.workspaceId === proof.workspaceId)?.enabled ?? true)) continue;
      if (j.items.some(e => e.sessionId === proof.sessionId && e.sourceSeq === source.seq && e.kind === kind)) continue;
      if (!this.emissionAllowed('device:' + device.deviceId, 120)) { this.coverage = 'degraded'; j.epoch = randomUUID(); j.items = []; this.save(device.deviceId, j); continue; }
      j.items = j.items.filter(e => e.occurredAt > Date.now() - RETENTION);
      j.items.push({ version: 1, eventId: randomUUID(), sequence: ++j.sequence, occurredAt: source.time, expiresAt: Date.now() + 86400_000, workspaceId: proof.workspaceId, sessionId: proof.sessionId, kind, sourceSeq: source.seq, ...(attentionId ? { attentionId } : {}), ...(kind === 'answer-finished' ? { turn: source.turn! } : {}) });
      j.items = j.items.slice(-2000); this.save(device.deviceId, j);
    }
  }
  private resetJournals(): void {
    for (const d of this.state.listDevices()) if (this.state.getDevice(d.deviceId)) {
      const j = this.journal(d.deviceId); if (!j.settings.enabled) continue;
      j.epoch = randomUUID(); j.items = []; this.save(d.deviceId, j);
    }
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
  async page(deviceId: string, after?: string, limit = 50) {
    if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new HostError('invalid_request');
    const cut = after ? this.parseCursor(deviceId, after) : undefined;
    const list = await this.source.list(this.signal());
    // Listing yields: never overwrite producer writes with a pre-await journal copy.
    this.lifetime.signal.throwIfAborted();
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
    const candidates = resetRequired ? [] : j.items.filter(e => e.sequence > cut![1]);
    let sequence = resetRequired ? j.sequence : cut![1], examined = 0;
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
    if (currentAttention.length > 1000) { this.coverage = 'degraded'; page.coverage = 'degraded'; }
    if (Buffer.byteLength(JSON.stringify(page)) > 128 * 1024 - 512) throw new HostError('payload_too_large');
    return page;
  }
  async close(): Promise<void> { this.lifetime.abort(); this.dispose?.(); if (this.timer) clearInterval(this.timer); await this.work; }
}
