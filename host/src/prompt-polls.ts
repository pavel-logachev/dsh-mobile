import { HostError } from './errors.ts';
import type { PromptOutcome } from './prompt-outcomes.ts';
export interface OutcomePage { token: string; outcomes: PromptOutcome[] }
interface Held { finish(): void; done: Promise<void> }
const stopped = Symbol('stopped');

/** Observation lifetime is independent of IO settlement. Cancellation is forwarded
 * to reads, but a late reader remains observed and owns its actual adapter IO slot.
 * Subscribe before reading; generation preserves wakes during serial reads.
 */
export class PromptPolls {
  private readonly held = new Map<string, Held>();
  private readonly active = new Set<Held>();
  private closing = false;
  async get(deviceId: string, since: string | null, waitSeconds: number, read: (signal: AbortSignal) => Promise<OutcomePage>, subscribe: (wake: () => void) => () => void, signal: AbortSignal): Promise<OutcomePage> {
    if (this.closing || this.active.size >= 128) throw new HostError('unavailable');
    signal.throwIfAborted();
    const controller = new AbortController();
    let wake = () => {}, generation = 0, end = false, resolveStop!: (value: typeof stopped) => void, resolveDone!: () => void;
    const stop = new Promise<typeof stopped>(resolve => { resolveStop = resolve; });
    const registration: Held = {
      finish() { if (end) return; end = true; controller.abort(); resolveStop(stopped); wake(); },
      done: new Promise<void>(resolve => { resolveDone = resolve; }),
    };
    this.active.add(registration);
    const abort = () => registration.finish();
    signal.addEventListener('abort', abort, { once: true });
    let unsubscribe = () => {}, deadlineTimer: ReturnType<typeof setTimeout> | undefined;
    // Promise.race observes late rejection too; it never frees the adapter IO slot.
    const observe = () => Promise.race([Promise.resolve().then(() => { controller.signal.throwIfAborted(); return read(controller.signal); }), stop]);
    try {
      unsubscribe = subscribe(() => { generation++; wake(); });
      if (signal.aborted || this.closing) registration.finish();
      let seen = generation;
      const opening = await observe();
      signal.throwIfAborted();
      if (opening === stopped) throw new HostError('unavailable');
      let current = opening;
      if (!waitSeconds || since !== current.token) return current;
      this.held.get(deviceId)?.finish();
      this.held.set(deviceId, registration);
      deadlineTimer = setTimeout(() => registration.finish(), waitSeconds * 1000);
      while (!end && current.token === since) {
        if (seen === generation) await new Promise<void>(resolve => {
          const timer = setTimeout(() => { wake = () => {}; resolve(); }, 1000);
          wake = () => { clearTimeout(timer); wake = () => {}; resolve(); };
          if (end || seen !== generation) wake();
        });
        if (end) break;
        seen = generation;
        const next = await observe();
        if (next === stopped) break;
        current = next;
      }
      signal.throwIfAborted();
      return current;
    } finally {
      clearTimeout(deadlineTimer);
      registration.finish();
      signal.removeEventListener('abort', abort);
      if (this.held.get(deviceId) === registration) this.held.delete(deviceId);
      try { unsubscribe(); } finally { this.active.delete(registration); resolveDone(); }
    }
  }
  async close(): Promise<void> {
    this.closing = true;
    const polls = [...this.active];
    for (const poll of polls) poll.finish();
    await Promise.all(polls.map(p => p.done));
  }
}
