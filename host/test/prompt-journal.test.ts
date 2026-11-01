import assert from 'node:assert/strict';
import { test } from 'node:test';
import { promptJournal } from '../src/prompt-journal.ts';

test('journal opens read-only with cancellation, closes exact handle and never flushes or writes', async () => {
  const signal = new AbortController().signal;
  const opened: unknown[] = []; let closed = 0;
  const handle = { async read() { return { events: [] }; }, async close() { closed++; } };
  const backend = { async open(...args: unknown[]) { opened.push(args); return handle; } };
  const source = promptJournal(() => backend);
  const reader = await source.open('synthetic', signal);
  assert.deepEqual(await reader.read(0, 256, { signal }), { events: [] });
  assert.deepEqual(opened, [['synthetic', 'read', { signal }]]);
  await reader.close(); assert.equal(closed, 1);
});

test('journal service loss and pre-aborted opening fail closed', async () => {
  const source = promptJournal(() => undefined);
  await assert.rejects(source.open('synthetic', new AbortController().signal));
  const controller = new AbortController(); controller.abort();
  await assert.rejects(source.open('synthetic', controller.signal));
});

test('journal replacement during read fails closed; original handle is still closed', async () => {
  let closed = 0;
  let backend: any = { async open() { return { async read() { backend = {}; return { events: [] }; }, async close() { closed++; } }; } };
  const source = promptJournal(() => backend), reader = await source.open('synthetic', new AbortController().signal);
  try { await assert.rejects(reader.read(0, 256)); } finally { await reader.close(); }
  assert.equal(closed, 1);
});
