import assert from 'node:assert/strict';
import { test } from 'node:test';
import { SNAPSHOT_LIMIT, RELAY_FIN_LIMIT, largeSnapshot, largeSse, finChunks } from './relay-acceptance-stream.mjs';

test('near-limit complete snapshots and cumulative SSE cross 2MiB without WebSocket fragmentation', () => {
  const snapshot = Buffer.from(JSON.stringify(largeSnapshot()));
  assert.ok(snapshot.length < SNAPSHOT_LIMIT && snapshot.length > SNAPSHOT_LIMIT - 8192);
  const sse = largeSse();
  assert.ok(sse.length > SNAPSHOT_LIMIT);
  const chunks = finChunks(sse);
  assert.ok(chunks.length > 64);
  assert.ok(chunks.every(chunk => chunk.length > 0 && chunk.length <= RELAY_FIN_LIMIT));
  assert.deepEqual(Buffer.concat(chunks), sse);
  const frames = sse.toString('utf8').split('\n\n').filter(Boolean);
  assert.equal(frames.length, 2);
  for (const frame of frames) {
    const json = frame.split('\ndata: ')[1];
    assert.ok(Buffer.byteLength(json) <= SNAPSHOT_LIMIT);
    assert.equal(JSON.parse(json).messages[0].text.length, SNAPSHOT_LIMIT - 4096 + 'SYNTHETIC_'.length);
  }
  // This proves generator bounds only. Live opaque forwarding is a separate gated integration check.
});
