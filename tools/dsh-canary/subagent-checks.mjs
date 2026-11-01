import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { PROMPTS, ANSWERS } from './deterministic.mts';

function assertCompleted(result, answer) {
  assert.equal(result.stopReason, 'completed');
  assert.equal(result.output.filter(block => block.type === 'text').map(block => block.text).join(''), answer);
}
async function waitActive(deterministic, kind, run) {
  // The preceding controller checks have already used these prompt kinds;
  // started[] is historical, whereas controls identifies a currently held turn.
  if (deterministic.controls.has(kind)) return;
  let started;
  try {
    await Promise.race([
      new Promise(resolve => { started = value => { if (value === kind) resolve(); }; deterministic.events.on('started', started); }),
      run.result.then(result => { throw new Error(`Synthetic ${kind} child settled before model start: ${result.stopReason}`); }),
    ]);
  } finally { deterministic.events.off('started', started); }
}

/** Real in-process fork/spawn providers and deterministic turns, no external model. */
export async function subagentChecks(runtime, loader, projectRoot, cwd, observe = async () => {}) {
  const { ctx, deterministic } = runtime;
  const fibers = [], runs = [];
  const callsBefore = deterministic.calls;
  const { foldSubagentDescriptor } = await loader.load('dsh-subagent');
  // Caller delivery preserves the idle main Agent: no model-facing settlement
  // notification is injected into it. The official manager still owns children.
  async function start(provider, parentAgent, kind, signal) {
    const label = `Synthetic canary ${provider}/${kind}`;
    const run = await ctx.subagents.startActivation({ provider, label, signal, delivery: 'caller',
      request: { parent: parentAgent, prompt: [{ type: 'text', text: PROMPTS[kind] }], maxDepth: 4 } });
    runs.push(run);
    await waitActive(deterministic, kind, run);
    const agent = ctx.agents.get(run.childId);
    assert.ok(agent, 'Published local activation has a live Agent');
    assert.ok(run.messageId, 'Local activation accepts an initial message');
    assert.equal(agent.session.header.origin, 'subagent');
    assert.equal(agent.session.header.parentSession, parentAgent.id);
    const descriptor = foldSubagentDescriptor(agent.session.snapshotEvents());
    assert.equal(descriptor?.mode, 'continuable');
    assert.equal(descriptor?.provider, provider);
    assert.equal(descriptor?.label, label);
    return { run, agent };
  }
  let parent, adapter;
  const active = new AbortController();
  try {
    for (const [id, config] of [['dsh-subagent', { maxDepth: 4, maxActiveSubagents: 8 }], ['dsh-subagent-fork-in-process', {}], ['dsh-subagent-spawn-in-process', {}]]) {
      const module = await loader.load(id), fiber = ctx.plugin(module.default ?? module, config);
      fibers.push(fiber); await fiber.await(); assert.equal(fiber.state, 2);
    }
    const { runningSubagentCounts } = await import(pathToFileURL(path.join(projectRoot, 'host', 'dist', 'running-subagents.js')).href);
    const module = await import(pathToFileURL(path.join(projectRoot, 'host', 'dist', 'dsh-adapter.js')).href);
    adapter = await module.createDshAdapter({ dshVersion: runtime.version, sessionController: ctx.sessionController, workspaces: [{ id: 'subagent-canary', name: 'Synthetic child canary', path: cwd }], pollIntervalMs: 10, throttleMs: 1,
      sessionStatus: id => ({ running: ctx.agents.get(id)?.status === 'running', updatedAt: ctx.sessions.get(id)?.header.createdAt ?? 0 }),
      runningSubagentCounts: () => runningSubagentCounts(ctx.agents.list(), ctx.sessions.list()),
    });
    parent = await ctx.agents.create({ sessionId: `session-subagent-parent-${randomUUID()}`, meta: { cwd }, agentOptions: ctx.agentDefaultModel.currentSelection() });
    // No prompt on the main Agent: its status remains idle throughout child work.
    const { run: first } = await start('fork', parent.agent, 'cancel', active.signal);
    const watch = adapter.watch(parent.agent.id, active.signal)[Symbol.asyncIterator]();
    try {
      const initial = (await watch.next()).value;
      assert.equal(initial.session.running, false); assert.equal(initial.activity, 'idle'); assert.equal(initial.runningSubagents, 1);
      await assert.rejects(adapter.snapshot(first.childId, new AbortController().signal), { code: 'not_found' }, 'real child address remains hidden');
      await observe(parent.agent.id, 1, first.childId);
      const next = watch.next(); deterministic.release('cancel'); assertCompleted(await first.result, ANSWERS.cancel);
      assert.equal(ctx.agents.get(first.childId), undefined, 'Settled activation releases its Agent');
      const settled = (await next).value;
      assert.equal(settled.runningSubagents, 0); assert.equal(settled.session.running, false);
      await observe(parent.agent.id, 0);
    } finally { active.abort(); await watch.return(); }
    const signal = new AbortController().signal;
    const { run: child, agent: childAgent } = await start('spawn', parent.agent, 'live', signal);
    const { run: nested } = await start('fork', childAgent, 'reconnect', signal);
    const summary = (await adapter.listSessions(signal)).find(item => item.id === parent.agent.id);
    assert.equal(summary.runningSubagents, 2); assert.equal(summary.running, false);
    await observe(parent.agent.id, 2, nested.childId);
    assert.equal((await adapter.listSessions(signal)).some(item => item.id === child.childId || item.id === nested.childId), false, 'delegated children stay hidden');
    deterministic.release('reconnect'); assertCompleted(await nested.result, ANSWERS.reconnect);
    assert.equal(ctx.agents.get(nested.childId), undefined);
    assert.equal((await adapter.snapshot(parent.agent.id, signal)).runningSubagents, 1);
    await observe(parent.agent.id, 1);
    deterministic.release('live'); assertCompleted(await child.result, ANSWERS.live);
    assert.equal(ctx.agents.get(child.childId), undefined);
    assert.equal((await adapter.snapshot(parent.agent.id, signal)).runningSubagents, 0);
    await observe(parent.agent.id, 0);
    assert.equal(parent.agent.status, 'idle');
    assert.equal(deterministic.calls - callsBefore, 3, 'Only the three synthetic children invoke the model');
    assert.equal(deterministic.active, 0);
    await ctx.sessionPersistence.flush();
    return { checks: ['real-fork-origin/parent-link', 'idle-main-with-child-one', 'settled-child-zero-via-watch', 'nested-fork-count-two', 'subagents-hidden-in-list', 'main-status/stop-semantics-unchanged'], deterministicCalls: deterministic.calls - callsBefore };
  } finally {
    active.abort(); await adapter?.dispose();
    for (const run of runs.toReversed()) await run.dispose();
    await parent?.dispose();
    for (const fiber of fibers.toReversed()) await fiber.dispose();
  }
}
