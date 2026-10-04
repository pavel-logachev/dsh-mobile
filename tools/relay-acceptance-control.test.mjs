import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fixtureControl } from './relay-acceptance-control.mjs';

async function ownedRun() { return mkdtemp(path.resolve('artifacts/relay-acceptance/finish-test-')); }
test('finish confirms durable cleanup even when transient ACK is never visible', async () => {
  const run = await ownedRun();
  try {
    const finished = fixtureControl(run, 'finish', { deadlineMs: 1000 });
    while (true) { try { assert.equal(JSON.parse(await readFile(path.join(run, 'control.json'))).action, 'finish'); break; } catch { await delay(5); } }
    // Model the real race: ACK already removed before the probe can observe it.
    await writeFile(path.join(run, 'receipt.json'), JSON.stringify({ stopReason: 'operator-finish', cleanup: { hostClosed: true, privateRuntimeRemoved: true } }));
    assert.equal((await finished).cleanup.hostClosed, true);
  } finally { await rm(run, { recursive: true, force: true }); }
});
test('finish rejects durable receipt with incomplete cleanup', async () => {
  const run = await ownedRun();
  try {
    await writeFile(path.join(run, 'receipt.json'), JSON.stringify({ stopReason: 'operator-finish', cleanup: { hostClosed: false, privateRuntimeRemoved: true } }));
    await assert.rejects(fixtureControl(run, 'finish', { deadlineMs: 1000 }), { code: 'ERR_ASSERTION' });
  } finally { await rm(run, { recursive: true, force: true }); }
});
