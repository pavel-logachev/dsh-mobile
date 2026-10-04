import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { request as httpsRequest } from 'node:https';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { prepareConfiguration, pairingInvitation } from '../src/config.ts';
import { startHostServer } from '../src/server.ts';
import type { HostAdapter, HostConfiguration } from '../src/types.ts';

function opensslPath(): string | undefined {
  const candidates = ['openssl', ...(process.platform === 'win32' && process.env.ProgramFiles ? [join(process.env.ProgramFiles, 'Git', 'mingw64', 'bin', 'openssl.exe')] : [])];
  return candidates.find(candidate => spawnSync(candidate, ['version'], { stdio: 'ignore' }).status === 0);
}
const openssl = opensslPath();

test('real HTTPS verifies certificate identity and intentional invitation SPKI pin without changing OS trust', { skip: !openssl ? 'Local OpenSSL unavailable; no certificate generator is installed by tests' : false }, async t => {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-mobile-tls-'));
  const certPath = join(dir, 'localhost.pem'), keyPath = join(dir, 'localhost.key');
  const generated = spawnSync(openssl!, ['req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-nodes', '-days', '1', '-keyout', keyPath, '-out', certPath, '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1'], { stdio: 'ignore' });
  assert.equal(generated.status, 0, 'Ephemeral synthetic certificate generation succeeds');
  const adapter: HostAdapter = {
    upstreamVersion: 'synthetic-https', async listPresets() { return []; }, async listSessions() { return []; },
    async snapshot() { throw new Error('No synthetic sessions'); }, async *watch() {},
    async createSession() { return { sessionId: 'synthetic' }; }, async prompt() {}, async cancel() {},
  };
  const config: HostConfiguration = { hostName: 'HTTPS fixture only', bind: '127.0.0.1', port: 0, statePath: join(dir, 'host.sqlite'), workspaces: [{ id: 'alpha', name: 'Alpha', path: dir }], tls: { certPath, keyPath } };
  const host = await startHostServer({ config, adapter });
  t.after(async () => { await host.close(); rmSync(dir, { recursive: true, force: true }); });
  const base = (await host.start()).baseUrl;
  const certificate = readFileSync(certPath, 'utf8');
  const prepared = await prepareConfiguration(config);
  prepared.config.includeCertificatePem = true;
  const offer = host.state.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: [] });
  const invitation = pairingInvitation(prepared, offer.pairingToken, base);
  assert.equal(invitation.baseUrl, base);
  assert.equal(invitation.certificatePem?.replace(/\r\n/g, '\n').trim(), certificate.replace(/\r\n/g, '\n').trim());
  // OpenSSL is an independent oracle for DER SPKI and its SHA-256 digest.
  const publicKey = spawnSync(openssl!, ['x509', '-in', certPath, '-pubkey', '-noout']).stdout;
  const spki = spawnSync(openssl!, ['pkey', '-pubin', '-outform', 'DER'], { input: publicKey }).stdout;
  const digest = spawnSync(openssl!, ['dgst', '-sha256', '-binary'], { input: spki }).stdout;
  assert.equal(invitation.pinSha256, 'sha256/' + digest.toString('base64'));

  function call(path: string, options: { ca?: string; servername?: string; body?: unknown; token?: string } = {}) {
    return new Promise<{ status: number; body: unknown }>((resolve, reject) => {
      const request = httpsRequest(base + '/v1' + path, { method: options.body ? 'POST' : 'GET', ...(options.ca ? { ca: options.ca } : {}), ...(options.servername ? { servername: options.servername } : {}), headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}) } }, response => {
        const chunks: Buffer[] = []; response.on('data', chunk => chunks.push(chunk));
        response.once('end', () => resolve({ status: response.statusCode!, body: JSON.parse(Buffer.concat(chunks).toString('utf8')) }));
      });
      request.once('error', reject); request.end(options.body ? JSON.stringify(options.body) : undefined);
    });
  }
  await assert.rejects(call('/capabilities'), error => ['DEPTH_ZERO_SELF_SIGNED_CERT', 'SELF_SIGNED_CERT_IN_CHAIN'].includes((error as NodeJS.ErrnoException).code!));
  await assert.rejects(call('/capabilities', { ca: certificate, servername: 'wrong.example.invalid' }), { code: 'ERR_TLS_CERT_ALTNAME_INVALID' });
  const paired = await call('/pairings', { ca: certificate, body: { pairingToken: invitation.pairingToken, deviceName: 'TLS fixture phone' } });
  assert.equal(paired.status, 201);
  const deviceToken = (paired.body as { deviceToken: string }).deviceToken;
  assert.equal((await call('/workspaces', { ca: certificate, token: deviceToken })).status, 200);
  assert.throws(() => pairingInvitation(prepared, '', 'https://wrong.example.invalid:9443'), { code: 'invalid_config' });
  const badKey = join(dir, 'other.key');
  assert.equal(spawnSync(openssl!, ['genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', badKey], { stdio: 'ignore' }).status, 0);
  await assert.rejects(prepareConfiguration({ ...config, tls: { certPath, keyPath: badKey } }), { code: 'invalid_config' });
});
