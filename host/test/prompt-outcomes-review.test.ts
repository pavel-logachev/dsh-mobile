import assert from 'node:assert/strict';
import { test } from 'node:test';
import { derivePromptOutcomes, provenTerminal } from '../src/prompt-outcomes.ts';
import type { OutcomeCheckpoint } from '../src/prompt-outcomes.ts';
const id = '00000000-0000-4000-8000-000000000001', other = '00000000-0000-4000-8000-000000000002';
const signal = () => new AbortController().signal;
const event = (type: string, data: unknown, seq: number) => ({ type, data, seq, time: 1000 + seq });
const message = (rpcId = id) => ({ source: { kind: 'user', rpcId } });
const reader = (events: unknown[]) => ({ async read(o: number, n: number) { return { events: events.slice(o, o + n) }; }, async close() {} });
const prior = (version: 1 | 2 | 3, status: 'completed' | 'failed'): OutcomeCheckpoint => ({ consumptionVersion: version, nextSeq: 9000, turn: 1, approvals: [], questions: [], outcome: { requestId: id, status, turnId: '1', updatedAt: '1970-01-01T00:00:01.002Z' } });
const heavy = (seq: number) => event('session/title', { title: 'x'.repeat(512 * 1024), messageSeqs: [], source: { kind: 'user' } }, seq);

test('F1 cv2 and cv3 terminal proof and cursor upgrade without any read; cv1 and pending still rescan', async () => {
  for (const version of [2, 3] as const) for (const status of ['completed', 'failed'] as const) {
    const checkpoint = prior(version, status), snapshot = structuredClone(checkpoint);
    const [actual] = await derivePromptOutcomes({ ...reader([]), async read() { throw Error('must not read proven terminal'); } }, [{ requestId: id, checkpoint }], signal());
    assert.deepEqual(actual, { ...checkpoint, consumptionVersion: 4, caughtUp: true });
    assert.deepEqual(checkpoint, snapshot); assert.equal(provenTerminal(actual!), true);
  }
  for (const checkpoint of [prior(1, 'completed'), { ...prior(3, 'completed'), pending: { target: 'next-turn', index: 0 } }]) {
    const [actual] = await derivePromptOutcomes(reader([event('agent/inbox/spliced', { target: 'next-turn', start: 0, inserted: [message()] }, 0)]), [{ requestId: id, checkpoint }], signal());
    assert.equal(actual!.outcome.status, 'queued'); assert.equal(actual!.nextSeq, 1);
  }
});

test('F1 byte budget yields exact cursor and consuming/attention state, resumes after JSON restoration', async () => {
  const events = [event('turn/start', { turn: 1 }, 0), event('user/message', message(), 1), event('approval/asked', { id: 'approval' }, 2),
    ...Array.from({ length: 40 }, (_, i) => heavy(i + 3)), event('approval/decided', { id: 'approval' }, 43), event('turn/end', { turn: 1, reason: { kind: 'completed' } }, 44)];
  const [first] = await derivePromptOutcomes(reader(events), [{ requestId: id }], signal());
  assert.equal(first!.caughtUp, false); assert.ok(first!.nextSeq > 3 && first!.nextSeq < 43);
  assert.equal(first!.outcome.status, 'waiting'); assert.equal(first!.turn, 1); assert.equal(first!.openTurn, 1); assert.deepEqual(first!.approvals, ['approval']);
  let offset = -1;
  const [next] = await derivePromptOutcomes({ ...reader(events), async read(o, n) { if (offset < 0) offset = o; return { events: events.slice(o, o + n) }; } }, [{ requestId: id, checkpoint: JSON.parse(JSON.stringify(first)) }], signal());
  assert.equal(offset, first!.nextSeq); assert.equal(next!.caughtUp, true); assert.equal(next!.outcome.status, 'completed');
});

test('F1 single event above byte budget is never skipped; preceding progress is returned first', async () => {
  const huge = event('session/title', { title: 'x'.repeat(16 * 1024 * 1024) }, 1);
  const events = [event('turn/start', { turn: 1 }, 0), huge];
  const [first] = await derivePromptOutcomes(reader(events), [{ requestId: id }], signal());
  assert.equal(first!.nextSeq, 1); assert.equal(first!.caughtUp, false); assert.equal(first!.openTurn, 1);
  await assert.rejects(derivePromptOutcomes(reader(events), [{ requestId: id, checkpoint: first }], signal()), (e: any) => e.code === 'unavailable');
});

test('F2 claimed but never consumed abort cannot manufacture a terminal user-stop proof', async () => {
  const events = [event('agent/inbox/spliced', { target: 'next-turn', start: 0, inserted: [message()] }, 0),
    event('turn/start', { turn: 1 }, 1), event('agent/inbox/spliced', { target: 'next-turn', start: 0, removedCount: 1, inserted: [] }, 2),
    event('turn/end', { turn: 1, reason: { kind: 'aborted', reason: { kind: 'user' } } }, 3)];
  const [actual] = await derivePromptOutcomes(reader(events), [{ requestId: id }], signal());
  assert.equal(actual!.outcome.status, 'unknown'); assert.equal(actual!.terminalReason, undefined); assert.equal(provenTerminal(actual!), false);
});

test('F2 consuming user stop pins before huge unrelated tail; repeated poll never reads', async () => {
  const events = [event('turn/start', { turn: 1 }, 0), event('user/message', message(), 1), event('turn/end', { turn: 1, reason: { kind: 'aborted', reason: { kind: 'user' } } }, 2),
    event('invalid-tail', { title: 'x'.repeat(17 * 1024 * 1024) }, 99)];
  const [first] = await derivePromptOutcomes(reader(events), [{ requestId: id }], signal());
  assert.equal(first!.outcome.status, 'rejected'); assert.equal(first!.caughtUp, true); assert.equal(first!.nextSeq, 3);
  assert.equal(provenTerminal(first!), true);
  assert.equal((first as any).terminalReason, 'user-stop');
  const [next] = await derivePromptOutcomes({ ...reader([]), async read() { throw Error('must not reread stopped turn'); } }, [{ requestId: id, checkpoint: JSON.parse(JSON.stringify(first)) }], signal());
  assert.deepEqual(next, first);
});

test('F2 stopped request stays pinned while another request continues over event budget and restart', async () => {
  const events = [event('turn/start', { turn: 1 }, 0), event('user/message', message(), 1), event('turn/end', { turn: 1, reason: { kind: 'aborted', reason: { kind: 'user' } } }, 2),
    event('turn/start', { turn: 2 }, 3), event('user/message', message(other), 4), ...Array.from({ length: 4200 }, (_, i) => event('session/title', {}, i + 5))];
  const first = await derivePromptOutcomes(reader(events), [{ requestId: id }, { requestId: other }], signal());
  assert.equal(first[0]!.caughtUp, true); assert.equal(first[0]!.nextSeq, 3); assert.equal(first[0]!.outcome.status, 'rejected');
  assert.equal(first[1]!.caughtUp, false); assert.equal(first[1]!.nextSeq, 4096);
  events.push(event('turn/end', { turn: 2, reason: { kind: 'completed' } }, events.length));
  const next = await derivePromptOutcomes(reader(events), first.map((checkpoint, i) => ({ requestId: [id, other][i]!, checkpoint: JSON.parse(JSON.stringify(checkpoint)) })), signal());
  assert.deepEqual(next[0], first[0]); assert.equal(next[1]!.outcome.status, 'completed');
});

test('F2 pre-discriminator cv4 consuming rejection upgrades in place, unlike queue discard', async () => {
  const consumed = { ...prior(3, 'failed'), consumptionVersion: 4 as const, caughtUp: false, outcome: { ...prior(3, 'failed').outcome, status: 'rejected' as const } };
  const [actual] = await derivePromptOutcomes({ ...reader([]), async read() { throw Error('do not rewind an already consumed stop'); } }, [{ requestId: id, checkpoint: consumed }], signal());
  assert.equal(actual!.nextSeq, 9000); assert.equal(actual!.caughtUp, true); assert.equal(actual!.terminalReason, 'user-stop');
  assert.equal(consumed.caughtUp, false);
  const discarded = structuredClone(consumed); delete discarded.turn; delete discarded.outcome.turnId;
  const [queue] = await derivePromptOutcomes(reader([]), [{ requestId: id, checkpoint: discarded }], signal());
  assert.equal(queue!.terminalReason, undefined); assert.equal(provenTerminal(queue!), false);
});

test('F2 queue discard at page edge is not consuming terminal and reinsertion survives', async () => {
  const events = Array.from({ length: 256 }, (_, i) => event('session/title', {}, i));
  events[0] = event('agent/inbox/spliced', { target: 'next-turn', start: 0, inserted: [message()] }, 0);
  events[255] = event('agent/inbox/spliced', { target: 'next-turn', start: 0, removedCount: 1, inserted: [], outcome: 'canceled' }, 255);
  const [discard] = await derivePromptOutcomes(reader(events), [{ requestId: id }], signal());
  assert.equal(discard!.outcome.status, 'rejected'); assert.equal(provenTerminal(discard!), false); assert.equal((discard as any).terminalReason, undefined);
  events.push(event('agent/inbox/spliced', { target: 'next-step', start: 0, inserted: [message()] }, 256));
  const [next] = await derivePromptOutcomes(reader(events), [{ requestId: id, checkpoint: JSON.parse(JSON.stringify(discard)) }], signal());
  assert.equal(next!.outcome.status, 'queued'); assert.equal(next!.pending?.target, 'next-step');
});
