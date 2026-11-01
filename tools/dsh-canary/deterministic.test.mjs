import test from 'node:test';
import assert from 'node:assert/strict';
import { createDeterministicAdapter, PROVIDER, MODEL, PROMPTS, ANSWERS, ANDROID_PROMPT } from './deterministic.mts';

const message = (text, kind = 'user') => ({ role: 'user', source: { kind }, content: [{ type: 'text', text }] });
const options = messages => ({ provider: PROVIDER, model: MODEL, tools: [], messages });

test('exact synthetic user prompt is selected despite later runtime context, including child input provenance', async () => {
  const adapter = createDeterministicAdapter(class {});
  for (const [messages, expected] of [
    [[message(PROMPTS.live)], ANSWERS.live],
    [[message(PROMPTS.live), message('Synthetic directory context', 'runtime-context')], ANSWERS.live],
    [[message(PROMPTS.live), message(PROMPTS.cancel), message('Synthetic directory context', 'runtime-context')], ANSWERS.cancel],
    [[message(PROMPTS.live), message(PROMPTS.cancel, 'runtime-context')], ANSWERS.live],
  ]) {
    const stream = adapter.stream(options(messages))[Symbol.asyncIterator]();
    try {
      assert.equal((await stream.next()).value.type, 'block-start');
      assert.equal((await stream.next()).value.text, expected.slice(0, 20), 'Select the latest real user input, not the latest admitted/context text');
    } finally { await stream.return(); }
  }
  assert.equal(adapter.calls, 4);
  assert.equal(adapter.active, 0);
});

test('prompt admission remains exact and provenance-aware at text boundaries', async () => {
  const adapter = createDeterministicAdapter(class {});
  for (const text of ['', '\uFEFF' + PROMPTS.live, PROMPTS.live + '\uFEFF', PROMPTS.live + '\n', PROMPTS.live + '\r\n', PROMPTS.live + '\r', 'Привет 😀', PROMPTS.live.replace('CANARY_LIVE', 'CANARY_ＬＩＶＥ'), ANDROID_PROMPT]) {
    await assert.rejects(adapter.stream(options([message(text)])).next(), /Only exact explicitly synthetic prompts/);
  }
  for (const messages of [[], [message(PROMPTS.live, 'runtime-context')], [{ role: 'user', content: [{ type: 'text', text: PROMPTS.live }] }], [message(PROMPTS.live), message('not admitted')]]) {
    await assert.rejects(adapter.stream(options(messages)).next(), /Only exact explicitly synthetic prompts/);
  }
  assert.equal(adapter.calls, 0);
  assert.equal(adapter.active, 0);
});
