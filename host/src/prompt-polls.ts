import { HostError } from './errors.ts';
import type { PromptOutcome } from './prompt-outcomes.ts';
export interface OutcomePage { token: string; outcomes: PromptOutcome[] }
interface Held { finish(): void; done: Promise<void> }

/** One held poll per device, independent of SSE. Serial reads, subscribe-before-read,
 * versioned wakeups: an event during a read schedules another read, never gets lost.
 * Fallback recheck covers external receipt/grant changes; it is NOT a status heuristic.
 */
export class PromptPolls {
  private readonly held = new Map<string, Held>();
  private closing = false;
  async get(deviceId: string, since: string | null, waitSeconds: number, read: () => Promise<OutcomePage>, subscribe: (wake: () => void) => () => void, signal: AbortSignal): Promise<OutcomePage> {
    if (this.closing) throw new HostError('unavailable');
    let wake = () => {}, generation = 0;
    const unsubscribe = subscribe(() => { generation++; wake(); });
    try {
      let seen = generation, current = await read();
      signal.throwIfAborted();
      if (!waitSeconds || since !== current.token) return current;
      this.held.get(deviceId)?.finish();
      // Bound process resources independently from the stream budget.
      if (this.held.size >= 128 && !this.held.has(deviceId)) throw new HostError('unavailable');
      let end = false, resolveDone!: () => void;
      const done = new Promise<void>(resolve => { resolveDone = resolve; });
      const registration: Held = { finish() { end = true; wake(); }, done };
      this.held.set(deviceId, registration);
      const abort = () => { end = true; wake(); };
      signal.addEventListener('abort', abort, { once: true });
      const deadline = Date.now() + waitSeconds * 1000;
      try {
        while (!end && !this.closing && current.token === since && Date.now() < deadline) {
          if (seen === generation) await new Promise<void>(resolve => {
            const timer = setTimeout(() => { wake = () => {}; resolve(); }, Math.min(1000, Math.max(0, deadline - Date.now())));
            wake = () => { clearTimeout(timer); wake = () => {}; resolve(); };
            if (end || this.closing || seen !== generation || signal.aborted) wake();
          });
          signal.throwIfAborted();
          seen = generation; current = await read();
        }
        signal.throwIfAborted();
        return current;
      } finally {
        signal.removeEventListener('abort', abort);
        if (this.held.get(deviceId) === registration) this.held.delete(deviceId);
        resolveDone();
      }
    } finally { unsubscribe(); }
  }
  async close(): Promise<void> {
    this.closing = true;
    const polls = [...this.held.values()];
    for (const poll of polls) poll.finish();
    await Promise.all(polls.map(p => p.done));
  }
}
