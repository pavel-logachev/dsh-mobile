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
  const desktop: ((value: typeof answer) => void)[] = [];
  ctx.on('user-questions/request', () => new Promise(resolve => { desktop.push(resolve); }), { global: true });
  const bridge = createQuestionBridge(listener => ctx.on('user-questions/request', listener, { global: true, prepend: true }));
  t.after(() => bridge.close());
  const executions: Promise<unknown>[] = [];
  const execute = (callId: string, signal = new AbortController().signal) => {
    const result = tool.execute({ questions: question.questions }, { agent, callId, signal });
    executions.push(result); return result;
  };
  try {
    const first = execute('legacy-one'), second = execute('legacy-two');
    await new Promise(resolve => setImmediate(resolve));
    const pending = bridge.pending(agent.session.id);
    assert.equal(pending.length, 2, 'legacy requests omit wait but must still be answerable');
    assert.notEqual(pending[0]!.callId, pending[1]!.callId, 'opaque phone handles must not collide');
    assert.equal(bridge.pending('other-session').length, 0);
    assert.equal(bridge.answer('other-session', pending[0]!.callId, answer), false);
    assert.equal(bridge.answer(agent.session.id, pending[0]!.callId, answer), true);
    assert.equal(bridge.answer(agent.session.id, pending[0]!.callId, answer), false);
    assert.deepEqual(await first, answer);
    assert.equal(bridge.pending(agent.session.id).length, 1);
    desktop[1]!(answer); assert.deepEqual(await second, answer);
    assert.equal(bridge.pending(agent.session.id).length, 0, 'desktop winner retires the phone handle');
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
