import assert from 'node:assert/strict';
import { test } from 'node:test';
import { PromptPolls } from '../src/prompt-polls.ts';

function deferred() { let resolve!: () => void; const promise = new Promise<void>(r => { resolve = r; }); return { promise, resolve }; }
const signal = () => new AbortController().signal;
const page = (token: string) => ({ token, outcomes: [] });

test('poll event during opening read is not lost and no concurrent read starts', async () => {
  const polls = new PromptPolls(), opening = deferred(); let wake = () => {}, reads = 0;
  const pending = polls.get('phone', 'old', 25, async () => { reads++; if (reads === 1) { await opening.promise; return page('old'); } return page('new'); }, fn => { wake = fn; return () => {}; }, signal());
  wake(); opening.resolve();
  assert.deepEqual(await pending, page('new')); assert.equal(reads, 2); await polls.close();
});

test('new held poll supersedes previous across session groups and returns current state', async () => {
  const polls = new PromptPolls(), started = deferred(); let reads = 0;
  const first = polls.get('phone', 'old', 25, async () => { reads++; if (reads === 1) started.resolve(); return page('old'); }, () => () => {}, signal());
  await started.promise;
  // Let first install its held registration, with no wall-clock timing assumption.
  await new Promise<void>(resolve => setImmediate(resolve));
  const nextStart = deferred();
  const second = polls.get('phone', 'another-session', 25, async () => { nextStart.resolve(); return page('another-session'); }, () => () => {}, signal());
  await nextStart.promise;
  assert.deepEqual(await first, page('old'));
  await polls.close(); assert.deepEqual(await second, page('another-session'));
});

test('disconnected poll aborts and disposes subscription; shutdown disposes held polls', async () => {
  const polls = new PromptPolls(), abort = new AbortController(), started = deferred(); let disposed = 0;
  const pending = polls.get('phone', 'same', 25, async () => { started.resolve(); return page('same'); }, () => () => { disposed++; }, abort.signal);
  await started.promise; abort.abort(); await assert.rejects(pending); assert.equal(disposed, 1);
  await polls.close();
  await assert.rejects(polls.get('phone', null, 0, async () => page('same'), () => () => {}, signal()));
});
