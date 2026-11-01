import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { runAdminCli } from '../src/cli.ts';
import { HostState, hashSecret } from '../src/state.ts';

const grants = { readWorkspaceIds: ['alpha'], executeWorkspaceIds: ['alpha'] };

function fixture(t: { after(fn: () => void): void }) {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-recovery-cli-')));
  const opened: HostState[] = [];
  t.after(() => { for (const state of opened) state.close(); rmSync(dir, { recursive: true, force: true }); });
  const configPath = join(dir, 'host.json'), statePath = join(dir, 'state', 'host.sqlite');
  writeFileSync(configPath, JSON.stringify({
    hostName: 'Synthetic recovery fixture', bind: '127.0.0.1', port: 0, statePath, allowInsecureLoopback: true,
    workspaces: [{ id: 'alpha', name: 'Alpha', path: dir }],
  }) + '\n');
  const state = new HostState(statePath);
  opened.push(state);
  const device = state.consumePairing(state.createPairing(grants).pairingToken, 'Fixture phone');
  const requestId = randomUUID();
  state.admitCommand(device.deviceId, requestId, 'prompt', hashSecret('synthetic'), 'alpha', 'session-alpha');
  state.finishCommand(device.deviceId, requestId, 'accepted');
  state.close();
  return { dir, configPath, statePath, deviceId: device.deviceId, requestId, track: (next: HostState) => { opened.push(next); return next; } };
}

function run(args: string[]) {
  let out = '', error = '';
  return runAdminCli(args, { out(text) { out += text; }, error(text) { error += text; } }).then(code => ({ code, out, error }));
}

test('recovery status reports the ledger and its recent command without exposing text', async t => {
  const f = fixture(t), result = await run(['recovery-status', '--config', f.configPath]);
  assert.equal(result.code, 0, result.error);
  const status = JSON.parse(result.out);
  assert.equal(status.hold, false); assert.equal(status.epoch, 1); assert.equal(status.witnessProblem, 'none');
  assert.equal(status.recentCommands.length, 1);
  assert.equal(status.recentCommands[0].requestId, f.requestId);
  assert.equal(status.recentCommands[0].sessionId, 'session-alpha');
  assert.equal(status.recentCommands[0].text, undefined, 'no prompt text is ever returned');
});

test('recovery hold and release are explicit, require a new epoch, and survive reopen', async t => {
  const f = fixture(t);
  let result = await run(['recovery-hold', '--config', f.configPath, '--reason', 'synthetic reconciliation']);
  assert.equal(result.code, 0, result.error);
  assert.equal(JSON.parse(result.out).hold, true);
  result = await run(['recovery-release', '--config', f.configPath]);
  assert.equal(result.code, 1); assert.match(result.error, /invalid_request/);
  result = await run(['recovery-release', '--config', f.configPath, '--new-epoch']);
  assert.equal(result.code, 0, result.error);
  const released = JSON.parse(result.out);
  assert.equal(released.hold, false); assert.equal(released.epoch, 2);
  assert.match(result.error, /does not prove earlier commands never executed/);
  const state = f.track(new HostState(f.statePath));
  assert.equal(state.ledgerStatus().hold, false);
  assert.equal(state.getCommand(f.deviceId, f.requestId)?.receipt.status, 'accepted');
});

test('recovery hold rejects an empty or control-character reason and a double release', async t => {
  const f = fixture(t);
  assert.equal((await run(['recovery-hold', '--config', f.configPath, '--reason', ''])).code, 1);
  assert.equal((await run(['recovery-hold', '--config', f.configPath, '--reason', 'bad\nreason'])).code, 1);
  assert.equal((await run(['recovery-hold', '--config', f.configPath])).code, 0);
  assert.equal((await run(['recovery-release', '--config', f.configPath, '--new-epoch'])).code, 0);
  assert.equal((await run(['recovery-release', '--config', f.configPath, '--new-epoch'])).code, 1);
});
