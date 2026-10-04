export const SNAPSHOT_LIMIT = 2 * 1024 * 1024;
export const RELAY_FIN_LIMIT = 32 * 1024;

/** Realistic bounded snapshots; aggregate SSE intentionally exceeds any per-frame lifetime cap. */
export function largeSnapshot(cursor = 1) {
  return {
    session: { id: 'large-synthetic', title: 'Synthetic large transport boundary', workspaceId: 'demo', updatedAt: 1700000000000, running: false, canExecute: true },
    messages: [{ id: `large-${cursor}`, role: 'assistant', text: 'SYNTHETIC_' + 'x'.repeat(SNAPSHOT_LIMIT - 4096), createdAt: 1700000000000 }],
    cursor, hasMore: false, activity: 'idle',
  };
}
export function largeSse() {
  return Buffer.from([1, 2].map(cursor => `event: snapshot\ndata: ${JSON.stringify(largeSnapshot(cursor))}\n\n`).join(''), 'utf8');
}
export function finChunks(bytes) {
  const result = [];
  for (let offset = 0; offset < bytes.length; offset += RELAY_FIN_LIMIT) result.push(bytes.subarray(offset, offset + RELAY_FIN_LIMIT));
  return result;
}
