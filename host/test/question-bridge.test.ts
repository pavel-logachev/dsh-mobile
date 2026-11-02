import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createQuestionBridge } from '../src/question-bridge.ts';
import { answerSyntax, validateAnswer, questionsOf } from '../src/phone.ts';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const question = questionsOf([{ callId: 'synthetic-call', state: 'open', questions: [{ id: 'q', header: 'Synthetic', question: 'Choose one', options: [{ label: 'A' }, { label: 'B' }] }] }])[0]!;
const answer = { answers: [{ id: 'q', selected: ['A'] }] };

// Match the installed legacy tool's public request, not a hand-built wait.callId.
test('real legacy ask_user_question exposes distinct mobile waits and returns the phone answer', { skip: !process.env.DSH_MOBILE_CORDIS_MODULE, timeout: 10000 }, async t => {
  const require = createRequire(process.env.DSH_MOBILE_CORDIS_MODULE!);
  const load = (name: string) => import(pathToFileURL(require.resolve(name)).href);
  const { Context } = await load('@deepseek-ai/cordis');
  const { AgentRegistry } = await load('@deepseek-ai/dsh-agent');
  const { UserQuestionService } = await load('@deepseek-ai/dsh-user-questions');
  const { createScope } = await load('@deepseek-ai/dsh-scope');
  const toolPlugin = await load('@deepseek-ai/dsh-tool-ask-user');
  const ctx = new Context();
  t.after(() => ctx.fiber.dispose());
  await ctx.plugin(AgentRegistry); await ctx.plugin(UserQuestionService);
  const agent: any = { id: 'synthetic-session', session: { id: 'synthetic-session' } };
  const scope = createScope(ctx, agent); agent.ctx = scope.ctx;
  const detach = ctx.get('agents').enter(agent);
  t.after(async () => { detach(); await scope.dispose(); });
  let tool: any;
  ctx.provide('tools', { register(value: any) { tool = value; return () => {}; } });
  await scope.ctx.plugin(toolPlugin);
  assert.equal(Object.hasOwn(tool.parameters, 'timeout'), false, 'exercise the legacy schema');
  const desktop: ((value: typeof answer) => void)[] = [], desktopSignals: AbortSignal[] = [];
  ctx.on('user-questions/request', (request: any) => new Promise((resolve, reject) => {
    desktop.push(resolve); desktopSignals.push(request.signal);
    request.signal.addEventListener('abort', () => reject(Object.assign(new Error('Desktop cancelled'), { code: 'ASK_ABORTED' })), { once: true });
  }), { global: true });
  const bridge = createQuestionBridge(listener => ctx.on('user-questions/request', listener, { global: true, prepend: true }));
  t.after(() => bridge.close());
  const executions: Promise<unknown>[] = [];
  const execute = (callId: string, signal = new AbortController().signal) => {
    const result = tool.execute({ questions: question.questions }, { agent, callId, signal });
    executions.push(result); return result;
  };
  try {
    const agentSignal = new AbortController().signal;
    const first = execute('legacy-one', agentSignal), second = execute('legacy-two');
    await new Promise(resolve => setImmediate(resolve));
    const pending = bridge.pending(agent.session.id);
    assert.equal(pending.length, 2, 'legacy requests omit wait but must still be answerable');
    assert.notEqual(pending[0]!.callId, pending[1]!.callId, 'opaque phone handles must not collide');
    assert.equal(bridge.pending('other-session').length, 0);
    assert.equal(bridge.answer('other-session', pending[0]!.callId, answer), false);
    assert.equal(bridge.answer(agent.session.id, pending[0]!.callId, answer), true);
    assert.equal(bridge.answer(agent.session.id, pending[0]!.callId, answer), false);
    assert.deepEqual(await first, answer);
    assert.equal(desktopSignals[0]!.aborted, true, 'phone winner must cancel the desktop waiter');
    assert.equal(agentSignal.aborted, false, 'phone answer must not abort the agent tool/turn signal');
    assert.equal(desktopSignals[1]!.aborted, false, 'another question must remain open');
    assert.equal(bridge.pending(agent.session.id).length, 1);
    desktop[1]!(answer); assert.deepEqual(await second, answer);
    assert.equal(bridge.pending(agent.session.id).length, 0, 'desktop winner retires the phone handle');
    assert.deepEqual(bridge.settled(agent.session.id), pending.map(q => ({ callId: q.callId, outcome: 'answered', answers: answer.answers })));
    assert.deepEqual(bridge.settled('other-session'), []);
    const abort = new AbortController();
    const cancelled = execute('legacy-abort', abort.signal);
    const rejected = assert.rejects(cancelled, { code: 'ASK_ABORTED' });
    await new Promise(resolve => setImmediate(resolve));
    const stale = bridge.pending(agent.session.id)[0]!;
    abort.abort(); await rejected;
    assert.equal(bridge.answer(agent.session.id, stale.callId, answer), false);
    assert.deepEqual(bridge.pending(agent.session.id), []);
  } finally {
    for (const release of desktop) release(answer);
    bridge.close(); await Promise.allSettled(executions);
  }
});

test('installed Remote gateway carries phone cancellation to its client waterfall without aborting the agent', { skip: !process.env.DSH_MOBILE_CORDIS_MODULE, timeout: 10000 }, async t => {
  const require = createRequire(process.env.DSH_MOBILE_CORDIS_MODULE!);
  const load = (name: string) => import(pathToFileURL(require.resolve(name)).href);
  const { Context } = await load('@deepseek-ai/cordis');
  const { createScope, scopeTarget } = await load('@deepseek-ai/dsh-scope');
  const { TypertGatewayService } = await load('@deepseek-ai/dsh-api-gateway');
  const remotes = await load('@deepseek-ai/dsh-api-remotes');
  const { ClientRemoteEvents } = await import(new URL('./types/client/remote-events.js', pathToFileURL(require.resolve('@deepseek-ai/dsh-api-gateway'))).href);
  const host = new Context(), client = new Context(), lifetime = new AbortController();
  t.after(async () => { lifetime.abort(); await client.fiber.dispose(); await host.fiber.dispose(); });
  // No HTTP server, live sessions or private data: exercise the installed in-process carrier.
  host.provide('typert', {}); host.provide('remoteAccess', {});
  await host.plugin(TypertGatewayService); await host.plugin(remotes);
  const gateway = host.get('typertGateway');
  const agent: any = { id: 'synthetic-session', session: { id: 'synthetic-session' } };
  const scope = createScope(host, agent); agent.ctx = scope.ctx;
  t.after(() => scope.dispose());
  client.provide('typert', { contexts: { getClient: () => ({ resolve: () => client }) } });
  const peer = { signal: lifetime.signal, principal: { kind: 'owner' }, interactions: 'answer' };
  const frames: any[] = [];
  const events = new ClientRemoteEvents(client, {
    registerGenerationSource: () => () => {},
    rpc: { call: (_path: string, endpoint: string, payload: unknown, signal: AbortSignal) => gateway.dispatchRpc(endpoint, payload, signal, peer) },
  }, async function* (endpoint: string, payload: unknown, signal: AbortSignal) {
    const source = await gateway.wireStream.open(endpoint, payload, undefined, peer, signal);
    for await (const frame of source) { frames.push(frame); yield frame; }
  });
  const ready = Promise.withResolvers();
  const pump = events.runGeneration(lifetime.signal, ready.resolve);
  t.after(async () => { lifetime.abort(); await pump; await events.dispose(); });
  await ready.promise;
  let received!: AbortSignal, desktopAnswer!: (value: typeof answer) => void;
  const opened = () => Promise.withResolvers();
  let waiting = opened(), cancelled = opened();
  events.subscribe(client, 'user-questions/request', (request: any) => {
    received = request.signal; waiting.resolve();
    return new Promise((resolve, reject) => {
      desktopAnswer = resolve;
      const abort = () => { cancelled.resolve(); reject(Object.assign(new Error('Desktop cancelled'), { code: 'ASK_ABORTED' })); };
      request.signal.addEventListener('abort', abort, { once: true });
      if (request.signal.aborted) abort();
    });
  });
  const bridge = createQuestionBridge(listener => host.on('user-questions/request', listener, { global: true, prepend: true }));
  t.after(() => bridge.close());
  for (const timed of [false, true]) {
    for (const winner of ['phone', 'early-phone', 'desktop'] as const) {
      waiting = opened(); cancelled = opened();
      const deliveredBefore = frames.filter(f => f.type === 'waterfall').length;
      const signal = new AbortController().signal;
      const request = { agent, questions: question.questions, signal, ...(timed ? { wait: { callId: 'timed-call', timed: true } } : {}) };
      const result = host.waterfall(scopeTarget(agent, agent), 'user-questions/request', request, () => Promise.reject(new Error('No answerer')));
      const callId = bridge.pending(agent.session.id)[0]!.callId;
      if (winner !== 'early-phone') await waiting.promise;
      if (winner === 'desktop') desktopAnswer(answer);
      else assert.equal(bridge.answer(agent.session.id, callId, answer), true);
      assert.deepEqual(await result, answer);
      if (winner === 'phone') {
        await cancelled.promise;
        assert.equal(received.aborted, true);
      }
      assert.equal(signal.aborted, false);
      assert.deepEqual(bridge.pending(agent.session.id), []);
      await new Promise(resolve => setImmediate(resolve));
      if (winner === 'early-phone') assert.equal(frames.filter(f => f.type === 'waterfall').length, deliveredBefore, 'already-cancelled request must not reach the GUI');
      assert.equal(request.signal, signal, 'restore the shared request only after downstream settles');
    }
  }
  assert.equal(frames.filter(f => f.type === 'waterfall').length, 4);
  assert.equal(frames.filter(f => f.type === 'cancel').length, 2);
});

test('desktop-only lifetime cleans up phone, upstream abort, close and downstream failures for legacy and timed requests', async () => {
  for (const timed of [false, true]) {
    for (const winner of ['phone', 'desktop', 'abort', 'close', 'error', 'no-provider'] as const) {
      let listener: any, desktopSignal!: AbortSignal, release!: (value: typeof answer) => void;
      const bridge = createQuestionBridge(fn => { listener = fn; return () => {}; });
      const upstream = new AbortController(), started = Promise.withResolvers();
      const request = { agent: { session: { id: 'session' } }, questions: question.questions, signal: upstream.signal, ...(timed ? { wait: { callId: 'timed-call' } } : {}) };
      const result = listener(request, async () => {
        // Like the Remote queue, downstream may not read the object immediately.
        await new Promise(resolve => setImmediate(resolve)); desktopSignal = request.signal;
        started.resolve();
        if (winner === 'error' || winner === 'no-provider') throw Object.assign(new Error('Synthetic failure'), { code: winner === 'no-provider' ? 'NO_PROVIDER' : 'DESKTOP_ERROR' });
        return new Promise((resolve, reject) => {
          release = resolve;
          desktopSignal.addEventListener('abort', () => reject(desktopSignal.reason), { once: true });
          if (desktopSignal.aborted) reject(desktopSignal.reason);
        });
      });
      const rejected = ['abort', 'close', 'error'].includes(winner) ? assert.rejects(result) : undefined;
      const callId = bridge.pending('session')[0]!.callId;
      if (winner === 'phone') bridge.answer('session', callId, answer);
      if (winner === 'abort') upstream.abort();
      if (winner === 'close') bridge.close();
      await started.promise;
      if (winner === 'desktop') release(answer);
      if (winner === 'no-provider') bridge.answer('session', callId, answer);
      if (rejected) await rejected; else assert.deepEqual(await result, answer);
      await new Promise(resolve => setImmediate(resolve));
      assert.equal(upstream.signal.aborted, winner === 'abort');
      assert.equal(desktopSignal.aborted, ['phone', 'abort', 'close'].includes(winner));
      assert.equal(request.signal, upstream.signal);
      assert.deepEqual(bridge.pending('session'), []);
      bridge.close();
    }
  }
});

test('legacy handles stay local, reject malformed requests and retire on bridge close', async () => {
  let listener: any;
  const bridge = createQuestionBridge(fn => { listener = fn; return () => {}; });
  const request = { agent: { session: { id: 'session' } }, questions: question.questions, signal: new AbortController().signal };
  let delegated = 0;
  for (const invalid of [
    { ...request, wait: {} }, { ...request, wait: { callId: '' } },
    { ...request, signal: undefined }, { ...request, signal: AbortSignal.abort() },
    { ...request, agent: undefined }, { ...request, questions: [] },
  ]) assert.deepEqual(await listener(invalid, async () => { delegated++; return answer; }), answer);
  assert.equal(delegated, 6); assert.deepEqual(bridge.pending('session'), []);
  const pending = listener(request, () => Promise.reject(Object.assign(new Error('Synthetic'), { code: 'NO_PROVIDER' })));
  const rejected = assert.rejects(pending, { code: 'unavailable' });
  const handle = bridge.pending('session')[0]!.callId;
  assert.match(handle, /^mobile-question:/);
  bridge.close(); await rejected;
  assert.equal(bridge.answer('session', handle, answer), false);
  assert.deepEqual(bridge.pending('session'), []);
});

test('legacy winner stays answered after abort and late desktop cannot overwrite it', async t => {
  let listener: any, release!: (value: typeof answer) => void;
  const bridge = createQuestionBridge(fn => { listener = fn; return () => {}; }); t.after(() => bridge.close());
  const abort = new AbortController(), desktop = new Promise<typeof answer>(resolve => { release = resolve; });
  const request = listener({ agent: { session: { id: 'session' } }, questions: question.questions, signal: abort.signal }, () => desktop);
  const callId = bridge.pending('session')[0]!.callId;
  assert.equal(bridge.answer('session', callId, answer), true);
  abort.abort(); await request;
  release({ answers: [{ id: 'q', selected: ['B'] }] }); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(bridge.settled('session'), [{ callId, outcome: 'answered', answers: answer.answers }]);
});

test('abort and close cannot acquire settled history from late resolutions', async () => {
  for (const cancel of ['abort', 'close'] as const) {
    let listener: any, release!: (value: typeof answer) => void;
    const bridge = createQuestionBridge(fn => { listener = fn; return () => {}; });
    const abort = new AbortController(), desktop = new Promise<typeof answer>(resolve => { release = resolve; });
    const request = listener({ agent: { session: { id: 'session' } }, questions: question.questions, signal: abort.signal }, () => desktop);
    const rejected = assert.rejects(request);
    if (cancel === 'abort') abort.abort(); else bridge.close();
    await rejected; release(answer); await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(bridge.settled('session'), []); bridge.close();
  }
  // A phone promise can be fulfilled before close, while its continuation has not run.
  let listener: any;
  const bridge = createQuestionBridge(fn => { listener = fn; return () => {}; });
  const request = listener({ agent: { session: { id: 'session' } }, questions: question.questions, signal: new AbortController().signal }, () => new Promise(() => {}));
  bridge.answer('session', bridge.pending('session')[0]!.callId, answer); bridge.close();
  await request; assert.deepEqual(bridge.settled('session'), []);
});

test('legacy history copies the settled whitelist without changing Unicode or newline answer values', async t => {
  let listener: any;
  const bridge = createQuestionBridge(fn => { listener = fn; return () => {}; }); t.after(() => bridge.close());
  const custom = '\uFEFFОтвет 😀\r\nLF\nCR\rвнутри\uFEFF', value = { answers: [{ id: 'q', selected: [], custom }] };
  const request = listener({ agent: { session: { id: 'session' } }, questions: question.questions, signal: new AbortController().signal }, async () => ({ ...value, private: 'not-wire-data' }));
  const callId = bridge.pending('session')[0]!.callId; await request;
  value.answers[0]!.custom = 'changed upstream';
  const history = bridge.settled('session');
  assert.deepEqual(history, [{ callId, outcome: 'answered', answers: [{ id: 'q', selected: [], custom }] }]);
  history[0]!.answers[0]!.custom = 'changed snapshot';
  assert.equal(bridge.settled('session')[0]!.answers[0]!.custom, custom);
});

test('question answer validates IDs, labels, single/multiple selection and free text', () => {
  validateAnswer(question, answerSyntax(answer));
  validateAnswer(question, answerSyntax({ answers: [{ id: 'q', selected: [] }] })); // Desktop skip representation.
  validateAnswer(question, answerSyntax({ answers: [{ id: 'q', selected: [], custom: 'Other' }] }));
  for (const invalid of [ { answers: [] }, { answers: [{ id: 'q', selected: ['missing'] }] }, { answers: [{ id: 'q', selected: ['A', 'B'] }] }, { answers: [{ id: 'wrong', selected: ['A'] }] }, { answers: [{ id: 'q', selected: ['A', 'A'] }] }, { answers: [{ id: 'q', selected: [], custom: ' ' }] } ]) assert.throws(() => validateAnswer(question, answerSyntax(invalid)), { code: 'invalid_request' });
  validateAnswer({ ...question, questions: [{ ...question.questions[0]!, multiSelect: true }] }, answerSyntax({ answers: [{ id: 'q', selected: ['A', 'B'], custom: 'Also' }] }));
  assert.throws(() => validateAnswer({ ...question, questions: [{ ...question.questions[0]!, allowFreeText: false }] }, answerSyntax({ answers: [{ id: 'q', selected: [], custom: 'No' }] })), { code: 'invalid_request' });
});

test('open question bridge races desktop, handles no answerer, expires and does not answer twice', async () => {
  let listener: any;
  const bridge = createQuestionBridge(fn => { listener = fn; return () => {}; });
  const abort = new AbortController();
  const request = { agent: { session: { id: 'session' } }, wait: { callId: question.callId }, questions: question.questions, signal: abort.signal };
  const pending = listener(request, () => Promise.reject(Object.assign(new Error('Synthetic no provider'), { code: 'NO_PROVIDER' })));
  assert.equal(bridge.pending('other').length, 0); assert.equal(bridge.pending('session').length, 1);
  assert.equal(bridge.answer('other', question.callId, answer), false);
  assert.equal(bridge.answer('session', question.callId, answer), true);
  assert.equal(bridge.answer('session', question.callId, answer), false);
  assert.deepEqual(await pending, answer);
  const desktop = listener(request, async () => answer); assert.deepEqual(await desktop, answer); assert.equal(bridge.pending('session').length, 0);
  const expired = listener(request, () => new Promise(() => {})); abort.abort(); await assert.rejects(expired, { name: 'AbortError' });
  assert.equal(bridge.answer('session', question.callId, answer), false); bridge.close();
});
