import { randomUUID } from 'node:crypto';
import { HostError } from './errors.ts';
import { questionsOf, validateAnswer } from './phone.ts';
import type { PendingQuestion, QuestionAnswer } from './phone.ts';

export interface QuestionServices {
  answer(agent: unknown, callId: string, answer: QuestionAnswer): boolean;
}
export interface QuestionBridge {
  answer(sessionId: string, callId: string, answer: QuestionAnswer): boolean;
  pending(sessionId: string): PendingQuestion[];
  close(): void;
}
/** Competes with (never invokes or impersonates) the ordinary desktop answerer.
 * The upstream cancellation signal settles losing mobile waits and removes secrets.
 */
export function createQuestionBridge(on: (listener: (request: any, next: () => Promise<QuestionAnswer>) => Promise<QuestionAnswer>) => () => unknown): QuestionBridge {
  const pending = new Map<string, { sessionId: string; question: PendingQuestion; resolve: (value: QuestionAnswer) => void; reject: (error: unknown) => void }>();
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
    const aborted = () => answer.reject(request.signal.reason);
    request.signal.addEventListener('abort', aborted, { once: true });
    try {
      const desktop = Promise.resolve().then(next).catch(error => {
        if (error?.code !== 'NO_PROVIDER') throw error;
        return answer.promise;
      });
      return await Promise.race([answer.promise, desktop]);
    } finally { pending.delete(key); request.signal.removeEventListener('abort', aborted); }
  });
  return {
    pending(sessionId) { return [...pending.values()].filter(p => p.sessionId === sessionId).map(p => p.question); },
    answer(sessionId, callId, answer) {
      const key = JSON.stringify([sessionId, callId]), entry = pending.get(key);
      if (!entry) return false;
      validateAnswer(entry.question, answer); pending.delete(key); entry.resolve(answer); return true;
    },
    close() { dispose(); for (const entry of pending.values()) entry.reject(new HostError('unavailable')); pending.clear(); },
  };
}
