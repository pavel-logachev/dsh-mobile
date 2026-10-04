import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { Duplex } from 'node:stream';
import { once } from 'node:events';
import tls from 'node:tls';
import { createHash, X509Certificate } from 'node:crypto';
import { checkedOuterUrl } from './relay-acceptance-evidence.mjs';
const require = createRequire(new URL('../relay/package.json', import.meta.url));
const { WebSocket } = require('ws');

/** Isolated acceptance probe only: actual mobile protocol socket -> opaque relay -> inner TLS. */
export async function openInnerTls(invitation, access = invitation.relay, { publicWss = false } = {}) {
  checkedOuterUrl(invitation.relay.url, publicWss);
  assert.equal(invitation.version, 2);
  const logical = new URL(invitation.baseUrl);
  assert.equal(logical.hostname, `h-${invitation.relay.routeId}.dsh.invalid`);
  const ws = new WebSocket(invitation.relay.url + '/v1/mobile', {
    headers: { Authorization: `Bearer ${access.accessToken}`, 'X-DSH-Route': invitation.relay.routeId, 'X-DSH-Access': access.accessId },
    perMessageDeflate: false, maxPayload: 32768, maxFragments: 1, followRedirects: false, handshakeTimeout: 10000,
    rejectUnauthorized: true, // Outer WSS uses ordinary Node trust; inner private CA is never supplied here.
  });
  ws.on('error', () => {});
  const ready = once(ws, 'message');
  ready.catch(() => {});
  let timer;
  let data, binary;
  try {
    [data, binary] = await Promise.race([
      (async () => { await once(ws, 'open'); return ready; })(),
      new Promise((_, reject) => { timer = setTimeout(() => { ws.terminate(); reject(new Error('Relay ready deadline')); }, 10000); }),
      once(ws, 'close').then(() => { throw new Error('Relay closed before ready'); }),
    ]);
    assert.equal(binary, true); assert.deepEqual(JSON.parse(data.toString()), { type: 'ready', version: 1 });
  } catch (error) { ws.terminate(); throw error; }
  finally { clearTimeout(timer); }
  const tunnel = new Duplex({
    read() {},
    write(chunk, encoding, callback) {
      let offset = 0;
      const next = error => {
        if (error) return callback(error);
        if (offset >= chunk.length) return callback();
        const part = chunk.subarray(offset, offset + 32768); offset += part.length;
        ws.send(part, { binary: true, fin: true, compress: false }, next);
      };
      next();
    },
    destroy(error, callback) { ws.terminate(); callback(error); },
  });
  ws.on('message', (chunk, isBinary) => {
    if (!isBinary || chunk.length > 32768) return tunnel.destroy(new Error('Invalid relay data'));
    tunnel.push(chunk);
  });
  ws.on('close', () => tunnel.push(null));
  ws.on('error', () => tunnel.destroy(new Error('Relay transport unavailable')));
  const inner = tls.connect({ socket: tunnel, servername: logical.hostname, ca: invitation.certificatePem, rejectUnauthorized: true });
  inner.on('error', () => {});
  await once(inner, 'secureConnect');
  const certificate = new X509Certificate(inner.getPeerCertificate().raw);
  const pin = 'sha256/' + createHash('sha256').update(certificate.publicKey.export({ format: 'der', type: 'spki' })).digest('base64');
  assert.equal(pin, invitation.pinSha256, 'SPKI must match before inner HTTP credentials are sent');
  return inner;
}

export async function innerRequest(invitation, { method = 'GET', target, token, body, access = invitation.relay, publicWss = false }) {
  const inner = await openInnerTls(invitation, access, { publicWss });
  try {
    const payload = body === undefined ? '' : JSON.stringify(body);
    const headers = [`${method} ${target} HTTP/1.1`, `Host: ${new URL(invitation.baseUrl).hostname}`, 'Connection: close', 'Accept: application/json'];
    if (token) headers.push(`Authorization: Bearer ${token}`);
    if (payload) headers.push('Content-Type: application/json; charset=utf-8', `Content-Length: ${Buffer.byteLength(payload)}`);
    inner.write(headers.join('\r\n') + '\r\n\r\n' + payload);
    const buffers = [];
    let size = 0;
    for await (const chunk of inner) { size += chunk.length; assert.ok(size <= 2 * 1024 * 1024 + 65536); buffers.push(chunk); }
    const response = Buffer.concat(buffers);
    const boundary = response.indexOf('\r\n\r\n'); assert.ok(boundary > 0);
    const head = response.subarray(0, boundary).toString('ascii'), raw = response.subarray(boundary + 4);
    const status = Number(/^HTTP\/1\.1 (\d+)/.exec(head)?.[1]);
    let jsonBody = raw;
    if (/transfer-encoding: chunked/i.test(head)) {
      const pieces = []; let cursor = 0;
      while (true) {
        const line = raw.indexOf('\r\n', cursor); const length = Number.parseInt(raw.subarray(cursor, line).toString('ascii'), 16);
        assert.ok(Number.isFinite(length)); if (!length) break;
        cursor = line + 2; pieces.push(raw.subarray(cursor, cursor + length)); cursor += length + 2;
      }
      jsonBody = Buffer.concat(pieces);
    }
    return { status, body: JSON.parse(jsonBody.toString('utf8')) };
  } finally { inner.destroy(); }
}
