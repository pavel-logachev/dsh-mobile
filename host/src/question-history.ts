import type { ResolvedQuestion } from './phone.ts';

export const LEGACY_ANSWER_TTL_MS = 24 * 60 * 60_000;
export const LEGACY_ANSWERS_PER_SESSION = 20;
export const LEGACY_ANSWERS_GLOBAL = 128;

/** Only normalized phone answers, keyed by session and opaque mobile call handle. */
export interface QuestionHistory {
  rememberSettledQuestion(sessionId: string, question: ResolvedQuestion): void;
  settledQuestions(sessionId: string): ResolvedQuestion[];
}

/** Standalone bridges retain the same bounded behavior without owning a database. */
export class MemoryQuestionHistory implements QuestionHistory {
  private readonly entries = new Map<string, { sessionId: string; question: ResolvedQuestion; expiresAt: number }>();
  private readonly now: () => number;
  constructor(now: () => number = Date.now) { this.now = now; }
  private prune(): void {
    const now = this.now();
    for (const [key, entry] of this.entries) if (entry.expiresAt <= now) this.entries.delete(key);
  }
  rememberSettledQuestion(sessionId: string, question: ResolvedQuestion): void {
    this.prune();
    const key = JSON.stringify([sessionId, question.callId]);
    this.entries.delete(key);
    this.entries.set(key, { sessionId, question: structuredClone(question), expiresAt: this.now() + LEGACY_ANSWER_TTL_MS });
    const sessionKeys = [...this.entries].filter(([, entry]) => entry.sessionId === sessionId).map(([id]) => id);
    if (sessionKeys.length > LEGACY_ANSWERS_PER_SESSION) this.entries.delete(sessionKeys[0]!);
    if (this.entries.size > LEGACY_ANSWERS_GLOBAL) this.entries.delete(this.entries.keys().next().value!);
  }
  clear(): void { this.entries.clear(); }
  settledQuestions(sessionId: string): ResolvedQuestion[] {
    this.prune();
    return structuredClone([...this.entries.values()].filter(entry => entry.sessionId === sessionId).map(entry => entry.question));
  }
}
