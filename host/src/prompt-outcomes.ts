import { createHash } from 'node:crypto';
import { HostError } from './errors.ts';
import { safeFailure } from './failure.ts';

export type PromptStatus = 'unknown' | 'queued' | 'running' | 'waiting' | 'completed' | 'failed' | 'rejected';
export interface PromptOutcome { requestId: string; status: PromptStatus; turnId?: string; summary?: string; updatedAt: string }
export interface OutcomeCheckpoint {
  nextSeq: number; caughtUp?: boolean; openTurn?: number; turn?: number;
  /** v1 proves consumption; v2 distinguishes discard; v3 settles failures; v4 silences explicit user stops. */
  consumptionVersion?: 1 | 2 | 3 | 4;
  pending?: { target: string; index: number }; approvals: string[]; questions: string[];
  outcome: PromptOutcome;
}
export interface PromptLookup { requestId: string; checkpoint?: OutcomeCheckpoint }
export interface JournalReader { read(offset: number, length: number, options?: { signal?: AbortSignal }): Promise<{ readonly events: readonly unknown[] }>; close(): Promise<void> }
export interface PromptJournal { open(sessionId: string, signal: AbortSignal): Promise<JournalReader> }
const PAGE = 256, MAX_EVENTS = 4096, MAX_BYTES = 16 * 1024 * 1024, MAX_ATTENTION = 128;
const object = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
function pendingResult(content: unknown): boolean {
  if (!Array.isArray(content)) return false;
  const text = content.map(object).find(block => block.type === 'text');
  if (typeof text?.text !== 'string') return false;
  try { return object(JSON.parse(text.text)).pending === true; } catch { return false; }
}
const END_FAILURES: Readonly<Record<string, string>> = {
  aborted: 'Задача остановлена до получения результата.',
  blocked: 'Выполнение задачи заблокировано.',
  'max-tokens': 'Достигнут лимит токенов. Полный результат не получен.',
  interrupted: 'Выполнение задачи прервано. Результат не получен.',
};
export const provenTerminal = (c: OutcomeCheckpoint) => c.consumptionVersion === 4 && !c.pending && (c.outcome.status === 'completed' || c.outcome.status === 'failed');
const terminal = provenTerminal;
export const unknownOutcome = (requestId: string): PromptOutcome => ({ requestId, status: 'unknown', updatedAt: '1970-01-01T00:00:00.000Z' });
export function outcomeToken(outcomes: readonly PromptOutcome[]): string { return createHash('sha256').update(JSON.stringify(outcomes)).digest('base64url'); }

/** Incremental journal fold. Checkpoints contain metadata only, never prompt/tool text.
 * A turn is established BEFORE user/message (agent-loop rc.5:945,1072).
 * Splices prove pending admission, not consumption: claimed is live-only (103-109).
 * Durable user/message.source.rpcId proves actual consumption (1067-1072).
 * End reasons not named by this contract fail closed to unknown, never success.
 */
export async function derivePromptOutcomes(reader: JournalReader, lookups: readonly PromptLookup[], signal: AbortSignal): Promise<OutcomeCheckpoint[]> {
  if (lookups.length > 32) throw new HostError('invalid_request');
  // Re-scan old proofs once: v1 lost discard, v2 lost unsuccessful endings,
  // v3 alerted on explicit user stop. Pre-v1 could contain false consumption.
  const states = lookups.map(item => item.checkpoint?.consumptionVersion === 4 ? structuredClone(item.checkpoint) : { consumptionVersion: 4, nextSeq: 0, approvals: [], questions: [], outcome: unknownOutcome(item.requestId) } as OutcomeCheckpoint);
  const active = states.filter(c => !terminal(c));
  if (!active.length) return states;
  let offset = Math.min(...active.map(c => c.nextSeq)), bytes = 0, count = 0, caughtUp = false;
  while (count < MAX_EVENTS) {
    signal.throwIfAborted();
    const { events } = await reader.read(offset, Math.min(PAGE, MAX_EVENTS - count), { signal });
    signal.throwIfAborted();
    if (events.length > PAGE) throw new HostError('unavailable');
    if (!events.length) { caughtUp = true; break; }
    for (const raw of events) {
      bytes += Buffer.byteLength(JSON.stringify(raw));
      if (bytes > MAX_BYTES) throw new HostError('unavailable');
      const e = object(raw), d = object(e.data);
      if (e.seq !== offset || !Number.isSafeInteger(e.time) || Number(e.time) < 0) throw new HostError('unavailable');
      for (let i = 0; i < states.length; i++) {
        const c = states[i]!;
        if (terminal(c) || c.nextSeq > offset) continue;
        const rpcId = lookups[i]!.requestId.toLowerCase();
        const update = (status: PromptStatus, summary?: string) => {
          const next: PromptOutcome = { requestId: lookups[i]!.requestId, status, ...(c.turn !== undefined ? { turnId: String(c.turn) } : {}), ...(summary !== undefined ? { summary } : {}), updatedAt: new Date(Number(e.time)).toISOString() };
          // Unrelated journal activity must not churn tokens/timestamps.
          if (next.status !== c.outcome.status || next.turnId !== c.outcome.turnId || next.summary !== c.outcome.summary) c.outcome = next;
        };
        if (e.type === 'turn/start' && Number.isSafeInteger(d.turn)) { c.openTurn = Number(d.turn); c.approvals = []; c.questions = []; }
        if (e.type === 'agent/inbox/spliced' && Array.isArray(d.inserted) && Number.isSafeInteger(d.start)) {
          const start = Number(d.start), removed = Number(d.removedCount ?? 0);
          if (c.pending && c.pending.target === d.target) {
            if (c.pending.index >= start && c.pending.index < start + removed) {
              // Normal claims have no outcome and still need user/message proof.
              // Explicit discard is terminal only if not reinserted (edit/steer)
              // later in this scan. Do not memoize rejected during the fold.
              delete c.turn; update(d.outcome === 'canceled' ? 'rejected' : 'unknown');
              delete c.pending;
            } else if (c.pending.index >= start + removed) c.pending.index += d.inserted.length - removed;
          }
          for (let j = 0; j < d.inserted.length; j++) {
            const source = object(object(d.inserted[j]).source);
            if (source.kind === 'user' && typeof source.rpcId === 'string' && source.rpcId.toLowerCase() === rpcId) {
              delete c.turn;
              c.pending = { target: String(d.target), index: start + j }; update('queued');
            }
          }
        }
        if (e.type === 'user/message') {
          const source = object(d.source);
          if (source.kind === 'user' && typeof source.rpcId === 'string' && source.rpcId.toLowerCase() === rpcId && c.openTurn !== undefined) {
            c.turn = c.openTurn; delete c.pending; update('running');
          }
        }
        if (c.openTurn !== undefined) {
          if (e.type === 'approval/asked' && typeof d.id === 'string') c.approvals = [...new Set([...c.approvals, d.id])];
          if (e.type === 'approval/decided') c.approvals = c.approvals.filter(id => id !== d.id);
          if (e.type === 'tool/call' && d.name === 'ask_user_question' && typeof d.callId === 'string') c.questions = [...new Set([...c.questions, d.callId])];
          if (e.type === 'tool/result') {
            const message = object(d.message);
            // Timed ask_user_question results can stay answerable as continued
            // (dsh-user-questions rc.5 projection.js:223-244).
            if (!pendingResult(message.content) && object(d.error).code !== 'TOOL_OUTCOME_UNKNOWN') c.questions = c.questions.filter(id => id !== message.toolCallId);
          }
          if (e.type === 'tool/ptc-dispatch' && d.name === 'ask_user_question' && d.isError !== true && pendingResult(d.content) && typeof d.subCallId === 'string') c.questions = [...new Set([...c.questions, d.subCallId])];
          if (e.type === 'user/message' && object(d.source).kind === 'user-question-reply') c.questions = c.questions.filter(id => id !== object(d.source).callId);
          if (c.approvals.length + c.questions.length > MAX_ATTENTION) throw new HostError('unavailable');
          if (c.turn === c.openTurn && (e.type === 'user/message' || e.type === 'approval/asked' || e.type === 'approval/decided' || e.type === 'tool/call' || e.type === 'tool/result' || e.type === 'tool/ptc-dispatch')) update(c.approvals.length || c.questions.length ? 'waiting' : 'running');
        }
        if (e.type === 'turn/end') {
          if (c.turn === d.turn) {
            const reason = object(d.reason);
            if (reason.kind === 'completed') update('completed');
            else if (reason.kind === 'aborted' && object(reason.reason).kind === 'user') update('rejected');
            else if (reason.kind === 'error') update('failed', safeFailure(reason.error).message);
            else if (typeof reason.kind === 'string' && Object.hasOwn(END_FAILURES, reason.kind)) update('failed', END_FAILURES[reason.kind]);
            else update('unknown');
            c.approvals = []; c.questions = [];
          }
          delete c.openTurn;
        }
        c.nextSeq = offset + 1;
      }
      offset++; count++;
    }
    if (events.length < PAGE) { caughtUp = true; break; }
    if (states.every(terminal)) break;
  }
  // An unfinished bounded scan must never advertise a stale running/waiting state.
  // Preserve fold state for the next read, exposing unknown until the head is reached.
  return states.map(c => ({ ...c, caughtUp: caughtUp || terminal(c) }));
}
