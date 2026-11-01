import assert from 'node:assert/strict';
import { test } from 'node:test';
import { randomUUID } from 'node:crypto';
import { mkdtemp, mkdir, readdir, readFile, writeFile, utimes, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { HostError } from '../src/errors.ts';
import { HostState } from '../src/state.ts';
import { assertPrivate } from '../src/private-files.ts';
import { assertStagedFilePrivate } from './helpers/staged-file-acl.ts';
import { startHostServer } from '../src/server.ts';
import { request as httpRequest } from 'node:http';
import { UPLOAD_LIMIT } from '../src/phone.ts';
import type { HostAdapter, HostSnapshot } from '../src/types.ts';

async function setup(t: any, scopeGate?: () => Promise<void>, dispatchTimeoutMs?: number) {
  const root = await mkdtemp(join(tmpdir(), 'mobile-phone-'));
  await mkdir(join(root, 'workspace'));
  const calls: unknown[] = [];
  const snapshot: HostSnapshot = { session: { id: 'chat', title: 'Synthetic', workspaceId: 'w', running: true, updatedAt: 1 }, messages: [{ id: 'm', text: 'Synthetic '.repeat(300), role: 'assistant', createdAt: 1 }], cursor: 1, hasMore: false, activity: 'running' };
  const adapter: HostAdapter = { upstreamVersion: 'synthetic', async listPresets() { return []; }, async listSessions() { return [snapshot.session]; }, async snapshot() { return structuredClone(snapshot); }, async *watch() { yield snapshot; }, async createSession() { return { sessionId: 'chat' }; }, async prompt(...args) { calls.push(args); }, async cancel() {},
    phoneCapabilities: () => ({ steer: true, queue: true, rename: true, models: true, questions: false, attachments: false }),
    async mutateSession(...args) { calls.push(args); }, async models() { return { items: [{ provider: 'fixture', id: 'model', name: 'Synthetic model', supportsImages: true }] }; },
  };
  const host = await startHostServer({ adapter, ...(dispatchTimeoutMs ? { dispatchTimeoutMs } : {}), ...(scopeGate ? { workspaceSource: { kind: 'explicit' as const, async list() { await scopeGate(); return [{ id: 'w', name: 'Synthetic', path: join(root, 'workspace') }]; }, archivedSessionIds: () => new Set<string>() } } : {}), config: { bind: '127.0.0.1', port: 0, allowInsecureLoopback: true, hostName: 'Synthetic', statePath: join(root, 'state.sqlite'), workspaces: [{ id: 'w', name: 'Synthetic', path: join(root, 'workspace') }] } });
  t.after(async () => { await host.close(); await rm(root, { recursive: true, force: true }); });
  const { baseUrl } = await host.start();
  const pair = (execute = true) => host.state.consumePairing(host.state.createPairing({ readWorkspaceIds: ['w'], executeWorkspaceIds: execute ? ['w'] : [] }).pairingToken, 'Synthetic phone');
  const request = (path: string, token?: string, body?: unknown, headers = {}) => fetch(baseUrl + '/v1' + path, { method: body === undefined ? 'GET' : 'POST', headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  return { host, pair, request, calls, snapshot, adapter, baseUrl, root };
}

test('question commands authorize, validate against pending schema, replay and expire', async t => {
  const { adapter, snapshot, pair, request, calls } = await setup(t);
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: true, attachments: false });
  snapshot.pendingQuestions = [{ callId: 'call', state: 'continued', questions: [{ id: 'q', question: 'Synthetic choice', header: 'Synthetic', options: [{ label: 'Yes' }], multiSelect: false, allowFreeText: true }] }];
  const token = pair().deviceToken, readOnly = pair(false).deviceToken;
  const body = { requestId: randomUUID(), callId: 'call', answer: { answers: [{ id: 'q', selected: ['Yes'] }] } };
  assert.equal((await request('/sessions/chat/answers', undefined, body)).status, 401);
  assert.equal((await request('/sessions/chat/answers', readOnly, body)).status, 403);
  assert.equal((await request('/sessions/chat/answers', token, { ...body, answer: { answers: [{ id: 'q', selected: ['No'] }] } })).status, 400);
  assert.equal((await request('/sessions/chat/answers', token, body)).status, 200);
  snapshot.pendingQuestions = [];
  assert.equal((await request('/sessions/chat/answers', token, body)).status, 200); assert.equal(calls.length, 1);
  adapter.phoneCapabilities = () => ({ steer: false, queue: false, rename: false, models: false, questions: false, attachments: false });
  assert.equal((await request('/sessions/chat/answers', token, body)).status, 200, 'durable receipt replay does not require an available capability');
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: true, attachments: false });
  const expired = await request('/sessions/chat/answers', token, { ...body, requestId: randomUUID() });
  assert.equal(expired.status, 409); assert.equal((await expired.json()).error.code, 'question_expired');
});

test('skip is explicit, schema-safe, permissioned and replayable; resolved state survives GET/SSE', async t => {
  const { adapter, snapshot, pair, request, calls } = await setup(t);
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: true, questionSkip: true, attachments: false });
  snapshot.model = { provider: 'p', id: 'm', reasoningEffort: 'high' }; snapshot.session.model = snapshot.model;
  snapshot.pendingQuestions = [{ callId: 'call', state: 'continued', questions: [{ id: 'q', question: 'Pick', options: [{ label: 'Yes (Recommended)' }], multiSelect: false, allowFreeText: true }] }];
  const token = pair().deviceToken, read = pair(false).deviceToken, body = { requestId: randomUUID(), callId: 'call', skip: true };
  assert.equal((await request('/sessions/chat/answers', undefined, body)).status, 401);
  assert.equal((await request('/sessions/chat/answers', read, body)).status, 403);
  assert.equal((await request('/sessions/chat/answers', token, { ...body, answer: { answers: [] } })).status, 400);
  assert.equal((await request('/sessions/chat/answers', token, body)).status, 200);
  snapshot.pendingQuestions = []; snapshot.resolvedQuestions = [{ callId: 'call', outcome: 'skipped', answers: [{ id: 'q', selected: [] }] }];
  assert.equal((await request('/sessions/chat/answers', token, body)).status, 200); assert.equal(calls.length, 1);
  const reopened = await (await request('/sessions/chat', read)).json(); assert.deepEqual(reopened.model, snapshot.model); assert.equal(reopened.resolvedQuestions[0].outcome, 'skipped');
  const stream = await request('/sessions/chat/events', read); const reader = stream.body!.getReader(); try { assert.match(new TextDecoder().decode((await reader.read()).value), /resolvedQuestions/); } finally { await reader.cancel(); }
});

test('Unicode display filenames are bounded at code points without breaking GET or SSE', async t => {
  const { snapshot, pair, request } = await setup(t); const token = pair(false).deviceToken;
  snapshot.messages[0]!.attachments = [{ id: 'unicode', name: 'я'.repeat(130) + '.png', size: 3, mime: 'image/png', kind: 'image' }];
  const response = await request('/sessions/chat', token); assert.equal(response.status, 200);
  const name = (await response.json()).messages[0].attachments[0].name; assert.equal(name, 'я'.repeat(128)); assert.equal(Buffer.byteLength(name), 256);
  snapshot.messages[0]!.attachments![0]!.name = '😀'.repeat(70) + '.png';
  const stream = await request('/sessions/chat/events', token); assert.equal(stream.status, 200); const reader = stream.body!.getReader();
  try { const text = new TextDecoder().decode((await reader.read()).value); assert.match(text, /😀/); assert.doesNotMatch(text, /�/); } finally { await reader.cancel(); }
});

for (const route of ['files', 'attachments']) test(`${route} slots recover when client closes during final scope check`, async t => {
  let hold = false, entered!: () => void, release!: () => void;
  const { host, adapter, snapshot, pair, baseUrl, request } = await setup(t, async () => { if (hold) { hold = false; entered(); await new Promise<void>(r => { release = r; }); } });
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: false, attachments: true, fileDownload: true });
  let aborting = true;
  const read = async (_session: string, id: string) => { if (aborting) hold = true; return { data: Buffer.from('abc'), ref: { id, name: 'synthetic', size: 3, mime: route === 'files' ? 'text/plain' : 'image/png', kind: route === 'files' ? 'file' as const : 'image' as const } }; };
  snapshot.messages[0]!.attachments = [{ id: 'ref', name: 'synthetic', size: 3, mime: route === 'files' ? 'text/plain' : 'image/png', kind: route === 'files' ? 'file' : 'image' }];
  if (route === 'files') adapter.file = read; else adapter.image = read;
  const token = pair(false).deviceToken;
  for (let i = 0; i < 2; i++) {
    const entry = new Promise<void>(r => { entered = r; });
    const serverClosed = new Promise<void>(r => { host.server.once('request', (_req, res) => { res.once('close', () => r()); }); });
    const req = httpRequest(baseUrl + `/v1/sessions/chat/${route}/ref`, { headers: { Authorization: 'Bearer ' + token } }, res => res.resume()); req.on('error', () => {}); req.end();
    await entry; req.destroy(); await serverClosed; release(); await new Promise(r => setImmediate(r));
  }
  aborting = false;
  const response = await request(`/sessions/chat/${route}/ref`, token); assert.equal(response.status, 200); assert.equal(await response.text(), 'abc');
});

test('image reads are capped at two per device and four host-wide, then release slots', async t => {
  const { adapter, snapshot, pair, request } = await setup(t);
  snapshot.messages[0]!.attachments = [{ id: 'image', name: 'synthetic.png', size: 3, mime: 'image/png', kind: 'image' }];
  let active = 0, release!: () => void; const gate = new Promise<void>(r => { release = r; });
  let entered!: () => void; let nextEntry = new Promise<void>(r => { entered = r; });
  adapter.image = async (_s, id) => { active++; entered(); await gate; active--; return { data: Buffer.from('abc'), ref: { id, name: 'synthetic.png', size: 3, mime: 'image/png', kind: 'image' } }; };
  const first = pair(false).deviceToken, second = pair(false).deviceToken, third = pair(false).deviceToken;
  const pending: Promise<Response>[] = [];
  try {
    for (const token of [first, first]) { pending.push(request('/sessions/chat/attachments/image', token)); await nextEntry; nextEntry = new Promise<void>(r => { entered = r; }); }
    // Race a rejection against a third adapter entry: no timing-based sleeps.
    const rejected = request('/sessions/chat/attachments/image', first); const result = await Promise.race([rejected, nextEntry.then(() => null)]); assert.ok(result, 'third same-device request must not enter adapter'); assert.equal(result.status, 429);
    for (const token of [second, second]) { pending.push(request('/sessions/chat/attachments/image', token)); await nextEntry; nextEntry = new Promise<void>(r => { entered = r; }); }
    assert.equal(active, 4);
    const global = request('/sessions/chat/attachments/image', third); const globalResult = await Promise.race([global, nextEntry.then(() => null)]); assert.ok(globalResult, 'fifth host request must not enter adapter'); assert.equal(globalResult.status, 429);
  } finally { release(); await Promise.all(pending.map(async p => { await (await p).arrayBuffer(); })); }
  assert.equal((await request('/sessions/chat/attachments/image', first)).status, 200);
});

test('file download requires read authority, safe metadata and bounded bytes', async t => {
  const { adapter, snapshot, pair, request } = await setup(t);
  snapshot.messages[0]!.attachments = [{ id: 'file', name: 'synthetic.txt', size: 3, mime: 'text/plain', kind: 'file' }];
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: false, attachments: true, fileDownload: true });
  let name = 'пример.txt', size = 3;
  adapter.file = async (_id, id) => { if (id !== 'file') throw Object.assign(new Error('Synthetic missing'), { code: 'not_found' }); return { data: Buffer.from('abc'), ref: { id, name, size, mime: 'text/plain', kind: 'file' } }; };
  const read = pair(false).deviceToken;
  assert.equal((await request('/sessions/chat/files/file')).status, 401);
  const response = await request('/sessions/chat/files/file', read); assert.equal(response.status, 200); assert.equal(response.headers.get('content-type'), 'application/octet-stream'); assert.match(response.headers.get('content-disposition')!, /^attachment;.*filename\*=UTF-8''%/); assert.equal(await response.text(), 'abc');
  name = '../secret'; assert.equal((await request('/sessions/chat/files/file', read)).status, 500);
  name = 'safe'; size = UPLOAD_LIMIT + 1; assert.equal((await request('/sessions/chat/files/file', read)).status, 413);
});

test('image bytes remain reserved while an admitted prompt holds its attachments', async t => {
  const { adapter, pair, baseUrl, request } = await setup(t);
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: false, attachments: true });
  adapter.upload = async (_id, input) => { for await (const _chunk of input.data) {} return { ref: { id: 'image', name: input.name, size: input.size, mime: input.mime, kind: 'image' }, upstream: { type: 'image', mediaType: 'image/png', data: '' } }; };
  let release!: () => void; const gate = new Promise<void>(r => { release = r; }); adapter.prompt = async () => { await gate; };
  const token = pair().deviceToken, ids: string[] = [];
  for (let i = 0; i < 10; i++) { const r = await fetch(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'image/png' }, body: Buffer.alloc(UPLOAD_LIMIT) }); ids.push((await r.json()).uploadId); }
  try {
    assert.equal((await request('/sessions/chat/messages', token, { requestId: randomUUID(), text: '', uploadIds: ids })).status, 202);
    const status = await new Promise<number>((resolve, reject) => { const req = httpRequest(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'image/png', 'Content-Length': String(UPLOAD_LIMIT) } }, res => { res.resume(); resolve(res.statusCode!); }); req.on('error', reject); req.end(); }); assert.equal(status, 429);
  } finally { release(); }
});

test('concurrent exact attachment replays return a receipt, not claimed-upload conflict', async t => {
  const { adapter, pair, baseUrl, request } = await setup(t);
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: false, attachments: true });
  let prompts = 0; adapter.prompt = async () => { prompts++; };
  adapter.upload = async () => { throw new Error('pick stays local'); };
  const token = pair().deviceToken;
  const picked = await fetch(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'text/plain' }, body: 'a' }); assert.equal(picked.status, 201); const { uploadId } = await picked.json();
  let releaseUpload!: () => void; const uploadGate = new Promise<void>(r => { releaseUpload = r; });
  t.after(() => releaseUpload());
  adapter.upload = async (_id, input) => { await uploadGate; for await (const _chunk of input.data) {} return { ref: { id: 'f', name: input.name, size: 1, mime: input.mime, kind: 'file' }, upstream: { type: 'file', receiptId: 'receipt' } }; };
  const snapshot = adapter.snapshot; let held = 0, release!: () => void; const gate = new Promise<void>(r => { release = r; });
  adapter.snapshot = async (...args) => { if (++held <= 2) { if (held === 2) release(); await gate; } return snapshot(...args); };
  const body = { requestId: randomUUID(), text: 'Synthetic', uploadIds: [uploadId] };
  try {
    const results = await Promise.all([request('/sessions/chat/messages', token, body), request('/sessions/chat/messages', token, body)]);
    assert.ok(results.every(r => r.status === 202), results.map(r => r.status).join(','));
    const receipts = await Promise.all(results.map(r => r.json()));
    assert.deepEqual(receipts[0], receipts[1]); assert.equal(receipts[0].status, 'pending');
    assert.equal(prompts, 0, 'pending replay must not wait for the upstream upload');
  } finally { releaseUpload(); }
  // Admission may return a pending receipt before disk I/O reaches prompt.
  // Observe settlement through exact HTTP replay, not the 25 ms response window.
  const deadline = Date.now() + 5000;
  let settled: Response;
  do { assert.ok(Date.now() < deadline, 'attachment command must settle'); settled = await request('/sessions/chat/messages', token, body); } while ((await settled.json()).status === 'pending');
  assert.equal(settled.status, 200); assert.equal(prompts, 1);
});

test('external HostState revocation cleans private staging on the next access', async t => {
  const { adapter, pair, baseUrl, request, root, host } = await setup(t);
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: false, attachments: true }); adapter.upload = async () => { throw new Error('local pick only'); };
  const device = pair();
  const response = await fetch(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + device.deviceToken, 'Content-Type': 'text/plain' }, body: 'synthetic' }); assert.equal(response.status, 201);
  const admin = new HostState(host.config.statePath); try { admin.revokeDevice(device.deviceId); } finally { admin.close(); }
  assert.equal((await request('/sessions/chat', device.deviceToken)).status, 401);
  assert.deepEqual(await readdir(join(root, 'mobile-uploads')), []);
});

test('periodic sweep removes staging after an independent admin revokes the device', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const { adapter, pair, baseUrl, root, host } = await setup(t);
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: false, attachments: true }); adapter.upload = async () => { throw new Error('local pick only'); };
  const device = pair();
  const response = await fetch(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + device.deviceToken, 'Content-Type': 'text/plain' }, body: 'synthetic' }); assert.equal(response.status, 201);
  const admin = new HostState(host.config.statePath); try { admin.revokeDevice(device.deviceId); } finally { admin.close(); }
  t.mock.timers.tick(30_000);
  const directory = join(root, 'mobile-uploads'), deadline = Date.now() + 5000;
  while ((await readdir(directory)).length) { assert.ok(Date.now() < deadline); await new Promise(r => setImmediate(r)); }
});

test('send-time file uploads queue under the shared two-device four-host limiter', async t => {
  const { adapter, pair, baseUrl, request, host } = await setup(t);
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: false, attachments: true });
  let release!: () => void, active = 0, peak = 0, calls = 0; const gate = new Promise<void>(r => { release = r; });
  adapter.upload = async (_id, input) => { calls++; active++; peak = Math.max(peak, active); await gate; for await (const _chunk of input.data) {} active--; return { ref: { id: 'f', name: input.name, size: 1, mime: input.mime, kind: 'file' }, upstream: { type: 'file', receiptId: 'receipt' } }; };
  const devices = [pair(), pair(), pair()], commands: { deviceId: string; requestId: string }[] = [];
  const picks: { device: typeof devices[number]; uploadId: string }[] = [];
  for (const device of devices) for (let i = 0; i < 3; i++) {
    const pick = await fetch(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + device.deviceToken, 'Content-Type': 'text/plain' }, body: 'a' }); picks.push({ device, uploadId: (await pick.json()).uploadId });
  }
  try {
    for (const { device, uploadId } of picks) {
      const requestId = randomUUID(); commands.push({ deviceId: device.deviceId, requestId });
      assert.equal((await request('/sessions/chat/messages', device.deviceToken, { requestId, text: 'Synthetic', uploadIds: [uploadId] })).status, 202);
      if (devices[0] === device) assert.ok(calls <= 2);
    }
    assert.equal(active, 4); assert.equal(peak, 4);
  } finally { release(); }
  const deadline = Date.now() + 5000;
  while (commands.some(c => host.state.getCommand(c.deviceId, c.requestId)?.receipt.status === 'pending')) { assert.ok(Date.now() < deadline); await new Promise(r => setImmediate(r)); }
  assert.equal(calls, 9); assert.ok(peak <= 4);
});

test('same mobile UUID on two devices maps to distinct upstream UUIDs and stable replay', async t => {
  const { pair, request, calls } = await setup(t); const a = pair().deviceToken, b = pair().deviceToken, requestId = randomUUID();
  const body = { requestId, text: 'Synthetic' };
  assert.equal((await request('/sessions/chat/messages', a, body)).status, 200);
  assert.equal((await request('/sessions/chat/messages', b, body)).status, 200);
  assert.equal((await request('/sessions/chat/messages', a, body)).status, 200);
  const sent = calls as any[]; assert.equal(sent.length, 2); assert.notEqual(sent[0][2], sent[1][2]);
  for (const call of sent) assert.match(call[2], /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});

for (const kind of ['image', 'file'] as const) test(`wire-trimmed ${kind} is not downloadable even when upstream accepts its reference`, async t => {
  const { adapter, snapshot, pair, request } = await setup(t); let reads = 0;
  const ref = { id: 'omitted', name: 'synthetic', size: 3, mime: kind === 'image' ? 'image/png' : 'text/plain', kind };
  snapshot.messages = [{ id: 'old-image', role: 'user', text: '', createdAt: 1, attachments: [ref] }, ...[0, 1, 2].map(i => ({ id: 'large-' + i, role: 'user' as const, text: 'x'.repeat(800000), createdAt: 2 + i }))];
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: false, attachments: true, fileDownload: true });
  adapter.image = adapter.file = async () => { reads++; return { data: Buffer.from('abc'), ref }; };
  const token = pair(false).deviceToken, response = await request('/sessions/chat', token);
  assert.equal((await response.json()).messages.some((m: any) => m.id === 'old-image'), false);
  assert.equal((await request(`/sessions/chat/${kind === 'image' ? 'attachments' : 'files'}/omitted`, token)).status, 404); assert.equal(reads, 0);
});

test('attachment-holding dispatch admission is bounded and releases capacity on settlement', async t => {
  const { adapter, pair, baseUrl, request, host } = await setup(t);
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: false, attachments: true });
  adapter.upload = async (_id, input) => { for await (const _chunk of input.data) {} return { ref: { id: 'image', name: input.name, size: 1, mime: input.mime, kind: 'image' }, upstream: { type: 'image', mediaType: 'image/png', data: '' } }; };
  let release!: () => void; const gate = new Promise<void>(r => { release = r; }); adapter.prompt = async () => { await gate; };
  const devices = [pair(), pair()], commands: { deviceId: string; requestId: string }[] = [];
  const pick = async (token: string) => { const r = await fetch(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'image/png' }, body: 'a' }); assert.equal(r.status, 201); return (await r.json()).uploadId; };
  const ids: { token: string; deviceId: string; uploadId: string }[] = [];
  for (const device of devices) for (let i = 0; i < 9; i++) ids.push({ token: device.deviceToken, deviceId: device.deviceId, uploadId: await pick(device.deviceToken) });
  try {
    for (const item of ids.slice(0, 16)) { const requestId = randomUUID(); commands.push({ deviceId: item.deviceId, requestId }); assert.equal((await request('/sessions/chat/messages', item.token, { requestId, text: 'Synthetic', uploadIds: [item.uploadId] })).status, 202); }
    const extra = ids[16]!; assert.equal((await request('/sessions/chat/messages', extra.token, { requestId: randomUUID(), text: 'Synthetic', uploadIds: [extra.uploadId] })).status, 429);
  } finally { release(); }
  const deadline = Date.now() + 5000;
  while (commands.some(c => host.state.getCommand(c.deviceId, c.requestId)?.receipt.status === 'pending')) { assert.ok(Date.now() < deadline); await new Promise(r => setImmediate(r)); }
  const extra = ids[16]!; assert.equal((await request('/sessions/chat/messages', extra.token, { requestId: randomUUID(), text: 'Synthetic', uploadIds: [extra.uploadId] })).status, 200);
});

test('GET and SSE restore own mobile IDs for Android queue and canonical reconciliation, not other devices', async t => {
  const { adapter, snapshot, pair, request, host } = await setup(t);
  const a = pair(), b = pair(), mobile = randomUUID(), legacy = randomUUID();
  adapter.prompt = async (_id, text, upstream) => { snapshot.pendingQueue = [{ id: 'queued', text, requestId: upstream, delivery: 'queue' }]; snapshot.messages.push({ id: 'canonical', text, role: 'user', createdAt: 2, requestId: upstream }); };
  const body = { requestId: mobile, text: 'Synthetic' }; assert.equal((await request('/sessions/chat/messages', a.deviceToken, body)).status, 200);
  const upstream = snapshot.pendingQueue![0]!.requestId; assert.notEqual(upstream, mobile);
  snapshot.messages.push({ id: 'legacy', role: 'user', text: 'Legacy', createdAt: 1, requestId: legacy });
  const own = await (await request('/sessions/chat', a.deviceToken)).json();
  assert.ok(own.messages.some((m: any) => m.role === 'user' && !m.provisional && m.requestId === mobile)); assert.ok(own.pendingQueue.some((q: any) => q.requestId === mobile)); assert.equal(own.messages.at(-1).requestId, legacy);
  const foreign = await (await request('/sessions/chat', b.deviceToken)).json(); assert.equal(foreign.pendingQueue[0].requestId, upstream); assert.ok(!foreign.messages.some((m: any) => m.requestId === mobile));
  const stream = await request('/sessions/chat/events', a.deviceToken), reader = stream.body!.getReader(); try { const data = new TextDecoder().decode((await reader.read()).value); assert.ok(data.includes(mobile)); assert.ok(!data.includes(upstream!)); } finally { await reader.cancel(); }
  await host.close(); const restarted = await startHostServer({ adapter, config: host.config }); try { const { baseUrl } = await restarted.start(); const value = await (await fetch(baseUrl + '/v1/sessions/chat', { headers: { Authorization: 'Bearer ' + a.deviceToken } })).json(); assert.equal(value.pendingQueue[0].requestId, mobile); } finally { await restarted.close(); }
});

test('receipt quota rejection before admission preserves upload token and permits a fresh retry', async t => {
  const { adapter, pair, request, baseUrl, host } = await setup(t); let blocked = true, uploads = 0, releases = 0;
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: false, attachments: true });
  adapter.reserveFileUploads = async () => { if (blocked) throw new HostError('rate_limited'); return { release() { releases++; } }; };
  adapter.upload = async (_id, input) => { uploads++; for await (const _chunk of input.data) {} assert.ok(input.reservation); return { ref: { id: 'file', name: input.name, size: input.size, mime: input.mime, kind: 'file' }, upstream: { type: 'file', receiptId: 'receipt' } }; };
  const device = pair(); const pick = await fetch(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + device.deviceToken, 'Content-Type': 'text/plain' }, body: 'synthetic' }); const { uploadId } = await pick.json();
  const body = { requestId: randomUUID(), text: 'Synthetic', uploadIds: [uploadId] };
  assert.equal((await request('/sessions/chat/messages', device.deviceToken, body)).status, 429); assert.equal(host.state.getCommand(device.deviceId, body.requestId), undefined); assert.equal(uploads, 0);
  blocked = false; assert.equal((await request('/sessions/chat/messages', device.deviceToken, body)).status, 200); assert.equal(uploads, 1); assert.equal(releases, 1);
});

test('file pick stays private on disk until idempotent send and removal leaves no upstream receipt', async t => {
  const { adapter, pair, baseUrl, request, root } = await setup(t);
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: false, attachments: true });
  let upstream = 0, prompts = 0;
  adapter.upload = async (_id, input) => { upstream++; const chunks = []; for await (const chunk of input.data) chunks.push(chunk); assert.equal(Buffer.concat(chunks).toString(), 'synthetic'); return { ref: { id: 'durable', name: input.name, size: input.size, mime: input.mime, kind: 'file' }, upstream: { type: 'file', receiptId: 'receipt' } }; };
  adapter.prompt = async (_id, _text, _requestId, _signal, _workspace, options) => { prompts++; assert.deepEqual(options!.attachments![0]!.upstream, { type: 'file', receiptId: 'receipt' }); };
  const token = pair().deviceToken;
  const pick = async () => { const response = await fetch(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'text/plain', 'X-File-Name': 'original.txt' }, body: 'synthetic' }); assert.equal(response.status, 201); return response.json(); };
  const first = await pick(), directory = join(root, 'mobile-uploads');
  assert.equal(upstream, 0); const names = await readdir(directory); assert.equal(names.length, 1); assert.match(names[0]!, /^[a-f0-9]{48}\.tmp$/); assert.equal(await readFile(join(directory, names[0]!), 'utf8'), 'synthetic');
  await assertPrivate(directory); await assertStagedFilePrivate(join(directory, names[0]!));
  const removed = await fetch(baseUrl + '/v1/sessions/chat/uploads/' + first.uploadId, { method: 'DELETE', headers: { Authorization: 'Bearer ' + token } }); assert.equal(removed.status, 204); assert.deepEqual(await readdir(directory), []); assert.equal(upstream, 0);
  const second = await pick(), body = { requestId: randomUUID(), text: 'Synthetic', uploadIds: [second.uploadId] };
  assert.equal((await request('/sessions/chat/messages', token, body)).status, 200);
  assert.equal((await request('/sessions/chat/messages', token, body)).status, 200);
  assert.equal(upstream, 1); assert.equal(prompts, 1); assert.deepEqual(await readdir(directory), []);
});

test('failed send cleans private file and exact replay never uploads upstream twice', async t => {
  const { adapter, pair, baseUrl, request, root } = await setup(t);
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: false, attachments: true });
  let uploaded = 0;
  adapter.upload = async (_id, input) => { uploaded++; for await (const _chunk of input.data) {} return { ref: { id: 'file', name: input.name, size: input.size, mime: input.mime, kind: 'file' }, upstream: { type: 'file', receiptId: 'orphan' } }; };
  adapter.prompt = async () => { throw new Error('Synthetic ambiguous prompt'); };
  const token = pair().deviceToken;
  const pick = await fetch(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'text/plain' }, body: 'synthetic' }); const { uploadId } = await pick.json();
  const body = { requestId: randomUUID(), text: 'Synthetic', uploadIds: [uploadId] };
  const response = await request('/sessions/chat/messages', token, body); assert.equal(response.status, 202);
  assert.equal((await response.json()).status, 'uncertain');
  assert.equal((await request('/sessions/chat/messages', token, body)).status, 202); assert.equal(uploaded, 1);
  assert.deepEqual(await readdir(join(root, 'mobile-uploads')), []);
});

test('file staging cleans TTL, revoke, close and stale startup files', async t => {
  const { adapter, pair, baseUrl, request, root, host } = await setup(t);
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: false, attachments: true });
  adapter.upload = async () => { throw new Error('pick must not create an upstream receipt'); };
  const token = pair().deviceToken, directory = join(root, 'mobile-uploads');
  const pick = () => fetch(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'text/plain' }, body: 'synthetic' });
  assert.equal((await pick()).status, 201);
  const clock = Date.now; let response: Response;
  try { Date.now = () => clock() + 11 * 60_000; response = await pick(); } finally { Date.now = clock; }
  assert.equal(response.status, 201); assert.equal((await readdir(directory)).length, 1);
  assert.equal((await fetch(baseUrl + '/v1/device', { method: 'DELETE', headers: { Authorization: 'Bearer ' + token } })).status, 204);
  await host.close(); assert.deepEqual(await readdir(directory), []);
  // Startup removes orphans older than the 24-hour reload grace;
  // opaque mobile IDs are not restored after restart.
  const stale = join(directory, 'a'.repeat(48) + '.tmp'); await writeFile(stale, 'stale', { mode: 0o600 });
  const old = new Date(Date.now() - 25 * 60 * 60_000); await utimes(stale, old, old);
  const restarted = await startHostServer({ adapter, config: host.config }); try { assert.deepEqual(await readdir(directory), []); } finally { await restarted.close(); }
});

test('uploads admit two per device and four host-wide with independent device counts', async t => {
  const { adapter, pair, baseUrl } = await setup(t);
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: false, attachments: true });
  let entered!: () => void; let nextEntry = new Promise<void>(r => { entered = r; });
  adapter.upload = async (_id, input) => { entered(); for await (const _chunk of input.data) {} return { ref: { id: 'image', name: 'synthetic', size: 2, mime: 'image/png', kind: 'image' }, upstream: { type: 'image', mediaType: 'image/png', data: '' } }; };
  const devices = [pair().deviceToken, pair().deviceToken, pair().deviceToken];
  const open: { req: ReturnType<typeof httpRequest>; result: Promise<number> }[] = [];
  const start = (token: string) => { let req!: ReturnType<typeof httpRequest>; const result = new Promise<number>((resolve, reject) => { req = httpRequest(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'image/png', 'Content-Length': '2' } }, res => { res.resume(); resolve(res.statusCode!); }); req.on('error', reject); req.write('a'); }); return { req, result }; };
  try {
    for (const token of [devices[0]!, devices[0]!, devices[1]!, devices[1]!]) { const pending = start(token); open.push(pending); const result = await Promise.race([nextEntry.then(() => 'entered'), pending.result]); assert.equal(result, 'entered'); nextEntry = new Promise<void>(r => { entered = r; }); }
    for (const token of [devices[0]!, devices[2]!]) { const pending = start(token); const status = await pending.result; pending.req.end('b'); assert.equal(status, 429); }
  } finally { for (const pending of open) pending.req.end('b'); await Promise.all(open.map(p => p.result)); }
});

test('host image staging reserves concurrent bytes while file receipt staging retains no file bytes', async t => {
  const { adapter, pair, baseUrl } = await setup(t);
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: false, attachments: true });
  adapter.upload = async (_id, input) => { for await (const _chunk of input.data) {} return { ref: { id: 'synthetic', name: input.name, size: input.size, mime: input.mime, kind: input.mime.startsWith('image/') ? 'image' : 'file' }, upstream: input.mime.startsWith('image/') ? { type: 'image', mediaType: 'image/png', data: '' } : { type: 'file', receiptId: 'receipt' } }; };
  const token = pair().deviceToken;
  const upload = (mime: string) => fetch(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': mime, 'X-File-Name': 'synthetic' }, body: Buffer.alloc(UPLOAD_LIMIT) });
  for (let i = 0; i < 9; i++) assert.equal((await upload('image/png')).status, 201);
  let pendingRequest!: ReturnType<typeof httpRequest>;
  // Hold the tenth image in flight after its first byte; the eleventh would
  // exceed 200 MiB even though the tenth has no staged receipt yet.
  let entered!: () => void; const entry = new Promise<void>(r => { entered = r; }); const original = adapter.upload;
  adapter.upload = async (...args) => { entered(); return original!(...args); };
  const status = new Promise<number>((resolve, reject) => { const req = httpRequest(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'image/png', 'Content-Length': String(UPLOAD_LIMIT) } }, res => { res.resume(); resolve(res.statusCode!); }); req.on('error', reject); pendingRequest = req; req.write(Buffer.alloc(1)); });
  await entry;
  try {
    const rejected = await new Promise<number>((resolve, reject) => { const req = httpRequest(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'image/png', 'Content-Length': String(UPLOAD_LIMIT) } }, res => { res.resume(); resolve(res.statusCode!); }); req.on('error', reject); req.end(); });
    assert.equal(rejected, 429);
  } finally { pendingRequest.end(Buffer.alloc(UPLOAD_LIMIT - 1)); }
  assert.equal(await status, 201);
  const fileToken = pair().deviceToken;
  const file = await fetch(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + fileToken, 'Content-Type': 'application/octet-stream' }, body: Buffer.alloc(UPLOAD_LIMIT) });
  assert.equal(file.status, 201, 'disk-backed files do not consume the image RAM budget');
});

test('attachment intake gates auth/scope, type, size, names, stages per device and downloads images', async t => {
  const { adapter, snapshot, pair, baseUrl, request, calls } = await setup(t);
  adapter.phoneCapabilities = () => ({ steer: true, queue: true, rename: true, models: true, questions: false, attachments: true });
  let stores = 0;
  adapter.upload = async (_id, input) => { let size = 0; for await (const chunk of input.data) size += chunk.length; assert.equal(size, input.size); stores++; return { ref: { id: 'image-fixture', name: input.name, size, mime: input.mime, kind: 'image' }, upstream: { type: 'image', data: 'synthetic' } }; };
  adapter.image = async (_id, id) => { assert.equal(id, 'image-fixture'); return { data: Buffer.from('synthetic'), ref: { id, name: 'fixture.png', size: 9, mime: 'image/png', kind: 'image' } }; };
  const token = pair().deviceToken, readOnly = pair(false).deviceToken, other = pair().deviceToken;
  const upload = (token?: string, headers = {}, data = 'synthetic') => fetch(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), 'Content-Type': 'image/png', 'X-File-Name': 'fixture.png', ...headers }, body: data });
  assert.equal((await upload()).status, 401); assert.equal((await upload(readOnly)).status, 403);
  assert.equal((await upload(token, { 'Content-Type': 'image/svg+xml' })).status, 415);
  assert.equal((await upload(token, { 'X-File-Name': '..%2Fsecret.png' })).status, 400);
  const tooLarge = await new Promise<number>((resolve, reject) => { const req = httpRequest(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'image/png', 'Content-Length': String(UPLOAD_LIMIT + 1) } }, res => { res.resume(); resolve(res.statusCode!); }); req.on('error', reject); req.end(); });
  assert.equal(tooLarge, 413); assert.equal(stores, 0);
  const uploaded = await upload(token); assert.equal(uploaded.status, 201); const receipt = await uploaded.json(); assert.equal(stores, 1);
  const body = { requestId: randomUUID(), text: '', uploadIds: [receipt.uploadId] };
  assert.equal((await request('/sessions/chat/messages', other, body)).status, 404);
  assert.equal((await request('/sessions/chat/messages', readOnly, body)).status, 403);
  assert.equal((await request('/sessions/chat/messages', token, body)).status, 200);
  assert.equal((await request('/sessions/chat/messages', token, body)).status, 200); assert.equal(calls.length, 1);
  assert.equal((await request('/sessions/chat/messages', token, { ...body, requestId: randomUUID(), uploadIds: Array.from({ length: 21 }, (_, n) => 'upload-' + n) })).status, 400);
  assert.equal((await request('/sessions/chat/attachments/image-fixture')).status, 401);
  snapshot.messages[0]!.attachments = [{ id: 'image-fixture', name: 'fixture.png', size: 9, mime: 'image/png', kind: 'image' }];
  const image = await request('/sessions/chat/attachments/image-fixture', readOnly); assert.equal(image.status, 200); assert.equal(image.headers.get('content-type'), 'image/png'); assert.equal(await image.text(), 'synthetic');
});

for (const [path, payload] of [
  ['messages', { text: 'Synthetic steering', delivery: 'steer' }],
  ['queue', { itemId: 'queued', action: 'steer' }],
  ['queue', { itemId: 'queued', action: 'remove' }],
  ['title', { title: 'New synthetic title' }],
  ['model', { provider: 'fixture', model: 'model' }],
] as const) test(`phone ${path} command authenticates, gates writes, validates and replays receipts`, async t => {
  const { request, pair, calls } = await setup(t);
  const token = pair().deviceToken, readOnly = pair(false).deviceToken;
  const body = { requestId: randomUUID(), ...payload };
  assert.equal((await request('/sessions/chat/' + path, undefined, body)).status, 401);
  assert.equal((await request('/sessions/chat/' + path, readOnly, body)).status, 403);
  assert.equal((await request('/sessions/chat/' + path, token, { ...body, unexpected: true })).status, 400);
  assert.equal((await request('/sessions/chat/' + path, token, body)).status, 200);
  assert.equal((await request('/sessions/chat/' + path, token, body)).status, 200);
  assert.equal(calls.length, 1);
  assert.equal((await request('/sessions/chat/' + path, token, { ...body, ...('text' in payload ? { text: 'Different' } : 'title' in payload ? { title: 'Different' } : 'model' in payload ? { model: 'different' } : { itemId: 'different' }) })).status, 409);
});

test('phone snapshot GET/SSE projects typed extensions without arbitrary adapter fields', async t => {
  const { request, pair, snapshot } = await setup(t), token = pair(false).deviceToken;
  snapshot.pendingQueue = [{ id: 'pending', text: 'Synthetic queue', delivery: 'queue', private: 'hidden' } as any];
  snapshot.pendingQuestions = [{ callId: 'call', state: 'continued', questions: [{ id: 'q', question: 'Synthetic?', options: [], multiSelect: false, allowFreeText: true, private: 'hidden' }], private: 'hidden' } as any];
  snapshot.messages[0]!.attachments = [{ id: 'image', name: 'synthetic.png', size: 3, mime: 'image/png', kind: 'image', private: 'hidden' } as any];
  const result = await (await request('/sessions/chat', token)).json(); assert.equal(result.pendingQueue[0].id, 'pending'); assert.equal(result.pendingQuestions[0].questions[0].allowFreeText, true); assert.doesNotMatch(JSON.stringify(result), /private|hidden/);
  const stream = await request('/sessions/chat/events', token, undefined, { 'Accept-Encoding': 'gzip' }); assert.equal(stream.headers.get('content-encoding'), null);
  const reader = stream.body!.getReader(); try { const frame = new TextDecoder().decode((await reader.read()).value); assert.match(frame, /pendingQuestions/); assert.doesNotMatch(frame, /private|hidden/); } finally { await reader.cancel(); }
  snapshot.messages[0]!.attachments![0]!.name = '../unsafe.png'; assert.equal((await request('/sessions/chat', token)).status, 500);
});

test('phone capabilities, model catalog and gzip JSON are additive and bounded', async t => {
  const { request, pair, host } = await setup(t), token = pair().deviceToken;
  const caps = await (await request('/capabilities', token)).json();
  assert.equal(caps.capabilities.steer, true); assert.equal(caps.capabilities.approvals, false);
  assert.equal(caps.storage.pendingCleanups, 0); assert.equal(caps.storage.cleanupFailures, 0);
  assert.equal((await (await request('/models', token)).json()).items[0].supportsImages, true);
  assert.equal((await request('/sessions/chat', token, undefined, { 'Accept-Encoding': 'gzip' })).headers.get('content-encoding'), 'gzip');
  assert.equal((await request('/sessions/chat', token, undefined, { 'Accept-Encoding': 'gzip;q=0, identity' })).headers.get('content-encoding'), null);
  assert.equal((await request('/presets', token, undefined, { 'Accept-Encoding': 'gzip' })).headers.get('content-encoding'), null);
  assert.equal((await request('/sessions/chat', token, undefined, { 'Accept-Encoding': 'GZip; q=0.5' })).headers.get('content-encoding'), 'gzip');
  assert.equal((await request('/sessions/chat', token, undefined, { 'Accept-Encoding': 'gzip, gzip;q=0' })).headers.get('content-encoding'), null);
  assert.equal(host.server.keepAliveTimeout, 60_000);
});

test('H03 deadline settles receipt but holds upload bytes and reservation until real settlement', { timeout: 5000 }, async t => {
  const { host, adapter, pair, baseUrl, request, root } = await setup(t, undefined, 60);
  const gate = Promise.withResolvers<void>(), entered = Promise.withResolvers<void>(); let released = 0, prompts = 0;
  adapter.phoneCapabilities = () => ({ steer: false, queue: false, rename: false, models: false, questions: false, attachments: true });
  adapter.reserveFileUploads = async () => ({ release() { released++; } });
  adapter.upload = async (_id, input) => { entered.resolve(); await gate.promise; return { ref: { id: 'f', name: input.name, size: input.size, mime: input.mime, kind: 'file' }, upstream: { type: 'file', receiptId: 'r' } }; };
  adapter.prompt = async () => { prompts++; };
  const device = pair(), token = device.deviceToken;
  const picked = await fetch(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'text/plain' }, body: 'synthetic bytes' });
  const uploadId = (await picked.json()).uploadId, requestId = randomUUID();
  const sending = request('/sessions/chat/messages', token, { requestId, text: 'Synthetic', uploadIds: [uploadId] });
  try {
    await entered.promise; await new Promise(r => setTimeout(r, 100));
    assert.equal(host.state.getCommand(device.deviceId, requestId)?.receipt.status, 'uncertain');
    assert.equal(released, 0); const paths = await readdir(join(root, 'mobile-uploads')); assert.equal(paths.length, 1);
    assert.equal((await readFile(join(root, 'mobile-uploads', paths[0]!))).toString(), 'synthetic bytes');
  } finally { gate.resolve(); await sending; }
  const until = Date.now() + 2000;
  while ((await readdir(join(root, 'mobile-uploads'))).length) { assert.ok(Date.now() < until); await new Promise(r => setImmediate(r)); }
  assert.equal(released, 1); assert.equal(prompts, 0); assert.equal(host.state.getCommand(device.deviceId, requestId)?.receipt.status, 'uncertain');
});

test('H03 held post-admission workspace lookup cannot dispatch after deadline', { timeout: 5000 }, async t => {
  const gate = Promise.withResolvers<void>(), entered = Promise.withResolvers<void>(); let admitted = false, creates = 0;
  const { host, adapter, pair, request } = await setup(t, async () => { if (admitted) { entered.resolve(); await gate.promise; } }, 30);
  const admit = host.state.admitCommand.bind(host.state);
  host.state.admitCommand = (...args) => { const result = admit(...args); admitted = result.fresh; return result; };
  adapter.createSession = async () => { creates++; return { sessionId: 'late' }; };
  const device = pair(), requestId = randomUUID();
  const sending = request('/sessions', device.deviceToken, { requestId, workspaceId: 'w' });
  try { await entered.promise; await new Promise(r => setTimeout(r, 70)); assert.equal(host.state.getCommand(device.deviceId, requestId)?.receipt.status, 'uncertain'); }
  finally { admitted = false; gate.resolve(); await sending; }
  await new Promise(r => setImmediate(r)); assert.equal(creates, 0);
});

test('H03 timed-out queued uploads keep claims until dequeue and never start late side effects', { timeout: 5000 }, async t => {
  const { host, adapter, pair, baseUrl, request, root } = await setup(t, undefined, 60);
  const gate = Promise.withResolvers<void>(); let uploads = 0, prompts = 0;
  adapter.phoneCapabilities = () => ({ steer: false, queue: false, rename: false, models: false, questions: false, attachments: true });
  adapter.upload = async (_id, input) => { uploads++; await gate.promise; return { ref: { id: 'f', name: input.name, size: 1, mime: input.mime, kind: 'file' }, upstream: { type: 'file', receiptId: 'r' } }; };
  adapter.prompt = async () => { prompts++; };
  const device = pair(), ids: string[] = [], picks: string[] = [];
  // Local intake shares the same per-device limiter: finish all picks before
  // occupying its two slots with intentionally held send-time upstream uploads.
  for (let i = 0; i < 3; i++) {
    const pick = await fetch(baseUrl + '/v1/sessions/chat/uploads', { method: 'POST', headers: { Authorization: 'Bearer ' + device.deviceToken, 'Content-Type': 'text/plain' }, body: 'a' });
    assert.equal(pick.status, 201); picks.push((await pick.json()).uploadId);
  }
  try {
    for (const uploadId of picks) {
      const requestId = randomUUID(); ids.push(requestId);
      const sent = await request('/sessions/chat/messages', device.deviceToken, { requestId, text: 'Synthetic', uploadIds: [uploadId] });
      assert.equal(sent.status, 202, JSON.stringify(await sent.json()));
    }
    await new Promise(r => setTimeout(r, 100));
    assert.equal(uploads, 2, 'third send is queued behind per-device capacity');
    assert.ok(ids.every(id => host.state.getCommand(device.deviceId, id)?.receipt.status === 'uncertain'));
    assert.equal((await readdir(join(root, 'mobile-uploads'))).length, 3);
  } finally { gate.resolve(); }
  const until = Date.now() + 2000;
  while ((await readdir(join(root, 'mobile-uploads'))).length) { assert.ok(Date.now() < until); await new Promise(r => setImmediate(r)); }
  assert.equal(uploads, 2); assert.equal(prompts, 0);
});
