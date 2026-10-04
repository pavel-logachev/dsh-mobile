import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { randomUUID, createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { fixtureControl } from './relay-acceptance-control.mjs';
import { setTimeout as delay } from 'node:timers/promises';
import { innerRequest } from './relay-acceptance-client.mjs';
import { RELAY_PROMPT, LOST_RESPONSE_PROMPT } from './relay-acceptance-adapter.mjs';
import { migrationEvidence } from './relay-acceptance-evidence.mjs';
import { readInnerSnapshots } from './relay-acceptance-sse.mjs';

// Never print invitation/response auth fields or HTTP errors. Fixture-owned ready path is nonsecret.
let checkpoint = 'config';
let diagnostic = {};
try {
  const readyPath = path.resolve(process.argv[2] ?? '');
  const ready = JSON.parse(await readFile(readyPath, 'utf8'));
  assert.equal(ready.kind, 'native-opaque-relay-fixture');
  const publicWss = ready.publicWss === true;
  const run = path.dirname(readyPath);
  const invitation = JSON.parse(await readFile(ready.invitationFile, 'utf8'));
  checkpoint = 'pair';
  const paired = await innerRequest(invitation, { publicWss, method: 'POST', target: '/v1/pairings', body: { pairingToken: invitation.pairingToken, deviceName: 'Synthetic Node relay probe' } });
  assert.equal(paired.status, 201);
  checkpoint = 'migration';
  const { deviceToken: token, relayAccess: access } = paired.body;
  assert.ok(access && access.accessId !== invitation.relay.accessId);
  const request = input => innerRequest(invitation, { ...input, token, access, publicWss });
  assert.equal((await request({ target: '/v1/capabilities' })).status, 200);
  checkpoint = 'create';
  const createId = randomUUID();
  const created = await request({ method: 'POST', target: '/v1/sessions', body: { requestId: createId, workspaceId: 'demo' } });
  assert.equal(created.status, 201);
  const id = created.body.result.sessionId;
  checkpoint = 'live-sse-initial';
  const requestId = randomUUID();
  const events = await readInnerSnapshots(invitation, { token, access, publicWss, sessionId: id,
    onInitial: async first => {
      assert.equal(first.session.id, id); assert.equal(first.messages.length, 0);
      checkpoint = 'normal-prompt';
      const normal = await request({ method: 'POST', target: `/v1/sessions/${id}/messages`, body: { requestId, text: RELAY_PROMPT } });
      diagnostic = { httpStatus: normal.status }; assert.equal(normal.status, 200);
      checkpoint = 'live-sse-changed';
    },
    complete: (event, first) => event.cursor > first.cursor && event.activity === 'idle' &&
      event.messages.some(message => message.role === 'assistant' && message.text === 'Synthetic demo answer. No model was called.'),
  });
  diagnostic = { sseSnapshots: events.length }; assert.ok(events.length >= 2); assert.ok(events.every(event => event.session.id === id));
  const control = action => fixtureControl(run, action);
  checkpoint = 'normal-canonical-snapshot';
  let snapshot;
  const deadline = Date.now() + (publicWss ? 20000 : 5000);
  do {
    snapshot = await request({ target: `/v1/sessions/${id}` });
    if (snapshot.body.activity === 'idle') break;
    await delay(25);
  } while (Date.now() < deadline);
  const canonicalUserCount = snapshot.body.messages.filter(message => message.requestId === requestId && message.role === 'user' && message.text === RELAY_PROMPT).length;
  diagnostic = { httpStatus: snapshot.status, canonicalUserCount, idle: snapshot.body.activity === 'idle' };
  assert.equal(snapshot.status, 200); assert.equal(snapshot.body.activity, 'idle'); assert.equal(canonicalUserCount, 1);
  checkpoint = 'arm-loss';
  await control('arm-loss');
  checkpoint = 'lost-response';
  const lostId = randomUUID();
  await assert.rejects(request({ method: 'POST', target: `/v1/sessions/${id}/messages`, body: { requestId: lostId, text: LOST_RESPONSE_PROMPT } }));
  checkpoint = 'original-receipt-recovery';
  const recovered = await request({ target: `/v1/commands/${lostId}` });
  assert.equal(recovered.status, 200); assert.equal(recovered.body.status, 'accepted');
  const status = JSON.parse(await readFile(path.join(run, 'status.json'), 'utf8'));
  // Status file is periodic; wait for it to show both dispatches before independently asserting.
  const statusDeadline = Date.now() + 5000;
  let final = status;
  while (final.calls.prompt.length !== 2 && Date.now() < statusDeadline) {
    await delay(25); final = JSON.parse(await readFile(path.join(run, 'status.json'), 'utf8'));
  }
  checkpoint = 'one-dispatch-evidence';
  assert.equal(final.calls.prompt.filter(call => call.requestId === requestId).length, 1);
  assert.equal(final.calls.prompt.filter(call => call.requestId === lostId).length, 1);
  checkpoint = 'migration-evidence';
  const migration = migrationEvidence(final, { publicWss, authenticatedDeviceRequest: true });
  checkpoint = 'revoke';
  await control('revoke-device');
  let denied = false;
  try { denied = (await request({ target: '/v1/capabilities' })).status === 401; }
  catch { denied = true; }
  assert.equal(denied, true);
  checkpoint = 'persist-protocol-result';
  const sourceHashes = {};
  for (const name of ['relay-acceptance.mjs', 'relay-acceptance-probe.mjs', 'relay-acceptance-client.mjs', 'relay-acceptance-sse.mjs', 'relay-acceptance-control.mjs']) {
    sourceHashes[name] = createHash('sha256').update(await readFile(fileURLToPath(new URL(name, import.meta.url)))).digest('hex');
  }
  const protocolResult = { kind: 'isolated-node-relay-probe', native: false, fixtureOnly: true, protocolPassed: true,
    publicRelay: publicWss, outerTrust: publicWss ? 'ordinary-Node-CA/no-custom-outer-CA' : 'test-only-loopback-WS', migration, sourceHashes,
    counts: { create: final.calls.create.length, normalDispatches: final.calls.prompt.filter(call => call.requestId === requestId).length, lostDispatches: final.calls.prompt.filter(call => call.requestId === lostId).length, sseSnapshots: events.length },
    checks: ['live-SSE-initial-and-changed-canonical-snapshot', 'actual-opaque-inner-TLS', 'v2-pair', 'device-capability-migration', 'create', 'one-normal-dispatch', 'lost-response-original-receipt-one-dispatch', 'revoke-denies-stream'] };
  await writeFile(path.join(run, 'protocol-result.json'), JSON.stringify(protocolResult, null, 2), { flag: 'wx' });
  checkpoint = 'durable-finish';
  const finished = await control('finish');
  console.log(JSON.stringify({ ...protocolResult, passed: true, cleanup: finished.cleanup }));
} catch (error) {
  console.error(JSON.stringify({ kind: 'isolated-node-relay-probe-failure', checkpoint, failureClass: error?.constructor?.name ?? 'unknown', diagnostic }));
  process.exitCode = 1;
}
