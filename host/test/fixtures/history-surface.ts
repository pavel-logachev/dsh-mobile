import { readFileSync } from 'node:fs';

// Exact seq/type/surface placement from three reproduced rc.2 bounded cuts.
// Timestamps, IDs, message text and all other data are invented. No private payloads.
type TopologyEvent = [number, string, ('append' | { op: 'replace'; startSeq: number; endSeq: number } | null)?, (number | null)?, (string | null)?];
export interface HistoryRecord { type: 'event'; event: { seq: number; time: number; type: string; data: Record<string, unknown>; surfaceOp?: unknown } }
export const historyCases = JSON.parse(readFileSync(new URL('./history-surface-topology.json', import.meta.url), 'utf8')) as {
  name: string; cursor: number; firstSeq: number; expectedMessageSeqs: number[]; events: TopologyEvent[];
}[];
export function historyRecords(topology: typeof historyCases[number]): HistoryRecord[] {
  return topology.events.map(([seq, type, surfaceOp, turn, reason]) => {
    const message = { id: `message-${seq}`, role: type === 'assistant/message' ? 'assistant' : 'user', source: { kind: 'user' }, content: [{ type: 'text', text: `Synthetic message ${seq}` }] };
    const data: Record<string, unknown> = { ...(turn == null ? {} : { turn }), ...(reason == null ? {} : { reason: { kind: reason } }) };
    if (type === 'user/message') Object.assign(data, message);
    if (type === 'assistant/message') data.message = message;
    return { type: 'event', event: { seq, time: 1700000000000 + seq, type, data, ...(surfaceOp == null ? {} : { surfaceOp }) } };
  });
}
/** rc.2 page() counts append-origin messages, not replacement messages. */
export function historyPage(records: readonly HistoryRecord[], throughSeq: number, beforeSeq: number, maxMessages: number) {
  const end = Math.min(throughSeq + 1, beforeSeq);
  let cut = 0, messages = 0;
  for (let index = end - 1; index >= 0; index--) {
    const event = records[index]!.event;
    if ((event.type === 'user/message' || event.type === 'assistant/message') && event.surfaceOp === 'append' && ++messages === maxMessages) { cut = index; break; }
  }
  return { records: records.slice(cut, end), hasMore: cut > 0 };
}
