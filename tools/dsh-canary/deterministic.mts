import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import type { LlmAdapter, GenerateOptions, StreamChunk, LlmResolvedModelInfo } from '@deepseek-ai/dsh-llm';
import type { CredentialProvider, CredentialKey, CredentialRecord } from '@deepseek-ai/dsh-credentials';

export const PROVIDER = 'dsh-mobile-canary';
export const MODEL = 'deterministic-text-v1';
export const ANDROID_PROMPT = 'DSH_MOBILE_CANARY_ANDROID: synthetic emulator prompt.';
export const ANDROID_ANSWER = 'CANARY_SERVE_OK — deterministic real DSH; no external model.';
export const PROMPTS = {
  live: 'DSH_MOBILE_CANARY_LIVE: synthetic prompt; return CANARY_OK.',
  reconnect: 'DSH_MOBILE_CANARY_RECONNECT: synthetic prompt; complete while observer is disconnected.',
  cancel: 'DSH_MOBILE_CANARY_CANCEL: synthetic prompt; wait until explicitly cancelled.',
  mobile: 'DSH_MOBILE_CANARY_MOBILE: synthetic prompt through the real companion adapter.',
  mobileCancel: 'DSH_MOBILE_CANARY_MOBILE_CANCEL: synthetic companion cancellation test.',
} as const;
export const ANSWERS = {
  live: 'CANARY_OK — deterministic official LLM adapter; no external model.',
  reconnect: 'CANARY_RECONNECTED — completed while observation was disconnected.',
  cancel: 'CANARY_CANCEL_PARTIAL',
  mobile: 'CANARY_MOBILE_OK — normalized from the real DSH SessionController.',
  mobileCancel: 'CANARY_MOBILE_CANCEL_PARTIAL',
} as const;
type Kind = keyof typeof PROMPTS;

/** Implements the exact installed official LLM adapter seam, not a SessionController mock. */
export function createDeterministicAdapter(Base: typeof LlmAdapter) {
  return new class DeterministicAdapter extends Base {
    readonly events = new EventEmitter();
    readonly started: Kind[] = [];
    readonly finished: string[] = [];
    readonly aborted: string[] = [];
    readonly controls = new Map<string, () => void>();
    readonly served: { sessionId: string | undefined; requestId: string | undefined; prompt: string; outcome: string }[] = [];
    calls = 0;
    active = 0;
    serve = false;

    providerInfo(provider: string) {
      assert.equal(provider, PROVIDER);
      return { id: provider, name: 'Isolated deterministic canary (no external LLM)' };
    }
    async listModels(provider: string) { return [await this.resolveModel(provider, MODEL)]; }
    async resolveModel(provider: string, model: string, signal?: AbortSignal): Promise<LlmResolvedModelInfo> {
      signal?.throwIfAborted();
      assert.equal(provider, PROVIDER);
      assert.equal(model, MODEL);
      return { provider, id: model, name: 'Deterministic canary text', inputModalities: ['text'], context: { contextWindow: 32768 }, defaultMaxTokens: 128 };
    }
    release(kind: Kind) { this.controls.get(kind)?.(); }
    releaseAll() { for (const release of this.controls.values()) release(); }

    async *stream(options: GenerateOptions): AsyncIterable<StreamChunk> {
      assert.equal(options.provider, PROVIDER);
      assert.equal(options.model, MODEL);
      assert.equal(options.tools?.length ?? 0, 0, 'Canary must offer zero tools');
      const user = options.messages.findLast(message => message.role === 'user');
      const text = user?.content.filter(block => block.type === 'text').map(block => block.text).join('') ?? '';
      const kind = (Object.entries(PROMPTS).find(([, prompt]) => prompt === text)?.[0]) as Kind | undefined;
      assert.ok(kind !== undefined || this.serve && text === ANDROID_PROMPT, 'Only exact explicitly synthetic prompts are admitted');
      const key = kind ?? 'serve';
      const answer = kind === undefined ? ANDROID_ANSWER : ANSWERS[kind];
      const served = this.serve ? { sessionId: options.sessionId as string | undefined, requestId: user && 'source' in user && user.source?.kind === 'user' && 'rpcId' in user.source ? String(user.source.rpcId) : undefined, prompt: text, outcome: 'started' } : undefined;
      if (served) this.served.push(served);
      this.calls++;
      this.active++;
      try {
        options.signal?.throwIfAborted();
        yield { type: 'block-start', index: 0, blockType: 'text' };
        const partial = answer.slice(0, Math.min(20, answer.length));
        yield { type: 'text-delta', index: 0, text: partial };
        let release: () => void = () => {};
        const gate = new Promise<void>(resolve => { release = resolve; });
        const onAbort = () => release();
        options.signal?.addEventListener('abort', onAbort, { once: true });
        this.controls.set(key, release);
        if (kind !== undefined) this.started.push(kind);
        this.events.emit('started', key);
        try {
          if (this.serve && kind === undefined) {
            const timer = setTimeout(release, 200);
            try { await gate; } finally { clearTimeout(timer); }
          } else {
            if (options.signal?.aborted) release();
            await gate;
          }
        } finally {
          this.controls.delete(key);
          options.signal?.removeEventListener('abort', onAbort);
        }
        if (options.signal?.aborted) {
          this.aborted.push(key);
          if (served) served.outcome = 'aborted';
          yield { type: 'block-end', index: 0, block: { type: 'text', text: partial } };
          yield { type: 'finish', reason: { kind: 'aborted', failure: { code: 'ABORTED', message: 'Synthetic canary cancellation' } } };
          return;
        }
        yield { type: 'text-delta', index: 0, text: answer.slice(partial.length) };
        yield { type: 'block-end', index: 0, block: { type: 'text', text: answer } };
        yield { type: 'usage', usage: { inputTokens: 1, outputTokens: 1, totalTokens: 2 } };
        this.finished.push(key);
        if (served) served.outcome = 'stop';
        yield { type: 'finish', reason: { kind: 'stop' } };
      } finally {
        this.active--;
        this.events.emit('settled', key);
      }
    }
  }();
}

/** The connection plugin requires a browser signing record; keep only this NEW record in RAM. */
export function ephemeralBrowserCredentials(Base: typeof CredentialProvider) {
  return class EphemeralBrowserCredentials extends Base {
    private record: CredentialRecord | undefined;
    private assertBrowser(key: CredentialKey) { assert.equal(String(key), 'client-connection/browser-session'); }
    async resolve(): Promise<undefined> { throw new Error('Credential reference resolution is disabled in canary'); }
    async describe() { return { configured: false, writable: false }; }
    async set(): Promise<void> { throw new Error('Credential writes disabled in canary'); }
    async unset(): Promise<void> { throw new Error('Credential writes disabled in canary'); }
    async readRecord(key: CredentialKey) { this.assertBrowser(key); return this.record; }
    async describeRecord(key: CredentialKey) { this.assertBrowser(key); return { configured: this.record !== undefined, writable: true }; }
    async listRecords() { return []; }
    async modifyRecord(key: CredentialKey, mutate: (current: CredentialRecord | undefined) => Promise<CredentialRecord | undefined>) {
      this.assertBrowser(key);
      const next = await mutate(this.record);
      if (next !== undefined) this.record = next;
      return this.record;
    }
    async deleteRecord(key: CredentialKey) { this.assertBrowser(key); this.record = undefined; }
  };
}
