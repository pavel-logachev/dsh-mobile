import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash, X509Certificate } from 'node:crypto';
import { mkdir, mkdtemp, readFile, writeFile, rm, rename, lstat, realpath } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { AcceptanceAdapter, RELAY_PROMPT, LOST_RESPONSE_PROMPT, installLostResponseFault } from './relay-acceptance-adapter.mjs';
import { checkedOuterUrl } from './relay-acceptance-evidence.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
function options(argv) {
  const parsed = { durationMs: 600000, relayPort: 19446, hostPort: 19447, openssl: process.platform === 'win32' ? path.join(process.env.ProgramFiles ?? '', 'Git/mingw64/bin/openssl.exe') : 'openssl' };
  const keys = new Map([['--duration-ms', 'durationMs'], ['--relay-port', 'relayPort'], ['--host-port', 'hostPort'], ['--openssl', 'openssl'], ['--relay-url', 'relayUrl'], ['--approved-relay-url', 'approvedRelayUrl'], ['--route-file', 'routeFile']]);
  const seen = new Set();
  for (let index = 0; index < argv.length; index += 2) {
    const key = keys.get(argv[index]);
    assert.ok(key && !seen.has(key) && argv[index + 1], 'Invalid fixture argument'); seen.add(key);
    parsed[key] = ['openssl', 'relayUrl', 'approvedRelayUrl', 'routeFile'].includes(key) ? argv[index + 1] : Number(argv[index + 1]);
  }
  assert.ok(Number.isInteger(parsed.durationMs) && parsed.durationMs >= 10000 && parsed.durationMs <= 900000);
  for (const port of [parsed.relayPort, parsed.hostPort]) assert.ok(Number.isInteger(port) && port >= 1024 && port <= 65535 && ![3080, 3081].includes(port));
  assert.notEqual(parsed.relayPort, parsed.hostPort);
  assert.equal(Boolean(parsed.relayUrl), Boolean(parsed.routeFile), 'Public mode requires both relay URL and private route file');
  if (parsed.relayUrl) {
    checkedOuterUrl(parsed.relayUrl, true, parsed.approvedRelayUrl ?? process.env.RELAY_PUBLIC_URL);
    assert.ok(path.isAbsolute(parsed.routeFile), 'Private pre-provisioned route file must be absolute');
  }
  return parsed;
}
const config = options(process.argv.slice(2));
const publicWss = Boolean(config.relayUrl);
const parent = path.join(root, 'artifacts/relay-acceptance');
await mkdir(parent, { recursive: true, mode: 0o700 });
assert.equal((await realpath(parent)).toLowerCase(), parent.toLowerCase(), 'Fixture output must not be redirected');
const run = await mkdtemp(path.join(parent, 'run-'));
const privateRoot = path.join(run, 'private');
await mkdir(privateRoot, { mode: 0o700 });
const workspace = path.join(privateRoot, 'workspace');
await mkdir(workspace);
function owned(value) { const target = path.resolve(value); assert.ok(target.startsWith(run + path.sep), 'Cleanup only owned fixture paths'); return target; }
async function atomic(name, value) {
  const target = owned(path.join(run, name)), temporary = target + '.writing';
  await writeFile(temporary, JSON.stringify(value, null, 2), { mode: 0o600 });
  await rename(temporary, target);
}
let relayState, relay, host, adapter, fault, timer;
let checkpoint = 'imports';
const accessOpens = new Map();
const outerSockets = new Set();
let bootstrapId;
let lastUpgradeAt = 0;
const observedDeviceGrantIds = new Set();
const observedReceipts = new Map();
let maxDeviceGrantsPublished = 0;
const receipt = { kind: 'native-opaque-relay-fixture', fixtureOnly: true, actualDsh: false, publicRelay: publicWss, release: false, productionCanary: false, startedAt: Date.now(), cleanup: {}, checks: [] };
const done = Promise.withResolvers();
const onSignal = () => done.resolve('signal');
process.once('SIGINT', onSignal); process.once('SIGTERM', onSignal);
function observeUpgrade(request, socket) {
  // Do not retain Authorization or full headers. Route/access IDs are fixture-owned only.
  if (request.url !== '/v1/mobile') return;
  const accessId = request.headers['x-dsh-access'];
  if (typeof accessId !== 'string') return;
  lastUpgradeAt = Date.now();
  let counted = false;
  // Relay rejects upgrades asynchronously. Count only after its server emits 101 switching protocols.
  const write = socket.write;
  socket.write = function (data, ...args) {
    if (!counted && (typeof data === 'string' || Buffer.isBuffer(data)) && data.toString().startsWith('HTTP/1.1 101')) {
      counted = true;
      accessOpens.set(accessId, (accessOpens.get(accessId) ?? 0) + 1);
    }
    return Reflect.apply(write, this, [data, ...args]);
  };
  outerSockets.add(socket); socket.once('close', () => outerSockets.delete(socket));
}
function summary() {
  const grants = host.state.relayGrantSnapshot(host.config.relay.routeId);
  const deviceGrants = grants.filter(grant => grant.deviceId !== null);
  const calls = adapter.evidence();
  for (const grant of deviceGrants) observedDeviceGrantIds.add(grant.accessId);
  maxDeviceGrantsPublished = Math.max(maxDeviceGrantsPublished, deviceGrants.filter(grant => host.state.relayPublication(grant.accessId)?.published).length);
  for (const call of calls.prompt) {
    const command = host.state.listDevices().map(device => host.state.getCommand(device.deviceId, call.requestId)).find(Boolean);
    if (command) observedReceipts.set(call.requestId, command.receipt.status);
  }
  return {
    kind: receipt.kind, fixtureOnly: true, actualDsh: false, publicRelay: publicWss, productionCanary: false,
    bootstrapPublicationComplete: host.state.relayPublication(bootstrapId)?.published === true,
    bootstrapOpens: accessOpens.get(bootstrapId) ?? 0,
    deviceGrantsPublished: deviceGrants.filter(grant => host.state.relayPublication(grant.accessId)?.published).length,
    maxDeviceGrantsPublished,
    deviceCapabilityOpens: [...observedDeviceGrantIds].reduce((sum, id) => sum + (accessOpens.get(id) ?? 0), 0),
    relay: relay?.stats() ?? { unavailable: 'Public relay counters require operator evidence; no local relay listener' }, connector: { connected: host.relay.status().connected, ready: host.relay.status().ready },
    calls, commandReceipts: calls.prompt.map(call => {
      return { requestId: call.requestId, status: observedReceipts.get(call.requestId) ?? 'not-observed' };
    }),
    fault: fault.evidence(), lastUpgradeAt,
  };
}
try {
  // Node24 source imports use coordinated working-tree modules; no build or child server launch.
  const relayModule = await import(pathToFileURL(path.join(root, 'relay/src/index.ts')).href);
  const hostModule = await import(pathToFileURL(path.join(root, 'host/src/server.ts')).href);
  const { FixtureAdapter, FIXTURE } = await import(pathToFileURL(path.join(root, 'host/src/fixture-adapter.ts')).href);
  relayState = new relayModule.RelayState(':memory:');
  let route;
  checkpoint = 'route-input';
  if (publicWss) {
    const info = await lstat(config.routeFile);
    assert.ok(info.isFile() && !info.isSymbolicLink() && info.size <= 4096, 'Private route file must be a bounded regular file');
    route = JSON.parse(await readFile(config.routeFile, 'utf8'));
    assert.deepEqual(Object.keys(route).sort(), ['connectorToken', 'routeId']);
    assert.match(route.routeId, /^[a-f0-9]{32}$/);
    assert.match(route.connectorToken, /^[A-Za-z0-9_-]{43}$/);
    receipt.cleanup.operatorRouteRevocationRequired = true;
    receipt.cleanup.inputRouteFileRetained = true;
  } else route = relayState.provisionRoute();
  checkpoint = 'generate-identity';
  const authority = `h-${route.routeId}.dsh.invalid`;
  const certPath = path.join(privateRoot, 'host-cert.pem'), keyPath = path.join(privateRoot, 'host-key.pem');
  const generated = spawnSync(config.openssl, ['req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-nodes', '-days', '1', '-keyout', keyPath, '-out', certPath, '-subj', `/CN=${authority}`, '-addext', `subjectAltName=DNS:${authority}`], { stdio: 'ignore' });
  assert.equal(generated.status, 0, 'Existing OpenSSL must generate owned synthetic host identity');
  const certificatePem = await readFile(certPath, 'utf8');
  const certificate = new X509Certificate(certificatePem);
  assert.equal(certificate.checkHost(authority), authority);
  const pinSha256 = 'sha256/' + createHash('sha256').update(certificate.publicKey.export({ type: 'spki', format: 'der' })).digest('base64');
  let outer;
  if (publicWss) outer = { baseUrl: config.relayUrl };
  else {
    relay = relayModule.createRelayServer({ state: relayState, bind: '127.0.0.1', port: config.relayPort });
    relay.server.prependListener('upgrade', observeUpgrade);
    outer = await relay.start();
    assert.equal(outer.baseUrl, `ws://127.0.0.1:${config.relayPort}`);
  }
  checkpoint = 'start-host';
  adapter = new AcceptanceAdapter(new FixtureAdapter({ answerDelayMs: 2000 }), { beforePrompt: async input => fault.beforePrompt(input) });
  host = await hostModule.createHostServer({ config: {
    hostName: FIXTURE.hostName, bind: '127.0.0.1', port: config.hostPort,
    statePath: path.join(privateRoot, 'host.sqlite'),
    workspaces: [{ id: FIXTURE.workspaceId, name: FIXTURE.workspaceName, path: workspace }],
    tls: { certPath, keyPath }, relay: { url: outer.baseUrl, ...route },
  }, adapter, ...(!publicWss ? { allowInsecureRelayLoopback: true } : {}) });
  assert.ok(host.relay && typeof host.relay.waitPublished === 'function', 'Coordinated host relay build is required');
  fault = installLostResponseFault(host.server);
  await host.start();
  checkpoint = 'connector-initial-ready';
  const initialReadyDeadline = Date.now() + 30000;
  while (true) {
    const status = host.relay.status();
    if (status.connected && status.ready && status.lastAckAt !== null) break;
    assert.ok(Date.now() < initialReadyDeadline, 'Initial connector readiness deadline');
    await delay(25);
  }
  checkpoint = 'create-bootstrap-offer';
  const offer = host.state.createRemotePairing({ readWorkspaceIds: ['demo'], executeWorkspaceIds: ['demo'] }, route.routeId, Math.min(config.durationMs, 900000));
  bootstrapId = offer.relayAccess.accessId;
  checkpoint = 'bootstrap-publication';
  await host.relay.waitPublished(bootstrapId, 5000);
  assert.equal(host.state.relayPublication(bootstrapId)?.published, true);
  const invitation = { version: 2, baseUrl: `https://${authority}`, pinSha256, certificatePem,
    pairingToken: offer.pairingToken, expiresAt: offer.expiresAt,
    relay: { url: outer.baseUrl, routeId: route.routeId, accessId: bootstrapId, accessToken: offer.relayAccess.accessToken } };
  await writeFile(path.join(privateRoot, 'invitation.private.json'), JSON.stringify(invitation), { flag: 'wx', mode: 0o600 });
  await atomic('ready.json', { kind: receipt.kind, fixtureOnly: true, actualDsh: false, localDebugWs: !publicWss,
    invitationFile: path.join(privateRoot, 'invitation.private.json'), relayUrl: outer.baseUrl,
    innerAuthority: authority, outerPort: publicWss ? null : config.relayPort, innerPortNeverReverse: config.hostPort,
    publicWss, operatorRouteRevocationRequired: publicWss, relayMigrationObservation: publicWss ? 'Operator accepted-access-ID evidence required; local counters unavailable' : 'Local accepted upgrade counters',
    expiresAt: offer.expiresAt, closesAt: Date.now() + config.durationMs,
    ...FIXTURE, prompt: RELAY_PROMPT, lostResponsePrompt: LOST_RESPONSE_PROMPT });
  console.log(JSON.stringify({ kind: receipt.kind, readyFile: path.join(run, 'ready.json'), localDebugWs: !publicWss }));
  timer = setTimeout(() => done.resolve('lifetime-expired'), config.durationMs);
  let finished = false;
  checkpoint = 'fixture-control';
  while (!finished) {
    await atomic('status.json', summary());
    const commandPath = path.join(run, 'control.json');
    try {
      const file = await lstat(commandPath);
      assert.ok(file.isFile() && !file.isSymbolicLink() && file.size <= 1024);
      const command = JSON.parse(await readFile(commandPath, 'utf8'));
      await rm(owned(commandPath));
      assert.deepEqual(Object.keys(command), ['action']);
      if (command.action === 'arm-loss') fault.arm();
      else if (command.action === 'disconnect') {
        assert.equal(publicWss, false, 'Public disconnect needs reviewed operator or native control, not fictitious local sockets');
        for (const socket of outerSockets) socket.destroy();
      }
      else if (command.action === 'revoke-device') {
        const devices = host.state.listDevices().filter(device => device.revokedAt === null);
        assert.equal(devices.length, 1, 'Revoke only the sole isolated fixture device');
        host.state.revokeDevice(devices[0].deviceId);
        receipt.checks.push('fixture-device-revoked');
      } else if (command.action === 'finish') done.resolve('operator-finish');
      else throw new Error('Unsupported fixture control');
      await atomic('control-result.json', { action: command.action, applied: true });
    } catch (error) { if (error.code !== 'ENOENT') throw error; }
    finished = await Promise.race([done.promise.then(reason => { receipt.stopReason = reason; return true; }), delay(250).then(() => false)]);
  }
  receipt.evidence = summary();
} catch (error) {
  const status = host?.relay?.status();
  const publication = bootstrapId ? host?.state.relayPublication(bootstrapId) : undefined;
  receipt.failure = 'Fixture startup/control failed; details redacted. Verify coordinated seams and safe status.';
  receipt.failureDiagnostic = { checkpoint, failureClass: ['HostError', 'AssertionError', 'Error', 'TypeError', 'SyntaxError'].includes(error?.constructor?.name) ? error.constructor.name : 'other',
    hostErrorCode: ['unavailable', 'invalid_config', 'unauthorized', 'rate_limited'].includes(error?.code) ? error.code : 'not-allowlisted',
    elapsedMs: Date.now() - receipt.startedAt, connector: status ? { connected: status.connected, ready: status.ready, activeStreams: status.activeStreams, pendingStreams: status.pendingStreams, ackObserved: status.lastAckAt !== null } : 'not-started',
    bootstrapPublication: publication ? { published: publication.published, revoked: publication.revoked } : 'not-observed' };
  process.exitCode = 1;
} finally {
  clearTimeout(timer);
  process.off('SIGINT', onSignal); process.off('SIGTERM', onSignal);
  fault?.close();
  try { await host?.close(); receipt.cleanup.hostClosed = true; } catch { receipt.cleanup.hostClosed = false; process.exitCode = 1; }
  adapter?.dispose();
  try { await relay?.close(); receipt.cleanup.localRelayClosed = publicWss ? 'not-owned/not-started' : true; } catch { receipt.cleanup.localRelayClosed = false; process.exitCode = 1; }
  receipt.cleanup.relayStatsAfterClose = relay?.stats();
  for (const name of ['ready.json', 'control.json', 'control-result.json']) {
    await rm(owned(path.join(run, name)), { force: true });
  }
  relayState?.close();
  try { await rm(owned(privateRoot), { recursive: true, force: true }); receipt.cleanup.privateRuntimeRemoved = true; } catch { receipt.cleanup.privateRuntimeRemoved = false; process.exitCode = 1; }
  receipt.finishedAt = Date.now();
  // No PASS claim: the native operator must reconcile screenshots/UI assertions with this evidence.
  receipt.nativeAcceptancePassed = false;
  await atomic('receipt.json', receipt);
  console.log(JSON.stringify({ kind: receipt.kind, receiptPath: path.join(run, 'receipt.json'), cleanup: receipt.cleanup }));
}
