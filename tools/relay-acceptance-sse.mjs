import assert from 'node:assert/strict';
import { openInnerTls } from './relay-acceptance-client.mjs';
/** Read bounded canonical SSE snapshots over the same opaque pinned inner TLS transport. */
export async function readInnerSnapshots(invitation, { token, access, publicWss, sessionId, onInitial, complete }) {
  const inner = await openInnerTls(invitation, access, { publicWss });
  let timer;
  try {
    timer = setTimeout(() => inner.destroy(new Error('Synthetic SSE deadline')), 15000);
    inner.write(`GET /v1/sessions/${sessionId}/events HTTP/1.1\r\nHost: ${new URL(invitation.baseUrl).hostname}\r\nAuthorization: Bearer ${token}\r\nAccept: text/event-stream\r\nConnection: close\r\n\r\n`);
    let raw = Buffer.alloc(0), body = Buffer.alloc(0), headerRead = false, chunked = false, initialNotified = false;
    for await (const chunk of inner) {
      raw = Buffer.concat([raw, chunk]); assert.ok(raw.length + body.length < 2 * 1024 * 1024);
      if (!headerRead) {
        const end = raw.indexOf('\r\n\r\n'); if (end < 0) continue;
        const head = raw.subarray(0, end).toString('ascii'); assert.match(head, /^HTTP\/1\.1 200 /);
        assert.match(head, /content-type:\s*text\/event-stream/i);
        chunked = /transfer-encoding:\s*chunked/i.test(head); headerRead = true; raw = raw.subarray(end + 4);
      }
      if (chunked) {
        while (true) {
          const end = raw.indexOf('\r\n'); if (end < 0) break;
          const size = Number.parseInt(raw.subarray(0, end).toString('ascii'), 16); assert.ok(Number.isSafeInteger(size));
          if (!size) throw new Error('Synthetic SSE ended before checkpoint');
          if (raw.length < end + 2 + size + 2) break;
          body = Buffer.concat([body, raw.subarray(end + 2, end + 2 + size)]); raw = raw.subarray(end + 2 + size + 2);
        }
      } else { body = Buffer.concat([body, raw]); raw = Buffer.alloc(0); }
      const snapshots = body.toString('utf8').split('\n\n').slice(0, -1).filter(event => /^event: snapshot$/m.test(event)).map(event => JSON.parse(event.split('\n').find(line => line.startsWith('data: ')).slice(6)));
      if (snapshots.length && !initialNotified) { initialNotified = true; await onInitial(snapshots[0]); }
      if (initialNotified && snapshots.some(snapshot => complete(snapshot, snapshots[0]))) return snapshots;
    }
    throw new Error('Synthetic SSE ended before checkpoint');
  } finally { clearTimeout(timer); inner.destroy(); }
}
