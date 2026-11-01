import type { JournalReader, PromptJournal } from './prompt-outcomes.ts';
import { HostError } from './errors.ts';

/** Public SessionPersistence.open('read') / SessionHandle.read(offset,length).
 * No Session promotion, ownership, flush or private file decoding.
 * rc.5 JSONL internally materializes a cold log before slicing; see acceptance report.
 */
export interface OutcomePersistence {
  open(id: string, access: 'read', options?: { signal?: AbortSignal }): Promise<JournalReader>;
}
export function promptJournal(backend: () => OutcomePersistence | undefined): PromptJournal {
  return { async open(id, signal) {
    signal.throwIfAborted();
    const persistence = backend();
    if (!persistence) throw new HostError('unavailable');
    const identity = (value: OutcomePersistence | undefined) => value && (Reflect.get(value, Symbol.for('cordis.original')) ?? value);
    const reader = await persistence.open(id, 'read', { signal });
    try {
      signal.throwIfAborted();
      if (identity(backend()) !== identity(persistence)) throw new HostError('unavailable');
      return {
        async read(offset, length, options) {
          if (identity(backend()) !== identity(persistence)) throw new HostError('unavailable');
          const result = await reader.read(offset, length, options);
          options?.signal?.throwIfAborted();
          if (identity(backend()) !== identity(persistence)) throw new HostError('unavailable');
          return result;
        },
        close: () => reader.close(),
      };
    } catch (error) { await reader.close(); throw error; }
  } };
}
