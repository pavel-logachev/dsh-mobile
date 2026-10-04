import assert from 'node:assert/strict';
import { createServer } from 'node:tls';
import { createRequire } from 'node:module';
import { createHash, randomUUID, X509Certificate } from 'node:crypto';
import { mkdtemp, readFile, writeFile, rm, mkdir } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import { openInnerTls } from './relay-acceptance-client.mjs';
import { largeSse } from './relay-acceptance-stream.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const parent = path.join(root, 'artifacts/relay-acceptance'); await mkdir(parent, { recursive: true });
const run = await mkdtemp(path.join(parent, 'large-'));
let relay, state, connector, host, hostState;
const receipt = { kind: 'opaque-relay-large-SSE-probe', native: false, fixtureOnly: true, actualDsh: false, passed: false, cleanup: {} };
let checkpoint = 'setup';
try {
  const relayModule = await import(pathToFileURL(path.join(root, 'relay/src/index.ts')).href);
  const { HostState } = await import(pathToFileURL(path.join(root, 'host/src/state.ts')).href);
  const { createRelayConnector } = await import(pathToFileURL(path.join(root, 'host/src/relay-connector.ts')).href);
  state = new relayModule.RelayState(':memory:'); const route = state.provisionRoute();
  const authority = `h-${route.routeId}.dsh.invalid`;
  const certPath = path.join(run, 'cert.pem'), keyPath = path.join(run, 'key.pem');
  const openssl = process.platform === 'win32' ? path.join(process.env.ProgramFiles, 'Git/mingw64/bin/openssl.exe') : 'openssl';
  assert.equal(spawnSync(openssl, ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-keyout', keyPath, '-out', certPath, '-subj', `/CN=${authority}`, '-addext', `subjectAltName=DNS:${authority}`], { stdio: 'ignore' }).status, 0);
  const cert = await readFile(certPath, 'utf8'), key = await readFile(keyPath, 'utf8');
  const bytes = largeSse();
  host = createServer({ cert, key }, socket => {
    socket.once('data', async () => {
      socket.write('HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n');
      // Keep each source write bounded and respect backpressure; connector emits independent FIN messages.
      for (let offset = 0; offset < bytes.length; offset += 16384) {
        if (!socket.write(bytes.subarray(offset, offset + 16384))) await new Promise(resolve => socket.once('drain', resolve));
      }
      socket.end();
    });
  });
  await new Promise(resolve => host.listen(0, '127.0.0.1', resolve));
  relay = relayModule.createRelayServer({ state, port: 0 }); const outer = await relay.start();
  hostState = new HostState(path.join(run, 'host.sqlite'));
  connector = createRelayConnector({ relay: { url: outer.baseUrl, ...route }, state: hostState, targetPort: host.address().port, allowInsecureLoopback: true });
  connector.start();
  const offer = hostState.createRemotePairing({ readWorkspaceIds: ['demo'], executeWorkspaceIds: ['demo'] }, route.routeId, 30000);
  await connector.waitPublished(offer.relayAccess.accessId);
  const certificate = new X509Certificate(cert);
  const invitation = { version: 2, baseUrl: `https://${authority}`, certificatePem: cert,
    pinSha256: 'sha256/' + createHash('sha256').update(certificate.publicKey.export({ type: 'spki', format: 'der' })).digest('base64'),
    relay: { url: outer.baseUrl, routeId: route.routeId, ...offer.relayAccess } };
  checkpoint = 'stream';
  const inner = await openInnerTls(invitation);
  inner.write(`GET /synthetic-large HTTP/1.1\r\nHost: ${authority}\r\nConnection: close\r\n\r\n`);
  const chunks = []; let count = 0;
  for await (const chunk of inner) { count += chunk.length; assert.ok(count <= bytes.length + 1024); chunks.push(chunk); }
  const result = Buffer.concat(chunks); const boundary = result.indexOf('\r\n\r\n');
  assert.ok(boundary > 0); assert.deepEqual(result.subarray(boundary + 4), bytes);
  receipt.bytes = bytes.length; receipt.completeSnapshots = 2; receipt.perSnapshotLimit = 2097152; receipt.websocketMessageLimit = 32768;
  receipt.passed = true;
} catch { receipt.failure = `Safe large-relay checkpoint: ${checkpoint}`; process.exitCode = 1; }
finally {
  await connector?.close();
  await relay?.close();
  receipt.cleanup.relayStats = relay?.stats();
  await new Promise(resolve => host ? host.close(resolve) : resolve());
  hostState?.close(); state?.close();
  for (const name of ['cert.pem', 'key.pem', 'host.sqlite', 'host.sqlite-shm', 'host.sqlite-wal']) {
    const target = path.join(run, name); assert.ok(target.startsWith(run + path.sep)); await rm(target, { force: true });
  }
  receipt.cleanup.privateFilesRemoved = true;
  await writeFile(path.join(run, 'receipt.json'), JSON.stringify(receipt, null, 2));
  console.log(JSON.stringify({ kind: receipt.kind, passed: receipt.passed, native: false, receiptPath: path.join(run, 'receipt.json'), cleanup: receipt.cleanup }));
}
