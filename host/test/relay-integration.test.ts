import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync, realpathSync } from 'node:fs';
import { createServer as createTcpServer } from 'node:net';
import { request as httpsRequest, Agent as HttpsAgent } from 'node:https';
import { connect as tlsConnect } from 'node:tls';
import { Duplex } from 'node:stream';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { WebSocket } from 'ws';
import { randomUUID } from 'node:crypto';
import { createRelayServer, RelayState } from '../../relay/src/index.ts';
import { runAdminCli } from '../src/cli.ts';
import { startHostServer } from '../src/server.ts';
import { prepareConfiguration } from '../src/config.ts';
import { FixtureAdapter, FIXTURE } from '../src/fixture-adapter.ts';
import { HostState } from '../src/state.ts';
import type { LocalHostConfiguration } from '../src/config.ts';
import type { RelayAccess } from '../src/types.ts';
const candidates = ['openssl', ...(process.platform === 'win32' && process.env.ProgramFiles ? [join(process.env.ProgramFiles, 'Git', 'mingw64', 'bin', 'openssl.exe')] : [])];
const openssl = candidates.find(candidate => spawnSync(candidate, ['version'], { stdio: 'ignore' }).status === 0);
async function reservePort(): Promise<number> { const server = createTcpServer(); await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve)); const port = (server.address() as { port: number }).port; await new Promise<void>(resolve => server.close(() => resolve())); return port; }
async function fixture(t: { after: (fn: () => Promise<void>) => void }) {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-relay-integration-')));
  const relayState = new RelayState(join(dir, 'relay.sqlite')), owner = relayState.provisionRoute();
  const relay = createRelayServer({ state: relayState, port: 0, pathPrefix: '/transport' }); const outer = (await relay.start()).baseUrl;
  const hostname = `h-${owner.routeId}.dsh.invalid`, certPath = join(dir, 'inner.pem'), keyPath = join(dir, 'inner.key');
  assert.equal(spawnSync(openssl!, ['req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-nodes', '-days', '1', '-keyout', keyPath, '-out', certPath, '-subj', `/CN=${hostname}`, '-addext', `subjectAltName=DNS:${hostname}`], { stdio: 'ignore' }).status, 0);
  const adapter = new FixtureAdapter({ answerDelayMs: 30 });
  const config: LocalHostConfiguration = { hostName: 'Relay fixture only', bind: '127.0.0.1', port: await reservePort(), statePath: join(dir, 'host.sqlite'), workspaces: [{ id: FIXTURE.workspaceId, name: FIXTURE.workspaceName, path: dir }], tls: { certPath, keyPath }, publicUrl: `https://${hostname}`, relay: { url: outer, ...owner } };
  const configPath = join(dir, 'private.json'); writeFileSync(configPath, JSON.stringify(config), { mode: 0o600 });
  const host = await startHostServer({ config, adapter, allowInsecureRelayLoopback: true });
  t.after(async () => { await host.close(); adapter.dispose(); await relay.close(); relayState.close(); rmSync(dir, { recursive: true, force: true }); });
  const certificate = readFileSync(certPath, 'utf8');
  const options = { allowInsecureRelayLoopback: true };
  let output = '', errors = ''; const io = { out: (s: string) => { output += s; }, error: (s: string) => { errors += s; } };
  const invitationPath = join(dir, 'invitations', 'invite.private.json');
  assert.equal(await runAdminCli(['remote-pair', '--config', configPath, '--read', FIXTURE.workspaceId, '--execute', FIXTURE.workspaceId, '--qr', '--output', invitationPath], io, options), 0, errors);
  const invitation = JSON.parse(readFileSync(invitationPath, 'utf8'));
  assert.equal(invitation.version, 2); assert.equal(invitation.baseUrl, `https://${hostname}`); assert.ok(invitation.certificatePem); assert.match(invitation.pinSha256, /^sha256\//);
  assert.match(errors, /Do not share/); assert.match(errors, /QR version|QR capacity/);
  assert.equal(output.includes(invitation.pairingToken), false); assert.equal(output.includes(invitation.relay.accessToken), false); assert.equal(JSON.stringify(invitation).includes(owner.connectorToken), false);
  return { dir, owner, relay, host, config, configPath, certificate, hostname, outer, invitation, io, options };
}

/** Node mobile-side fixture: TLS sees the real host cert; WS sees only opaque bytes. */
async function mobileStream(outer: string, routeId: string, access: Pick<RelayAccess, 'accessId' | 'accessToken'>) {
  const ws = new WebSocket(outer + '/v1/mobile', { headers: { Authorization: `Bearer ${access.accessToken}`, 'X-DSH-Route': routeId, 'X-DSH-Access': access.accessId }, perMessageDeflate: false, maxPayload: 32768, followRedirects: false });
  const stream = new Duplex({ read() {}, write(chunk, _encoding, callback) { const bytes = Buffer.from(chunk); let offset = 0; function next(error?: Error) { if (error || offset >= bytes.length) { callback(error); return; } const part = bytes.subarray(offset, offset + 32768); offset += part.length; ws.send(part, { binary: true, fin: true, compress: false }, next); } next(); }, destroy(error, callback) { ws.terminate(); callback(error); } });
  stream.on('error', () => {}); ws.on('error', error => stream.destroy(error)); ws.on('close', () => { stream.push(null); stream.destroy(); });
  await new Promise<void>((resolve, reject) => { ws.once('message', (data, binary) => { try { assert.equal(binary, true); assert.deepEqual(JSON.parse(data.toString()), { type: 'ready', version: 1 }); ws.on('message', (bytes, binary) => { if (!binary) stream.destroy(); else stream.push(bytes); }); resolve(); } catch (error) { reject(error); } }); ws.once('error', reject); ws.once('unexpected-response', (_request, response) => { reject(new Error(`outer_${response.statusCode}`)); ws.terminate(); }); });
  return stream;
}
async function remoteCall(f: Awaited<ReturnType<typeof fixture>>, access: Pick<RelayAccess, 'accessId' | 'accessToken'>, path: string, options: { body?: unknown; token?: string; servername?: string; ca?: string } = {}) {
  const raw = await mobileStream(f.outer, f.owner.routeId, access);
  const socket = tlsConnect({ socket: raw, servername: options.servername ?? f.hostname, ca: options.ca ?? f.certificate, minVersion: 'TLSv1.2' });
  return new Promise<{ status: number; body: any }>((resolve, reject) => {
    socket.once('error', reject);
    socket.once('secureConnect', () => {
      const agent = new HttpsAgent(); agent.createConnection = () => socket;
      const req = httpsRequest({ hostname: f.hostname, port: 443, path: '/v1' + path, method: options.body ? 'POST' : 'GET', agent, headers: { Connection: 'close', ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}) } }, res => {
        const chunks: Buffer[] = []; res.on('data', bytes => chunks.push(bytes)); res.once('end', () => { raw.destroy(); resolve({ status: res.statusCode!, body: JSON.parse(Buffer.concat(chunks).toString()) }); });
      }); req.once('error', error => { raw.destroy(); reject(error); }); req.end(options.body ? JSON.stringify(options.body) : undefined);
    });
  }).finally(() => { socket.destroy(); raw.destroy(); });
}

test('private ACK-ready invitation pairs over opaque real TLS and switches to acknowledged per-device access', { skip: !openssl, timeout: 15000 }, async t => {
  const f = await fixture(t);
  await assert.rejects(remoteCall(f, f.invitation.relay, '/capabilities', { servername: 'wrong.dsh.invalid' }), { code: 'ERR_TLS_CERT_ALTNAME_INVALID' });
  const paired = await remoteCall(f, f.invitation.relay, '/pairings', { body: { pairingToken: f.invitation.pairingToken, deviceName: 'Synthetic relay phone' } });
  assert.equal(paired.status, 201); assert.ok(paired.body.relayAccess.accessToken); assert.equal(f.host.state.relayPublication(paired.body.relayAccess.accessId)?.published, true);
  assert.equal((await remoteCall(f, paired.body.relayAccess, '/workspaces', { token: paired.body.deviceToken })).status, 200);
  assert.equal((await remoteCall(f, paired.body.relayAccess, '/pairings', { body: { pairingToken: f.invitation.pairingToken, deviceName: 'Second' } })).status, 401);
  const requestId = randomUUID();
  const command = await remoteCall(f, paired.body.relayAccess, `/sessions/${FIXTURE.existingSessionId}/messages`, { token: paired.body.deviceToken, body: { requestId, text: 'Synthetic opaque TLS prompt' } });
  assert.equal(command.status, 200);
  assert.equal((await remoteCall(f, paired.body.relayAccess, `/commands/${requestId}`, { token: paired.body.deviceToken })).body.status, 'accepted');
  const admin = new HostState(f.host.state.path); admin.revokeDevice(paired.body.deviceId); admin.close();
  const barrier = f.host.state.createRemotePairing({ readWorkspaceIds: [FIXTURE.workspaceId], executeWorkspaceIds: [] }, f.owner.routeId);
  await f.host.relay!.waitPublished(barrier.relayAccess.accessId);
  await assert.rejects(mobileStream(f.outer, f.owner.routeId, paired.body.relayAccess), /outer_401/);
  assert.equal(f.relay.stats().active, 0);
});

test('remote-pair accepts wildcard registry grants without reading the DSH profile and rejects explicit registry IDs', { skip: !openssl, timeout: 15000 }, async t => {
  const f = await fixture(t);
  const config = { ...f.config, workspaceSource: 'dsh-registry', workspaces: [] };
  writeFileSync(f.configPath, JSON.stringify(config));
  const invitationPath = join(f.dir, 'invitations', 'registry.private.json');
  assert.equal(await runAdminCli(['remote-pair', '--config', f.configPath, '--read', 'all', '--execute', 'all', '--output', invitationPath], f.io, f.options), 0);
  const invitation = JSON.parse(readFileSync(invitationPath, 'utf8'));
  const paired = await remoteCall(f, invitation.relay, '/pairings', { body: { pairingToken: invitation.pairingToken, deviceName: 'Synthetic all-project phone' } });
  assert.equal(paired.status, 201);
  assert.deepEqual(f.host.state.getDevice(paired.body.deviceId)?.grants, { readWorkspaceIds: ['*'], executeWorkspaceIds: ['*'] });
  const before = f.host.state.relayGrantSnapshot(f.owner.routeId);
  assert.equal(await runAdminCli(['grant', '--config', f.configPath, '--device', paired.body.deviceId, '--read', 'all'], f.io, f.options), 0);
  assert.deepEqual(f.host.state.relayGrantSnapshot(f.owner.routeId), before);
  assert.equal((await remoteCall(f, paired.body.relayAccess, `/sessions/${FIXTURE.existingSessionId}/messages`, { token: paired.body.deviceToken, body: { requestId: randomUUID(), text: 'Denied after narrowing' } })).status, 403);
  const deniedOutput = join(f.dir, 'denied.private.json');
  let errors = '';
  assert.equal(await runAdminCli(['remote-pair', '--config', f.configPath, '--read', FIXTURE.workspaceId, '--output', deniedOutput], { out() {}, error(text) { errors += text; } }, f.options), 1);
  assert.match(errors, /Explicit workspace IDs are only supported in explicit-list mode/);
  assert.equal(existsSync(deniedOutput), false);
});

test('remote CLI output is exclusive and never writes an unavailable invitation', { skip: !openssl, timeout: 15000 }, async t => {
  const f = await fixture(t), output = join(f.dir, 'existing.private.json');
  writeFileSync(output, 'preserved');
  assert.equal(await runAdminCli(['remote-pair', '--config', f.configPath, '--read', FIXTURE.workspaceId, '--output', output], f.io, f.options), 1);
  assert.equal(readFileSync(output, 'utf8'), 'preserved');
  await f.host.relay!.close();
  const absent = join(f.dir, 'unavailable.private.json');
  assert.equal(await runAdminCli(['remote-pair', '--config', f.configPath, '--read', FIXTURE.workspaceId, '--output', absent], f.io, f.options), 1);
  assert.equal(existsSync(absent), false);
  assert.equal(f.host.state.relayGrantSnapshot(f.owner.routeId).length, 1);
});
