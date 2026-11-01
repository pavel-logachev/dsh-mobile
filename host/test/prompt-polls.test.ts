import assert from 'node:assert/strict';
import { test } from 'node:test';
import { PromptPolls } from '../src/prompt-polls.ts';

function deferred() { let resolve!: () => void; const promise = new Promise<void>(r => { resolve = r; }); return { promise, resolve }; }
const signal = () => new AbortController().signal;
const page = (token: string) => ({ token, outcomes: [] });

for (const kind of ['timeout', 'abort', 'supersede', 'shutdown', 'opening-shutdown'] as const) {
  test(`R06 ${kind} releases subscription and cancels an unsettled read without awaiting late IO`, async () => {
    const polls = new PromptPolls(), entered = deferred(), held = deferred(), controller = new AbortController();
    let wake = () => {}, reads = 0, subscriptions = 0, readSignal: AbortSignal | undefined;
    const pending = polls.get('phone', 'same', 1, async (active?: AbortSignal) => {
      readSignal = active;
      if (++reads === (kind === 'opening-shutdown' ? 1 : 2)) { entered.resolve(); await held.promise; }
      return page('same');
    }, fn => { subscriptions++; wake = fn; return () => { subscriptions--; }; }, controller.signal);
    const observed = pending.then(value => ({ value }), error => ({ error }));
    let second: Promise<unknown> | undefined;
    try {
      if (kind !== 'opening-shutdown') { await new Promise<void>(resolve => setImmediate(resolve)); wake(); }
      await entered.promise;
      if (kind === 'abort') controller.abort();
      if (kind.endsWith('shutdown')) await Promise.race([polls.close(), new Promise<never>((_, reject) => { const timer = setTimeout(() => reject(new Error('close retained held IO')), 300); timer.unref(); })]);
      if (kind === 'supersede') second = polls.get('phone', 'same', 25, async () => page('same'), () => { subscriptions++; return () => { subscriptions--; }; }, signal());
      const result = await Promise.race([observed, new Promise<never>((_, reject) => { const timer = setTimeout(() => reject(new Error('poll retained held IO')), kind === 'timeout' ? 1300 : 300); timer.unref(); })]);
      assert.equal(subscriptions, kind === 'supersede' ? 1 : 0);
      assert.equal(readSignal?.aborted, true, 'underlying read receives cancellation');
      if (kind === 'abort' || kind === 'opening-shutdown') assert.ok('error' in result);
      else assert.deepEqual(result, { value: page('same') });
    } finally { held.resolve(); await polls.close(); await observed; await second; }
    assert.equal(subscriptions, 0);
  });
}

test('R06 132 superseded held reads retain only one subscription and shutdown observes late rejection', async () => {
  const polls = new PromptPolls(), late = Promise.withResolvers<void>(); let subscriptions = 0, finished = 0;
  const pending: Promise<unknown>[] = [];
  try {
    for (let i = 0; i < 132; i++) {
      const entered = deferred(); let reads = 0, wake = () => {};
      pending.push(polls.get('phone', 'same', 25, async () => { if (++reads === 2) { entered.resolve(); await late.promise; } return page('same'); }, fn => { subscriptions++; wake = fn; return () => { subscriptions--; }; }, signal()).then(() => { finished++; }));
      await new Promise<void>(resolve => setImmediate(resolve)); wake(); await entered.promise;
      assert.equal(subscriptions, 1); assert.equal(finished, i);
    }
    await polls.close(); await Promise.all(pending);
    assert.equal(subscriptions, 0); assert.equal(finished, 132);
    late.reject(new Error('late upstream rejection'));
    await new Promise<void>(resolve => setImmediate(resolve));
  } finally { late.resolve(); await polls.close(); await Promise.all(pending); }
});

test('R06 global active cap includes opening reads and abort releases every subscription', async () => {
  const polls = new PromptPolls(), gate = deferred(), controllers: AbortController[] = []; let subscriptions = 0;
  const pending: Promise<unknown>[] = [];
  try {
    for (let i = 0; i < 128; i++) {
      const controller = new AbortController(); controllers.push(controller);
      pending.push(polls.get(String(i), null, 0, async () => { await gate.promise; return page('same'); }, () => { subscriptions++; return () => { subscriptions--; }; }, controller.signal).catch(error => error));
    }
    await assert.rejects(polls.get('overflow', null, 0, async () => page('same'), () => { throw new Error('must reject before subscribe'); }, signal()), { code: 'unavailable' });
    assert.equal(subscriptions, 128);
    controllers.forEach(controller => controller.abort()); await Promise.all(pending);
    assert.equal(subscriptions, 0);
  } finally { gate.resolve(); await polls.close(); await Promise.all(pending); }
});

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
