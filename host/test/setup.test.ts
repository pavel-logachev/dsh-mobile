import assert from 'node:assert/strict';
import { createHash, createPrivateKey, X509Certificate, randomBytes } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { HostState } from '../src/state.ts';
import { mkdtemp, readFile, rm, writeFile, symlink, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { inflateSync } from 'node:zlib';
import jsQR from 'jsqr';
import { runAdminCli } from '../src/cli.ts';
import { prepareConfiguration, pairingInvitation } from '../src/config.ts';

async function setup(t: { after(fn: () => Promise<void>): void }) {
  const root = await mkdtemp(join(tmpdir(), 'dsh-mobile-setup-'));
  t.after(async () => { await rm(root, { recursive: true, force: true }); });
  const configPath = join(root, 'private', 'host.json');
  let out = '', error = '';
  const io = { out(text: string) { out += text; }, error(text: string) { error += text; } };
  const args = ['setup-direct', '--config', configPath, '--host', 'computer.example', '--host', '127.0.0.1', '--host', '100.64.0.10', '--dsh-version', '0.2.1-alpha.1'];
  assert.equal(await runAdminCli(args, io), 0, error);
  const config = JSON.parse(await readFile(configPath, 'utf8'));
  return { root, configPath, config, args, io, output: () => out, error: () => error };
}

test('setup-direct creates stable registry TLS identity satisfying Android trust/date/SAN and server usage rules', async t => {
  const h = await setup(t);
  assert.equal(h.config.workspaceSource, 'dsh-registry');
  assert.equal(h.config.dshVersion, '0.2.1-alpha.1');
  assert.equal(h.config.bind, '0.0.0.0'); assert.equal(h.config.port, 19445);
  assert.equal(h.config.publicUrl, 'https://computer.example:19445');
  assert.equal(h.config.includeCertificatePem, true); assert.equal(h.config.workspaces, undefined);
  const pem = await readFile(h.config.tls.certPath, 'utf8'), key = await readFile(h.config.tls.keyPath, 'utf8');
  assert.ok(Buffer.byteLength(pem) < 16 * 1024);
  assert.match(pem.trim(), /^-----BEGIN CERTIFICATE-----\s+[A-Za-z0-9+/=\r\n]+\s+-----END CERTIFICATE-----$/);
  const cert = new X509Certificate(pem);
  assert.equal(cert.ca, false); assert.equal(cert.verify(cert.publicKey), true);
  assert.equal(cert.checkPrivateKey(createPrivateKey(key)), true);
  assert.equal(cert.publicKey.asymmetricKeyType, 'ec'); assert.equal(cert.publicKey.asymmetricKeyDetails?.namedCurve, 'prime256v1');
  assert.ok(Date.parse(cert.validFrom) <= Date.now()); assert.ok(Date.parse(cert.validTo) > Date.now());
  assert.ok(cert.keyUsage?.includes('1.3.6.1.5.5.7.3.1'), 'serverAuth EKU');
  assert.equal(cert.checkHost('computer.example', { subject: 'never', wildcards: false }), 'computer.example');
  assert.equal(cert.checkIP('127.0.0.1'), '127.0.0.1'); assert.equal(cert.checkIP('100.64.0.10'), '100.64.0.10');
  assert.equal(cert.checkHost('other.example', { subject: 'never' }), undefined);
  const prepared = await prepareConfiguration(h.config);
  const invitation = pairingInvitation(prepared, 'A'.repeat(43));
  assert.equal(invitation.certificatePem?.trim(), pem.trim());
  assert.equal(invitation.pinSha256, 'sha256/' + createHash('sha256').update(cert.publicKey.export({ type: 'spki', format: 'der' })).digest('base64'));
  assert.equal(await runAdminCli(h.args, h.io), 0, h.error());
  assert.equal(await readFile(h.config.tls.keyPath, 'utf8'), key, 'idempotency must not rotate identity');
  assert.ok(!h.output().includes(key)); assert.ok(!h.output().includes(pem));
});

test('setup-direct refuses unsafe hosts, partial state and unverified version without overwriting identity', async t => {
  const h = await setup(t);
  const key = await readFile(h.config.tls.keyPath, 'utf8');
  for (const host of ['example.com/path', '*.example.com', 'example.com,IP:8.8.8.8', '127.1', 'EXAMPLE.com']) {
    assert.equal(await runAdminCli(['setup-direct', '--config', h.configPath, '--host', host, '--dsh-version', '0.2.1-alpha.1'], h.io), 1, host);
  }
  assert.equal(await runAdminCli(['setup-direct', '--config', h.configPath, '--host', 'computer.example', '--dsh-version', '9.0.0'], h.io), 1);
  assert.equal(await runAdminCli(['setup-direct', '--config', 'relative.json', '--host', 'computer.example', '--dsh-version', '0.2.1-alpha.1'], h.io), 1);
  const redirected = join(h.root, 'redirected');
  await symlink(join(h.root, 'private'), redirected, process.platform === 'win32' ? 'junction' : 'dir');
  assert.equal(await runAdminCli(['setup-direct', '--config', join(redirected, 'host.json'), '--host', 'computer.example', '--dsh-version', '0.2.1-alpha.1'], h.io), 1);
  const repository = join(h.root, 'synthetic-repo'); await mkdir(join(repository, '.git'), { recursive: true });
  assert.equal(await runAdminCli(['setup-direct', '--config', join(repository, 'private', 'host.json'), '--host', 'computer.example', '--dsh-version', '0.2.1-alpha.1'], h.io), 1);
  const changedState = join(h.root, 'unapproved-target', 'host.sqlite');
  await writeFile(h.configPath, JSON.stringify({ ...h.config, statePath: changedState }));
  assert.equal(await runAdminCli(h.args, h.io), 1);
  await assert.rejects(readFile(changedState), { code: 'ENOENT' });
  await writeFile(h.configPath, '{}');
  assert.equal(await runAdminCli(h.args, h.io), 1);
  assert.equal(await readFile(h.configPath, 'utf8'), '{}'); assert.equal(await readFile(h.config.tls.keyPath, 'utf8'), key);
});

test('pairing CLI requires explicit private output before creating any offer or state', async t => {
  const root = await mkdtemp(join(tmpdir(), 'dsh-mobile-output-consent-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const configPath = join(root, 'host.json'), statePath = join(root, 'state', 'host.sqlite');
  await writeFile(configPath, JSON.stringify({ hostName: 'Synthetic output consent', workspaceSource: 'dsh-registry', bind: '127.0.0.1', port: 9443, allowInsecureLoopback: true, statePath }));
  for (const args of [['pair'], ['remote-pair'], ['remote-pair', '--qr']]) {
    let output = '', errors = '';
    const code = await runAdminCli([...args, '--config', configPath, '--read', 'all'], { out(text) { output += text; }, error(text) { errors += text; } });
    assert.equal(code, 1);
    assert.equal(output, '', 'no implicit secret or status output');
    assert.match(errors, /^invitation_output_required: /);
    assert.match(errors, /--qr.*--output/);
    assert.doesNotMatch(errors, /pairingToken|accessToken|token_hash/);
    await assert.rejects(readFile(statePath), { code: 'ENOENT' }, 'reject before state/offer creation');
  }
});

test('pair QR round-trips zlib envelope and independent QR decoder without leaking plaintext', async t => {
  const h = await setup(t);
  const output = join(h.root, 'private', 'invitations', 'synthetic.json');
  let printed = '', error = '';
  assert.equal(await runAdminCli(['pair', '--config', h.configPath, '--read', 'all', '--execute', 'all', '--qr', '--output', output], { out(text) { printed += text; }, error(text) { error += text; } }), 0, error);
  const invitation = JSON.parse(await readFile(output, 'utf8'));
  assert.equal(printed.includes(invitation.pairingToken), false);
  assert.match(error, /Do not share/); assert.match(error, /Clear-Host/);
  const { encodeInvitationQr, invitationQr } = await import('../src/qr.ts');
  const payload = encodeInvitationQr(invitation);
  assert.match(payload, /^dshm1:[A-Za-z0-9_-]+$/);
  assert.ok(payload.length <= 87388);
  const compressed = Buffer.from(payload.slice(6), 'base64url'); assert.ok(compressed.length <= 65536);
  const decoded = inflateSync(compressed); assert.ok(decoded.length <= 65536);
  assert.deepEqual(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(decoded)), invitation);
  const qr = invitationQr(invitation);
  assert.ok(qr.version <= 40); assert.equal(qr.errorCorrectionLevel.bit, 0, 'M');
  const size = qr.modules.size + 8, scale = 4, pixels = new Uint8ClampedArray(size * scale * size * scale * 4);
  for (let y = 0; y < size * scale; y++) for (let x = 0; x < size * scale; x++) {
    const mx = Math.floor(x / scale) - 4, my = Math.floor(y / scale) - 4;
    const black = mx >= 0 && my >= 0 && mx < qr.modules.size && my < qr.modules.size && qr.modules.get(my, mx);
    const offset = (y * size * scale + x) * 4; pixels.fill(black ? 0 : 255, offset, offset + 3); pixels[offset + 3] = 255;
  }
  const result = jsQR(pixels, size * scale, size * scale);
  assert.equal(result?.data, payload, 'independent decoder proves symbol carries exact envelope');
  t.diagnostic(`P-256 invitation: ${Buffer.byteLength(JSON.stringify(invitation))} JSON bytes, ${payload.length} ASCII characters, QR version ${qr.version}, M`);
  assert.throws(() => encodeInvitationQr({ text: 'x'.repeat(65536) }), { code: 'payload_too_large' });
  assert.throws(() => invitationQr({ text: randomBytes(12000).toString('base64url') }), { code: 'qr_too_large' });
});

test('built dist CLI keeps oversized QR fallback file usable without revoking the published remote grant', { timeout: 30000 }, async t => {
  const h = await setup(t), routeId = 'a'.repeat(32), hostname = `h-${routeId}.dsh.invalid`;
  const candidates = ['openssl', ...(process.platform === 'win32' && process.env.ProgramFiles ? [join(process.env.ProgramFiles, 'Git', 'mingw64', 'bin', 'openssl.exe'), join(process.env.ProgramFiles, 'Git', 'ucrt64', 'bin', 'openssl.exe')] : [])];
  const openssl = candidates.find(candidate => spawnSync(candidate, ['version'], { stdio: 'ignore' }).status === 0);
  assert.ok(openssl, 'setup prerequisite is installed');
  // A valid self-signed certificate with a random custom extension: long but within JSON bounds.
  const extension = randomBytes(5000).toString('hex');
  assert.equal(spawnSync(openssl, ['req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-sha256', '-nodes', '-days', '1', '-keyout', h.config.tls.keyPath, '-out', h.config.tls.certPath, '-subj', '/CN=Synthetic oversized QR', '-addext', `subjectAltName=DNS:${hostname}`, '-addext', `1.3.6.1.4.1.55555.1=ASN1:UTF8String:${extension}`], { stdio: 'ignore', timeout: 10000 }).status, 0);
  const config = { ...h.config, bind: '127.0.0.1', publicUrl: `https://${hostname}`, relay: { url: 'wss://relay.example/transport', routeId, connectorToken: randomBytes(32).toString('base64url') } };
  await writeFile(h.configPath, JSON.stringify(config));
  const invitationPath = join(h.root, 'private', 'invitations', 'oversized.private.json');
  const state = new HostState(config.statePath);
  let published = false;
  const publication = setInterval(() => {
    const grants = state.relayGrantSnapshot(routeId);
    if (grants.length) published = true;
    state.acknowledgeRelaySnapshot(routeId, grants, 'synthetic-generation');
    state.updateRelayStatus(routeId, true, true, 'synthetic-generation');
  }, 20);
  try {
    const child = spawn(process.execPath, [fileURLToPath(new URL('../dist/cli.js', import.meta.url)), 'remote-pair', '--config', h.configPath, '--read', 'all', '--qr', '--output', invitationPath], { stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '', errors = '';
    child.stdout.setEncoding('utf8'); child.stdout.on('data', text => { output += text; });
    child.stderr.setEncoding('utf8'); child.stderr.on('data', text => { errors += text; });
    const exitCode = await new Promise<number | null>((resolve, reject) => { child.once('error', reject); child.once('close', resolve); });
    clearInterval(publication);
    const invitation = JSON.parse(await readFile(invitationPath, 'utf8'));
    const { encodeInvitationQr } = await import('../src/qr.ts');
    assert.ok(encodeInvitationQr(invitation).length > 2331, 'exercise real QR capacity failure');
    assert.ok(Buffer.byteLength(JSON.stringify(invitation)) <= 65536, 'not a protocol size failure');
    assert.equal(published, true);
    assert.equal(exitCode, 0, errors);
    assert.match(errors, /Invitation exceeds QR capacity; use the private JSON file instead/);
    assert.equal(state.relayPublication(invitation.relay.accessId)?.revoked, false);
    assert.equal(state.remotePairingRoute(invitation.pairingToken), routeId, 'saved invitation retains a consumable remote offer');
    assert.doesNotMatch(output + errors, new RegExp(`${invitation.pairingToken}|${invitation.relay.accessToken}|${config.relay.connectorToken}`));
  } finally { clearInterval(publication); state.close(); }
});
