import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { PROMPTS, ANSWERS } from './deterministic.mts';

const signal = () => new AbortController().signal;
function text(record) {
  if (record?.type !== 'event') return '';
  const event = record.event;
  const message = event.type === 'user/message' ? event.data : event.data?.message;
  return message?.content?.filter(block => block.type === 'text').map(block => block.text).join('') ?? '';
}
function recordWith(snapshot, expected) { return snapshot.records.find(record => text(record) === expected); }
export async function opening(controller, sessionId) {
  const abort = new AbortController();
  const iterator = controller.follow({ address: { kind: 'session', sessionId }, assistantStream: true, maxMessages: 100 }, abort.signal)[Symbol.asyncIterator]();
  try {
    const result = await iterator.next();
    assert.equal(result.value?.type, 'snapshot');
    return result.value;
  } finally {
    abort.abort();
    await iterator.return(); // Do NOT advance past the cold opening snapshot.
  }
}
export async function waitStarted(deterministic, kind) {
  if (deterministic.started.includes(kind)) return;
  await new Promise(resolve => {
    function onStarted(value) { if (value === kind) { deterministic.events.off('started', onStarted); resolve(); } }
    deterministic.events.on('started', onStarted);
  });
}
async function consumeTurn(iterator) {
  const frames = [];
  while (true) {
    const result = await iterator.next();
    assert.equal(result.done, false, 'Follow must not disconnect unexpectedly');
    frames.push(result.value);
    if (result.value.type === 'event' && result.value.event.type === 'turn/end') return frames;
  }
}
function normalized(snapshot) {
  return {
    sessionId: snapshot.header.id,
    cursor: snapshot.cursor,
    hasMore: snapshot.hasMore,
    messages: snapshot.records.filter(record => record.type === 'event' && ['user/message', 'assistant/message'].includes(record.event.type)).map(record => ({
      role: record.event.type === 'user/message' ? 'user' : 'assistant',
      text: text(record),
      ...(record.event.type === 'user/message' ? { requestId: record.event.data.source?.rpcId } : {}),
    })),
  };
}

export async function controllerChecks(runtime, cwd) {
  const { ctx, deterministic } = runtime;
  const controller = ctx.sessionController;
  const sessionId = `session-canary-${randomUUID()}`;
  const created = await controller.create({ cwd, sessionId });
  assert.equal(created.sessionId, sessionId);
  const repeated = await controller.create({ cwd, sessionId });
  assert.equal(repeated.sessionId, sessionId);
  assert.equal(deterministic.calls, 0, 'Create is not a model invocation');
  const abort = new AbortController();
  const iterator = controller.follow({ address: { kind: 'session', sessionId }, assistantStream: true }, abort.signal)[Symbol.asyncIterator]();
  try {
    assert.equal((await iterator.next()).value.type, 'snapshot');
    const framesPromise = consumeTurn(iterator);
    const requestId = randomUUID();
    assert.deepEqual(await controller.prompt({ sessionId, requestId, mode: 'queue', content: [{ type: 'text', text: PROMPTS.live }] }, signal()), { accepted: true });
    await waitStarted(deterministic, 'live');
    assert.equal(ctx.agents.get(sessionId).status, 'running');
    assert.deepEqual(await controller.prompt({ sessionId, requestId, mode: 'queue', content: [{ type: 'text', text: PROMPTS.live }] }, signal()), { accepted: true });
    deterministic.release('live');
    const frames = await framesPromise;
    await ctx.agents.get(sessionId).whenIdle();
    assert.ok(frames.some(frame => frame.type === 'assistant-stream' && frame.frame.type === 'chunk'));
    assert.ok(frames.some(frame => text(frame) === ANSWERS.live));
    assert.equal(deterministic.calls, 1, 'Duplicate prompt must not run again');
    const snapshot = await opening(controller, sessionId);
    assert.equal(recordWith(snapshot, PROMPTS.live).event.data.source.rpcId, requestId);
  } finally { abort.abort(); await iterator.return(); }

  const disconnected = new AbortController();
  const observer = controller.follow({ address: { kind: 'session', sessionId }, assistantStream: true }, disconnected.signal)[Symbol.asyncIterator]();
  await observer.next();
  const reconnectId = randomUUID();
  await controller.prompt({ sessionId, requestId: reconnectId, mode: 'queue', content: [{ type: 'text', text: PROMPTS.reconnect }] }, signal());
  await waitStarted(deterministic, 'reconnect');
  disconnected.abort(); await observer.return();
  assert.equal(ctx.agents.get(sessionId).status, 'running', 'Disconnect observation is not cancel');
  deterministic.release('reconnect');
  await ctx.agents.get(sessionId).whenIdle();
  const reconnected = await opening(controller, sessionId);
  assert.ok(recordWith(reconnected, ANSWERS.reconnect));
  assert.equal(recordWith(reconnected, PROMPTS.reconnect).event.data.source.rpcId, reconnectId);

  const cancelId = randomUUID();
  await controller.prompt({ sessionId, requestId: cancelId, mode: 'queue', content: [{ type: 'text', text: PROMPTS.cancel }] }, signal());
  await waitStarted(deterministic, 'cancel');
  const inProgress = await opening(controller, sessionId);
  assert.ok(inProgress.assistantStream.activeAttempt, 'Reconnect carries active attempt');
  assert.deepEqual(controller.cancel({ sessionId }), { accepted: true });
  await ctx.agents.get(sessionId).whenIdle();
  assert.ok(deterministic.aborted.includes('cancel'), 'Cancellation reaches adapter AbortSignal');
  const completed = await opening(controller, sessionId);
  assert.equal(completed.assistantStream.activeAttempt, undefined);
  assert.equal(deterministic.calls, 3);
  await ctx.sessionPersistence.flush();
  assert.ok(await ctx.sessionPersistence.stat(sessionId));
  return { sessionId, checks: ['create/adopt', 'queue-prompt/requestId/dedup', 'live-durable-and-provisional-follow', 'disconnect-does-not-cancel', 'reconnect-authoritative-snapshot', 'cancel-honors-abort', 'jsonl-durability'], result: normalized(completed) };
}

export async function coldChecks(runtime, sessionId) {
  const { ctx, deterministic } = runtime;
  assert.equal(ctx.agents.get(sessionId), undefined);
  assert.equal(ctx.sessions.get(sessionId), undefined);
  const listed = await ctx.sessionController.list({}, signal());
  const row = listed.items.find(item => item.sessionId === sessionId);
  assert.ok(row);
  assert.equal(row.agentAvailable, false);
  const snapshot = await opening(ctx.sessionController, sessionId);
  const page = await ctx.sessionController.page({ address: { kind: 'session', sessionId }, throughSeq: snapshot.cursor, maxMessages: 100 }, signal());
  assert.ok(page.records.length > 0);
  assert.ok(await ctx.sessionController.projections({ sessionId }, signal()));
  assert.ok(await ctx.sessionController.inspect(sessionId, signal()));
  // Allow pending microtasks to reveal an accidental activation; never next() a cold follow.
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(ctx.agents.get(sessionId), undefined, 'Cold reads must not attach an Agent');
  assert.equal(ctx.sessions.get(sessionId), undefined, 'Cold reads must not attach a Session');
  assert.equal(deterministic.calls, 0);
  return { checks: ['cold-list', 'opening-follow-return-before-promotion', 'cold-page/projections/inspect', 'zero-activation-zero-llm'], result: normalized(snapshot) };
}

export async function adapterChecks(runtime, module, workspace, coldSessionId) {
  const { ctx, deterministic } = runtime;
  const adapter = await module.createDshAdapter({ dshVersion: runtime.version, sessionController: ctx.sessionController, agentPresets: ctx.get('agentPresets'), workspaces: [workspace], pollIntervalMs: 100, throttleMs: 10 });
  try {
    assert.deepEqual(await adapter.listPresets(signal()), [], 'Absent preset registry must be an explicit empty catalog');
    const listed = await adapter.listSessions(signal());
    assert.ok(listed.some(session => session.id === coldSessionId && session.workspaceId === workspace.id));
    const cold = await adapter.snapshot(coldSessionId, signal());
    assert.ok(cold.messages.some(message => message.text === ANSWERS.live));
    const coldAbort = new AbortController();
    const coldWatch = adapter.watch(coldSessionId, coldAbort.signal)[Symbol.asyncIterator]();
    await coldWatch.next(); coldAbort.abort(); await coldWatch.return();
    assert.equal(ctx.agents.get(coldSessionId), undefined, 'Mobile cold snapshot/watch must not promote');
    assert.equal(deterministic.calls, 0);
    const requestId = randomUUID();
    const created = await adapter.createSession({ workspaceId: workspace.id, requestId }, signal(), workspace.id);
    const repeated = await adapter.createSession({ workspaceId: workspace.id, requestId }, signal(), workspace.id);
    assert.equal(repeated.sessionId, created.sessionId);
    const watchAbort = new AbortController();
    const watcher = adapter.watch(created.sessionId, watchAbort.signal)[Symbol.asyncIterator]();
    try {
      assert.equal((await watcher.next()).value.session.id, created.sessionId);
      const promptId = randomUUID();
      await adapter.prompt(created.sessionId, PROMPTS.mobile, promptId, signal(), workspace.id);
      await waitStarted(deterministic, 'mobile');
      let provisional;
      do { provisional = (await watcher.next()).value; }
      while (!provisional.messages.some(message => message.role === 'assistant' && message.provisional));
      assert.equal(provisional.session.running, true);
      watchAbort.abort(); await watcher.return();
      assert.equal(ctx.agents.get(created.sessionId).status, 'running');
      deterministic.release('mobile');
      await ctx.agents.get(created.sessionId).whenIdle();
      const after = await adapter.snapshot(created.sessionId, signal());
      assert.ok(after.messages.some(message => message.text === ANSWERS.mobile && !message.provisional));
      assert.equal(after.messages.find(message => message.role === 'user').requestId, promptId);
      assert.equal(after.session.running, false);
      const reconnectAbort = new AbortController();
      const reconnect = adapter.watch(created.sessionId, reconnectAbort.signal)[Symbol.asyncIterator]();
      const replacement = (await reconnect.next()).value;
      reconnectAbort.abort(); await reconnect.return();
      assert.deepEqual(replacement.messages, after.messages);

      await adapter.prompt(created.sessionId, PROMPTS.mobileCancel, randomUUID(), signal(), workspace.id);
      await waitStarted(deterministic, 'mobileCancel');
      const cancelling = await adapter.snapshot(created.sessionId, signal());
      assert.equal(cancelling.session.running, true);
      await adapter.cancel(created.sessionId, signal(), cancelling.cursor, workspace.id);
      await ctx.agents.get(created.sessionId).whenIdle();
      assert.ok(deterministic.aborted.includes('mobileCancel'));
      const cancelled = await adapter.snapshot(created.sessionId, signal());
      assert.equal(cancelled.session.running, false);
      await ctx.sessionPersistence.flush();
      return { checks: ['mobile-absent-presets/list-sessions', 'mobile-cold-safe', 'mobile-create/adopt', 'mobile-prompt/requestId', 'mobile-provisional-snapshot', 'mobile-disconnect/reconnect', 'mobile-cursor-guarded-cancel'], result: cancelled };
    } finally { watchAbort.abort(); await watcher.return(); }
  } finally { await adapter.dispose(); }
}
