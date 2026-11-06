import assert from 'node:assert/strict';
import { test } from 'node:test';
import { derivePromptOutcomes } from '../src/prompt-outcomes.ts';

const id = '00000000-0000-4000-8000-000000000001';
const signal = () => new AbortController().signal;
function event(type: string, data: unknown, seq: number) { return { type, data, seq, time: 1000 + seq }; }
function message(rpcId = id) { return { id: 'message-' + rpcId, source: { kind: 'user', rpcId }, content: [{ type: 'text', text: 'Synthetic' }] }; }
function reader(events: unknown[]) { return { async read(offset: number, length: number) { return { events: events.slice(offset, offset + length) }; }, async close() {} }; }

test('durable journal resolves completed after transcript eviction and later desktop turns', async () => {
  const events = [event('turn/start', { turn: 1 }, 0), event('user/message', message(), 1), event('turn/end', { turn: 1, reason: { kind: 'completed' } }, 2), event('turn/start', { turn: 2 }, 3), event('user/message', message('desktop'), 4)];
  const [result] = await derivePromptOutcomes(reader(events), [{ requestId: id }], signal());
  assert.deepEqual(result!.outcome, { requestId: id, status: 'completed', turnId: '1', updatedAt: '1970-01-01T00:00:01.002Z' });
});

test('queued admission comes from durable inbox, not an accepted receipt or idle state', async () => {
  const events = [event('agent/inbox/spliced', { target: 'next-turn', start: 0, inserted: [message()] }, 0)];
  assert.equal((await derivePromptOutcomes(reader(events), [{ requestId: id }], signal()))[0]!.outcome.status, 'queued');
  assert.equal((await derivePromptOutcomes(reader([]), [{ requestId: id }], signal()))[0]!.outcome.status, 'unknown');
});

test('durable user message proves consuming turn and queued steer shares the turn', async () => {
  const steer = '00000000-0000-4000-8000-000000000002';
  const events = [event('agent/inbox/spliced', { target: 'next-turn', start: 0, inserted: [message()] }, 0), event('turn/start', { turn: 7 }, 1), event('agent/inbox/spliced', { target: 'next-turn', start: 0, removedCount: 1, inserted: [] }, 2), event('user/message', message(), 3), event('agent/inbox/spliced', { target: 'next-step', start: 0, inserted: [message(steer)] }, 4), event('agent/inbox/spliced', { target: 'next-step', start: 0, removedCount: 1, inserted: [] }, 5), event('user/message', message(steer), 6)];
  const running = await derivePromptOutcomes(reader(events), [{ requestId: id }, { requestId: steer }], signal());
  assert.deepEqual(running.map(c => [c.outcome.status, c.outcome.turnId]), [['running', '7'], ['running', '7']]);
  events.push(event('turn/end', { turn: 7, reason: { kind: 'completed' } }, 7));
  const completed = await derivePromptOutcomes(reader(events), running.map((checkpoint, i) => ({ requestId: [id, steer][i]!, checkpoint })), signal());
  assert.deepEqual(completed.map(c => [c.outcome.status, c.outcome.turnId]), [['completed', '7'], ['completed', '7']]);
});

test('waiting question and approval episodes clear only on their own durable resolution', async () => {
  const events = [event('turn/start', { turn: 1 }, 0), event('user/message', message(), 1), event('tool/call', { turn: 1, name: 'ask_user_question', callId: 'q' }, 2), event('approval/asked', { id: 'a' }, 3)];
  let [checkpoint] = await derivePromptOutcomes(reader(events), [{ requestId: id }], signal());
  assert.equal(checkpoint!.outcome.status, 'waiting');
  events.push(event('approval/decided', { id: 'a', outcome: 'allowed-once' }, 4));
  [checkpoint] = await derivePromptOutcomes(reader(events), [{ requestId: id, checkpoint: checkpoint! }], signal());
  assert.equal(checkpoint!.outcome.status, 'waiting');
  events.push(event('tool/result', { message: { toolCallId: 'q', content: [] } }, 5));
  [checkpoint] = await derivePromptOutcomes(reader(events), [{ requestId: id, checkpoint: checkpoint! }], signal());
  assert.equal(checkpoint!.outcome.status, 'running');
  events.push(event('tool/call', { turn: 1, name: 'ask_user_question', callId: 'q2' }, 6));
  [checkpoint] = await derivePromptOutcomes(reader(events), [{ requestId: id, checkpoint: checkpoint! }], signal());
  assert.equal(checkpoint!.outcome.status, 'waiting');
  events.push(event('turn/end', { turn: 1, reason: { kind: 'completed' } }, 7));
  [checkpoint] = await derivePromptOutcomes(reader(events), [{ requestId: id, checkpoint: checkpoint! }], signal());
  assert.equal(checkpoint!.outcome.status, 'completed');
});

test('failed astral/control/BOM/CRLF/lone-CR/non-ASCII summary is safe and at most 2048 UTF-8 bytes', async () => {
  const text = '\uFEFFОшибка\r\n\0\t' + '🛰️'.repeat(800) + '\rконец';
  const events = [event('turn/start', { turn: 1 }, 0), event('user/message', message(), 1), event('turn/end', { turn: 1, reason: { kind: 'error', error: { message: text } } }, 2)];
  const [result] = await derivePromptOutcomes(reader(events), [{ requestId: id }], signal());
  assert.equal(result!.outcome.status, 'failed');
  const summary = result!.outcome.summary!;
  assert.ok(Buffer.byteLength(summary) <= 2048); assert.ok(summary.startsWith('Ошибка 🛰️'));
  assert.doesNotMatch(summary, /[\u0000-\u001f\ud800-\udfff]/u);
  assert.equal(Buffer.from(summary).toString(), summary);
});

test('forked and unrecognized endings remain unknown rather than guessing a result', async () => {
  for (const kind of ['forked', 'future']) {
    const events = [event('turn/start', { turn: 1 }, 0), event('user/message', message(), 1), event('turn/end', { turn: 1, reason: { kind } }, 2)];
    assert.equal((await derivePromptOutcomes(reader(events), [{ requestId: id }], signal()))[0]!.outcome.status, 'unknown');
  }
});

test('known unsuccessful turn endings settle failed, including persisted v2 unknown after restart', async () => {
  const summaries = {
    aborted: 'Задача остановлена до получения результата.',
    blocked: 'Выполнение задачи заблокировано.',
    'max-tokens': 'Достигнут лимит токенов. Полный результат не получен.',
    interrupted: 'Выполнение задачи прервано. Результат не получен.',
  };
  for (const [kind, summary] of Object.entries(summaries)) {
    const events = [event('turn/start', { turn: 1 }, 0), event('user/message', message(), 1),
      event('turn/end', { turn: 1, reason: { kind, ...(kind === 'aborted' ? { reason: { kind: 'user' } } : {}) } }, 2)];
    const old = { consumptionVersion: 2 as const, nextSeq: 3, caughtUp: true, turn: 1, approvals: [], questions: [],
      outcome: { requestId: id, status: 'unknown' as const, turnId: '1', updatedAt: '1970-01-01T00:00:01.002Z' } };
    for (const checkpoint of [undefined, JSON.parse(JSON.stringify(old))]) {
      const [result] = await derivePromptOutcomes(reader(events), [{ requestId: id, checkpoint }], signal());
      assert.deepEqual(result!.outcome, { requestId: id, status: 'failed', turnId: '1', summary, updatedAt: '1970-01-01T00:00:01.002Z' });
    }
  }
});

test('bounded scan checkpoints restart at cursor and page split preserves open turn', async () => {
  const events = Array.from({ length: 4200 }, (_, seq) => event('session/title', { title: 'synthetic' }, seq));
  events[4095] = event('turn/start', { turn: 9 }, 4095);
  events[4096] = event('user/message', message(), 4096);
  events[4199] = event('turn/end', { turn: 9, reason: { kind: 'completed' } }, 4199);
  const [first] = await derivePromptOutcomes(reader(events), [{ requestId: id }], signal());
  assert.equal(first!.nextSeq, 4096); assert.equal(first!.caughtUp, false);
  let start = -1;
  const source = { ...reader(events), async read(offset: number, length: number) { if (start === -1) start = offset; return { events: events.slice(offset, offset + length) }; } };
  const [second] = await derivePromptOutcomes(source, [{ requestId: id, checkpoint: JSON.parse(JSON.stringify(first)) }], signal());
  assert.equal(start, 4096); assert.equal(second!.outcome.status, 'completed');
  const [third] = await derivePromptOutcomes({ ...source, async read() { throw new Error('terminal should not scan'); } }, [{ requestId: id, checkpoint: second! }], signal());
  assert.deepEqual(third, second);
});

test('continued timed questions and PTC pending calls remain waiting until a durable answer', async () => {
  const events = [event('turn/start', { turn: 1 }, 0), event('user/message', message(), 1), event('tool/call', { turn: 1, name: 'ask_user_question', callId: 'q' }, 2), event('tool/result', { message: { toolCallId: 'q', content: [{ type: 'text', text: '{"pending":true}' }] } }, 3)];
  let [checkpoint] = await derivePromptOutcomes(reader(events), [{ requestId: id }], signal());
  assert.equal(checkpoint!.outcome.status, 'waiting');
  events.push(event('user/message', { source: { kind: 'user-question-reply', callId: 'q' } }, 4));
  [checkpoint] = await derivePromptOutcomes(reader(events), [{ requestId: id, checkpoint: checkpoint! }], signal());
  assert.equal(checkpoint!.outcome.status, 'running');
  events.push(event('tool/ptc-dispatch', { name: 'ask_user_question', subCallId: 'ptc-q', content: [{ type: 'text', text: '{"pending":true}' }] }, 5));
  [checkpoint] = await derivePromptOutcomes(reader(events), [{ requestId: id, checkpoint: checkpoint! }], signal());
  assert.equal(checkpoint!.outcome.status, 'waiting');
});

test('inbox reorder/removal and cancellation cannot attach a queued phone prompt to an unrelated turn', async () => {
  const events = [event('agent/inbox/spliced', { target: 'next-turn', start: 0, inserted: [message('desktop'), message()] }, 0), event('agent/inbox/spliced', { target: 'next-turn', start: 0, removedCount: 1, inserted: [], outcome: 'canceled' }, 1), event('turn/start', { turn: 5 }, 2), event('agent/inbox/spliced', { target: 'next-turn', start: 0, removedCount: 1, inserted: [], outcome: 'canceled' }, 3), event('turn/end', { turn: 5, reason: { kind: 'completed' } }, 4)];
  const [checkpoint] = await derivePromptOutcomes(reader(events), [{ requestId: id }], signal());
  assert.equal(checkpoint!.outcome.status, 'rejected'); assert.equal(checkpoint!.outcome.turnId, undefined);
});

test('explicit queue discard settles rejected and old unknown checkpoints are repaired without consumption', async () => {
  const events = [event('turn/start', { turn: 1 }, 0), event('user/message', message('desktop'), 1),
    event('agent/inbox/spliced', { target: 'next-turn', start: 0, inserted: [message()] }, 2),
    event('agent/inbox/spliced', { target: 'next-turn', start: 0, removedCount: 1, inserted: [], outcome: 'canceled' }, 3)];
  const legacy = { consumptionVersion: 1 as const, nextSeq: 4, caughtUp: true, openTurn: 1, approvals: [], questions: [],
    outcome: { requestId: id, status: 'unknown' as const, updatedAt: '1970-01-01T00:00:01.003Z' } };
  for (const checkpoint of [undefined, legacy]) {
    const [result] = await derivePromptOutcomes(reader(events), [{ requestId: id, checkpoint }], signal());
    assert.deepEqual(result!.outcome, { requestId: id, status: 'rejected', updatedAt: '1970-01-01T00:00:01.003Z' });
    assert.equal(result!.pending, undefined);
  }
});

test('R07 replacement and cross-inbox reinsertion stay queued through completed or failed unrelated turns', async () => {
  for (const target of ['next-turn', 'next-step']) for (const reason of [{ kind: 'completed' }, { kind: 'error', error: { message: 'unrelated' } }]) {
    const events = [event('turn/start', { turn: 1 }, 0), event('agent/inbox/spliced', { target: 'next-turn', start: 0, inserted: [message('desktop'), message()] }, 1),
      event('agent/inbox/spliced', { target: 'next-turn', start: 0, removedCount: 2, inserted: target === 'next-turn' ? [message(), message('desktop')] : [], outcome: 'canceled' }, 2)];
    if (target === 'next-step') events.push(event('agent/inbox/spliced', { target, start: 0, inserted: [message()] }, events.length));
    const [pending] = await derivePromptOutcomes(reader(events), [{ requestId: id }], signal());
    assert.equal(pending!.outcome.status, 'queued'); assert.equal(pending!.outcome.turnId, undefined);
    events.push(event('turn/end', { turn: 1, reason }, events.length));
    const [result] = await derivePromptOutcomes(reader(events), [{ requestId: id, checkpoint: JSON.parse(JSON.stringify(pending)) }], signal());
    assert.equal(result!.outcome.status, 'queued'); assert.equal(result!.outcome.turnId, undefined); assert.equal(result!.pending?.target, target);
  }
});

test('discard at bounded scan edge cannot settle before a following steer reinsertion', async () => {
  const events = Array.from({ length: 4095 }, (_, seq) => event('session/title', {}, seq));
  events[0] = event('agent/inbox/spliced', { target: 'next-turn', start: 0, inserted: [message()] }, 0);
  events.push(event('agent/inbox/spliced', { target: 'next-turn', start: 0, removedCount: 1, inserted: [], outcome: 'canceled' }, 4095));
  events.push(event('agent/inbox/spliced', { target: 'next-step', start: 0, inserted: [message()] }, 4096));
  const [first] = await derivePromptOutcomes(reader(events), [{ requestId: id }], signal());
  assert.equal(first!.caughtUp, false);
  const [second] = await derivePromptOutcomes(reader(events), [{ requestId: id, checkpoint: first }], signal());
  assert.equal(second!.outcome.status, 'queued'); assert.equal(second!.caughtUp, true);
});

test('R07 removal without durable consumption is unknown, even across checkpoint restart', async () => {
  const events = [event('agent/inbox/spliced', { target: 'next-turn', start: 0, inserted: [message()] }, 0), event('turn/start', { turn: 7 }, 1), event('agent/inbox/spliced', { target: 'next-turn', start: 0, removedCount: 1, inserted: [] }, 2)];
  const [checkpoint] = await derivePromptOutcomes(reader(events), [{ requestId: id }], signal());
  assert.equal(checkpoint!.outcome.status, 'unknown'); assert.equal(checkpoint!.outcome.turnId, undefined);
  events.push(event('turn/end', { turn: 7, reason: { kind: 'completed' } }, 3));
  assert.equal((await derivePromptOutcomes(reader(events), [{ requestId: id, checkpoint: checkpoint! }], signal()))[0]!.outcome.status, 'unknown');
});

test('R07 legacy terminal checkpoint without consumption proof is rederived instead of memoized', async () => {
  const events = [event('agent/inbox/spliced', { target: 'next-turn', start: 0, inserted: [message()] }, 0)];
  const checkpoint = { nextSeq: 1, caughtUp: true, turn: 1, pending: { target: 'next-turn', index: 0 }, approvals: [], questions: [], outcome: { requestId: id, status: 'completed' as const, turnId: '1', updatedAt: '1970-01-01T00:00:01.000Z' } };
  assert.equal((await derivePromptOutcomes(reader(events), [{ requestId: id, checkpoint }], signal()))[0]!.outcome.status, 'queued');
});

test('journal gaps, duplicate/reordered records, oversized events and canceled reads fail closed', async () => {
  for (const events of [[event('turn/start', { turn: 1 }, 1)], [event('turn/start', { turn: 1 }, 0), event('turn/start', { turn: 1 }, 0)], [event('session/title', { title: 'x'.repeat(16 * 1024 * 1024) }, 0)]]) {
    await assert.rejects(derivePromptOutcomes(reader(events), [{ requestId: id }], signal()));
  }
  const controller = new AbortController(); controller.abort();
  await assert.rejects(derivePromptOutcomes(reader([]), [{ requestId: id }], controller.signal));
});
