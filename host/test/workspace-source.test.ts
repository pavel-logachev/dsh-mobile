import assert from 'node:assert/strict';
import { mkdtemp, mkdir, realpath, rm, readFile, writeFile, symlink, unlink, lstat } from 'node:fs/promises';
import { realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { prepareConfiguration, loadConfiguration } from '../src/config.ts';
import { createWorkspaceSource } from '../src/workspace-source.ts';
import type { DshWorkspace } from '../src/workspace-source.ts';
import { startHostServer } from '../src/server.ts';
import { createDshAdapter } from '../src/dsh-adapter.ts';
import { runAdminCli } from '../src/cli.ts';
import { HostState } from '../src/state.ts';
import { opening, summary } from './fixtures/rc2.ts';
import { randomUUID } from 'node:crypto';

const ALPHA = '11111111-1111-4111-8111-111111111111';
const BETA = '22222222-2222-4222-8222-222222222222';
function workspace(id: string, path: string, title: string): DshWorkspace {
  return { id, path, title, async status() { try { await realpath(path); return 'ok'; } catch { return 'missing-dir'; } } };
}

test('registry mode projects live sidebar order, sanitized names, new/renamed/deleted projects and missing directories', async t => {
  const dir = realpathSync.native(await mkdtemp(join(tmpdir(), 'dsh-mobile-registry-')));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const alpha = join(dir, 'alpha'), beta = join(dir, 'beta');
  await mkdir(alpha); await mkdir(beta);
  const config = { hostName: 'Synthetic registry', bind: '127.0.0.1', port: 0, statePath: join(dir, 'host.sqlite'), allowInsecureLoopback: true, workspaceSource: 'dsh-registry' as const };
  const prepared = await prepareConfiguration(config);
  assert.deepEqual(prepared.config.workspaces, []);
  let rows = [workspace(BETA, await realpath(beta), '  Бета\u0000\n  '), workspace(ALPHA, await realpath(alpha), 'А'.repeat(100))];
  const registry = { list: () => rows, archivedSessionIds: [] as string[] };
  const source = await createWorkspaceSource(prepared.config, () => registry);
  assert.deepEqual((await source.list()).map(item => [item.id, item.name]), [[BETA, 'Бета'], [ALPHA, 'А'.repeat(64)]]);
  rows = [workspace(ALPHA, await realpath(alpha), '  Новый заголовок  '), workspace(BETA, await realpath(beta), '\u0000 \n')];
  assert.deepEqual((await source.list()).map(item => [item.id, item.name]), [[ALPHA, 'Новый заголовок'], [BETA, 'beta']]);
  const gamma = join(dir, 'gamma'); await mkdir(gamma);
  rows.unshift(workspace('gamma', await realpath(gamma), 'Гамма (демо)'));
  assert.deepEqual((await source.list()).map(item => item.id), ['gamma', ALPHA, BETA]);
  rows = rows.filter(item => item.id !== ALPHA);
  await rm(beta, { recursive: true });
  assert.deepEqual((await source.list()).map(item => item.id), ['gamma']);
  const configPath = join(dir, 'host.json');
  await writeFile(configPath, JSON.stringify(config));
  assert.equal((await loadConfiguration(configPath)).workspaceSource, 'dsh-registry');
  await assert.rejects(prepareConfiguration({ ...config, workspaces: [{ id: 'a', name: 'A', path: alpha }] }), { code: 'invalid_config' });
});

test('distinct canonical paths differing only by case remain separate DSH workspace identities', async t => {
  const dir = realpathSync.native(await mkdtemp(join(tmpdir(), 'dsh-mobile-path-case-')));
  t.after(() => rm(dir, { recursive: true, force: true }));
  // Synthetic canonical filesystem results model case-sensitive Windows folders
  // without changing case-sensitivity flags on any host directory.
  const parent = await realpath(dir);
  const rows = [workspace(ALPHA, join(parent, 'Alpha'), 'Upper case'), workspace(BETA, join(parent, 'alpha'), 'Lower case')];
  for (const row of rows) row.status = async () => 'ok';
  const registry = { list: () => rows, archivedSessionIds: [] };
  const source = await createWorkspaceSource({ workspaceSource: 'dsh-registry' }, () => registry);
  assert.deepEqual((await source.list()).map(item => [item.id, item.path]), [[ALPHA, join(parent, 'Alpha')], [BETA, join(parent, 'alpha')]]);
});

test('registry source fails closed for unavailable/invalid services and caps visible projects at one hundred', async t => {
  const dir = realpathSync.native(await mkdtemp(join(tmpdir(), 'dsh-mobile-registry-limit-')));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const config = { workspaceSource: 'dsh-registry' as const };
  await assert.rejects(createWorkspaceSource(config), { code: 'workspace_registry_unavailable' });
  let registry: { list: () => DshWorkspace[]; archivedSessionIds: string[] } | undefined;
  const path = await realpath(dir);
  const rows = Array.from({ length: 101 }, (_, index) => workspace(`synthetic-${index}`, join(path, `path-${index}`), `Project ${index}`));
  for (const row of rows) row.status = async () => 'ok';
  registry = { list: () => rows, archivedSessionIds: [] };
  const source = await createWorkspaceSource(config, () => registry);
  const visible = await source.list();
  assert.equal(visible.length, 100); assert.equal(visible[0]?.id, 'synthetic-0'); assert.equal(visible[99]?.id, 'synthetic-99');
  rows[0] = { ...rows[0]!, id: '*' };
  await assert.rejects(source.list(), { code: 'workspace_registry_unavailable' });
  registry = undefined;
  await assert.rejects(source.list(), { code: 'workspace_registry_unavailable' });
  assert.throws(() => source.archivedSessionIds(), { code: 'workspace_registry_unavailable' });
});

test('registry source fails closed when its active service or visible revision changes during status awaits', async t => {
  const dir = realpathSync.native(await mkdtemp(join(tmpdir(), 'dsh-mobile-registry-await-'))), path = await realpath(dir);
  t.after(() => rm(dir, { recursive: true, force: true }));
  for (const change of ['lost', 'replaced', 'deleted', 'reordered', 'renamed', 'retargeted', 'remapped'] as const) {
    const entered = Promise.withResolvers<void>(), release = Promise.withResolvers<void>();
    const alpha = workspace(ALPHA, path, 'Before'), beta = workspace(BETA, join(path, 'beta'), 'Beta');
    alpha.status = async () => { entered.resolve(); await release.promise; return 'ok'; };
    beta.status = async () => 'ok';
    let rows = [alpha, beta];
    const original = { list: () => rows, archivedSessionIds: [] };
    let registry: typeof original | undefined = original;
    const source = await createWorkspaceSource({ workspaceSource: 'dsh-registry' }, () => registry);
    const pending = source.list();
    // Attach rejection handling before releasing the external status barrier.
    const denied = assert.rejects(pending, { code: 'workspace_registry_unavailable' }, change);
    await entered.promise;
    switch (change) {
      case 'lost': registry = undefined; break;
      case 'replaced': registry = { ...original }; break;
      case 'deleted': rows = [beta]; break;
      case 'reordered': rows.reverse(); break;
      case 'renamed': rows[0] = { ...alpha, title: 'After' }; break;
      case 'retargeted': rows[0] = { ...alpha, path: join(path, 'other') }; break;
      case 'remapped': rows[0] = { ...alpha, id: 'replacement' }; break;
    }
    release.resolve(); await denied;
  }
});

test('registry API lists new/renamed projects and all-scope sessions live, omits missing and archived entries without reload', async t => {
  const dir = realpathSync.native(await mkdtemp(join(tmpdir(), 'dsh-mobile-registry-api-')));
  const alpha = join(dir, 'alpha'), beta = join(dir, 'beta'); await mkdir(alpha); await mkdir(beta);
  let projects = [workspace(ALPHA, await realpath(alpha), 'Альфа (демо)')], archived: string[] = [];
  const config = { workspaceSource: 'dsh-registry' as const, hostName: 'Synthetic registry API', bind: '127.0.0.1', port: 0, statePath: join(dir, 'host.sqlite'), allowInsecureLoopback: true };
  const registry = { list: () => projects, get archivedSessionIds() { return archived; } };
  const source = await createWorkspaceSource(config, () => registry);
  let rows = [{ ...summary, cwd: alpha }];
  const controller = { async list() { return { items: rows }; }, async *follow(request: { address: { sessionId: string } }) {
    const frame = structuredClone(opening); frame.header.id = request.address.sessionId; frame.header.cwd = rows.find(row => row.sessionId === request.address.sessionId)!.cwd; yield frame;
  }, async projections() { return {}; }, async create(input: { sessionId: string }) { return { sessionId: input.sessionId }; }, async prompt() {}, cancel() { return { accepted: true }; } };
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: controller, workspaceSource: source });
  const host = await startHostServer({ config, adapter, workspaceSource: source });
  t.after(async () => { adapter.dispose(); await host.close(); await rm(dir, { recursive: true, force: true }); });
  const paired = host.state.consumePairing(host.state.createPairing({ readWorkspaceIds: ['*'], executeWorkspaceIds: ['*'] }).pairingToken, 'Synthetic phone');
  const base = (await host.start()).baseUrl;
  const request = (path: string, body?: unknown) => fetch(`${base}/v1${path}`, { headers: { authorization: `Bearer ${paired.deviceToken}`, ...(body ? { 'content-type': 'application/json' } : {}) }, ...(body ? { method: 'POST', body: JSON.stringify(body) } : {}) });
  assert.deepEqual((await (await request('/workspaces')).json()).items, [{ id: ALPHA, name: 'Альфа (демо)', canExecute: true }]);
  projects.unshift(workspace(BETA, await realpath(beta), 'Бета (демо)'));
  projects[1] = workspace(ALPHA, await realpath(alpha), 'Новая альфа (демо)');
  rows.unshift({ ...summary, sessionId: 'beta-session', cwd: beta });
  assert.deepEqual((await (await request('/workspaces')).json()).items, [{ id: BETA, name: 'Бета (демо)', canExecute: true }, { id: ALPHA, name: 'Новая альфа (демо)', canExecute: true }]);
  assert.equal((await (await request('/sessions')).json()).items.length, 2);
  assert.equal((await request('/sessions', { workspaceId: BETA, requestId: randomUUID() })).status, 201);
  const stream = await request('/sessions/session-fixture/events'), reader = stream.body!.getReader();
  await reader.read();
  archived = ['session-fixture'];
  assert.equal((await reader.read()).done, true);
  assert.equal((await request('/sessions/session-fixture')).status, 404);
  assert.equal((await request('/sessions/session-fixture/cancellations', { requestId: randomUUID(), expectedCursor: 7 })).status, 404);
  assert.deepEqual((await (await request('/sessions')).json()).items.map((item: { id: string }) => item.id), ['beta-session']);
  await rm(beta, { recursive: true });
  assert.deepEqual((await (await request('/workspaces')).json()).items.map((item: { id: string }) => item.id), [ALPHA]);
  assert.equal((await request('/sessions', { workspaceId: BETA, requestId: randomUUID() })).status, 403);
  projects = [];
  assert.deepEqual(await (await request('/workspaces')).json(), { items: [] });
});

test('listed metadata cannot cross alpha-only grants when a junction cwd retargets within the old cache TTL', async t => {
  const dir = realpathSync.native(await mkdtemp(join(tmpdir(), 'dsh-mobile-retarget-api-')));
  const alpha = join(dir, 'alpha'), beta = join(dir, 'beta'), cwd = join(dir, 'session-cwd');
  await mkdir(alpha); await mkdir(beta);
  const alphaPath = await realpath(alpha), betaPath = await realpath(beta);
  await symlink(alphaPath, cwd, process.platform === 'win32' ? 'junction' : 'dir');
  const config = { workspaceSource: 'dsh-registry' as const, hostName: 'Synthetic retarget', bind: '127.0.0.1', port: 0, statePath: join(dir, 'host.sqlite'), allowInsecureLoopback: true };
  const registry = { list: () => [workspace(ALPHA, alphaPath, 'Alpha'), workspace(BETA, betaPath, 'Beta')], archivedSessionIds: [] };
  const source = await createWorkspaceSource(config, () => registry);
  let row = { ...structuredClone(summary), cwd };
  const controller = { async list() { return { items: [row] }; }, async *follow() { throw new Error('Listing must never open transcript'); }, async projections() {}, async create() { throw new Error('No mutation expected'); }, async prompt() {}, cancel() {} };
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: controller, workspaceSource: source });
  const host = await startHostServer({ config, adapter, workspaceSource: source });
  t.after(async () => { adapter.dispose(); await host.close(); await rm(dir, { recursive: true, force: true }); });
  const pair = (id: string) => host.state.consumePairing(host.state.createPairing({ readWorkspaceIds: [id], executeWorkspaceIds: [] }).pairingToken, 'Synthetic phone');
  const alphaDevice = pair(ALPHA), betaDevice = pair(BETA), base = (await host.start()).baseUrl;
  const list = async (token: string) => {
    const response = await fetch(`${base}/v1/sessions`, { headers: { authorization: `Bearer ${token}` } });
    assert.equal(response.status, 200); return response.json();
  };
  assert.equal((await list(alphaDevice.deviceToken)).items[0]?.workspaceId, ALPHA);
  // Retarget only our verified disposable junction, not either owned directory.
  assert.equal((await lstat(cwd)).isSymbolicLink(), true); assert.equal(await realpath(cwd), alphaPath);
  await unlink(cwd); await symlink(betaPath, cwd, process.platform === 'win32' ? 'junction' : 'dir');
  row = { ...row, projections: { kind: 'cached', asOfSeq: 7, values: { title: 'BETA-ONLY NEW METADATA' } }, updatedAt: 900000, running: true };
  const denied = await list(alphaDevice.deviceToken);
  assert.deepEqual(denied.items, []); assert.doesNotMatch(JSON.stringify(denied), /BETA-ONLY NEW METADATA|900000/);
  const visible = (await list(betaDevice.deviceToken)).items;
  assert.equal(visible[0]?.workspaceId, BETA); assert.equal(visible[0]?.title, 'BETA-ONLY NEW METADATA');
});

test('mutations reject a registry workspace remap at the server-to-adapter admission barrier', async t => {
  const dir = realpathSync.native(await mkdtemp(join(tmpdir(), 'dsh-mobile-remap-api-'))), path = await realpath(dir);
  const config = { workspaceSource: 'dsh-registry' as const, hostName: 'Synthetic remap', bind: '127.0.0.1', port: 0, statePath: join(dir, 'host.sqlite'), allowInsecureLoopback: true };
  let projectId = ALPHA;
  const registry = { list: () => [workspace(projectId, path, 'Synthetic workspace')], archivedSessionIds: [] };
  const source = await createWorkspaceSource(config, () => registry);
  let prompts = 0, cancels = 0, creates = 0;
  const controller = { async list() { return { items: [{ ...summary, cwd: path, running: true }] }; }, async *follow() {
    const frame = structuredClone(opening); frame.header.cwd = path; yield frame;
  }, async projections() {}, async create(input: { sessionId: string }) { creates++; return { sessionId: input.sessionId }; }, async prompt() { prompts++; }, cancel() { cancels++; return { accepted: true }; } };
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: controller, workspaceSource: source });
  // Deterministically replace the external registry exactly after server checks,
  // at the public HostAdapter call boundary, then run the real adapter logic.
  const prompt = adapter.prompt.bind(adapter), cancel = adapter.cancel.bind(adapter), create = adapter.createSession.bind(adapter);
  adapter.prompt = async (...args) => { projectId = BETA; return prompt(...args); };
  adapter.cancel = async (...args) => { projectId = BETA; return cancel(...args); };
  adapter.createSession = async (...args) => { projectId = BETA; return create(...args); };
  const host = await startHostServer({ config, adapter, workspaceSource: source });
  t.after(async () => { adapter.dispose(); await host.close(); await rm(dir, { recursive: true, force: true }); });
  const device = host.state.consumePairing(host.state.createPairing({ readWorkspaceIds: [ALPHA], executeWorkspaceIds: [ALPHA] }).pairingToken, 'Synthetic alpha-only phone');
  const base = (await host.start()).baseUrl;
  for (const [suffix, body] of [['/session-fixture/messages', { text: 'Never dispatch into beta' }], ['/session-fixture/cancellations', { expectedCursor: 7 }], ['', { workspaceId: ALPHA }]] as const) {
    projectId = ALPHA;
    const requestId = randomUUID();
    const response = await fetch(`${base}/v1/sessions${suffix}`, { method: 'POST', headers: { authorization: `Bearer ${device.deviceToken}`, 'content-type': 'application/json' }, body: JSON.stringify({ ...body, requestId }) });
    assert.equal(response.status, 404);
    assert.equal((await response.json()).status, 'rejected');
    assert.equal(host.state.getCommand(device.deviceId, requestId)?.receipt.status, 'rejected');
  }
  assert.deepEqual({ prompts, cancels, creates }, { prompts: 0, cancels: 0, creates: 0 });
});

test('registry create with a preset attaches once and duplicate requestId returns the original receipt', async t => {
  const dir = realpathSync.native(await mkdtemp(join(tmpdir(), 'dsh-mobile-create-api-'))), path = await realpath(dir);
  const config = { workspaceSource: 'dsh-registry' as const, hostName: 'Synthetic create', bind: '127.0.0.1', port: 0, statePath: join(dir, 'host.sqlite'), allowInsecureLoopback: true };
  const registry = { list: () => [workspace(ALPHA, path, 'Synthetic project')], archivedSessionIds: [] };
  const source = await createWorkspaceSource(config, () => registry);
  const creates: unknown[] = [];
  const controller = { async list() { return { items: [] }; }, async *follow() {}, async projections() {}, async create(input: { sessionId: string }) { creates.push(input); return { sessionId: input.sessionId }; }, async prompt() {}, cancel() {} };
  const adapter = await createDshAdapter({ dshVersion: '0.2.0-rc.2', sessionController: controller, workspaceSource: source, agentPresets: { async remoteExportList() { return { presets: [{ id: 'synthetic-preset', name: 'Synthetic preset' }] }; } } });
  const host = await startHostServer({ config, adapter, workspaceSource: source });
  t.after(async () => { adapter.dispose(); await host.close(); await rm(dir, { recursive: true, force: true }); });
  const device = host.state.consumePairing(host.state.createPairing({ readWorkspaceIds: ['*'], executeWorkspaceIds: ['*'] }).pairingToken, 'Synthetic phone');
  const base = (await host.start()).baseUrl, requestId = randomUUID();
  const create = (presetId: string) => fetch(`${base}/v1/sessions`, { method: 'POST', headers: { authorization: `Bearer ${device.deviceToken}`, 'content-type': 'application/json' }, body: JSON.stringify({ requestId, workspaceId: ALPHA, presetId }) });
  const first = await create('synthetic-preset'); assert.equal(first.status, 201);
  const receipt = await first.json(); assert.equal(receipt.status, 'accepted');
  const duplicate = await create('synthetic-preset'); assert.equal(duplicate.status, 201); assert.deepEqual(await duplicate.json(), receipt);
  assert.equal((await create('different-preset')).status, 409);
  assert.deepEqual(creates, [{ workspaceId: ALPHA, sessionId: `session-${requestId}`, agentPreset: 'synthetic-preset' }]);
});

test('standalone registry administration supports all-scope pairing/grants only and never needs DSH storage or credentials', async t => {
  const dir = realpathSync.native(await mkdtemp(join(tmpdir(), 'dsh-mobile-registry-cli-')));
  const config = { workspaceSource: 'dsh-registry', hostName: 'Synthetic CLI', bind: '127.0.0.1', port: 9443, statePath: join(dir, 'host.sqlite'), allowInsecureLoopback: true };
  const configPath = join(dir, 'host.json'); await writeFile(configPath, JSON.stringify(config));
  let output = '', errors = ''; const io = { out: (text: string) => { output += text; }, error: (text: string) => { errors += text; } };
  const invitationPath = join(dir, 'invitations', 'registry.private.json');
  assert.equal(await runAdminCli(['pair', '--config', configPath, '--read', 'all', '--execute', 'all', '--output', invitationPath], io), 0);
  const invitation = JSON.parse(await readFile(invitationPath, 'utf8'));
  assert.doesNotMatch(output + errors, /pairingToken|accessToken|token_hash/);
  const state = new HostState(config.statePath);
  t.after(async () => { state.close(); await rm(dir, { recursive: true, force: true }); });
  const device = state.consumePairing(invitation.pairingToken, 'Synthetic phone');
  assert.deepEqual(state.getDevice(device.deviceId)?.grants, { readWorkspaceIds: ['*'], executeWorkspaceIds: ['*'] });
  output = ''; errors = '';
  assert.equal(await runAdminCli(['grant', '--config', configPath, '--device', device.deviceId, '--read', 'all'], io), 0);
  assert.deepEqual(state.authenticate(device.deviceToken)?.grants, { readWorkspaceIds: ['*'], executeWorkspaceIds: [] });
  assert.equal(await runAdminCli(['grant', '--config', configPath, '--device', device.deviceId, '--read', ALPHA], io), 1);
  assert.match(errors, /Explicit workspace IDs are only supported in explicit-list mode/);
  assert.equal(await runAdminCli(['pair', '--config', configPath, '--read', 'all', '--execute', ALPHA, '--output', join(dir, 'invitations', 'denied.private.json')], io), 1);
  assert.doesNotMatch(output + errors, new RegExp(`${device.deviceToken}|${invitation.pairingToken}`));
});
