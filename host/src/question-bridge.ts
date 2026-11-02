import { randomUUID } from 'node:crypto';
import { HostError } from './errors.ts';
import { questionsOf, validateAnswer } from './phone.ts';
import type { PendingQuestion, QuestionAnswer, ResolvedQuestion } from './phone.ts';
import { settledQuestions } from './phone-projection.ts';
import { MemoryQuestionHistory } from './question-history.ts';
import type { QuestionHistory } from './question-history.ts';

export interface QuestionServices {
  answer(agent: unknown, callId: string, answer: QuestionAnswer): boolean;
}
export interface QuestionBridge {
  answer(sessionId: string, callId: string, answer: QuestionAnswer): boolean;
  pending(sessionId: string): PendingQuestion[];
  settled(sessionId: string): ResolvedQuestion[];
  close(): void;
}
/** Races the ordinary desktop answerer, cancelling only its losing waiter.
 * The upstream cancellation signal settles losing mobile waits and removes secrets.
 */
export function createQuestionBridge(on: (listener: (request: any, next: () => Promise<QuestionAnswer>) => Promise<QuestionAnswer>) => () => unknown, history?: QuestionHistory): QuestionBridge {
  const memory = history ? undefined : new MemoryQuestionHistory();
  const store = history ?? memory!;
  const pending = new Map<string, { sessionId: string; question: PendingQuestion; resolve: (value: QuestionAnswer) => void; reject: (error: unknown) => void }>();
  let closed = false;
  const dispose = on(async (request, next) => {
    // The legacy tool supplies no wait metadata. Its blocking request can use
    // a bridge-local handle; only timed/continued questions need the tool call ID.
    const sessionId = request.agent?.session?.id, callId = request.wait === undefined ? `mobile-question:${randomUUID()}` : request.wait?.callId;
    const parsed = questionsOf([{ callId, questions: request.questions, state: 'open' }])[0];
    if (typeof sessionId !== 'string' || !parsed || !request.signal || request.signal.aborted || pending.size >= 128) return next();
    const key = JSON.stringify([sessionId, callId]);
    if (pending.has(key)) return next();
    let resolve!: (value: QuestionAnswer) => void, reject!: (error: unknown) => void;
    const promise = new Promise<QuestionAnswer>((yes, no) => { resolve = yes; reject = no; });
    const answer = { promise, resolve, reject };
    pending.set(key, { sessionId, question: parsed, resolve: answer.resolve, reject: answer.reject });
    const upstreamSignal: AbortSignal = request.signal, desktopLifetime = new AbortController();
    const aborted = () => answer.reject(upstreamSignal.reason);
    upstreamSignal.addEventListener('abort', aborted, { once: true });
    // Cordis next() reuses this object, and the Remote gateway reads it asynchronously.
    // Keep the desktop-only signal installed until downstream has actually settled.
    request.signal = AbortSignal.any([upstreamSignal, desktopLifetime.signal]);
    let desktopSettled = false;
    try {
      const desktop = Promise.resolve().then(next).finally(() => {
        desktopSettled = true;
        request.signal = upstreamSignal;
      }).catch(error => {
        if (error?.code !== 'NO_PROVIDER') throw error;
        return answer.promise;
      });
      const result = await Promise.race([answer.promise, desktop]);
      if (request.wait === undefined && !closed) {
        const question = settledQuestions([{ callId, answers: result.answers }])[0];
        // Snapshot retention cannot turn an already delivered upstream answer into
        // a tool failure. On storage failure keep the answer result, without logging it.
        if (question) { try { store.rememberSettledQuestion(sessionId, question); } catch { /* Best-effort history only. */ } }
      }
      return result;
    } finally {
      pending.delete(key); upstreamSignal.removeEventListener('abort', aborted);
      if (!desktopSettled) desktopLifetime.abort();
    }
  });
  return {
    pending(sessionId) { return [...pending.values()].filter(p => p.sessionId === sessionId).map(p => p.question); },
    settled(sessionId) { return closed ? [] : store.settledQuestions(sessionId); },
    answer(sessionId, callId, answer) {
      const key = JSON.stringify([sessionId, callId]), entry = pending.get(key);
      if (!entry) return false;
      validateAnswer(entry.question, answer); pending.delete(key); entry.resolve(answer); return true;
    },
    close() { closed = true; dispose(); for (const entry of pending.values()) entry.reject(new HostError('unavailable')); pending.clear(); memory?.clear(); },
  };
}
