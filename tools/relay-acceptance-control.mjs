import assert from 'node:assert/strict';
import { readFile, writeFile, rename } from 'node:fs/promises';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

export async function fixtureControl(run, action, { deadlineMs = 5000 } = {}) {
  const command = path.join(run, 'control.json');
  await writeFile(command + '.writing', JSON.stringify({ action }), { flag: 'wx' });
  await rename(command + '.writing', command);
  const deadline = Date.now() + deadlineMs;
  while (Date.now() < deadline) {
    try {
      if (action === 'finish') {
        const receipt = JSON.parse(await readFile(path.join(run, 'receipt.json'), 'utf8'));
        if (receipt.stopReason === 'operator-finish') {
          assert.equal(receipt.cleanup.hostClosed, true);
          assert.equal(receipt.cleanup.privateRuntimeRemoved, true);
          return receipt;
        }
      } else {
        const result = JSON.parse(await readFile(path.join(run, 'control-result.json'), 'utf8'));
        if (result.action === action && result.applied) return result;
      }
    } catch (error) { if (error.code !== 'ENOENT' && !(error instanceof SyntaxError)) throw error; }
    await delay(25);
  }
  throw new Error(action === 'finish' ? 'Durable fixture finish deadline' : 'Fixture control deadline');
}
